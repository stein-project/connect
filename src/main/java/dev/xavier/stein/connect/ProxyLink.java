/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import dev.xavier.stein.loader.api.Lang;

/**
 * Liga a conexao do jogo ao {@link Socks5}: e o que a ponte pergunta na hora de conectar (pela API Net do Loader).
 *
 * <p>Com o proxy ligado, {@link #address} manda conectar nele e guarda o destino de verdade; {@link #wrap} devolve um
 * inicializador que poe o tunel na frente e so depois deixa o do jogo montar a pilha de pacotes dele. Com o proxy
 * desligado, os dois devolvem o que o jogo ja faria.
 *
 * <p><b>Cada conexao tem o seu destino.</b> O jogo chama {@code handler(...)} e depois {@code connect(...)} na mesma
 * thread, e o tunel so e montado depois, na thread da rede. Antes o destino ficava num campo so, e a lista de
 * servidores, que manda o ping de todos ao mesmo tempo (varias threads), fazia todo tunel ir para o ultimo destino
 * gravado: com o proxy ligado, um servidor aparecia no lugar de todos. Agora o {@code wrap} cria o destino daquela
 * conexao e o deixa com a thread; o {@code address}, logo em seguida na mesma thread, o preenche.
 */
final class ProxyLink implements dev.xavier.stein.loader.api.Net.Proxy {

    private final ConnectConfig config;

    /** O destino de uma conexao, preenchido pelo address e lido pelo tunel dela. */
    private static final class Target {
        volatile InetAddress host;
        volatile int port;
    }

    /** O destino criado pelo wrap desta thread, esperando o address da mesma tentativa. */
    private final ThreadLocal<Target> pending = new ThreadLocal<>();

    ProxyLink(ConnectConfig config) {
        this.config = config;
    }

    private boolean on() {
        return config.proxyOn && !config.proxyHost.isBlank() && config.proxyPort > 0;
    }

    @Override
    public Object address(Object destination, int port) {
        Target t = pending.get();
        pending.remove();
        if (!on() || t == null || !(destination instanceof InetAddress a)) {
            return null;
        }
        t.host = a;
        t.port = port;
        return new InetSocketAddress(config.proxyHost.trim(), config.proxyPort);
    }

    @Override
    public Object wrap(Object initializer) {
        pending.remove();
        if (!on() || !(initializer instanceof ChannelHandler game)) {
            return initializer;
        }
        Target target = new Target();
        pending.set(target);
        String user = config.proxyUser;
        String pass = config.proxyPass;
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                InetAddress to = target.host;
                if (to != null) {
                    ch.pipeline().addLast("stein-socks", new Socks5(to, target.port, user, pass));
                }
                // O inicializador do jogo roda em seguida e se tira da fila sozinho.
                ch.pipeline().addLast(game);
            }
        };
    }

    // ---- teste do proxy, fora da thread do jogo ----

    /** Resultado do ultimo teste, para a linha do Painel. */
    private volatile String result = "";
    private volatile boolean testing;
    /** O ultimo teste passou e o proxy ainda nao foi para a lista dos usados (quem grava e a thread do jogo). */
    private volatile boolean passed;

    /** Se um teste passou desde a ultima pergunta. */
    boolean takePassed() {
        boolean p = passed;
        passed = false;
        return p;
    }

    /** A linha do Painel: o resultado traduzido (verde quando passou), ou a falha de rede pelo nome dela. */
    String status() {
        if (testing) {
            return "...";
        }
        String r = result;
        if (r.isEmpty()) {
            return "";
        }
        if (r.startsWith("steinconnect.")) {
            return (r.equals(OK) ? "§a" : "§c") + Lang.tr(r);
        }
        return "§c" + r;
    }

    /**
     * Abre o proxy e negocia ate o CONNECT, para dizer na hora se ele responde — proxy morto tem que falhar com
     * motivo, nao com tempo esgotado no meio de uma entrada em servidor. O destino e o proprio proxy: basta saber
     * que ele aceita e autentica.
     */
    void test() {
        if (testing || !on()) {
            return;
        }
        testing = true;
        result = "";
        String proxyHost = config.proxyHost.trim();
        int proxyPort = config.proxyPort;
        String user = config.proxyUser;
        String pass = config.proxyPass;
        Thread t = new Thread(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(proxyHost, proxyPort), 6000);
                s.setSoTimeout(6000);
                result = handshake(s, user, pass);
                passed = result.equals(OK);
            } catch (IOException e) {
                result = failure(e);
            } finally {
                testing = false;
            }
        }, "stein-connect-proxy-test");
        t.setDaemon(true);
        t.start();
    }

    private static String handshake(Socket s, String user, String pass) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        boolean auth = user != null && !user.isEmpty();
        out.write(auth ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        out.flush();
        byte[] r = read(in, 2);
        if (r[0] != 5) {
            return "steinconnect.proxy.notSocks";
        }
        if (r[1] == 2) {
            if (!auth) {
                return "steinconnect.proxy.needsAuth";
            }
            byte[] u = user.getBytes(StandardCharsets.UTF_8);
            byte[] p = pass == null ? new byte[0] : pass.getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = 1;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg);
            out.flush();
            if (read(in, 2)[1] != 0) {
                return "steinconnect.proxy.badAuth";
            }
        } else if (r[1] != 0) {
            return "steinconnect.proxy.refused";
        }
        return OK;
    }

    private static final String OK = "steinconnect.proxy.ok";

    /** A falha de rede do teste como chave do .lang; a que nao tem traducao aparece pelo nome da classe. */
    private static String failure(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return "steinconnect.proxy.timeout";
        }
        if (e instanceof UnknownHostException) {
            return "steinconnect.proxy.unknownHost";
        }
        if (e instanceof ConnectException) {
            return "steinconnect.proxy.unreachable";
        }
        if (e instanceof EOFException) {
            return "steinconnect.proxy.closed";
        }
        return e.getClass().getSimpleName();
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int k = in.read(b, got, n - got);
            if (k < 0) {
                throw new EOFException("proxy fechou a conexao");
            }
            got += k;
        }
        return b;
    }
}

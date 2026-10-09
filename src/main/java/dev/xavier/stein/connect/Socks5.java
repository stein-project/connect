/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import dev.xavier.stein.loader.api.Lang;

/**
 * O tunel SOCKS5, escrito aqui (o Netty do 1.8.9 e de 2014 e nao tem o handler de proxy; trocar a biblioteca inteira
 * sairia mais caro — ver a secao "Netty" do README).
 *
 * <p>Fica na frente dos handlers do jogo: conversa com o proxy nos bytes crus e, so quando o tunel esta de pe, sai da
 * fila e deixa o jogo falar. Enquanto isso <b>segura o que o jogo escreve</b>: ele manda o handshake assim que o
 * connect volta, e nesse instante o tunel ainda esta sendo negociado. O {@code channelActive} passa na hora, porque
 * e nele que o NetworkManager do jogo guarda o canal — segurando-o, o jogo nem conseguia mostrar o motivo de uma
 * recusa do proxy e ficava parado em "entrando".
 *
 * <p>Sequencia (RFC 1928 e 1929): saudacao com os metodos aceitos, autenticacao de usuario e senha se o proxy pedir,
 * e o pedido de CONNECT para o endereco do servidor. Erro em qualquer passo vira excecao no canal, entao o jogo
 * mostra o motivo na tela de desconexao em vez de esperar o tempo esgotar.
 */
final class Socks5 extends ChannelDuplexHandler {

    private static final byte VERSION = 5;
    private static final byte NO_AUTH = 0;
    private static final byte USER_PASS = 2;
    private static final byte CONNECT = 1;
    private static final byte IPV4 = 1;
    private static final byte DOMAIN = 3;
    private static final byte IPV6 = 4;

    /** Passo: 0 saudacao enviada, 1 autenticacao enviada, 2 CONNECT enviado, 3 tunel de pe. */
    private int step;
    private final InetAddress host;
    private final int port;
    private final String user;
    private final String pass;
    /** O que o jogo escreveu antes de o tunel ficar pronto. */
    private final List<Object[]> held = new ArrayList<>();
    private ByteBuf inbox;

    Socks5(InetAddress host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(VERSION);
        if (user.isEmpty()) {
            b.writeByte(1).writeByte(NO_AUTH);
        } else {
            b.writeByte(2).writeByte(NO_AUTH).writeByte(USER_PASS);
        }
        ctx.writeAndFlush(b);
        // O jogo precisa saber do canal desde ja: e no channelActive que o NetworkManager dele guarda o canal, e sem
        // isso ele nao consegue nem fechar com motivo se o proxy recusar. O que ele escrever fica seguro aqui.
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (step == 3 || !(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (inbox == null) {
            inbox = ctx.alloc().buffer();
        }
        inbox.writeBytes(in);
        in.release();
        while (step < 3 && advance(ctx)) {
            // cada passo consome o que chegou dele
        }
        if (step == 3 && inbox != null) {
            // O que veio junto com a resposta do proxy ja e do servidor.
            ByteBuf rest = inbox;
            inbox = null;
            if (rest.isReadable()) {
                ctx.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }
    }

    /** Le um passo da conversa; devolve false quando ainda falta byte. */
    private boolean advance(ChannelHandlerContext ctx) throws Exception {
        switch (step) {
            case 0 -> {
                if (inbox.readableBytes() < 2) {
                    return false;
                }
                byte version = inbox.readByte();
                byte method = inbox.readByte();
                if (version != VERSION) {
                    return fail(ctx, "steinconnect.proxy.notSocks");
                }
                if (method == NO_AUTH) {
                    request(ctx);
                } else if (method == USER_PASS && !user.isEmpty()) {
                    auth(ctx);
                } else {
                    return fail(ctx, method == (byte) 0xFF ? "steinconnect.proxy.noMethod"
                            : "steinconnect.proxy.needsAuth");
                }
                return true;
            }
            case 1 -> {
                if (inbox.readableBytes() < 2) {
                    return false;
                }
                inbox.readByte(); // versao da autenticacao
                if (inbox.readByte() != 0) {
                    return fail(ctx, "steinconnect.proxy.badAuth");
                }
                request(ctx);
                return true;
            }
            default -> {
                if (inbox.readableBytes() < 5) {
                    return false;
                }
                int mark = inbox.readerIndex();
                inbox.readByte(); // versao
                byte reply = inbox.readByte();
                inbox.readByte(); // reservado
                byte type = inbox.readByte();
                int rest = type == IPV4 ? 4 : type == IPV6 ? 16 : type == DOMAIN ? 1 + inbox.getUnsignedByte(inbox.readerIndex()) : -1;
                if (rest < 0) {
                    return fail(ctx, "steinconnect.proxy.badReply");
                }
                if (inbox.readableBytes() < rest + 2) {
                    inbox.readerIndex(mark);
                    return false;
                }
                inbox.skipBytes(rest + 2);
                if (reply != 0) {
                    return fail(ctx, reason(reply));
                }
                done(ctx);
                return true;
            }
        }
    }

    private void auth(ChannelHandlerContext ctx) {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = pass.getBytes(StandardCharsets.UTF_8);
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(1).writeByte(u.length).writeBytes(u).writeByte(p.length).writeBytes(p);
        ctx.writeAndFlush(b);
        step = 1;
    }

    private void request(ChannelHandlerContext ctx) {
        byte[] addr = host.getAddress();
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(VERSION).writeByte(CONNECT).writeByte(0);
        b.writeByte(addr.length == 16 ? IPV6 : IPV4).writeBytes(addr);
        b.writeShort(port);
        ctx.writeAndFlush(b);
        step = 2;
    }

    /** Tunel de pe: sai da fila, o jogo recebe o channelActive e o que ele escreveu sai agora. */
    private void done(ChannelHandlerContext ctx) throws Exception {
        step = 3;
        dev.xavier.stein.loader.api.Log.of("steinconnect").info("tunel do proxy aberto ate " + host.getHostAddress() + ":" + port);
        ctx.pipeline().remove(this);
        for (Object[] w : held) {
            ctx.write(w[0], (ChannelPromise) w[1]);
        }
        held.clear();
        ctx.flush();
    }

    /** Falha com o motivo traduzido ({@code key} e a chave do .lang): e o texto que o jogo mostra na desconexao. */
    private boolean fail(ChannelHandlerContext ctx, String key) {
        step = 3;
        IOException cause = new IOException("Proxy: " + Lang.tr(key));
        // A excecao vai antes de falhar o que estava guardado: quem chega primeiro e o motivo que o jogo mostra.
        ctx.fireExceptionCaught(cause);
        discard(cause);
        ctx.close();
        return false;
    }

    /** A chave do .lang para o codigo de recusa do CONNECT (RFC 1928, secao 6). */
    private static String reason(byte reply) {
        return switch (reply) {
            case 2 -> "steinconnect.proxy.notAllowed";
            case 3 -> "steinconnect.proxy.networkUnreachable";
            case 4 -> "steinconnect.proxy.hostUnreachable";
            case 5 -> "steinconnect.proxy.serverRefused";
            case 6 -> "steinconnect.proxy.timeout";
            default -> "steinconnect.proxy.refused";
        };
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (step < 3) {
            held.add(new Object[] {msg, promise});
            return;
        }
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (step >= 3) {
            ctx.flush();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (step < 3) {
            discard(new IOException("Proxy: " + Lang.tr("steinconnect.proxy.closed")));
        }
    }

    private void discard(IOException cause) {
        if (inbox != null) {
            inbox.release();
            inbox = null;
        }
        for (Object[] w : held) {
            if (w[0] instanceof ByteBuf b) {
                b.release();
            }
            ((ChannelPromise) w[1]).tryFailure(cause);
        }
        held.clear();
    }
}

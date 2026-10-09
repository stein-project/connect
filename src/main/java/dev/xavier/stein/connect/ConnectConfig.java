/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Secrets;
import dev.xavier.stein.loader.api.Tasks;

/**
 * config/steinconnect.json: os nicks ja usados, o reconectar e o proxy.
 *
 * <p>Na primeira vez le as configuracoes dos dois mods que viraram este (config/steinaltmanager.json e
 * config/steinreconnect.json) e grava tudo junto — ninguem perde os nicks nem a espera escolhida. Os arquivos
 * antigos ficam onde estao; so nao sao mais lidos depois disso.
 *
 * <p><b>As senhas dos proxies nao vao para o JSON</b>: ficam no cofre do mod (config/steinconnect.secrets, protegido
 * pelo Windows), a do proxy de agora em {@link #CURRENT} e a de cada proxy guardado em {@link #SAVED} + o rotulo dele
 * ("usuario@host:porta", unico na lista). Arquivo da versao antiga, com a senha em texto puro, e migrado ao carregar:
 * a senha vai para o cofre e o JSON e regravado sem ela.
 *
 * <p>O arquivo e salvo um segundo depois da ultima mudanca ({@link #save}), e nao a cada tecla de um campo; e sempre
 * por um temporario trocado de uma vez, para uma queda no meio nao deixar um JSON pela metade.
 */
final class ConnectConfig {

    /** Quantos proxies a lista guarda (os nicks nao tem limite: tem quem use dezenas de contas). */
    static final int MAX_PROXIES = 6;
    /** Chave da senha do proxy de agora no cofre. */
    static final String CURRENT = "proxy.current";
    /** Prefixo da senha de cada proxy guardado no cofre, seguido do rotulo dele. */
    static final String SAVED = "proxy.saved.";

    /** Nicks offline guardados, o ultimo acrescentado primeiro; sem limite. */
    final List<String> recent = new ArrayList<>();
    /** Reconectar sozinho quando a contagem chega a zero. */
    boolean auto = true;
    /** Segundos de contagem na tela de desconexao. */
    int delaySeconds = 10;

    /** Conectar pelo proxy SOCKS5 em vez de direto. */
    boolean proxyOn = false;
    String proxyHost = "";
    int proxyPort = 1080;
    String proxyUser = "";
    /** So na memoria e no cofre; nunca no JSON. */
    String proxyPass = "";
    /** Proxies ja usados (conectou ou passou no teste), do mais recente para o mais antigo: um clique volta a ele. */
    final List<SavedProxy> proxies = new ArrayList<>();

    /** Um proxy da lista: endereco, porta e a conta dele. */
    static final class SavedProxy {
        String host = "";
        int port = 1080;
        String user = "";
        /** So na memoria e no cofre; nunca no JSON. */
        String pass = "";

        /** Como aparece na lista: "usuario@host:porta" (a senha nunca aparece). */
        String label() {
            return (user.isEmpty() ? "" : user + "@") + host + ":" + port;
        }
    }

    /** Guarda o proxy de agora no topo da lista (o mesmo endereco e usuario nao se repete). */
    boolean rememberProxy() {
        if (proxyHost.isBlank()) {
            return false;
        }
        SavedProxy p = new SavedProxy();
        p.host = proxyHost.trim();
        p.port = proxyPort;
        p.user = proxyUser;
        p.pass = proxyPass;
        String label = p.label();
        if (!proxies.isEmpty() && proxies.get(0).label().equals(label) && proxies.get(0).pass.equals(p.pass)) {
            return false;
        }
        proxies.removeIf(s -> s.label().equals(label));
        proxies.add(0, p);
        while (proxies.size() > MAX_PROXIES) {
            proxies.remove(proxies.size() - 1);
        }
        return true;
    }

    private File file;
    private Secrets secrets;
    private Log log;
    /** O que esta no cofre agora (chave -> senha): so o que mudou e regravado, e o que saiu da lista e apagado. */
    private final Map<String, String> stored = new HashMap<>();
    /** Havia senha em texto puro no JSON: foi para o cofre, e o arquivo tem de ser regravado sem ela. */
    private boolean migrated;
    /** Um salvamento ja marcado para daqui a pouco. */
    private volatile boolean pending;

    /** {@code configFile}: config/steinconnect.json (ModContext.configFile); os dos mods antigos ficam ao lado. */
    void load(File configFile, Secrets secrets, Log log) {
        this.secrets = secrets;
        this.log = log;
        file = configFile;
        File dir = configFile.getParentFile();
        if (file.isFile()) {
            read(file, true, true);
            if (migrated) {
                log.info("senhas de proxy tiradas do " + file.getName() + " e guardadas no cofre");
                saveNow();
            }
            return;
        }
        // Primeira vez: o que era do Alt Manager e do Reconnect.
        read(new File(dir, "steinaltmanager.json"), true, false);
        read(new File(dir, "steinreconnect.json"), false, true);
        saveNow();
    }

    private void read(File f, boolean nicks, boolean reconnect) {
        if (!f.isFile()) {
            return;
        }
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            JsonObject o = new JsonParser().parse(r).getAsJsonObject();
            if (nicks && o.has("recent") && o.get("recent").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("recent")) {
                    String nick = e.getAsString();
                    if (Accounts.valid(nick) && recent.stream().noneMatch(nick::equalsIgnoreCase)) {
                        recent.add(nick);
                    }
                }
            }
            if (reconnect) {
                if (o.has("auto")) {
                    auto = o.get("auto").getAsBoolean();
                }
                if (o.has("delaySeconds")) {
                    delaySeconds = Math.max(1, Math.min(60, o.get("delaySeconds").getAsInt()));
                }
            }
            if (o.has("proxyOn")) {
                proxyOn = o.get("proxyOn").getAsBoolean();
            }
            proxyHost = string(o, "proxyHost", proxyHost);
            proxyUser = string(o, "proxyUser", proxyUser);
            proxyPass = password(CURRENT, string(o, "proxyPass", ""));
            if (o.has("proxyPort")) {
                proxyPort = Math.max(1, Math.min(65535, o.get("proxyPort").getAsInt()));
            }
            if (o.has("proxies") && o.get("proxies").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("proxies")) {
                    if (!e.isJsonObject() || proxies.size() >= MAX_PROXIES) {
                        continue;
                    }
                    JsonObject j = e.getAsJsonObject();
                    SavedProxy p = new SavedProxy();
                    p.host = string(j, "host", "").trim();
                    p.port = j.has("port") ? Math.max(1, Math.min(65535, j.get("port").getAsInt())) : 1080;
                    p.user = string(j, "user", "");
                    if (!p.host.isEmpty()) {
                        p.pass = password(SAVED + p.label(), string(j, "pass", ""));
                        proxies.add(p);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn(f + " ilegivel, seguindo com o padrao: " + e);
        }
    }

    /**
     * A senha de uma chave: a que ainda esta em texto puro no JSON (versao antiga) vai para o cofre; sem ela, a do
     * cofre. Cofre que nao abre (arquivo copiado de outra conta ou maquina) da senha vazia: o jogador digita de novo.
     */
    private String password(String key, String plain) {
        if (!plain.isEmpty()) {
            secrets.put(key, plain);
            stored.put(key, plain);
            migrated = true;
            return plain;
        }
        String s = secrets.get(key);
        if (s == null || s.isEmpty()) {
            return "";
        }
        stored.put(key, s);
        return s;
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    /**
     * Salva daqui a um segundo, juntando as mudancas desse meio tempo (cada tecla de um campo) num salvamento so. Na
     * thread do jogo, como todo mundo que mexe na configuracao.
     */
    void save() {
        if (file == null || pending) {
            return;
        }
        pending = true;
        Tasks.later(20, () -> {
            if (pending) {
                saveNow();
            }
        });
    }

    /** Salva o que ainda esperava o segundo: o jogo fechando nao pode perder a ultima tecla. */
    void flush() {
        if (pending) {
            saveNow();
        }
    }

    synchronized void saveNow() {
        pending = false;
        if (file == null) {
            return;
        }
        saveSecrets();
        JsonArray list = new JsonArray();
        for (String n : recent) {
            list.add(new JsonPrimitive(n));
        }
        JsonObject o = new JsonObject();
        o.add("recent", list);
        o.addProperty("auto", auto);
        o.addProperty("delaySeconds", delaySeconds);
        o.addProperty("proxyOn", proxyOn);
        o.addProperty("proxyHost", proxyHost);
        o.addProperty("proxyPort", proxyPort);
        o.addProperty("proxyUser", proxyUser);
        JsonArray saved = new JsonArray();
        for (SavedProxy p : proxies) {
            JsonObject j = new JsonObject();
            j.addProperty("host", p.host);
            j.addProperty("port", p.port);
            j.addProperty("user", p.user);
            saved.add(j);
        }
        o.add("proxies", saved);
        file.getParentFile().mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(new GsonBuilder().setPrettyPrinting().create().toJson(o));
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("nao salvou " + file + ": " + e);
        }
    }

    /** O cofre igual as senhas de agora: grava so a que mudou e apaga a de proxy que saiu da lista. */
    private void saveSecrets() {
        if (secrets == null) {
            return;
        }
        Map<String, String> now = new HashMap<>();
        if (!proxyPass.isEmpty()) {
            now.put(CURRENT, proxyPass);
        }
        for (SavedProxy p : proxies) {
            if (!p.pass.isEmpty()) {
                now.put(SAVED + p.label(), p.pass);
            }
        }
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!e.getValue().equals(stored.get(e.getKey()))) {
                secrets.put(e.getKey(), e.getValue());
            }
        }
        Set<String> gone = new HashSet<>(stored.keySet());
        gone.removeAll(now.keySet());
        for (String k : gone) {
            secrets.remove(k);
        }
        stored.clear();
        stored.putAll(now);
    }
}

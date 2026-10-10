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

import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Secrets;
import dev.xavier.stein.loader.api.Tasks;

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
import java.util.List;
import java.util.Map;

/**
 * {@code config/steinconnect.json}: the offline nicks, reconnect and the proxy.
 *
 * <p>The first time, it reads the settings of the two mods that became this one ({@code
 * config/steinaltmanager.json} and {@code config/steinreconnect.json}) and writes them together, so
 * nobody loses their nicks or chosen delay. The old files stay where they are; they are just not
 * read again.
 *
 * <p><b>Proxy passwords never go to the JSON</b>: they live in the mod's vault ({@link
 * ProxyVault}). A file from an old version, with the password in plain text, is migrated on load:
 * the password goes to the vault and the JSON is written again without it.
 *
 * <p>The file is saved one second after the last change ({@link #save}), not on every keystroke in
 * a field, and always through a temporary file swapped in at once, so a crash midway does not leave
 * half a JSON. The JSON keys are what players already have: do not rename them.
 *
 * <p>Kept as its own reader (not ModConfig) for the migrations above and the passwords kept out of
 * the file.
 */
final class ConnectConfig {

    /**
     * How many proxies the list keeps (nicks have no limit: some people use dozens of accounts).
     */
    static final int MAX_PROXIES = 6;

    /** Saved offline nicks, the last one added first; no limit. */
    final List<String> recent = new ArrayList<>();

    /** Reconnect on its own when the countdown reaches zero. */
    boolean auto = true;

    /** Seconds of countdown on the disconnect screen. */
    int delaySeconds = 10;

    /** Connect through the SOCKS5 proxy instead of directly. */
    boolean proxyOn = false;

    String proxyHost = "";
    int proxyPort = 1080;
    String proxyUser = "";

    /** Only in memory and in the vault; never in the JSON. */
    String proxyPass = "";

    /** Proxies already used (connected or passed the test), newest first: one click goes back. */
    final List<SavedProxy> proxies = new ArrayList<>();

    private File file;
    private ProxyVault vault;
    private Log log;

    /** A save already scheduled for a moment from now. */
    private volatile boolean pending;

    /**
     * Puts the current proxy at the top of the list (the same address and user is not repeated).
     */
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
        if (!proxies.isEmpty()
                && proxies.get(0).label().equals(label)
                && proxies.get(0).pass.equals(p.pass)) {
            return false;
        }
        proxies.removeIf(s -> s.label().equals(label));
        proxies.add(0, p);
        while (proxies.size() > MAX_PROXIES) {
            proxies.remove(proxies.size() - 1);
        }
        return true;
    }

    /**
     * Loads {@code configFile} (config/steinconnect.json, from ModContext.configFile); the old
     * mods' files sit next to it.
     */
    void load(File configFile, Secrets secrets, Log log) {
        this.vault = new ProxyVault(secrets);
        this.log = log;
        file = configFile;
        File dir = configFile.getParentFile();
        if (file.isFile()) {
            read(file, true, true);
            if (vault.migrated()) {
                log.info("proxy passwords moved out of " + file.getName() + " into the vault");
                saveNow();
            }
            return;
        }
        // First time: what belonged to Alt Manager and Reconnect.
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
            if (nicks) {
                readNicks(o);
            }
            if (reconnect) {
                if (o.has("auto")) {
                    auto = o.get("auto").getAsBoolean();
                }
                if (o.has("delaySeconds")) {
                    delaySeconds = Math.max(1, Math.min(60, o.get("delaySeconds").getAsInt()));
                }
            }
            readProxy(o);
        } catch (IOException | RuntimeException e) {
            log.warn(f + " unreadable, going on with the defaults: " + e);
        }
    }

    private void readNicks(JsonObject o) {
        if (!o.has("recent") || !o.get("recent").isJsonArray()) {
            return;
        }
        for (JsonElement e : o.getAsJsonArray("recent")) {
            String nick = e.getAsString();
            if (Accounts.valid(nick) && recent.stream().noneMatch(nick::equalsIgnoreCase)) {
                recent.add(nick);
            }
        }
    }

    private void readProxy(JsonObject o) {
        if (o.has("proxyOn")) {
            proxyOn = o.get("proxyOn").getAsBoolean();
        }
        proxyHost = string(o, "proxyHost", proxyHost);
        proxyUser = string(o, "proxyUser", proxyUser);
        proxyPass = vault.password(ProxyVault.CURRENT, string(o, "proxyPass", ""));
        if (o.has("proxyPort")) {
            proxyPort = port(o.get("proxyPort").getAsInt());
        }
        if (!o.has("proxies") || !o.get("proxies").isJsonArray()) {
            return;
        }
        for (JsonElement e : o.getAsJsonArray("proxies")) {
            if (!e.isJsonObject() || proxies.size() >= MAX_PROXIES) {
                continue;
            }
            JsonObject j = e.getAsJsonObject();
            SavedProxy p = new SavedProxy();
            p.host = string(j, "host", "").trim();
            p.port = j.has("port") ? port(j.get("port").getAsInt()) : 1080;
            p.user = string(j, "user", "");
            if (!p.host.isEmpty()) {
                p.pass = vault.password(ProxyVault.SAVED + p.label(), string(j, "pass", ""));
                proxies.add(p);
            }
        }
    }

    private static int port(int value) {
        return Math.max(1, Math.min(65535, value));
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    /**
     * Saves one second from now, merging the changes in the meantime (each keystroke in a field)
     * into a single save. On the game thread, like everything that changes the settings.
     */
    void save() {
        if (file == null || pending) {
            return;
        }
        pending = true;
        Tasks.later(
                20,
                () -> {
                    if (pending) {
                        saveNow();
                    }
                });
    }

    /** Saves what was still waiting for its second: closing the game must not lose the last key. */
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
        vault.sync(passwords());
        file.getParentFile().mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try {
            try (Writer w =
                    new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(new GsonBuilder().setPrettyPrinting().create().toJson(toJson()));
            }
            try {
                Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("could not save " + file + ": " + e);
        }
    }

    /** The passwords as the vault must hold them now (key -> password). */
    private Map<String, String> passwords() {
        Map<String, String> now = new HashMap<>();
        if (!proxyPass.isEmpty()) {
            now.put(ProxyVault.CURRENT, proxyPass);
        }
        for (SavedProxy p : proxies) {
            if (!p.pass.isEmpty()) {
                now.put(ProxyVault.SAVED + p.label(), p.pass);
            }
        }
        return now;
    }

    /** Everything but the passwords. */
    private JsonObject toJson() {
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
        return o;
    }
}

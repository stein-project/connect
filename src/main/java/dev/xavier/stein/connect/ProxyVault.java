/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Secrets;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The proxy passwords, kept in the mod's vault ({@code config/steinconnect.secrets}, see {@link
 * Secrets}) and never in the JSON: the current proxy's under {@link #CURRENT}, each saved proxy's
 * under {@link #SAVED} + its label.
 *
 * <p>Never log a value from here.
 */
final class ProxyVault {

    /** Key of the current proxy's password. */
    static final String CURRENT = "proxy.current";

    /** Prefix of each saved proxy's password, followed by its label. */
    static final String SAVED = "proxy.saved.";

    private final Secrets secrets;

    /** What is in the vault now (key -> password): only what changed is written again. */
    private final Map<String, String> stored = new HashMap<>();

    /**
     * A plain-text password was found in the JSON: it moved here, and the JSON must be rewritten.
     */
    private boolean migrated;

    ProxyVault(Secrets secrets) {
        this.secrets = secrets;
    }

    /**
     * The password under {@code key}. One still in plain text in the JSON (an old version) moves to
     * the vault; without it, the vault's. A vault that does not open (copied from another account
     * or machine) gives an empty password: the player types it again.
     */
    String password(String key, String plain) {
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

    /** Whether a plain-text password was moved out of the JSON while loading. */
    boolean migrated() {
        return migrated;
    }

    /**
     * Makes the vault match {@code now} (key -> password, no empty values): writes only what
     * changed and deletes what is gone (a proxy removed from the list).
     */
    void sync(Map<String, String> now) {
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

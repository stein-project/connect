/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Server;

import java.util.List;

/**
 * Back to the server after a drop. Came from Stein Reconnect.
 *
 * <p>On the disconnect screen (drop, kick, server full), a "Reconnect (10s)" button with a
 * countdown: at zero it reconnects on its own (if the option is on), and a click reconnects at
 * once. The server is the last one the player joined, remembered on connecting, so it also works
 * for a kick still during login.
 *
 * <p>The Loader's Server.lastAddress() only changes once actually in the world; on the connecting
 * screen {@link Server#connectingAddress} already is the server being tried. Joining is the
 * Loader's job ({@link Server#connect}).
 */
final class Reconnect {

    /** An id far from the game's (the disconnect screen only has 0). */
    static final int BUTTON = 0x5E100;

    private final ConnectConfig config;
    private final Log log;

    /** The last server the player joined (or tried to). */
    private String lastIp;

    private int attempts;

    /** The current disconnect screen, its button and the end of the countdown (0 = none). */
    private Object screen;

    private Object button;
    private long deadline;

    Reconnect(ConnectConfig config, Log log) {
        this.config = config;
        this.log = log;
    }

    void joined() {
        // The connection's address, which also works when the game joined from the command line
        // (without ServerData).
        String a = Server.address();
        if (!a.isEmpty()) {
            lastIp = a;
        }
        attempts = 0;
    }

    /** Returns true when it put the button on the screen. */
    boolean guiInit(Object s, List<Object> buttons) {
        Screens.Kind kind = Screens.kind(s);
        if (kind == Screens.Kind.CONNECTING) {
            remember();
            return false;
        }
        if (kind != Screens.Kind.DISCONNECTED || lastIp == null) {
            return false;
        }
        Object back = Screens.findButton(buttons, 0);
        // Right below the game's "Back to server list", with the same width.
        int x = back != null ? Screens.buttonX(back) : Screens.width(s) / 2 - 100;
        int y = (back != null ? Screens.buttonY(back) : Screens.height(s) / 2) + 24;
        button = Screens.button(BUTTON, x, y, 200, 20, "");
        buttons.add(button);
        if (s != screen) { // a new screen, not just the window being resized
            screen = s;
            deadline = config.auto ? now() + config.delaySeconds * 1000L : 0L;
        }
        label();
        return true;
    }

    boolean guiAction(Object s, Object clicked) {
        if (s == screen && Screens.buttonId(clicked) == BUTTON) {
            reconnect();
            return true;
        }
        return false;
    }

    void tick() {
        if (screen == null) {
            return;
        }
        if (Screens.current() != screen) { // left the disconnect screen
            screen = null;
            button = null;
            deadline = 0L;
            return;
        }
        if (deadline > 0L && now() >= deadline) {
            reconnect();
            return;
        }
        label();
    }

    private void label() {
        if (button == null) {
            return;
        }
        if (deadline > 0L) {
            long left = Math.max(1L, (deadline - now() + 999L) / 1000L);
            Screens.setLabel(button, Lang.tr("steinconnect.reconnect.in", left));
        } else {
            Screens.setLabel(button, Lang.tr("steinconnect.reconnect"));
        }
    }

    /** On the connecting screen, before joining: the server it is trying. */
    private void remember() {
        String a = Server.connectingAddress();
        if (!a.isEmpty()) {
            lastIp = a;
        }
    }

    private void reconnect() {
        attempts++;
        screen = null;
        button = null;
        deadline = 0L;
        log.info("reconnecting to " + lastIp + " (attempt " + attempts + ")");
        Server.connect(lastIp);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}

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
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Net;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Panel;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.SteinMod;

import java.util.List;

/**
 * Connect: everything around joining a server, in place of the Alt Manager and Reconnect mods,
 * which became this one.
 *
 * <ul>
 *   <li><b>Account</b> ({@link Accounts}): play with an offline nick and go back to the launcher's
 *       account. The server list has a button with the current account, which opens this mod's page
 *       on the Panel;
 *   <li><b>Proxy</b> ({@link ProxyLink}): join servers through a SOCKS5 proxy;
 *   <li><b>Reconnect</b> ({@link Reconnect}): on the disconnect screen, a button with a countdown
 *       that goes back to the last server.
 * </ul>
 *
 * <p>One configuration ({@code config/steinconnect.json}, {@link ConnectConfig}), which the first
 * time reads the two old mods'. Everything is set on the Connect page ({@link ConnectPage}), opened
 * by the server list's button (it does not show on the in-game Panel).
 */
public final class SteinConnect implements SteinMod {

    /** The mod's name, which is its page's name on the Panel. */
    static final String NAME = "Connect";

    /** An id far from the server list's. */
    private static final int BUTTON = 0x5E200;

    private final ConnectConfig config = new ConnectConfig();
    private final ModContext ctx = ModContext.of(this);
    private final Log log = ctx.log();
    private final Accounts accounts = new Accounts(config, log);
    private final Reconnect reconnect = new Reconnect(config, log);
    private final ProxyLink proxy = new ProxyLink(config, log);
    private final ProxyTest proxyTest = new ProxyTest(config);
    private final ConnectPage page = new ConnectPage(config, accounts, proxyTest);

    @Override
    public void afterStartGame() {
        config.load(ctx.configFile(), ctx.secrets(), log);
        Net.set(proxy);
        log.info(
                "account "
                        + accounts.launcherName()
                        + ", "
                        + config.recent.size()
                        + " nick(s) on the list");
    }

    /** The settings are saved a second after the last change: closing the game first saves now. */
    @Override
    public void onShutdown() {
        config.flush();
    }

    @Override
    public void onJoinGame() {
        reconnect.joined();
        // Joined a server through the proxy: it works, and goes on the used list.
        if (config.proxyOn && config.rememberProxy()) {
            config.save();
        }
    }

    @Override
    public void onTickEnd() {
        reconnect.tick();
        // The proxy test runs on another thread; this one writes the list and the file.
        if (proxyTest.takePassed() && config.rememberProxy()) {
            config.save();
        }
    }

    @Override
    public void onGuiInit(Object screen, List<Object> buttons) {
        if (reconnect.guiInit(screen, buttons)) {
            return;
        }
        if (Screens.kind(screen) == Screens.Kind.MULTIPLAYER) {
            // The mod's button at the top right of the server list: account and proxy are set from
            // here, before joining, which is when it matters (with a screen open the game does not
            // deliver the Panel key).
            int x = Screens.width(screen) - 124;
            buttons.add(
                    Screens.button(
                            BUTTON,
                            x,
                            4,
                            120,
                            20,
                            Lang.tr("steinconnect.button", accounts.name())));
        }
    }

    @Override
    public boolean onGuiAction(Object screen, Object button) {
        if (reconnect.guiAction(screen, button)) {
            return true;
        }
        if (Screens.kind(screen) == Screens.Kind.MULTIPLAYER
                && Screens.buttonId(button) == BUTTON) {
            // Only the Connect page: from the server list the player sees account, reconnect and
            // proxy, and none of the other mods; they are choosing how to join, and Esc goes back
            // to the list.
            Panel.openOnly(screen, NAME);
            return true;
        }
        return false;
    }

    @Override
    public void onPage(Page page) {
        this.page.build(page);
    }
}

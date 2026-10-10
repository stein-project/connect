/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Panel;

import java.util.ArrayList;
import java.util.List;

/**
 * The Connect page, opened by the server list's button: account, proxy and reconnect.
 *
 * <p>It is where the player chooses <b>how</b> to join, so it lives on the server list and not on
 * the in-game Panel: with a game in progress the account cannot change. Accounts and proxies are
 * real lists: the whole row picks and the bin removes, two different gestures, so nobody deletes
 * the account they wanted to use. Once an account is picked, the page closes and goes back to the
 * server list, which is what the player came to do.
 */
final class ConnectPage {

    private static final int[] DELAYS = {3, 5, 10, 15, 30, 60};

    private final ConnectConfig config;
    private final Accounts accounts;
    private final ProxyTest proxyTest;

    /** The nick being typed on the page (it does not go to the file). */
    private String pending = "";

    ConnectPage(ConnectConfig config, Accounts accounts, ProxyTest proxyTest) {
        this.config = config;
        this.accounts = accounts;
        this.proxyTest = proxyTest;
    }

    void build(Page page) {
        // In a game nothing here helps (no account or proxy change with the connection open): with
        // an empty page, the game's Panel does not even list Connect. It only exists through the
        // server list's button.
        if (Entities.self() != null) {
            return;
        }
        page.icon("link");
        accountSection(page);
        proxySection(page);
        reconnectSection(page);
    }

    private void accountSection(Page page) {
        page.section(tr("steinconnect.accounts"));
        // The field and the add button come before the list: with many accounts the list grows
        // downwards, and adding one does not mean scrolling to its end.
        page.option(new Option.Text(tr("steinconnect.nick"), "", () -> pending, v -> pending = v));
        page.option(
                new Option(tr("steinconnect.add")) {
                    @Override
                    public String value() {
                        String nick = pending.trim();
                        return nick.isEmpty() || Accounts.valid(nick)
                                ? ""
                                : "§c" + tr("steinconnect.invalid");
                    }

                    @Override
                    public void click(boolean forward) {
                        use(pending.trim());
                    }
                });
        page.rows(this::accountRows, "");
        page.restore(
                () -> { // the launcher's account, and no saved nicks
                    accounts.restore();
                    config.recent.clear();
                    pending = "";
                    config.save();
                });
    }

    private void proxySection(Page page) {
        page.section(tr("steinconnect.proxySection"));
        page.option(
                Option.toggle(
                        tr("steinconnect.proxyOn"),
                        () -> config.proxyOn,
                        v -> {
                            config.proxyOn = v;
                            config.save();
                        }));
        page.rows(this::proxyRows, tr("steinconnect.noProxies"));
        proxyFields(page);
        page.restore(
                () -> { // off, no address or account, and no saved proxies
                    ConnectConfig d = new ConnectConfig();
                    config.proxyOn = d.proxyOn;
                    config.proxyHost = d.proxyHost;
                    config.proxyPort = d.proxyPort;
                    config.proxyUser = d.proxyUser;
                    config.proxyPass = d.proxyPass;
                    config.proxies.clear();
                    config.save();
                });
    }

    private void reconnectSection(Page page) {
        page.section(tr("steinconnect.reconnectSection"));
        page.option(
                Option.toggle(
                        tr("steinconnect.auto"),
                        () -> config.auto,
                        v -> {
                            config.auto = v;
                            config.save();
                        }));
        page.option(
                Option.cycle(
                                tr("steinconnect.delay"),
                                delayNames(),
                                this::delayIndex,
                                i -> {
                                    config.delaySeconds = DELAYS[i];
                                    config.save();
                                })
                        .advanced());
        page.restore(
                () -> {
                    ConnectConfig d = new ConnectConfig();
                    config.auto = d.auto;
                    config.delaySeconds = d.delaySeconds;
                    config.save();
                });
    }

    /**
     * The accounts: the launcher's first (it cannot be removed), then the offline nicks already
     * used. Each one's detail is what it is (verified Microsoft, offline), and the current one is
     * marked.
     */
    private List<Page.Item> accountRows() {
        List<Page.Item> out = new ArrayList<>();
        boolean onLauncher = accounts.onLauncher();
        out.add(
                new Page.Item(
                        accounts.launcherName(),
                        accounts.launcherKind(),
                        onLauncher,
                        () -> {
                            accounts.restore();
                            Panel.close();
                        },
                        null));
        String now = accounts.name();
        for (String nick : new ArrayList<>(config.recent)) {
            boolean current = !onLauncher && nick.equalsIgnoreCase(now);
            out.add(
                    new Page.Item(
                            nick,
                            tr("steinconnect.offline"),
                            current,
                            () -> use(nick),
                            () -> {
                                config.recent.removeIf(n -> n.equalsIgnoreCase(nick));
                                config.save();
                            }));
        }
        return out;
    }

    /** The saved proxies: the row makes that proxy the one in use, the bin removes it. */
    private List<Page.Item> proxyRows() {
        List<Page.Item> out = new ArrayList<>();
        for (SavedProxy p : new ArrayList<>(config.proxies)) {
            boolean current =
                    config.proxyOn
                            && p.host.equals(config.proxyHost)
                            && p.port == config.proxyPort
                            && p.user.equals(config.proxyUser);
            out.add(
                    new Page.Item(
                            p.label(),
                            current ? tr("steinconnect.inUse") : "",
                            current,
                            () -> {
                                useProxy(p.label());
                                config.proxyOn = true;
                                config.save();
                            },
                            () -> {
                                config.proxies.removeIf(s -> s.label().equals(p.label()));
                                config.save();
                            }));
        }
        return out;
    }

    /** Plays as the nick and goes back to the server list: with the account picked, it is done. */
    private void use(String nick) {
        if (!Accounts.valid(nick)) {
            return; // the add row already says what is wrong
        }
        accounts.useNick(nick);
        pending = "";
        Panel.close();
    }

    /** The fields of a new proxy, and the buttons that test and save what is in them. */
    private void proxyFields(Page page) {
        page.option(
                new Option.Text(
                        tr("steinconnect.proxyHost"),
                        tr("steinconnect.proxyHost.hint"),
                        () ->
                                config.proxyHost.isEmpty()
                                        ? ""
                                        : config.proxyHost + ":" + config.proxyPort,
                        this::setHost));
        // Username and password sit under "More options": a public proxy asks for neither, and
        // whoever needs them knows. Declared as Option, not Option.Text, because advanced() is
        // Option's.
        Option user =
                new Option.Text(
                        tr("steinconnect.proxyUser"),
                        "",
                        () -> config.proxyUser,
                        v -> {
                            config.proxyUser = v;
                            config.save();
                        });
        page.option(user.advanced());
        Option pass =
                new Option.Text(
                        tr("steinconnect.proxyPass"),
                        "",
                        () -> config.proxyPass,
                        v -> {
                            config.proxyPass = v;
                            config.save();
                        },
                        true);
        page.option(pass.advanced());

        page.option(
                new Option(tr("steinconnect.proxyTest")) {
                    @Override
                    public String value() {
                        return proxyTest.status();
                    }

                    @Override
                    public void click(boolean forward) {
                        proxyTest.start();
                    }
                });
        page.option(
                new Option(tr("steinconnect.addProxy")) {
                    @Override
                    public String value() {
                        return config.proxyHost.isEmpty()
                                ? "§7" + tr("steinconnect.proxyHost")
                                : "";
                    }

                    @Override
                    public void click(boolean forward) {
                        if (config.rememberProxy()) {
                            config.save();
                        }
                    }
                });
    }

    /** Goes back to a proxy from the used list. */
    private void useProxy(String label) {
        for (SavedProxy p : config.proxies) {
            if (p.label().equals(label)) {
                config.proxyHost = p.host;
                config.proxyPort = p.port;
                config.proxyUser = p.user;
                config.proxyPass = p.pass;
                config.save();
                return;
            }
        }
    }

    /** "host:port" in a single field: what comes after the colon is the port, if it is a number. */
    private void setHost(String text) {
        String v = text.trim();
        int colon = v.lastIndexOf(':');
        if (colon > 0) {
            try {
                config.proxyPort =
                        Math.max(
                                1,
                                Math.min(65535, Integer.parseInt(v.substring(colon + 1).trim())));
                v = v.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // still typing the port
            }
        }
        config.proxyHost = v.trim();
        config.save();
    }

    private static String[] delayNames() {
        String[] out = new String[DELAYS.length];
        for (int i = 0; i < DELAYS.length; i++) {
            out[i] = Lang.tr("steinconnect.seconds", DELAYS[i]);
        }
        return out;
    }

    private int delayIndex() {
        int best = 0;
        for (int i = 0; i < DELAYS.length; i++) {
            if (Math.abs(DELAYS[i] - config.delaySeconds)
                    < Math.abs(DELAYS[best] - config.delaySeconds)) {
                best = i;
            }
        }
        return best;
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}

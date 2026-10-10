/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Account;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;

/**
 * The account the game joins servers with. Came from Stein Alt Manager.
 *
 * <p>An offline nick is a session without an account (the nick's offline UUID, no token): it only
 * joins servers without authentication, and on one with authentication the game itself refuses.
 * Going back restores the session the launcher handed over; the game always starts in it. The
 * switch, the launcher's session and the check with Microsoft belong to the Loader ({@link
 * Account}): this mod never sees a token.
 */
final class Accounts {

    private final ConnectConfig config;
    private final Log log;

    Accounts(ConnectConfig config, Log log) {
        this.config = config;
        this.log = log;
    }

    /** The current session's nick. */
    String name() {
        return Account.name();
    }

    String launcherName() {
        return Account.launcherName();
    }

    boolean onLauncher() {
        return Account.isLauncher();
    }

    /**
     * The kind of the launcher's account, for display. <b>It only says Microsoft after
     * checking</b>: having a token is not enough (a launcher without an account sends any). Until
     * the answer comes, "verifying".
     */
    String launcherKind() {
        // The launcher's, even with a nick in use: the Loader checks and keeps both.
        Account.Kind k = Account.launcherKind();
        if (k == Account.Kind.OFFLINE) {
            return Lang.tr("steinconnect.offline");
        }
        if (k == Account.Kind.MICROSOFT) {
            return "§a" + Lang.tr("steinconnect.microsoft");
        }
        if (k == Account.Kind.NOT_MICROSOFT) {
            return "§e" + Lang.tr("steinconnect.notMicrosoft");
        }
        if (k == Account.Kind.UNVERIFIED) {
            return "§7" + Lang.tr("steinconnect.unverified");
        }
        return "§7" + Lang.tr("steinconnect.checking");
    }

    /**
     * 3 to 16 letters, digits or _: stricter than Players.isValidNick (1 to 16), as it always was
     * here.
     */
    static boolean valid(String nick) {
        return nick != null && nick.matches("[A-Za-z0-9_]{3,16}");
    }

    /**
     * Plays as the nick (offline session) and keeps it on the list. The list has no limit, and an
     * account already on it stays in place: with dozens of accounts, the one just used jumping to
     * the top reshuffled the list on every click. Only a new one goes to the top.
     */
    void useNick(String nick) {
        Account.useOffline(nick);
        if (config.recent.stream().noneMatch(nick::equalsIgnoreCase)) {
            config.recent.add(0, nick);
        }
        config.save();
        log.info("offline nick: " + nick);
    }

    void restore() {
        Account.restoreLauncher();
        log.info("back to the launcher account: " + Account.launcherName());
    }
}

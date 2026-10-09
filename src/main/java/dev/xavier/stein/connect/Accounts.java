/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Account;
import dev.xavier.stein.loader.api.Log;

/**
 * A conta com que o jogo entra no servidor. Veio do Stein Alt Manager.
 *
 * <p>Nick offline e uma sessao sem conta (o UUID offline do nick, sem token): so entra em servidor sem
 * autenticacao — num servidor com autenticacao o proprio jogo recusa. Voltar restaura a sessao que o launcher
 * entregou; o jogo sempre abre nela. A troca, a sessao do launcher e a conferencia com a Microsoft sao do Loader
 * ({@link Account}): o mod nao ve o token.
 */
final class Accounts {

    private final ConnectConfig config;
    private final Log log;

    Accounts(ConnectConfig config, Log log) {
        this.config = config;
        this.log = log;
    }

    /** O nick da sessao de agora. */
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
     * O tipo da conta do launcher, para mostrar. <b>So diz Microsoft depois de conferir</b>: ter um token nao basta
     * (launcher sem conta manda um qualquer). Enquanto a resposta nao chega, "verificando".
     */
    String launcherKind() {
        // A do launcher, mesmo com um nick em uso: o Loader confere e guarda as duas.
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

    /** 3 a 16 letras, numeros ou _: mais estrito que o Players.isValidNick (1 a 16), como sempre foi aqui. */
    static boolean valid(String nick) {
        return nick != null && nick.matches("[A-Za-z0-9_]{3,16}");
    }

    /**
     * Entra como o nick (sessao offline) e guarda na lista. A lista nao tem limite, e uma conta que ja esta nela fica
     * no lugar: com dezenas de contas, a que acabou de ser usada pular para o topo baguncava a lista a cada clique.
     * So a nova entra no topo.
     */
    void useNick(String nick) {
        Account.useOffline(nick);
        if (config.recent.stream().noneMatch(nick::equalsIgnoreCase)) {
            config.recent.add(0, nick);
        }
        config.save();
        log.info("nick offline: " + nick);
    }

    void restore() {
        Account.restoreLauncher();
        log.info("de volta a conta do launcher: " + Account.launcherName());
    }
}

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import java.util.List;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Server;

/**
 * Voltar ao servidor depois de uma queda. Veio do Stein Reconnect.
 *
 * <p>Na tela de desconexao (queda, kick, servidor cheio), um botao "Reconectar (10s)" com contagem regressiva: ao
 * chegar a zero reconecta sozinho (se a opcao estiver ligada), e o clique reconecta na hora. O servidor e o ultimo
 * em que o jogador entrou — guardado ao conectar, entao vale tambem para kick ainda no login.
 *
 * <p>O Server.lastAddress() do Loader so muda ao entrar de fato no mundo; na tela de conexao vale o
 * {@link Server#connectingAddress}, que ja e o servidor tentado. Quem entra no servidor e o Loader
 * ({@link Server#connect}).
 */
final class Reconnect {

    /** Id longe dos do jogo (a tela de desconexao so tem o 0). */
    static final int BUTTON = 0x5E100;

    private final ConnectConfig config;
    private final Log log;

    /** Ultimo servidor em que o jogador entrou (ou tentou entrar). */
    private String lastIp;
    private int attempts;

    /** Tela de desconexao atual, o botao dela e o fim da contagem (0 = sem contagem). */
    private Object screen;
    private Object button;
    private long deadline;

    Reconnect(ConnectConfig config, Log log) {
        this.config = config;
        this.log = log;
    }

    void joined() {
        // O endereco da conexao, que vale tambem quando o jogo entrou pela linha de comando (sem ServerData).
        String a = Server.address();
        if (!a.isEmpty()) {
            lastIp = a;
        }
        attempts = 0;
    }

    /** Devolve true quando pos o botao na tela. */
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
        // Logo abaixo do "Voltar a lista de servidores" do jogo, da mesma largura.
        int x = back != null ? Screens.buttonX(back) : Screens.width(s) / 2 - 100;
        int y = (back != null ? Screens.buttonY(back) : Screens.height(s) / 2) + 24;
        button = Screens.button(BUTTON, x, y, 200, 20, "");
        buttons.add(button);
        if (s != screen) { // tela nova, nao so a janela mudando de tamanho
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
        if (Screens.current() != screen) { // saiu da tela de desconexao
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

    /** Na tela de conexao, antes de entrar: o servidor que ela esta tentando. */
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
        log.info("reconectando em " + lastIp + " (tentativa " + attempts + ")");
        Server.connect(lastIp);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}

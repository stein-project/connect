/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import java.util.ArrayList;
import java.util.List;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Panel;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.SteinMod;

/**
 * Connect: tudo que cerca a entrada num servidor, no lugar dos mods Alt Manager e Reconnect, que viraram este.
 *
 * <ul>
 *   <li><b>Conta</b> ({@link Accounts}): entrar com um nick offline e voltar para a conta do launcher. Na lista de
 *       servidores ha um botao com a conta de agora, que abre esta pagina no Painel;
 *   <li><b>Reconectar</b> ({@link Reconnect}): na tela de desconexao, um botao com contagem regressiva que volta ao
 *       ultimo servidor.
 * </ul>
 *
 * <p>Uma configuracao so (config/steinconnect.json), que na primeira vez le as dos dois mods antigos. Tudo se mexe
 * pela pagina Connect, que abre pelo botao da lista de servidores (no Painel da partida ela nao aparece).
 *
 * <p>Escrito com os nomes legiveis do MCP (os do SDK); o tools/build-mods.sh traduz para os do jogo.
 */
public final class SteinConnect implements SteinMod {

    /** Nome do mod, que e o nome da pagina dele no Painel. */
    static final String NAME = "Connect";
    /** Id longe dos da lista de servidores. */
    private static final int BUTTON = 0x5E200;
    private static final int[] DELAYS = {3, 5, 10, 15, 30, 60};

    private final ConnectConfig config = new ConnectConfig();
    private final ModContext ctx = ModContext.of(this);
    private final Log log = ctx.log();
    private final Accounts accounts = new Accounts(config, log);
    private final Reconnect reconnect = new Reconnect(config, log);
    private final ProxyLink proxy = new ProxyLink(config);
    /** Nick sendo digitado no Painel (nao vai para o arquivo). */
    private String pending = "";

    @Override
    public void afterStartGame() {
        // O grupo de threads de rede, que antes era criado aqui, agora nasce no Loader, antes dos mods.
        config.load(ctx.configFile(), ctx.secrets(), log);
        dev.xavier.stein.loader.api.Net.set(proxy);
        log.info("conta " + accounts.launcherName() + ", " + config.recent.size() + " nick(s) na lista");
    }

    /** A configuracao salva um segundo depois da ultima mudanca: fechando o jogo antes disso, salva na saida. */
    @Override
    public void onShutdown() {
        config.flush();
    }

    @Override
    public void onJoinGame() {
        reconnect.joined();
        // Entrou num servidor pelo proxy: ele funciona, e vai para a lista dos usados.
        if (config.proxyOn && config.rememberProxy()) {
            config.save();
        }
    }

    @Override
    public void onTickEnd() {
        reconnect.tick();
        // O teste do proxy roda noutra thread; quem grava a lista e o arquivo e esta.
        if (proxy.takePassed() && config.rememberProxy()) {
            config.save();
        }
    }

    @Override
    public void onGuiInit(Object screen, List<Object> buttons) {
        if (reconnect.guiInit(screen, buttons)) {
            return;
        }
        if (Screens.kind(screen) == Screens.Kind.MULTIPLAYER) {
            // Botao do mod no canto superior direito da lista de servidores: e daqui que se mexe em conta e proxy
            // antes de entrar, que e quando importa (com tela aberta o jogo nao entrega a tecla do Painel).
            int x = Screens.width(screen) - 124;
            buttons.add(Screens.button(BUTTON, x, 4, 120, 20, Lang.tr("steinconnect.button", accounts.name())));
        }
    }

    @Override
    public boolean onGuiAction(Object screen, Object button) {
        if (reconnect.guiAction(screen, button)) {
            return true;
        }
        if (Screens.kind(screen) == Screens.Kind.MULTIPLAYER && Screens.buttonId(button) == BUTTON) {
            // So a pagina do Connect: da lista de servidores o jogador ve conta, reconexao e proxy, e nada dos outros
            // mods — la ele esta escolhendo como entrar, e Esc volta para a lista.
            Panel.openOnly(screen, NAME);
            return true;
        }
        return false;
    }

    /**
     * A tela do Connect, aberta pelo botao da lista de servidores: conta, proxy e reconexao.
     *
     * <p>E onde se escolhe <b>como</b> entrar, e por isso vive na lista de servidores e nao no Painel da partida —
     * com a partida em andamento nao da para trocar de conta. Conta e proxy sao listas de verdade: a linha inteira
     * escolhe e a lixeira tira, dois gestos diferentes, para ninguem apagar a conta que queria usar. Escolhida a
     * conta, a tela se fecha sozinha e volta para a lista de servidores — era o que a pessoa foi fazer ali.
     */
    @Override
    public void onPage(Page page) {
        // Dentro da partida nada daqui serve (nao se troca de conta nem de proxy com a conexao aberta): sem nada na
        // pagina, o Painel do jogo nem lista o Connect. Ele so existe pelo botao da lista de servidores.
        if (Entities.self() != null) {
            return;
        }
        page.icon("link");
        page.section(tr("steinconnect.accounts"));
        // O campo e o botao de acrescentar vem antes da lista: com muitas contas a lista cresce para baixo, e
        // acrescentar uma nao obriga a rolar ate o fim dela.
        page.option(new Option.Text(tr("steinconnect.nick"), "", () -> pending, v -> pending = v));
        page.option(new Option(tr("steinconnect.add")) {
            @Override
            public String value() {
                String nick = pending.trim();
                return nick.isEmpty() || Accounts.valid(nick) ? "" : "§c" + tr("steinconnect.invalid");
            }

            @Override
            public void click(boolean forward) {
                use(pending.trim());
            }
        });
        page.rows(this::accountRows, "");
        page.restore(() -> {   // a conta do launcher, e sem nicks guardados
            accounts.restore();
            config.recent.clear();
            pending = "";
            config.save();
        });

        page.section(tr("steinconnect.proxySection"));
        page.option(Option.toggle(tr("steinconnect.proxyOn"), () -> config.proxyOn, v -> {
            config.proxyOn = v;
            config.save();
        }));
        page.rows(this::proxyRows, tr("steinconnect.noProxies"));
        proxyFields(page);
        page.restore(() -> {   // desligado, sem endereco nem conta, e sem proxies guardados
            ConnectConfig d = new ConnectConfig();
            config.proxyOn = d.proxyOn;
            config.proxyHost = d.proxyHost;
            config.proxyPort = d.proxyPort;
            config.proxyUser = d.proxyUser;
            config.proxyPass = d.proxyPass;
            config.proxies.clear();
            config.save();
        });

        page.section(tr("steinconnect.reconnectSection"));
        page.option(Option.toggle(tr("steinconnect.auto"), () -> config.auto, v -> {
            config.auto = v;
            config.save();
        }));
        page.option(Option.cycle(tr("steinconnect.delay"), delayNames(), this::delayIndex, i -> {
            config.delaySeconds = DELAYS[i];
            config.save();
        }).advanced());
        page.restore(() -> {
            ConnectConfig d = new ConnectConfig();
            config.auto = d.auto;
            config.delaySeconds = d.delaySeconds;
            config.save();
        });
    }

    /**
     * As contas: a do launcher em primeiro (essa nao se apaga) e os nicks offline ja usados. O detalhe de cada uma
     * e o que ela e — Microsoft verificada, offline —, e a de agora fica marcada.
     */
    private List<Page.Item> accountRows() {
        List<Page.Item> out = new ArrayList<>();
        boolean onLauncher = accounts.onLauncher();
        out.add(new Page.Item(accounts.launcherName(), accounts.launcherKind(), onLauncher, () -> {
            accounts.restore();
            Panel.close();
        }, null));
        String now = accounts.name();
        for (String nick : new ArrayList<>(config.recent)) {
            boolean current = !onLauncher && nick.equalsIgnoreCase(now);
            out.add(new Page.Item(nick, tr("steinconnect.offline"), current, () -> use(nick), () -> {
                config.recent.removeIf(n -> n.equalsIgnoreCase(nick));
                config.save();
            }));
        }
        return out;
    }

    /** Os proxies guardados: a linha poe aquele proxy para valer, a lixeira o tira da lista. */
    private List<Page.Item> proxyRows() {
        List<Page.Item> out = new ArrayList<>();
        for (ConnectConfig.SavedProxy p : new ArrayList<>(config.proxies)) {
            boolean current = config.proxyOn && p.host.equals(config.proxyHost) && p.port == config.proxyPort
                    && p.user.equals(config.proxyUser);
            out.add(new Page.Item(p.label(), current ? tr("steinconnect.inUse") : "", current, () -> {
                useProxy(p.label());
                config.proxyOn = true;
                config.save();
            }, () -> {
                config.proxies.removeIf(s -> s.label().equals(p.label()));
                config.save();
            }));
        }
        return out;
    }

    /** Entra com o nick e volta para a lista de servidores: escolhida a conta, nao ha mais nada a fazer aqui. */
    private void use(String nick) {
        if (!Accounts.valid(nick)) {
            return; // a propria linha de acrescentar ja diz o que falta
        }
        accounts.useNick(nick);
        pending = "";
        Panel.close();
    }

    /** Os campos de um proxy novo, e o que testa e guarda o que esta neles. */
    private void proxyFields(Page page) {
        page.option(new Option.Text(tr("steinconnect.proxyHost"), tr("steinconnect.proxyHost.hint"),
                () -> config.proxyHost.isEmpty() ? "" : config.proxyHost + ":" + config.proxyPort, this::setHost));
        // Usuario e senha ficam em "Mais opcoes": proxy publico nao pede nenhum dos dois, e quem precisa sabe.
        // O tipo declarado e Option, e nao Option.Text, porque o advanced() e de la (o mod so alcanca a API).
        Option user = new Option.Text(tr("steinconnect.proxyUser"), "", () -> config.proxyUser, v -> {
            config.proxyUser = v;
            config.save();
        });
        page.option(user.advanced());
        Option pass = new Option.Text(tr("steinconnect.proxyPass"), "", () -> config.proxyPass, v -> {
            config.proxyPass = v;
            config.save();
        }, true);
        page.option(pass.advanced());

        page.option(new Option(tr("steinconnect.proxyTest")) {
            @Override
            public String value() {
                return proxy.status();
            }

            @Override
            public void click(boolean forward) {
                proxy.test();
            }
        });
        page.option(new Option(tr("steinconnect.addProxy")) {
            @Override
            public String value() {
                return config.proxyHost.isEmpty() ? "§7" + tr("steinconnect.proxyHost") : "";
            }

            @Override
            public void click(boolean forward) {
                if (config.rememberProxy()) {
                    config.save();
                }
            }
        });
    }

    /** Volta a um proxy da lista dos usados. */
    private void useProxy(String label) {
        for (ConnectConfig.SavedProxy p : config.proxies) {
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

    /** "host:porta" num campo so: o que vem depois dos dois pontos e a porta, se for numero. */
    private void setHost(String text) {
        String v = text.trim();
        int colon = v.lastIndexOf(58);
        if (colon > 0) {
            try {
                config.proxyPort = Math.max(1, Math.min(65535, Integer.parseInt(v.substring(colon + 1).trim())));
                v = v.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // ainda digitando a porta
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
            if (Math.abs(DELAYS[i] - config.delaySeconds) < Math.abs(DELAYS[best] - config.delaySeconds)) {
                best = i;
            }
        }
        return best;
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}

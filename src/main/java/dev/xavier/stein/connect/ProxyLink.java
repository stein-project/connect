/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Hooks the game's connection to {@link Socks5}: what the Loader asks when connecting (through its
 * {@link Net} API).
 *
 * <p>With the proxy on, {@link #address} has the game connect to the proxy and keeps the real
 * destination; {@link #wrap} returns an initializer that puts the tunnel first and only then lets
 * the game's build its packet stack. With the proxy off, both return what the game would do anyway.
 *
 * <p><b>Each connection has its own destination.</b> The game calls {@code handler(...)} and then
 * {@code connect(...)} on the same thread, and the tunnel is only built later, on the network
 * thread. The destination used to live in a single field, and the server list, which pings every
 * server at once (several threads), sent every tunnel to the last destination written: with the
 * proxy on, one server showed in place of all of them. Now {@code wrap} creates that connection's
 * destination and leaves it with the thread; {@code address}, right after on the same thread, fills
 * it in.
 */
final class ProxyLink implements Net.Proxy {

    private final ConnectConfig config;
    private final Log log;

    /** A connection's destination, filled in by address and read by its tunnel. */
    private static final class Target {
        volatile InetAddress host;
        volatile int port;
    }

    /** The destination created by this thread's wrap, waiting for the same attempt's address. */
    private final ThreadLocal<Target> pending = new ThreadLocal<>();

    ProxyLink(ConnectConfig config, Log log) {
        this.config = config;
        this.log = log;
    }

    /** Whether the proxy is on and has an address. */
    static boolean on(ConnectConfig config) {
        return config.proxyOn && !config.proxyHost.isBlank() && config.proxyPort > 0;
    }

    @Override
    public Object address(Object destination, int port) {
        Target t = pending.get();
        pending.remove();
        if (!on(config) || t == null || !(destination instanceof InetAddress a)) {
            return null;
        }
        t.host = a;
        t.port = port;
        return new InetSocketAddress(config.proxyHost.trim(), config.proxyPort);
    }

    @Override
    public Object wrap(Object initializer) {
        pending.remove();
        if (!on(config) || !(initializer instanceof ChannelHandler game)) {
            return initializer;
        }
        Target target = new Target();
        pending.set(target);
        String user = config.proxyUser;
        String pass = config.proxyPass;
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                InetAddress to = target.host;
                if (to != null) {
                    ch.pipeline()
                            .addLast("stein-socks", new Socks5(to, target.port, user, pass, log));
                }
                // The game's initializer runs next and takes itself out of the pipeline.
                ch.pipeline().addLast(game);
            }
        };
    }
}

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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The SOCKS5 tunnel, written here: the Netty of 1.8.9 is from 2014 and has no proxy handler, and
 * replacing the whole library would cost more.
 *
 * <p>It sits in front of the game's handlers: it talks to the proxy in raw bytes and, only once the
 * tunnel is up, leaves the pipeline and lets the game talk. Meanwhile it <b>holds what the game
 * writes</b>: the game sends its handshake as soon as connect returns, while the tunnel is still
 * being negotiated. {@code channelActive} goes through at once, because that is where the game's
 * NetworkManager keeps the channel; held back, the game could not even show why the proxy refused
 * and stayed on "connecting".
 *
 * <p>Sequence (RFC 1928 and 1929): greeting with the accepted methods, username and password
 * authentication if the proxy asks, and the CONNECT request to the server's address. An error at
 * any step becomes an exception on the channel, so the game shows the reason on the disconnect
 * screen instead of waiting for a timeout.
 */
final class Socks5 extends ChannelDuplexHandler {

    private static final byte VERSION = 5;
    private static final byte NO_AUTH = 0;
    private static final byte USER_PASS = 2;
    private static final byte CONNECT = 1;
    private static final byte IPV4 = 1;
    private static final byte DOMAIN = 3;
    private static final byte IPV6 = 4;

    /** Step: 0 greeting sent, 1 authentication sent, 2 CONNECT sent, 3 tunnel up. */
    private int step;

    private final InetAddress host;
    private final int port;
    private final String user;
    private final String pass;
    private final Log log;

    /** What the game wrote before the tunnel was ready. */
    private final List<Object[]> held = new ArrayList<>();

    private ByteBuf inbox;

    Socks5(InetAddress host, int port, String user, String pass, Log log) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.log = log;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(VERSION);
        if (user.isEmpty()) {
            b.writeByte(1).writeByte(NO_AUTH);
        } else {
            b.writeByte(2).writeByte(NO_AUTH).writeByte(USER_PASS);
        }
        ctx.writeAndFlush(b);
        // The game must know about the channel right away: its NetworkManager keeps the channel in
        // channelActive, and without it, it cannot even close with a reason if the proxy refuses.
        // Whatever it writes is held here.
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (step == 3 || !(msg instanceof ByteBuf in)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (inbox == null) {
            inbox = ctx.alloc().buffer();
        }
        inbox.writeBytes(in);
        in.release();
        while (step < 3 && advance(ctx)) {
            // each step consumes what arrived for it
        }
        if (step == 3 && inbox != null) {
            // Whatever came along with the proxy's answer already belongs to the server.
            ByteBuf rest = inbox;
            inbox = null;
            if (rest.isReadable()) {
                ctx.fireChannelRead(rest);
            } else {
                rest.release();
            }
        }
    }

    /** Reads one step of the conversation; false while bytes are still missing. */
    private boolean advance(ChannelHandlerContext ctx) throws Exception {
        return switch (step) {
            case 0 -> greeted(ctx);
            case 1 -> authenticated(ctx);
            default -> connected(ctx);
        };
    }

    private boolean greeted(ChannelHandlerContext ctx) {
        if (inbox.readableBytes() < 2) {
            return false;
        }
        byte version = inbox.readByte();
        byte method = inbox.readByte();
        if (version != VERSION) {
            return fail(ctx, "steinconnect.proxy.notSocks");
        }
        if (method == NO_AUTH) {
            request(ctx);
        } else if (method == USER_PASS && !user.isEmpty()) {
            auth(ctx);
        } else {
            return fail(
                    ctx,
                    method == (byte) 0xFF
                            ? "steinconnect.proxy.noMethod"
                            : "steinconnect.proxy.needsAuth");
        }
        return true;
    }

    private boolean authenticated(ChannelHandlerContext ctx) {
        if (inbox.readableBytes() < 2) {
            return false;
        }
        inbox.readByte(); // authentication version
        if (inbox.readByte() != 0) {
            return fail(ctx, "steinconnect.proxy.badAuth");
        }
        request(ctx);
        return true;
    }

    private boolean connected(ChannelHandlerContext ctx) throws Exception {
        if (inbox.readableBytes() < 5) {
            return false;
        }
        int mark = inbox.readerIndex();
        inbox.readByte(); // version
        byte reply = inbox.readByte();
        inbox.readByte(); // reserved
        byte type = inbox.readByte();
        int rest =
                type == IPV4
                        ? 4
                        : type == IPV6
                                ? 16
                                : type == DOMAIN
                                        ? 1 + inbox.getUnsignedByte(inbox.readerIndex())
                                        : -1;
        if (rest < 0) {
            return fail(ctx, "steinconnect.proxy.badReply");
        }
        if (inbox.readableBytes() < rest + 2) {
            inbox.readerIndex(mark);
            return false;
        }
        inbox.skipBytes(rest + 2);
        if (reply != 0) {
            return fail(ctx, reason(reply));
        }
        done(ctx);
        return true;
    }

    private void auth(ChannelHandlerContext ctx) {
        byte[] u = user.getBytes(StandardCharsets.UTF_8);
        byte[] p = pass.getBytes(StandardCharsets.UTF_8);
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(1).writeByte(u.length).writeBytes(u).writeByte(p.length).writeBytes(p);
        ctx.writeAndFlush(b);
        step = 1;
    }

    private void request(ChannelHandlerContext ctx) {
        byte[] addr = host.getAddress();
        ByteBuf b = ctx.alloc().buffer();
        b.writeByte(VERSION).writeByte(CONNECT).writeByte(0);
        b.writeByte(addr.length == 16 ? IPV6 : IPV4).writeBytes(addr);
        b.writeShort(port);
        ctx.writeAndFlush(b);
        step = 2;
    }

    /** Tunnel up: leave the pipeline; what the game wrote goes out now. */
    private void done(ChannelHandlerContext ctx) throws Exception {
        step = 3;
        log.info("proxy tunnel open to " + host.getHostAddress() + ":" + port);
        ctx.pipeline().remove(this);
        for (Object[] w : held) {
            ctx.write(w[0], (ChannelPromise) w[1]);
        }
        held.clear();
        ctx.flush();
    }

    /**
     * Fails with the translated reason ({@code key} is the lang key): the text the game shows on
     * the disconnect screen.
     */
    private boolean fail(ChannelHandlerContext ctx, String key) {
        step = 3;
        IOException cause = new IOException("Proxy: " + Lang.tr(key));
        // The exception goes before failing what was held: what arrives first is the reason the
        // game shows.
        ctx.fireExceptionCaught(cause);
        discard(cause);
        ctx.close();
        return false;
    }

    /** The lang key for the CONNECT refusal code (RFC 1928, section 6). */
    private static String reason(byte reply) {
        return switch (reply) {
            case 2 -> "steinconnect.proxy.notAllowed";
            case 3 -> "steinconnect.proxy.networkUnreachable";
            case 4 -> "steinconnect.proxy.hostUnreachable";
            case 5 -> "steinconnect.proxy.serverRefused";
            case 6 -> "steinconnect.proxy.timeout";
            default -> "steinconnect.proxy.refused";
        };
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        if (step < 3) {
            held.add(new Object[] {msg, promise});
            return;
        }
        ctx.write(msg, promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (step >= 3) {
            ctx.flush();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (step < 3) {
            discard(new IOException("Proxy: " + Lang.tr("steinconnect.proxy.closed")));
        }
    }

    private void discard(IOException cause) {
        if (inbox != null) {
            inbox.release();
            inbox = null;
        }
        for (Object[] w : held) {
            if (w[0] instanceof ByteBuf b) {
                b.release();
            }
            ((ChannelPromise) w[1]).tryFailure(cause);
        }
        held.clear();
    }
}

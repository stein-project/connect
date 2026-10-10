/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

import dev.xavier.stein.loader.api.Lang;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * The "Test connection" button: opens the proxy and negotiates up to authentication, off the game
 * thread, to tell right away whether it answers. A dead proxy must fail with a reason, not with a
 * timeout halfway through joining a server.
 */
final class ProxyTest {

    private static final String OK = "steinconnect.proxy.ok";
    private static final int TIMEOUT_MS = 6000;

    private final ConnectConfig config;

    /** The last test's result: a lang key, or the network failure's class name. */
    private volatile String result = "";

    private volatile boolean testing;

    /** The last test passed and the proxy is not on the used list yet (the game thread adds it). */
    private volatile boolean passed;

    ProxyTest(ConnectConfig config) {
        this.config = config;
    }

    /** Whether a test passed since the last time this was asked. */
    boolean takePassed() {
        boolean p = passed;
        passed = false;
        return p;
    }

    /** The Panel line: the translated result (green when it passed), or the failure's name. */
    String status() {
        if (testing) {
            return "...";
        }
        String r = result;
        if (r.isEmpty()) {
            return "";
        }
        if (r.startsWith("steinconnect.")) {
            return (r.equals(OK) ? "§a" : "§c") + Lang.tr(r);
        }
        return "§c" + r;
    }

    /** Starts a test of the current proxy; nothing while one is running or the proxy is off. */
    void start() {
        if (testing || !ProxyLink.on(config)) {
            return;
        }
        testing = true;
        result = "";
        String proxyHost = config.proxyHost.trim();
        int proxyPort = config.proxyPort;
        String user = config.proxyUser;
        String pass = config.proxyPass;
        Thread t =
                new Thread(
                        () -> {
                            try (Socket s = new Socket()) {
                                s.connect(new InetSocketAddress(proxyHost, proxyPort), TIMEOUT_MS);
                                s.setSoTimeout(TIMEOUT_MS);
                                result = handshake(s, user, pass);
                                passed = result.equals(OK);
                            } catch (IOException e) {
                                result = failure(e);
                            } finally {
                                testing = false;
                            }
                        },
                        "stein-connect-proxy-test");
        t.setDaemon(true);
        t.start();
    }

    /** RFC 1928 greeting and, if the proxy asks, RFC 1929 authentication. */
    private static String handshake(Socket s, String user, String pass) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        boolean auth = user != null && !user.isEmpty();
        out.write(auth ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        out.flush();
        byte[] r = read(in, 2);
        if (r[0] != 5) {
            return "steinconnect.proxy.notSocks";
        }
        if (r[1] == 2) {
            if (!auth) {
                return "steinconnect.proxy.needsAuth";
            }
            byte[] u = user.getBytes(StandardCharsets.UTF_8);
            byte[] p = pass == null ? new byte[0] : pass.getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = 1;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg);
            out.flush();
            if (read(in, 2)[1] != 0) {
                return "steinconnect.proxy.badAuth";
            }
        } else if (r[1] != 0) {
            return "steinconnect.proxy.refused";
        }
        return OK;
    }

    /** The test's network failure as a lang key; one without a translation shows its class name. */
    private static String failure(IOException e) {
        if (e instanceof SocketTimeoutException) {
            return "steinconnect.proxy.timeout";
        }
        if (e instanceof UnknownHostException) {
            return "steinconnect.proxy.unknownHost";
        }
        if (e instanceof ConnectException) {
            return "steinconnect.proxy.unreachable";
        }
        if (e instanceof EOFException) {
            return "steinconnect.proxy.closed";
        }
        return e.getClass().getSimpleName();
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int k = in.read(b, got, n - got);
            if (k < 0) {
                throw new EOFException("the proxy closed the connection");
            }
            got += k;
        }
        return b;
    }
}

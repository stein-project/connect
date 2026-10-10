/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.connect;

/** A proxy on the saved list: address, port and its account. */
final class SavedProxy {
    String host = "";
    int port = 1080;
    String user = "";

    /** Only in memory and in the vault; never in the JSON. */
    String pass = "";

    /**
     * How it shows on the list, "user@host:port" (the password never shows); unique on the list.
     */
    String label() {
        return (user.isEmpty() ? "" : user + "@") + host + ":" + port;
    }
}

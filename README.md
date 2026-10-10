# Connect

A [Stein Loader](https://github.com/Stein-Project) mod for Minecraft 1.8.9: everything about joining a server.
Accounts, reconnect after a drop and a SOCKS5 proxy, in place of the old Alt Manager and Reconnect mods.

- **Accounts**: play with an offline nick (for servers without authentication) and go back to the launcher's
  account in one click. The list of nicks has no limit and does not reorder itself. The launcher's account shows
  whether it is a verified Microsoft account; the check is done by the Stein Loader, and this mod never sees a token.
- **Reconnect**: on the disconnect screen (drop, kick, full server) a "Reconnect (10s)" button counts down and
  joins the last server again; a click reconnects at once. Automatic reconnect and the delay are adjustable.
- **SOCKS5 proxy**: join servers through a SOCKS5 proxy, with or without a username and password
  (RFC 1928 and 1929). "Test connection" tells right away whether the proxy answers and why it does not. Proxies
  that worked are kept in a list (up to 6) to go back to with one click.

Open it from the **Connect** button at the top right of the server list. It is where you choose how to join, so it
does not show on the in-game Panel.

The settings are kept in `config/steinconnect.json`. **Proxy passwords never go to that file**: they are kept in
`config/steinconnect.secrets`, encrypted by Windows (DPAPI) for your Windows account on this computer. On other
systems the file is only scrambled and readable by its owner.

<!-- Screenshot: docs/connect.png (the Connect page opened from the server list) -->

## Download

The [Stein Client](https://github.com/Stein-Project/stein-client/releases/latest) installs and updates it for you.
By hand: download `Connect.steinmod` from the
[latest release](https://github.com/Stein-Project/connect/releases/latest), put it in `.minecraft/mods` and play
with the **Stein Loader** profile.

## Building

You need **JDK 17 or newer** to run Gradle (the JDK 25 the mod compiles with is downloaded if you do not have it).
The [Stein SDK](https://github.com/Stein-Project/stein-sdk) Gradle plugin prepares Minecraft 1.8.9 for compiling the
first time, from your own installation or from Mojang.

```sh
./gradlew build            # build/libs/Connect.steinmod (also checks the formatting)
./gradlew spotlessApply    # format the code (google-java-format, AOSP style) and add the licence header
./gradlew installMod       # build and copy into .minecraft/mods
```

Before a Stein SDK release (developing the SDK and the mod together), build against a local SDK: in a clone of
[sdk](https://github.com/Stein-Project/sdk) run `./gradlew build publishPlugin`, then here
`./gradlew build -Pstein.sdkDir=<path to that clone>` (the plugin comes from its `build/maven-repo`, the SDK from
its `build/sdk/stein-sdk.jar`).

## Contributing

Bug reports, ideas and pull requests are welcome. Read the
[contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first; your first pull
request asks you to sign the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md). For a change to this
mod, also check that:

- an existing `config/steinconnect.json` still loads (the JSON keys `recent`, `auto`, `delaySeconds`, `proxyOn`,
  `proxyHost`, `proxyPort`, `proxyUser` and `proxies` with `host`, `port`, `user` are what players already have),
  and so do the passwords in `config/steinconnect.secrets` (keys `proxy.current` and `proxy.saved.<user@host:port>`);
- no password, token or other secret is written to the JSON or to the log;
- the text is translated in `en_US`, `pt_BR` and `es_ES` (`src/main/resources/assets/steinconnect/lang`).

Found a security problem (a password or token that leaks, for example)? Do not open a public issue: follow the
[security policy](https://github.com/Stein-Project/.github/blob/main/SECURITY.md).

Releases are made by the maintainer: a `v<version>` tag, with `changelog/<version>.en_US.md` and `.pt_BR.md`
written first (see `.github/workflows/release.yml`).

## Licence

Connect is licensed under the [Mozilla Public License 2.0](LICENSE). Third-party notices are in [NOTICE](NOTICE).
The Stein name and logo are not covered by the licence: see the
[trademark policy](https://github.com/Stein-Project/.github/blob/main/TRADEMARK.md).

Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

Created by [Breno Xavier](https://github.com/brenoxavier).

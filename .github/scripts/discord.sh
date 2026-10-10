#!/usr/bin/env bash
# The Discord announcements of a release, by the Stein Labs bot (ported from stein-master tools/notify-discord.sh and
# tools/discord-downloads.sh):
#
#   discord.sh changelog    posts the notes of this release in the #changelog channel (DISCORD_CANAL_CHANGELOG)
#                           env: ROTULO (name), VERSAO, URL (the release page), NOTAS (the pt_BR changelog file),
#                                VERSOES (optional: extra lines for the "Versões" block)
#   discord.sh downloads    rewrites the single downloads message (DISCORD_CANAL_DOWNLOADS): for every component,
#                           its last published release, read from GitHub (needs GH_TOKEN, any token reads public repos)
#
# PRINT=1 writes the messages and sends nothing (the dry run uses it). Without DISCORD_BOT_TOKEN, or without the
# channel id, it says so and exits 0: a release may go out without an announcement, never fail because of one.
#
# The same file is kept in the installer, client and sdk repositories and in every mod repository
# (.github/scripts/discord.sh): change all of them together.
set -uo pipefail
TOKEN="${DISCORD_BOT_TOKEN:-}"
PRINT="${PRINT:-0}"
DIST_OWNER="${DIST_OWNER:-Stein-Project}"
# The old account (read only, public, no token of its own): its last releases of the loader, client and sdk stay
# there frozen, and are listed only while the organisation has none of that component yet.
LEGACY_OWNER="${LEGACY_OWNER:-x4vieer}"
# A mod not moved yet is still released from stein-master to the old account (stein-<mod>). A moved one is released
# from its own repository, <DIST_OWNER>/<mod>, which is looked at first; the mod being released right now is looked
# for first in RELEASE_REPO (its release repository, when it is not <DIST_OWNER>/<mod>: a private mod).
MODS_OWNER="${MODS_OWNER:-x4vieer}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
jq() { command jq "$@" | tr -d '\r'; }
here="$(cd "$(dirname "$0")" && pwd)"

if [ -z "$TOKEN" ] && [ "$PRINT" != 1 ]; then
  echo "no DISCORD_BOT_TOKEN: skipping the Discord announcement" >&2
  exit 0
fi

# Characters, not bytes: Discord's limit is in characters, and emoji and accents take several bytes.
caracteres() { LC_ALL=C.UTF-8 bash -c 't="$(cat "$1")"; echo "${#t}"' _ "$1"; }

# <method> <path> [file with the body]. The body goes by file, not by argument: on Git Bash an argument with accents,
# line breaks and slashes still goes through MSYS path conversion before reaching curl.exe ("invalid JSON").
api() {
  local metodo="$1" caminho="$2" corpo="${3:-}"
  if [ -n "$corpo" ]; then
    curl -sS --ssl-no-revoke -X "$metodo" -H "Authorization: Bot $TOKEN" \
      -H 'Content-Type: application/json' --data-binary "@$corpo" "https://discord.com/api/v10$caminho"
  else
    curl -sS --ssl-no-revoke -X "$metodo" -H "Authorization: Bot $TOKEN" "https://discord.com/api/v10$caminho"
  fi
}

# flags 4 = SUPPRESS_EMBEDS: a preview of every link would be a wall of cards.
corpo_de() {  # <message file> <everyone 0|1> -> body file
  jq -n --rawfile c "$1" --argjson e "$2" \
    '{content: ($c | sub("\\s+$"; "")), flags: 4,
      allowed_mentions: {parse: (if $e == 1 then ["everyone"] else [] end)}}' > "$TMP/corpo.json"
  echo "$TMP/corpo.json"
}

changelog() {
  : "${ROTULO:?}" "${VERSAO:?}" "${URL:?}" "${NOTAS:?}"
  local titulo resumo itens todos cortados=0 msg="$TMP/changelog.md"
  titulo="$(bash "$here/changelog.sh" title "$NOTAS")"
  resumo="$(bash "$here/changelog.sh" summary "$NOTAS")"
  itens="$(bash "$here/changelog.sh" body "$NOTAS")"
  mensagem() {
    echo "## $ROTULO $VERSAO · $(date +%d/%m/%Y)"
    echo
    echo "**Versões**"
    if [ -n "${VERSOES:-}" ]; then echo "$VERSOES"; else echo "- $ROTULO: **$VERSAO**"; fi
    echo
    echo "**$titulo**"
    echo
    echo "$resumo"
    echo
    echo "$itens"
    echo
    echo "Download: [$ROTULO $VERSAO]($URL)"
  }
  # Long notes lose their last items, with a note; the release page (the link at the end) has the whole text.
  mensagem > "$msg"
  todos="$itens"
  while [ "$(caracteres "$msg")" -gt 2000 ] && [ -n "$todos" ]; do
    todos="$(printf '%s\n' "$todos" | sed '$d')"
    cortados=$((cortados + 1))
    itens="$todos"$'\n'"- … e mais: as notas completas estão no link abaixo."
    mensagem > "$msg"
  done
  [ "$cortados" = 0 ] || echo "warning: $cortados changelog item(s) cut to fit Discord" >&2
  if [ "$PRINT" = 1 ]; then
    echo "===== #changelog ($(caracteres "$msg") characters) ====="; cat "$msg"; echo
    return 0
  fi
  local canal="${DISCORD_CANAL_CHANGELOG:-}"
  [ -n "$canal" ] || { echo "no DISCORD_CANAL_CHANGELOG: skipping the changelog post" >&2; return 0; }
  local resp
  resp="$(api POST "/channels/$canal/messages" "$(corpo_de "$msg" 0)")"
  [ -n "$(jq -r '.id // empty' <<< "$resp" 2>/dev/null)" ] \
    || { echo "Discord refused the changelog post: $(head -c 400 <<< "$resp")" >&2; return 1; }
  echo "changelog posted in $canal"
}

# id | where (dist: the organisation's distribution repositories; mods: the old account) | repo | name | file | line
COMPONENTES='client|dist|stein-client|Stein Client|SteinClient.exe|O launcher oficial: entre com sua conta Microsoft e clique em Jogar. Ele baixa e atualiza tudo sozinho. Para Windows.
loader|dist|stein-loader|Stein Loader|SteinLoader-Installer.jar|Instalador do Stein Bridge e do Stein Loader, para Windows, Linux e macOS (Java 8+).
combat|mods|stein-combat|Combat|Combat.steinmod|Aliados e clãs, automações de armadura e efeitos, e movimentação para PvP.
hud|mods|stein-hud|HUD|HUD.steinmod|HUD personalizável, minimapa e lista do Tab.
connect|mods|stein-connect|Connect|Connect.steinmod|Contas, reconexão automática e proxy SOCKS5.
mouse|mods|stein-mouse|Mouse|Mouse.steinmod|Shift e arrastar para transferir itens em inventários e baús.
keybind|mods|stein-keybind|Keybind|Keybind.steinmod|Mensagens e comandos numa tecla.
replay|mods|stein-replay|Replay|Replay.steinmod|Replay da morte: veja como você morreu antes de renascer.
sdk|dist|stein-sdk|Stein SDK|stein-sdk.jar|Para quem faz mod: a API do Stein Loader (experimental), o modelo e um mod de exemplo.'

downloads() {
  command -v gh >/dev/null 2>&1 || { echo "needs the gh CLI" >&2; return 1; }
  local id onde repo rotulo arquivo resumo dono
  # The last published release of each component, once. A distribution component not yet released by the
  # organisation (the first days of the move) falls back to its last release on the old account.
  while IFS='|' read -r id onde repo rotulo arquivo resumo; do
    [ -n "$id" ] || continue
    if [ "$onde" = mods ]; then donos="$DIST_OWNER/$id $MODS_OWNER/$repo"; else donos="$DIST_OWNER/$repo $LEGACY_OWNER/$repo"; fi
    if [ "$id" = "${INDEX_ID:-}" ] && [ -n "${RELEASE_REPO:-}" ]; then donos="$RELEASE_REPO $donos"; fi
    for dono in $donos; do
      if gh release view --repo "$dono" --json tagName,publishedAt > "$TMP/$id.json" 2>/dev/null; then
        echo "$dono" > "$TMP/$id.repo"; break
      fi
      rm -f "$TMP/$id.json"
    done
  done <<< "$COMPONENTES"

  dia() { date -d "$1" +%d/%m/%Y 2>/dev/null || echo "$1"; }
  # <level>: 0 complete; 1 without the dates of the manual install; 2 without its descriptions too.
  lista() {
    local nivel="$1" manual=0 rel tag ver quando url emoji
    echo "# Downloads"
    echo
    echo "> ⚠️ Em fase de testes: bugs e crashes podem ocorrer. Utilize por sua conta e risco."
    while IFS='|' read -r id onde repo rotulo arquivo resumo; do
      [ -f "$TMP/$id.json" ] || continue    # not released yet: nothing to download
      rel="$(cat "$TMP/$id.json")"
      tag="$(jq -r .tagName <<< "$rel")"
      ver="${tag#v}"
      quando="$(dia "$(jq -r .publishedAt <<< "$rel")")"
      url="https://github.com/$(cat "$TMP/$id.repo")/releases/latest/download/$arquivo"
      echo
      if [ "$id" != client ] && [ "$manual" = 0 ]; then
        manual=1
        echo "### 🛠️ Instalação manual"
        echo "-# Só para quem não usa o Stein Client: ele já baixa e atualiza tudo abaixo."
        echo
      fi
      case "$id" in client) emoji="🚀" ;; loader) emoji="💾" ;; sdk) emoji="🧩" ;; *) emoji="📦" ;; esac
      if [ "$id" = client ]; then
        echo "## $emoji $rotulo"
        echo "**Recomendado** · \`$ver\` · $quando"
        echo "$resumo"
      elif [ "$nivel" = 0 ]; then
        echo "$emoji **$rotulo** · \`$ver\` · $quando"
        echo "$resumo"
      else
        echo "$emoji **$rotulo** · \`$ver\`"
        [ "$nivel" -ge 2 ] || echo "$resumo"
      fi
      echo "[Download]($url)"
    done <<< "$COMPONENTES"
    echo
    echo "-# Atualizado em $(date +%d/%m/%Y)"
  }

  local msg="$TMP/downloads.md" tamanho nivel
  for nivel in 0 1 2; do
    lista "$nivel" > "$msg"
    tamanho="$(caracteres "$msg")"
    [ "$tamanho" -gt 2000 ] || break
  done
  [ "$tamanho" -le 2000 ] || { echo "the downloads list has $tamanho characters (Discord takes 2000)" >&2; return 1; }
  if [ "$PRINT" = 1 ]; then
    echo "===== #downloads ($tamanho characters) ====="; cat "$msg"; echo
    return 0
  fi
  local canal="${DISCORD_CANAL_DOWNLOADS:-}"
  [ -n "$canal" ] || { echo "no DISCORD_CANAL_DOWNLOADS: skipping the downloads list" >&2; return 0; }
  local corpo eu antiga resp
  corpo="$(corpo_de "$msg" 0)"
  eu="$(api GET /users/@me | jq -r '.id // empty')"
  [ -n "$eu" ] || { echo "Discord did not accept the bot token" >&2; return 1; }
  # Edit, do not post again: the oldest message of the bot in the channel is the one (Discord lists newest first).
  antiga="$(api GET "/channels/$canal/messages?limit=100" \
    | jq -r --arg eu "$eu" '[.[] | select(.author.id == $eu)] | last | .id // empty')"
  if [ -n "$antiga" ]; then resp="$(api PATCH "/channels/$canal/messages/$antiga" "$corpo")"
  else resp="$(api POST "/channels/$canal/messages" "$corpo")"; fi
  [ -n "$(jq -r '.id // empty' <<< "$resp" 2>/dev/null)" ] \
    || { echo "Discord refused the downloads list: $(head -c 300 <<< "$resp")" >&2; return 1; }
  if [ -n "$antiga" ]; then echo "downloads list updated (message $antiga)"; else echo "downloads list posted"; fi
}

case "${1:-}" in
  changelog) changelog ;;
  downloads) downloads ;;
  *) echo "usage: discord.sh changelog|downloads" >&2; exit 2 ;;
esac

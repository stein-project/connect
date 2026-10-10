#!/usr/bin/env bash
# The release of a Stein mod, run by release.yml from the repository root after "./gradlew build":
#
#   mod.sh version              the "version" of src/main/resources/steinmod.json
#   mod.sh files                dist/<Name>.steinmod, dist/SHA256SUMS.txt and notes.md (the release text, en_US)
#   mod.sh entry                the mod's entry in the "mods" index of stein.json (JSON, printed)
#   mod.sh release              the GitHub release of this repository (GH_TOKEN: the workflow's own token)
#   mod.sh index                the entry upserted in <DIST_OWNER>/stein-loader/stein.json (GH_TOKEN: DIST_TOKEN)
#
#   env: INDEX_ID (the short id of the index: "mouse"), INDEX_DESCRIPTION (the line players read, in Portuguese),
#        RELEASE_REPO (owner/repo where the release goes: this repository for an open mod, a public one for a private
#        mod), TAG (v<version> or v<version>-<label>), TESTE (the label, empty for a stable release),
#        DIST_OWNER (default Stein-Project)
#
# Ported from stein-master tools/publish.sh (indice_mod and sincronizar_dist_hub). The index entry is what the
# installer and the Stein Client read to download and update the mod: id, modId (the "id" of steinmod.json, by which
# they recognise the file already in the mods folder), name, file, version, url, sha256, loader, description.
# The same file is kept in every mod repository (.github/scripts/mod.sh): change all of them together.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
MANIFEST=src/main/resources/steinmod.json
DIST_OWNER="${DIST_OWNER:-Stein-Project}"
TESTE="${TESTE:-}"

field() { jq -r --arg k "$1" '.[$k] // empty' "$MANIFEST"; }
version() { field version; }
# The file name, by the SDK's rule: the "name" (or the "id") without spaces or symbols.
file_name() {
  local n; n="$(field name)"; [ -n "$n" ] || n="$(field id)"
  echo "$(tr -cd 'A-Za-z0-9._-' <<< "$n").steinmod"
}

files() {
  local file ver ch
  file="$(file_name)"; ver="$(version)"; ch="changelog/$ver.en_US.md"
  [ -f "build/libs/$file" ] || { echo "::error::build/libs/$file is missing: run ./gradlew build first"; return 1; }
  rm -rf dist; mkdir dist
  cp "build/libs/$file" dist/
  # Nothing from Mojang: a mod carries only its own classes and assets.
  if unzip -Z1 "dist/$file" | grep -qE '^(net/minecraft/|assets/minecraft/)'; then
    echo "::error::dist/$file has game files inside"; return 1
  fi
  (cd dist && sha256sum -- "$file" > SHA256SUMS.txt)
  cl() { bash "$here/changelog.sh" "$1" "$ch"; }
  {
    echo "## $(cl title)"
    echo
    cl summary
    echo
    cl body
    echo
    echo "**Install:** the [Stein Client](https://github.com/$DIST_OWNER/stein-client/releases/latest) and the"
    echo "[Stein Loader installer](https://github.com/$DIST_OWNER/stein-loader/releases/latest) download and update"
    echo "this mod for you. By hand: put \`$file\` in \`.minecraft/mods\` and play with the **Stein Loader** profile"
    echo "(the mod asks for the Loader library $(field loader) or newer; any up-to-date install has it)."
    echo
    echo "**SHA-256** \`$file\` \`$(cut -d' ' -f1 dist/SHA256SUMS.txt)\`"
  } > notes.md
}

entry() {
  local file ver sha
  file="$(file_name)"; ver="$(version)"
  [ -f dist/SHA256SUMS.txt ] || { echo "::error::dist/SHA256SUMS.txt is missing: run mod.sh files first" >&2; return 1; }
  sha="$(cut -d' ' -f1 dist/SHA256SUMS.txt)"
  : "${INDEX_ID:?}" "${INDEX_DESCRIPTION:?}" "${RELEASE_REPO:?}" "${TAG:?}"
  jq -n --arg id "$INDEX_ID" --arg modId "$(field id)" --arg name "$(field name)" --arg file "$file" \
        --arg version "$ver${TESTE:+-$TESTE}" \
        --arg url "https://github.com/$RELEASE_REPO/releases/download/$TAG/$file" \
        --arg sha256 "$sha" --arg loader "$(field loader)" --arg description "$INDEX_DESCRIPTION" \
        '{id: $id, modId: $modId, name: $name, file: $file, version: $version, url: $url,
          sha256: $sha256, loader: $loader, description: $description}'
}

release() {
  local name notes=notes.md pre=0
  name="$(field name) $(version)"
  if [ -n "$TESTE" ]; then
    # A pre-release: releases/latest ignores it, so every usual link stays on the stable one.
    notes=notes-teste.md
    { echo "> **Test version ($TESTE)**, only for the Stein Client's test channel. The version for everyone comes later."
      echo; cat notes.md; } > "$notes"
    pre=1
    name="$name ($TESTE)"
  fi
  : "${RELEASE_REPO:?}"; dist_need_token "RELEASE_TOKEN (or the workflow token)"
  dist_release "$RELEASE_REPO" "$TAG" "$name" "$notes" "$pre" dist/*
}

index() {
  local m title
  dist_need_token "DIST_TOKEN"
  m="$(entry)"
  title="$(field name) $(version)${TESTE:+ ($TESTE)}"
  if [ -n "$TESTE" ]; then
    # Test: only the "test.mods" block, which only the Stein Client's test channel reads.
    dist_hub "$DIST_OWNER/stein-loader" "$title" \
      '.test.mods = ((.test.mods // []) | if any(.[]; .id == $m.id) then map(if .id == $m.id then $m else . end)
                                          else . + [$m] end)' --argjson m "$m"
  else
    # Replaced in place (or appended): the order of the index does not change, and running again writes nothing.
    # The test of the same version leaves the test channel (whoever tested goes back to the stable one).
    dist_hub "$DIST_OWNER/stein-loader" "$title" \
      '.mods = ((.mods // []) | if any(.[]; .id == $m.id) then map(if .id == $m.id then $m else . end) else . + [$m] end)
       | if .test.mods then
           .test.mods |= map(select(.id != $m.id or (.version | startswith($m.version + "-") | not)))
         else . end' --argjson m "$m"
  fi
}

case "${1:-}" in
  version) version ;;
  files) files ;;
  entry) entry ;;
  release) release ;;
  index) index ;;
  *) echo "usage: mod.sh version|files|entry|release|index" >&2; exit 2 ;;
esac

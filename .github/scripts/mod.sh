#!/usr/bin/env bash
# The release of a Stein mod, run by release.yml from the repository root after "./gradlew build":
#
#   mod.sh version              the "version" of src/main/resources/steinmod.json
#   mod.sh files                dist/<Name>.steinmod, dist/SHA256SUMS.txt and notes.md (the release text, en_US)
#   mod.sh entry                the mod's entry in its index (JSON, printed)
#   mod.sh steinmods            dist/steinmods.json, the mod's index, published with every stable release
#   mod.sh release              the GitHub release of RELEASE_REPO (GH_TOKEN: the workflow's own token, or RELEASE_TOKEN)
#   mod.sh test-index           a test version: its entry in the "test" block of the latest stable release's
#                               steinmods.json (GH_TOKEN: same as release)
#
#   env: INDEX_ID (the short id of the index: "mouse"), INDEX_DESCRIPTION (the line players read, in Portuguese),
#        RELEASE_REPO (owner/repo where the release goes: this repository for an open mod, a public one for a private
#        mod), TAG (v<version> or v<version>-<label>), TESTE (the label, empty for a stable release),
#        DIST_OWNER (default Stein-Project: where the Stein Client and the installer are, for the release notes)
#
# Every mod publishes its own index, steinmods.json, as an asset of each stable release: the Stein Client and the
# installer read it at https://github.com/<RELEASE_REPO>/releases/latest/download/steinmods.json, which always
# points to the latest stable release. No central file is written, so no token other than the release's is needed.
# The official mods are the ones whose index is listed in "sources" of Stein-Project/stein-loader/stein.json; any
# other mod works the same way, as a third-party source the player adds in the Stein Client.
#
#   { "format": 1, "name": "HUD", "author": "X4vieer", "homepage": "https://github.com/...",
#     "source": "<the latest/download link above>",
#     "mods": [ <entry> ],
#     "test": { "mods": [ <entry of a test version> ] } }      only while a test version is out
#
# An entry is what the Stein Client and the installer read to download and update the mod: id, modId (the "id" of
# steinmod.json, by which they recognise the file already in the mods folder), name, file, version, url, sha256,
# loader, description. An index may list several mods (one written by hand); the one written here lists this mod.
#
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
  # A stable release carries the index; a test one goes into the stable index instead (test-index).
  [ -n "$TESTE" ] || steinmods
  dist_release "$RELEASE_REPO" "$TAG" "$name" "$notes" "$pre" dist/*
}

steinmods() {
  local m
  m="$(entry)"
  jq -n --argjson m "$m" --arg name "$(field name)" --arg author "$(field author)" \
        --arg homepage "https://github.com/$RELEASE_REPO" \
        --arg source "https://github.com/$RELEASE_REPO/releases/latest/download/steinmods.json" \
        '{format: 1, name: $name, author: $author, homepage: $homepage, source: $source, mods: [$m]}' \
    > dist/steinmods.json
}

test_index() {
  local m latest work
  dist_need_token "RELEASE_TOKEN (or the workflow token)"
  m="$(entry)"
  # The latest stable release: the one releases/latest/download points to.
  latest="$(gh release view --repo "$RELEASE_REPO" --json tagName -q .tagName 2>/dev/null || true)"
  if [ -z "$latest" ]; then
    echo "::warning::$RELEASE_REPO has no stable release yet: the test channel only sees a test version on top of one"
    return 0
  fi
  work="$(mktemp -d)"
  gh release download "$latest" --repo "$RELEASE_REPO" -p steinmods.json -D "$work" \
    || { echo "::error::$latest of $RELEASE_REPO has no steinmods.json"; return 1; }
  jq --argjson m "$m" '.test.mods = ((.test.mods // []) | map(select(.id != $m.id)) + [$m])' \
    "$work/steinmods.json" > "$work/new.json"
  if cmp -s "$work/steinmods.json" "$work/new.json"; then
    echo "steinmods.json of $latest already has $TAG"
  else
    mv "$work/new.json" "$work/steinmods.json"
    gh release upload "$latest" "$work/steinmods.json" --repo "$RELEASE_REPO" --clobber
    echo "steinmods.json of $latest: test version $TAG"
  fi
  rm -rf "$work"
}

case "${1:-}" in
  version) version ;;
  files) files ;;
  entry) entry ;;
  release) release ;;
  steinmods) steinmods ;;
  test-index) test_index ;;
  *) echo "usage: mod.sh version|files|entry|steinmods|release|test-index" >&2; exit 2 ;;
esac

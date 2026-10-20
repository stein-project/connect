#!/usr/bin/env bash
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# Copyright (c) 2026 Breno Xavier
#
# Helpers to read and write the Stein registry (Stein-Project/registry), sourced by the release workflows
# (". .github/scripts/registry.sh"). The registry holds what the Stein Loader, the Stein Client and the installer look
# up at run time; a release writes its part there (the installer: "latest" in stein.json; a mod: mods/<id>.json), and
# the registry's own workflow signs and publishes it.
#
# Writing needs REGISTRY_TOKEN: a fine-grained token with "Contents: write" on the registry only. Each write is one
# commit through the contents API, under the project owner's name and noreply address, and none when the content is
# already there. With ENSAIO=true (the dry run) nothing is written: the new content is printed.
#
# The same file is kept in the installer and in every official mod repository (.github/scripts/registry.sh): change
# all of them together.

REGISTRY_REPO="${REGISTRY_REPO:-Stein-Project/registry}"
# Where the registry's workflow publishes it, signed: what the programs read and what an index's "source" points to.
REGISTRY_URL="${REGISTRY_URL:-https://stein-project.github.io/registry/}"

# registry_get <path> <out file>: the file as it is on the registry's main branch (fails when it does not exist).
registry_get() {
  local path="$1" out="$2" token="${REGISTRY_TOKEN:-${GH_TOKEN:-}}"
  GH_TOKEN="$token" gh api -H "Accept: application/vnd.github.raw" "repos/$REGISTRY_REPO/contents/$path" > "$out"
}

# registry_put <path> <local file> <commit message>
registry_put() {
  local path="$1" file="$2" msg="$3" current sha
  command jq -e . "$file" > /dev/null || { echo "::error::$file is not valid JSON"; return 1; }
  if [ "${ENSAIO:-false}" = true ]; then
    echo "registry $REGISTRY_REPO/$path would become (dry run, nothing written):"
    cat "$file"
    return 0
  fi
  [ -n "${REGISTRY_TOKEN:-}" ] \
    || { echo "::error::the REGISTRY_TOKEN secret is missing (Contents: write on $REGISTRY_REPO)"; return 1; }
  current="$(GH_TOKEN="$REGISTRY_TOKEN" gh api "repos/$REGISTRY_REPO/contents/$path" 2> /dev/null || true)"
  sha="$(command jq -r '.sha // empty' <<< "$current" 2> /dev/null | tr -d '\r' || true)"
  if [ -n "$sha" ] \
      && [ "$(command jq -r .content <<< "$current" | base64 -d | command jq -S . | tr -d '\r')" \
           = "$(command jq -S . "$file" | tr -d '\r')" ]; then
    echo "registry $path already up to date"
    return 0
  fi
  local args=(-X PUT "repos/$REGISTRY_REPO/contents/$path"
    -f message="$msg"
    -f content="$(base64 < "$file" | tr -d '\r\n')"
    -f "committer[name]=Breno Xavier" -f "committer[email]=41960989+brenoxavier@users.noreply.github.com"
    -f "author[name]=Breno Xavier" -f "author[email]=41960989+brenoxavier@users.noreply.github.com")
  [ -n "$sha" ] && args+=(-f sha="$sha")
  GH_TOKEN="$REGISTRY_TOKEN" gh api "${args[@]}" > /dev/null
  echo "registry $path updated"
}

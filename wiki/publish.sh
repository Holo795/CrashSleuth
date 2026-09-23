#!/usr/bin/env bash
# Pushes the pages of wiki/ to the GitHub wiki.
#
# GitHub only creates the wiki repository once a first page exists, and there is no API for that: go to
# https://github.com/Holo795/CrashSleuth/wiki once, click "Create the first page", save anything. After
# that this script keeps the wiki and docs/ in step, from the same files.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
remote="https://github.com/Holo795/CrashSleuth.wiki.git"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet "$remote" "$work"
find "$work" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp "$here"/*.md "$work/"
rm -f "$work/publish.sh"
mkdir -p "$work/images"
cp "$here"/images/* "$work/images/"

cd "$work"
git add -A
if git diff --cached --quiet; then
  echo "wiki already up to date"
else
  git -c user.name="Holo795" -c user.email="paulinsegura79@gmail.com" \
      commit -qm "docs: the pages of wiki/, as published on the site"
  git push --quiet
  echo "wiki updated: https://github.com/Holo795/CrashSleuth/wiki"
fi

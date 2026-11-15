#!/usr/bin/env bash
# Fails when release/NOTES.md gives the release a name an earlier release has. The release
# workflow refuses such a release too, but only after the merge.
set -euo pipefail

version=$(tr -d '[:space:]' < release/VERSION)
name=$(sed -n '1s/^# *//p' release/NOTES.md)
if [ -z "$name" ]; then
  echo "::error::release/NOTES.md must start with '# ' and the release's name."
  exit 1
fi
releases=$(gh release list --repo "${GITHUB_REPOSITORY:-hazymorning/Amsel}" --limit 1000 --json name,tagName)
taken=$(jq -r --arg version "$version" --arg name "$name" \
  '.[] | select(.tagName != $version and (.name | split(" • ")[0]) == $name) | .tagName' <<< "$releases")
if [ -n "$taken" ]; then
  echo "::error::$taken is already called '$name'; give $version a name of its own in release/NOTES.md."
  exit 1
fi
echo "$version: $name"

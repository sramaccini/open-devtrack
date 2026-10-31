#!/usr/bin/env bash
# Downloads the JSON Schemas of a Quadrava release into the classpath of the uiregistry module (ADR-03): the registry
# validates the documents with the same schemas as the runtime image of that version (ADR-01).
#
#   backend/tools/sync-quadrava-schemas.sh 0.1.0
#
# Run it when the pinned Quadrava version changes, and commit the result together with the new version.
set -euo pipefail

version="${1:?usage: $0 <quadrava version, for example 0.1.0>}"
target="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/quadrava/schemas"
base="https://raw.githubusercontent.com/sramaccini/quadrava/v$version/frontend/projects/schema/src/lib/schemas"

mkdir -p "$target"
for kind in screen datasources navigation theme messages; do
  curl -fsS -o "$target/$kind.schema.json" "$base/$kind.schema.json"
done
echo "$version" > "$target/QUADRAVA_VERSION"
echo "Quadrava $version schemas in $target"

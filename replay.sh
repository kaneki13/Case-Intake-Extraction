#!/usr/bin/env bash
# Rebuilds out/ from the recorded fixtures, the documents and a catalog. No API key, no network.
# Usage: ./replay.sh [catalog]   (default catalog/field_catalog.json)
# Optional: REPLAY_OUT=<dir> writes the outputs there instead of out/ (used by tests).
set -euo pipefail

catalog="${1:-catalog/field_catalog.json}"
# A relative catalog path is taken from the caller's directory, not the repo root.
if [[ "$catalog" != /* && -e "$catalog" ]]; then
  catalog="$(cd "$(dirname "$catalog")" && pwd)/$(basename "$catalog")"
fi

out_args=()
if [[ -n "${REPLAY_OUT:-}" ]]; then
  # Like the catalog, a relative REPLAY_OUT is taken from the caller's directory.
  out_dir="$REPLAY_OUT"
  [[ "$out_dir" == /* ]] || out_dir="$PWD/$out_dir"
  out_args=(--out="$out_dir")
fi

cd "$(dirname "$0")"

jar=target/case-intake.jar
if [[ ! -f "$jar" || -n "$(find src pom.xml -newer "$jar" -print -quit)" ]]; then
  ./mvnw -q -DskipTests package
fi

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$java_bin" -jar "$jar" replay --app.catalog="$catalog" ${out_args[@]+"${out_args[@]}"}

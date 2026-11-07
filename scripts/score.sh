#!/usr/bin/env bash
# Scores out/ against the answer files with the exercise checker (LLD 13). Extra arguments go to the checker.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/check.py --catalog catalog/field_catalog.json --answers answers --out out --docs documents "$@"

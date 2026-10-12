#!/usr/bin/env bash
set -euo pipefail
stamp=$1
sha=$2
python3 /tmp/yfuse-fast-pull-20261012.py "/opt/yfuse-calendar-renderer/releases/${stamp}-${sha:0:9}/compose.yaml"

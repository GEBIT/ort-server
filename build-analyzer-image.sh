#!/usr/bin/env bash
#
# Kept for backwards compatibility. Builds the Analyzer worker images only.
# Use build-worker-images.sh to build the Analyzer and Advisor worker images.
#
# Usage:
#   ./build-analyzer-image.sh [ORT_SERVER_DIR]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

exec "${SCRIPT_DIR}/build-worker-images.sh" analyzer "$@"

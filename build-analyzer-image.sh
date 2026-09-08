#!/usr/bin/env bash
#
# Builds the Docker images required to run the ORT Server Analyzer worker
# with our custom plugins (GebitPackageCurationProvider, NpmNoDevDependencies,
# MavenNoTestDependencies):
#
#   1. ort-server-base-image           - common base image (Java, certs, etc.)
#   2. ort-server-analyzer-worker-base-image - Analyzer tooling on top of the base image
#   3. ort-server-analyzer-worker       - the actual worker image, built via Gradle/Jib
#      on top of the base image, containing the worker's Java/Kotlin code
#
# Usage:
#   ./build-analyzer-image.sh [ORT_SERVER_DIR]
#
# ORT_SERVER_DIR defaults to .

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ORT_SERVER_DIR="${1:-${SCRIPT_DIR}}"

# Pinned to the base image published for the upstream ORT Server 0.91.0 release tag (commit
# 656bfd93df591231e67c4ac0413954da1b2319fd), verified to exist at
# ghcr.io/eclipse-apoapsis/ort-server-base-image:sha-656bfd9 (an equivalent "0.91.0"-tagged alias of the same
# image is also published, but the sha- form matches this script's existing pinning style).
BASE_IMAGE_TAG="sha-656bfd9"
BASE_IMAGE_NAME="ort-server-base-image:${BASE_IMAGE_TAG}"
ANALYZER_BASE_IMAGE_NAME="ort-server-analyzer-worker-base-image:${BASE_IMAGE_TAG}"

if [[ ! -d "${ORT_SERVER_DIR}" ]]; then
    echo "ORT Server checkout not found at: ${ORT_SERVER_DIR}" >&2
    exit 1
fi

echo "==> Using ORT Server checkout: ${ORT_SERVER_DIR}"

echo "==> [1/3] Building common base image: ${BASE_IMAGE_NAME}"
docker build \
    "${ORT_SERVER_DIR}/docker" \
    -f "${ORT_SERVER_DIR}/docker/Base.Dockerfile" \
    -t "${BASE_IMAGE_NAME}"

echo "==> [2/3] Building Analyzer base image: ${ANALYZER_BASE_IMAGE_NAME}"
docker build \
    "${ORT_SERVER_DIR}/workers/analyzer/docker" \
    -f "${ORT_SERVER_DIR}/workers/analyzer/docker/Analyzer.Dockerfile" \
    --build-arg BASE_REGISTRY= \
    --build-arg BASE_IMAGE_TAG="${BASE_IMAGE_TAG}" \
    -t "${ANALYZER_BASE_IMAGE_NAME}"

echo "==> [3/3] Building Analyzer worker image via Gradle/Jib"
(
    cd "${ORT_SERVER_DIR}"
    ./gradlew :workers:analyzer:tinyJibDocker \
        -Djib.from.image="docker://${ANALYZER_BASE_IMAGE_NAME}" \
        -Djib.to.image=ort-server-analyzer-worker:latest
)

echo "==> Done. Built images:"
echo "    ${BASE_IMAGE_NAME}"
echo "    ${ANALYZER_BASE_IMAGE_NAME}"
echo "    ort-server-analyzer-worker:latest"

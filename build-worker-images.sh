#!/usr/bin/env bash
#
# Builds the Docker images required to run the ORT Server Analyzer and Advisor
# workers with our custom plugins:
#
#   - Analyzer worker: GebitPackageCurationProvider, NpmNoDevDependencies,
#     MavenNoTestDependencies, Ivy
#   - Advisor worker: LicenseObligation
#
# Images built:
#
#   1. ort-server-base-image                 - common base image (Java, certs, etc.)
#   2. ort-server-analyzer-worker-base-image - Analyzer tooling on top of the base image
#                                              (analyzer only)
#   3. ort-server-analyzer-worker            - the Analyzer worker, built via Gradle/Jib
#   4. ort-server-advisor-worker             - the Advisor worker, built via Gradle/Jib
#                                              directly on the common base image
#
# Usage:
#   ./build-worker-images.sh [analyzer|advisor|all] [ORT_SERVER_DIR]
#
# The first argument selects the worker(s) to build and defaults to "all".
# ORT_SERVER_DIR defaults to the directory of this script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

TARGET="${1:-all}"
ORT_SERVER_DIR="${2:-${SCRIPT_DIR}}"

case "${TARGET}" in
    analyzer|advisor|all) ;;
    *)
        echo "Unknown target: ${TARGET} (expected analyzer, advisor or all)" >&2
        exit 1
        ;;
esac

# Pinned to the base image published for the upstream ORT Server 0.91.0 release.
BASE_IMAGE_TAG="0.91.0"
BASE_IMAGE_NAME="ort-server-base-image:${BASE_IMAGE_TAG}"
ANALYZER_BASE_IMAGE_NAME="ort-server-analyzer-worker-base-image:${BASE_IMAGE_TAG}"

if [[ ! -d "${ORT_SERVER_DIR}" ]]; then
    echo "ORT Server checkout not found at: ${ORT_SERVER_DIR}" >&2
    exit 1
fi

echo "==> Using ORT Server checkout: ${ORT_SERVER_DIR}"

echo "==> Building common base image: ${BASE_IMAGE_NAME}"
docker build \
    "${ORT_SERVER_DIR}/docker" \
    -f "${ORT_SERVER_DIR}/docker/Base.Dockerfile" \
    -t "${BASE_IMAGE_NAME}"

BUILT_IMAGES=("${BASE_IMAGE_NAME}")

if [[ "${TARGET}" == "analyzer" || "${TARGET}" == "all" ]]; then
    echo "==> Building Analyzer base image: ${ANALYZER_BASE_IMAGE_NAME}"
    docker build \
        "${ORT_SERVER_DIR}/workers/analyzer/docker" \
        -f "${ORT_SERVER_DIR}/workers/analyzer/docker/Analyzer.Dockerfile" \
        --build-arg BASE_REGISTRY= \
        --build-arg BASE_IMAGE_TAG="${BASE_IMAGE_TAG}" \
        -t "${ANALYZER_BASE_IMAGE_NAME}"

    echo "==> Building Analyzer worker image via Gradle/Jib"
    (
        cd "${ORT_SERVER_DIR}"
        ./gradlew :workers:analyzer:tinyJibDocker \
            -Djib.from.image="docker://${ANALYZER_BASE_IMAGE_NAME}" \
            -Djib.to.image=ort-server-analyzer-worker:latest
    )

    BUILT_IMAGES+=("${ANALYZER_BASE_IMAGE_NAME}" "ort-server-analyzer-worker:latest")
fi

if [[ "${TARGET}" == "advisor" || "${TARGET}" == "all" ]]; then
    echo "==> Building Advisor worker image via Gradle/Jib"
    (
        cd "${ORT_SERVER_DIR}"
        ./gradlew :workers:advisor:tinyJibDocker \
            -Djib.from.image="docker://${BASE_IMAGE_NAME}" \
            -Djib.to.image=ort-server-advisor-worker:latest
    )

    BUILT_IMAGES+=("ort-server-advisor-worker:latest")
fi

echo "==> Done. Built images:"
printf '    %s\n' "${BUILT_IMAGES[@]}"

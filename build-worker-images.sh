#!/usr/bin/env bash
#
# Builds the Docker images required to run the ORT Server Analyzer and Reporter
# workers with our custom plugins:
#
#   - Analyzer worker: GebitPackageCurationProvider, NpmNoDevDependencies,
#     MavenNoTestDependencies, Ivy
#   - Reporter worker: CycloneDxLicenseObligation
#
# Images built:
#
#   1. ort-server-base-image                 - common base image (Java, certs, etc.)
#   2. ort-server-analyzer-worker-base-image - Analyzer tooling on top of the base image
#                                              (analyzer only)
#   3. ort-server-analyzer-worker            - the Analyzer worker, built via Gradle/Jib
#   4. ort-server-reporter-worker-base-image - Reporter tooling on top of the base image
#   5. ort-server-reporter-worker            - the Reporter worker, built via Gradle/Jib
#
# Usage:
#   ./build-worker-images.sh [analyzer|reporter|all] [ORT_SERVER_DIR]
#
# The first argument selects the worker(s) to build and defaults to "all".
# ORT_SERVER_DIR defaults to the directory of this script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

TARGET="${1:-all}"
ORT_SERVER_DIR="${2:-${SCRIPT_DIR}}"

case "${TARGET}" in
    analyzer|reporter|all) ;;
    *)
        echo "Unknown target: ${TARGET} (expected analyzer, reporter or all)" >&2
        exit 1
        ;;
esac

# Pinned to the base image published for the upstream ORT Server 0.91.0 release.
BASE_IMAGE_TAG="0.91.0"
BASE_IMAGE_NAME="ort-server-base-image:${BASE_IMAGE_TAG}"
ANALYZER_BASE_IMAGE_NAME="ort-server-analyzer-worker-base-image:${BASE_IMAGE_TAG}"
REPORTER_BASE_IMAGE_NAME="ort-server-reporter-worker-base-image:${BASE_IMAGE_TAG}"

# Overrides the Go version of Analyzer.Dockerfile (1.25.0): it installs buildozer@latest, which now requires Go 1.26.
GO_VERSION="1.26.0"

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
        --build-arg GO_VERSION="${GO_VERSION}" \
        -t "${ANALYZER_BASE_IMAGE_NAME}"

    echo "==> Building Analyzer worker image via Gradle/Jib"
    (
        cd "${ORT_SERVER_DIR}"
        ./gradlew :workers:analyzer:tinyJibDocker --rerun \
            -Djib.from.image="docker://${ANALYZER_BASE_IMAGE_NAME}" \
            -Djib.to.image=ort-server-analyzer-worker:latest
    )

    BUILT_IMAGES+=("${ANALYZER_BASE_IMAGE_NAME}" "ort-server-analyzer-worker:latest")
fi

if [[ "${TARGET}" == "reporter" || "${TARGET}" == "all" ]]; then
    echo "==> Building Reporter base image: ${REPORTER_BASE_IMAGE_NAME}"
    docker build \
        "${ORT_SERVER_DIR}/workers/reporter/docker" \
        -f "${ORT_SERVER_DIR}/workers/reporter/docker/Reporter.Dockerfile" \
        --build-arg BASE_REGISTRY= \
        --build-arg BASE_IMAGE_TAG="${BASE_IMAGE_TAG}" \
        -t "${REPORTER_BASE_IMAGE_NAME}"

    echo "==> Building Reporter worker image via Gradle/Jib"
    (
        cd "${ORT_SERVER_DIR}"
        ./gradlew :workers:reporter:tinyJibDocker --rerun \
            -Djib.from.image="docker://${REPORTER_BASE_IMAGE_NAME}" \
            -Djib.to.image=ort-server-reporter-worker:latest
    )

    BUILT_IMAGES+=("${REPORTER_BASE_IMAGE_NAME}" "ort-server-reporter-worker:latest")
fi

echo "==> Done. Built images:"
printf '    %s\n' "${BUILT_IMAGES[@]}"

#!/usr/bin/env bash
#
# Builds the Docker images required to run the ORT Server Analyzer, Config and
# Reporter workers with our custom plugins:
#
#   - Analyzer worker: GebitPackageCurationProvider, NpmNoDevDependencies,
#     MavenNoTestDependencies, Ivy
#   - Config worker: CycloneDxLicenseObligation (needed to validate the report
#     format requested by the run's job configuration)
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
#   6. ort-server-config-worker              - the Config worker, built via Gradle/Jib on top of
#                                              the common base image
#
# The worker images are tagged ${REGISTRY}/ort-server-<worker>-worker:gebit-<ORT_SERVER_VERSION>; REGISTRY can be
# overridden via the environment.
#
# Usage:
#   ./build-worker-images.sh [analyzer|config|reporter|all] [ORT_SERVER_DIR]
#
# The first argument selects the worker(s) to build and defaults to "all".
# ORT_SERVER_DIR defaults to the directory of this script.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"

TARGET="${1:-all}"
ORT_SERVER_DIR="${2:-${SCRIPT_DIR}}"

case "${TARGET}" in
    analyzer|config|reporter|all) ;;
    *)
        echo "Unknown target: ${TARGET} (expected analyzer, config, reporter or all)" >&2
        exit 1
        ;;
esac

# Pinned to the upstream ORT Server release this checkout is based on. The tag only names the local base images.
ORT_SERVER_VERSION="0.104.0"
BASE_IMAGE_TAG="${ORT_SERVER_VERSION}"
BASE_IMAGE_NAME="ort-server-base-image:${BASE_IMAGE_TAG}"
ANALYZER_BASE_IMAGE_NAME="ort-server-analyzer-worker-base-image:${BASE_IMAGE_TAG}"
REPORTER_BASE_IMAGE_NAME="ort-server-reporter-worker-base-image:${BASE_IMAGE_TAG}"

# The final worker images are tagged "gebit-<version>" in the GEBIT registry namespace. They are only built locally;
# push them manually, then ort-server-service/scripts/k8s/mirror-images.sh re-tags them as "<version>-gebit".
REGISTRY="${REGISTRY:-docker-registry.local.gebit.de:5002/gebit-build}"
WORKER_TAG="gebit-${ORT_SERVER_VERSION}"
ANALYZER_WORKER_IMAGE="${REGISTRY}/ort-server-analyzer-worker:${WORKER_TAG}"
CONFIG_WORKER_IMAGE="${REGISTRY}/ort-server-config-worker:${WORKER_TAG}"
REPORTER_WORKER_IMAGE="${REGISTRY}/ort-server-reporter-worker:${WORKER_TAG}"

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
        ./gradlew :workers:analyzer:tinyJibDocker --rerun \
            -Djib.from.image="docker://${ANALYZER_BASE_IMAGE_NAME}" \
            -Djib.to.image="${ANALYZER_WORKER_IMAGE}"
    )

    BUILT_IMAGES+=("${ANALYZER_BASE_IMAGE_NAME}" "${ANALYZER_WORKER_IMAGE}")
fi

if [[ "${TARGET}" == "config" || "${TARGET}" == "all" ]]; then
    echo "==> Building Config worker image via Gradle/Jib"
    (
        cd "${ORT_SERVER_DIR}"
        ./gradlew :workers:config:tinyJibDocker --rerun \
            -Djib.from.image="docker://${BASE_IMAGE_NAME}" \
            -Djib.to.image="${CONFIG_WORKER_IMAGE}"
    )

    BUILT_IMAGES+=("${CONFIG_WORKER_IMAGE}")
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
            -Djib.to.image="${REPORTER_WORKER_IMAGE}"
    )

    BUILT_IMAGES+=("${REPORTER_BASE_IMAGE_NAME}" "${REPORTER_WORKER_IMAGE}")
fi

echo "==> Done. Built images:"
printf '    %s\n' "${BUILT_IMAGES[@]}"

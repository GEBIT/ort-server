# License Obligation Advisor Design

## Goal

ORT Server is used solely as an SBOM generator; all violation and policy
handling happens in Dependency-Track. Add an ORT advisor plugin that checks
whether the **distributed artifact** (the binary Maven artifact, not the VCS and
not the sources JAR) of every package contains the legal files its license
requires, and reports missing ones so they end up in the CycloneDX SBOM.

## Non-goals

- Checking VCS checkouts or sources JARs.
- Detecting or reporting dependencies bundled inside shaded/fat JARs. Such JARs
  are treated like any other JAR (see "Future extension").
- Evaluating licenses or license policy. Dependency-Track does that from the
  license data already in the SBOM.
- Verifying that license text content matches the declared license (only
  presence of files is checked).

## Why an advisor

The stock ORT `CycloneDxReporter` (92.6.0, as pinned in `settings.gradle.kts`)
exports components (with licenses and `ort:effectiveLicense`) and the
advisor results from `OrtResult.getVulnerabilities()` as CycloneDX
`vulnerabilities` whose `affects.ref` is the component's coordinates. It does
**not** export scan issues, scanner findings or custom properties. A scanner
plugin's result would therefore never reach the SBOM, whereas advisor
vulnerabilities flow through the existing `cyclonedx` report format without any
reporter changes, and Dependency-Track ingests them as vulnerabilities (policy
conditions, notifications, auditing/suppression).

## Findings

Findings are returned as ORT `Vulnerability` objects. The `id` is the custom
identifier; the severity is carried in a `VulnerabilityReference`.

| Id | Severity | Condition |
|---|---|---|
| `LIC_OBL_MISSING_LICENSE` | HIGH | The artifact has no license file (defect). |
| `LIC_OBL_MISSING_NOTICE` | LOW | The effective license requires a NOTICE and the artifact has none. |
| `LIC_OBL_ARTIFACT_UNAVAILABLE` | LOW | The binary artifact could not be downloaded or opened, so nothing could be verified. |

Each vulnerability has a human-readable `description` (what is missing, which
license triggered the rule, which artifact URL was inspected) and a single
reference with `scoringSystem = "Other"`, the severity, and a URL to the
internal policy documentation (configurable).

Notes:

- The `LIC_OBL_` prefix keeps these ids disjoint from CVE/GHSA/OSV ids from
  other analyzers, so Dependency-Track policies can match on it.
- `LIC_OBL_ARTIFACT_UNAVAILABLE` exists so that "could not check" is never
  mistaken for "fine". It is emitted rather than failing the run.
- Because the CycloneDX mapping only emits `ratings` per reference, the
  severity must be set on the reference; the reporter drops nothing else we
  need.

## Rules

Evaluation is split into two steps so the cache can store facts and not
verdicts (see "Cache").

1. **Fact extraction** (`ArtifactLegalFacts`) from the artifact archive:
   - `licenseFiles`: entries matching the license file patterns.
   - `noticeFiles`: entries matching the notice file patterns.
2. **Rule evaluation** (`ObligationRules`), a pure function of the facts, the
   package's effective license and the configuration.

Default patterns, matched case-insensitively against archive entries at the
archive root or under `META-INF/`, optionally with an extension (`.txt`,
`.md`, `.html`):

- license: `LICENSE`, `LICENCE`, `COPYING`
- notice: `NOTICE`, `NOTICES`

### Obligations checkable per license

Derived from `obligation_classification` in
`ort-server-scripts/license_report/config/license-classifications-gebit.yml`
(103 classified licenses). Only obligations verifiable from the *presence of
files* in the distributed artifact are checked:

| Obligation | Licenses | Check | Finding |
|---|---|---|---|
| Include license text | Practically all permissive-with-obligations and weak-copyleft licenses (MIT, BSD-*, ISC, Apache-*, W3C, Zlib, EPL, MPL, CDDL, CPL, LGPL, ...) | License file present | `LIC_OBL_MISSING_LICENSE` (HIGH) |
| Propagate NOTICE | `Apache-2.0`, `ECL-2.0` | Notice file present | `LIC_OBL_MISSING_NOTICE` (LOW) |

Since the license file check applies to every package regardless of license,
the table only documents why it is meaningful for the policy; the NOTICE
check is driven by `noticeRequiredLicenses`.

Not checkable by the advisor (need human judgement or are not a property of
the artifact), and therefore left to Dependency-Track's license policy or manual
review: no-endorsement and advertising clauses (BSD-3/4, Apache-1.1), marking
changed files (Apache-2.0, FTL, PSF), file-level copyleft on modifications
(EPL, MPL, CDDL, CPL), source offers and relinking (GPL, LGPL, AGPL, EUPL),
relicensing/share-alike (CC-BY-SA), commercial agreements and change dates
(BUSL, FSL, Oracle FUTC, vendor SDKs), and the semantic restrictions of `JSON`
and `Beerware`.

Possible later check (not in v1): `LIC_OBL_MISSING_COPYRIGHT` (LOW) for the
"retain copyright notice" family (MIT, BSD, ISC, BSL), which requires reading
the license file content and extending the cached facts
(`extractorVersion` bump).

`LICENSE` missing is reported for every package, independent of license, since
every license requires its text to be distributed. `NOTICE` is checked only for
licenses configured as NOTICE-requiring (default: `Apache-2.0`, `ECL-2.0`).

The effective license is `declaredLicensesProcessed.spdxExpression` of the
package, with the concluded license taken from ORT curations if present, as
already provided on the `Package`. For `OR` expressions the obligation applies
only if every alternative requires it (the consumer may choose the license with
fewer obligations). For `AND` it applies if any operand requires it.

A NOTICE is only legally required by Apache-2.0 when the upstream has one, which
the artifact alone cannot tell. This is why `LIC_OBL_MISSING_NOTICE` is LOW
rather than a defect.

Packages are skipped (no findings, no download) when:

- the package type is not `Maven`;
- `Package.isMetadataOnly` is true;
- the package has no usable `binaryArtifact` (reported as
  `LIC_OBL_ARTIFACT_UNAVAILABLE`, not skipped, unless metadata-only).

### Artifact types

The checks are applied to whatever `Package.binaryArtifact` points at: `jar`,
`war`, `aar`, and `ear`. POM-only artifacts (`packaging = pom`) are skipped
because they carry no distributed code.

## Architecture

New module `shared/advisors`, following the layout of `shared/reporters` and
`shared/package-curation-providers`:

```
shared/advisors/
  build.gradle.kts            # ksp(ortLibs.ortPlugins.compiler), api(advisors.api)
  src/main/kotlin/
    LicenseObligationAdvisor.kt         # @OrtPlugin AdviceProvider + config
    ArtifactDownloader.kt               # fetch to temp file, checksum verify
    ArtifactInspector.kt                # facts from archive (streamed ZipInputStream)
    ObligationRules.kt                  # facts + license -> findings
    LicenseObligationCache.kt           # interface + file implementation
```

The advisor worker lists its advisors by name and resolves them through
`AdviceProviderFactory.ALL` (`AdvisorRunner`), so the plugin only has to be on
the worker's runtime classpath. Add it in `workers/advisor/build.gradle.kts`:

```kotlin
runtimeOnly(projects.shared.advisors)
```

Users enable it by adding `LicenseObligation` to the advisors of a run (the id
generated by KSP from the plugin descriptor).

`retrievePackageFindings(packages)`:

1. Filter packages as described under "Rules".
2. For each package, concurrently (bounded by `maxParallelDownloads`):
   look up facts in the cache by key; on miss, download, extract facts, store.
3. Evaluate rules and build the `AdvisorResult` (with `AdvisorDetails` naming
   the plugin and a `capabilities = VULNERABILITIES`-style entry) for packages
   with findings only; the API contract returns just those.

### Extraction details

- Stream the archive with `ZipInputStream`; never extract to disk, and only
  read entry **names**, so size is bounded by download time, not memory.
- Apply guards against hostile archives: a maximum entry count (default
  100000) and a maximum download size (default 512 MB); exceeding either
  yields `LIC_OBL_ARTIFACT_UNAVAILABLE` with the reason in the description.
- Verify the download against `Package.binaryArtifact.hash` when present; a
  mismatch is treated as unavailable.

### Artifact access

The advisor needs network access to the artifact repositories (Maven Central
and any internal Nexus). Credentials follow the existing mechanism for
authenticated repositories: the plugin accepts `secrets` through the standard
`PluginConfig` (`username`, `password`), resolved by the worker's existing
secret handling. Pulling through the Nexus proxy is recommended so downloads
are cached close to the worker.

## Cache

The advisor worker is a permanently running container (`advisor-worker` in
`ort-server-service/docker-compose.yml`, restart policy `unless-stopped`), but
it must survive container re-creation (image updates) and, since the same
artifacts recur across projects and runs, should persist on disk. The cache
stores **facts**, not verdicts, so that changing rules, patterns or severities
never requires invalidation.

- **Key:** `sha1(binaryArtifact.url + hash value + extractorVersion)`. The
  hash makes entries immutable per artifact content; `extractorVersion` is a
  constant bumped when the extraction logic or default patterns change.
- Artifacts without a verifiable hash, including a blank hash value, are not cached.
- **Value:** small JSON: `{ "licenseFiles": [...], "noticeFiles": [...],
  "entryCount": n, "extractorVersion": 1 }`. The artifact itself is never
  cached.
- **Failures:** `LIC_OBL_ARTIFACT_UNAVAILABLE` results are never cached, so
  transient failures do not stick.
- **Interface:** `LicenseObligationCache { get(key): ArtifactLegalFacts?;
  put(key, facts) }`, so the backing store can change without touching the
  rest of the plugin.
- **Default implementation:** file-based, one JSON file per key below the
  `cacheDir` option (default: `/home/ort/.cache/advisor`; an empty value
  disables caching, and tests use a temp dir). Writes are atomic (temp file +
  rename), so concurrent runs within the worker are safe. If the directory
  cannot be created or written, the plugin logs a warning and continues
  without cache instead of failing the run.
- **Deployment:** a Docker named volume on `advisor-worker`, no init container.
  In `docker-compose.yml`:
  ```yaml
  advisor-worker:
    volumes:
      - advisor-cache:/home/ort/.cache
  volumes:
    advisor-cache:
      driver: local
  ```
  and the plugin's `cacheDir` default (`/home/ort/.cache/advisor`) already
  points there, so no plugin config is needed; the plugin creates the
  `advisor` subdirectory itself (`mkdirs`).
  The mount path matters: `docker/Base.Dockerfile` creates `/home/ort/.cache`
  owned by `ort` (UID 1000), and Docker copies that ownership into a new empty
  named volume. A path that does not exist in the image (e.g. a subdirectory
  of `/home/ort`) would be created root-owned and not be writable. Mounting
  the whole `/home/ort` is avoided because it would persist runtime-generated
  files such as credentials. Verified with
  `ort-server-advisor-worker:main`: `ort` can write to the volume and the data
  survives a new container. A custom advisor image must keep
  `/home/ort/.cache` owned by `ort`. The volume survives container
  re-creation and can be reset with `docker volume rm`.
- **Growth:** entries are tiny (a few hundred bytes), so no eviction is
  needed in v1. The directory can be deleted at any time; it is rebuilt on
  demand.

Alternative considered and deferred: storing facts in the ORT Server database
through the `storage`/`dao` modules, like the scanner worker does with its
provenance storages. It requires worker-side code and a migration, and the
shared plugin module must not depend on the server's internal modules. With a
single long-running advisor worker and a host directory already used for the
analyzer, it brings no benefit. The `LicenseObligationCache` interface leaves
this door open.

## Plugin options

| Option | Default | Description |
|---|---|---|
| `licenseFilePatterns` | see Rules | Entry name patterns for license files. |
| `noticeFilePatterns` | see Rules | Entry name patterns for notice files. |
| `noticeRequiredLicenses` | `Apache-2.0`, `ECL-2.0` | SPDX ids requiring a NOTICE. |
| `cacheDir` | `/home/ort/.cache/advisor` | Directory of the file cache; empty disables it. |
| `maxParallelDownloads` | `4` | Concurrency limit. |
| `maxArchiveBytes` | `536870912` | Download size guard. |
| `maxArchiveEntries` | `100000` | Entry count guard. |
| `policyUrl` | none | Reference URL attached to findings. |
| secrets `username`, `password` | none | Repository credentials. |

## Dependency-Track usage

Operational notes for the policy side; no code is needed:

- Findings appear as vulnerabilities from the BOM with ids `LIC_OBL_*` and the
  ratings' severities. Policies can match on severity, and alerts can be limited
  to these ids by a naming convention.
- Dependency-Track's own analyzers do not know the ids and will not overwrite
  them. To be confirmed on the target version with a sample BOM: that
  re-upload keeps audit/suppression state for unchanged findings.
- Accepted cases (e.g. an upstream that legitimately ships no NOTICE) are
  suppressed per component in Dependency-Track, or removed at the source by a
  future ORT-side configuration.

## Future extension: shaded/fat JARs

Shaded and fat JARs are treated as normal JARs: only the artifact's own
legal files are checked, and bundled third-party code is neither reported nor
inspected. The design is prepared for later extension:

- Fact extraction already walks all archive entry names. A later
  `ArtifactLegalFacts.bundledArtifacts` field can be filled from
  `META-INF/maven/<groupId>/<artifactId>/pom.properties`, which shade, Shadow
  and Spring Boot builds embed for each bundled artifact even after relocation.
  Adding a field and bumping `extractorVersion` invalidates old cache entries
  safely.
- `ObligationRules` can then add findings such as
  `LIC_OBL_BUNDLED_DEPENDENCY_UNDECLARED` for bundled artifacts missing from
  the SBOM and bundled artifacts without a matching license/NOTICE entry.
- Components for bundled artifacts would need a reporter extension, since the
  stock CycloneDX reporter only writes components from the ORT result.

## Testing

Unit tests (Kotest, JUnit 5; test archives generated in-memory, no network):

- `ArtifactInspector`: license at the root and under `META-INF/`, case
  variants, extensions, nested directories not matching, empty archive,
  corrupt archive, entry-count guard.
- `ObligationRules`: Apache-2.0 with/without NOTICE, MIT without NOTICE not
  flagged, `OR`/`AND`/`WITH` expressions, missing license for every license,
  severities and ids.
- `LicenseObligationAdvisor` through `AdviceProviderFactory.ALL` with a local
  HTTP server or `file:` URLs: finding mapping, only-packages-with-findings
  contract, non-Maven/metadata-only skipped, hash mismatch, download failure
  producing `LIC_OBL_ARTIFACT_UNAVAILABLE`.
- Cache: hit avoids a download (verified with a counting server), key changes
  with hash and `extractorVersion`, failed lookups not cached,
  concurrent writers.
- A reporter round trip: run the stock `CycloneDxReporter` on an `OrtResult`
  containing a finding and assert the BOM `vulnerabilities` entry (id,
  severity rating, `affects.ref`).

## Open questions for review

1. ~~Is `Package.binaryArtifact` populated?~~ Resolved by assumption: it is
   set for most packages (sample run 12225: 145/145 Maven packages with Nexus
   URL and SHA-1). The plugin uses it directly and does not construct URLs;
   packages without it get `LIC_OBL_ARTIFACT_UNAVAILABLE`.
2. ~~Severity of `LIC_OBL_ARTIFACT_UNAVAILABLE`~~ Decided: LOW.
3. ~~NOTICE-requiring licenses~~ Decided for v1: `Apache-2.0` and `ECL-2.0`
   only (see "Obligations checkable per license"). EPL/MPL are not added; their
   NOTICE-like files are project convention, not a license requirement.
4. ~~Cache storage~~ Decided: file cache on a Docker named volume mounted at
   `/home/ort/.cache` of the permanently running `advisor-worker` container,
   no init container (see "Cache").

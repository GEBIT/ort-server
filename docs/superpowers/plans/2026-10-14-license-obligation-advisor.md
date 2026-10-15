# License Obligation Advisor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ORT advisor plugin `LicenseObligation` that inspects the distributed binary artifact of every Maven package and reports missing LICENSE/NOTICE files as `LIC_OBL_*` vulnerabilities, which the stock CycloneDX reporter writes into the SBOM for Dependency-Track.

**Architecture:** A new Gradle module `shared/advisors` holds an `@OrtPlugin` `AdviceProvider`. Per package it streams the artifact from `Package.binaryArtifact` (verifying the hash), extracts *facts* (license/notice entry names) with a `ZipInputStream`, caches the facts on disk, and evaluates pure rules (`ObligationRules`) to produce findings. The advisor worker picks the plugin up through `AdviceProviderFactory.ALL` once the module is on its runtime classpath.

**Tech Stack:** Kotlin, ORT 92.6.0 (`advisor-api`, `model`), KSP (`ortPlugins.compiler`), kotlinx.serialization (cache files), kotlinx.coroutines, JDK `HttpClient`, Kotest 6 (`StringSpec`), JDK `HttpServer` for tests.

**Spec:** `docs/superpowers/specs/2026-10-14-license-obligation-advisor-design.md`

---

## Conventions for every task

- Work in `/home/dschiemann/workspace/compliance-reports/ort-server` (the git root). All Gradle commands are `./gradlew ...` from there.
- Package for all new code: `org.eclipse.apoapsis.ortserver.shared.advisors`.
- Every new `.kt` and `.kts` file starts with the standard header (use the current year, 2026):

```kotlin
/*
 * Copyright (C) 2026 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */
```

  The code blocks below omit this header for brevity; add it.
- Import order (detekt enforces it): groups separated by a blank line, in this order: `java.*`/`javax.*`, `kotlin.*`/`kotlinx.*`, `io.*`, `org.eclipse.*`, `org.ossreviewtoolkit.*`, `org.slf4j.*`. Max line length is 120.
- Commit messages must start with a Jira key (server-side hook). Set it once per shell session before committing:
  `export TICKET=DINF-<number>` (use the ticket for this feature; `NO-ISSUE` is the fallback). Commit as `git commit -m "$TICKET: <imperative description>"`.
- Test command pattern: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.<TestClass>"`.

## Spec deviation (decided while planning)

The spec allows caching failed lookups with a TTL (`failedLookupTtl`). This plan implements the simpler variant: **failures are never cached**, and the `failedLookupTtl` option is dropped (YAGNI; unavailable artifacts are rare and retried per run). Task 10 updates the spec accordingly.

## Known gap (not in this plan)

Internal artifacts (e.g. `de.gebit.rp.*`, concluded license `LicenseRef-GEBIT`) probably ship no LICENSE and would produce `LIC_OBL_MISSING_LICENSE` (HIGH). The spec has no exclusion option. If this is unwanted, add a follow-up (e.g. a `skipLicenses` option); do not add it here.

## File structure

| File | Responsibility |
|---|---|
| `shared/advisors/build.gradle.kts` | Module build: KSP, serialization, deps. |
| `shared/advisors/src/main/kotlin/ArtifactLegalFacts.kt` | `ArtifactLegalFacts` (serializable facts) + `EXTRACTOR_VERSION`. |
| `shared/advisors/src/main/kotlin/LegalFilePattern.kt` | Entry-name matching for license/notice files. |
| `shared/advisors/src/main/kotlin/ArtifactInspector.kt` | `ArtifactException` + streaming archive inspection into facts. |
| `shared/advisors/src/main/kotlin/ObligationRules.kt` | `ObligationFinding`, finding ids, pure rules. |
| `shared/advisors/src/main/kotlin/ArtifactDownloader.kt` | HTTP streaming download with size limit, hash verification, basic auth. |
| `shared/advisors/src/main/kotlin/LicenseObligationCache.kt` | Cache interface, no-op and file implementations, key + factory. |
| `shared/advisors/src/main/kotlin/LicenseObligationAdvisor.kt` | Config + `@OrtPlugin` advisor tying everything together. |
| `shared/advisors/src/test/kotlin/*` | Tests + `TestJars.kt` / `TestArtifactServer.kt` helpers. |
| `settings.gradle.kts`, `workers/advisor/build.gradle.kts`, `shared/plugin-info/...`, `../ort-server-service/docker-compose.yml` | Wiring and deployment. |

---

### Task 1: Module skeleton and wiring

**Files:**
- Modify: `settings.gradle.kts` (after line 82-83, keep alphabetical order)
- Create: `shared/advisors/build.gradle.kts`
- Modify: `workers/advisor/build.gradle.kts`
- Modify: `shared/plugin-info/build.gradle.kts`

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, insert before `include(":shared:api-mappings")`:

```kotlin
include(":shared:advisors")
```

- [ ] **Step 2: Create the build file**

Create `shared/advisors/build.gradle.kts` (with the license header, as `shared/package-curation-providers/build.gradle.kts` has):

```kotlin
plugins {
    // Apply precompiled plugins.
    id("ort-server-kotlin-jvm-conventions")
    id("ort-server-publication-conventions")

    // Apply third-party plugins.
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

group = "org.eclipse.apoapsis.ortserver.shared"

dependencies {
    api(ortLibs.ortPlugins.advisors.api)

    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j)
    implementation(ortLibs.utils.common)

    ksp(ortLibs.ortPlugins.compiler)
    ksp(ortLibs.ortPlugins.advisors.api)

    testImplementation(ortLibs.ortPlugins.reporters.cyclonedx)
    testImplementation(ortLibs.reporter)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
```

- [ ] **Step 3: Put the plugin on the advisor worker's runtime classpath**

In `workers/advisor/build.gradle.kts`, in the `dependencies` block, add directly above `runtimeOnly(platform(ortLibs.ortPlugins.advisors))`:

```kotlin
    runtimeOnly(projects.shared.advisors)
```

- [ ] **Step 4: Make the plugin visible to the plugin-info module**

In `shared/plugin-info/build.gradle.kts`, add above `compileOnly(projects.shared.packageCurationProviders)`:

```kotlin
    compileOnly(projects.shared.advisors)
```

- [ ] **Step 5: Verify the build configures**

Run: `./gradlew :shared:advisors:tasks --quiet | head -5`
Expected: task list prints without configuration errors. If the version catalog accessor `ortLibs.ortPlugins.reporters.cyclonedx` is unresolved, print the available accessors with `./gradlew :shared:advisors:dependencies --configuration testCompileClasspath --quiet | head` and use the generated name (the catalog entry is `ortPlugins-reporters-cyclonedx`).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts shared/advisors/build.gradle.kts workers/advisor/build.gradle.kts shared/plugin-info/build.gradle.kts
git commit -m "$TICKET: Add shared advisors module skeleton"
```

---

### Task 2: Facts model and file-name patterns

**Files:**
- Create: `shared/advisors/src/main/kotlin/ArtifactLegalFacts.kt`
- Create: `shared/advisors/src/main/kotlin/LegalFilePattern.kt`
- Test: `shared/advisors/src/test/kotlin/LegalFilePatternTest.kt`

- [ ] **Step 1: Write the failing test**

`LegalFilePatternTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LegalFilePatternTest : StringSpec({
    val pattern = LegalFilePattern(listOf("LICENSE", "LICENCE", "COPYING"))

    "files at the archive root match" {
        pattern.matches("LICENSE") shouldBe true
        pattern.matches("COPYING") shouldBe true
    }

    "files directly below META-INF match" {
        pattern.matches("META-INF/LICENSE") shouldBe true
        pattern.matches("META-INF/LICENCE.txt") shouldBe true
    }

    "matching is case-insensitive" {
        pattern.matches("license") shouldBe true
        pattern.matches("meta-inf/License.MD") shouldBe true
    }

    "the supported extensions match" {
        listOf(".txt", ".md", ".html", ".htm").forEach { ext ->
            pattern.matches("LICENSE$ext") shouldBe true
        }
    }

    "other extensions and name variants do not match" {
        pattern.matches("LICENSE.class") shouldBe false
        pattern.matches("LICENSE-2.0.txt") shouldBe false
        pattern.matches("LICENSEE") shouldBe false
        pattern.matches("MYLICENSE") shouldBe false
    }

    "files in other directories do not match" {
        pattern.matches("com/example/LICENSE") shouldBe false
        pattern.matches("META-INF/versions/9/LICENSE") shouldBe false
        pattern.matches("META-INF/maven/org.example/lib/LICENSE") shouldBe false
    }

    "directories do not match" {
        pattern.matches("LICENSE/") shouldBe false
        pattern.matches("META-INF/") shouldBe false
    }

    "blank base names are ignored" {
        LegalFilePattern(listOf("", " ")).matches("") shouldBe false
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LegalFilePatternTest"`
Expected: FAIL (compilation error: `LegalFilePattern` unresolved).

- [ ] **Step 3: Implement**

`ArtifactLegalFacts.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import kotlinx.serialization.Serializable

/**
 * The version of the fact extraction logic. Bump it whenever [ArtifactInspector] or the default file patterns change,
 * which invalidates all cache entries (the version is part of the cache key).
 */
const val EXTRACTOR_VERSION = 1

/**
 * The facts about an artifact that are relevant for obligation checks. This is what the cache stores; verdicts are
 * derived from it by [ObligationRules], so changing rules never requires cache invalidation.
 */
@Serializable
data class ArtifactLegalFacts(
    val extractorVersion: Int = EXTRACTOR_VERSION,

    /** The number of entries in the archive. */
    val entryCount: Int,

    /** The names of the archive entries that are license files. */
    val licenseFiles: List<String>,

    /** The names of the archive entries that are notice files. */
    val noticeFiles: List<String>
)
```

`LegalFilePattern.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

private const val META_INF = "meta-inf/"
private val EXTENSIONS = listOf(".txt", ".md", ".html", ".htm")

/**
 * Matches archive entry names against a list of legal file base names (e.g. `LICENSE`). An entry matches if it is at
 * the archive root or directly below `META-INF/`, and its name equals a base name, optionally followed by one of the
 * extensions `.txt`, `.md`, `.html` or `.htm`. Matching is case-insensitive.
 */
class LegalFilePattern(baseNames: Collection<String>) {
    private val names = baseNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(entryName: String): Boolean {
        val lowerName = entryName.lowercase()
        val metaInfRelative = lowerName.removePrefix(META_INF)

        val fileName = when {
            '/' !in lowerName -> lowerName
            lowerName.startsWith(META_INF) && '/' !in metaInfRelative -> metaInfRelative
            else -> return false
        }

        return names.any { name -> fileName == name || EXTENSIONS.any { fileName == name + it } }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LegalFilePatternTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add legal file pattern matching for the license obligation advisor"
```

---

### Task 3: Test helpers and archive inspection

**Files:**
- Create: `shared/advisors/src/test/kotlin/TestJars.kt`
- Create: `shared/advisors/src/main/kotlin/ArtifactInspector.kt`
- Test: `shared/advisors/src/test/kotlin/ArtifactInspectorTest.kt`

- [ ] **Step 1: Create the shared test helper**

`TestJars.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Build an in-memory archive with the given (entry name, content) pairs. Names ending in `/` become directories. */
fun buildJar(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()

    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }

    return bytes.toByteArray()
}

/** Return the lower-case hex SHA-1 of [bytes]. */
fun sha1(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
```

- [ ] **Step 2: Write the failing test**

`ArtifactInspectorTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ArtifactInspectorTest : StringSpec({
    fun inspector(maxEntries: Int = 100) = ArtifactInspector(
        licensePattern = LegalFilePattern(listOf("LICENSE", "LICENCE", "COPYING")),
        noticePattern = LegalFilePattern(listOf("NOTICE", "NOTICES")),
        maxEntries = maxEntries
    )

    "license and notice files at the root and in META-INF are found" {
        val jar = buildJar(
            "com/example/Foo.class" to "x",
            "META-INF/LICENSE" to "license",
            "NOTICE.txt" to "notice",
            "META-INF/MANIFEST.MF" to "m"
        )

        val facts = inspector().inspect(jar.inputStream())

        facts.licenseFiles shouldContainExactlyInAnyOrder listOf("META-INF/LICENSE")
        facts.noticeFiles shouldContainExactlyInAnyOrder listOf("NOTICE.txt")
        facts.entryCount shouldBe 4
        facts.extractorVersion shouldBe EXTRACTOR_VERSION
    }

    "an archive without legal files yields empty lists" {
        val facts = inspector().inspect(buildJar("com/example/Foo.class" to "x").inputStream())

        facts.licenseFiles.shouldBeEmpty()
        facts.noticeFiles.shouldBeEmpty()
    }

    "nested legal files and directories are ignored" {
        val jar = buildJar(
            "docs/LICENSE" to "x",
            "LICENSE/" to "",
            "META-INF/" to ""
        )

        val facts = inspector().inspect(jar.inputStream())

        facts.licenseFiles.shouldBeEmpty()
    }

    "an archive with too many entries is rejected" {
        val jar = buildJar("a" to "1", "b" to "2", "c" to "3")

        shouldThrow<ArtifactException> { inspector(maxEntries = 2).inspect(jar.inputStream()) }
            .message shouldContain "more than 2 entries"
    }

    "data that is not an archive is rejected" {
        shouldThrow<ArtifactException> {
            inspector().inspect("<html>404</html>".toByteArray().inputStream())
        }.message shouldContain "no entries"
    }

    "a truncated archive is rejected" {
        val jar = buildJar("LICENSE" to "x".repeat(2000))
        val truncated = jar.copyOf(40)

        shouldThrow<ArtifactException> { inspector().inspect(truncated.inputStream()) }
    }

    "the input stream is not closed so that the caller can drain it" {
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(buildJar("LICENSE" to "x")) {
            override fun close() {
                closed = true
            }
        }

        inspector().inspect(stream)

        closed shouldBe false
    }
})
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ArtifactInspectorTest"`
Expected: FAIL (compilation error: `ArtifactInspector`/`ArtifactException` unresolved).

- [ ] **Step 4: Implement**

`ArtifactInspector.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** An exception for artifacts that cannot be obtained or inspected. A subclass of [IOException] for easy handling. */
class ArtifactException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Extracts [ArtifactLegalFacts] from a ZIP-based artifact (JAR, WAR, AAR, EAR). Only entry *names* are looked at, the
 * content is skipped while streaming, so memory use is independent of the archive size.
 */
class ArtifactInspector(
    private val licensePattern: LegalFilePattern,
    private val noticePattern: LegalFilePattern,
    private val maxEntries: Int
) {
    /**
     * Inspect the archive provided by [input]. The stream is **not** closed, so that the caller can drain the rest of
     * it, e.g. to compute a checksum. Throws an [ArtifactException] if the data is not a readable archive or exceeds
     * the configured number of entries.
     */
    fun inspect(input: InputStream): ArtifactLegalFacts {
        val licenseFiles = mutableListOf<String>()
        val noticeFiles = mutableListOf<String>()
        var entryCount = 0

        try {
            val zip = ZipInputStream(input)

            while (true) {
                val entry = zip.nextEntry ?: break

                entryCount++
                if (entryCount > maxEntries) throw ArtifactException("The archive has more than $maxEntries entries.")

                if (entry.isDirectory) continue

                if (licensePattern.matches(entry.name)) licenseFiles += entry.name
                if (noticePattern.matches(entry.name)) noticeFiles += entry.name
            }
        } catch (e: ZipException) {
            throw ArtifactException("The archive is corrupt: ${e.message}", e)
        }

        if (entryCount == 0) throw ArtifactException("The archive contains no entries.")

        return ArtifactLegalFacts(
            entryCount = entryCount,
            licenseFiles = licenseFiles,
            noticeFiles = noticeFiles
        )
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ArtifactInspectorTest"`
Expected: PASS (7 tests). If "a truncated archive is rejected" does not throw (some truncations yield only an empty stream), change `copyOf(40)` to `copyOf(jar.size / 2)`; a half archive always fails with a `ZipException` or yields zero entries, both of which map to `ArtifactException`.

- [ ] **Step 6: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add archive inspection for the license obligation advisor"
```

---

### Task 4: Obligation rules

**Files:**
- Create: `shared/advisors/src/main/kotlin/ObligationRules.kt`
- Test: `shared/advisors/src/test/kotlin/ObligationRulesTest.kt`

- [ ] **Step 1: Write the failing test**

`ObligationRulesTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

class ObligationRulesTest : StringSpec({
    val rules = ObligationRules(setOf("Apache-2.0", "ECL-2.0"))
    val url = "https://nexus.example/lib-1.0.jar"

    fun facts(license: Boolean, notice: Boolean) = ArtifactLegalFacts(
        entryCount = 3,
        licenseFiles = if (license) listOf("META-INF/LICENSE") else emptyList(),
        noticeFiles = if (notice) listOf("META-INF/NOTICE") else emptyList()
    )

    fun ids(license: String?, license_: Boolean, notice: Boolean) =
        rules.evaluate(license?.let { SpdxExpression.parse(it) }, facts(license_, notice), url).map { it.id }

    "an artifact with license and notice has no findings" {
        ids("Apache-2.0", license_ = true, notice = true).shouldBeEmpty()
    }

    "a missing license file is a HIGH finding for every license" {
        listOf("MIT", "Apache-2.0", "EPL-2.0", "LicenseRef-GEBIT").forEach { license ->
            val findings = rules.evaluate(SpdxExpression.parse(license), facts(license = false, notice = true), url)

            findings.map { it.id } shouldBe listOf(LIC_OBL_MISSING_LICENSE)
            findings.single().severity shouldBe FindingSeverity.HIGH
            findings.single().description shouldContain url
        }
    }

    "a missing license file is reported even if the license is unknown" {
        ids(null, license_ = false, notice = false) shouldBe listOf(LIC_OBL_MISSING_LICENSE)
    }

    "a missing notice is a LOW finding for Apache-2.0 and ECL-2.0" {
        listOf("Apache-2.0", "ECL-2.0", "Apache-2.0+").forEach { license ->
            val findings = rules.evaluate(SpdxExpression.parse(license), facts(license = true, notice = false), url)

            findings.map { it.id } shouldBe listOf(LIC_OBL_MISSING_NOTICE)
            findings.single().severity shouldBe FindingSeverity.LOW
            findings.single().description shouldContain license
        }
    }

    "a missing notice is no finding for licenses that do not require it" {
        listOf("MIT", "EPL-2.0", "BSD-3-Clause", "LicenseRef-GEBIT").forEach { license ->
            ids(license, license_ = true, notice = false).shouldBeEmpty()
        }
        ids(null, license_ = true, notice = false).shouldBeEmpty()
    }

    "AND requires a notice if any operand requires it" {
        ids("MIT AND Apache-2.0", license_ = true, notice = false) shouldBe listOf(LIC_OBL_MISSING_NOTICE)
    }

    "OR requires a notice only if every alternative requires it" {
        ids("MIT OR Apache-2.0", license_ = true, notice = false).shouldBeEmpty()
        ids("Apache-2.0 OR ECL-2.0", license_ = true, notice = false) shouldBe listOf(LIC_OBL_MISSING_NOTICE)
    }

    "WITH exceptions do not hide the license" {
        ids("Apache-2.0 WITH LLVM-exception", license_ = true, notice = false) shouldBe listOf(LIC_OBL_MISSING_NOTICE)
    }

    "both findings are reported in a stable order" {
        ids("Apache-2.0", license_ = false, notice = false) shouldBe
                listOf(LIC_OBL_MISSING_LICENSE, LIC_OBL_MISSING_NOTICE)
    }

    "an unavailable artifact is a LOW finding" {
        val finding = rules.unavailable("HTTP status 404", url)

        finding.id shouldBe LIC_OBL_ARTIFACT_UNAVAILABLE
        finding.severity shouldBe FindingSeverity.LOW
        finding.description shouldContain "HTTP status 404"
        finding.description shouldContain url
    }

    "an unavailable artifact without URL has a readable description" {
        rules.unavailable("no binary artifact", "").description shouldContain "no artifact URL"
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ObligationRulesTest"`
Expected: FAIL (compilation error: `ObligationRules` unresolved).

- [ ] **Step 3: Implement**

`ObligationRules.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import org.ossreviewtoolkit.utils.spdxexpression.SpdxCompoundExpression
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression
import org.ossreviewtoolkit.utils.spdxexpression.SpdxOperator

const val LIC_OBL_MISSING_LICENSE = "LIC_OBL_MISSING_LICENSE"
const val LIC_OBL_MISSING_NOTICE = "LIC_OBL_MISSING_NOTICE"
const val LIC_OBL_ARTIFACT_UNAVAILABLE = "LIC_OBL_ARTIFACT_UNAVAILABLE"

/** The severity of a finding. The enum name is used as the severity string of the resulting vulnerability. */
enum class FindingSeverity { HIGH, LOW }

/** A single obligation finding for one artifact. */
data class ObligationFinding(
    val id: String,
    val severity: FindingSeverity,
    val description: String
)

/**
 * The obligation rules: pure functions from [ArtifactLegalFacts] and the effective license to findings.
 *
 * A missing license file is always a defect. A missing notice file is only reported for the licenses in
 * [noticeRequiredLicenses] (SPDX ids without the `+` suffix), and only as a low severity finding, since a NOTICE is
 * only required if the upstream has one, which the artifact alone cannot tell.
 */
class ObligationRules(private val noticeRequiredLicenses: Set<String>) {
    fun evaluate(license: SpdxExpression?, facts: ArtifactLegalFacts, artifactUrl: String): List<ObligationFinding> =
        buildList {
            if (facts.licenseFiles.isEmpty()) {
                add(
                    ObligationFinding(
                        id = LIC_OBL_MISSING_LICENSE,
                        severity = FindingSeverity.HIGH,
                        description = "The distributed artifact contains no license file: ${describe(artifactUrl)}."
                    )
                )
            }

            if (facts.noticeFiles.isEmpty() && license != null && requiresNotice(license)) {
                add(
                    ObligationFinding(
                        id = LIC_OBL_MISSING_NOTICE,
                        severity = FindingSeverity.LOW,
                        description = "The effective license '$license' requires a NOTICE file, but the distributed " +
                                "artifact contains none: ${describe(artifactUrl)}."
                    )
                )
            }
        }

    /** Create the finding for an artifact that could not be verified, e.g. because it could not be downloaded. */
    fun unavailable(reason: String, artifactUrl: String) =
        ObligationFinding(
            id = LIC_OBL_ARTIFACT_UNAVAILABLE,
            severity = FindingSeverity.LOW,
            description = "The distributed artifact could not be verified ($reason): ${describe(artifactUrl)}."
        )

    private fun requiresNotice(expression: SpdxExpression): Boolean =
        when (expression) {
            is SpdxCompoundExpression -> when (expression.operator) {
                SpdxOperator.AND -> expression.children.any { requiresNotice(it) }
                // With OR the consumer can pick the alternative with fewer obligations.
                SpdxOperator.OR -> expression.children.all { requiresNotice(it) }
            }

            else -> expression.licenses().any { it.removeSuffix("+") in noticeRequiredLicenses }
        }

    private fun describe(artifactUrl: String) = artifactUrl.ifBlank { "no artifact URL" }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ObligationRulesTest"`
Expected: PASS (11 tests). If the `Apache-2.0+` or `WITH` cases fail because `licenses()` returns a different string form, print it with a quick `println(SpdxExpression.parse("Apache-2.0+").licenses())` in the test and adapt `requiresNotice` (strip the `+` or the `WITH` part) so that the test expectations in this plan hold.

- [ ] **Step 5: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add obligation rules for the license obligation advisor"
```

---

### Task 5: Artifact download

**Files:**
- Create: `shared/advisors/src/test/kotlin/TestArtifactServer.kt`
- Create: `shared/advisors/src/main/kotlin/ArtifactDownloader.kt`
- Test: `shared/advisors/src/test/kotlin/ArtifactDownloaderTest.kt`

- [ ] **Step 1: Create the test server helper**

`TestArtifactServer.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import com.sun.net.httpserver.HttpServer

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A local HTTP server that serves artifacts from memory and counts requests. */
class TestArtifactServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val artifacts = ConcurrentHashMap<String, ByteArray>()

    val requestCount = AtomicInteger()

    /** If set, requests must carry exactly this `Authorization` header value, otherwise they get a 401. */
    @Volatile
    var requiredAuthorization: String? = null

    init {
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()

            val body = artifacts[exchange.requestURI.path]
            val authorized = requiredAuthorization?.let { exchange.requestHeaders.getFirst("Authorization") == it }
                ?: true

            when {
                !authorized -> exchange.sendResponseHeaders(401, -1)
                body == null -> exchange.sendResponseHeaders(404, -1)
                else -> {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }

            exchange.close()
        }

        server.start()
    }

    /** Serve [content] at [path] and return the full URL. */
    fun serve(path: String, content: ByteArray): String {
        artifacts[path] = content
        return url(path)
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
```

- [ ] **Step 2: Write the failing test**

`ArtifactDownloaderTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.util.Base64

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.RemoteArtifact

class ArtifactDownloaderTest : StringSpec({
    val server = TestArtifactServer()
    val jar = buildJar("META-INF/LICENSE" to "license", "com/example/Foo.class" to "x".repeat(10_000))
    val url = server.serve("/lib-1.0.jar", jar)
    val artifact = RemoteArtifact(url, Hash(sha1(jar), HashAlgorithm.SHA1))

    afterSpec { server.close() }

    afterTest { server.requiredAuthorization = null }

    fun downloader(maxBytes: Long = 1_000_000, username: String? = null, password: String? = null) =
        ArtifactDownloader(maxBytes, username, password)

    "the processing function receives the artifact content" {
        val size = downloader().download(artifact) { it.readAllBytes().size }

        size shouldBe jar.size
    }

    "the whole artifact is verified even if the processing function reads only part of it" {
        val wrongHash = artifact.copy(hash = Hash("0".repeat(40), HashAlgorithm.SHA1))

        val exception = shouldThrow<ArtifactException> {
            downloader().download(wrongHash) { it.read() }
        }

        exception.message shouldContain "checksum"
    }

    "a hash with an unknown algorithm is not verified" {
        val unverified = artifact.copy(hash = Hash.NONE)

        downloader().download(unverified) { it.readAllBytes().size } shouldBe jar.size
    }

    "a failing HTTP status is reported" {
        val missing = RemoteArtifact(server.url("/missing.jar"), Hash.NONE)

        shouldThrow<ArtifactException> { downloader().download(missing) { } }.message shouldContain "404"
    }

    "an artifact larger than the limit is rejected" {
        shouldThrow<ArtifactException> {
            downloader(maxBytes = 100).download(artifact) { it.readAllBytes() }
        }.message shouldContain "maximum size"
    }

    "an invalid URL is reported as ArtifactException" {
        shouldThrow<ArtifactException> {
            downloader().download(RemoteArtifact("not a url", Hash.NONE)) { }
        }
    }

    "an unreachable server is reported as ArtifactException" {
        shouldThrow<ArtifactException> {
            downloader().download(RemoteArtifact("http://127.0.0.1:1/x.jar", Hash.NONE)) { }
        }
    }

    "credentials are sent as basic authentication" {
        server.requiredAuthorization = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())

        downloader(username = "user", password = "secret").download(artifact) { it.readAllBytes().size } shouldBe
                jar.size

        shouldThrow<ArtifactException> { downloader().download(artifact) { } }.message shouldContain "401"
    }
})
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ArtifactDownloaderTest"`
Expected: FAIL (compilation error: `ArtifactDownloader` unresolved).

- [ ] **Step 4: Implement**

`ArtifactDownloader.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

import org.ossreviewtoolkit.model.RemoteArtifact

/**
 * Downloads artifacts via HTTP(S) as a stream, enforcing a size limit and verifying the checksum from the
 * [RemoteArtifact] (if it has a verifiable algorithm). The optional [username] and [password] are sent as basic
 * authentication. All failures are reported as [ArtifactException].
 */
class ArtifactDownloader(
    private val maxBytes: Long,
    private val username: String? = null,
    private val password: String? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
) {
    /**
     * Download [artifact] and pass its content to [process]. After [process] returns, the rest of the stream is
     * drained, so that the size limit and the checksum cover the complete artifact, not only what [process] read.
     */
    fun <T> download(artifact: RemoteArtifact, process: (InputStream) -> T): T {
        val response = send(artifact)

        response.body().use { body ->
            if (response.statusCode() != 200) {
                throw ArtifactException("HTTP status ${response.statusCode()} for ${artifact.url}")
            }

            val digest = createDigest(artifact)
            val limited = LimitedInputStream(body, maxBytes)
            val input = if (digest != null) DigestInputStream(limited, digest) else limited

            val result = process(input)
            input.copyTo(OutputStream.nullOutputStream())

            if (digest != null) {
                val actual = HexFormat.of().formatHex(digest.digest())
                val expected = artifact.hash.value.lowercase()

                if (actual != expected) {
                    throw ArtifactException("Checksum mismatch for ${artifact.url}: expected $expected, got $actual.")
                }
            }

            return result
        }
    }

    private fun send(artifact: RemoteArtifact): HttpResponse<InputStream> {
        try {
            val request = HttpRequest.newBuilder(URI.create(artifact.url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .also { builder ->
                    if (username != null && password != null) {
                        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                        builder.header("Authorization", "Basic $credentials")
                    }
                }
                .build()

            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IllegalArgumentException) {
            throw ArtifactException("Invalid artifact URL '${artifact.url}'.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ArtifactException("Interrupted while downloading ${artifact.url}.", e)
        } catch (e: IOException) {
            if (e is ArtifactException) throw e
            throw ArtifactException("Download of ${artifact.url} failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private fun createDigest(artifact: RemoteArtifact): MessageDigest? {
        val algorithm = artifact.hash.algorithm.takeIf { it.isVerifiable } ?: return null

        return try {
            MessageDigest.getInstance(algorithm.aliases.first())
        } catch (_: NoSuchAlgorithmException) {
            null
        }
    }
}

/** Fails with an [ArtifactException] once more than [maxBytes] have been read. */
private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    // Skipping must count as reading, otherwise the limit could be bypassed.
    override fun skip(n: Long): Long {
        if (n <= 0) return 0

        val read = read(ByteArray(minOf(n, 8192L).toInt()))
        return maxOf(read, 0).toLong()
    }

    private fun add(bytes: Long) {
        count += bytes
        if (count > maxBytes) throw ArtifactException("The artifact exceeds the maximum size of $maxBytes bytes.")
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.ArtifactDownloaderTest"`
Expected: PASS (8 tests). Note: the "unreachable server" test targets `127.0.0.1:1`; connection refused is an `IOException`, which is wrapped.

- [ ] **Step 6: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add artifact download for the license obligation advisor"
```

---

### Task 6: Facts cache

**Files:**
- Create: `shared/advisors/src/main/kotlin/LicenseObligationCache.kt`
- Test: `shared/advisors/src/test/kotlin/LicenseObligationCacheTest.kt`

- [ ] **Step 1: Write the failing test**

`LicenseObligationCacheTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

import java.io.File
import java.nio.file.Files

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.RemoteArtifact

class LicenseObligationCacheTest : StringSpec({
    val tempDirs = mutableListOf<File>()

    fun tempDir() = Files.createTempDirectory("lic-obl-cache").toFile().also { tempDirs += it }

    afterSpec { tempDirs.forEach { it.deleteRecursively() } }

    val facts = ArtifactLegalFacts(
        entryCount = 7,
        licenseFiles = listOf("META-INF/LICENSE"),
        noticeFiles = listOf("META-INF/NOTICE.txt")
    )

    "facts survive a round trip through the file cache" {
        val cache = createCache(tempDir().absolutePath)

        cache.put("key", facts)

        cache.get("key") shouldBe facts
    }

    "a missing key is a cache miss" {
        createCache(tempDir().absolutePath).get("unknown").shouldBeNull()
    }

    "a corrupt cache file is a cache miss" {
        val dir = tempDir()
        File(dir, "key.json").writeText("{ this is not json")

        createCache(dir.absolutePath).get("key").shouldBeNull()
    }

    "an entry written by another extractor version is a cache miss" {
        val cache = createCache(tempDir().absolutePath)

        cache.put("key", facts.copy(extractorVersion = EXTRACTOR_VERSION + 1))

        cache.get("key").shouldBeNull()
    }

    "the cache directory is created on demand, including parents" {
        val dir = File(tempDir(), "a/b/advisor")

        createCache(dir.absolutePath).put("key", facts)

        dir.isDirectory shouldBe true
    }

    "a blank cache directory disables caching" {
        val cache = createCache("")

        cache.put("key", facts)

        cache.get("key").shouldBeNull()
        cache.shouldBeInstanceOf<NoOpLicenseObligationCache>()
    }

    "an unusable cache directory disables caching instead of failing" {
        val file = File(tempDir(), "regular-file").apply { writeText("x") }

        createCache(File(file, "advisor").absolutePath).shouldBeInstanceOf<NoOpLicenseObligationCache>()
    }

    "the key depends on URL, hash and extractor version" {
        val artifact = RemoteArtifact("https://example.org/a.jar", Hash("abc", HashAlgorithm.SHA1))

        val key = cacheKey(artifact)

        key shouldBe cacheKey(artifact)
        key shouldNotBe cacheKey(artifact.copy(url = "https://example.org/b.jar"))
        key shouldNotBe cacheKey(artifact.copy(hash = Hash("abd", HashAlgorithm.SHA1)))
        key shouldNotBe cacheKey(artifact, extractorVersion = EXTRACTOR_VERSION + 1)
        key.matches(Regex("[0-9a-f]{40}")) shouldBe true
    }

    "concurrent writers to the same key never produce a corrupt entry" {
        val cache = createCache(tempDir().absolutePath)

        val threads = (1..8).map { i ->
            Thread { repeat(50) { cache.put("key", facts.copy(entryCount = i)) } }.also { it.start() }
        }
        repeat(200) { cache.get("key")?.entryCount?.let { it in 1..8 } shouldBe (cache.get("key") != null) }
        threads.forEach { it.join() }

        cache.get("key") shouldNotBe null
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LicenseObligationCacheTest"`
Expected: FAIL (compilation error: `createCache` unresolved).

- [ ] **Step 3: Implement**

`LicenseObligationCache.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

import org.ossreviewtoolkit.model.RemoteArtifact

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LicenseObligationCache::class.java)

private val json = Json { ignoreUnknownKeys = true }

/** A store for [ArtifactLegalFacts], keyed by [cacheKey]. Implementations must be safe for concurrent use. */
interface LicenseObligationCache {
    /** Return the facts stored for [key], or `null` on a miss. Never throws. */
    fun get(key: String): ArtifactLegalFacts?

    /** Store [facts] for [key]. Failures are logged, not thrown, since the cache is only an optimization. */
    fun put(key: String, facts: ArtifactLegalFacts)
}

/** A cache that stores nothing. Used if caching is disabled or the cache directory is unusable. */
object NoOpLicenseObligationCache : LicenseObligationCache {
    override fun get(key: String): ArtifactLegalFacts? = null

    override fun put(key: String, facts: ArtifactLegalFacts) = Unit
}

/** A cache that stores one JSON file per key in [directory]. Writes are atomic (temporary file and rename). */
class FileLicenseObligationCache(private val directory: File) : LicenseObligationCache {
    override fun get(key: String): ArtifactLegalFacts? {
        val file = File(directory, "$key.json")

        return try {
            json.decodeFromString<ArtifactLegalFacts>(file.readText())
                .takeIf { it.extractorVersion == EXTRACTOR_VERSION }
        } catch (_: IOException) {
            null
        } catch (e: SerializationException) {
            logger.warn("Ignoring corrupt cache file '{}': {}", file, e.message)
            null
        }
    }

    override fun put(key: String, facts: ArtifactLegalFacts) {
        try {
            val temp = Files.createTempFile(directory.toPath(), "tmp-", ".part")
            temp.toFile().writeText(json.encodeToString(facts))
            Files.move(temp, File(directory, "$key.json").toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            logger.warn("Could not write cache entry '{}': {}", key, e.message)
        }
    }
}

/**
 * Create the cache for [cacheDir]. A blank value disables caching. If the directory cannot be created or written, a
 * warning is logged and caching is disabled, so that the advisor still works outside of its container.
 */
fun createCache(cacheDir: String): LicenseObligationCache {
    if (cacheDir.isBlank()) return NoOpLicenseObligationCache

    val directory = File(cacheDir)

    return try {
        Files.createDirectories(directory.toPath())
        if (!Files.isWritable(directory.toPath())) throw IOException("The directory is not writable.")
        FileLicenseObligationCache(directory)
    } catch (e: IOException) {
        logger.warn("The cache directory '{}' is unusable ({}). Caching is disabled.", cacheDir, e.message)
        NoOpLicenseObligationCache
    }
}

/**
 * Return the cache key for [artifact]: the SHA-1 of its URL, its hash value and the [extractorVersion]. The hash makes
 * an entry immutable per artifact content; the version invalidates entries when the extraction logic changes.
 */
fun cacheKey(artifact: RemoteArtifact, extractorVersion: Int = EXTRACTOR_VERSION): String {
    val input = "${artifact.url}|${artifact.hash.value}|$extractorVersion"
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(input.toByteArray()))
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LicenseObligationCacheTest"`
Expected: PASS (9 tests). Note: "an unusable cache directory" relies on `Files.createDirectories` failing below a regular file (`FileSystemException`, an `IOException`); this holds on Linux. The corrupt-file test relies on `decodeFromString` throwing `SerializationException` (or `IllegalArgumentException` subclass of it) for broken JSON; if kotlinx throws a plain `IllegalArgumentException` in this version, widen the second `catch` to `IllegalArgumentException`.

- [ ] **Step 5: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add facts cache for the license obligation advisor"
```

---

### Task 7: The advisor plugin

**Files:**
- Create: `shared/advisors/src/main/kotlin/LicenseObligationAdvisor.kt`
- Test: `shared/advisors/src/test/kotlin/LicenseObligationAdvisorTest.kt`

- [ ] **Step 1: Write the failing test**

`LicenseObligationAdvisorTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.io.File
import java.nio.file.Files
import java.util.Base64

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.plugins.advisors.api.AdviceProvider
import org.ossreviewtoolkit.plugins.advisors.api.AdviceProviderFactory
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

class LicenseObligationAdvisorTest : StringSpec({
    val server = TestArtifactServer()
    val tempDirs = mutableListOf<File>()

    afterSpec {
        server.close()
        tempDirs.forEach { it.deleteRecursively() }
    }

    beforeTest { server.requestCount.set(0) }

    fun tempDir() = Files.createTempDirectory("lic-obl-advisor").toFile().also { tempDirs += it }

    fun advisor(
        cacheDir: String = "",
        options: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap()
    ): AdviceProvider =
        AdviceProviderFactory.ALL.getValue("LicenseObligation")
            .create(PluginConfig(options = mapOf("cacheDir" to cacheDir) + options, secrets = secrets))

    /** Serve a jar with the given entries and return a Maven package that points to it. */
    fun pkg(
        name: String,
        vararg entries: Pair<String, String>,
        license: String = "Apache-2.0",
        type: String = "Maven",
        fileName: String = "$name-1.0.jar"
    ): Package {
        val content = buildJar(*entries)
        val url = server.serve("/$name/$fileName", content)

        return Package.EMPTY.copy(
            id = Identifier(type, "org.example", name, "1.0"),
            purl = "pkg:maven/org.example/$name@1.0",
            binaryArtifact = RemoteArtifact(url, Hash(sha1(content), HashAlgorithm.SHA1)),
            concludedLicense = SpdxExpression.parse(license)
        )
    }

    "the plugin is registered with the expected id and details" {
        advisor().details.name shouldBe "LicenseObligation"
    }

    "a package with license and notice has no findings" {
        val pkg = pkg("ok", "META-INF/LICENSE" to "l", "META-INF/NOTICE" to "n")

        advisor().retrievePackageFindings(setOf(pkg)).shouldBeEmpty()
    }

    "a missing license and a missing notice are both reported" {
        val pkg = pkg("bare", "com/example/Foo.class" to "x")

        val result = advisor().retrievePackageFindings(setOf(pkg)).getValue(pkg)

        result.advisor.name shouldBe "LicenseObligation"
        result.vulnerabilities.map { it.id } shouldContainExactly
                listOf("LIC_OBL_MISSING_LICENSE", "LIC_OBL_MISSING_NOTICE")
        result.vulnerabilities.map { it.references.single().severity } shouldContainExactly listOf("HIGH", "LOW")
        result.vulnerabilities.forEach { vulnerability ->
            vulnerability.references.single().scoringSystem shouldBe "Other"
            vulnerability.references.single().url.toString() shouldBe pkg.binaryArtifact.url
            vulnerability.description!! shouldContain pkg.binaryArtifact.url
        }
    }

    "a missing notice is not reported for licenses that do not require one" {
        val pkg = pkg("mit", "LICENSE" to "l", license = "MIT")

        advisor().retrievePackageFindings(setOf(pkg)).shouldBeEmpty()
    }

    "only packages with findings are returned" {
        val good = pkg("good", "LICENSE" to "l", "NOTICE" to "n")
        val bad = pkg("bad", "NOTICE" to "n")

        val result = advisor().retrievePackageFindings(setOf(good, bad))

        result.keys shouldContainExactly listOf(bad)
    }

    "a failed download is reported as unavailable" {
        val pkg = pkg("gone", "LICENSE" to "l").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(url = server.url("/does-not-exist.jar")))
        }

        val vulnerability = advisor().retrievePackageFindings(setOf(pkg)).getValue(pkg).vulnerabilities.single()

        vulnerability.id shouldBe "LIC_OBL_ARTIFACT_UNAVAILABLE"
        vulnerability.references.single().severity shouldBe "LOW"
    }

    "a hash mismatch is reported as unavailable" {
        val pkg = pkg("tampered", "LICENSE" to "l").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(hash = Hash("0".repeat(40), HashAlgorithm.SHA1)))
        }

        val vulnerability = advisor().retrievePackageFindings(setOf(pkg)).getValue(pkg).vulnerabilities.single()

        vulnerability.id shouldBe "LIC_OBL_ARTIFACT_UNAVAILABLE"
        vulnerability.description!! shouldContain "checksum"
    }

    "a package without binary artifact URL is reported as unavailable" {
        val pkg = Package.EMPTY.copy(id = Identifier("Maven", "org.example", "no-url", "1.0"))

        val result = advisor().retrievePackageFindings(setOf(pkg))

        result.getValue(pkg).vulnerabilities.single().id shouldBe "LIC_OBL_ARTIFACT_UNAVAILABLE"
        server.requestCount.get() shouldBe 0
    }

    "non-Maven, metadata-only and POM packages are skipped without any request" {
        val npm = pkg("npm-pkg", "x" to "y", type = "NPM")
        val metadataOnly = pkg("meta", "x" to "y").copy(isMetadataOnly = true)
        val pom = pkg("parent", "x" to "y", fileName = "parent-1.0.pom")

        advisor().retrievePackageFindings(setOf(npm, metadataOnly, pom)).shouldBeEmpty()
        server.requestCount.get() shouldBe 0
    }

    "the cache avoids a second download" {
        val cacheDir = tempDir().absolutePath
        val pkg = pkg("cached", "NOTICE" to "n")

        val first = advisor(cacheDir).retrievePackageFindings(setOf(pkg))
        val requestsAfterFirst = server.requestCount.get()
        val second = advisor(cacheDir).retrievePackageFindings(setOf(pkg))

        requestsAfterFirst shouldBe 1
        server.requestCount.get() shouldBe 1
        second.getValue(pkg).vulnerabilities.map { it.id } shouldBe
                first.getValue(pkg).vulnerabilities.map { it.id }
    }

    "findings are derived from cached facts, so changed options take effect without a new download" {
        val cacheDir = tempDir().absolutePath
        val pkg = pkg("rules", "LICENSE" to "l", license = "EPL-2.0")

        advisor(cacheDir).retrievePackageFindings(setOf(pkg)).shouldBeEmpty()
        val strict = advisor(cacheDir, mapOf("noticeRequiredLicenses" to "EPL-2.0"))
            .retrievePackageFindings(setOf(pkg))

        strict.getValue(pkg).vulnerabilities.map { it.id } shouldBe listOf("LIC_OBL_MISSING_NOTICE")
        server.requestCount.get() shouldBe 1
    }

    "failures are not cached" {
        val cacheDir = tempDir().absolutePath
        val pkg = pkg("flaky", "LICENSE" to "l").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(url = server.url("/flaky-1.0.jar")))
        }

        advisor(cacheDir).retrievePackageFindings(setOf(pkg)).getValue(pkg).vulnerabilities.single().id shouldBe
                "LIC_OBL_ARTIFACT_UNAVAILABLE"

        val content = buildJar("LICENSE" to "l", "NOTICE" to "n")
        val fixed = pkg.copy(
            binaryArtifact = RemoteArtifact(
                server.serve("/flaky-1.0.jar", content),
                Hash(sha1(content), HashAlgorithm.SHA1)
            )
        )

        advisor(cacheDir).retrievePackageFindings(setOf(fixed)).shouldBeEmpty()
    }

    "an empty cacheDir disables the cache" {
        val pkg = pkg("uncached", "NOTICE" to "n")

        advisor("").retrievePackageFindings(setOf(pkg))
        advisor("").retrievePackageFindings(setOf(pkg))

        server.requestCount.get() shouldBe 2
    }

    "the policy URL is used as the reference URL" {
        val pkg = pkg("policy", "x" to "y")

        val reference = advisor(options = mapOf("policyUrl" to "https://policy.example/licenses"))
            .retrievePackageFindings(setOf(pkg)).getValue(pkg).vulnerabilities.first().references.single()

        reference.url.toString() shouldBe "https://policy.example/licenses"
    }

    "configured file patterns are honored" {
        val pkg = pkg("custom", "META-INF/MY-LEGAL" to "x", "META-INF/MY-NOTICE" to "x")

        val result = advisor(
            options = mapOf("licenseFilePatterns" to "MY-LEGAL", "noticeFilePatterns" to "MY-NOTICE")
        ).retrievePackageFindings(setOf(pkg))

        result.shouldBeEmpty()
    }

    "credentials from the plugin secrets are used for downloads" {
        server.requiredAuthorization = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())

        try {
            val pkg = pkg("secured", "LICENSE" to "l", "NOTICE" to "n")

            advisor(secrets = mapOf("username" to "user", "password" to "secret"))
                .retrievePackageFindings(setOf(pkg)).shouldBeEmpty()
            advisor().retrievePackageFindings(setOf(pkg)).getValue(pkg).vulnerabilities.single().id shouldBe
                    "LIC_OBL_ARTIFACT_UNAVAILABLE"
        } finally {
            server.requiredAuthorization = null
        }
    }

    "downloads are bounded by maxParallelDownloads but all packages are processed" {
        val packages = (1..10).map { pkg("many-$it", "x" to "y") }.toSet()

        val result = advisor(options = mapOf("maxParallelDownloads" to "2")).retrievePackageFindings(packages)

        result.keys shouldBe packages
    }
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LicenseObligationAdvisorTest"`
Expected: FAIL (`NoSuchElementException: Key LicenseObligation is missing in the map`, or a compilation error, since the plugin does not exist yet).

- [ ] **Step 3: Implement**

`LicenseObligationAdvisor.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import org.ossreviewtoolkit.model.AdvisorDetails
import org.ossreviewtoolkit.model.AdvisorResult
import org.ossreviewtoolkit.model.AdvisorSummary
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.vulnerabilities.Vulnerability
import org.ossreviewtoolkit.model.vulnerabilities.VulnerabilityReference
import org.ossreviewtoolkit.plugins.advisors.api.AdviceProvider
import org.ossreviewtoolkit.plugins.advisors.api.AdviceProviderFactory
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.api.Secret

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LicenseObligationAdvisor::class.java)

/** Used as reference URL if neither a policy URL nor an artifact URL is available. */
private const val FALLBACK_REFERENCE_URL = "urn:ort-server:license-obligation"

/** The configuration options of the [LicenseObligationAdvisor]. */
data class LicenseObligationAdvisorConfig(
    /** The base names of license files, e.g. `LICENSE` matches `META-INF/LICENSE.txt`. */
    @OrtPluginOption(defaultValue = "LICENSE,LICENCE,COPYING")
    val licenseFilePatterns: List<String>,

    /** The base names of notice files. */
    @OrtPluginOption(defaultValue = "NOTICE,NOTICES")
    val noticeFilePatterns: List<String>,

    /** The SPDX ids of the licenses that require a NOTICE file. */
    @OrtPluginOption(defaultValue = "Apache-2.0,ECL-2.0")
    val noticeRequiredLicenses: List<String>,

    /** The directory of the file cache. The default is inside the `.cache` volume of the advisor worker container. */
    @OrtPluginOption(defaultValue = "/home/ort/.cache/advisor")
    val cacheDir: String,

    /** The maximum number of concurrent artifact downloads. */
    @OrtPluginOption(defaultValue = "4")
    val maxParallelDownloads: Int,

    /** The maximum size of a downloaded artifact in bytes. */
    @OrtPluginOption(defaultValue = "536870912")
    val maxArchiveBytes: Long,

    /** The maximum number of entries in an artifact archive. */
    @OrtPluginOption(defaultValue = "100000")
    val maxArchiveEntries: Int,

    /** A URL to the policy documentation, attached as reference to every finding. */
    val policyUrl: String?,

    /** The user name for basic authentication against the artifact repository. */
    val username: Secret?,

    /** The password for basic authentication against the artifact repository. */
    val password: Secret?
)

/**
 * An advisor that checks whether the distributed binary artifact of a Maven package contains the legal files its
 * license requires. Findings are returned as vulnerabilities with `LIC_OBL_*` ids, so that the CycloneDX reporter
 * exports them into the SBOM.
 */
@OrtPlugin(
    id = "LicenseObligation",
    displayName = "License Obligation",
    summary = "An advisor that checks whether the distributed artifacts of Maven packages contain the required " +
            "LICENSE and NOTICE files and reports missing ones as vulnerabilities with LIC_OBL_* ids.",
    factory = AdviceProviderFactory::class
)
class LicenseObligationAdvisor(
    override val descriptor: PluginDescriptor,
    private val config: LicenseObligationAdvisorConfig
) : AdviceProvider {
    override val details = AdvisorDetails(descriptor.id)

    private val inspector = ArtifactInspector(
        licensePattern = LegalFilePattern(config.licenseFilePatterns),
        noticePattern = LegalFilePattern(config.noticeFilePatterns),
        maxEntries = config.maxArchiveEntries
    )

    private val rules = ObligationRules(config.noticeRequiredLicenses.toSet())

    private val downloader = ArtifactDownloader(
        maxBytes = config.maxArchiveBytes,
        username = config.username?.value,
        password = config.password?.value
    )

    private val cache = createCache(config.cacheDir)

    override suspend fun retrievePackageFindings(packages: Set<Package>): Map<Package, AdvisorResult> {
        val startTime = Instant.now()
        val semaphore = Semaphore(config.maxParallelDownloads.coerceAtLeast(1))

        val findings = coroutineScope {
            packages.filter { it.isCandidate() }.map { pkg ->
                async(Dispatchers.IO) { semaphore.withPermit { pkg to check(pkg) } }
            }.awaitAll()
        }

        return findings.filter { (_, pkgFindings) -> pkgFindings.isNotEmpty() }.associate { (pkg, pkgFindings) ->
            pkg to AdvisorResult(
                advisor = details,
                summary = AdvisorSummary(startTime = startTime, endTime = Instant.now()),
                vulnerabilities = pkgFindings.map { it.toVulnerability(pkg) }
            )
        }
    }

    /** Only Maven packages with a distributed artifact are checked; POM-only packages have none. */
    private fun Package.isCandidate() =
        id.type == "Maven" && !isMetadataOnly && !binaryArtifact.url.endsWith(".pom")

    private fun check(pkg: Package): List<ObligationFinding> {
        val artifact = pkg.binaryArtifact

        if (artifact.url.isBlank()) return listOf(rules.unavailable("the package has no binary artifact", ""))

        val facts = try {
            loadFacts(artifact)
        } catch (e: IOException) {
            logger.warn("Could not verify the artifact of '{}': {}", pkg.id.toCoordinates(), e.message)
            return listOf(rules.unavailable(e.message.orEmpty(), artifact.url))
        }

        return rules.evaluate(pkg.concludedLicense ?: pkg.declaredLicensesProcessed.spdxExpression, facts, artifact.url)
    }

    private fun loadFacts(artifact: RemoteArtifact): ArtifactLegalFacts {
        val key = cacheKey(artifact)

        cache.get(key)?.let { return it }

        return downloader.download(artifact) { inspector.inspect(it) }.also { cache.put(key, it) }
    }

    private fun ObligationFinding.toVulnerability(pkg: Package) =
        Vulnerability(
            id = id,
            description = description,
            references = listOf(
                VulnerabilityReference(
                    url = referenceUrl(pkg),
                    scoringSystem = "Other",
                    severity = severity.name,
                    score = null,
                    vector = null
                )
            )
        )

    private fun referenceUrl(pkg: Package): URI {
        val candidate = config.policyUrl?.takeIf { it.isNotBlank() }
            ?: pkg.binaryArtifact.url.takeIf { it.isNotBlank() }
            ?: FALLBACK_REFERENCE_URL

        return try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            URI(FALLBACK_REFERENCE_URL)
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.LicenseObligationAdvisorTest"`
Expected: PASS (17 tests). Troubleshooting, in this order:
1. KSP generated sources are under `shared/advisors/build/generated/ksp/main/kotlin/`; check `LicenseObligationAdvisorFactory.kt` exists and which option types it parses. If the generated code rejects `Secret?` or `Long`, look at the error and the generated parse calls (`parseNullableSecretOption`, `parseLongOption` exist in ORT 92.6.0).
2. If an empty `cacheDir` option value is rejected by the generated parsing, change the tests that pass `""` to omit the option and instead pass a path of a temp directory whose parent is a regular file (this makes `createCache` return the no-op cache), and keep one test that `createCache("")` returns the no-op cache (already covered in Task 6).
3. If `PluginDescriptor.id` is not `"LicenseObligation"`, print `descriptor.id` and adapt `details` and the test.

- [ ] **Step 5: Run the whole module test suite**

Run: `./gradlew :shared:advisors:test`
Expected: PASS (all tests of Tasks 2-7).

- [ ] **Step 6: Commit**

```bash
git add shared/advisors/src
git commit -m "$TICKET: Add the license obligation advisor plugin"
```

---

### Task 8: CycloneDX round trip

Verifies the central assumption of the design: findings flow through the stock CycloneDX reporter into the BOM.

**Files:**
- Test: `shared/advisors/src/test/kotlin/CycloneDxRoundTripTest.kt`

- [ ] **Step 1: Write the test**

`CycloneDxRoundTripTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.ossreviewtoolkit.model.AdvisorDetails
import org.ossreviewtoolkit.model.AdvisorResult
import org.ossreviewtoolkit.model.AdvisorRun
import org.ossreviewtoolkit.model.AdvisorSummary
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.AnalyzerRun
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.vulnerabilities.Vulnerability
import org.ossreviewtoolkit.model.vulnerabilities.VulnerabilityReference
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.reporters.cyclonedx.CycloneDxReporterFactory
import org.ossreviewtoolkit.reporter.ReporterInput

class CycloneDxRoundTripTest : StringSpec({
    "advisor findings are exported as CycloneDX vulnerabilities" {
        val pkg = Package.EMPTY.copy(
            id = Identifier("Maven", "org.example", "lib", "1.0"),
            purl = "pkg:maven/org.example/lib@1.0"
        )

        val vulnerability = Vulnerability(
            id = LIC_OBL_MISSING_LICENSE,
            description = "The distributed artifact contains no license file.",
            references = listOf(
                VulnerabilityReference(
                    url = URI("https://policy.example/licenses"),
                    scoringSystem = "Other",
                    severity = "HIGH",
                    score = null,
                    vector = null
                )
            )
        )

        val ortResult = OrtResult.EMPTY.copy(
            analyzer = AnalyzerRun.EMPTY.copy(
                result = AnalyzerResult.EMPTY.copy(packages = setOf(pkg.toCuratedPackage()))
            ),
            advisor = AdvisorRun.EMPTY.copy(
                results = mapOf(
                    pkg.id to listOf(
                        AdvisorResult(
                            advisor = AdvisorDetails("LicenseObligation"),
                            summary = AdvisorSummary(Instant.EPOCH, Instant.EPOCH),
                            vulnerabilities = listOf(vulnerability)
                        )
                    )
                )
            )
        )

        val outputDir = Files.createTempDirectory("cdx-roundtrip").toFile()

        try {
            CycloneDxReporterFactory().create(PluginConfig.EMPTY).generateReport(ReporterInput(ortResult), outputDir)

            val bom = Json.parseToJsonElement(File(outputDir, "bom.cyclonedx.json").readText()).jsonObject
            val vulnerabilities = bom.getValue("vulnerabilities").jsonArray

            vulnerabilities.size shouldBe 1

            val exported = vulnerabilities.single().jsonObject
            exported.getValue("id").jsonPrimitive.content shouldBe LIC_OBL_MISSING_LICENSE
            exported.ratings().single().getValue("severity").jsonPrimitive.content shouldBe "high"
            exported.getValue("affects").jsonArray.single().jsonObject.getValue("ref").jsonPrimitive.content shouldBe
                    pkg.id.toCoordinates()
        } finally {
            outputDir.deleteRecursively()
        }
    }
})

private fun JsonObject.ratings() = getValue("ratings").jsonArray.map { it.jsonObject }
```

- [ ] **Step 2: Run**

Run: `./gradlew :shared:advisors:test --tests "org.eclipse.apoapsis.ortserver.shared.advisors.CycloneDxRoundTripTest"`
Expected: PASS. If it fails, diagnose before changing the design:
- If the reporter throws for missing project data (single-BOM metadata), add `projects = setOf(Project.EMPTY.copy(id = Identifier("Maven", "org.example", "app", "1.0")))` to the `AnalyzerResult` (import `org.ossreviewtoolkit.model.Project`).
- If `vulnerabilities` is missing in the BOM, print the BOM; check that the advisor results are keyed by the same `Identifier` as the package and that `OrtResult.getVulnerabilities()` is not empty. A genuinely missing vulnerability section would invalidate the design (spec "Why an advisor"); stop and report it instead of working around it.
- If the severity is serialized differently, adapt the expected value to what CycloneDX writes (the mapping uses the lower-case schema enum, `high`).

- [ ] **Step 3: Commit**

```bash
git add shared/advisors/src/test
git commit -m "$TICKET: Verify that advisor findings reach the CycloneDX BOM"
```

---

### Task 9: Plugin info and deployment

**Files:**
- Modify: `shared/plugin-info/src/test/kotlin/PluginInfoTest.kt`
- Modify: `../ort-server-service/docker-compose.yml` (a separate repository next to `ort-server`; commit there separately)

- [ ] **Step 1: Write the failing plugin-info test**

In `shared/plugin-info/src/test/kotlin/PluginInfoTest.kt`, in the test "contain the IDs of plugins defined in the ORT Server project", add this entry to `expectedPluginIds` after the `Dir` entry:

```kotlin
                TypedPluginId(
                    PluginId("LicenseObligation"),
                    PluginType("org.ossreviewtoolkit.plugins.advisors.api.AdviceProviderFactory")
                ),
```

- [ ] **Step 2: Run**

Run: `./gradlew :shared:plugin-info:test`
Expected: PASS (the `compileOnly(projects.shared.advisors)` added in Task 1 makes the collector see the plugin). If it fails with the plugin missing, check how `collectDependencyPlugins` picks up modules in `buildSrc/src/main/kotlin/PluginInfoCollectorPlugin.kt` and make sure `:shared:advisors` is covered the same way as `:shared:reporters`.

- [ ] **Step 3: Commit (ort-server repo)**

```bash
git add shared/plugin-info/src/test/kotlin/PluginInfoTest.kt
git commit -m "$TICKET: Test that the LicenseObligation plugin is listed in the plugin info"
```

- [ ] **Step 4: Add the cache volume to Docker Compose**

In `ort-server-service/docker-compose.yml`, in the `advisor-worker` service, replace

```yaml
    volumes:
    - type: bind
      source: ./scripts/compose/config
      target: /mnt/config
    - type: bind
      source: ./scripts/compose/secrets.properties
      target: /mnt/secrets.properties
    - secrets:/mnt/secrets-provider
    logging:
      driver: ${LOG_DRIVER:-gelf}
      options:
        # This is relative to the host, not the container.
        gelf-address: "udp://127.0.0.1:12201"
        tag: "advisor-worker"
```

with

```yaml
    volumes:
    - type: bind
      source: ./scripts/compose/config
      target: /mnt/config
    - type: bind
      source: ./scripts/compose/secrets.properties
      target: /mnt/secrets.properties
    - secrets:/mnt/secrets-provider
    # Cache of the LicenseObligation advisor (its default cacheDir is /home/ort/.cache/advisor). The mount point
    # must exist in the image and be owned by the "ort" user, which holds for /home/ort/.cache, so no init container
    # is needed.
    - advisor-cache:/home/ort/.cache
    logging:
      driver: ${LOG_DRIVER:-gelf}
      options:
        # This is relative to the host, not the container.
        gelf-address: "udp://127.0.0.1:12201"
        tag: "advisor-worker"
```

and in the top-level `volumes:` block (after `loki:` / before `secrets:`, keeping the order used there) add:

```yaml
  advisor-cache:
    driver: local
```

- [ ] **Step 5: Validate the compose file**

Run: `cd ../ort-server-service && docker compose config --quiet && echo OK`
Expected: `OK`. (If required env files are missing, run `docker compose config --quiet 2>&1 | head` and make sure the only complaints are unrelated to `advisor-cache`.)

- [ ] **Step 6: Commit (ort-server-service repo)**

```bash
cd ../ort-server-service
git add docker-compose.yml
git commit -m "$TICKET: Add a cache volume for the license obligation advisor"
```

---

### Task 10: Align the spec and run the full verification

**Files:**
- Modify: `docs/superpowers/specs/2026-10-14-license-obligation-advisor-design.md`

- [ ] **Step 1: Update the spec for the decisions made in this plan**

Apply these three edits in the spec:

1. Replace

```
- **Failures:** `LIC_OBL_ARTIFACT_UNAVAILABLE` results are not cached (or have a
  short TTL, default 1 hour) so transient failures do not stick.
```

with

```
- **Failures:** `LIC_OBL_ARTIFACT_UNAVAILABLE` results are never cached, so
  transient failures do not stick.
```

2. Delete the options table row

```
| `failedLookupTtl` | `1h` | TTL for unavailable-artifact results. |
```

3. In "Testing", replace `failed lookups not cached or expiring,` with `failed lookups not cached,`.

- [ ] **Step 2: Run the full verification**

Run: `./gradlew :shared:advisors:check :shared:plugin-info:test :workers:advisor:test`
Expected: BUILD SUCCESSFUL, which includes detekt for the new module. Fix reported style findings (import order, line length, missing license header) in the new files; do not suppress rules.

- [ ] **Step 3: Verify the worker image would contain the plugin**

Run: `./gradlew :workers:advisor:dependencies --configuration runtimeClasspath --quiet | grep -n "shared:advisors"`
Expected: a line containing `project :shared:advisors`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-14-license-obligation-advisor-design.md docs/superpowers/plans/2026-10-14-license-obligation-advisor.md
git commit -m "$TICKET: Align the license obligation advisor spec with the implementation plan"
```

---

### Task 11: Manual smoke test against the real Nexus (not automated)

- [ ] **Step 1: Build the advisor worker image** with the project's existing Jib setup (see `buildSrc/src/main/kotlin/ort-server-kotlin-jvm-application-conventions.gradle.kts`), tag it, and point `advisor-worker` in the `ort-server-service` compose file at it.
- [ ] **Step 2: Start the stack**: `docker compose up -d advisor-worker`, then `docker compose exec advisor-worker ls -ld /home/ort/.cache` and confirm the owner is `ort`.
- [ ] **Step 3: Trigger one run** with `advisors: ["LicenseObligation"]` (and `cyclonedx` as report format) for a small repository, for example the one behind run 12225 (`estore-deployment-service-api`).
- [ ] **Step 4: Check the results**: download `bom.cyclonedx.json` of the run via `/api/v1/runs/<id>/reporter/bom.cyclonedx.json`; confirm `vulnerabilities` contains `LIC_OBL_*` entries with `affects.ref` pointing at components, and that `/home/ort/.cache/advisor` contains `*.json` files in the container.
- [ ] **Step 5: Re-run** the same run and confirm in the worker log that no artifacts are downloaded again (cache hits), and upload the BOM to Dependency-Track to confirm the findings appear as vulnerabilities from the BOM (open question in the spec: audit state on re-upload).

---

## Self-review (spec coverage)

| Spec section | Task |
|---|---|
| Findings, ids, severities (`LIC_OBL_MISSING_LICENSE` HIGH, `_MISSING_NOTICE` LOW, `_ARTIFACT_UNAVAILABLE` LOW) | 4, 7 |
| Reference with `scoringSystem = Other`, severity, policy URL | 7 |
| Rules (license always; notice for Apache-2.0/ECL-2.0; AND/OR semantics; unknown license) | 4 |
| Default patterns, root and `META-INF/`, extensions | 2 |
| Skip non-Maven, metadata-only, `.pom` | 7 |
| Streaming extraction, entry-count and size guards, hash verification | 3, 5 |
| Credentials via secrets | 5, 7 |
| Facts-only cache, key from URL + hash + extractor version, atomic writes, failures not cached | 6, 7 (spec adjusted in 10) |
| `cacheDir` default `/home/ort/.cache/advisor`, empty disables, unusable dir falls back | 6, 7 |
| Named volume at `/home/ort/.cache`, no init container | 9 |
| Module `shared/advisors`, worker runtime dependency | 1 |
| Plugin options table | 7 |
| CycloneDX round trip | 8 |
| Plugin id `LicenseObligation` listed | 9 |
| Shaded/fat JARs treated as normal JARs, extension prepared | 2 (`extractorVersion`), 3 (inspector walks all entries); no code needed |
| Dependency-Track operational notes, audit-state open point | 11 (manual) |

Placeholder scan: none; the only variable left to fill in is `$TICKET` (Jira key), set once per session. Type consistency: `ArtifactLegalFacts(extractorVersion, entryCount, licenseFiles, noticeFiles)`, `LegalFilePattern.matches`, `ArtifactInspector(licensePattern, noticePattern, maxEntries).inspect`, `ObligationRules(noticeRequiredLicenses).evaluate/unavailable`, `FindingSeverity`, `ArtifactDownloader(maxBytes, username, password).download`, `createCache`, `cacheKey`, `LicenseObligationCache.get/put` are used with identical names and signatures in all tasks.

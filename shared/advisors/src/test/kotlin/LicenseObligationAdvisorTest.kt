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
package org.eclipse.apoapsis.ortserver.shared.advisors

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

import java.io.File
import java.nio.file.Files
import java.util.Base64

import kotlinx.coroutines.CancellationException

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
        tempDirs.forEach { directory -> directory.deleteRecursively() }
    }

    beforeTest { server.requestCount.set(0) }

    fun tempDir() = Files.createTempDirectory("lic-obl-advisor").toFile().also { directory -> tempDirs += directory }

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

    "artifacts without a verifiable hash are not cached" {
        val cacheDir = tempDir().absolutePath
        val pkg = pkg("unverified", "LICENSE" to "l", "NOTICE" to "n").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(hash = Hash.NONE))
        }

        advisor(cacheDir).retrievePackageFindings(setOf(pkg))
        advisor(cacheDir).retrievePackageFindings(setOf(pkg))

        server.requestCount.get() shouldBe 2
    }

    "an unexpected runtime failure for one package does not abort other packages" {
        val failing = pkg("unexpected", "LICENSE" to "l")
        val other = pkg("other-runtime", "LICENSE" to "l", license = "MIT")
        val factsLoader: (RemoteArtifact) -> ArtifactLegalFacts = { artifact ->
            if (artifact.url == failing.binaryArtifact.url) {
                error("sensitive failure detail")
            }

            ArtifactLegalFacts(entryCount = 1, licenseFiles = emptyList(), noticeFiles = emptyList())
        }

        val result = (advisor() as LicenseObligationAdvisor)
            .retrievePackageFindings(setOf(failing, other), factsLoader)

        result.keys shouldContainExactly setOf(failing, other)
        result.getValue(failing).vulnerabilities.single().id shouldBe "LIC_OBL_ARTIFACT_UNAVAILABLE"
        val unavailableDescription = result.getValue(failing).vulnerabilities.single().description!!
        unavailableDescription shouldContain "unexpected error"
        unavailableDescription shouldNotContain "sensitive failure detail"
        result.getValue(other).vulnerabilities.map { it.id } shouldContainExactly listOf("LIC_OBL_MISSING_LICENSE")
    }

    "cancellation exceptions are rethrown" {
        val pkg = pkg("cancelled", "LICENSE" to "l")
        val factsLoader: (RemoteArtifact) -> ArtifactLegalFacts = { throw CancellationException("cancelled") }

        shouldThrow<CancellationException> {
            (advisor() as LicenseObligationAdvisor).retrievePackageFindings(setOf(pkg), factsLoader)
        }
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

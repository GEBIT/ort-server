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

import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

import kotlinx.coroutines.CancellationException
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

    override suspend fun retrievePackageFindings(packages: Set<Package>): Map<Package, AdvisorResult> =
        retrievePackageFindings(packages, ::loadFacts)

    internal suspend fun retrievePackageFindings(
        packages: Set<Package>,
        factsLoader: (RemoteArtifact) -> ArtifactLegalFacts
    ): Map<Package, AdvisorResult> {
        val startTime = Instant.now()
        val semaphore = Semaphore(config.maxParallelDownloads.coerceAtLeast(1))

        val findings = coroutineScope {
            packages.filter { it.isCandidate() }.map { pkg ->
                async(Dispatchers.IO) { semaphore.withPermit { pkg to check(pkg, factsLoader) } }
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

    @Suppress("TooGenericExceptionCaught")
    private fun check(
        pkg: Package,
        factsLoader: (RemoteArtifact) -> ArtifactLegalFacts
    ): List<ObligationFinding> {
        val artifact = pkg.binaryArtifact

        if (artifact.url.isBlank()) return listOf(rules.unavailable("the package has no binary artifact", ""))

        return try {
            val facts = factsLoader(artifact)

            rules.evaluate(pkg.concludedLicense ?: pkg.declaredLicensesProcessed.spdxExpression, facts, artifact.url)
        } catch (e: IOException) {
            logger.warn("Could not verify the artifact of '{}': {}", pkg.id.toCoordinates(), e.message)
            listOf(rules.unavailable(e.message.orEmpty(), artifact.url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            val reason = "unexpected error: ${e::class.simpleName ?: "RuntimeException"}"
            logger.warn("Unexpected error while checking artifact for '{}': {}", pkg.id.toCoordinates(), reason)
            listOf(rules.unavailable(reason, artifact.url))
        }
    }

    private fun loadFacts(artifact: RemoteArtifact): ArtifactLegalFacts {
        val cacheable = artifact.hash.value.isNotBlank() && artifact.hash.algorithm.isVerifiable
        val key = if (cacheable) cacheKey(artifact) else null

        key?.let { cache.get(it)?.let { facts -> return facts } }

        return downloader.download(artifact) { inspector.inspect(it) }.also { facts ->
            key?.let { cache.put(it, facts) }
        }
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

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
package org.eclipse.apoapsis.ortserver.shared.reporters

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

import org.cyclonedx.Format
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Component
import org.cyclonedx.model.Property
import org.cyclonedx.parsers.JsonParser
import org.cyclonedx.parsers.XmlParser

import org.ossreviewtoolkit.model.CuratedPackage
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.api.Secret
import org.ossreviewtoolkit.reporter.Reporter
import org.ossreviewtoolkit.reporter.ReporterFactory
import org.ossreviewtoolkit.reporter.ReporterInput

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(CycloneDxLicenseObligationReporter::class.java)

data class CycloneDxLicenseObligationReporterConfig(
    /** CycloneDX output formats. Supported values are JSON and XML. */
    @OrtPluginOption(defaultValue = "JSON")
    val outputFileFormats: List<String>,

    /** The base names of license files, e.g. `LICENSE` matches `META-INF/LICENSE.txt`. */
    @OrtPluginOption(defaultValue = "LICENSE,LICENCE,COPYING")
    val licenseFilePatterns: List<String>,

    /** The base names of notice files. */
    @OrtPluginOption(defaultValue = "NOTICE,NOTICES")
    val noticeFilePatterns: List<String>,

    /** The SPDX ids of the licenses that require a NOTICE file. */
    @OrtPluginOption(defaultValue = "Apache-2.0,ECL-2.0")
    val noticeRequiredLicenses: List<String>,

    /** The directory of the file cache. The default is inside the `.cache` volume of the reporter worker container. */
    @OrtPluginOption(defaultValue = "/home/ort/.cache/license-obligation")
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

    /** Maven namespaces to skip. Each entry matches the namespace itself and all its sub-namespaces. */
    @OrtPluginOption(defaultValue = "")
    val skipNamespaces: List<String>,

    /** The user name for basic authentication against the artifact repository. */
    val username: Secret?,

    /** The password for basic authentication against the artifact repository. */
    val password: Secret?
) {
    init {
        require(outputFileFormats.isNotEmpty() && outputFileFormats.all { it.uppercase() in SUPPORTED_FORMATS }) {
            "CycloneDX output formats must be JSON or XML."
        }
    }

    companion object {
        private val SUPPORTED_FORMATS = setOf("JSON", "XML")
    }
}

/**
 * A reporter that adds license-obligation findings as CycloneDX component properties to the standard CycloneDX BOM.
 */
@OrtPlugin(
    id = "CycloneDxLicenseObligation",
    displayName = "CycloneDX License Obligation",
    summary = "Adds missing license and notice findings as CycloneDX component properties.",
    factory = ReporterFactory::class
)
class CycloneDxLicenseObligationReporter(
    override val descriptor: PluginDescriptor = CycloneDxLicenseObligationReporterFactory.descriptor,
    private val config: CycloneDxLicenseObligationReporterConfig
) : Reporter {
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

    private val skipNamespaces = config.skipNamespaces.map(String::trim).filter(String::isNotEmpty)

    override fun generateReport(input: ReporterInput, outputDir: File): List<Result<File>> {
        val targetDir = outputDir.resolve(".cyclonedx-license-obligation")
        targetDir.deleteRecursively()
        targetDir.mkdirs()

        return try {
            runCatching {
                val formats = config.outputFileFormats.joinToString(",") { it.uppercase() }
                val findings = findFindings(input.ortResult.getPackages(omitExcluded = true)).getOrThrow()
                val stockResults = ReporterFactory.ALL.getValue("CycloneDX").create(
                    PluginConfig(options = mapOf("outputFileFormats" to formats, "singleBom" to "true"))
                ).generateReport(input, targetDir)

                stockResults.map { stockResult ->
                    stockResult.fold(
                        onSuccess = { stockFile ->
                            runCatching {
                                val format = if (stockFile.extension.equals("xml", ignoreCase = true)) {
                                    Format.XML
                                } else {
                                    Format.JSON
                                }
                                val bom = parseBom(stockFile, format)
                                addProperties(bom, findings)
                                bom.vulnerabilities = null

                                val reportFile = outputDir.resolve(stockFile.name)
                                reportFile.writeText(bom.toString(format))
                                reportFile
                            }
                        },
                        onFailure = { Result.failure(it) }
                    )
                }
            }.getOrElse { listOf(Result.failure(it)) }
        } finally {
            targetDir.deleteRecursively()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun findFindings(packages: Set<CuratedPackage>): Result<Map<String, List<ObligationFinding>>> {
        val candidates = packages.map { it.metadata }.filter { it.isCandidate() }
        val executor = Executors.newFixedThreadPool(config.maxParallelDownloads.coerceAtLeast(1)) { task ->
            Thread(task, "license-obligation-check").apply { isDaemon = true }
        }
        var completedNormally = false

        return try {
            val futures: List<Future<Pair<String, List<ObligationFinding>>>> = candidates.map { pkg ->
                executor.submit<Pair<String, List<ObligationFinding>>> {
                    val packageFindings = try {
                        check(pkg)
                    } catch (e: Throwable) {
                        if (e is InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }

                        val reason = "unexpected error: ${e::class.simpleName ?: "Throwable"}"
                        logger.warn(
                            "Unexpected error while checking artifact for '{}': {}",
                            pkg.id.toCoordinates(),
                            reason
                        )
                        listOf(rules.unavailable(reason, pkg.binaryArtifact.url))
                    }

                    pkg.id.toCoordinates() to packageFindings
                }
            }

            val findings = mutableMapOf<String, List<ObligationFinding>>()

            for (future in futures) {
                val (coordinates, packageFindings) = try {
                    future.get()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    executor.shutdownNow()
                    return Result.failure(e)
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e

                    if (cause is InterruptedException) {
                        Thread.currentThread().interrupt()
                        executor.shutdownNow()
                        return Result.failure(cause)
                    }

                    throw cause
                }

                if (packageFindings.isNotEmpty()) {
                    findings[coordinates] = packageFindings
                }
            }

            completedNormally = true
            Result.success(findings)
        } finally {
            if (completedNormally) {
                executor.shutdown()
            } else {
                executor.shutdownNow()
            }
        }
    }

    private fun Package.isCandidate() =
        id.type == "Maven" && !isMetadataOnly && !binaryArtifact.url.endsWith(".pom") && !isSkippedNamespace()

    private fun Package.isSkippedNamespace() =
        skipNamespaces.any { id.namespace == it || id.namespace.startsWith("$it.") }

    @Suppress("TooGenericExceptionCaught")
    private fun check(pkg: Package): List<ObligationFinding> {
        val artifact = pkg.binaryArtifact

        if (artifact.url.isBlank()) return listOf(rules.unavailable("the package has no binary artifact", ""))

        return try {
            val facts = loadFacts(artifact)
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

    private fun parseBom(file: File, format: Format): Bom =
        when (format) {
            Format.JSON -> JsonParser().parse(file)
            Format.XML -> XmlParser().parse(file)
        }

    private fun addProperties(bom: Bom, findings: Map<String, List<ObligationFinding>>) {
        bom.components.orEmpty().flatMap { it.allComponents() }.forEach { component ->
            val properties = component.properties.orEmpty().toMutableList()
            val existingNames = properties.mapTo(mutableSetOf()) { it.name }

            findings[component.bomRef]?.forEach { finding ->
                val propertyName = finding.id.toPropertyName()

                if (propertyName !in existingNames) {
                    properties += Property(propertyName, finding.description)
                    existingNames += propertyName
                }
            }

            if (properties.isNotEmpty()) component.properties = properties
        }
    }

    private fun Component.allComponents(): List<Component> =
        listOf(this) + components.orEmpty().flatMap { it.allComponents() }

    private fun String.toPropertyName(): String =
        when (this) {
            LIC_OBL_MISSING_LICENSE -> "gebit:lic-obl-missing-license"
            LIC_OBL_MISSING_NOTICE -> "gebit:lic-obl-missing-notice"
            LIC_OBL_ARTIFACT_UNAVAILABLE -> "gebit:lic-obl-artifact-unavailable"
            else -> error("Unknown license obligation finding id: $this")
        }

    private fun Bom.toString(format: Format): String =
        when (format) {
            Format.JSON -> BomGeneratorFactory.createJson(Version.VERSION_16, this).toJsonString()
            Format.XML -> BomGeneratorFactory.createXml(Version.VERSION_16, this).toXmlString()
        }
}

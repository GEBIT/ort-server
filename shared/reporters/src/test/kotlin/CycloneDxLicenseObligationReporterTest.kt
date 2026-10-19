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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.cyclonedx.parsers.XmlParser

import org.eclipse.apoapsis.ortserver.shared.orttestdata.OrtTestData

import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.AnalyzerRun
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.HashAlgorithm
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.reporter.ReporterFactory
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

class CycloneDxLicenseObligationReporterTest : StringSpec({
    val server = TestArtifactServer()
    val outputDir = File("build/license-obligation-reporter-test")

    afterSpec {
        server.close()
        outputDir.deleteRecursively()
    }

    beforeTest {
        server.requestCount.set(0)
    }

    fun pkg(name: String, vararg entries: Pair<String, String>): Package {
        val content = buildJar(*entries)
        val url = server.serve("/$name-1.0.jar", content)

        return Package.EMPTY.copy(
            id = Identifier("Maven", "org.example", name, "1.0"),
            purl = "pkg:maven/org.example/$name@1.0",
            binaryArtifact = RemoteArtifact(url, Hash(sha1(content), HashAlgorithm.SHA1)),
            concludedLicense = SpdxExpression.parse("Apache-2.0")
        )
    }

    fun report(packages: Set<Package>, format: String = "JSON", options: Map<String, String> = emptyMap()): File {
        val formatDir = outputDir.resolve(format.lowercase())
        formatDir.deleteRecursively()
        formatDir.mkdirs()

        val reporter = ReporterFactory.ALL.getValue("CycloneDxLicenseObligation").create(
            PluginConfig(options = mapOf("outputFileFormats" to format, "cacheDir" to "") + options)
        )
        val project = OrtTestData.project.copy(
            scopeDependencies = setOf(Scope("compile", packages.mapTo(mutableSetOf()) { PackageReference(it.id) })),
            scopeNames = null
        )
        val ortResult = OrtResult.EMPTY.copy(
            repository = OrtTestData.repository,
            analyzer = AnalyzerRun.EMPTY.copy(
                result = AnalyzerResult.EMPTY.copy(projects = setOf(project), packages = packages)
            )
        )

        return reporter.generateReport(ReporterInput(ortResult), formatDir).single().getOrThrow()
    }

    fun obligationProperties(component: kotlinx.serialization.json.JsonObject) =
        component.getValue("properties").jsonArray.map { it.jsonObject }.filter {
            it.getValue("name").jsonPrimitive.content.startsWith("gebit:lic-obl-")
        }

    "findings become component properties and clean components have none" {
        val affected = pkg("affected", "META-INF/README" to "content")
        val clean = pkg("clean", "LICENSE" to "license", "NOTICE" to "notice")

        val bom = Json.parseToJsonElement(report(setOf(affected, clean)).readText()).jsonObject
        val components = bom.getValue("components").jsonArray.map { it.jsonObject }
        val affectedComponent = components.single { it.getValue("name").jsonPrimitive.content == "affected" }
        val cleanComponent = components.single { it.getValue("name").jsonPrimitive.content == "clean" }
        val findings = obligationProperties(affectedComponent)

        components.size shouldBe 3
        affectedComponent.getValue("licenses").jsonArray.isNotEmpty() shouldBe true
        val metadataComponentName =
            bom.getValue("metadata").jsonObject.getValue("component").jsonObject.getValue("name").jsonPrimitive.content
        metadataComponentName shouldBe "project"
        bom.getValue("dependencies").jsonArray.isNotEmpty() shouldBe true
        findings.map { it.getValue("name").jsonPrimitive.content } shouldContainExactly listOf(
            "gebit:lic-obl-missing-license",
            "gebit:lic-obl-missing-notice"
        )
        findings.first().getValue("value").jsonPrimitive.content shouldContain
                "The distributed artifact contains no license file"
        obligationProperties(cleanComponent) shouldBe emptyList()
        bom["vulnerabilities"] shouldBe null
    }

    "only candidate packages outside skipped namespaces are checked" {
        val exact = pkg("exact", "README" to "x")
        val nested = pkg("nested", "README" to "x").let {
            it.copy(id = it.id.copy(namespace = "org.example.inner"))
        }
        val prefixOnly = pkg("prefix", "README" to "x").let {
            it.copy(id = it.id.copy(namespace = "org.examples"))
        }
        val nonMaven = pkg("non-maven", "README" to "x").let { it.copy(id = it.id.copy(type = "NPM")) }
        val metadataOnly = pkg("metadata-only", "README" to "x").copy(isMetadataOnly = true)
        val pom = pkg("pom", "README" to "x").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(url = server.url("/parent-1.0.pom")))
        }

        val bom = Json.parseToJsonElement(
            report(
                setOf(exact, nested, prefixOnly, nonMaven, metadataOnly, pom),
                options = mapOf("skipNamespaces" to "org.example")
            ).readText()
        ).jsonObject
        val components = bom.getValue("components").jsonArray.map { it.jsonObject }

        server.requestCount.get() shouldBe 1
        obligationProperties(components.single { it.getValue("name").jsonPrimitive.content == "exact" }) shouldBe
                emptyList()
        obligationProperties(components.single { it.getValue("name").jsonPrimitive.content == "nested" }) shouldBe
                emptyList()
        obligationProperties(components.single { it.getValue("name").jsonPrimitive.content == "prefix" })
            .map { it.getValue("name").jsonPrimitive.content } shouldContain "gebit:lic-obl-missing-license"
    }

    "cache avoids repeat downloads and per-package failures are reported as unavailable" {
        val cached = pkg("cached", "NOTICE" to "n")
        val cacheDir = outputDir.resolve("cache").path

        val firstReport = Json.parseToJsonElement(
            report(setOf(cached), options = mapOf("cacheDir" to cacheDir)).readText()
        ).jsonObject
        val firstRequestCount = server.requestCount.get()
        val secondReport = Json.parseToJsonElement(
            report(setOf(cached), options = mapOf("cacheDir" to cacheDir)).readText()
        ).jsonObject

        firstRequestCount shouldBe 1
        server.requestCount.get() shouldBe 1
        val firstFinding = obligationProperties(
            firstReport.getValue("components").jsonArray.map { it.jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "cached" }
        ).single()
        val secondFinding = obligationProperties(
            secondReport.getValue("components").jsonArray.map { it.jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "cached" }
        ).single()
        firstFinding shouldBe secondFinding

        server.requestCount.set(0)
        val unverified = pkg("unverified", "LICENSE" to "license").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(hash = Hash.NONE))
        }
        report(setOf(unverified), options = mapOf("cacheDir" to cacheDir))
        report(setOf(unverified), options = mapOf("cacheDir" to cacheDir))
        server.requestCount.get() shouldBe 2

        val unavailable = pkg("unavailable", "LICENSE" to "license").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(url = server.url("/missing.jar")))
        }
        val blankUrl = pkg("blank-url", "LICENSE" to "license").let {
            it.copy(binaryArtifact = it.binaryArtifact.copy(url = ""))
        }
        val good = pkg("still-good", "LICENSE" to "license", "NOTICE" to "notice")
        val failureBom = Json.parseToJsonElement(report(setOf(unavailable, blankUrl, good)).readText()).jsonObject
        val failureComponents = failureBom.getValue("components").jsonArray.map { it.jsonObject }
        val unavailableFinding = obligationProperties(
            failureComponents.single { it.getValue("name").jsonPrimitive.content == "unavailable" }
        ).single()

        unavailableFinding.getValue("name").jsonPrimitive.content shouldBe "gebit:lic-obl-artifact-unavailable"
        unavailableFinding.getValue("value").jsonPrimitive.content shouldContain "could not be verified"
        val blankUrlFinding = obligationProperties(
            failureComponents.single { it.getValue("name").jsonPrimitive.content == "blank-url" }
        ).single()
        blankUrlFinding.getValue("name").jsonPrimitive.content shouldBe "gebit:lic-obl-artifact-unavailable"
        blankUrlFinding.getValue("value").jsonPrimitive.content shouldContain "no artifact URL"
        val goodProperties =
            obligationProperties(failureComponents.single { it.getValue("name").jsonPrimitive.content == "still-good" })
        goodProperties shouldBe emptyList()
    }

    "XML output is generated with obligation properties" {
        val affected = pkg("xml", "README" to "content")

        val reportFile = report(setOf(affected), "XML")

        reportFile.name shouldBe "bom.cyclonedx.xml"
        val bom = XmlParser().parse(reportFile)
        val findings = bom.components.single { it.name == "xml" }.properties
            .filter { it.name.startsWith("gebit:lic-obl-") }

        findings.map { it.name } shouldContainExactly listOf(
            "gebit:lic-obl-missing-license",
            "gebit:lic-obl-missing-notice"
        )
    }
})

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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import java.io.File
import java.net.URI
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
                result = AnalyzerResult.EMPTY.copy(packages = setOf(pkg))
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

        val outputDir = File("build/cdx-roundtrip")
        outputDir.deleteRecursively()
        outputDir.mkdirs()

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

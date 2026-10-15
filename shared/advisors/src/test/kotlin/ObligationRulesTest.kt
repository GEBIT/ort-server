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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import org.ossreviewtoolkit.utils.spdxexpression.SpdxExpression

class ObligationRulesTest : StringSpec({
    val rules = ObligationRules(setOf("Apache-2.0", "ECL-2.0"))
    val url = "https://nexus.example/lib-1.0.jar"

    fun facts(hasLicense: Boolean, hasNotice: Boolean) = ArtifactLegalFacts(
        entryCount = 3,
        licenseFiles = if (hasLicense) listOf("META-INF/LICENSE") else emptyList(),
        noticeFiles = if (hasNotice) listOf("META-INF/NOTICE") else emptyList()
    )

    fun ids(license: String?, hasLicense: Boolean, hasNotice: Boolean) =
        rules.evaluate(license?.let { SpdxExpression.parse(it) }, facts(hasLicense, hasNotice), url).map { it.id }

    "an artifact with license and notice has no findings" {
        ids("Apache-2.0", hasLicense = true, hasNotice = true).shouldBeEmpty()
    }

    "a missing license file is a HIGH finding for every license" {
        listOf("MIT", "Apache-2.0", "EPL-2.0", "LicenseRef-GEBIT").forEach { license ->
            val findings = rules.evaluate(
                SpdxExpression.parse(license),
                facts(hasLicense = false, hasNotice = true),
                url
            )

            findings.map { it.id } shouldBe listOf(LIC_OBL_MISSING_LICENSE)
            findings.single().severity shouldBe FindingSeverity.HIGH
            findings.single().description shouldContain url
        }
    }

    "a missing license file is reported even if the license is unknown" {
        ids(null, hasLicense = false, hasNotice = false) shouldBe listOf(LIC_OBL_MISSING_LICENSE)
    }

    "a missing notice is a LOW finding for Apache-2.0 and ECL-2.0" {
        listOf("Apache-2.0", "ECL-2.0", "Apache-2.0+").forEach { license ->
            val findings = rules.evaluate(
                SpdxExpression.parse(license),
                facts(hasLicense = true, hasNotice = false),
                url
            )

            findings.map { it.id } shouldBe listOf(LIC_OBL_MISSING_NOTICE)
            findings.single().severity shouldBe FindingSeverity.LOW
            findings.single().description shouldContain license
        }
    }

    "a missing notice is no finding for licenses that do not require it" {
        listOf("MIT", "EPL-2.0", "BSD-3-Clause", "LicenseRef-GEBIT").forEach { license ->
            ids(license, hasLicense = true, hasNotice = false).shouldBeEmpty()
        }
        ids(null, hasLicense = true, hasNotice = false).shouldBeEmpty()
    }

    "AND requires a notice if any operand requires it" {
        ids("MIT AND Apache-2.0", hasLicense = true, hasNotice = false) shouldBe listOf(LIC_OBL_MISSING_NOTICE)
    }

    "OR requires a notice only if every alternative requires it" {
        ids("MIT OR Apache-2.0", hasLicense = true, hasNotice = false).shouldBeEmpty()
        ids("Apache-2.0 OR ECL-2.0", hasLicense = true, hasNotice = false) shouldBe listOf(LIC_OBL_MISSING_NOTICE)
    }

    "WITH exceptions do not hide the license" {
        ids("Apache-2.0 WITH LLVM-exception", hasLicense = true, hasNotice = false) shouldBe
                listOf(LIC_OBL_MISSING_NOTICE)
    }

    "both findings are reported in a stable order" {
        ids("Apache-2.0", hasLicense = false, hasNotice = false) shouldBe
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

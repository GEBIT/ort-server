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

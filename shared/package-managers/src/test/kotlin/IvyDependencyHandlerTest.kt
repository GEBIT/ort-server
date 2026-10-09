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
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.PackageLinkage

class IvyDependencyHandlerTest : StringSpec({
    val child = IvyDependency("o", "child", "2", "", "", emptySet(), "", "")
    val dependency = IvyDependency(
        organisation = "de.gebit.trend",
        name = "trend-base",
        revision = "1.0",
        description = "Base module",
        homepageUrl = "https://example.org",
        licenses = setOf("Apache-2.0"),
        binaryArtifactUrl = "https://repo/trend-base-1.0.jar",
        sourceArtifactUrl = ""
    ).apply { dependencies += child }

    "Dependencies are identified with Maven coordinates" {
        IvyDependencyHandler().identifierFor(dependency) shouldBe
            Identifier("Maven", "de.gebit.trend", "trend-base", "1.0")
    }

    "The children of a dependency are its dependencies and the linkage is dynamic" {
        val handler = IvyDependencyHandler()

        handler.dependenciesFor(dependency) shouldBe listOf(child)
        handler.linkageFor(dependency) shouldBe PackageLinkage.DYNAMIC
    }

    "Packages carry the module metadata" {
        val issues = mutableListOf<Issue>()

        val pkg = IvyDependencyHandler().createPackage(dependency, issues)!!

        pkg.id shouldBe Identifier("Maven", "de.gebit.trend", "trend-base", "1.0")
        pkg.description shouldBe "Base module"
        pkg.homepageUrl shouldBe "https://example.org"
        pkg.declaredLicenses shouldBe setOf("Apache-2.0")
        pkg.binaryArtifact.url shouldBe "https://repo/trend-base-1.0.jar"
        pkg.sourceArtifact.url shouldBe ""
        issues shouldBe emptyList()
    }
})

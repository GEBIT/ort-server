/*
 * Copyright (C) 2025 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
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

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.packagemanagers.pub.Pub
import org.ossreviewtoolkit.plugins.packagemanagers.pub.PubFactory

/** A Kaml instance mirroring ORT's built-in, strict Pub configuration (anchors/aliases forbidden). */
private val strictYaml = Yaml(configuration = YamlConfiguration(strictMode = false))

class PubNoDevDependenciesTest : StringSpec({
    "The plugin is registered under its own id" {
        PackageManagerFactory.ALL shouldContainKey "PubNoDevDependencies"
    }

    "The plugin delegates its definition file globs and project type to the built-in Pub package manager" {
        val delegate = PubFactory.create()
        val plugin = PackageManagerFactory.ALL.getValue("PubNoDevDependencies").create(PluginConfig.EMPTY)

        plugin.globsForDefinitionFiles shouldBe delegate.globsForDefinitionFiles
        plugin.projectType shouldBe delegate.projectType
    }

    "resolveDependencies() forwards the multi-file result from the built-in Pub package manager and merges in a " +
        "hard-coded 'dev_dependencies' scope exclude" {
        mockkConstructor(Pub::class)
        try {
            val expectedResult = PackageManagerResult(
                projectResults = emptyMap(),
                dependencyGraph = null,
                sharedPackages = emptySet()
            )
            val excludesSlot = slot<Excludes>()

            every {
                anyConstructed<Pub>().resolveDependencies(
                    any(),
                    any<List<File>>(),
                    capture(excludesSlot),
                    any(),
                    any(),
                    any()
                )
            } returns expectedResult

            val analysisRoot = File(".")
            val definitionFile = File(analysisRoot, "pubspec.yaml")
            val plugin = PackageManagerFactory.ALL.getValue("PubNoDevDependencies").create(PluginConfig.EMPTY)

            val actualResult = plugin.resolveDependencies(
                analysisRoot,
                listOf(definitionFile),
                Excludes.EMPTY,
                Includes.EMPTY,
                AnalyzerConfiguration(),
                emptyMap()
            )

            actualResult shouldBe expectedResult
            excludesSlot.captured.scopes.map { it.pattern } shouldContain "dev_dependencies"
        } finally {
            unmockkConstructor(Pub::class)
        }
    }

    "beforeResolution() expands YAML anchors/aliases in pubspec.yaml files so that the delegate's strict parser " +
        "(which forbids them, see PubNoDevDependencies' documentation) can subsequently read them without failing" {
        mockkConstructor(Pub::class)
        try {
            every { anyConstructed<Pub>().beforeResolution(any(), any(), any()) } returns Unit

            val tempDir = kotlin.io.path.createTempDirectory("pub-no-dev-dependencies-test").toFile()
            val definitionFile = File(tempDir, "pubspec.yaml").apply {
                writeText(
                    """
                    name: some_package
                    environment: &env
                      sdk: '>=2.19.0 <4.0.0'
                    dependencies:
                      some_dep:
                        environment: *env
                    """.trimIndent()
                )
            }

            val plugin = PackageManagerFactory.ALL.getValue("PubNoDevDependencies").create(PluginConfig.EMPTY)

            plugin.beforeResolution(tempDir, listOf(definitionFile), AnalyzerConfiguration())

            val rewritten = definitionFile.readText()
            rewritten shouldNotContain "&"
            rewritten shouldNotContain "*"

            // The delegate's own strict configuration (anchors/aliases forbidden) must now be able to parse the
            // rewritten file without throwing.
            strictYaml.parseToYamlNode(rewritten)
        } finally {
            unmockkConstructor(Pub::class)
        }
    }
})

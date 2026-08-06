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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.packagemanagers.node.npm.Npm
import org.ossreviewtoolkit.plugins.packagemanagers.node.npm.NpmFactory

class NoDevDependenciesNpmTest : StringSpec({
    "The plugin is registered under its own id" {
        PackageManagerFactory.ALL shouldContainKey "NpmNoDevDependencies"
    }

    "The plugin delegates its definition file globs to the built-in NPM package manager" {
        val delegateGlobs = NpmFactory().create(PluginConfig.EMPTY).globsForDefinitionFiles
        val pluginGlobs = PackageManagerFactory.ALL.getValue("NpmNoDevDependencies")
            .create(PluginConfig.EMPTY)
            .globsForDefinitionFiles

        pluginGlobs shouldBe delegateGlobs
    }

    "resolveDependencies() forwards the multi-file result including the dependency graph built by the built-in " +
        "NPM package manager (regression test: previously this fell back to the base class's default " +
        "'createPackageManagerResult()', which discards the dependency graph and always returns an empty one)" {
        // The built-in NPM package manager builds its dependency graph internally (via its own 'graphBuilder'
        // property) and only exposes it via the return value of its own multi-file resolveDependencies() override.
        // Mock the delegate's construction to return a result with a known, non-null dependency graph, and verify
        // the plugin forwards that exact result unchanged instead of discarding it.
        mockkConstructor(Npm::class)
        try {
            val fakeDependencyGraph = mockk<DependencyGraph>()
            val expectedResult = PackageManagerResult(
                projectResults = emptyMap(),
                dependencyGraph = fakeDependencyGraph,
                sharedPackages = emptySet()
            )
            val excludesSlot = slot<Excludes>()

            every {
                anyConstructed<Npm>().resolveDependencies(
                    any(),
                    any<List<File>>(),
                    capture(excludesSlot),
                    any(),
                    any(),
                    any()
                )
            } returns expectedResult

            val analysisRoot = File(".")
            val definitionFile = File(analysisRoot, "package.json")
            val plugin = PackageManagerFactory.ALL.getValue("NpmNoDevDependencies").create(PluginConfig.EMPTY)

            val actualResult = plugin.resolveDependencies(
                analysisRoot,
                listOf(definitionFile),
                Excludes.EMPTY,
                Includes.EMPTY,
                AnalyzerConfiguration(),
                emptyMap()
            )

            // The delegate's result (in particular its dependency graph) must be passed through unchanged.
            actualResult shouldBe expectedResult
            actualResult.dependencyGraph shouldBe fakeDependencyGraph

            // The 'devDependencies' scope exclude must still have been merged in before delegating.
            excludesSlot.captured.scopes.map { it.pattern } shouldContain "devDependencies"
        } finally {
            unmockkConstructor(Npm::class)
        }
    }
})

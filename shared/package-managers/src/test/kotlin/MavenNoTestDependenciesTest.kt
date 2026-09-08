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
import io.mockk.verify

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.packagemanagers.maven.Maven
import org.ossreviewtoolkit.plugins.packagemanagers.maven.MavenFactory

class MavenNoTestDependenciesTest : StringSpec({
    "The plugin is registered under its own id" {
        PackageManagerFactory.ALL shouldContainKey "MavenNoTestDependencies"
    }

    "The plugin delegates its definition file globs and project type to the built-in Maven package manager" {
        val delegate = MavenFactory.create()
        val plugin = PackageManagerFactory.ALL.getValue("MavenNoTestDependencies").create(PluginConfig.EMPTY)

        plugin.globsForDefinitionFiles shouldBe delegate.globsForDefinitionFiles
        plugin.projectType shouldBe delegate.projectType
    }

    "resolveDependencies() forwards the multi-file result including the dependency graph built by the built-in " +
        "Maven package manager (regression test: previously this fell back to the base class's default " +
        "'createPackageManagerResult()', which discards the dependency graph and always returns an empty one)" {
        // The built-in Maven package manager builds its dependency graph internally (in a private field) and only
        // exposes it via the return value of its own multi-file resolveDependencies() override. Mock the delegate's
        // construction to return a result with a known, non-null dependency graph, and verify the plugin forwards
        // that exact result unchanged instead of discarding it.
        mockkConstructor(Maven::class)
        try {
            val fakeDependencyGraph = mockk<DependencyGraph>()
            val expectedResult = PackageManagerResult(
                projectResults = emptyMap(),
                dependencyGraph = fakeDependencyGraph,
                sharedPackages = emptySet()
            )
            val excludesSlot = slot<Excludes>()

            every {
                anyConstructed<Maven>().resolveDependencies(
                    any(),
                    any<List<File>>(),
                    capture(excludesSlot),
                    any(),
                    any(),
                    any()
                )
            } returns expectedResult

            val analysisRoot = File(".")
            val definitionFile = File(analysisRoot, "pom.xml")
            val plugin = PackageManagerFactory.ALL.getValue("MavenNoTestDependencies").create(PluginConfig.EMPTY)

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

            // The 'test' scope exclude must still have been merged in before delegating.
            excludesSlot.captured.scopes.map { it.pattern } shouldContain "test"
        } finally {
            unmockkConstructor(Maven::class)
        }
    }

    "beforeResolution() configures the delegate with the first existing settings file candidate" {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val devSettings = File(tempDir, "development/settings.xml").apply {
                parentFile.mkdirs()
                writeText("<settings/>")
            }
            File(tempDir, "settings.xml").writeText("<settings/>")

            mockkConstructor(Maven::class)
            try {
                every { anyConstructed<Maven>().beforeResolution(any(), any(), any()) } returns Unit

                val plugin = PackageManagerFactory.ALL.getValue("MavenNoTestDependencies").create(
                    PluginConfig(
                        options = mapOf("settingsFileCandidates" to "development/settings.xml,settings.xml"),
                        secrets = emptyMap()
                    )
                ) as MavenNoTestDependencies
                plugin.beforeResolution(tempDir, emptyList(), AnalyzerConfiguration())

                verify {
                    anyConstructed<Maven>().beforeResolution(any(), any(), any())
                }
            } finally {
                unmockkConstructor(Maven::class)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    "resolveSettingsFile() falls back to no settings file when no candidate exists" {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val plugin = PackageManagerFactory.ALL.getValue("MavenNoTestDependencies").create(
                PluginConfig(
                    options = mapOf("settingsFileCandidates" to "development/settings.xml,settings.xml"),
                    secrets = emptyMap()
                )
            ) as MavenNoTestDependencies

            plugin.resolveSettingsFile(tempDir) shouldBe null
        } finally {
            tempDir.deleteRecursively()
        }
    }

    "resolveSettingsFile() trims blank entries and preserves priority order" {
        val tempDir = kotlin.io.path.createTempDirectory().toFile()
        try {
            File(tempDir, "settings.xml").writeText("<settings/>")
            val plugin = PackageManagerFactory.ALL.getValue("MavenNoTestDependencies").create(
                PluginConfig(
                    options = mapOf("settingsFileCandidates" to " , development/settings.xml , settings.xml , "),
                    secrets = emptyMap()
                )
            ) as MavenNoTestDependencies

            plugin.resolveSettingsFile(tempDir) shouldBe File(tempDir, "settings.xml")
        } finally {
            tempDir.deleteRecursively()
        }
    }
})

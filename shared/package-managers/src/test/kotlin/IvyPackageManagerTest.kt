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
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.plugins.api.PluginConfig

class IvyPackageManagerTest : StringSpec({
    "The plugin is registered under the id 'Ivy' with ivy.xml as definition file" {
        PackageManagerFactory.ALL shouldContainKey "Ivy"

        val plugin = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY)

        plugin.globsForDefinitionFiles shouldBe listOf("ivy.xml")
        plugin.projectType shouldBe "Ivy"
    }

    "Definition files below the configured skipDirs are ignored" {
        val root = File("/repo")
        val files = listOf(
            File("/repo/ivy.xml"),
            File("/repo/core/base/ivy.xml"),
            File("/repo/core/base/build/ivy.xml"),
            File("/repo/dist/lib/ivy.xml"),
            File("/repo/bin/ivy.xml"),
            File("/repo/a/other/ivy.xml")
        )

        val plugin = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY)

        plugin.mapDefinitionFiles(root, files, AnalyzerConfiguration()) shouldContainExactly listOf(
            File("/repo/ivy.xml"),
            File("/repo/core/base/ivy.xml"),
            File("/repo/a/other/ivy.xml")
        )
    }

    "The skipDirs option can be overridden" {
        val plugin = PackageManagerFactory.ALL.getValue("Ivy")
            .create(PluginConfig(options = mapOf("skipDirs" to "generated")))

        val files = listOf(File("/repo/generated/ivy.xml"), File("/repo/build/ivy.xml"))

        plugin.mapDefinitionFiles(File("/repo"), files, AnalyzerConfiguration()) shouldContainExactly
                listOf(File("/repo/build/ivy.xml"))
    }

    "A project is resolved into a project result with scopes and packages" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "c", "1")
        repo.publish("o", "a", "1", deps = listOf(TestDep("o", "c", "1")), license = "Apache-2.0")
        val definitionFile = repo.writeProject("project", listOf(TestDep("o", "a", "1", "compile->default")))

        val plugin = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY)
        val result = plugin.resolveDependencies(
            repo.root, listOf(definitionFile), Excludes(), Includes(), AnalyzerConfiguration(), emptyMap()
        )

        val projectResult = result.projectResults.getValue(definitionFile).single()
        val project = projectResult.project

        project.id shouldBe Identifier("Ivy", "test.org", "project", "1.0")
        project.scopeNames shouldBe setOf("compile", "runtime")
        projectResult.issues shouldBe emptyList()

        result.sharedPackages.map { it.id } shouldContainExactlyInAnyOrder listOf(
            Identifier("Maven", "o", "a", "1"), Identifier("Maven", "o", "c", "1")
        )

        val compile = result.dependencyGraph!!.createScopes().single { it.name == "compile" }
        compile.dependencies.single().id shouldBe Identifier("Maven", "o", "a", "1")
        compile.dependencies.single().dependencies.single().id shouldBe Identifier("Maven", "o", "c", "1")
    }

    "Scope excludes from the repository configuration are honored" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1")
        val definitionFile = repo.writeProject("project", listOf(TestDep("o", "a", "1", "compile->default")))

        val excludes = Excludes(scopes = listOf(ScopeExclude("compile", ScopeExcludeReason.PROVIDED_DEPENDENCY_OF)))
        val result = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY).resolveDependencies(
            repo.root, listOf(definitionFile), excludes, Includes(), AnalyzerConfiguration(), emptyMap()
        )

        result.projectResults.getValue(definitionFile).single().project.scopeNames shouldBe setOf("runtime")
    }

    "Unresolved dependencies are reported as issues on the project" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        val definitionFile = repo.writeProject("project", listOf(TestDep("o", "missing", "9", "compile->default")))

        val result = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY).resolveDependencies(
            repo.root, listOf(definitionFile), Excludes(), Includes(), AnalyzerConfiguration(), emptyMap()
        )

        val issues = result.projectResults.getValue(definitionFile).single().issues
        issues shouldHaveSize 1
        issues.single().message shouldContain "o#missing;9"
    }

    "A broken ivy.xml yields an error issue and does not abort the analysis" {
        val root = tempdir()
        val definitionFile = root.resolve("broken/ivy.xml").apply {
            parentFile.mkdirs()
            writeText("this is not xml")
        }

        val result = PackageManagerFactory.ALL.getValue("Ivy").create(PluginConfig.EMPTY).resolveDependencies(
            root, listOf(definitionFile), Excludes(), Includes(), AnalyzerConfiguration(), emptyMap()
        )

        val projectResult = result.projectResults.getValue(definitionFile).single()
        projectResult.project.id.type shouldBe "Ivy"
        projectResult.issues.single().severity shouldBe Severity.ERROR
    }
})

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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith

import org.ossreviewtoolkit.model.Severity

private fun IvyDependency.tree(): String =
    if (dependencies.isEmpty()) {
        "$name:$revision"
    } else {
        "$name:$revision(${dependencies.joinToString(",") { it.tree() }})"
    }

private fun IvyDependency.guardedTree(depth: Int = 0, maxDepth: Int = 10): String =
    if (depth >= maxDepth) {
        "depth-limit"
    } else if (dependencies.isEmpty()) {
        "$name:$revision"
    } else {
        "$name:$revision(${dependencies.joinToString(",") { it.guardedTree(depth + 1, maxDepth) }})"
    }

private fun IvyResolution.trees(conf: String) = scopes.getValue(conf).map { it.tree() }

class IvyResolverTest : StringSpec({
    "Transitive dependencies are resolved per configuration" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "c", "1")
        repo.publish("o", "b", "1", deps = listOf(TestDep("o", "c", "1")))
        repo.publish("o", "a", "1", deps = listOf(TestDep("o", "b", "1")), license = "Apache-2.0")
        repo.publish("o", "t", "1")
        val project = repo.writeProject(
            "project",
            listOf(TestDep("o", "a", "1", "compile->default"), TestDep("o", "t", "1", "test->default"))
        )

        val resolution = IvyResolver(ivyTestConfig()).resolve(repo.root, project)

        resolution.organisation shouldBe "test.org"
        resolution.module shouldBe "project"
        resolution.revision shouldBe "1.0"
        resolution.scopes.keys.toList() shouldContainExactly listOf("compile", "runtime")
        resolution.trees("compile") shouldContainExactly listOf("a:1(b:1(c:1))")
        resolution.trees("runtime").shouldBeEmpty()
        resolution.issues.shouldBeEmpty()
    }

    "Module metadata is taken from the resolved descriptor and repository" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1", license = "Apache-2.0")
        val project = repo.writeProject("project", listOf(TestDep("o", "a", "1", "compile->default")))

        val dependency = IvyResolver(ivyTestConfig()).resolve(repo.root, project).scopes.getValue("compile").single()

        dependency.organisation shouldBe "o"
        dependency.description shouldBe "a module"
        dependency.licenses shouldContainExactly setOf("Apache-2.0")
        dependency.binaryArtifactUrl shouldEndWith "a-1.jar"
    }

    "The confs option '*' resolves all public configurations except the excluded ones" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1")
        repo.publish("o", "t", "1")
        val project = repo.writeProject(
            "project",
            listOf(TestDep("o", "a", "1", "compile->default"), TestDep("o", "t", "1", "test->default")),
            confs = listOf("compile", "runtime", "test", "sources")
        )

        val config = ivyTestConfig(confs = listOf("*"), excludeConfs = listOf("test", "sources"))

        IvyResolver(config).resolve(repo.root, project).scopes.keys.toList() shouldContainExactly
                listOf("compile", "runtime")
    }

    "Configured configurations that the project does not declare are skipped" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        val project = repo.writeProject("project", emptyList(), confs = listOf("compile"))

        val resolution = IvyResolver(ivyTestConfig(confs = listOf("compile", "runtime"))).resolve(repo.root, project)

        resolution.scopes.keys.toList() shouldContainExactly listOf("compile")
    }

    "No declared configurations yields a warning instead of a clean empty resolution" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        val project = repo.root.resolve("project/ivy.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <ivy-module version="2.0">
                  <info organisation="test.org" module="project" revision="1.0"/>
                  <dependencies/>
                </ivy-module>
                """.trimIndent()
            )
        }

        val config = ivyTestConfig(confs = listOf("compile", "runtime"), excludeConfs = listOf("test", "sources"))
        val resolution = IvyResolver(config).resolve(repo.root, project)

        resolution.scopes.size shouldBe 0
        resolution.issues shouldHaveSize 1
        resolution.issues.single().source shouldBe "Ivy"
        resolution.issues.single().severity shouldBe Severity.WARNING
        resolution.issues.single().message shouldContain "default"
        resolution.issues.single().message shouldContain "compile, runtime"
        resolution.issues.single().message shouldContain "test, sources"
        resolution.issues.single().message shouldContain "confs"
        resolution.issues.single().message shouldContain "excludeConfs"
    }

    "Excluding all declared configurations yields a warning with configuration guidance" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        val project = repo.writeProject("project", emptyList(), confs = listOf("compile", "runtime"))
        val config = ivyTestConfig(
            confs = listOf("*"),
            excludeConfs = listOf("compile", "runtime")
        )

        val resolution = IvyResolver(config).resolve(repo.root, project)

        resolution.scopes.size shouldBe 0
        resolution.issues shouldHaveSize 1
        resolution.issues.single().source shouldBe "Ivy"
        resolution.issues.single().severity shouldBe Severity.WARNING
        resolution.issues.single().message shouldContain "compile, runtime"
        resolution.issues.single().message shouldContain "*"
        resolution.issues.single().message shouldContain "excludeConfs"
        resolution.issues.single().message shouldContain "Adjust"
    }

    "An unresolved dependency yields an issue while the other dependencies are still resolved" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1")
        val project = repo.writeProject(
            "project",
            listOf(TestDep("o", "a", "1", "compile->default"), TestDep("o", "missing", "9", "compile->default"))
        )

        val resolution = IvyResolver(ivyTestConfig()).resolve(repo.root, project)

        resolution.trees("compile") shouldContainExactly listOf("a:1")
        resolution.issues shouldHaveSize 1
        resolution.issues.single().severity shouldBe Severity.WARNING
        resolution.issues.single().message shouldContain "o#missing;9"
    }

    "Versions evicted by the conflict manager are not reported" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "c", "1")
        repo.publish("o", "c", "2")
        repo.publish("o", "a", "1", deps = listOf(TestDep("o", "c", "1")))
        repo.publish("o", "b", "1", deps = listOf(TestDep("o", "c", "2")))
        val project = repo.writeProject(
            "project",
            listOf(TestDep("o", "a", "1", "compile->default"), TestDep("o", "b", "1", "compile->default"))
        )

        val resolution = IvyResolver(ivyTestConfig()).resolve(repo.root, project)

        resolution.trees("compile") shouldContainExactly listOf("a:1(c:2)", "b:1(c:2)")
    }

    "Dependency cycles are resolved without creating unbounded dependency trees" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1", deps = listOf(TestDep("o", "b", "1")))
        repo.publish("o", "b", "1", deps = listOf(TestDep("o", "a", "1")))
        val project = repo.writeProject("project", listOf(TestDep("o", "a", "1", "compile->default")))

        val resolution = IvyResolver(ivyTestConfig()).resolve(repo.root, project)
        val tree = resolution.scopes.getValue("compile").single().guardedTree()

        tree shouldBe "a:1(b:1)"
    }

    "Offline resolution uses cached dependencies after the repository is removed" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        repo.publish("o", "a", "1")
        val project = repo.writeProject("project", listOf(TestDep("o", "a", "1", "compile->default")))

        val onlineResolution = IvyResolver(ivyTestConfig()).resolve(repo.root, project)

        onlineResolution.trees("compile") shouldContainExactly listOf("a:1")
        onlineResolution.scopes.getValue("compile").single().binaryArtifactUrl shouldEndWith "a-1.jar"

        repo.repoDir.deleteRecursively()

        val offlineResolution = IvyResolver(ivyTestConfig(offline = true)).resolve(repo.root, project)

        offlineResolution.trees("compile") shouldContainExactly listOf("a:1")
        offlineResolution.scopes.getValue("compile").single().binaryArtifactUrl shouldBe ""
    }
})

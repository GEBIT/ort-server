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

import java.io.File

/** A dependency declaration in an `ivy.xml`: [conf] is the Ivy `conf` mapping, e.g. `compile->default`. */
internal data class TestDep(val org: String, val name: String, val rev: String, val conf: String = "default")

/**
 * A local file-based Ivy repository below [root] with matching settings. Modules are published with a single
 * `default` configuration and an (empty) jar.
 */
internal class IvyTestRepo(val root: File) {
    val repoDir: File = root.resolve("repo")
    val cacheDir: File = root.resolve("cache")

    /** Publish a module with the given [deps], optionally with a [license]. */
    fun publish(org: String, name: String, rev: String, deps: List<TestDep> = emptyList(), license: String? = null) {
        val dir = repoDir.resolve("$org/$name/$rev").apply { mkdirs() }
        val licenseXml = license?.let { "<license name=\"$it\"/>" }.orEmpty()

        dir.resolve("ivy.xml").writeText(
            """
            <ivy-module version="2.0">
              <info organisation="$org" module="$name" revision="$rev">$licenseXml<description>$name module</description></info>
              <configurations><conf name="default"/></configurations>
              <publications><artifact name="$name" type="jar" ext="jar"/></publications>
              <dependencies>${deps.toXml()}</dependencies>
            </ivy-module>
            """.trimIndent()
        )
        dir.resolve("$name-$rev.jar").writeText("")
    }

    /** The settings XML using the local repository; paths are absolute. */
    fun settingsXml(): String =
        """
        <ivysettings>
          <settings defaultResolver="test"/>
          <caches defaultCacheDir="${cacheDir.absolutePath}"/>
          <resolvers>
            <filesystem name="test">
              <ivy pattern="${repoDir.absolutePath}/[organisation]/[module]/[revision]/ivy.xml"/>
              <artifact pattern="${repoDir.absolutePath}/[organisation]/[module]/[revision]/[artifact]-[revision].[ext]"/>
            </filesystem>
          </resolvers>
        </ivysettings>
        """.trimIndent()

    /** Write [settingsXml] to `ivysettings.xml` in [root] and return the file. */
    fun writeSettings(): File = root.resolve("ivysettings.xml").apply { writeText(settingsXml()) }

    /**
     * Write a project descriptor at `<dir>/ivy.xml` below [root] with the configurations [confs] and [deps], and
     * return it. [revision] may contain Ivy placeholders.
     */
    fun writeProject(
        dir: String,
        deps: List<TestDep>,
        confs: List<String> = listOf("compile", "runtime", "test"),
        org: String = "test.org",
        module: String = "project",
        revision: String = "1.0"
    ): File =
        root.resolve(dir).resolve("ivy.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <ivy-module version="2.0">
                  <info organisation="$org" module="$module" revision="$revision"/>
                  <configurations>${confs.joinToString("") { "<conf name=\"$it\"/>" }}</configurations>
                  <dependencies>${deps.toXml()}</dependencies>
                </ivy-module>
                """.trimIndent()
            )
        }

    private fun List<TestDep>.toXml() =
        joinToString("") { """<dependency org="${it.org}" name="${it.name}" rev="${it.rev}" conf="${it.conf}"/>""" }
}

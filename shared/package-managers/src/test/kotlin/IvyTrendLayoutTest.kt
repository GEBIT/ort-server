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
import io.kotest.matchers.shouldBe

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.plugins.api.PluginConfig

private const val D = "$"

class IvyTrendLayoutTest : StringSpec({
    "A repository with settings in a subdirectory, includes, properties and an extends parent is resolved" {
        val root = tempdir()
        val repo = IvyTestRepo(root)
        repo.publish("de.third", "lib", "3.0")
        repo.publish("de.third", "testlib", "1.0")

        repo.repoDir.resolve("de.gebit/parent/1.0").mkdirs()
        repo.repoDir.resolve("de.gebit/parent/1.0/ivy.xml").writeText(
            """
            <ivy-module version="2.0">
              <info organisation="de.gebit" module="parent" revision="1.0"/>
              <configurations><conf name="compile"/><conf name="mustHaveDependencies"/></configurations>
              <dependencies>
                <dependency org="de.third" name="lib" rev="3.0" conf="compile->default"/>
                <dependency org="de.third" name="testlib" rev="1.0" conf="mustHaveDependencies->default"/>
              </dependencies>
            </ivy-module>
            """.trimIndent()
        )

        val ant = root.resolve("ant-build").apply { mkdirs() }
        ant.resolve("ivysettings.xml").writeText(
            """
            <ivysettings>
              <properties file="$D{ivy.settings.dir}/build.properties_ci" override="false"/>
              <include file="$D{ivy.settings.dir}/ivysettings-online.xml"/>
            </ivysettings>
            """.trimIndent()
        )
        ant.resolve("ivysettings-online.xml").writeText(
            """
            <ivysettings>
              <settings defaultResolver="test"/>
              <caches defaultCacheDir="$D{cache.dir}"/>
              <resolvers>
                <filesystem name="test">
                  <ivy pattern="$D{repo.dir}/[organisation]/[module]/[revision]/ivy.xml"/>
                  <artifact pattern="$D{repo.dir}/[organisation]/[module]/[revision]/[artifact]-[revision].[ext]"/>
                </filesystem>
              </resolvers>
            </ivysettings>
            """.trimIndent()
        )
        ant.resolve("build.properties_ci").writeText("repo.dir=${repo.repoDir.absolutePath}\n")
        ant.resolve("dependencies.properties").writeText(
            "gebitparentivy.org=de.gebit\ngebitparentivy.name=parent\ngebitparentivy.rev=1.0\n"
        )

        val definitionFile = root.resolve("core/base/ivy.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <ivy-module version="2.0">
                  <info organisation="de.gebit.trend" module="trend-base" revision="$D{version.integration}">
                    <extends organisation="$D{gebitparentivy.org}" module="$D{gebitparentivy.name}"
                      revision="$D{gebitparentivy.rev}"/>
                  </info>
                  <configurations><conf name="compile"/></configurations>
                </ivy-module>
                """.trimIndent()
            )
        }

        val plugin = PackageManagerFactory.ALL.getValue("Ivy").create(
            PluginConfig(
                options = mapOf(
                    "settingsFileCandidates" to "ant-build/ivysettings.xml",
                    "propertiesFiles" to "ant-build/dependencies.properties",
                    "extraProperties" to "cache.dir=${repo.cacheDir.absolutePath},version.integration=7.1",
                    "excludeConfs" to "test,sources,javadoc,mustHaveDependencies"
                )
            )
        )

        val result = plugin.resolveDependencies(
            root, listOf(definitionFile), Excludes(), Includes(), AnalyzerConfiguration(), emptyMap()
        )
        val projectResult = result.projectResults.getValue(definitionFile).single()

        projectResult.issues shouldBe emptyList()
        projectResult.project.id shouldBe Identifier("Ivy", "de.gebit.trend", "trend-base", "7.1")
        projectResult.project.scopeNames shouldBe setOf("compile")
        result.sharedPackages.map { it.id } shouldBe listOf(Identifier("Maven", "de.third", "lib", "3.0"))
    }
})

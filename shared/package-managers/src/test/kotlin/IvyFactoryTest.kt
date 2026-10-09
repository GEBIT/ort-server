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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import java.io.File

private const val EMPTY_SETTINGS = "<ivysettings/>"

internal fun ivyTestConfig(
    settingsFileCandidates: List<String> = listOf("ant-build/ivysettings.xml", "ivysettings.xml"),
    propertiesFiles: List<String> = emptyList(),
    extraProperties: List<String> = emptyList(),
    confs: List<String> = listOf("compile", "runtime"),
    excludeConfs: List<String> = listOf("test", "sources", "javadoc"),
    offline: Boolean = false,
    skipDirs: List<String> = listOf("build", "bin", "dist")
) = IvyConfig(
    settingsFileCandidates, propertiesFiles, extraProperties, confs, excludeConfs, offline, skipDirs
)

class IvyFactoryTest : StringSpec({
    "A settings file next to the ivy.xml takes precedence over the configured candidates" {
        val root = tempdir()
        val project = root.resolve("project").apply { mkdirs() }
        val definitionFile = project.resolve("ivy.xml").apply { writeText("<ivy-module/>") }
        val local = project.resolve("ivysettings.xml").apply { writeText(EMPTY_SETTINGS) }
        root.resolve("ivysettings.xml").writeText(EMPTY_SETTINGS)

        IvyFactory(ivyTestConfig()).findSettingsFile(root, definitionFile) shouldBe local
    }

    "The first existing candidate relative to the analysis root wins" {
        val root = tempdir()
        val definitionFile = root.resolve("project/ivy.xml").apply {
            parentFile.mkdirs()
            writeText("<ivy-module/>")
        }
        root.resolve("ant-build").mkdirs()
        val second = root.resolve("ivysettings.xml").apply { writeText(EMPTY_SETTINGS) }

        val config = ivyTestConfig(settingsFileCandidates = listOf(" ", "missing.xml", "ivysettings.xml"))

        IvyFactory(config).findSettingsFile(root, definitionFile) shouldBe second
    }

    "No settings file is found if no candidate exists" {
        val root = tempdir()
        val definitionFile = root.resolve("ivy.xml").apply { writeText("<ivy-module/>") }

        IvyFactory(ivyTestConfig()).findSettingsFile(root, definitionFile) shouldBe null
    }

    "Extra properties override properties files and settings can reference them" {
        val root = tempdir()
        val definitionFile = root.resolve("ivy.xml").apply { writeText("<ivy-module/>") }
        root.resolve("ivysettings.xml").writeText(EMPTY_SETTINGS)
        root.resolve("vars.properties").writeText("a=fromFile\nb=fromFile\nc=\${a}-c\n")

        val config = ivyTestConfig(
            propertiesFiles = listOf("missing.properties", "vars.properties"),
            extraProperties = listOf("b=fromExtra", " ")
        )

        val settings = IvyFactory(config).createIvy(root, definitionFile).settings

        settings.getVariable("a") shouldBe "fromFile"
        settings.getVariable("b") shouldBe "fromExtra"
        settings.substitute("\${c}") shouldBe "fromFile-c"
    }

    "The variables ivy.settings.dir and basedir are set" {
        val root = tempdir()
        val ant = root.resolve("ant-build").apply { mkdirs() }
        ant.resolve("ivysettings.xml").writeText(EMPTY_SETTINGS)
        val definitionFile = root.resolve("core/ivy.xml").apply {
            parentFile.mkdirs()
            writeText("<ivy-module/>")
        }

        val settings = IvyFactory(ivyTestConfig()).createIvy(root, definitionFile).settings

        File(settings.getVariable("ivy.settings.dir")).canonicalPath shouldBe ant.canonicalPath
        File(settings.getVariable("basedir")).canonicalPath shouldBe definitionFile.parentFile.canonicalPath
    }

    "Environment variables are available as env.* variables" {
        val root = tempdir()
        val definitionFile = root.resolve("ivy.xml").apply { writeText("<ivy-module/>") }

        val settings = IvyFactory(ivyTestConfig()).createIvy(root, definitionFile).settings

        settings.substitute("\${env.PATH}") shouldBe System.getenv("PATH")
    }

    "Without a settings file the Ivy default settings are used" {
        val root = tempdir()
        val definitionFile = root.resolve("ivy.xml").apply { writeText("<ivy-module/>") }

        val settings = IvyFactory(ivyTestConfig()).createIvy(root, definitionFile).settings

        settings.defaultResolver shouldBe settings.getResolver("default")
    }

    "An invalid extra property entry is rejected" {
        val root = tempdir()
        val definitionFile = root.resolve("ivy.xml").apply { writeText("<ivy-module/>") }

        val exception = shouldThrow<IllegalArgumentException> {
            IvyFactory(ivyTestConfig(extraProperties = listOf("novalue"))).createIvy(root, definitionFile)
        }

        exception.message shouldContain "novalue"
    }
})

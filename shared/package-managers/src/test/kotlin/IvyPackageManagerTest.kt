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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
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
})

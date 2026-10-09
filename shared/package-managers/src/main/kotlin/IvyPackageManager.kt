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

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor

/** The project type reported for Ivy projects. */
internal const val IVY_PROJECT_TYPE = "Ivy"

/** The default, ordered list of relative paths to probe for an Ivy settings file. */
private const val DEFAULT_SETTINGS_FILE_CANDIDATES =
    "ant-build/ivysettings.xml,ivysettings.xml,settings/ivysettings.xml"

/**
 * The configuration options supported by [IvyPackageManager]. All list options are comma-separated in the plugin
 * configuration; blank entries are ignored.
 */
data class IvyConfig(
    /**
     * Ordered paths relative to the analysis root that are probed for an Ivy settings file. An `ivysettings.xml`
     * next to the analyzed `ivy.xml` is always checked first. The first existing regular file wins; if none exists,
     * Ivy's default settings are used.
     */
    @OrtPluginOption(defaultValue = DEFAULT_SETTINGS_FILE_CANDIDATES)
    val settingsFileCandidates: List<String>,

    /**
     * Ordered paths relative to the analysis root of `.properties` files that are loaded as Ivy variables when they
     * exist.
     */
    @OrtPluginOption(defaultValue = "")
    val propertiesFiles: List<String>,

    /** `key=value` entries that are set as Ivy variables and override values from [propertiesFiles]. */
    @OrtPluginOption(defaultValue = "")
    val extraProperties: List<String>,

    /** The Ivy configurations to resolve; `*` means all public configurations. */
    @OrtPluginOption(defaultValue = "compile,runtime")
    val confs: List<String>,

    /** Ivy configurations that are never resolved or reported, even if matched by [confs]. */
    @OrtPluginOption(defaultValue = "test,sources,javadoc")
    val excludeConfs: List<String>,

    /** Whether to resolve from caches and local repositories only. */
    @OrtPluginOption(defaultValue = "false")
    val offline: Boolean,

    /** Names of directories whose `ivy.xml` files are ignored. */
    @OrtPluginOption(defaultValue = "build,bin,dist")
    val skipDirs: List<String>
)

/**
 * A [PackageManager] for Ant/Ivy projects that fully resolves the dependencies declared in `ivy.xml` files using
 * Apache Ivy with configurable Ivy settings. Resolved modules are reported with Maven-style identifiers.
 */
@OrtPlugin(
    id = "Ivy",
    displayName = "Ant/Ivy",
    summary = "Resolves Ant/Ivy projects declared in ivy.xml files with configurable Ivy settings.",
    factory = PackageManagerFactory::class
)
class IvyPackageManager(
    override val descriptor: PluginDescriptor,
    private val config: IvyConfig
) : PackageManager(IVY_PROJECT_TYPE) {
    override val globsForDefinitionFiles = listOf("ivy.xml")

    override fun mapDefinitionFiles(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ): List<File> {
        val skipDirs = config.skipDirs.cleaned().toSet()

        return definitionFiles.filterNot { file ->
            file.relativeTo(analysisRoot).invariantSeparatorsPath.split('/').dropLast(1).any { it in skipDirs }
        }
    }

    // Replaced by the real implementation in a later task.
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> = emptyList()
}

/** Trim all entries and drop the blank ones. */
internal fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }

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
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.model.utils.isScopeIncluded
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.utils.common.collectMessages

import org.slf4j.LoggerFactory

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
    private val logger = LoggerFactory.getLogger(IvyPackageManager::class.java)
    private val resolver = IvyResolver(config)
    private val graphBuilder = DependencyGraphBuilder(IvyDependencyHandler())

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

    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> {
        val definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path
        val vcsProcessed = processProjectVcs(definitionFile.parentFile)

        val resolution = runCatching { resolver.resolve(analysisRoot, definitionFile) }.getOrElse { exception ->
            logger.error("Failed to resolve '$definitionFile'.", exception)

            val project = Project(
                id = Identifier(IVY_PROJECT_TYPE, "", definitionFile.parentFile.name, ""),
                definitionFilePath = definitionFilePath,
                authors = emptySet(),
                declaredLicenses = emptySet(),
                vcs = VcsInfo.EMPTY,
                vcsProcessed = vcsProcessed,
                homepageUrl = "",
                scopeNames = emptySet()
            )

            val issue = Issue(
                source = IVY_PROJECT_TYPE,
                message = "Failed to resolve '${definitionFile.relativeTo(analysisRoot).invariantSeparatorsPath}': " +
                    exception.collectMessages(),
                severity = Severity.ERROR
            )

            return listOf(ProjectAnalyzerResult(project, emptySet(), listOf(issue)))
        }

        val projectId = Identifier(IVY_PROJECT_TYPE, resolution.organisation, resolution.module, resolution.revision)

        val includedScopes = resolution.scopes.filterKeys { isScopeIncluded(it, excludes, includes) }
        includedScopes.forEach { (conf, dependencies) -> graphBuilder.addDependencies(projectId, conf, dependencies) }

        val project = Project(
            id = projectId,
            definitionFilePath = definitionFilePath,
            authors = emptySet(),
            declaredLicenses = resolution.licenses,
            vcs = VcsInfo.EMPTY,
            vcsProcessed = vcsProcessed,
            homepageUrl = resolution.homepageUrl,
            scopeNames = includedScopes.keys
        )

        return listOf(ProjectAnalyzerResult(project, emptySet(), resolution.issues))
    }

    override fun createPackageManagerResult(projectResults: Map<File, List<ProjectAnalyzerResult>>) =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())
}

/** Trim all entries and drop the blank ones. */
internal fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }

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

import java.io.File

import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.OrtPluginOption
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagemanagers.maven.Maven
import org.ossreviewtoolkit.plugins.packagemanagers.maven.MavenConfig
import org.ossreviewtoolkit.plugins.packagemanagers.maven.MavenFactory

/** The Maven project type / package manager id that this plugin's delegate reports itself as. */
private const val MAVEN_PROJECT_TYPE = "Maven"

/** The name of the Maven scope that contains test-only dependencies. */
private const val TEST_SCOPE_NAME = "test"

/** The default, ordered list of relative paths to probe for a repository-provided Maven settings file. */
private const val DEFAULT_SETTINGS_FILE_CANDIDATES = "development/settings.xml,settings.xml"

/**
 * The configuration options supported by [MavenNoTestDependencies].
 */
data class MavenNoTestDependenciesConfig(
    /**
     * An ordered list of paths relative to the analyzer's analysis root to probe for a repository-provided Maven
     * `settings.xml`. The first candidate that exists as a regular file is passed to the delegate Maven package
     * manager. Blank entries are ignored.
     */
    @OrtPluginOption(defaultValue = DEFAULT_SETTINGS_FILE_CANDIDATES)
    val settingsFileCandidates: List<String>
)

/**
 * A [PackageManager] for Maven projects that always excludes the [TEST_SCOPE_NAME] scope, regardless of whether a
 * scope exclude for it is declared in a repository's `.ort.yml` and regardless of the global
 * `analyzer.skipExcluded` setting.
 *
 * This does not reimplement any Maven parsing/dependency-resolution logic. Instead, it delegates almost entirely to
 * ORT's built-in `Maven` package manager (obtained via [MavenFactory]) and only enriches the [Excludes] that are
 * passed down to it with an additional, hard-coded [ScopeExclude] for `test`. This mirrors the composition-based
 * delegation pattern used by `NoDevDependenciesNpm` (and `GebitPackageCurationProvider`, for the
 * `PackageCurationProvider` SPI): ORT's built-in `Maven` class cannot be subclassed directly (it is not declared
 * `open`), so delegation via composition is used instead of inheritance.
 */
@OrtPlugin(
    id = "MavenNoTestDependencies",
    displayName = "Maven (excluding test dependencies)",
    summary = "Delegates to ORT's built-in Maven package manager, but always excludes the 'test' scope.",
    factory = PackageManagerFactory::class
)
class MavenNoTestDependencies(
    override val descriptor: PluginDescriptor,
    private val config: MavenNoTestDependenciesConfig
) : PackageManager(MAVEN_PROJECT_TYPE) {
    /** The real Maven package manager implementation that this plugin delegates to. */
    private var delegate = MavenFactory.create()

    override val globsForDefinitionFiles = delegate.globsForDefinitionFiles

    override fun mapDefinitionFiles(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) = delegate.mapDefinitionFiles(analysisRoot, definitionFiles, analyzerConfig)

    override fun beforeResolution(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) {
        val settingsFile = resolveSettingsFile(analysisRoot)
        delegate = Maven(MavenFactory.descriptor, MavenConfig(userSettingsFile = settingsFile?.path))
        delegate.beforeResolution(analysisRoot, definitionFiles, analyzerConfig)
    }

    /**
     * Return the first configured settings-file candidate relative to [analysisRoot] that exists as a regular file,
     * or `null` if none does (or none are configured), preserving the configured candidate order.
     */
    internal fun resolveSettingsFile(analysisRoot: File): File? =
        config.settingsFileCandidates
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(analysisRoot, it) }
            .firstOrNull { it.isFile }

    override fun afterResolution(analysisRoot: File, definitionFiles: List<File>) =
        delegate.afterResolution(analysisRoot, definitionFiles)

    /**
     * Override the plural entry point (called directly by ORT's `Analyzer`) instead of relying on the base class's
     * default implementation. The base class's default `resolveDependencies(files)` would loop over
     * [resolveDependencies] (singular, below) and then call *this* instance's `createPackageManagerResult()`, which
     * -- since it is not overridden here -- produces a [PackageManagerResult] with an empty dependency graph. The
     * actual dependency graph is only built and exposed by [delegate]'s own `createPackageManagerResult()`
     * (accessible only via its own `resolveDependencies(files)`), so the whole plural call must be delegated as-is.
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFiles: List<File>,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): PackageManagerResult {
        val excludesWithTestDependencies = excludes.copy(
            scopes = excludes.scopes + ScopeExclude(
                pattern = TEST_SCOPE_NAME,
                reason = ScopeExcludeReason.TEST_DEPENDENCY_OF,
                comment = "Always excluded by the '${descriptor.id}' package manager, independent of .ort.yml."
            )
        )

        return delegate.resolveDependencies(
            analysisRoot,
            definitionFiles,
            excludesWithTestDependencies,
            includes,
            analyzerConfig,
            labels
        )
    }

    /**
     * Not used: the plural [resolveDependencies] override above delegates the whole resolution (and the resulting
     * dependency graph) to [delegate] directly, so this per-file method is never invoked from within this class.
     * It is still implemented to satisfy the abstract contract of [PackageManager].
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> =
        delegate.resolveDependencies(analysisRoot, definitionFile, excludes, includes, analyzerConfig, labels)
}

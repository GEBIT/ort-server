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

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNode

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
import org.ossreviewtoolkit.plugins.packagemanagers.pub.PubFactory

/** The name of the Pub scope that contains dev-only dependencies (this is also where test packages live). */
private const val DEV_DEPENDENCIES_SCOPE_NAME = "dev_dependencies"

/**
 * The maximum number of YAML aliases that [anchorResolvingYaml] will resolve for a single `pubspec.yaml` file. This
 * bounds the same "billion laughs" resource-exhaustion attack that anchors/aliases were disabled for in the first
 * place (see the class documentation below), while still allowing well-behaved files that use them for legitimate
 * de-duplication to be parsed.
 */
private const val MAX_ALIAS_COUNT = 50u

/**
 * A permissive Kaml instance used only to resolve YAML anchors/aliases in `pubspec.yaml` files before handing them
 * to the delegate. It is deliberately not used for any real (de)serialization of Pub-specific data classes.
 */
private val anchorResolvingYaml = Yaml(
    configuration = YamlConfiguration(
        strictMode = false,
        anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = MAX_ALIAS_COUNT)
    )
)

/**
 * The configuration options for [PubNoDevDependencies], mirroring the options supported by ORT's built-in `Pub`
 * package manager since they are forwarded to the delegate unchanged.
 */
data class PubNoDevDependenciesConfig(
    /**
     * The version to use when bootstrapping Flutter. Forwarded to the delegate Pub package manager.
     */
    @OrtPluginOption(defaultValue = "3.19.3-stable")
    val flutterVersion: String,

    /**
     * The version of Gradle to use when analyzing Gradle projects. Forwarded to the delegate Pub package manager.
     */
    @OrtPluginOption(defaultValue = "7.3")
    val gradleVersion: String,

    /**
     * Only scan Pub dependencies and skip native ones for Android (Gradle) and iOS (CocoaPods). Forwarded to the
     * delegate Pub package manager.
     */
    @OrtPluginOption(defaultValue = "false")
    val pubDependenciesOnly: Boolean
)

/**
 * A [PackageManager] for Pub (Dart / Flutter) projects that always excludes the [DEV_DEPENDENCIES_SCOPE_NAME] scope
 * -- which is where test-only packages such as `test`, `flutter_test` and `mockito` are declared -- regardless of
 * whether a scope exclude for it is declared in a repository's `.ort.yml`.
 *
 * ORT's built-in `Pub` package manager parses `pubspec.yaml` (and `pubspec.lock`) using Kaml, via a hard-coded
 * `YamlConfiguration` defined by a private top-level property inside the `pub-package-manager` artifact. Since Kaml
 * 0.53.0 (the fix for CVE-2023-28118, a "billion laughs" denial-of-service via YAML anchors/aliases), that
 * configuration's default forbids anchors/aliases, so any `pubspec.yaml` using them fails with "Parsing anchors and
 * aliases is disabled." That configuration is neither exposed nor overridable from outside the artifact, so this
 * plugin cannot change it directly (unlike the hard-coded scope exclude below, which only requires enriching the
 * [Excludes] passed to the delegate).
 *
 * Instead, before delegating resolution, this plugin pre-processes each `pubspec.yaml` definition file: it parses
 * the raw file with [anchorResolvingYaml] (a Kaml instance configured to permit anchors/aliases, up to
 * [MAX_ALIAS_COUNT]) into a generic [YamlNode] tree and re-serializes that tree back to the same file. Since
 * aliases are resolved into independent values while building the tree, the re-serialized file no longer contains
 * any `&`/`*` syntax, so the delegate's strict parser can subsequently read it without failing. This relies on ORT
 * analyzing an ephemeral checkout, so rewriting the file in place is safe; no other part of the Pub package manager
 * needs to be reimplemented or forked.
 *
 * Like `MavenNoTestDependencies` and `NoDevDependenciesNpm`, this delegates almost entirely to ORT's built-in `Pub`
 * package manager (obtained via [PubFactory]) rather than reimplementing any dependency-resolution logic.
 */
@OrtPlugin(
    id = "PubNoDevDependencies",
    displayName = "Pub (excluding dev dependencies)",
    summary = "Delegates to ORT's built-in Pub package manager, but always excludes the 'dev_dependencies' scope " +
        "and pre-expands YAML anchors/aliases in pubspec.yaml files so they can be parsed.",
    factory = PackageManagerFactory::class
)
class PubNoDevDependencies(
    override val descriptor: PluginDescriptor,
    config: PubNoDevDependenciesConfig
) : PackageManager("Pub") {
    /** The real Pub package manager implementation that this plugin delegates to. */
    private val delegate = PubFactory.create(
        flutterVersion = config.flutterVersion,
        gradleVersion = config.gradleVersion,
        pubDependenciesOnly = config.pubDependenciesOnly
    )

    override val globsForDefinitionFiles = delegate.globsForDefinitionFiles

    /**
     * Expands any YAML anchors/aliases in [definitionFiles] (see the class documentation above) before delegating,
     * since the delegate itself already starts reading `pubspec.yaml` files from within its own `beforeResolution()`
     * (e.g. to detect Flutter projects), i.e. before its `resolveDependencies()` is ever called.
     */
    override fun beforeResolution(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) {
        definitionFiles.forEach(::expandYamlAnchorsAndAliases)
        delegate.beforeResolution(analysisRoot, definitionFiles, analyzerConfig)
    }

    override fun afterResolution(analysisRoot: File, definitionFiles: List<File>) =
        delegate.afterResolution(analysisRoot, definitionFiles)

    /**
     * Override the plural entry point (called directly by ORT's `Analyzer`) instead of relying on the base class's
     * default implementation. Unlike `Maven`/`Npm`, the built-in `Pub` package manager does not build a separate
     * internal dependency graph -- but it does override `createPackageManagerResult()` with project-package
     * filtering logic (removing packages that are also referenced as project dependencies) that would otherwise be
     * lost if this class's own (default) implementation were used instead. Delegating the whole plural call to
     * [delegate] preserves that filtering, mirroring the pattern used by `MavenNoTestDependencies` and
     * `NoDevDependenciesNpm`.
     */
    override fun resolveDependencies(
        analysisRoot: File,
        definitionFiles: List<File>,
        excludes: Excludes,
        includes: Includes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): PackageManagerResult {
        val excludesWithDevDependencies = excludes.copy(
            scopes = excludes.scopes + ScopeExclude(
                pattern = DEV_DEPENDENCIES_SCOPE_NAME,
                reason = ScopeExcludeReason.DEV_DEPENDENCY_OF,
                comment = "Always excluded by the '${descriptor.id}' package manager, independent of .ort.yml."
            )
        )

        return delegate.resolveDependencies(
            analysisRoot,
            definitionFiles,
            excludesWithDevDependencies,
            includes,
            analyzerConfig,
            labels
        )
    }

    /**
     * Not used: the plural [resolveDependencies] override above delegates the whole resolution to [delegate]
     * directly, so this per-file method is never invoked from within this class. It is still implemented to satisfy
     * the abstract contract of [PackageManager].
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

/**
 * Rewrites [definitionFile] in place with any YAML anchors and aliases resolved into independent values, so that
 * the delegate's strict Kaml-based parser (which forbids anchors/aliases, see [PubNoDevDependencies]'s
 * documentation) can read it without failing. Files that do not use `&`/`*` at all are left untouched.
 */
private fun expandYamlAnchorsAndAliases(definitionFile: File) {
    val original = definitionFile.readText()
    if ('&' !in original && '*' !in original) return

    val resolvedNode = anchorResolvingYaml.parseToYamlNode(original)
    definitionFile.writeText(anchorResolvingYaml.encodeToString(YamlNode.serializer(), resolvedNode))
}

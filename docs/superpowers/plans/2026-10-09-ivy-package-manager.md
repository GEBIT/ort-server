# Ant/Ivy Package Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ORT `PackageManager` plugin `Ivy` that fully resolves Ant/Ivy projects (including transitive dependencies) with customizable Ivy settings, usable for general projects and for the `trend/` layout.

**Architecture:** A new plugin class `IvyPackageManager` in `shared/package-managers` (KSP-registered via `@OrtPlugin`, like the other GEBIT plugins). Settings/variables handling (`IvyFactory`), the Ivy resolution into a plain model (`IvyResolver`), and the ORT mapping (`IvyDependencyHandler` + `DependencyGraphBuilder`) are separate units, so each can be tested without the others. Ivy dependencies are mapped to Maven-style identifiers (`Maven:<org>:<name>:<rev>`).

**Tech Stack:** Kotlin, ORT 92.6.0 analyzer API, Apache Ivy 2.5.2, Kotest (StringSpec), Gradle.

**Spec:** `docs/superpowers/specs/2026-10-09-ivy-package-manager-design.md`

---

## Conventions (apply to every task)

- Working directory: `/home/dschiemann/workspace/compliance-reports/ort-server` (git repo, branch `gebit`).
- All new Kotlin files live flat in `shared/package-managers/src/main/kotlin/` or `.../src/test/kotlin/` and use `package org.eclipse.apoapsis.ortserver.shared.packagemanagers`.
- Every new Kotlin file starts with this header (year 2026):

```kotlin
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
```

- Commit messages are prefixed `DINF-1282: ` and carry **no** `Co-authored-by` trailer.
- Test command pattern: `./gradlew :shared:package-managers:test --tests '<pattern>'` (add `--offline` if the network is unavailable).
- Import ordering follows the existing files: third-party imports (`io.kotest`, `io.mockk`, `org.apache`), then `java.*`, then `org.ossreviewtoolkit.*`, with blank lines between groups, as in `MavenNoTestDependencies.kt`.

## File Structure

| File | Responsibility |
|---|---|
| `gradle/libs.versions.toml` (modify) | Ivy version and library alias |
| `shared/package-managers/build.gradle.kts` (modify) | Ivy dependency |
| `src/main/kotlin/IvyPackageManager.kt` (create) | `IvyConfig`, the `@OrtPlugin` class, definition-file filtering, project/graph assembly |
| `src/main/kotlin/IvyFactory.kt` (create) | Settings file lookup, Ivy variables (properties files, extra properties, env), `Ivy` instance creation |
| `src/main/kotlin/IvyMessageLogger.kt` (create) | Routes Ivy log output to SLF4J |
| `src/main/kotlin/IvyResolver.kt` (create) | Runs the Ivy resolve and converts the report into `IvyResolution` / `IvyDependency` |
| `src/main/kotlin/IvyDependencyHandler.kt` (create) | `DependencyHandler<IvyDependency>` creating identifiers and ORT `Package`s |
| `src/test/kotlin/IvyTestFixtures.kt` (create) | Helpers writing a local file-based Ivy repository, settings and project descriptors |
| `src/test/kotlin/IvyPackageManagerTest.kt`, `IvyFactoryTest.kt`, `IvyResolverTest.kt`, `IvyTrendLayoutTest.kt` (create) | Tests |
| `README.md`, `build-analyzer-image.sh` (modify) | Documentation |
| `docs/superpowers/specs/2026-10-09-ivy-package-manager-design.md` (modify) | trend example uses `build.properties_ci` |
| `../ort-server-service/scripts/compose/config/ort-server.params.kts` (modify, separate repo) | Enable the plugin |

---

### Task 1: Dependency and plugin skeleton

**Files:**
- Modify: `gradle/libs.versions.toml`, `shared/package-managers/build.gradle.kts`
- Create: `shared/package-managers/src/main/kotlin/IvyPackageManager.kt`
- Test: `shared/package-managers/src/test/kotlin/IvyPackageManagerTest.kt`

- [ ] **Step 1: Add the Ivy library to the version catalog**

In `gradle/libs.versions.toml`, add to the `[versions]` section (keep alphabetical order, after `kaml`/before `kotest` entries as appropriate):

```toml
ivy = "2.5.2"
```

and to `[libraries]` (next to `kaml`):

```toml
ivy = { module = "org.apache.ivy:ivy", version.ref = "ivy" }
```

- [ ] **Step 2: Add the dependency to the module**

In `shared/package-managers/build.gradle.kts`, in `dependencies { ... }` add before `implementation(libs.kaml)`:

```kotlin
    implementation(libs.ivy)
```

- [ ] **Step 3: Write the failing test**

Create `shared/package-managers/src/test/kotlin/IvyPackageManagerTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :shared:package-managers:test --tests '*IvyPackageManagerTest*'`
Expected: FAIL — "Ivy" is not in `PackageManagerFactory.ALL` (or the source set does not compile).

- [ ] **Step 5: Implement the config and the skeleton plugin**

Create `shared/package-managers/src/main/kotlin/IvyPackageManager.kt`:

```kotlin
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
private const val DEFAULT_SETTINGS_FILE_CANDIDATES = "ant-build/ivysettings.xml,ivysettings.xml,settings/ivysettings.xml"

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
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :shared:package-managers:test --tests '*IvyPackageManagerTest*'`
Expected: PASS (3 tests). If KSP rejects `defaultValue = ""` for a `List<String>`, change those two annotations to `@OrtPluginOption(defaultValue = " ")`; the `cleaned()` helper already drops blank entries.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml shared/package-managers
git commit -m "DINF-1282: Add Ivy package manager plugin skeleton"
```

---

### Task 2: Ivy settings, variables and logging

**Files:**
- Create: `src/main/kotlin/IvyFactory.kt`, `src/main/kotlin/IvyMessageLogger.kt`
- Test: `src/test/kotlin/IvyFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/package-managers/src/test/kotlin/IvyFactoryTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:package-managers:test --tests '*IvyFactoryTest*'`
Expected: FAIL — compilation error, `IvyFactory` is unresolved.

- [ ] **Step 3: Implement the SLF4J message logger**

Create `shared/package-managers/src/main/kotlin/IvyMessageLogger.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import org.apache.ivy.util.AbstractMessageLogger
import org.apache.ivy.util.Message

import org.slf4j.LoggerFactory

/** A message logger that routes Ivy's output to SLF4J so that nothing is written to stdout. */
internal class IvyMessageLogger : AbstractMessageLogger() {
    private val logger = LoggerFactory.getLogger("Ivy")

    override fun log(msg: String?, level: Int) {
        when (level) {
            Message.MSG_ERR -> logger.error(msg)
            Message.MSG_WARN -> logger.warn(msg)
            Message.MSG_INFO -> logger.info(msg)
            else -> logger.debug(msg)
        }
    }

    override fun rawlog(msg: String?, level: Int) = log(msg, level)

    override fun doProgress() = Unit

    override fun doEndProgress(msg: String?) = Unit
}
```

- [ ] **Step 4: Implement the factory**

Create `shared/package-managers/src/main/kotlin/IvyFactory.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import java.io.File
import java.util.Properties

import org.apache.ivy.Ivy
import org.apache.ivy.core.settings.IvySettings

private const val SETTINGS_FILE_NAME = "ivysettings.xml"

/** Creates configured [Ivy] instances according to an [IvyConfig]. */
internal class IvyFactory(private val config: IvyConfig) {
    /**
     * Return the settings file to use for [definitionFile]: an `ivysettings.xml` next to it, otherwise the first
     * existing configured candidate relative to [analysisRoot], or `null` if there is none.
     */
    fun findSettingsFile(analysisRoot: File, definitionFile: File): File? {
        val nextToDefinition = definitionFile.parentFile.resolve(SETTINGS_FILE_NAME)
        val candidates = config.settingsFileCandidates.cleaned().map { analysisRoot.resolve(it) }

        return (listOf(nextToDefinition) + candidates).firstOrNull { it.isFile }
    }

    /**
     * Create an [Ivy] instance for [definitionFile]. Variables are set in this order, later ones overriding earlier
     * ones: `env.*`, `basedir`, the existing files of [IvyConfig.propertiesFiles], [IvyConfig.extraProperties].
     * Settings files may still define further variables, e.g. via `<properties override="false">`.
     */
    fun createIvy(analysisRoot: File, definitionFile: File): Ivy {
        val settings = IvySettings()

        settings.addAllVariables(System.getenv().mapKeys { "env.${it.key}" }, false)
        settings.setVariable("basedir", definitionFile.parentFile.absolutePath, true)

        config.propertiesFiles.cleaned().map { analysisRoot.resolve(it) }.filter { it.isFile }.forEach { file ->
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            properties.forEach { (key, value) -> settings.setVariable(key.toString(), value.toString(), true) }
        }

        parseExtraProperties().forEach { (key, value) -> settings.setVariable(key, value, true) }

        val settingsFile = findSettingsFile(analysisRoot, definitionFile)
        if (settingsFile != null) settings.load(settingsFile) else settings.loadDefault()

        return Ivy.newInstance(settings).also { it.loggerEngine.setDefaultLogger(IvyMessageLogger()) }
    }

    private fun parseExtraProperties(): List<Pair<String, String>> =
        config.extraProperties.cleaned().map { entry ->
            val index = entry.indexOf('=')
            require(index > 0) { "Invalid extraProperties entry '$entry', expected 'key=value'." }

            entry.substring(0, index).trim() to entry.substring(index + 1).trim()
        }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :shared:package-managers:test --tests '*IvyFactoryTest*'`
Expected: PASS (8 tests). If "Without a settings file…" fails because the default resolver is not named `default`, assert `settings.defaultResolver.name shouldBe "default"` instead (the default chain resolver is named `default`).

- [ ] **Step 6: Commit**

```bash
git add shared/package-managers
git commit -m "DINF-1282: Add Ivy settings and variable handling"
```

---

### Task 3: Test fixtures and the Ivy resolver

**Files:**
- Create: `src/test/kotlin/IvyTestFixtures.kt`, `src/main/kotlin/IvyResolver.kt`
- Test: `src/test/kotlin/IvyResolverTest.kt`

- [ ] **Step 1: Create the shared test fixtures**

Create `shared/package-managers/src/test/kotlin/IvyTestFixtures.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import java.io.File

private const val D = "$"

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
     * return it. [revision] may contain `${D}{...}` placeholders.
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
```

(`D` is only used in the KDoc above; if the compiler warns about it being unused, delete the constant and write the placeholder in the KDoc literally.)

- [ ] **Step 2: Write the failing resolver tests**

Create `shared/package-managers/src/test/kotlin/IvyResolverTest.kt`:

```kotlin
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
        resolution.scopes.keys shouldContainExactly listOf("compile", "runtime")
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

        IvyResolver(config).resolve(repo.root, project).scopes.keys shouldContainExactly listOf("compile", "runtime")
    }

    "Configured configurations that the project does not declare are skipped" {
        val repo = IvyTestRepo(tempdir())
        repo.writeSettings()
        val project = repo.writeProject("project", emptyList(), confs = listOf("compile"))

        val resolution = IvyResolver(ivyTestConfig(confs = listOf("compile", "runtime"))).resolve(repo.root, project)

        resolution.scopes.keys shouldContainExactly listOf("compile")
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

    "A project without dependencies resolves to empty scopes using Ivy default settings in offline mode" {
        val repo = IvyTestRepo(tempdir())
        val project = repo.writeProject("project", emptyList())

        // With Ivy's default settings and no dependencies, resolution succeeds and yields empty scopes.
        val resolution = IvyResolver(ivyTestConfig(offline = true)).resolve(repo.root, project)

        resolution.scopes.values.forEach { it.shouldBeEmpty() }
    }
})
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :shared:package-managers:test --tests '*IvyResolverTest*'`
Expected: FAIL — compilation error, `IvyResolver`, `IvyResolution` and `IvyDependency` are unresolved.

- [ ] **Step 4: Implement the resolver and its model**

Create `shared/package-managers/src/main/kotlin/IvyResolver.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import java.io.File

import org.apache.ivy.core.module.descriptor.DefaultArtifact
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.IvyNode
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.cache.ArtifactOrigin
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry

import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Severity

/** A resolved Ivy module together with the modules it depends on within one configuration. */
internal class IvyDependency(
    val organisation: String,
    val name: String,
    val revision: String,
    val description: String,
    val homepageUrl: String,
    val licenses: Set<String>,
    val binaryArtifactUrl: String,
    val sourceArtifactUrl: String
) {
    val dependencies = mutableListOf<IvyDependency>()
}

/** The outcome of resolving one `ivy.xml`: project metadata, the direct dependencies per scope and issues. */
internal class IvyResolution(
    val organisation: String,
    val module: String,
    val revision: String,
    val description: String,
    val homepageUrl: String,
    val licenses: Set<String>,
    /** The direct dependencies (with their transitive tree) per Ivy configuration. */
    val scopes: Map<String, List<IvyDependency>>,
    val issues: List<Issue>
)

/** Resolves `ivy.xml` files with Apache Ivy and converts the result into an [IvyResolution]. */
internal class IvyResolver(private val config: IvyConfig) {
    private val factory = IvyFactory(config)

    fun resolve(analysisRoot: File, definitionFile: File): IvyResolution {
        val ivy = factory.createIvy(analysisRoot, definitionFile)
        val url = definitionFile.toURI().toURL()

        val descriptor = ModuleDescriptorParserRegistry.getInstance().parseDescriptor(ivy.settings, url, false)
        val confs = selectConfigurations(descriptor)
        val issues = mutableListOf<Issue>()
        val scopes = linkedMapOf<String, List<IvyDependency>>()

        if (confs.isNotEmpty()) {
            val options = ResolveOptions()
                .setConfs(confs.toTypedArray())
                .setValidate(false)
                .setDownload(false)
                .setUseCacheOnly(config.offline)

            val report = ivy.resolve(url, options)
            val metadataCache = mutableMapOf<ModuleRevisionId, IvyDependency>()

            confs.forEach { conf -> scopes[conf] = buildScope(report, conf, metadataCache) }

            report.unresolvedDependencies.forEach { node ->
                issues += Issue(
                    source = IVY_PROJECT_TYPE,
                    message = "Unresolved dependency ${node.id}: ${node.problemMessage}",
                    severity = Severity.WARNING
                )
            }
        }

        val id = descriptor.moduleRevisionId

        return IvyResolution(
            organisation = id.organisation,
            module = id.name,
            revision = id.revision,
            description = descriptor.description.orEmpty(),
            homepageUrl = descriptor.homePage.orEmpty(),
            licenses = descriptor.licenses.mapNotNull { it.name }.toSet(),
            scopes = scopes,
            issues = issues
        )
    }

    /** Return the configurations to resolve: the wanted ones that the descriptor declares, minus the excluded. */
    private fun selectConfigurations(descriptor: ModuleDescriptor): List<String> {
        val available = descriptor.publicConfigurationsNames.toList()
        val configured = config.confs.cleaned()
        val wanted = if (configured == listOf("*")) available else configured
        val excluded = config.excludeConfs.cleaned().toSet()

        return wanted.filter { it in available && it !in excluded }
    }

    private fun buildScope(
        report: ResolveReport,
        conf: String,
        metadataCache: MutableMap<ModuleRevisionId, IvyDependency>
    ): List<IvyDependency> {
        val confReport = report.getConfigurationReport(conf)
        val rootId = confReport.moduleDescriptor.moduleRevisionId

        val nodes = confReport.moduleRevisionIds
            .mapNotNull { confReport.getDependency(it) }
            .filter { it.isLoaded && !it.isCompletelyEvicted && !it.isEvicted(conf) }
            .filter { it.resolvedId.moduleId != rootId.moduleId }

        val dependencies = nodes.associate { node ->
            node.resolvedId to createDependency(node, metadataCache)
        }

        val roots = mutableListOf<IvyDependency>()

        nodes.forEach { node ->
            val dependency = dependencies.getValue(node.resolvedId)

            node.getCallers(conf).forEach { caller ->
                val callerId = caller.moduleRevisionId

                if (callerId.moduleId == rootId.moduleId) {
                    if (dependency !in roots) roots += dependency
                } else {
                    val parent = dependencies[callerId]
                    if (parent != null && dependency !in parent.dependencies) parent.dependencies += dependency
                }
            }
        }

        return roots
    }

    /** Create a fresh [IvyDependency] for [node] (the tree edges are per configuration), reusing cached metadata. */
    private fun createDependency(
        node: IvyNode,
        metadataCache: MutableMap<ModuleRevisionId, IvyDependency>
    ): IvyDependency {
        val metadata = metadataCache.getOrPut(node.resolvedId) { readMetadata(node) }

        return IvyDependency(
            organisation = metadata.organisation,
            name = metadata.name,
            revision = metadata.revision,
            description = metadata.description,
            homepageUrl = metadata.homepageUrl,
            licenses = metadata.licenses,
            binaryArtifactUrl = metadata.binaryArtifactUrl,
            sourceArtifactUrl = metadata.sourceArtifactUrl
        )
    }

    private fun readMetadata(node: IvyNode): IvyDependency {
        val id = node.resolvedId
        val descriptor = node.descriptor

        return IvyDependency(
            organisation = id.organisation,
            name = id.name,
            revision = id.revision,
            description = descriptor?.description.orEmpty(),
            homepageUrl = descriptor?.homePage.orEmpty(),
            licenses = descriptor?.licenses?.mapNotNull { it.name }?.toSet().orEmpty(),
            binaryArtifactUrl = locate(node, DefaultArtifact(id, null, id.name, "jar", "jar")),
            sourceArtifactUrl = locate(
                node,
                DefaultArtifact(id, null, id.name, "source", "jar", mapOf("classifier" to "sources"))
            )
        )
    }

    /** Return the location of [artifact] as known to the resolver of [node], or an empty string if unavailable. */
    private fun locate(node: IvyNode, artifact: DefaultArtifact): String =
        runCatching {
            val origin = node.moduleRevision?.resolver?.locate(artifact)
            if (origin == null || ArtifactOrigin.isUnknown(origin)) "" else origin.location
        }.getOrDefault("")
}
```

Notes for the implementer: `node.getCallers(conf)` takes the root module configuration name; if `getCallers` returns no callers for direct dependencies in your Ivy version, fall back to `node.allCallers` filtered by `caller.callerConfigurations`. `report.unresolvedDependencies` is a `ResolveReport` method (see `javap -cp ivy-2.5.2.jar org.apache.ivy.core.report.ResolveReport`).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :shared:package-managers:test --tests '*IvyResolverTest*'`
Expected: PASS (7 tests). If a tree assertion fails, print `resolution.trees("compile")` and compare: the typical deviations are (a) `getCallers` needing the root conf name (already passed) and (b) `moduleRevisionIds` containing the root — the `moduleId != rootId.moduleId` filter handles that.

- [ ] **Step 6: Commit**

```bash
git add shared/package-managers
git commit -m "DINF-1282: Resolve Ivy projects into a dependency model"
```

---

### Task 4: ORT dependency handler

**Files:**
- Create: `src/main/kotlin/IvyDependencyHandler.kt`
- Test: add to `src/test/kotlin/IvyPackageManagerTest.kt` in Task 5 (the handler is covered through the plugin); a dedicated unit test is added here.
- Test: `src/test/kotlin/IvyDependencyHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `shared/package-managers/src/test/kotlin/IvyDependencyHandlerTest.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.PackageLinkage

class IvyDependencyHandlerTest : StringSpec({
    val child = IvyDependency("o", "child", "2", "", "", emptySet(), "", "")
    val dependency = IvyDependency(
        organisation = "de.gebit.trend",
        name = "trend-base",
        revision = "1.0",
        description = "Base module",
        homepageUrl = "https://example.org",
        licenses = setOf("Apache-2.0"),
        binaryArtifactUrl = "https://repo/trend-base-1.0.jar",
        sourceArtifactUrl = ""
    ).apply { dependencies += child }

    "Dependencies are identified with Maven coordinates" {
        IvyDependencyHandler().identifierFor(dependency) shouldBe
                Identifier("Maven", "de.gebit.trend", "trend-base", "1.0")
    }

    "The children of a dependency are its dependencies and the linkage is dynamic" {
        val handler = IvyDependencyHandler()

        handler.dependenciesFor(dependency) shouldBe listOf(child)
        handler.linkageFor(dependency) shouldBe PackageLinkage.DYNAMIC
    }

    "Packages carry the module metadata" {
        val issues = mutableListOf<Issue>()

        val pkg = IvyDependencyHandler().createPackage(dependency, issues)!!

        pkg.id shouldBe Identifier("Maven", "de.gebit.trend", "trend-base", "1.0")
        pkg.description shouldBe "Base module"
        pkg.homepageUrl shouldBe "https://example.org"
        pkg.declaredLicenses shouldBe setOf("Apache-2.0")
        pkg.binaryArtifact.url shouldBe "https://repo/trend-base-1.0.jar"
        pkg.sourceArtifact.url shouldBe ""
        issues shouldBe emptyList()
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:package-managers:test --tests '*IvyDependencyHandlerTest*'`
Expected: FAIL — `IvyDependencyHandler` is unresolved.

- [ ] **Step 3: Implement the handler**

Create `shared/package-managers/src/main/kotlin/IvyDependencyHandler.kt`:

```kotlin
package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.SourceCodeOrigin
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.utils.DependencyHandler

/** The package type used for Ivy modules; Ivy modules live in Maven-style repositories. */
private const val PACKAGE_TYPE = "Maven"

/** A [DependencyHandler] that maps [IvyDependency] trees to ORT identifiers and packages. */
internal class IvyDependencyHandler : DependencyHandler<IvyDependency> {
    override fun identifierFor(dependency: IvyDependency) =
        Identifier(PACKAGE_TYPE, dependency.organisation, dependency.name, dependency.revision)

    override fun dependenciesFor(dependency: IvyDependency): List<IvyDependency> = dependency.dependencies

    override fun linkageFor(dependency: IvyDependency) = PackageLinkage.DYNAMIC

    override fun createPackage(dependency: IvyDependency, issues: MutableCollection<Issue>): Package =
        Package(
            id = identifierFor(dependency),
            authors = emptySet(),
            declaredLicenses = dependency.licenses,
            description = dependency.description,
            homepageUrl = dependency.homepageUrl,
            binaryArtifact = dependency.binaryArtifactUrl.toRemoteArtifact(),
            sourceArtifact = dependency.sourceArtifactUrl.toRemoteArtifact(),
            vcs = VcsInfo.EMPTY,
            sourceCodeOrigins = listOf(SourceCodeOrigin.ARTIFACT)
        )

    private fun String.toRemoteArtifact() = if (isEmpty()) RemoteArtifact.EMPTY else RemoteArtifact(this, Hash.NONE)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:package-managers:test --tests '*IvyDependencyHandlerTest*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/package-managers
git commit -m "DINF-1282: Map Ivy dependencies to ORT packages"
```

---

### Task 5: Wire the plugin (projects, scopes, issues)

**Files:**
- Modify: `src/main/kotlin/IvyPackageManager.kt`
- Test: `src/test/kotlin/IvyPackageManagerTest.kt`

- [ ] **Step 1: Add failing plugin tests**

In `IvyPackageManagerTest.kt` add these imports (merge with existing ones):

```kotlin
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.Includes
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
```

and add these tests inside the `StringSpec` block:

```kotlin
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
        project.definitionFilePath shouldBe "project/ivy.xml"
        project.scopeNames shouldBe setOf("compile", "runtime")
        projectResult.issues shouldBe emptyList()

        result.sharedPackages.map { it.id } shouldBe listOf(
            Identifier("Maven", "o", "a", "1"), Identifier("Maven", "o", "c", "1")
        )

        val compile = result.dependencyGraph.createScopes(setOf("compile")).single()
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
        projectResult.project.definitionFilePath shouldBe "broken/ivy.xml"
        projectResult.issues.single().severity shouldBe Severity.ERROR
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :shared:package-managers:test --tests '*IvyPackageManagerTest*'`
Expected: FAIL — the new tests fail because `resolveDependencies` returns an empty list (e.g. `NoSuchElementException`/missing key).

- [ ] **Step 3: Implement the resolution wiring**

In `IvyPackageManager.kt` add imports:

```kotlin
import org.ossreviewtoolkit.analyzer.PackageManagerResult
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.model.utils.isScopeIncluded
```

Replace the stub `resolveDependencies` (including the "Replaced by…" comment) and add the members below inside the class (after `mapDefinitionFiles`):

```kotlin
    private val resolver = IvyResolver(config)
    private val graphBuilder = DependencyGraphBuilder(IvyDependencyHandler())

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
            logger.error(exception) { "Failed to resolve '$definitionFile'." }

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
                message = "Failed to resolve '$definitionFilePath': ${exception.collectMessages()}",
                severity = Severity.ERROR
            )

            return listOf(ProjectAnalyzerResult(project, emptySet(), listOf(issue)))
        }

        val projectId = Identifier(IVY_PROJECT_TYPE, resolution.organisation, resolution.module, resolution.revision)

        resolution.scopes
            .filterKeys { isScopeIncluded(it, excludes, includes) }
            .forEach { (conf, dependencies) -> graphBuilder.addDependencies(projectId, conf, dependencies) }

        val project = Project(
            id = projectId,
            definitionFilePath = definitionFilePath,
            authors = emptySet(),
            declaredLicenses = resolution.licenses,
            vcs = VcsInfo.EMPTY,
            vcsProcessed = vcsProcessed,
            homepageUrl = resolution.homepageUrl,
            scopeNames = graphBuilder.scopesFor(projectId)
        )

        return listOf(ProjectAnalyzerResult(project, emptySet(), resolution.issues))
    }

    override fun createPackageManagerResult(projectResults: Map<File, List<ProjectAnalyzerResult>>) =
        PackageManagerResult(projectResults, graphBuilder.build(), graphBuilder.packages())
```

Also add `import org.ossreviewtoolkit.utils.common.collectMessages` (the helper `Throwable.collectMessages()` used throughout ORT) and, for the logger, `import org.apache.logging.log4j.kotlin.logger`? — the base class `PackageManager` already exposes a `logger` (see `Gleam`/`Maven`, which call `logger.info { ... }`), so no extra import is needed. If `logger` is not accessible, remove the `logger.error(...)` line.

Note: `graphBuilder` is shared across all definition files processed by this instance, which is how ORT's dependency graph (`PackageManagerResult.dependencyGraph`) is meant to be built.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :shared:package-managers:test --tests '*IvyPackageManagerTest*'`
Expected: PASS (7 tests). Check specifically the "resolved into a project result" test: if `definitionFilePath` is `"ivy.xml"` or has a different prefix, that is because `VersionControlSystem.getPathInfo` returns a path relative to a VCS root; outside a git working tree it is the empty string relative path. In that case assert `endsWith("project/ivy.xml")` or initialise a git repository in the temp dir in the test (`org.ossreviewtoolkit.utils.common` offers no helper; run `git init` via `ProcessBuilder`).

- [ ] **Step 5: Commit**

```bash
git add shared/package-managers
git commit -m "DINF-1282: Resolve Ivy projects into ORT analyzer results"
```

---

### Task 6: trend-like layout test

**Files:**
- Test: `src/test/kotlin/IvyTrendLayoutTest.kt`

This verifies the requirement that the plugin is configurable for a repository like `trend/`: settings in a subdirectory, an `<include>`d settings file, variables from properties files, `${...}` placeholders in `ivy.xml`, and an `<extends>` parent module.

- [ ] **Step 1: Write the test**

Create `shared/package-managers/src/test/kotlin/IvyTrendLayoutTest.kt`:

```kotlin
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

        // The parent module that the project extends and that contributes a dependency.
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

        // ant-build/ivysettings.xml loads properties and includes the resolver definitions, as in trend.
        val ant = root.resolve("ant-build").apply { mkdirs() }
        ant.resolve("ivysettings.xml").writeText(
            """
            <ivysettings>
              <properties file="${D}{ivy.settings.dir}/build.properties" override="false"/>
              <include file="${D}{ivy.settings.dir}/ivysettings-resolvers.xml"/>
            </ivysettings>
            """.trimIndent()
        )
        ant.resolve("ivysettings-resolvers.xml").writeText(
            """
            <ivysettings>
              <settings defaultResolver="test"/>
              <caches defaultCacheDir="${D}{cache.dir}"/>
              <resolvers>
                <filesystem name="test">
                  <ivy pattern="${D}{repo.dir}/[organisation]/[module]/[revision]/ivy.xml"/>
                  <artifact pattern="${D}{repo.dir}/[organisation]/[module]/[revision]/[artifact]-[revision].[ext]"/>
                </filesystem>
              </resolvers>
            </ivysettings>
            """.trimIndent()
        )

        // Not named build.properties: as in trend, only a *_ci template exists in the checkout.
        ant.resolve("build.properties_ci").writeText(
            "repo.dir=${repo.repoDir.absolutePath}\nversion.integration=7.1\nparent.rev=1.0\n"
        )

        val definitionFile = root.resolve("core/base/ivy.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <ivy-module version="2.0">
                  <info organisation="de.gebit.trend" module="trend-base" revision="${D}{version.integration}">
                    <extends organisation="de.gebit" module="parent" revision="${D}{parent.rev}"/>
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
                    "propertiesFiles" to "ant-build/build.properties_ci",
                    "extraProperties" to "cache.dir=${repo.cacheDir.absolutePath}",
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
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :shared:package-managers:test --tests '*IvyTrendLayoutTest*'`
Expected: PASS. If it fails, diagnose (do not weaken the test): the likely causes and fixes are

- project revision stays `${version.integration}` → the descriptor was parsed before the variables were set; check `IvyFactory.createIvy` sets the variables before `settings.load`;
- `mustHaveDependencies` is not excluded → check `selectConfigurations` and that the parent's `mustHaveDependencies` conf is not in the child's declared confs (the child only declares `compile`, so it is not resolved at all; adjust the assertion only if you also add that conf to the child);
- `extends` fails to resolve the parent → the `<extends>` parent is looked up through the default resolver; confirm the repository layout matches the `ivy pattern`.

- [ ] **Step 3: Commit**

```bash
git add shared/package-managers
git commit -m "DINF-1282: Test Ivy resolution with a trend-like layout"
```

---

### Task 7: Documentation and deployment configuration

**Files:**
- Modify: `README.md`, `build-analyzer-image.sh`, `docs/superpowers/specs/2026-10-09-ivy-package-manager-design.md`
- Modify (separate repo): `../ort-server-service/scripts/compose/config/ort-server.params.kts`

- [ ] **Step 1: Update the spec's trend example**

In the spec, replace the example paragraph under "Options" with:

```markdown
Options are configured in `ort-server.params.kts` like those of the other GEBIT
plugins. For `trend/`: `settingsFileCandidates=ant-build/ivysettings.xml`,
`propertiesFiles=ant-build/build.properties_ci` (trend's CI creates the git-ignored
`build.properties` from this template; the settings file skips a missing
`build.properties`), `excludeConfs=test,sources,javadoc,mustHaveDependencies`.
```

- [ ] **Step 2: Add a README bullet**

In `README.md`, directly before the paragraph starting "The custom plugins are packaged in shared modules", add:

```markdown
- `Ivy` (`IvyPackageManager`) analyzes Ant/Ivy projects by fully resolving
  their `ivy.xml` files with Apache Ivy, including transitive dependencies.
  Modules are reported as Maven packages (`org` = namespace, `name` = name), so
  `GebitPackageCurationProvider` applies to them. Every resolved Ivy
  configuration becomes a scope. Options (comma-separated lists):
  `settingsFileCandidates` (default
  `ant-build/ivysettings.xml,ivysettings.xml,settings/ivysettings.xml`; an
  `ivysettings.xml` next to the `ivy.xml` always wins, Ivy's default settings
  are used if none exists), `propertiesFiles` and `extraProperties`
  (`key=value`) to define Ivy variables, `confs` (default `compile,runtime`,
  `*` for all public configurations), `excludeConfs` (default
  `test,sources,javadoc`), `offline` and `skipDirs` (default
  `build,bin,dist`). For `trend/` use
  `propertiesFiles=ant-build/build.properties_ci` and
  `excludeConfs=test,sources,javadoc,mustHaveDependencies`. Options are
  configured in `ort-server.params.kts`; see the [deployment example](https://gitlab.local.gebit.de/gebit-build/services/ort-server-service/-/blob/master/scripts/compose/config/ort-server.params.kts?ref_type=heads).
```

- [ ] **Step 3: Mention the plugin in the image script**

In `build-analyzer-image.sh`, change the comment lines

```
# with our custom plugins (GebitPackageCurationProvider, NpmNoDevDependencies,
# MavenNoTestDependencies):
```

to

```
# with our custom plugins (GebitPackageCurationProvider, NpmNoDevDependencies,
# MavenNoTestDependencies, Ivy):
```

- [ ] **Step 4: Commit the ort-server changes**

```bash
git add README.md build-analyzer-image.sh docs/superpowers/specs/2026-10-09-ivy-package-manager-design.md
git commit -m "DINF-1282: Document the Ivy package manager"
```

- [ ] **Step 5: Enable the plugin in the deployment (repo `ort-server-service`)**

In `/home/dschiemann/workspace/compliance-reports/ort-server-service/scripts/compose/config/ort-server.params.kts`:

1. In `enabledPackageManagers`, extend the added list:

```kotlin
                listOf("NpmNoDevDependencies", "MavenNoTestDependencies", "PubNoDevDependencies", "Ivy")
```

2. In the `packageManagerOptions` block, add `val ivyConfig = packageManagerOptions["Ivy"] ?: PackageManagerConfiguration()` next to the other two `val`s, and extend the returned map:

```kotlin
                "Ivy" to ivyConfig.copy(
                    options = ivyConfig.options.orEmpty() + mapOf(
                        "propertiesFiles" to "ant-build/build.properties_ci",
                        "excludeConfs" to "test,sources,javadoc,mustHaveDependencies"
                    )
                ),
```

3. Extend the explanatory comment above `val resolvedAnalyzerJobConfig` by one sentence: `// Ant/Ivy projects (e.g. trend) are analyzed by the Ivy package manager (IvyPackageManager in ort-server).`

Then commit in that repo:

```bash
cd /home/dschiemann/workspace/compliance-reports/ort-server-service
git add scripts/compose/config/ort-server.params.kts
git commit -m "DINF-1282: Enable the Ivy package manager"
```

---

### Task 8: Full verification

- [ ] **Step 1: Run the whole module's checks**

Run (in `ort-server`): `./gradlew :shared:package-managers:check`
Expected: BUILD SUCCESSFUL — all tests (existing Maven/Npm/Pub tests and the new Ivy tests) pass, and the module's static analysis (detekt/ktlint, if configured by the conventions plugin) is clean. Fix reported style findings (import order, line length 120) in the new files only.

- [ ] **Step 2: Verify the plugin is packaged into the Analyzer worker**

Run: `./gradlew :workers:analyzer:dependencies --configuration runtimeClasspath | grep -E "package-managers|ivy"`
Expected: output contains both `project :shared:package-managers` and `org.apache.ivy:ivy:2.5.2`. If `:shared:package-managers` is missing, it is not on the worker's classpath and needs to be added where the other GEBIT plugin modules are referenced (`grep -rn "shared:package-managers\|shared.packageManagers" workers/analyzer/build.gradle.kts`).

- [ ] **Step 3: Smoke test against the real `trend/` checkout (only if the Nexus is reachable)**

Create a throwaway test file `shared/package-managers/src/test/kotlin/IvyTrendSmokeTest.kt` (do **not** commit it) that runs the plugin on `/home/dschiemann/workspace/compliance-reports/trend` with options `propertiesFiles=ant-build/build.properties_ci`, `excludeConfs=test,sources,javadoc,mustHaveDependencies`, definition file `core/base/ivy.xml`, and prints `result.projectResults` issues and `result.sharedPackages.size`. Run it with `./gradlew :shared:package-managers:test --tests '*IvyTrendSmokeTest*' -i`.
Expected: the project id is `Ivy:de.gebit.trend:trend-base:<version>`, the package list is non-empty, and any issues are understandable (e.g. missing variables such as `PARAM_BRANCH_VERSION_DOTS` placeholders in `build.properties_ci` — supply them through `extraProperties` if they break resolution). Delete the file afterwards. This step is informational; record findings and adjust the deployment `propertiesFiles`/`extraProperties` accordingly.

- [ ] **Step 4: Confirm a clean tree**

Run: `git status --short` in both repos. Expected: no output.

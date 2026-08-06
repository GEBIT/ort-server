package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import kotlin.Boolean
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.api.PluginOption
import org.ossreviewtoolkit.plugins.api.PluginOptionType
import org.ossreviewtoolkit.plugins.api.parseBooleanOption

public class NoDevDependenciesNpmFactory : PackageManagerFactory {
  override val descriptor: PluginDescriptor by Companion::descriptor

  override fun create(config: PluginConfig): NoDevDependenciesNpm {
    val configObject = NoDevDependenciesNpmConfig(
        ignoreProjectNpmrcFiles = parseBooleanOption("ignoreProjectNpmrcFiles", config),
        legacyPeerDeps = parseBooleanOption("legacyPeerDeps", config),
    )

    return NoDevDependenciesNpm(descriptor, configObject)
  }

  public companion object {
    public val descriptor: PluginDescriptor = PluginDescriptor(
            id = "NpmNoDevDependencies",
            displayName = "NPM (excluding devDependencies)",
            summary = "Delegates to ORT's built-in NPM package manager, but always excludes the 'devDependencies' scope.",
            description = """
        |A [PackageManager] for NPM projects that always excludes the [DEV_DEPENDENCIES_SCOPE_NAME] scope, regardless of
        | whether a scope exclude for it is declared in a repository's `.ort.yml` and regardless of the global
        | `analyzer.skipExcluded` setting.
        |
        | This does not reimplement any NPM parsing logic. Instead, it delegates almost entirely to ORT's built-in `NPM`
        | package manager (obtained via [NpmFactory]) and only enriches the [Excludes] that are passed down to it with an
        | additional, hard-coded [ScopeExclude] for `devDependencies`. This mirrors the composition-based delegation pattern
        | used by `GebitPackageCurationProvider`, but for the [PackageManager] SPI: ORT's built-in `Npm` class cannot be
        | subclassed directly (it is not declared `open`), so delegation via composition is used instead of inheritance.
        """.trimMargin(),
            options = listOf(
                PluginOption(
                    name = "ignoreProjectNpmrcFiles",
                    description = "If true, ignore any project-specific `.npmrc` files. Forwarded to the delegate NPM package manager.",
                    type = PluginOptionType.BOOLEAN,
                    enumType = null,
                    enumEntries = null,
                    defaultValue = "false",
                    aliases = emptyList(),
                    isNullable = false,
                    isRequired = false
                ),
                PluginOption(
                    name = "legacyPeerDeps",
                    description = "If true, pass \"--legacy-peer-deps\" to NPM. Forwarded to the delegate NPM package manager.",
                    type = PluginOptionType.BOOLEAN,
                    enumType = null,
                    enumEntries = null,
                    defaultValue = "false",
                    aliases = emptyList(),
                    isNullable = false,
                    isRequired = false
                ),
            )
        )

    public fun create(ignoreProjectNpmrcFiles: Boolean = false, legacyPeerDeps: Boolean = false): NoDevDependenciesNpm {
      val configObject = NoDevDependenciesNpmConfig(
          ignoreProjectNpmrcFiles = ignoreProjectNpmrcFiles,
          legacyPeerDeps = legacyPeerDeps,
      )

      return NoDevDependenciesNpm(descriptor, configObject)
    }
  }
}

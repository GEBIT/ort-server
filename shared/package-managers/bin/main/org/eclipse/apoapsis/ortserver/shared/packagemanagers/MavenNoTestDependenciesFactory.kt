package org.eclipse.apoapsis.ortserver.shared.packagemanagers

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.plugins.api.PluginConfig
import org.ossreviewtoolkit.plugins.api.PluginDescriptor

public class MavenNoTestDependenciesFactory : PackageManagerFactory {
  override val descriptor: PluginDescriptor by Companion::descriptor

  override fun create(config: PluginConfig): MavenNoTestDependencies = MavenNoTestDependencies(descriptor)

  public companion object {
    public val descriptor: PluginDescriptor = PluginDescriptor(
            id = "MavenNoTestDependencies",
            displayName = "Maven (excluding test dependencies)",
            summary = "Delegates to ORT's built-in Maven package manager, but always excludes the 'test' scope.",
            description = """
        |A [PackageManager] for Maven projects that always excludes the [TEST_SCOPE_NAME] scope, regardless of whether a
        | scope exclude for it is declared in a repository's `.ort.yml` and regardless of the global
        | `analyzer.skipExcluded` setting.
        |
        | This does not reimplement any Maven parsing/dependency-resolution logic. Instead, it delegates almost entirely to
        | ORT's built-in `Maven` package manager (obtained via [MavenFactory]) and only enriches the [Excludes] that are
        | passed down to it with an additional, hard-coded [ScopeExclude] for `test`. This mirrors the composition-based
        | delegation pattern used by `NoDevDependenciesNpm` (and `GebitPackageCurationProvider`, for the
        | `PackageCurationProvider` SPI): ORT's built-in `Maven` class cannot be subclassed directly (it is not declared
        | `open`), so delegation via composition is used instead of inheritance.
        """.trimMargin(),
            options = listOf(
            )
        )

    public fun create(): MavenNoTestDependencies = MavenNoTestDependencies(descriptor)
  }
}

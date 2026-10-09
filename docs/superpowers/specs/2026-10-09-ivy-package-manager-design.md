# Ant/Ivy Package Manager Design

## Goal

Add an ORT package manager plugin `Ivy` that analyzes Ant/Ivy projects with full
dependency resolution and customizable Ivy settings. It must work for general
Ant/Ivy projects and be configurable for repositories such as `trend/`, which
keep a shared settings file in a subdirectory (`ant-build/ivysettings.xml`),
load properties files, include online/offline settings variants, use custom
configurations, and use `<extends>` parent modules.

ORT (and ORT Server) have no Ivy support, so this is a new `PackageManager`
implementation, not a wrapper around a built-in one.

## Architecture

Implement `IvyPackageManager` in `shared/package-managers`, annotated with
`@OrtPlugin(id = "Ivy", displayName = "Ant/Ivy", factory = PackageManagerFactory::class)`
so KSP generates the factory and service registration, like the existing GEBIT
plugins. Add `org.apache.ivy:ivy` to the version catalog and the module's
dependencies.

- Definition file name: `ivy.xml`. `build.xml` is not matched; Ant projects are
  detected through their Ivy descriptors.
- Files below `build/`, `bin/` and `dist/` directories are skipped by default.
- Every `ivy.xml` found is analyzed as its own project.

## Options (`IvyConfig`)

| Option | Default | Meaning |
|---|---|---|
| `settingsFileCandidates` | `ant-build/ivysettings.xml,ivysettings.xml,settings/ivysettings.xml` | Ordered paths relative to the analysis root; an `ivysettings.xml` next to the `ivy.xml` is checked first. First existing regular file wins. If none exists, Ivy's default settings (Maven Central) are used. Blank entries are ignored. |
| `propertiesFiles` | empty | Ordered candidate paths relative to the analysis root, loaded as Ivy variables when they exist (e.g. `ant-build/build.properties,ant-build/ivysettings-local.properties`). |
| `extraProperties` | empty | `key=value` list of Ivy variables; overrides values from `propertiesFiles`. |
| `confs` | `compile,runtime` | Ivy configurations to resolve; `*` means all public configurations. |
| `excludeConfs` | `test,sources,javadoc` | Configurations never resolved or reported, even if matched by `confs`. |
| `offline` | `false` | Resolve from caches and local repositories only. |
| `skipDirs` | `build,bin,dist` | Directory names whose `ivy.xml` files are ignored. |

The automatic variable `ivy.settings.dir` is set to the directory of the chosen
settings file, and `basedir` to the project directory. Placeholders in settings
and descriptors are resolved from these variables, `propertiesFiles`,
`extraProperties` and environment variables. Repository credentials use the
usual ORT mechanisms (`.netrc`, environment variables); no secrets are
exposed as options.

Options are configured in `ort-server.params.kts` like those of the other GEBIT
plugins. For `trend/`: `settingsFileCandidates=ant-build/ivysettings.xml`,
`propertiesFiles=ant-build/build.properties_ci,ant-build/dependencies.properties`
(trend's CI creates the git-ignored `build.properties` from the `_ci` template;
`dependencies.properties` defines e.g. `gebitparentivy.*` used by `<extends>`),
`extraProperties=version.integration=0.0.0-SNAPSHOT,version.integration.dep=+`
(trend's Ant build computes these two at build time, so they must be supplied;
the values are verified by the smoke test in Task 8),
`excludeConfs=test,sources,javadoc,mustHaveDependencies`.

## Data flow per `ivy.xml`

1. Build an `Ivy` instance from the chosen settings and variables. Ivy's own
   `<include>` and `<extends>` handling covers settings includes and parent
   modules.
2. Run a full `resolve` for the selected configurations, including transitive
   dependencies, conflict managers, revision ranges and `conf` mappings.
3. Create one ORT scope per resolved configuration. Its dependency tree is built
   from the `IvyNode` graph, honouring evictions by conflict managers.
4. Map each resolved module to a `Package` with identifier
   `Maven:<organisation>:<name>:<revision>`. Using Maven identifiers lets
   `GebitPackageCurationProvider` apply to `de.gebit.*` modules unchanged.
5. Fill package metadata (description, homepage, declared licenses, binary and
   source artifact URLs where the resolver can locate them) from the resolved
   `ivy.xml`. VCS information and artifact hashes are not populated in this
   version.
6. The project identifier is `Ivy:<organisation>:<module>:<revision>` taken from
   `<info>`; its definition file path is the `ivy.xml` path.

## Error handling

- Unresolved dependencies become ORT issues (severity warning) on the project;
  the resolved remainder is still returned.
- An unreadable or invalid settings file, or failing resolution as a whole,
  becomes an error issue on the project; analysis of other `ivy.xml` files
  continues.
- Ivy's output is routed to SLF4J and does not write to stdout.
- Ivy uses its default cache (`${user.home}/.ivy2/cache`) unless the settings
  file configures another location (e.g. trend's `ivy.cache.dir`). No extra
  volume is required; like Maven's `~/.m2`, the cache lives in the container
  filesystem and is reused across runs of the same worker container. Mutable
  revisions (`-SNAPSHOT`, `latest.integration`) are governed by the TTL
  settings in the Ivy settings file.

## Testing

Unit tests use a local file-based Ivy repository created in a temp directory;
no network access. They cover:

- transitive resolution and `conf` mapping into scopes
- `confs` / `excludeConfs` filtering, including `*`
- property substitution from `propertiesFiles`, `extraProperties`, and the
  precedence between them
- settings candidate fallback, including no settings file
- an unresolved dependency yielding an issue while others resolve
- skipped `build/` directories
- a trend-like layout: settings in a subdirectory, included variant file,
  properties files, and an `<extends>` parent module

## Documentation

Add a bullet for `Ivy` to the GEBIT Modifications section of `README.md`
describing the options and the trend example, and mention the plugin in
`build-analyzer-image.sh`'s comment listing the custom plugins.

## Assumptions and out of scope

- The Analyzer worker can reach the Nexus at `gebit-nexus.local.gebit.de`, as it
  already must for Maven.
- No Ant `build.xml` parsing; no execution of Ant targets.
- No publishing of artifacts, and no persistent or shared Ivy cache across
  worker containers (no `cacheDir` option, no volume mount).

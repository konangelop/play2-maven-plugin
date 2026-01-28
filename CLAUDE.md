# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build all modules
mvn install

# Build with full verification (tests, checksyle, etc.) - used by CI
mvn verify --update-snapshots --settings .travis.settings.xml -e

# Build a single module
mvn install -pl play2-maven-plugin

# Build a module with its dependencies
mvn install -pl play2-providers/play2-provider-play29 -am

# Run checkstyle
mvn checkstyle:check

# Skip tests during build
mvn install -DskipTests
```

**Requirements:** Maven 3.2.2+, Java 11+

## Architecture Overview

This is a Maven plugin that builds Play Framework applications. It supports Play 2.9.x and 3.0.x via a **provider pattern** that isolates version-specific code.

### Module Structure

- **play2-maven-plugin** - Maven Mojos (plugin goals): routes-compile, template-compile, enhance, run, dist, etc.
- **play2-provider-api** - SPI defining `Play2Provider` interface with methods for routes compiler, template compiler, enhancer, runner
- **play2-providers/play2-provider-play2X** - Version-specific implementations wrapping Play's internal APIs
- **play2-source-watcher-api** - File watching abstraction for dev mode hot reload
- **play2-source-watchers/** - Implementations: jdk7 (WatchService), jnotify (native), polling

### Key Design Patterns

**Provider Pattern:** Each Play version (2.9, 3.0) has a provider module that implements `Play2Provider`. The provider is selected at runtime based on the Play version string. This isolates API changes between Play versions. Play 2.9.x uses `com.typesafe.play` groupId with Scala 2.13; Play 3.0.x uses `org.playframework` groupId with Scala 3.

**Scala Compilation:** The `play2` packaging lifecycle uses `scala-maven-plugin` (net.alchim31.maven) for Scala compilation, configured in `META-INF/plexus/components.xml`.

**Mojo Hierarchy:** All Mojos extend `AbstractPlay2Mojo`. Source generators extend `AbstractPlay2SourceGeneratorMojo`. Enhancement Mojos extend `AbstractPlay2EnhanceMojo`.

### Hot Reload (Dev Mode)

The `run` goal implements Play's `BuildLink` interface:
1. `MavenPlay2Builder` tracks file changes via `FileWatchService`
2. On HTTP request, Play calls `Reloader.reload()`
3. If sources changed, Maven goals execute (`process-classes`)
4. New `ClassLoader` returned triggers app reload

Key classes:
- `Play2RunMojo` - Entry point, orchestrates dev server startup
- `MavenPlay2Builder` - Implements build/reload logic
- `Reloader` (in provider) - Implements Play's `BuildLink`

### SBT Analysis Integration

The plugin reads SBT/Zinc compiler analysis from `target/classes/cache/compile` to:
- Map source files to compiled classes (for enhancement)
- Track compilation timestamps (for incremental builds)
- Support source position mapping in error messages

## Adding Play Version Support

1. Create new module in `play2-providers/` (copy existing provider)
2. Implement `Play2Provider` interface with `@Component` annotation
3. Update `Play2Providers.getDefaultProviderId()` to recognize new version prefix
4. Add module to `play2-providers/pom.xml`
5. Update `Play2Providers.getDefaultProviderId()` to recognize new version prefix

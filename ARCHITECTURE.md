ok # Play2 Maven Plugin Architecture

This document provides a comprehensive overview of the Play2 Maven Plugin architecture, explaining how each component works and how it integrates with the Play Framework.

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [Overview](#overview)
3. [Project Structure](#project-structure)
4. [Provider Architecture](#provider-architecture)
5. [Maven Goals (Mojos)](#maven-goals-mojos)
6. [Routes Compilation](#routes-compilation)
7. [Template Compilation](#template-compilation)
8. [Bytecode Enhancement](#bytecode-enhancement)
9. [Development Mode (Hot Reload)](#development-mode-hot-reload)
10. [Distribution Packaging](#distribution-packaging)
11. [File Watch Service](#file-watch-service)
12. [SBT Analysis Integration](#sbt-analysis-integration)
13. [Configuration Reference](#configuration-reference)
14. [Play 2.9 and Play 3.0 Support Changes](#play-29-and-play-30-support-changes)

---

## Problem Statement

### Why This Plugin Exists

Play Framework applications cannot be built with a plain `mvn compile`. Play relies on several code generation and bytecode transformation steps that are normally handled by SBT (Scala Build Tool), Play's default build system. Without a specialized plugin, a Maven-based build would be missing critical parts of the compilation pipeline.

### How Play Builds Differ from Standard Java/Scala Projects

A Play application has source artifacts that are not standard Java or Scala files and require pre-processing before the Scala/Java compiler can run:

1. **Routes files** (`conf/routes`) — A DSL that maps HTTP endpoints to controller methods. These must be compiled into Scala source files (`Routes.scala`, reverse routing classes) before the main Scala compilation step. Without this, the application has no HTTP routing.

2. **Twirl templates** (`app/views/*.scala.html`) — Play's type-safe HTML templating language. Each template is compiled into a Scala object (e.g. `views.html.index`) that can be called from controllers. Without this, templates are just text files the compiler cannot see.

3. **Bytecode enhancement** — Play's Java API uses bytecode manipulation (via ASM) to auto-generate getters/setters for public fields in model classes and rewrite field access to go through those accessors. Without this post-compilation step, Java models don't behave as Play expects.

4. **Development mode (hot reload)** — Play's dev server calls back into the build tool on every HTTP request via the `BuildLink` interface. If source files have changed, the build tool recompiles and returns a new `ClassLoader`, and Play reloads the application without restarting the JVM. This requires tight integration between the build tool and Play's runtime.

5. **Distribution packaging** — Production deployment requires assembling all dependency JARs and generating platform-specific startup scripts that invoke `play.core.server.ProdServerStart`. This is not a standard Maven JAR or WAR.

### What This Plugin Does

The play2-maven-plugin integrates these steps into the Maven build lifecycle:

| Build Step | Maven Phase | What Happens |
|-----------|-------------|--------------|
| Routes compilation | `generate-sources` | `conf/routes` → Scala source files in `target/routes/main/` |
| Template compilation | `generate-sources` | `*.scala.html` → Scala source files in `target/twirl/main/` |
| Scala/Java compilation | `compile` | All sources (including generated) compiled by `scala-maven-plugin` |
| Bytecode enhancement | `process-classes` | ASM transforms applied to compiled `.class` files |
| Distribution packaging | `package` | JARs + startup scripts assembled into a deployable archive |

The plugin defines a custom `play2` packaging type that wires these steps into Maven's standard lifecycle, so `mvn package` produces a complete Play application.

### Without This Plugin

To build a Play application with Maven but without this plugin, you would need to:

1. **Manually invoke Play's compilers** — Call `play.routes.compiler.RoutesCompiler` and `play.twirl.compiler.TwirlCompiler` as pre-build steps (e.g. via `exec-maven-plugin` or a custom script), ensuring their output lands in the right directories and gets picked up by the Scala compiler.

2. **Configure source generation** — Use `build-helper-maven-plugin` to add the generated source directories (`target/routes/main/`, `target/twirl/main/`) to the compilation source roots.

3. **Run bytecode enhancement** — Write a post-compilation step that loads compiled classes, runs `play.core.enhancers.PropertiesEnhancer.generateAccessors()` and `rewriteAccess()` on them, and writes the modified class files back.

4. **Implement dev mode from scratch** — Write a program that implements Play's `BuildLink` interface, watches for file changes, triggers Maven rebuilds, creates new classloaders, and feeds them to `play.core.server.DevServerStart`. This is hundreds of lines of classloader management and build orchestration.

5. **Script distribution packaging** — Write shell/batch scripts to collect all dependency JARs, assemble the directory layout, and generate startup scripts.

In practice, this is infeasible to maintain by hand, which is why this plugin exists. It wraps all of Play's internal build APIs behind Maven goals that integrate naturally with the Maven lifecycle.

---

## Overview

The Play2 Maven Plugin enables building Play Framework applications using Apache Maven instead of SBT (Scala Build Tool). It provides Maven goals that mirror SBT's functionality:

- Compile routes files into Scala source code
- Compile Twirl templates into Scala source code
- Perform bytecode enhancement for Play's Java API
- Run applications in development mode with hot reload
- Package applications for production deployment

The plugin supports Play Framework versions 2.9.x and 3.0.x through a **provider pattern** that abstracts version-specific implementation details.

---

## Project Structure

The plugin is organized as a multi-module Maven project:

```
play2-maven-plugin/
├── play2-maven-plugin/           # Main plugin (Maven Mojos)
├── play2-provider-api/           # SPI for Play Framework providers
├── play2-providers/              # Version-specific implementations
│   ├── play2-provider-play29/    # Play 2.9.x support (Scala 2.13)
│   └── play2-provider-play30/    # Play 3.0.x support (Scala 3)
├── play2-source-position-mappers/ # Source position mapping for debugging
├── play2-source-watcher-api/      # File watching abstraction
└── play2-source-watchers/         # File watcher implementations
    ├── play2-source-watcher-jdk7/
    ├── play2-source-watcher-jnotify/
    └── play2-source-watcher-polling/
```

### Module Responsibilities

| Module | Purpose |
|--------|---------|
| `play2-maven-plugin` | Contains all Maven Mojo implementations that define the plugin's goals |
| `play2-provider-api` | Defines the Service Provider Interface (SPI) that all version-specific providers must implement |
| `play2-provider-play2X` | Version-specific implementations that wrap Play Framework's internal compilers and runners |
| `play2-source-position-mappers` | Maps compiled code positions back to original source files for error reporting |
| `play2-source-watcher-api` | Abstract interface for file change detection |
| `play2-source-watcher-*` | Concrete implementations using JDK7 WatchService, JNotify, or polling |

---

## Provider Architecture

The plugin uses a **provider pattern** to support multiple Play Framework versions while maintaining a single plugin codebase.

### Play2Provider Interface

**Location:** `play2-provider-api/src/main/java/com/google/code/play2/provider/api/Play2Provider.java`

```java
public interface Play2Provider {
    Play2LessCompiler getLessCompiler();
    Play2CoffeescriptCompiler getCoffeescriptCompiler();
    Play2JavascriptCompiler getJavascriptCompiler();
    Play2RoutesCompiler getRoutesCompiler();
    Play2TemplateCompiler getTemplatesCompiler();
    Play2JavaEnhancer getEnhancer();
    Play2EbeanEnhancer getEbeanEnhancer();
    Play2Runner getRunner();
}
```

Each method returns a compiler or runner that wraps the actual Play Framework implementation for that specific version.

### Provider Resolution

**Location:** `play2-provider-api/src/main/java/com/google/code/play2/provider/api/Play2Providers.java:26-69`

The provider is selected based on the Play version string:

```java
public static String getDefaultProviderId(String playVersion) {
    if (playVersion.startsWith("2.9.")) return "play29";
    // Play 3.x
    return "play30";
}
```

### Provider Implementation Example

**Location:** `play2-providers/play2-provider-play30/src/main/java/com/google/code/play2/provider/play30/Play30Provider.java`

Providers are registered using Plexus component annotations:

```java
@Component(role = Play2Provider.class, hint = "play30", description = "Play! 3.0.x")
public class Play30Provider implements Play2Provider {
    @Override
    public Play2RoutesCompiler getRoutesCompiler() {
        return new Play30RoutesCompiler();
    }

    @Override
    public Play2Runner getRunner() {
        return new Play30Runner();
    }
    // ... other implementations
}
```

### Why This Architecture?

Play Framework's internal APIs change between major versions. For example:
- Play 2.9.x uses `com.typesafe.play` groupId and Scala 2.13
- Play 3.0.x uses `org.playframework` groupId and Scala 3
- Artifact names differ (e.g. `play-build-link_2.13` vs `play-build-link` without Scala suffix)
- Dev server startup mechanisms changed between versions

The provider pattern isolates these changes, allowing the main plugin code to work uniformly across all supported versions.

---

## Maven Goals (Mojos)

All Mojos extend from `AbstractPlay2Mojo` which provides common functionality:

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/AbstractPlay2Mojo.java:65-150`

### Goal Hierarchy

```
AbstractPlay2Mojo
├── AbstractPlay2SourceGeneratorMojo
│   ├── Play2RoutesCompileMojo (routes-compile)
│   └── Play2TemplateCompileMojo (template-compile)
├── AbstractPlay2EnhanceMojo
│   ├── Play2EnhanceClassesMojo (enhance)
│   ├── Play2EbeanEnhanceMojo (ebean-enhance)
│   └── Play2RunMojo (run)
├── AbstractPlay2DistMojo
│   ├── Play2DistMojo (dist)
│   └── Play2DistExplodedMojo (dist-exploded)
└── AbstractPlay2AssetsCompileMojo
    ├── Play2LessCompileMojo (less-compile)
    ├── Play2CoffeeCompileMojo (coffee-compile)
    └── Play2ClosureCompileMojo (closure-compile)
```

### Available Goals

| Goal | Phase | Description |
|------|-------|-------------|
| `routes-compile` | `generate-sources` | Compiles `conf/routes` files into Scala source |
| `template-compile` | `generate-sources` | Compiles `*.scala.html` templates into Scala source |
| `enhance` | `process-classes` | Performs Play Java bytecode enhancement |
| `ebean-enhance` | `process-classes` | Performs Ebean ORM bytecode enhancement |
| `less-compile` | `generate-resources` | Compiles LESS stylesheets to CSS |
| `coffee-compile` | `generate-resources` | Compiles CoffeeScript to JavaScript |
| `closure-compile` | `generate-resources` | Minifies JavaScript using Google Closure |
| `run` | (interactive) | Runs the application in development mode |
| `start` | (interactive) | Starts the application in production mode |
| `stop` | (interactive) | Stops a running production server |
| `dist` | `package` | Creates a distribution archive (zip/tar) |
| `dist-exploded` | `package` | Creates an exploded distribution directory |

---

## Routes Compilation

### Overview

Play Framework uses a type-safe routing DSL. The routes file (`conf/routes`) is compiled into Scala source code that handles HTTP request routing.

### Play Framework Reference

In Play Framework, routes compilation is handled by `play.routes.compiler.RoutesCompiler`. The compiler:
1. Parses the routes DSL
2. Generates a `Routes.scala` file with pattern matching for URL paths
3. Generates reverse routing classes for type-safe URL generation

### Plugin Implementation

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/Play2RoutesCompileMojo.java:46-279`

```java
@Mojo(name = "routes-compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class Play2RoutesCompileMojo extends AbstractPlay2SourceGeneratorMojo {

    @Parameter(property = "play2.mainLang", required = true, defaultValue = "scala")
    private String mainLang;  // "java" or "scala"

    @Parameter(property = "play2.routesGenerator")
    private String routesGenerator;  // "static" or "injected"
}
```

#### Compilation Process

1. **Scan for routes files** (line 106-126):
   - Looks for `routes` and `*.routes` files in resource directories
   - Excludes the `public` target path (web assets)

2. **Get compiler from provider** (line 137-138):
   ```java
   Play2Provider play2Provider = getProvider();
   Play2RoutesCompiler compiler = play2Provider.getRoutesCompiler();
   ```

3. **Configure compiler** (lines 140-193):
   - Set output directory: `target/routes/main`
   - Set generator type (injected vs static)
   - Set additional imports based on language (Java/Scala)

4. **Compile each routes file** (lines 201-234):
   - Check if regeneration is needed (timestamp comparison)
   - Call `compiler.compile(routesFile)`
   - Handle compilation errors with source position mapping

#### Provider Implementation

**Location:** `play2-providers/play2-provider-play30/src/main/java/com/google/code/play2/provider/play30/Play28RoutesCompiler.java:37-132`

The provider wraps Play's actual routes compiler:

```java
public void compile(File routesFile) throws RoutesCompilationException {
    RoutesGenerator routesGenerator = InjectedRoutesGenerator$.MODULE$;
    RoutesCompiler.RoutesCompilerTask task = new RoutesCompiler.RoutesCompilerTask(
        routesFile,
        JavaConversions.asScalaBuffer(additionalImports),
        true,   // forwardsRouter
        true,   // reverseRouter
        false   // namespaceReverseRouter
    );
    Either<Seq<RoutesCompilationError>, Seq<File>> result =
        RoutesCompiler.compile(task, routesGenerator, outputDirectory);
    // Handle errors...
}
```

#### Generated Output

For a routes file like:
```
GET  /           controllers.HomeController.index()
GET  /assets/*   controllers.Assets.versioned(path="/public", file: Asset)
```

The compiler generates:
- `target/routes/main/router/Routes.scala` - Main routing logic
- `target/routes/main/router/RoutesPrefix.scala` - Route prefix handling
- `target/routes/main/controllers/ReverseRoutes.scala` - Reverse routing

---

## Template Compilation

### Overview

Play uses the **Twirl** template engine. Templates are `.scala.html` files that compile to Scala functions.

### Play Framework Reference

Twirl templates:
- Use `@` for Scala expressions
- Are strongly typed with declared parameters
- Compile to `views.html.*` Scala objects

### Plugin Implementation

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/Play2TemplateCompileMojo.java:45-208`

```java
@Mojo(name = "template-compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class Play2TemplateCompileMojo extends AbstractPlay2SourceGeneratorMojo {

    @Parameter(property = "play2.mainLang", required = true, defaultValue = "scala")
    private String mainLang;

    @Parameter(property = "play2.templateAdditionalImports")
    private String templateAdditionalImports;

    @Parameter(property = "play2.templateSourceDirectory")
    private File templateSourceDirectory;
}
```

#### Compilation Process

1. **Identify template source directories** (lines 188-206):
   - Use configured `templateSourceDirectory` if specified
   - Otherwise, scan all compile source roots
   - Exclude generated directories (inside `target/`)

2. **Scan for templates** (lines 127-132):
   ```java
   scanner.setIncludes(new String[] { "**/*.scala.*" });
   ```
   This catches `*.scala.html`, `*.scala.xml`, `*.scala.js`, etc.

3. **Compile each template** (lines 138-164):
   - Call provider's template compiler
   - Track whether file was actually recompiled (incremental build support)

#### Generated Output

For a template `app/views/index.scala.html`:
```html
@(message: String)
<h1>@message</h1>
```

Generates `target/src_managed/main/views/html/index.template.scala`:
```scala
package views.html
object index extends _root_.play.twirl.api.BaseScalaTemplate[...](...) {
  def apply(message: String): play.twirl.api.HtmlFormat.Appendable = {
    // Generated rendering code
  }
}
```

---

## Bytecode Enhancement

### What It Does

Play's bytecode enhancement transforms Java model classes after compilation. It performs two operations:

1. **Generate accessors** — For every public field, it creates getter/setter methods
2. **Rewrite access** — All direct field access (reads/writes) is rewritten to use those getters/setters

### Example

You write:
```java
public class User {
    public String name;
    public Integer age;
}
```

After enhancement, it behaves as if you wrote:
```java
public class User {
    public String name;
    public Integer age;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}
```

And anywhere in your code that does:
```java
user.name = "Alice";
String n = user.name;
```

Gets rewritten to:
```java
user.setName("Alice");
String n = user.getName();
```

### Why It Exists

1. **Cleaner model code** — You write `public String name;` instead of boilerplate getters/setters
2. **JavaBeans compatibility** — Frameworks like JPA, JSON serializers, and form binders expect getter/setter methods. Enhancement provides them automatically.
3. **Interception points** — Because all access goes through methods, Play (or libraries like Ebean) can intercept field access for lazy loading, dirty tracking, validation, etc.

### How It Works

Play uses the **ASM** bytecode manipulation library. The enhancer:

1. Loads the compiled `.class` file
2. Parses its bytecode using ASM's `ClassReader`
3. Adds synthetic getter/setter methods for public fields
4. Scans all methods for `GETFIELD`/`PUTFIELD` instructions that access enhanced fields
5. Rewrites those to `INVOKEVIRTUAL` calls to the generated accessors
6. Writes the modified bytecode back to the `.class` file

### Where It Fits in the Build

Enhancement happens in the `process-classes` phase, after compilation but before packaging:

```
Source files (.java)
       ↓
   [compile]  ←── scala-maven-plugin
       ↓
Class files (.class)
       ↓
 [process-classes]  ←── play2:enhance
       ↓
Enhanced class files (.class)
```

### When It's Needed

Enhancement is only needed if you:
- Use Play's Java API (not Scala)
- Have model classes with public fields that need JavaBeans-style access

If you write explicit getters/setters or use Scala (which has its own property syntax), you can skip enhancement.

### Plugin Implementation

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/Play2EnhanceClassesMojo.java:48-306`

```java
@Mojo(name = "enhance", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class Play2EnhanceClassesMojo extends AbstractPlay2EnhanceMojo {

    @Parameter(defaultValue = "${project.compileClasspathElements}",
               readonly = true, required = true)
    private List<String> classpathElements;
}
```

#### Enhancement Process

1. **Load SBT Analysis** (lines 64-81):
   ```java
   File analysisCacheFile = getAnalysisCacheFile();
   Analysis analysis = sbtAnalysisProcessor.readFromFile(analysisCacheFile);
   ```
   The analysis contains source-to-class mappings from the Scala/Java compiler.

2. **Find Java sources to enhance** (lines 99-128):
   - Scan for `**/*.java` files in unmanaged source roots
   - These are the sources that need accessor generation

3. **Generate accessors** (lines 216-261):
   ```java
   for (File sourceFile : javaSources) {
       if (analysis.getCompilationTime(sourceFile) > lastEnhanced) {
           Set<File> javaClasses = analysis.getProducts(sourceFile);
           for (File classFile : javaClasses) {
               if (enhancer.generateAccessors(classFile) ||
                   enhancer.rewriteAccess(classFile)) {
                   analysis.updateClassFileTimestamp(classFile);
               }
           }
       }
   }
   ```

4. **Rewrite access in all classes** (lines 263-304):
   - For Play 2.3.x and earlier: Also process template classes
   - For Play 2.4.x+: Process all Java and Scala sources

#### Version Differences

**Play 2.1.x - 2.3.x** (lines 131-152):
- `sourcesToRewriteAccess` includes generated Scala templates
- Templates that access Java model fields need rewriting

**Play 2.4.x+** (lines 154-178):
- All source roots are processed uniformly
- Includes both `**/*.java` and `**/*.scala` patterns

---

## Development Mode (Hot Reload)

### The Problem Hot Reload Solves

In traditional Java web development, the edit-compile-restart cycle is slow:
1. Edit source code
2. Run `mvn package`
3. Stop the running server
4. Start the server again
5. Wait for JVM warmup, dependency injection, database connections, etc.
6. Finally test your change

This can take 30+ seconds per change. Play's hot reload reduces this to ~1-3 seconds.

### How Hot Reload Works

Play's dev server doesn't restart the JVM. Instead, it creates a new `ClassLoader` with the recompiled classes and reloads just the application code, while keeping the JVM, framework classes, and database connections alive.

The key insight: **Play calls back into the build tool on every HTTP request**.

```
Browser                    Play Dev Server                 Build Tool (Maven)
   │                             │                                │
   │──── GET /users ────────────>│                                │
   │                             │                                │
   │                             │─── buildLink.reload() ────────>│
   │                             │                                │
   │                             │    (check for file changes)    │
   │                             │    (recompile if needed)       │
   │                             │    (return new ClassLoader)    │
   │                             │                                │
   │                             │<── ClassLoader or null ────────│
   │                             │                                │
   │                             │    (if new ClassLoader:        │
   │                             │     reload application)        │
   │                             │                                │
   │<─── HTTP Response ──────────│                                │
```

### The BuildLink Interface

`BuildLink` is Play's SPI (Service Provider Interface) that build tools must implement:

```java
// Simplified from play.core.BuildLink
public interface BuildLink {
    // Called on every HTTP request
    // Returns:
    //   - null: no changes, keep current app
    //   - ClassLoader: changes detected, reload with this classloader
    //   - Throwable: compilation failed, show error page
    Object reload();

    // Find source file for a class (for error pages)
    Object[] findSource(String className, Integer line);

    // Get project path
    File projectPath();

    // Run mode settings
    Map<String, String> settings();
}
```

### Plugin Implementation

The plugin implements this in two classes:

#### MavenPlay2Builder

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/MavenPlay2Builder.java`

This is the Maven-specific build logic:

```java
public class MavenPlay2Builder implements Play2Builder {

    // File watcher callback - called when source files change
    public void onChange(File changedFile) {
        synchronized (changedFilesLock) {
            changedFiles.put(path, timestamp);
        }
    }

    // Called by Reloader on each request
    public boolean build() throws Play2BuildFailure {
        // 1. Check if any files changed
        if (changedFiles.isEmpty() && !forceReload) {
            return false;  // No rebuild needed
        }

        // 2. Figure out which Maven modules need rebuilding
        List<MavenProject> projectsToBuild = calculateProjectsToBuild();

        // 3. Execute Maven build (routes-compile, template-compile, compile, enhance)
        MavenExecutionResult result = executeBuild(projectsToBuild, goals);

        // 4. Handle compilation errors
        if (result.hasExceptions()) {
            throw new Play2BuildFailure(extractError(result));
        }

        // 5. Return true = classloader reload needed
        return true;
    }
}
```

#### Reloader

**Location:** `play2-providers/play2-provider-play30/src/main/java/com/google/code/play2/provider/play30/run/Reloader.java`

This wraps `MavenPlay2Builder` and implements Play's `BuildLink`:

```java
public class Reloader implements BuildLink {
    private Play2Builder buildLink;
    private ClassLoader baseLoader;
    private URLClassLoader currentApplicationClassLoader;
    private int classLoaderVersion = 0;

    @Override
    public synchronized Object reload() {
        try {
            // Ask Maven to rebuild if needed
            boolean reloadRequired = buildLink.build();

            if (reloadRequired) {
                // Create new ClassLoader with fresh classes
                classLoaderVersion++;
                currentApplicationClassLoader = new DelegatedResourcesClassLoader(
                    "ReloadableClassLoader(v" + classLoaderVersion + ")",
                    toUrls(outputDirectories),  // target/classes
                    baseLoader
                );
                return currentApplicationClassLoader;
            }
            return null;  // No changes

        } catch (Play2BuildFailure e) {
            // Return compilation error - Play shows nice error page
            return new CompilationException(
                e.getMessage(),
                e.line(),
                e.position(),
                e.source()
            );
        }
    }
}
```

### ClassLoader Hierarchy

The classloader structure is critical for hot reload to work:

```
System ClassLoader
    │
    └── CommonClassLoader (H2 database, etc.)
            │
            └── DelegatingClassLoader (Play internals)
                    │
                    └── PlayDependencyClassLoader (your dependencies)
                            │
                            └── AssetsClassLoader (static files)
                                    │
                                    └── ReloadableClassLoader ← REPLACED ON RELOAD
                                            │
                                            (your application classes)
```

Only the bottom `ReloadableClassLoader` gets replaced. Everything above it stays loaded, which is why:
- Database connection pools survive reloads
- Framework classes don't need re-initialization
- Only your changed code reloads

### File Watching

#### Runtime Architecture

The `FileWatchService` is **not** part of your application. It runs only inside the Maven plugin process during `mvn play2:run`:

```
┌─────────────────────────────────────────────────────────────┐
│  Maven Process (mvn play2:run)                              │
│                                                             │
│  ┌─────────────────────┐    ┌─────────────────────────────┐ │
│  │  FileWatchService   │    │  MavenPlay2Builder          │ │
│  │  (jdk7/jnotify/     │───>│  (triggers rebuild when     │ │
│  │   polling)          │    │   files change)             │ │
│  └─────────────────────┘    └─────────────────────────────┘ │
│                                        │                    │
│                                        ▼                    │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  Play Dev Server (runs in same JVM but separate         ││
│  │  classloaders)                                          ││
│  │                                                         ││
│  │  - Your application code                                ││
│  │  - Play Framework                                       ││
│  │  - Your dependencies                                    ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

When you run `mvn play2:run`, the plugin stays active the entire time:

```
mvn play2:run
     │
     ▼
┌─────────────────────────────────────────────────────────────────┐
│  Plugin starts and stays running until you press Enter          │
│                                                                 │
│  1. FileWatchService (background thread)                        │
│     └── Constantly monitors: app/, conf/, public/, etc.         │
│     └── Calls onChange() when files modified                    │
│                                                                 │
│  2. Play Dev Server (runs your app)                             │
│     └── On each HTTP request, calls buildLink.reload()          │
│     └── If files changed → rebuild → new ClassLoader            │
│                                                                 │
│  3. Main thread                                                 │
│     └── Blocked on System.in.read() waiting for Enter key       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
     │
     ▼ (user presses Enter)

  Server shuts down, Maven exits
```

The key code in `Play2RunMojo`:

```java
// Start the server
Play2DevServer devModeServer = play2Runner.runInDevMode(configuration);

// This line blocks - plugin sits here until you press Enter
getLog().info("(Server started, use [Enter] to stop...)");
System.in.read();  // <-- Maven process waits here

// Clean up
devModeServer.close();
```

The watch service:
- Lives in the Maven plugin's classloader
- Watches your source directories (`app/`, `conf/`, etc.)
- Notifies `MavenPlay2Builder` when files change
- Is **not** packaged in your application JAR/distribution

When you run `mvn package` or `mvn play2:dist` for production, none of this hot-reload machinery is included.

#### Watch Service Selection

The watch service is selected based on OS and JDK, not Play version:

| OS | JDK | Watch Service |
|---|---|---|
| Windows | Java 7+ | **jdk7** (uses `java.nio.file.WatchService`) |
| Linux | Java 7+ | **jdk7** |
| Windows/Linux | Java 6 | jnotify (native library) |
| macOS | any | jnotify (native library) |
| Other | any | polling (fallback) |

Since Play 2.9+ requires Java 11+, on Windows or Linux you'll always get **jdk7**.

Note: The module is named `play2-source-watcher-jdk7` for historical reasons (the `WatchService` API was introduced in JDK 7). The module itself targets Java 11.

You can override the selection:
```xml
<configuration>
    <fileWatchService>polling</fileWatchService>
</configuration>
```

Or via command line:
```bash
mvn play2:run -Dplay2.fileWatchService=polling
```

#### Watch Service Implementations

- **jdk7** — Uses `java.nio.file.WatchService`. Default on Windows/Linux with Java 7+.
- **jnotify** — Native library using OS-specific file notification APIs. Default on macOS.
- **polling** — Simple periodic file scanning. Fallback for network filesystems or when native watching fails.

### Source Position Mapping

When compilation fails, Play shows a nice error page pointing to the exact line. But for generated code (routes, templates), the `.scala` file line numbers don't match your source.

The plugin maps positions back to original sources:

```java
public Object[] findSource(String className, Integer line) {
    File sourceFile = sourceMap.get(className);

    // If it's a generated template, map back to .scala.html
    if (isGeneratedTemplate(sourceFile)) {
        Play2TemplateGeneratedSource template =
            mapper.getGeneratedSource(sourceFile);
        return new Object[] {
            new File(template.getSourceFileName()),  // index.scala.html
            template.mapLine(line)                   // original line number
        };
    }
    return new Object[] { sourceFile, line };
}
```

### Dev Server Startup

When you run `mvn play2:run`, here's what happens:

1. **Collect classpath** — All dependency JARs (excluding reactor modules being reloaded)
2. **Set up classloader hierarchy** — As shown above
3. **Create Reloader** — With reference to `MavenPlay2Builder`
4. **Start Play via reflection**:
   ```java
   Class<?> mainClass = loader.loadClass("play.core.server.DevServerStart");
   Method mainDev = mainClass.getMethod("mainDevHttpMode",
       BuildLink.class, Integer.TYPE, String.class);
   ReloadableServer server = mainDev.invoke(null, reloader, port, address);
   ```
5. **Wait for Enter key** — `System.in.read()` blocks until user presses Enter
6. **Shutdown** — `server.close()`

### Why This Is Complex

Hot reload requires:
- **Tight integration with Play internals** — `BuildLink`, classloader contracts
- **Incremental build tracking** — Know exactly which files changed
- **Multi-module awareness** — Rebuild dependent modules in correct order
- **Source mapping** — Trace generated code back to original sources
- **Error handling** — Convert Maven errors to Play's error page format

This is why the plugin exists — doing this manually would require reimplementing hundreds of lines of carefully coordinated code.

---

## Distribution Packaging

### Overview

The `dist` goal packages the application for production deployment, similar to `sbt dist`.

### Plugin Implementation

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/AbstractPlay2DistMojo.java:45-364`

#### Archive Structure

The distribution creates:
```
${project.build.finalName}/
├── lib/                    # All dependency JARs
│   ├── ${artifactId}-${version}.jar
│   └── ${groupId}.${artifactId}-${version}.jar  # Dependencies
├── start                   # Linux/Unix startup script
├── start.bat               # Windows startup script
└── README                  # Optional readme file
```

#### Start Script Generation

**Linux script** (lines 255-301):
```bash
#!/usr/bin/env sh
scriptdir=`dirname $0`
classpath=$scriptdir/lib/*
exec java $* -cp "$classpath" ${prodSettings} ${serverJvmArgs} \
    play.core.server.ProdServerStart $scriptdir
```

**Windows script** (lines 303-349):
```batch
set scriptdir=%~dp0
set classpath=%scriptdir%/lib/*
java %* -cp "%classpath%" ${prodSettings} ${serverJvmArgs} ^
    play.core.server.ProdServerStart %scriptdir%
```

#### Dependency Filtering

The plugin supports including/excluding dependencies:

```xml
<configuration>
    <distDependencyIncludes>com.example:*</distDependencyIncludes>
    <distDependencyExcludes>*:test-utils</distDependencyExcludes>
</configuration>
```

**Implementation** (lines 186-219):
```java
AndArtifactFilter dependencyFilter = new AndArtifactFilter();
if (distDependencyIncludes != null) {
    List<String> incl = Arrays.asList(distDependencyIncludes.split(","));
    dependencyFilter.add(new PatternIncludesArtifactFilter(incl, true));
}
if (distDependencyExcludes != null) {
    List<String> excl = Arrays.asList(distDependencyExcludes.split(","));
    dependencyFilter.add(new PatternExcludesArtifactFilter(excl, true));
}
```

---

## File Watch Service

### Overview

The file watch service detects source file changes during development mode.

### API

**Location:** `play2-source-watcher-api/src/main/java/com/google/code/play2/watcher/api/FileWatchService.java:28-50`

```java
public interface FileWatchService {
    void initialize(FileWatchLogger log) throws FileWatchException;

    FileWatcher watch(List<File> filesToWatch, FileWatchCallback onChange)
        throws FileWatchException;
}
```

### Implementations

| Implementation | Description | Best For |
|---------------|-------------|----------|
| `jdk7` | Uses `java.nio.file.WatchService` | JDK 7+, most platforms |
| `jnotify` | Uses native JNotify library | Linux with inotify |
| `polling` | Periodic file timestamp checking | Fallback, network filesystems |

### Selection Logic

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/Play2RunMojo.java:577-647`

```java
private FileWatchService getWatchService() throws MojoExecutionException {
    if (!watchServices.isEmpty()) {
        // Use explicitly declared watch service
        return getDeclaredWatchService();
    } else {
        // Auto-detect based on OS and JDK
        return getWellKnownWatchService();
    }
}
```

The default is selected via `FileWatchServices.getDefaultWatchServiceId()` based on:
- Operating system
- JDK version
- Available native libraries

---

## SBT Analysis Integration

### Overview

The plugin integrates with SBT's incremental compilation analysis to:
- Track source-to-class mappings
- Determine what needs recompilation
- Support bytecode enhancement

### Analysis Cache

The SBT compiler (Zinc) writes analysis data to:
```
target/classes/cache/compile
```

This file contains:
- Source file → class file mappings
- Compilation timestamps
- Dependency relationships

### Usage in Enhancement

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/Play2EnhanceClassesMojo.java:80-81`

```java
AnalysisProcessor sbtAnalysisProcessor = getSbtAnalysisProcessor();
Analysis analysis = sbtAnalysisProcessor.readFromFile(analysisCacheFile);
```

### Usage in Hot Reload

**Location:** `play2-maven-plugin/src/main/java/com/google/code/play2/plugin/MavenPlay2Builder.java:366-410`

```java
for (MavenProject p : projectsToBuild) {
    File analysisCacheFile = defaultAnalysisCacheFile(p);
    Analysis analysis = sbtAnalysisProcessor.readFromFile(analysisCacheFile);

    for (File sourceFile : analysis.getSourceFiles()) {
        Set<File> sourceFileProducts = analysis.getProducts(sourceFile);
        for (File product : sourceFileProducts) {
            // Build source map for error reporting
            sourceMap.put(className, sourceFile);
        }
    }
}
```

---

## Configuration Reference

### Common Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.version` | (detected) | Play Framework version |
| `play2.mainLang` | `scala` | Primary language: `java` or `scala` |
| `project.build.sourceEncoding` | `UTF-8` | Source file encoding |

### Routes Compilation

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.routesGenerator` | `injected` | Router type: `static` or `injected` |
| `play2.routesAdditionalImports` | | Additional imports (space-separated) |

### Template Compilation

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.templateAdditionalImports` | | Additional imports |
| `play2.templateSourceDirectory` | (all source roots) | Template source location |

### Development Mode

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.httpPort` | `9000` | HTTP server port |
| `play2.httpsPort` | | HTTPS server port |
| `play2.httpAddress` | `0.0.0.0` | Server bind address |
| `play2.runGoals` | `process-classes` | Maven goals on rebuild |
| `play2.runAdditionalGoals` | | Additional goals for main module |
| `play2.devSettings` | | Dev-mode settings (space-separated key=value) |
| `play2.serverJvmArgs` | | JVM arguments (only -D flags used) |
| `play2.fileWatchService` | (auto) | Watch service: `jdk7`, `jnotify`, `polling` |
| `play2.mainModule` | | Module selector for multi-module projects |

### Distribution

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.distOutputDirectory` | `${project.build.directory}` | Output directory |
| `play2.distArchiveName` | `${project.build.finalName}` | Archive name |
| `play2.distTopLevelDirectory` | `${project.build.finalName}` | Archive root directory |
| `play2.distClassifierIncludes` | | Additional project artifacts |
| `play2.distDependencyIncludes` | | Dependency include patterns |
| `play2.distDependencyExcludes` | | Dependency exclude patterns |
| `play2.prodSettings` | | Production settings (space-separated key=value) |
| `play2.serverJvmArgs` | | JVM arguments for startup scripts |

### Asset Compilation

| Parameter | Default | Description |
|-----------|---------|-------------|
| `play2.assetsDirectory` | `${basedir}/app/assets` | Assets source directory |
| `play2.assetsOutputDirectory` | `${outputDirectory}/public` | Assets output directory |
| `play2.assetsPrefix` | `public/` | URL prefix for assets |

---

## Play Framework Integration Points

This section maps plugin functionality to Play Framework internal classes.

### Routes Compiler

- **Play Class:** `play.routes.compiler.RoutesCompiler`
- **Generator:** `play.routes.compiler.InjectedRoutesGenerator$`
- **Task:** `play.routes.compiler.RoutesCompiler.RoutesCompilerTask`

### Template Compiler

- **Play Class:** `play.twirl.compiler.TwirlCompiler`
- **Output:** `play.twirl.api.BaseScalaTemplate`

### Dev Server

- **Entry Point:** `play.core.server.DevServerStart`
- **Methods:** `mainDevHttpMode()`, `mainDevOnlyHttpsMode()`
- **Interface:** `play.core.BuildLink`
- **Server:** `play.core.server.ReloadableServer`

### Production Server

- **Entry Point:** `play.core.server.ProdServerStart`
- **Usage:** Called from generated start scripts

### Bytecode Enhancement

- **Play Class:** `play.core.enhancers.PropertiesEnhancer`
- **Operations:** `generateAccessors()`, `rewriteAccess()`

### Classloader Hierarchy (Dev Mode)

```
ExtClassLoader (JDK)
    └── CommonClassLoader (H2, shared libs)
        └── DelegatingClassLoader (Play internal)
            └── PlayDependencyClassLoader (dependencies)
                └── AssetsClassLoader (static assets)
                    └── ReloadableClassLoader (application classes)
```

This hierarchy enables:
- Shared database connections across reloads
- Play framework classes to remain loaded
- Only application classes to be reloaded

---

## Play 2.9 and Play 3.0 Support Changes

This section documents the changes made to support Play 2.9.x and Play 3.0.x, which have significant differences from earlier Play versions.

### Key Differences Between Play 2.9 and Play 3.0

| Aspect | Play 2.9.x | Play 3.0.x |
|--------|-----------|-----------|
| GroupId | `com.typesafe.play` | `org.playframework` |
| Scala Version | Scala 2.13 | Scala 3 |
| build-link artifact | `play-build-link` (no Scala suffix) | `play-build-link` (no Scala suffix) |
| routes-compiler artifact | `play-routes-compiler_2.13` | `play-routes-compiler_3` |
| twirl-compiler artifact | `twirl-compiler_2.13` | `twirl-compiler_3` |
| Minimum Java | Java 11 | Java 11 |

### Step 1: Provider Selection Logic

**File:** `play2-provider-api/src/main/java/com/google/code/play2/provider/api/Play2Providers.java`

The provider selection was updated to recognize Play 2.9.x:

```java
public static String getDefaultProviderId(String playVersion) {
    if (playVersion.startsWith("2.9.")) {
        return "play29";
    }
    // Play 3.x
    return "play30";
}
```

### Step 2: Add play29 Provider to Build

**File:** `play2-providers/pom.xml`

Added the play29 module to the build:

```xml
<modules>
    <module>play2-provider-play29</module>
    <module>play2-provider-play30</module>
</modules>
```

### Step 3: Fix play29 Provider Dependencies

**File:** `play2-providers/play2-provider-play29/pom.xml`

The artifact name for `play-build-link` changed — it no longer has a Scala suffix in Play 2.9+:

```xml
<!-- Wrong (old naming) -->
<artifactId>play-build-link_2.13</artifactId>

<!-- Correct -->
<artifactId>play-build-link</artifactId>
```

### Step 4: Update ServerStartException

**File:** `play2-providers/play2-provider-play29/src/main/java/.../run/ServerStartException.java`

The `Play2ServerStartException` class was removed from the API. Changed to extend `Throwable` directly:

```java
// Old (broken)
public class ServerStartException extends Play2ServerStartException { ... }

// New (working)
public class ServerStartException extends Throwable {
    private Throwable underlying;

    public ServerStartException(Throwable underlying) {
        this.underlying = underlying;
    }

    public String getMessage() {
        return underlying.getMessage();
    }
}
```

### Step 5: Fix Play29Runner

**File:** `play2-providers/play2-provider-play29/src/main/java/.../Play29Runner.java`

The `Build.sharedClasses` changed from a method to a field:

```java
// Old (broken)
new DelegatingClassLoader(commonClassLoader, Build.sharedClasses(), buildLoader, ...);

// New (working)
new DelegatingClassLoader(commonClassLoader, Build.sharedClasses, buildLoader, ...);
```

### Step 6: Update Play29TemplateCompiler

**File:** `play2-providers/play2-provider-play29/src/main/java/.../Play29TemplateCompiler.java`

The `Play2TemplateCompiler` interface changed:

1. **Return types changed from `String[]` to `List<String>`:**
   ```java
   // Old
   public String[] getDefaultJavaImports() { ... }
   public String[] getDefaultScalaImports() { ... }

   // New
   public List<String> getDefaultJavaImports() { ... }
   public List<String> getDefaultScalaImports() { ... }
   ```

2. **New method required:**
   ```java
   @Override
   public String getCustomOutputDirectoryName() {
       return "twirl";
   }
   ```

3. **Removed method:**
   ```java
   // This method no longer exists in the interface
   public String[] getTemplateFileExtensions() { ... }
   ```

### Step 7: Replace Scala Compiler Plugin

**File:** `play2-maven-plugin/src/main/resources/META-INF/plexus/components.xml`

The `sbt-compiler-maven-plugin` is outdated and doesn't support modern Scala/Java versions. Replaced with `scala-maven-plugin`:

```xml
<!-- Old (broken with Scala 2.13.17+) -->
<compile>
  com.google.code.sbt-compiler-maven-plugin:sbt-compiler-maven-plugin:compile
</compile>
<test-compile>
  com.google.code.sbt-compiler-maven-plugin:sbt-compiler-maven-plugin:testCompile
</test-compile>

<!-- New (working) -->
<compile>
  net.alchim31.maven:scala-maven-plugin:compile
</compile>
<test-compile>
  net.alchim31.maven:scala-maven-plugin:testCompile
</test-compile>
```

### Step 8: Update Java Target Version

**File:** `play2-source-watchers/play2-source-watcher-jdk7/pom.xml`

The module targeted Java 1.7 which is no longer supported by modern compilers. Updated to Java 11:

```xml
<!-- Old -->
<source>1.7</source>
<target>1.7</target>

<!-- New -->
<source>11</source>
<target>11</target>
```

### User Configuration for Play 2.9

Users of Play 2.9.x with modern Scala (2.13.17+) need to configure the `scala-maven-plugin` in their application's `pom.xml`:

```xml
<plugin>
    <groupId>net.alchim31.maven</groupId>
    <artifactId>scala-maven-plugin</artifactId>
    <version>4.9.2</version>
    <configuration>
        <!-- Scala 2.13 doesn't support -release 25, use max supported -->
        <args>
            <arg>-release:21</arg>
        </args>
    </configuration>
</plugin>
```

This is needed because:
- The scala-maven-plugin inherits the `-release` flag from `maven-compiler-plugin`
- Scala 2.13 only supports up to `-release:21`
- If your project targets Java 25, Scala compilation will fail without this override

---

## Summary

The Play2 Maven Plugin provides comprehensive Maven support for Play Framework through:

1. **Provider Pattern:** Isolates version-specific code, supporting Play 2.9.x and 3.0.x
2. **Maven Lifecycle Integration:** Goals map to standard Maven phases
3. **Development Mode:** Full hot-reload support via `BuildLink` interface
4. **Incremental Compilation:** Leverages SBT analysis for efficient rebuilds
5. **Production Packaging:** Creates deployable distributions with startup scripts

The architecture mirrors SBT's functionality while integrating naturally with Maven's build lifecycle and project model.

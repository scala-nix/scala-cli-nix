# Contributing to scala-cli-nix

## Architecture

scala-cli-nix has two phases: **lock** (runs outside Nix, has network) and **build** (runs inside Nix sandbox, no network). This split exists because Nix builds are sandboxed — JVM dependencies must be pre-fetched with known hashes.

### Phase 1: Locking (`scala-cli-nix lock`)

Implemented in `cli/scala-cli-nix.scala` (Scala 3). The CLI is itself built by `buildScalaCliApp` (self-hosting). At runtime, the CLI needs a `scala-cli` binary to shell out to: it reads the absolute path from `SCALA_CLI_NIX_SCALA_CLI` if set, otherwise falls back to whatever `scala-cli` is on PATH. The Nix-built `scala-cli-nix-cli` derivation sets that env var to a bundled fork release (see "Overlay" below).

1. `scala-cli --power list-targets <inputs>` returns the build matrix as JSON — one `{platform, scalaVersion}` entry per declared target. The CLI handles `//> using platform[s]` / `//> using scala` directives, so we don't parse them ourselves.
2. For each target in the matrix, `scala-cli export --json --platform <p> --scala-version <v> <inputs>` discovers the Scala version, source files, and direct+transitive dependencies.
3. `coursierapi.Fetch` (from `io.get-coursier:interface`) downloads all transitive JARs for both the compiler and library dependencies. No `cs` CLI needed — resolution happens in-process.
4. For each JAR, the adjacent POM is found in the Coursier cache. Parent POMs are discovered by walking the `<parent>` chain, parsed with `scala-xml`. SHA-256 hashes are computed in-process via `java.security.MessageDigest` — no `nix hash file` needed. Hashes are cached at `$XDG_CACHE_HOME/scala-cli-nix/hashes.json` (default `~/.cache/scala-cli-nix/hashes.json`), keyed by absolute path with size+mtime as the freshness stamp; the cache is loaded once per `lock`/`init` invocation and persisted on the way out (including on failure). Threaded through the `sha256Base64` call sites as a `using HashCache` parameter — see `class HashCache` and `withHashCache` in `cli/scala-cli-nix.scala`.
5. The output is `scala.lock.json` with one section per target.

#### Custom Maven repositories (e.g. Artifactory)

`//> using repository <URL>` in the project sources is picked up by scala-cli and reported in the `resolvers[]` field of `export --json`. The lock command reads that list, filters to `http(s)://` entries (Ivy and local resolvers are skipped — Coursier's interface only accepts Maven repos, and local caches aren't reproducible inputs), and passes each URL as a `MavenRepository.of(...)` to every Coursier `Fetch` for that target. The non-default repos are threaded through `fetchArtifacts` via a `using Repos` context.

Credentials for private repos are loaded *automatically* by Coursier from `~/.config/coursier/credentials.properties` (or the path pointed to by `COURSIER_CREDENTIALS` / `COURSIER_CONFIG_DIR`). The CLI does not need to call `addFileCredentials` explicitly — `Fetch.create()` already picks up those defaults via the underlying `coursier.cache.CacheDefaults`.

At Nix build time, `pkgs.fetchurl` fetches each lockfile URL directly. If the Artifactory repo requires auth, configure Nix's standard `netrc-file` (or place credentials in `~/.netrc`) — the FOD download then succeeds against the same URL the lock recorded. No changes to `lib.nix` are needed.

For `lock-coords` (no scala-cli source files, so no `resolvers[]`), pass extra repos via repeatable `--repository <URL>` flags. If you want *only* those repos and no Maven Central (e.g. everything is mirrored in a private Artifactory instance), also pass `--no-default-repositories`; this replaces the Coursier default repository list (ivy2Local + Maven Central) entirely with the `--repository` URLs.

#### Locking external sources (`lock --src <dir>`)

When the project sources don't live next to the lockfile (e.g. you want to package an upstream repo as an external build, sourcing it via `fetchFromGitHub`), pass `--src <dir>` to `lock`. The CLI then:

1. Runs `scala-cli list-targets`/`export` with `<dir>` as the input.
2. Strips `<dir>` (the *source root*) from absolute source paths before writing them to the lockfile, so the recorded paths are relative to `<dir>` rather than to cwd.

The lockfile is still written to the *current* working directory — that's where the corresponding `derivation.nix` lives. Paired with `src = fetchFromGitHub { ... };` in the derivation, the lockfile's relative `sources` paths match against the fetched tarball at Nix build time. See `external/scala-monitor` for a worked example.

When positional file paths are passed alongside `--src`, each *relative* path is resolved under `<dir>` rather than under cwd (`scn lock --src /nix/store/xxx-src foo.scala bar.scala` locks just those two files inside the external source root). Absolute positional paths pass through unchanged.

For end-to-end scaffolding from a GitHub URL — prefetch + lock + derivation generation in one command — see `init <github-url>` below; that path uses `--src` semantics internally.

#### Lockfile format (`scala.lock.json`, version 9)

The lockfile uses a multi-target format. Each target (a platform/Scala version combination) has its own section under the `targets` key.

**Cross-platform example** (1 Scala version, 2 platforms):
```json
{
  "version": 9,
  "sources": ["hello.scala"],
  "resourceDirs": ["resources"],
  "targets": {
    "jvm": {
      "scalaVersion": "3.6.4",
      "platform": "JVM",
      "exportHash": "<sha1>",
      "compiler": [{"url": "...", "sha256": "base64..."}],
      "libraryDependencies": [{"url": "...", "sha256": "base64..."}]
    },
    "native": {
      "scalaVersion": "3.6.4",
      "platform": "Native",
      "exportHash": "<sha1>",
      "compiler": [...],
      "libraryDependencies": [...],
      "native": {
        "scalaNativeVersion": "0.5.10",
        "toolingDependencies": [...]
      }
    }
  }
}
```

**Single-target example** (standard JVM project):
```json
{
  "version": 9,
  "sources": ["foo.scala"],
  "resourceDirs": [],
  "targets": {
    "jvm": {
      "scalaVersion": "3.8.3",
      "platform": "JVM",
      "exportHash": "<sha1>",
      "compiler": [...],
      "libraryDependencies": [...]
    }
  }
}
```

**Test scope** is captured per target as an optional `test` block, omitted when there are no test sources or test-only deps:
```json
"jvm": {
  ...,
  "test": {
    "sources": ["foo.test.scala"],
    "resourceDirs": ["test-resources"],
    "libraryDependencies": [...]
  }
}
```
`test.libraryDependencies` is the full transitive resolution of the test scope's effective direct deps (user-declared + scala-cli-injected). Both scope-level direct-dep sets come straight from the fork's `export --json`: each scope reports `dependencies` (user-declared) and `injectedDependencies` (scala-cli-supplied — JVM test-runner, Native test-interface, JS test-bridge, plus the platform runtime libs that also appear in the main scope). The lock command feeds `dependencies ++ injectedDependencies` to Coursier as the scope's direct-dep set, so the lockfile's winners match what `scala-cli test --offline` will look up at build time.

##### Two scopes, two resolutions

scala-cli builds the main and test scopes as separate `Build`s, each with its own Coursier resolution. For test frameworks that pin a Scala Native version higher than scala-cli's bundled one (e.g. munit 1.3.0 → `javalib_native:0.5.11` while scala-cli ships `0.5.10`), Coursier picks the higher version in the test scope's resolution and the lower one in the main scope's resolution (where no test framework is present). Both winners end up in the offline cache at build time. The lockfile mirrors this: `targets.<key>.libraryDependencies` holds the main-scope winners, `targets.<key>.test.libraryDependencies` holds the test-scope winners, and `lib.nix`'s test build symlinks both into the offline Coursier cache so scala-cli's two-scope resolution can find each scope's expected version.

##### Target key naming

Target keys use only the dimensions that vary:

| Platforms | Scala versions | Key format | Example |
|---|---|---|---|
| 1 | 1 | `<platform>` | `jvm` |
| many | 1 | `<platform>` | `jvm`, `native` |
| 1 | many | `<version>` | `3.6.4`, `3.5.0` |
| many | many | `<platform>-<version>` | `jvm-3.6.4`, `native-3.5.0` |

##### Field reference

- `version` — schema version (9). Checked at build time; mismatch causes a build error directing the user to re-lock.
- `sources` — top-level, shared across targets. Lists source files relative to the project root.
- `resourceDirs` — top-level, shared across targets. Resource directories declared via `//> using resourceDir` (or equivalent CLI options), as paths relative to the project root. The build pulls each directory into the filtered source tree as a whole subtree so `scala-cli package` embeds its contents into the JAR (JVM) or the linked binary (Native).
- `targets.<key>.exportHash` — SHA-1 hex digest of the canonicalized (sorted keys, no spaces) `scala-cli export --json` output for this target, followed by a newline. Used for per-target staleness detection.
- `targets.<key>.platform` — `"JVM"`, `"Native"`, or `"JS"`. Determines the build strategy.
- `targets.<key>.compiler` / `libraryDependencies` — JARs, their POMs, and parent POMs. Parent POMs are needed because Coursier resolves version inheritance from parent POMs during offline resolution. The lock command walks each resolved POM's declared deps and materializes their POMs too (so scala-cli's offline resolver can see the full dep graph), but it does **not** materialize a JAR for any `(group, artifact)` already covered by the main resolution winner — an extra JAR at a different version on the runtime classpath would shadow the winner's classes (NoSuchMethodError at runtime).
- `targets.<key>.native` — present for Scala Native targets. Carries `scalaNativeVersion` and `toolingDependencies` (the linker, scala-native-cli, etc.). Tooling is resolved on its own because it targets Scala 2.12 while everything else uses the project's Scala version. Scala Native's own runtime deps (`nscplugin`, `scala3lib_native`, `javalib_native`) come from the export's per-scope `injectedDependencies` and are folded into `libraryDependencies` and `test.libraryDependencies` via the combined resolution — see the next bullet.
- **Combined resolution for Native targets.** scala-cli, at build time, resolves user deps and its injected native runtime deps together in one Coursier pass. The lock command must do the same: if user libs are resolved separately, a different version can "win" for a transitively-shared module than the version scala-cli picks at build time, and `scala-cli package --offline` fails to find the JAR for its winner. Concretely: `portable-scala-reflect_native0.5_2.13:1.1.3` (transitively pulled by e.g. `scala-java-time`) declares `scalalib_native0.5_2.13:2.13.8+0.5.2` directly. In a user-libs-only resolution, an older transitive `scala3lib_native` pulls a higher `scalalib_native0.5_2.13` and wins. With scala-cli's latest `scala3lib_native` added as a direct dep, it excludes `scalalib_native0.5_2.13` from its chain — the only remaining path is portable-scala-reflect's pinned 2.13.8+0.5.2, which becomes the winner. The lock-time CLI mirrors this by handing the fork's `injectedDependencies` (which include `scala3lib_native`/`javalib_native`/`nscplugin`) to Coursier alongside the user's deps. Regression test: `examples/scala3-native-evicted-2.13`.
- `targets.<key>.js` — present for Scala.js targets. Carries `scalaJsVersion`, `scalaJsCliVersion`, and `toolingDependencies` (the Scala.js linker: `org.virtuslab.scala-cli:scalajscli_2.13` and its transitive closure, including the closure-compiler). The linker is resolved with `scalajs-linker_2.13` forced to `scalaJsVersion` (mirrors scala-cli's own `ScalaJsLinker.linkerCommand`), so the locked linker links the same way a real `scala-cli package --js` run would. Unlike Native, the JS standard library (`scala3-library_sjs1_3:<scalaVersion>` for Scala 3) is **not** reported in the export's `injectedDependencies`, so the lock command adds it as a direct lib dep itself; without it `scala-cli package --offline` can't find the JS stdlib in the cache. The Scala.js runtime (`scalajs-library_2.13`) does come through `injectedDependencies`.
- `targets.<key>.test` — optional. Present when the project has test sources or test-only deps. Contains `sources` (test source files), `resourceDirs` (test-scope resource directories, merged with the top-level `resourceDirs` when running tests), and `libraryDependencies` (full main+test classpath; reuses the target's `compiler`, `native`, and `js` blocks).

#### Coursier cache path structure

Coursier stores artifacts at `<cache-root>/<protocol>/<host>/<path>`, which mirrors the URL structure. For example:

```
https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

becomes:

```
<cache>/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.jar
```

Coursier also percent-encodes special characters like `+` → `%2B` in directory and file names. The lock script accounts for this: it percent-encodes `+` when looking up POMs in the cache, and `mkCacheDir` does the same when creating the symlink layout.

The lock script reconstructs URLs by stripping the cache prefix and re-adding `://`.

### Phase 2: Building (`lib.nix`)

`lib.nix` exposes these functions:

- `buildScalaCliApp { pname, version, src, lockFile, mainClass?, target?, nativeImage?, packaging? }` — builds a single target, returning one derivation. If the lockfile has multiple targets, `target` must be specified (e.g. `target = "jvm"`). If the lockfile has exactly one target, it is selected automatically. `nativeImage = true` switches a JVM target to GraalVM native-image output (see below); it is only valid for JVM targets. `packaging` selects the JVM output shape — `"app"` (default, classpath wrapper + thin user JAR) or `"assembly"` (fat JAR + wrapper); see below. `packaging = "assembly"` is JVM-only and mutually exclusive with `nativeImage = true`.
- `buildScalaCliApps { pname, version, src, lockFile, mainClass?, nativeImage?, packaging? }` — builds all targets, returning an attrset of derivations keyed by target name (dots normalized to underscores, e.g. `{ jvm = <drv>; native = <drv>; }`). `nativeImage` and `packaging` apply to every JVM target in the set.
- `buildCoursierApp { pname, version, lockFile, mainClass?, javaOptions? }` — builds a runnable app straight from a `kind = "coursier-app"` lockfile (Coursier coordinates only, no project sources): a wrapper that runs `java -cp <jars> <mainClass>`. Used for packaging tools published to Maven (e.g. `smithy4s-codegen`).
- `collectChecks packages` — flattens an attrset of packages into a checks-shaped attrset by reading each package's `passthru.tests`. Each `<pkgName>` contributes one entry per test, named `<pkgName>-<testName>`. Packages without `passthru.tests` contribute nothing. Used as `checks.<system> = pkgs.scala-cli-nix.collectChecks self.packages.<system>;`.
- `mkCoursierCache { name, deps }` — builds an offline Coursier cache directory from a list of `{ url, sha256 }` entries (the shape of a lockfile's `dependencies`). Each artifact is fetched as its own FOD and symlinked into Coursier's on-disk layout. Point `COURSIER_CACHE` at the result so a tool that resolves dependencies **at runtime** (e.g. a Smithy codegen whose model assembler pulls `alloy`/`smithy-model` from Maven) runs fully offline in the sandbox. Include the `.pom` URLs alongside the `.jar`s — Coursier needs the POMs for offline resolution. Also surfaced on the overlay as `pkgs.mkCoursierCache`.

Each derivation returned by `buildScalaCliApp(s)` carries `passthru.tests` — an attrset (currently `{ test = <drv>; }`) of test-runner derivations. The test derivation runs `scala-cli test --offline --server=false` against the project's test sources using a deps cache built from both `libraryDependencies` (main scope) and `test.libraryDependencies` (test scope) — scala-cli runs separate resolutions for each scope and the cache must satisfy both. Tests are skipped (the attrset is empty) when the lockfile has no `test` section for that target. The `init` command's generated flake wires every package's `passthru.tests` into `checks` via `collectChecks` so `nix flake check` runs them; users with an existing `flake.nix` get the same one-liner in the printed instructions.

Test invocation passes the filtered project tree as a single directory argument (not as enumerated source files). This matters because scala-cli classifies source scope from path layout — files under a `test/` subdir, or files with the `.test.scala` suffix, land in the test scope. Enumerating files puts everything in the main scope, hiding the test deps from compilation. The test phase also copies the tree into `$TMPDIR` and `cd`s there before invoking scala-cli, so cwd matches the project root for tests that read fixture files via cwd-relative paths (e.g. `Source.fromFile("test/foo.txt")`).

The `mainClass` parameter (JVM only) is only needed when the project has multiple main classes — otherwise it is discovered automatically at build time.

Both functions pass `--platform` and `--scala-version` flags to `scala-cli package` so that multi-platform sources are compiled for the correct target.

#### JVM builds

1. **Per-artifact FODs**: Each `{url, sha256}` entry becomes a `pkgs.fetchurl` call. Each is its own Fixed-Output Derivation in the Nix store — updating one dependency only re-downloads that one JAR. `pkgs.fetchurl` (not `builtins.fetchurl`) is used so Nix schedules downloads in parallel; `builtins.fetchurl` would block the single-threaded evaluator on each artifact sequentially.
2. **Source filtering**: The `src` is filtered using `lib.cleanSourceWith` to only include files listed in the lockfile's `sources` array, plus everything under each `resourceDirs` entry (so `using resourceDir` keeps working). This means changes to unrelated files (e.g. `README.md`, `flake.nix`) don't trigger a rebuild.
3. **Deps cache**: All fetched artifacts are symlinked into a Coursier-compatible cache layout (`mkCacheDir`). This is set as `COURSIER_CACHE` so `scala-cli --offline` can resolve dependencies.
4. **Compilation**: `scala-cli --power package <sources> --server=false --offline --library --platform jvm --scala-version <v>` compiles user code into a small JAR (~4KB) containing only the compiled classes, no bundled dependencies.
5. **Main class discovery**: Unless `mainClass` is explicitly passed, `scala-cli --power run --main-class-list <sources> --server=false --offline` is run inside the sandbox to find the main class. If there isn't exactly one, the build fails with an error asking the user to pass `mainClass` explicitly.
6. **Wrapper**: `makeWrapper` creates an executable that runs `java -cp <all library JARs>:<compiled JAR> <mainClass>`. The classpath references individual Nix store paths — no duplication, each dep independently cacheable.

#### JVM assembly builds (`packaging = "assembly"`)

When `packaging = "assembly"` is passed to `buildScalaCliApp(s)`, a JVM target is built as a single fat JAR bundling user code and all transitive deps:

1. **Deps cache**: Same as the regular JVM build — compiler + library JARs/POMs are symlinked into a Coursier cache.
2. **Compilation**: `scala-cli --power package <sources> --server=false --offline --assembly --platform jvm --scala-version <v> [--main-class <mc>] -o $out/share/<pname>.jar` produces an assembly JAR with `Main-Class` embedded in the manifest. No separate main-class discovery step is needed — scala-cli writes it for us. `--main-class` is forwarded only when the user passed `mainClass` explicitly; otherwise scala-cli infers it.
3. **Wrapper**: scala-cli's `--assembly` output already includes a `#!/usr/bin/env bash` preamble that locates `java` on PATH and execs it on the JAR. `makeWrapper` wraps that executable JAR at `$out/bin/<pname>`, prepending `openjdk/bin` to PATH so the preamble finds our pinned JDK.
4. **Tradeoff**: Per-artifact Nix-store granularity is lost for the *output* — every dep change rebuilds the fat JAR. Inputs (the fetchurl FODs) are still cached per-artifact. Useful for distribution where a single JAR is preferable to a directory of store paths.

`packaging = "assembly"` is rejected on Scala Native targets and is mutually exclusive with `nativeImage = true`.

#### GraalVM native-image builds (JVM targets)

When `nativeImage = true` is passed to `buildScalaCliApp(s)`, a JVM target is built as a GraalVM native image instead of a JAR + JVM wrapper:

1. **Deps cache**: Same as the JVM build path — compiler + library JARs/POMs are symlinked into a Coursier cache.
2. **GraalVM**: nixpkgs' `graalvmPackages.graalvm-ce` is provided as a build input. Its path is passed to scala-cli via `--java-home` so scala-cli does **not** try to coursier-fetch a GraalVM distribution (which would fail in the sandbox).
3. **Compilation + linking**: `scala-cli --power package <sources> --server=false --offline --native-image --java-home <graalvm> --platform jvm --scala-version <v>` compiles user code, resolves deps from the offline cache, and invokes `native-image` to produce a single binary. scala-cli's bundled reflection/resource configs (e.g. for the Scala 3 reflection machinery) are applied automatically — there's no need to maintain a `reflect-config.json` for stdlib usage.
4. **No wrapper**: The output is a native binary placed directly at `$out/bin/<pname>`. No JVM at runtime.
5. **Caveats**: `native-image` is slow and memory-hungry, so JVM-target builds with `nativeImage = true` are noticeably slower than the regular JVM build. App-specific reflection (e.g. Jackson, custom proxies) still needs user-supplied configs — pass them via scala-cli's `using` directives (`//> using packaging.graalvmArgs ...`) so they end up in the build.

`nativeImage = true` is rejected on Scala Native targets — Scala Native already produces a native binary via LLVM.

#### Scala Native builds

For Scala Native (`platform: "Native"`), the build is simpler but the dependency set is larger:

1. **Deps cache**: Compiler and library JARs/POMs are symlinked into the Coursier cache, along with native tooling (linker, scala-native-cli). Scala Native's own runtime artifacts (`nscplugin`, `scala3lib_native`, `javalib_native`) live in `libraryDependencies` — see "Combined resolution for Native targets" in the lockfile reference above. The `+` character in artifact versions (e.g., `3.6.4+0.5.10`) is percent-encoded to `%2B` to match Coursier's cache layout.
2. **Compilation + linking**: `scala-cli --power package <sources> --server=false --offline --platform scala-native --scala-version <v>` compiles and links everything into a single native executable. No `--library` flag — the entire app is linked into one binary.
3. **No wrapper**: The output is a native binary, copied directly to `$out/bin`. No JVM or classpath needed at runtime.
4. **Extra build inputs**: `clang` and `which` are needed for the native linking step.

#### Scala.js builds (frontend)

For Scala.js (`platform: "JS"`), the build links the app into a single ES-module JS file. This targets the frontend-only flow (no node) — the output is meant to be bundled downstream (e.g. by vite), mirroring `scala-cli package --js -o main.js` outside Nix.

1. **Deps cache**: Compiler + library JARs/POMs plus the JS linker tooling (`js.toolingDependencies`) are symlinked into the Coursier cache. The library set already includes the JS stdlib (`scala3-library_sjs1_3`), the JS runtime (`scalajs-library_2.13`), and user deps — see the `targets.<key>.js` field reference.
2. **Linking on the JVM**: `scala-cli --power package <sources> --server=false --offline --platform scala-js --js-version <v> --js-cli-version <v> --js-cli-on-jvm --scala-version <v> -o $out/share/<pname>.js`. `--js-cli-on-jvm` forces the Scala.js linker to run as JARs from the offline cache (the default would download a per-OS `scala-js-ld` binary from GitHub, which the sandbox can't reach). `--js-cli-version` pins the linker version exactly.
3. **No wrapper, no node**: The output is just `$out/share/<pname>.js`. There is no `$out/bin` and no node runtime.
4. **Why the fork is used in-sandbox for JS only.** Unlike JVM/Native (which build with upstream `prev.scala-cli`), the JS path builds with the bundled kubukoz/scala-cli fork (`scalaCliJs` in `lib.nix`, wired in the overlay). Upstream's linker resolves the Scala.js CLI as a `<v>+` range, which needs version-listing metadata that the offline sandbox cache doesn't carry; the fork pins it exactly when `--js-cli-version` is passed. `clang`/native tooling are **not** needed — linking is pure JVM.

#### Common key flags

- `--library` (JVM, default `packaging = "app"`): produces a tiny JAR with only user code. `--standalone` would bundle all deps into one fat JAR, defeating per-artifact store granularity. The opt-in `packaging = "assembly"` mode uses `--assembly` instead, producing a fat JAR for distribution (tradeoff documented above).
- `--server=false`: disables Bloop compilation server (can't run in sandbox).
- `--offline`: prevents any network access attempts.
- `--power`: required to use `--library`.
- `--platform jvm|scala-native|scala-js`: selects the target platform. Always passed, even for single-target projects.
- `--scala-version <v>`: pins the Scala version. Always passed.
- `--js-version <v> --js-cli-version <v> --js-cli-on-jvm` (JS only): pin the Scala.js version and linker version, and run the linker on the JVM from the offline cache. See "Scala.js builds" above.

Sandbox environment variables set in the derivation:
- `COURSIER_CACHE` → the symlinked cache dir
- `COURSIER_ARCHIVE_CACHE` → `/tmp/coursier-arc`
- `SCALA_CLI_HOME` → `/tmp/scala-cli-home`
- `HOME` → `$TMPDIR/home` (Nix sets HOME to `/var/empty` which causes `FileSystemException`)

The classpath filters out POM files (`builtins.match ".*\\.jar"`) since only JARs belong on the runtime classpath.

### Overlay (`flake.nix`)

The flake exposes two overlay entry points:

- `overlays.default` — the standard overlay; equivalent to `overlays.withConfig {}`.
- `overlays.withConfig { urlTransform ? (url: url) }` — parameterised variant for corporate environments. `urlTransform` is applied to every artifact URL before it reaches `fetchurl`, so Maven Central fetches can be redirected to a private mirror (e.g. Artifactory). Because Nix FOD hashes are content-based, a mirror serving identical bytes satisfies the same `sha256`. Usage in a downstream flake:

```nix
overlays = [
  (scala-cli-nix.overlays.withConfig {
    urlTransform = url: builtins.replaceStrings
      [ "https://repo1.maven.org/maven2" ]
      [ "https://artifactory.corp/artifactory/maven-central" ]
      url;
  })
];
```

The overlay provides two packages:

| Package | Description |
|---|---|
| `scala-cli-nix` | The build library (`buildScalaCliApp` and `buildScalaCliApps`) |
| `scala-cli-nix-cli` | The CLI (init/lock commands), built by `buildScalaCliApp` itself (self-hosting); exposes both `scala-cli-nix` and the shorter `scn` alias |
| `scala-cli-nix-cli-native-image` | Same CLI built with `nativeImage = true`; no JVM at runtime, fast startup, slower to build |

`pkgs.scala-cli` itself is **not** overridden — users get whatever upstream nixpkgs ships. Likewise, the sandboxed Nix build (`lib.nix`) uses `prev.scala-cli`.

The CLI does need a specific scala-cli build at lock time (the `kubukoz/scala-cli` fork has fixes the lock workflow depends on), so `scala-cli-nix-cli` is wrapped with `makeWrapper` to set `SCALA_CLI_NIX_SCALA_CLI` to the bundled fork binary's absolute path. This is internal — the fork is never on the user's PATH and never used inside the sandbox.

#### Shell completions

The CLI uses case-app's `CommandsEntryPoint` with `enableCompleteCommand` and `enableCompletionsCommand`, which provides `scala-cli-nix complete <shell> ...` (used by the completion script) and `scala-cli-nix completions install/uninstall` (interactive setup writing to user rc files).

For Nix-installed users the interactive flow isn't needed: `cli/_scala-cli-nix` is a static zsh completion script (`#compdef scala-cli-nix scn`) that the overlay installs to `$out/share/zsh/site-functions/`. nixpkgs adds that path to `fpath` automatically, so completions work out of the box for both `scala-cli-nix` and the `scn` alias.

### `scala-cli-nix init`

Scaffolds a new project:
- `derivation.nix` — callPackage-shaped, calls `buildScalaCliApp` for single-target projects or `buildScalaCliApps` for cross-platform/cross-version projects (detected by counting entries returned by `scala-cli --power list-targets`)
- `flake.nix` — full flake with overlay, packages, and devShell (or prints instructions if flake.nix already exists)
- `scala.lock.json` — generated via `lock`

The generated flake uses the overlay pattern so consumers just do `pkgs.callPackage ./derivation.nix {}`.

#### `--ref`

`init --ref <value>` pins the generated `scala-cli-nix.url`. The value is auto-classified: a 40-char lowercase hex string becomes `?rev=<value>`, anything else becomes `?ref=<value>`. Empty or omitted leaves the URL bare (`github:scala-nix/scala-cli-nix`, floating on default branch). Useful when scaffolding against a feature branch or a known-good rev.

#### External builds (`init <github-url>`)

A second mode of `init`: pass a GitHub web URL — `https://github.com/<owner>/<repo>` or `https://github.com/<owner>/<repo>/tree/<ref>` — and the CLI scaffolds an external build from upstream sources, without expecting any local files. The flow:

1. **Parse** the URL. `<ref>` can be a branch, tag, or sha; omitting `/tree/<ref>` resolves the repo's default branch.
2. **Resolve to a sha** via the GitHub API (`/repos/:owner/:repo/commits/:ref`). 40-char hex refs short-circuit the call.
3. **Compute a dynver-style version**: list semver tags, find the highest one reachable from the resolved sha (via `/compare/<tag>...<rev>`), and format as `<tag>` (identical), `<tag>-<ahead>-<short-sha>` (ahead), or `0-unstable-<short-sha>` (no tag reachable).
4. **Prefetch the tarball** with `nix-prefetch-url --unpack --print-path` to get an SRI sha256 and the unpacked store path.
5. **Sanitize the source** if any `.scala` file declares `//> using computeVersion git:dynver` — the GitHub tarball has no `.git`, so scala-cli refuses to evaluate the directive. The lock step uses a writable temp copy with the directive stripped; the generated `derivation.nix` embeds a matching `runCommand` wrapper so the *build* step applies the same patch.
6. **Lock** the deps via the regular `computeLock` pipeline, with the sanitized path as `sourceRoot` (same code path as `lock --src`).
7. **Write** `derivation.nix` (callPackage-shaped, uses `fetchFromGitHub`) and `scala.lock.json` to the current directory. No `flake.nix` — external builds plug into the host repo's flake, not their own.
8. **Print a hint** when the project targets Scala Native: many SN apps need extra native libs at link time (libcurl, libidn2, ...). The hint shows the `attrOverrides` snippet to add them.

Shared HTTP client: GitHub API calls reuse the `Client[IO]` constructed once per CLI invocation in `runIO`, so a single Ember connection pool covers both the version-check workflow and `init <url>`.

### `scala-cli-nix lock-coords` (coursier-app path)

A second, narrower lockfile shape: package a JVM app straight from Coursier coordinates, without any scala-cli source files. Useful for redistributing existing apps like `metals`, `scalafmt`, or anything in the coursier app channels.

Inputs accepted:

- A positional app name. The CLI looks it up in the default channel (https://github.com/coursier/apps under `apps/resources/<name>.json`); with `--contrib`, it also searches the contrib channel (`apps-contrib/resources/<name>.json`). This mirrors `cs install --contrib NAME`. The descriptor's `dependencies` and `mainClass` drive the lock. Additional `--channel ORG:NAME` flags (repeatable) point at arbitrary Maven channel artifacts — the CLI resolves them to JARs via Coursier (`latest.release`, no transitive deps) and reads `<app>.json` from the JAR root. User channels are searched after default and contrib, in the order given; the first hit wins. The built-in channels stay on the GitHub raw path because it's a single HTTP GET per descriptor; the Maven path costs one JAR fetch per channel.
- One or more `--dep org:name:version` (or `org::name::version` Scala-suffixed). `--main-class CLASS` is optional: if omitted, the lock command opens each directly-passed JAR's `META-INF/MANIFEST.MF` and uses its `Main-Class` attribute. It errors if no `--dep` JAR declares one (or multiple disagree); pass `--main-class` explicitly in that case. Channel-free path.

`--scala-binary <ver>` (default `3.3.0`) selects how `::`/`:::` coordinates expand — set it to `2.13.12` for apps that only ship 2.13 artifacts (e.g. smithy4s).

Both modes share the resolution machinery used by `lock`: `coursierapi.Fetch` resolves transitively, SHA-256 is computed via `java.security.MessageDigest`, and the per-user hash cache is reused.

The resulting lockfile uses a discriminated shape — `kind = "coursier-app"` at the top level — so the existing scala-cli format (implicit `kind = "scala-cli"`) is untouched. Only JARs are recorded; POMs are dropped because the build side doesn't shell out to scala-cli's offline resolver for this path, so they would be dead weight.

```json
{
  "version": 9,
  "kind": "coursier-app",
  "mainClass": "scala.meta.metals.Main",
  "javaOptions": [],
  "dependencies": [
    { "url": "https://repo1.maven.org/.../foo-1.0.jar", "sha256": "..." }
  ]
}
```

### `buildCoursierApp` (build side)

`lib.nix` exposes `buildCoursierApp { pname, version, lockFile, mainClass ? null, javaOptions ? [] }`. It asserts `kind = "coursier-app"`, fetches each JAR via `pkgs.fetchurl`, and writes a hand-rolled shell wrapper at `$out/bin/<pname>` that exec's `java -cp <classpath> <mainClass>`. No scala-cli at build time, no compilation step — this path is fast.

`mainClass` defaults to the value baked into the lockfile by `lock-coords`. `javaOptions` from the lockfile (e.g. coursier channel descriptors that ship `-D...` flags) are placed before any caller-supplied ones.

#### Why a hand-rolled wrapper, not `makeWrapper`

JVM resolves `user.home` from the OS user database (macOS) or `/etc/passwd` (Linux), not from `$HOME`. In a Nix sandbox there is no user record, so the JVM lands on `/var/empty` (macOS) or `/homeless-shelter` (Linux). Apps that write under `user.home` — metals' macOS log dir, coursier's cache, etc. — then crash on startup.

`makeWrapper` would bake `-Duser.home=...` at build time, but we don't have a writable path until runtime. The wrapper checks `$HOME` for writability at exec time, falling back to `$TMPDIR` or `/tmp`. That keeps normal user invocations on `$HOME` and rescues sandbox / odd CI environments without forcing the user to set anything.

## Development

### Project structure

```
flake.nix              # Flake: overlay, packages, checks
lib.nix                # buildScalaCliApp / buildScalaCliApps Nix functions
cli/
  scala-cli-nix.scala  # CLI tool (init/lock), built by buildScalaCliApp
  derivation.nix       # Self-hosting derivation
  scala.lock.json      # CLI's own lockfile
examples/
  scala3/              # Scala 3 example (cats-effect hello world)
  scala2/              # Scala 2 example (os-lib hello world)
  scala-native/        # Scala Native example (hello world)
  scala-native-ce/     # Scala Native + cats-effect example
  scala-native-ce-cross/  # Cross JVM+Native example (cats-effect)
  scala-resources/        # Cross JVM+Native example using //> using resourceDir
  scala3-js/              # Scala.js (frontend) example: links to $out/share/<pname>.js, no node
  scala3-native-image/    # JVM target built as a GraalVM native image (nativeImage = true)
  scala3-assembly/        # JVM target built as a fat assembly JAR (packaging = "assembly")
  scala3-shadowed-deps/   # Regression guard: builds against a real lockfile that includes an evicted-POM coordinate; the binary calls `Node.child` to verify the runtime classpath isn't shadowed by a duplicate JAR
  scala3-native-evicted-2.13/    # Regression guard for combined Native resolution: portable-scala-reflect pins scalalib_native0.5_2.13, which would resolve differently under a user-libs-only pass — see "Combined resolution for Native targets"
  scala3-subset/          # Regression guard for subset source locking: an `unrelated.scala` with invalid Scala lives in the project root and must NOT leak into the build (the lockfile scopes sources to `src/` only)
  scala3-cross-platform-version/  # Full matrix example: JVM+Native × two Scala 3 versions (3.3.4 and 3.6.4), exercising the `<platform>-<version>` target-key format
  scala-test-weaver/        # Cross JVM+Native, weaver-cats test framework
  scala-test-munit/         # Cross JVM+Native, munit test framework
  scala-test-utest/         # Cross JVM+Native, utest test framework
  scala-test-scalatest/     # Cross JVM+Native, scalatest test framework
  scala-test-ziotest/       # Cross JVM+Native, zio-test test framework
  scala-native-docker/            # Wraps the scala-native binary into a dockerTools.buildLayeredImage (Linux-only)
  scala3-jvm-docker/              # Wraps the scala3 JVM app (wrapper + per-artifact JARs end up in the image)
  scala3-native-image-docker/     # Wraps the GraalVM native-image binary
  hello-http4s-docker/            # Wraps hello-http4s.jvm and exposes 8080; VM test runs `docker run -p` and curls the response
  hello-http4s-nixos-container/   # VM test that exercises the http-apps module's `container.enable` path: hello-http4s.jvm inside a declarative nixos-container fronted by caddy
  metals/                         # buildCoursierApp from raw --dep coords (no contrib channel)
  scalafmt/                       # buildCoursierApp via the default coursier app channel
  smithy4s/                       # buildCoursierApp via the coursier contrib channel (--contrib, --scala-binary 2.13.12)
```

Each `*-docker/` example is a thin `dockerTools.buildLayeredImage` derivation that takes the upstream app as a `callPackage` argument; no Scala source or lockfile of its own. They're wired into the root flake under `lib.optionalAttrs pkgs.stdenv.isLinux` (because `dockerTools` doesn't build on Darwin) so they at least build under `nix flake check` on aarch64-linux. The per-image VM tests (`docker-image-scala-native`, `docker-image-scala3-jvm`, `docker-image-scala3-native-image`, each built via the `mkDockerImageTest` helper) are further gated to `x86_64-linux` because `pkgs.testers.runNixOSTest` needs KVM of the matching arch, and that's the only Linux arch we count on for CI builders. Each VM test runs a NixOS guest with dockerd that loads one image and asserts the container's stdout — covering the full pattern from the README's Docker section.

`hello-http4s-docker/` and `hello-http4s-nixos-container/` are sibling deployment examples that both ship the `hello-http4s.native` binary — host units cover the JVM/native binary axis already, so the container/docker variants focus on smaller, faster-starting native images. The docker example's VM test (`docker-image-hello-http4s`) loads the image, runs it with `-p 8080:8080`, and curls the exposed port — proving the full request path works, not just stdout.

`hello-http4s-nixos-container/` follows the same deployment-example pattern but uses NixOS's native declarative containers instead of Docker. The shared `nixos/http-apps.nix` module (exposed as `nixosModules.http-apps`) gained a per-app `container.enable` option: when set, the unit runs inside `containers.<name>` (systemd-nspawn) on its own auto-allocated `/30` veth pair, with the listen port forwarded to the host so caddy's reverse-proxy line is identical to the host-unit case. `derivation.nix` is a `runNixOSTest` that imports the module, configures one entry with `container.enable = true`, and curls through caddy end-to-end — gated to `x86_64-linux` for the same KVM-arch reason as the docker tests. The same option is exercised in production by `services.demo-apps` (which wires this module's `hello-native-container` entry), enabled on the actual host in the `jk-hetzner` repo.

The same module supports a third deployment mode: `docker.image = "name:tag"` (with `docker.imageFile` for locally-built tarballs) deploys via `virtualisation.oci-containers`, so dockerd manages the unit and NixOS handles the `docker load` + `docker run` plumbing. The `demo-apps` module's `hello-native-docker` entry runs the locally-built `examples/hello-http4s-docker` image this way, alongside the host-unit and nixos-container variants — three different deployment styles for the same binary, each behind its own caddy vhost.

### NixOS modules (`nixos/`)

`nixos/http-apps.nix` (`nixosModules.http-apps`) is the generic per-app deployment module described above. `nixos/demo-apps.nix` (`nixosModules.demo-apps`) imports it and pre-wires the `examples/hello-http4s` cross app across all three deployment modes behind a single `services.demo-apps = { enable; baseDomain; }`; it `callPackage`s the example derivations, so a consuming host must apply `overlays.default`. Neither module declares a `nixosConfigurations` host — the concrete Hetzner box (disko/GRUB, opentofu provisioning, deploy-rs wiring, the smithy-exercises site) lives in the separate `jk-hetzner` repo, which imports `demo-apps` as a flake input.

### Running checks

```bash
nix flake check --print-build-logs
```

This builds all example apps (Scala 2, Scala 3, Scala Native, Native+CE, the cross JVM/Native example, and on Linux the docker images via a NixOS VM test) and verifies their output.

### CI cache (nix-ci.com + nixbuild.net → `scala-cli-nix.cachix.org`)

CI builds run on [nix-ci.com](https://nix-ci.com), which dispatches the heavy work to [nixbuild.net](https://nixbuild.net). Both share the `scala-cli-nix` Cachix cache:

- nix-ci pushes every successful flake build to `scala-cli-nix.cachix.org`.
- nixbuild.net pulls from the same cache as a substituter, so unchanged derivations aren't rebuilt across runs.
- Local `nix build` / `nix flake check` (and downstream repos in `known-users`) hits the cache directly.

#### Per-repo: `nix-ci.nix`

The `cachix` block in `nix-ci.nix` at the repo root tells nix-ci which cache to push to and which public key to trust on pull. The `public-key` placeholder must be replaced with the real one — copy it from the cache page on `app.cachix.org/cache/scala-cli-nix` (Settings → Public signing key).

A `CACHIX_AUTH_TOKEN` (or `CACHIX_SIGNING_KEY`) secret must be set on the repo in the nix-ci dashboard for pushes to succeed.

#### Account-side: nixbuild.net

For nixbuild.net to *substitute from* the cache (avoid rebuilds across runs), set this once on the nixbuild.net account settings:

- `substituters`: append `https://scala-cli-nix.cachix.org`
- `trusted-public-keys`: append the same `scala-cli-nix.cachix.org-1:...` key used in `nix-ci.nix`

This is account-level, not per-repo — every repo built through nixbuild.net under this account benefits.

### CLI tool

The CLI tool (`cli/scala-cli-nix.scala`) is written in Scala 3 and built by `buildScalaCliApp`. It uses `coursierapi` for dependency resolution, `fs2` for process execution and file I/O, and `circe` for JSON. To update the CLI's own lockfile after changing its dependencies, run:

```bash
cd cli
nix run ..# -- lock .
```

### Regenerating an example lockfile

```bash
cd examples/scala3
nix run ../..# -- lock
# or, from devShell:
scn lock
```

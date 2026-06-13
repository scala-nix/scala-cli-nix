# `scalaCliJs` is the scala-cli used *inside the sandbox for Scala.js builds*.
# It defaults to the same `scala-cli` as every other platform, but the overlay
# passes the bundled kubukoz/scala-cli fork: the JS linker needs the fork's
# "pin the Scala.js CLI version exactly instead of as a `<v>+` range" fix so it
# resolves offline (upstream still uses a range, which needs version-listing
# metadata the sandbox cache doesn't carry). JVM/Native builds keep using the
# unmodified upstream `scala-cli`.
{ scala-cli, scalaCliJs ? scala-cli, openjdk, makeWrapper, runCommand, stdenv, lib, clang, which, graalvmPackages, fetchurl, bash }:
let
  supportedVersion = 9;

  # Each fetchurl produces its own FOD — per-artifact granularity, and Nix
  # realizes them in parallel (unlike builtins.fetchurl, which would block the
  # single-threaded evaluator on each download sequentially).
  fetchAll = deps: builtins.map (dep: { inherit dep; path = fetchurl { inherit (dep) url sha256; }; }) deps;

  # Common version + kind check shared by both lockfile loaders.
  checkLockVersion = lockVersion:
    if lockVersion != supportedVersion
    then builtins.throw "scala-cli-nix: unsupported lockfile version ${builtins.toString lockVersion} (expected ${builtins.toString supportedVersion}). Re-run 'scala-cli-nix lock' (or 'scala-cli-nix lock-coords') to regenerate."
    else true;

  fetchDeps = lockFile:
    let
      json = builtins.fromJSON (builtins.readFile lockFile);
      lockVersion = json.version or null;
      versionCheck = checkLockVersion lockVersion;
      kind = json.kind or "scala-cli";
      kindCheck =
        if kind != "scala-cli"
        then builtins.throw "scala-cli-nix: buildScalaCliApp(s) expects a scala-cli lockfile (kind = \"scala-cli\") but got kind = \"${kind}\". Use buildCoursierApp for coursier-app lockfiles."
        else true;
      native = target: target.native or null;
      js = target: target.js or null;
      test = target: target.test or null;
    in assert versionCheck; assert kindCheck; {
      sources = json.sources or [];
      resourceDirs = json.resourceDirs or [];
      targets = builtins.mapAttrs (name: target:
        let
          n = native target;
          j = js target;
          t = test target;
        in {
          inherit (target) platform scalaVersion;
          compiler = fetchAll target.compiler;
          libraryDependencies = fetchAll target.libraryDependencies;
          nativeToolingDependencies = if n != null then fetchAll n.toolingDependencies else [];
          scalaNativeVersion = if n != null then n.scalaNativeVersion else null;
          jsToolingDependencies = if j != null then fetchAll j.toolingDependencies else [];
          scalaJsVersion = if j != null then j.scalaJsVersion else null;
          scalaJsCliVersion = if j != null then j.scalaJsCliVersion else null;
          test =
            if t != null
            then {
              sources = t.sources;
              resourceDirs = t.resourceDirs or [];
              libraryDependencies = fetchAll t.libraryDependencies;
            }
            else null;
        }
      ) json.targets;
    };

  # Build a Coursier-compatible cache directory from fetched deps
  mkCacheDir = name: deps:
    let
      # Generate symlink commands for each dep
      linkCommands = builtins.map (entry:
        let
          # Strip "https://" and percent-encode "+" to match Coursier cache layout
          relative = builtins.replaceStrings [ "https://" "+" ] [ "https/" "%2B" ] entry.dep.url;
        in ''
          mkdir -p $out/$(dirname "${relative}")
          ln -sf ${entry.path} $out/${relative}
        ''
      ) deps;
    in runCommand name {} (builtins.concatStringsSep "\n" linkCommands);

  # Public: build an offline Coursier cache directory from a list of
  # `{ url, sha256 }` entries (the same shape as a lockfile's `dependencies`).
  # Each artifact is fetched as its own FOD and symlinked into the on-disk
  # layout Coursier expects, so a tool that resolves dependencies *at runtime*
  # (e.g. a Smithy/smithy4s codegen that calls a model assembler which pulls
  # `alloy`/`smithy-model` from Maven) can run fully offline in the Nix sandbox
  # by pointing `COURSIER_CACHE` at the result.
  #
  #   COURSIER_CACHE = scala-cli-nix.mkCoursierCache {
  #     name = "my-runtime-deps";
  #     deps = (builtins.fromJSON (builtins.readFile ./runtime-deps.lock.json)).dependencies;
  #   };
  #
  # Include the `.pom` URLs alongside the `.jar`s — Coursier needs the POMs for
  # offline resolution metadata.
  mkCoursierCache = { name, deps }: mkCacheDir name (fetchAll deps);

  # Filter `src` down to the listed source files (and resource directories),
  # returning both the filtered source tree and a space-separated string of
  # source paths to feed scala-cli. Resource directories are included as
  # whole subtrees so `using resourceDir` can find them at compile/link time.
  prepareSources = sources: resourceDirs: src:
    let
      # Is `rel` inside (or equal to) any resourceDir? Then keep it.
      insideResourceDir = rel:
        builtins.any (r: rel == r || lib.hasPrefix (r + "/") rel) resourceDirs;
      # Is `rel` an ancestor of any resourceDir? Then we must descend into it.
      ancestorOfResourceDir = rel:
        builtins.any (r: lib.hasPrefix (rel + "/") r) resourceDirs;
      filteredSrc =
        if sources != [] || resourceDirs != []
        then lib.cleanSourceWith {
          src = src;
          filter = path: type:
            let rel = lib.removePrefix (toString src + "/") (toString path);
            in if type == "directory"
              then builtins.any (s: lib.hasPrefix (rel + "/") s) sources
                || insideResourceDir rel
                || ancestorOfResourceDir rel
              else builtins.elem rel sources || insideResourceDir rel;
        }
        else src;
      sourceArgs =
        if sources != []
        then builtins.concatStringsSep " " (builtins.map (s: "${filteredSrc}/${s}") sources)
        else "${filteredSrc}";
    in { inherit filteredSrc sourceArgs; };

  # Map lockfile platform name to --platform flag value
  platformFlag = platform:
    if platform == "Native" then "scala-native"
    else if platform == "JS" then "scala-js"
    else "jvm";

  # `--platform <p>` plus, for Native targets, `--native-version <v>` so
  # `scala-cli package`/`test` uses the Scala Native version recorded in the
  # lockfile rather than whatever default the in-sandbox scala-cli ships with.
  # Without this, builds break whenever the locking and building scala-cli
  # versions disagree on the bundled Scala Native (e.g. fork on 0.5.11 vs
  # nixpkgs on 0.5.10).
  #
  # JS is analogous: `--js-version <v>` pins the Scala.js version to the locked
  # linker, and `--js-cli-on-jvm` forces the linker to run from the Coursier
  # cache on the JVM (instead of downloading a per-OS `scala-js-ld` binary from
  # GitHub, which the sandbox can't reach). Module kind / opt mode come from the
  # project's `using` directives, so they don't need to be passed here.
  platformArgs = fetched:
    "--platform ${platformFlag fetched.platform}" +
    (if fetched.scalaNativeVersion != null
     then " --native-version ${fetched.scalaNativeVersion}"
     else "") +
    (if fetched.scalaJsVersion != null
     then " --js-version ${fetched.scalaJsVersion}"
        + " --js-cli-version ${fetched.scalaJsCliVersion}"
        + " --js-cli-on-jvm"
     else "");

  # Common scala-cli sandbox env setup, shared by JVM/Native build and test phases
  scalaCliEnvSetup = ''
    export HOME=$TMPDIR/home
    export COURSIER_ARCHIVE_CACHE=$TMPDIR/coursier-arc
    export SCALA_CLI_HOME=$TMPDIR/scala-cli-home
    mkdir -p $HOME $COURSIER_ARCHIVE_CACHE $SCALA_CLI_HOME
  '';

  buildJvmTest = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      mainAndTestResourceDirs = resourceDirs ++ fetched.test.resourceDirs;
      # Pass the filtered project tree (not enumerated files) so scala-cli's
      # directory-based scope heuristics fire — sources under a `test/` dir get
      # classified as test scope, just like `.test.scala`-suffixed files.
      # Enumerating files puts everything in main scope and test deps go
      # missing at compile time.
      inherit (prepareSources mainAndTestSources mainAndTestResourceDirs src) filteredSrc;
      # scala-cli runs separate Coursier resolutions for the main and test
      # scopes; if the test framework pulls a transitive version that wins over
      # what the main scope picks, the two resolutions disagree and each scope
      # needs its own winners in cache.
      allDeps = fetched.compiler ++ fetched.libraryDependencies ++ fetched.test.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-test-deps-${pname}" allDeps;
    in stdenv.mkDerivation (attrOverrides ({
      pname = "${pname}-test";
      inherit version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk ];
      COURSIER_CACHE = depsCache;
      # Run from a writable copy of the project tree so tests that read
      # cwd-relative files (e.g. `Source.fromFile("test/foo.txt")`) resolve
      # the same way they do when run from a normal working checkout.
      buildPhase = scalaCliEnvSetup + ''
        cp -r ${filteredSrc} $TMPDIR/proj
        chmod -R u+w $TMPDIR/proj
        cd $TMPDIR/proj
        scala-cli --power test . --server=false --offline \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion}
      '';
      installPhase = ''
        mkdir -p $out
        touch $out/passed
      '';
    }) "JVM");

  buildNativeTest = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      mainAndTestResourceDirs = resourceDirs ++ fetched.test.resourceDirs;
      # See buildJvmTest for why we pass the project tree, not file args.
      inherit (prepareSources mainAndTestSources mainAndTestResourceDirs src) filteredSrc;
      # scala-cli runs separate Coursier resolutions for the main and test
      # scopes; if the test framework pulls a transitive version that wins over
      # what the main scope picks (e.g. munit 1.3.0 → javalib_native 0.5.11
      # while the main scope's scala3lib_native pins 0.5.10), the two scopes
      # disagree and each needs its own winners in cache.
      allDeps = fetched.compiler ++ fetched.libraryDependencies ++ fetched.test.libraryDependencies
        ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-test-deps-${pname}" allDeps;
    in stdenv.mkDerivation (attrOverrides ({
      pname = "${pname}-test";
      inherit version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];
      COURSIER_CACHE = depsCache;
      # See buildJvmTest for why we run from a writable copy of the tree.
      buildPhase = scalaCliEnvSetup + ''
        cp -r ${filteredSrc} $TMPDIR/proj
        chmod -R u+w $TMPDIR/proj
        cd $TMPDIR/proj
        scala-cli --power test . --server=false --offline \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion}
      '';
      installPhase = ''
        mkdir -p $out
        touch $out/passed
      '';
    }) "Native");

  buildJsTest = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      mainAndTestSources = sources ++ fetched.test.sources;
      mainAndTestResourceDirs = resourceDirs ++ fetched.test.resourceDirs;
      # See buildJvmTest for why we pass the project tree, not file args.
      inherit (prepareSources mainAndTestSources mainAndTestResourceDirs src) filteredSrc;
      # Main + test scope winners, plus the JS linker tooling so `scala-cli test
      # --platform js --js-cli-on-jvm` can link the test bundle offline.
      allDeps = fetched.compiler ++ fetched.libraryDependencies ++ fetched.test.libraryDependencies
        ++ fetched.jsToolingDependencies;
      depsCache = mkCacheDir "scala-cli-test-deps-${pname}" allDeps;
    in stdenv.mkDerivation (attrOverrides ({
      pname = "${pname}-test";
      inherit version;
      dontUnpack = true;
      buildInputs = [ scalaCliJs openjdk ];
      COURSIER_CACHE = depsCache;
      # See buildJvmTest for why we run from a writable copy of the tree.
      buildPhase = scalaCliEnvSetup + ''
        cp -r ${filteredSrc} $TMPDIR/proj
        chmod -R u+w $TMPDIR/proj
        cd $TMPDIR/proj
        scala-cli --power test . --server=false --offline \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion}
      '';
      installPhase = ''
        mkdir -p $out
        touch $out/passed
      '';
    }) "JS");

  # Build the test derivation attrset for passthru.tests, or {} if no tests.
  mkTests = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    if fetched.test == null then {}
    else if fetched.platform == "Native"
    then { test = buildNativeTest { inherit pname version src sources resourceDirs fetched attrOverrides; }; }
    else if fetched.platform == "JS"
    then { test = buildJsTest { inherit pname version src sources resourceDirs fetched attrOverrides; }; }
    else { test = buildJvmTest { inherit pname version src sources resourceDirs fetched attrOverrides; }; };

  buildJvmApp = { pname, version, src, sources, resourceDirs, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      # Only JARs on the classpath, not POMs
      libraryJars = builtins.filter (e: builtins.match ".*\\.jar" e.dep.url != null) fetched.libraryDependencies;
      libraryClasspath = builtins.concatStringsSep ":" (builtins.map (e: e.path) libraryJars);

      defaultCompileAttrs = {
        pname = "${pname}-compiled";
        inherit version;
        dontUnpack = true;
        buildInputs = [ scala-cli openjdk ];

        COURSIER_CACHE = depsCache;

        buildPhase = scalaCliEnvSetup + ''
          scala-cli --power package ${sourceArgs} --server=false --offline --library \
            ${platformArgs fetched} \
            --scala-version ${fetched.scalaVersion} \
            -o $out/share/${pname}.jar
        '' + lib.optionalString (mainClass == null) ''
          MAIN_CLASSES=$(scala-cli --power run --main-class-list ${sourceArgs} --server=false --offline \
            ${platformArgs fetched} \
            --scala-version ${fetched.scalaVersion})
          MAIN_CLASS_COUNT=$(echo "$MAIN_CLASSES" | wc -l)
          if [ "$MAIN_CLASS_COUNT" -ne 1 ]; then
            echo "error: found $MAIN_CLASS_COUNT main classes, expected exactly 1:"
            echo "$MAIN_CLASSES"
            echo "Pass mainClass to buildScalaCliApp to disambiguate."
            exit 1
          fi
          echo -n "$MAIN_CLASSES" > $out/share/main-class
        '';
        installPhase = "true";
      };

      compiledJar = stdenv.mkDerivation (attrOverrides defaultCompileAttrs "JVM");

      resolvedMainClass =
        if mainClass != null
        then mainClass
        else builtins.readFile "${compiledJar}/share/main-class";

      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };

    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ openjdk makeWrapper ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;
      installPhase = ''
        mkdir -p $out/bin
        makeWrapper ${openjdk}/bin/java $out/bin/${pname} \
          --add-flags "-cp ${libraryClasspath}:${compiledJar}/share/${pname}.jar ${resolvedMainClass}"
      '';
    }) "JVM");

  buildJvmAssemblyApp = { pname, version, src, sources, resourceDirs, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;
      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk makeWrapper ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;

      COURSIER_CACHE = depsCache;

      buildPhase = scalaCliEnvSetup + ''
        mkdir -p $out/share
        scala-cli --power package ${sourceArgs} --server=false --offline --assembly \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion} \
          ${lib.optionalString (mainClass != null) "--main-class ${mainClass}"} \
          -o $out/share/${pname}.jar
      '';
      installPhase = ''
        mkdir -p $out/bin
        makeWrapper $out/share/${pname}.jar $out/bin/${pname} \
          --prefix PATH : ${openjdk}/bin
      '';
    }) "JVM");

  buildNativeImageApp = { pname, version, src, sources, resourceDirs, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;
      graalvm = graalvmPackages.graalvm-ce;
      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli graalvm ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;

      COURSIER_CACHE = depsCache;

      buildPhase = scalaCliEnvSetup + ''
        mkdir -p $out/bin
        scala-cli --power package ${sourceArgs} --server=false --offline --native-image \
          --java-home ${graalvm} \
          --platform jvm \
          --scala-version ${fetched.scalaVersion} \
          ${lib.optionalString (mainClass != null) "--main-class ${mainClass}"} \
          -o $out/bin/${pname}
      '';
      installPhase = "true";
    }) "JVM");

  buildNativeApp = { pname, version, src, sources, resourceDirs, fetched, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies
        ++ fetched.nativeToolingDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;

      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scala-cli openjdk clang which ];
      passthru = { inherit tests; };
      meta.mainProgram = pname;

      COURSIER_CACHE = depsCache;

      buildPhase = scalaCliEnvSetup + ''
        mkdir -p $out/bin
        scala-cli --power package ${sourceArgs} --server=false --offline \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion} \
          -o $out/bin/${pname}
      '';
      installPhase = "true";
    }) "Native");

  # Scala.js (frontend) build: link the app to a single ES-module JS file at
  # `$out/share/<pname>.js`. There is no node runtime and no `$out/bin` wrapper
  # — the output is meant to be bundled by a downstream tool (e.g. vite) the
  # same way `scala-cli package --js -o main.js` feeds a web build outside Nix.
  buildJsApp = { pname, version, src, sources, resourceDirs, fetched, mainClass, attrOverrides }:
    let
      inherit (prepareSources sources resourceDirs src) sourceArgs;
      allDeps = fetched.compiler ++ fetched.libraryDependencies
        ++ fetched.jsToolingDependencies;
      depsCache = mkCacheDir "scala-cli-deps-${pname}" allDeps;
      tests = mkTests { inherit pname version src sources resourceDirs fetched attrOverrides; };
    in stdenv.mkDerivation (attrOverrides ({
      inherit pname version;
      dontUnpack = true;
      buildInputs = [ scalaCliJs openjdk ];
      passthru = { inherit tests; };

      COURSIER_CACHE = depsCache;

      buildPhase = scalaCliEnvSetup + ''
        mkdir -p $out/share
        scala-cli --power package ${sourceArgs} --server=false --offline \
          ${platformArgs fetched} \
          --scala-version ${fetched.scalaVersion} \
          ${lib.optionalString (mainClass != null) "--main-class ${mainClass}"} \
          -o $out/share/${pname}.js --force
      '';
      installPhase = "true";
    }) "JS");

  buildTarget = { pname, version, src, sources, resourceDirs, targetFetched, mainClass ? null, nativeImage ? false, packaging ? "app", attrOverrides }:
    assert lib.assertMsg (builtins.elem packaging [ "app" "assembly" ])
      "scala-cli-nix: packaging must be \"app\" or \"assembly\" (got \"${packaging}\")";
    assert lib.assertMsg (!(nativeImage && packaging != "app"))
      "scala-cli-nix: nativeImage = true is incompatible with packaging = \"${packaging}\"";
    if targetFetched.platform == "Native"
    then
      assert lib.assertMsg (!nativeImage)
        "scala-cli-nix: nativeImage = true is only valid for JVM targets (this target is Scala Native)";
      assert lib.assertMsg (packaging == "app")
        "scala-cli-nix: packaging = \"${packaging}\" is only valid for JVM targets (this target is Scala Native)";
      buildNativeApp { inherit pname version src sources resourceDirs attrOverrides; fetched = targetFetched; }
    else if targetFetched.platform == "JS"
    then
      assert lib.assertMsg (!nativeImage)
        "scala-cli-nix: nativeImage = true is only valid for JVM targets (this target is Scala.js)";
      assert lib.assertMsg (packaging == "app")
        "scala-cli-nix: packaging = \"${packaging}\" is only valid for JVM targets (this target is Scala.js)";
      buildJsApp { inherit pname version src sources resourceDirs mainClass attrOverrides; fetched = targetFetched; }
    else if nativeImage
    then buildNativeImageApp { inherit pname version src sources resourceDirs mainClass attrOverrides; fetched = targetFetched; }
    else if packaging == "assembly"
    then buildJvmAssemblyApp { inherit pname version src sources resourceDirs mainClass attrOverrides; fetched = targetFetched; }
    else buildJvmApp { inherit pname version src sources resourceDirs mainClass attrOverrides; fetched = targetFetched; };

  # Normalize dots to underscores for Nix attribute name ergonomics
  nixKey = key: builtins.replaceStrings [ "." ] [ "_" ] key;

  # Load a `kind = "coursier-app"` lockfile: just dependencies, mainClass, and
  # optional javaOptions. No sources, no platform, no Coursier cache setup —
  # the build is a `makeWrapper` over `java -cp <fetched JARs> <mainClass>`.
  loadCoursierAppLock = lockFile:
    let
      json = builtins.fromJSON (builtins.readFile lockFile);
      lockVersion = json.version or null;
      versionCheck = checkLockVersion lockVersion;
      kind = json.kind or "scala-cli";
      kindCheck =
        if kind != "coursier-app"
        then builtins.throw "scala-cli-nix: buildCoursierApp expects a coursier-app lockfile (kind = \"coursier-app\") but got kind = \"${kind}\". Did you mean buildScalaCliApp?"
        else true;
    in assert versionCheck; assert kindCheck; {
      mainClass = json.mainClass;
      javaOptions = json.javaOptions or [];
      dependencies = fetchAll (json.dependencies or []);
    };

in {
  # Build an offline Coursier cache from `{ url, sha256 }` entries. See the
  # definition above for the rationale (runtime dependency resolution offline).
  inherit mkCoursierCache;

  # Collect `passthru.tests` from every package in `packages` into a flat
  # checks-shaped attrset, mapping `<pkgName>-<testName>` to each test
  # derivation. Packages without a `passthru.tests` attrset contribute
  # nothing. Intended for use as `checks.<system> = scala-cli-nix.collectChecks
  # self.packages.<system>;` in flakes that consume `buildScalaCliApp(s)`.
  collectChecks = packages:
    builtins.foldl'
      (acc: pkgName:
        let tests = packages.${pkgName}.passthru.tests or {};
        in acc // lib.mapAttrs'
          (testName: drv: { name = "${pkgName}-${testName}"; value = drv; })
          tests)
      {}
      (builtins.attrNames packages);

  # Build all targets from a lockfile, returning an attrset keyed by target name
  # e.g. { jvm = <drv>; native = <drv>; } or { jvm-3_6_4 = <drv>; native-3_6_4 = <drv>; }
  buildScalaCliApps = { pname, version, src, lockFile, mainClass ? null, nativeImage ? false, packaging ? "app", attrOverrides ? (attrs: _platform: attrs) }:
    let
      fetched = fetchDeps lockFile;
    in builtins.listToAttrs (
      builtins.map (key:
        { name = nixKey key;
          value = buildTarget {
            inherit pname version src mainClass nativeImage packaging attrOverrides;
            sources = fetched.sources;
            resourceDirs = fetched.resourceDirs;
            targetFetched = fetched.targets.${key};
          };
        }
      ) (builtins.attrNames fetched.targets)
    );

  # Build a single target from a lockfile.
  # If the lockfile has exactly one target, builds it.
  # If it has multiple targets, `target` must be specified (e.g. target = "jvm").
  buildScalaCliApp = { pname, version, src, lockFile, mainClass ? null, target ? null, nativeImage ? false, packaging ? "app", attrOverrides ? (attrs: _platform: attrs) }:
    let
      fetched = fetchDeps lockFile;
      targetNames = builtins.attrNames fetched.targets;
      resolvedTarget =
        if target != null
        then target
        else if builtins.length targetNames == 1
        then builtins.head targetNames
        else builtins.throw "scala-cli-nix: lockfile has multiple targets (${builtins.concatStringsSep ", " targetNames}). Pass `target` to buildScalaCliApp or use buildScalaCliApps.";
    in buildTarget {
      inherit pname version src mainClass nativeImage packaging attrOverrides;
      sources = fetched.sources;
      resourceDirs = fetched.resourceDirs;
      targetFetched = fetched.targets.${resolvedTarget};
    };

  # Build a JVM app straight from a `coursier-app` lockfile produced by
  # `scala-cli-nix lock-coords`. No scala-cli at build time, no compilation —
  # just `makeWrapper` over `java -cp <fetched JARs> <mainClass>`.
  #
  # `mainClass` defaults to the value baked into the lockfile by `lock-coords`;
  # callers can override it. `javaOptions` from the lockfile (e.g. from a
  # coursier contrib-channel descriptor) are placed before any user-supplied
  # ones, which themselves precede the classpath.
  buildCoursierApp = { pname, version, lockFile, mainClass ? null, javaOptions ? [] }:
    let
      loaded = loadCoursierAppLock lockFile;
      resolvedMainClass = if mainClass != null then mainClass else loaded.mainClass;
      mainClassCheck =
        if resolvedMainClass == null
        then builtins.throw "scala-cli-nix: buildCoursierApp requires mainClass — neither the lockfile nor the call site provided one."
        else true;
      # Only JARs end up on the classpath; the lockfile already excludes POMs
      # (lock-coords drops them) but we filter defensively to stay symmetric
      # with the scala-cli build path.
      jarEntries = builtins.filter
        (e: builtins.match ".*\\.jar" e.dep.url != null)
        loaded.dependencies;
      classpath = builtins.concatStringsSep ":" (builtins.map (e: e.path) jarEntries);
      allJavaOptions = loaded.javaOptions ++ javaOptions;
      # JVM picks `user.home` from the OS user database on macOS (and from
      # /etc/passwd on Linux), so Nix sandbox builds leave it at /var/empty
      # or /homeless-shelter — both unwritable. Apps that write under
      # user.home (metals' log dir on macOS, coursier's cache, etc.) then
      # crash. The wrapper evaluates a writable home at runtime: it prefers
      # $HOME when set and writable (normal user case) and falls back to
      # $TMPDIR or /tmp when it isn't (sandbox / odd CI envs). Done in a
      # hand-rolled shell wrapper because makeWrapper freezes its flags at
      # build time.
      javaOptsStr = lib.escapeShellArgs allJavaOptions;
    in assert mainClassCheck; stdenv.mkDerivation {
      inherit pname version;
      dontUnpack = true;
      meta.mainProgram = pname;
      installPhase = ''
        mkdir -p $out/bin
        cat > $out/bin/${pname} <<EOF
        #!${bash}/bin/bash
        if [ -n "\''${HOME:-}" ] && [ -w "\$HOME" ]; then
          user_home="\$HOME"
        else
          user_home="\''${TMPDIR:-/tmp}"
        fi
        exec ${openjdk}/bin/java -Duser.home="\$user_home" ${javaOptsStr} -cp ${classpath} ${resolvedMainClass} "\$@"
        EOF
        chmod +x $out/bin/${pname}
      '';
    };
}

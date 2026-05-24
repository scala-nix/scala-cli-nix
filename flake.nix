{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    disko.url = "github:nix-community/disko";
    disko.inputs.nixpkgs.follows = "nixpkgs";
    deploy-rs.url = "github:serokell/deploy-rs";
    deploy-rs.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, disko, deploy-rs, ... }:
    let
      forAllSystems = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" ];
    in {
      lib = ./lib.nix;

      nixosConfigurations.server01 = nixpkgs.lib.nixosSystem {
        system = "x86_64-linux";
        modules = [
          disko.nixosModules.disko
          # Make `pkgs.scala-cli-nix` available in configuration.nix so it can
          # callPackage example derivations directly.
          { nixpkgs.overlays = [ self.overlays.default ]; }
          ./hetzner-nixos/configuration.nix
        ];
      };

      overlays.default = final: prev: {
        scala-cli-nix = final.callPackage self.lib { scala-cli = prev.scala-cli; };

        # scala-cli-nix CLI tool (init/lock), built by its own buildScalaCliApp.
        # Exposes both `scala-cli-nix` and the shorter `scn` alias.
        #
        # The CLI shells out to `scala-cli` during `lock` / `init` (for
        # `list-targets` and `export --json`). We pass the path to a
        # kubukoz/scala-cli fork release via SCALA_CLI_NIX_SCALA_CLI because the
        # fork has fixes the lock workflow depends on. This is internal —
        # neither the sandboxed build (`lib.nix`) nor the user's PATH sees the
        # fork.
        #
        # `scala-cli-nix-cli-native-image` is the same CLI built as a GraalVM
        # native image (no JVM at runtime). Slower to build, much faster to
        # start.
        inherit (
          let
            forkScalaCli =
              let
                assets = {
                  "aarch64-darwin" = {
                    asset = "scala-cli-aarch64-apple-darwin.gz";
                    sha256 = "15h6v107jzazhhpx0ljpxn8zbl1k8gr4csshcrcqd8f0crh19mmq";
                  };
                  "x86_64-linux" = {
                    asset = "scala-cli-x86_64-pc-linux.gz";
                    sha256 = "19bj9zqcv0krwmx3m5nr41vafhimf4ccmijlha7pv63yih2vj5sr";
                  };
                };
                asset = assets.${final.stdenv.hostPlatform.system}
                  or (throw "scala-cli fork release has no asset for ${final.stdenv.hostPlatform.system}");
                src = final.fetchurl {
                  url = "https://github.com/kubukoz/scala-cli/releases/download/fork-fee67bb/${asset.asset}";
                  inherit (asset) sha256;
                };
              in (prev.scala-cli.override { jre = prev.jdk; }).overrideAttrs (old: {
                version = "fork-fee67bb";
                inherit src;
              });
            wrapCli = name: base: final.symlinkJoin {
              inherit name;
              paths = [ base ];
              nativeBuildInputs = [ final.makeWrapper ];
              postBuild = ''
                wrapProgram $out/bin/scala-cli-nix \
                  --set-default SCALA_CLI_NIX_SCALA_CLI ${forkScalaCli}/bin/scala-cli
                ln -s scala-cli-nix $out/bin/scn

                # zsh completion: nixpkgs auto-loads files under share/zsh/site-functions
                # via the standard fpath. Covers both scala-cli-nix and the scn alias.
                install -Dm644 ${./cli/_scala-cli-nix} $out/share/zsh/site-functions/_scala-cli-nix
              '';
              # symlinkJoin drops passthru by default; forward the unwrapped
              # derivation's `passthru.tests` so consumers (and our own checks)
              # can run the CLI's munit suite via `nix flake check`.
              passthru = base.passthru or { };
              meta.mainProgram = "scala-cli-nix";
            };
          in {
            scala-cli-nix-cli = wrapCli "scala-cli-nix"
              (final.callPackage ./cli/derivation.nix { });
            scala-cli-nix-cli-native-image = wrapCli "scala-cli-nix-native-image"
              (final.callPackage ./cli/derivation.nix { nativeImage = true; });
          }
        ) scala-cli-nix-cli scala-cli-nix-cli-native-image;
      };

      packages = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
        in {
          default = pkgs.scala-cli-nix-cli;
          inherit (pkgs) scala-cli-nix-cli-native-image;
          # The wrapper is a plain shell script around the `deploy` binary,
          # which deploy-rs ships for every supported system. Exposing it
          # per-system lets `nix run .#deploy-server01` work from darwin too;
          # deploy-rs builds the linux closure (system profile + each app
          # profile) via the local store / configured remote builder.
          deploy-server01 = pkgs.callPackage ./hetzner-nixos/deploy-server01.nix {
            deploy-rs = deploy-rs.packages.${system}.default;
            hostKey = "178.105.118.88 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII/gUJ/hYY4swoEvQTxw7OAGpj3SQxTm9kg7gk7xOgax";
          };
        }
      );

      # deploy-rs configuration. Single `system` profile — the NixOS toplevel
      # owns all services and binaries (including hello-http4s-native and
      # hello-http4s-jvm declared in configuration.nix). `activate.nixos` runs
      # switch-to-configuration on the target, which restarts changed units.
      deploy.nodes.server01 = {
        hostname = "178.105.118.88";
        sshUser = "root";
        user = "root";
        profiles.system.path = deploy-rs.lib.x86_64-linux.activate.nixos
          self.nixosConfigurations.server01;
      };

      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
        in {
          # Used by the top-level .envrc. `withPlugins` bakes the hcloud
          # provider into the tofu wrapper so `tofu init` does not need to
          # fetch it from the registry.
          default = pkgs.mkShellNoCC {
            packages = [
              # `external` and `null` are pulled in by the nixos-anywhere
              # all-in-one module.
              (pkgs.opentofu.withPlugins (p: [ p.hetznercloud_hcloud p.hashicorp_external p.hashicorp_null ]))
              pkgs.nixos-anywhere
              deploy-rs.packages.${system}.default
            ];
          };
        }
      );

      checks = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            overlays = [ self.overlays.default ];
          };
          # Pull `passthru.tests` from every package in `self.packages.<system>`
          # into checks via the library helper — same code path as a generated
          # user flake, so the CLI's munit suite runs under `nix flake check`.
          packageTests = pkgs.scala-cli-nix.collectChecks self.packages.${system};
          example = pkgs.callPackage ./examples/scala3/derivation.nix { };
          example-scala3-subset = pkgs.callPackage ./examples/scala3-subset/derivation.nix { };
          example-scala2 = pkgs.callPackage ./examples/scala2/derivation.nix { };
          example-scala-native = pkgs.callPackage ./examples/scala-native/derivation.nix { };
          example-scala-native-ce = pkgs.callPackage ./examples/scala-native-ce/derivation.nix { };
          example-hello-http4s = pkgs.callPackage ./examples/hello-http4s/derivation.nix { };
          example-scala-native-ce-cross = pkgs.callPackage ./examples/scala-native-ce-cross/derivation.nix { };
          example-scala-resources = pkgs.callPackage ./examples/scala-resources/derivation.nix { };
          example-scala3-native-image = pkgs.callPackage ./examples/scala3-native-image/derivation.nix { };
          example-scala3-assembly = pkgs.callPackage ./examples/scala3-assembly/derivation.nix { };
          example-scala3-shadowed-deps = pkgs.callPackage ./examples/scala3-shadowed-deps/derivation.nix { };
          example-scala3-native-evicted-2_13 = pkgs.callPackage ./examples/scala3-native-evicted-2.13/derivation.nix { };
          example-scala3-cross-platform-version = pkgs.callPackage ./examples/scala3-cross-platform-version/derivation.nix { };
          example-scala-test-weaver = pkgs.callPackage ./examples/scala-test-weaver/derivation.nix { };
          example-scala-test-munit = pkgs.callPackage ./examples/scala-test-munit/derivation.nix { };
          example-scala-test-utest = pkgs.callPackage ./examples/scala-test-utest/derivation.nix { };
          example-scala-test-scalatest = pkgs.callPackage ./examples/scala-test-scalatest/derivation.nix { };
          example-scala-test-ziotest = pkgs.callPackage ./examples/scala-test-ziotest/derivation.nix { };

          # Coursier-app examples (built by `buildCoursierApp` — no scala-cli
          # at build time). `metals` was locked from raw `--dep` coords;
          # `scalafmt` was locked via the default channel
          # (`lock-coords scalafmt`); `smithy4s` was locked via the contrib
          # channel (`lock-coords smithy4s --contrib`).
          example-metals = pkgs.callPackage ./examples/metals/derivation.nix { };
          example-scalafmt = pkgs.callPackage ./examples/scalafmt/derivation.nix { };
          example-smithy4s = pkgs.callPackage ./examples/smithy4s/derivation.nix { };

          # External builds: third-party scala-cli projects packaged here
          # because they don't ship their own flake. Lockfiles are generated
          # via `scn init <github-url>` (or `scn lock --src <path>`) against a
          # pinned upstream revision.
          external-scala-monitor = pkgs.callPackage ./external/scala-monitor/derivation.nix { };

          # Build a runCommand that runs `<pkg>/bin/<binName>` and asserts its
          # stdout equals `expected`. `binName` defaults to the check key,
          # which holds for every example whose pname matches its check name;
          # cross-target checks (e.g. `example-foo-jvm`) pass `binName`
          # explicitly because the underlying binary keeps the unsuffixed
          # pname.
          mkOutputCheck = { name, pkg, expected, binName ? name }:
            pkgs.runCommand "check-${name}" { } ''
              output=$(${pkg}/bin/${binName})
              expected=${nixpkgs.lib.escapeShellArg expected}
              if [ "$output" = "$expected" ]; then
                echo "OK: ${name} output matches"
                touch $out
              else
                echo "FAIL: expected '$expected', got '$output'"
                exit 1
              fi
            '';
        in packageTests // {
          example = mkOutputCheck { name = "example"; pkg = example; expected = "hello world!"; };
          example-scala3-subset = mkOutputCheck { name = "example-scala3-subset"; pkg = example-scala3-subset; expected = "hello from subset!"; };
          example-scala2 = mkOutputCheck { name = "example-scala2"; pkg = example-scala2; expected = "hello from scala 2!"; };
          example-scala-native = mkOutputCheck { name = "example-scala-native"; pkg = example-scala-native; expected = "hello from scala native!"; };
          example-scala-native-test = example-scala-native.passthru.tests.test;
          example-scala-native-ce = mkOutputCheck { name = "example-scala-native-ce"; pkg = example-scala-native-ce; expected = "hello from scala native with cats-effect!"; };
          example-scala-native-ce-cross-jvm = mkOutputCheck { name = "example-scala-native-ce-cross-jvm"; pkg = example-scala-native-ce-cross.jvm; binName = "example-scala-native-ce-cross"; expected = "hello from scala jvm/native with cats-effect!"; };
          example-scala-native-ce-cross-native = mkOutputCheck { name = "example-scala-native-ce-cross-native"; pkg = example-scala-native-ce-cross.native; binName = "example-scala-native-ce-cross"; expected = "hello from scala jvm/native with cats-effect!"; };
          example-scala-resources-jvm = mkOutputCheck { name = "example-scala-resources-jvm"; pkg = example-scala-resources.jvm; binName = "example-scala-resources"; expected = "hello from embedded resource!"; };
          example-scala-resources-native = mkOutputCheck { name = "example-scala-resources-native"; pkg = example-scala-resources.native; binName = "example-scala-resources"; expected = "hello from embedded resource!"; };
          example-scala3-native-image = mkOutputCheck { name = "example-scala3-native-image"; pkg = example-scala3-native-image; expected = "hello from graalvm native image!"; };
          example-scala3-assembly = mkOutputCheck { name = "example-scala3-assembly"; pkg = example-scala3-assembly; expected = "hello from assembly!"; };
          # Regression: scala-java-time transitively pulls
          # portable-scala-reflect_native0.5_2.13, which pins scalalib_native0.5_2.13
          # to 2.13.8+0.5.2. scala-cli's combined resolution at build time picks
          # that pinned version (other paths to scalalib_2.13 are excluded by
          # the newer scala3lib). If the lock generator resolves user libs
          # separately from scala-cli's native runtime deps, it lands on a
          # different scalalib_2.13 winner and `scala-cli package --offline`
          # can't find the JAR. Running the binary verifies the lock matches
          # scala-cli's build-time resolution.
          example-scala3-native-evicted-2_13 = mkOutputCheck { name = "example-scala3-native-evicted-2_13"; pkg = example-scala3-native-evicted-2_13; expected = "hello from evicted-2.13!"; };
          # Regression: a transitive POM (scalatest 3.2.9) declares scala-xml_3:2.0.0;
          # if that JAR ends up on the runtime classpath alongside our 2.4.0
          # winner, the binary throws NoSuchMethodError on `Node.child()` (the
          # signature changed between 2.x). Running the binary verifies the
          # classpath only carries the resolved winner.
          example-scala3-shadowed-deps = mkOutputCheck { name = "example-scala3-shadowed-deps"; pkg = example-scala3-shadowed-deps; expected = "hello from shadowed-deps! grandchildren=2"; };
          # Coursier-app checks. Each app is exposed twice: the package
          # itself (so `nix flake check` reports a build failure distinctly
          # from a runtime failure) and a `-test` smoke test that launches
          # the wrapper on a benign flag. Metals' `--help` is empty (LSP
          # server first, CLI second) but still exits 0; scalafmt prints
          # "scalafmt <version>"; smithy4s' bare invocation prints a
          # Decline usage banner we grep for.
          inherit example-metals example-scalafmt example-smithy4s external-scala-monitor;
          example-hello-http4s-jvm = example-hello-http4s.jvm;
          example-hello-http4s-native = example-hello-http4s.native;
          example-metals-test = pkgs.runCommand "check-example-metals" { } ''
            ${example-metals}/bin/metals --help > /dev/null
            echo "OK: metals --help launched"
            touch $out
          '';
          example-scalafmt-test = pkgs.runCommand "check-example-scalafmt" { } ''
            output=$(${example-scalafmt}/bin/scalafmt --version)
            case "$output" in
              "scalafmt 3.11.1") echo "OK: scalafmt version $output"; touch $out ;;
              *) echo "FAIL: unexpected scalafmt --version output: $output"; exit 1 ;;
            esac
          '';
          example-smithy4s-test = pkgs.runCommand "check-example-smithy4s" { } ''
            # smithy4s-codegen-cli prints a Decline usage summary on bare
            # invocation (no args). Exit code is 1 but the wrapper itself
            # had to launch successfully for us to see the usage banner.
            output=$(${example-smithy4s}/bin/smithy4s 2>&1 || true)
            case "$output" in
              *"smithy4s generate"*) echo "OK: smithy4s usage banner printed"; touch $out ;;
              *) echo "FAIL: unexpected smithy4s output:"; echo "$output"; exit 1 ;;
            esac
          '';
          external-scala-monitor-test = pkgs.runCommand "check-external-scala-monitor" { } ''
            # mainargs prints a usage banner with the registered flags on --help.
            # The binary may exit non-zero on --help (mainargs convention), so we
            # check the output content rather than the exit code.
            output=$(${external-scala-monitor}/bin/scala-monitor --help 2>&1 || true)
            case "$output" in
              *"Output format"*) echo "OK: scala-monitor --help launched"; touch $out ;;
              *) echo "FAIL: unexpected scala-monitor --help output:"; echo "$output"; exit 1 ;;
            esac
          '';
          external-scala-monitor-test-scope = external-scala-monitor.passthru.tests.test;
        } // (
          # Cross JVM+Native test-framework examples. Each framework gets its
          # own example built for both platforms; for every (framework,
          # platform) pair we register two checks: a binary-output check
          # (proves the main app links and runs) and a passthru test check
          # (proves the test framework actually runs under
          # `scala-cli test --offline --server=false`).
          let
            frameworks = [
              { name = "weaver"; expected = "hello from weaver test framework!"; }
              { name = "munit"; expected = "hello from munit test framework!"; }
              { name = "utest"; expected = "hello from utest test framework!"; }
              { name = "scalatest"; expected = "hello from scalatest test framework!"; }
              { name = "ziotest"; expected = "hello from zio-test test framework!"; }
            ];
            pkgFor = fw: ({
              weaver = example-scala-test-weaver;
              munit = example-scala-test-munit;
              utest = example-scala-test-utest;
              scalatest = example-scala-test-scalatest;
              ziotest = example-scala-test-ziotest;
            }).${fw.name};
            targets = [ "jvm" "native" ];
            entries = nixpkgs.lib.flatten (builtins.map
              (fw: builtins.map (t:
                let
                  apps = pkgFor fw;
                  pkg = apps.${t};
                  base = "example-scala-test-${fw.name}";
                  binName = base;
                in [
                  { name = "${base}-${t}"; value = mkOutputCheck { name = "${base}-${t}"; inherit pkg binName; inherit (fw) expected; }; }
                  { name = "${base}-${t}-test"; value = pkg.passthru.tests.test; }
                ])
                targets)
              frameworks);
          in nixpkgs.lib.listToAttrs entries
        ) // nixpkgs.lib.optionalAttrs pkgs.stdenv.isLinux (
          # Docker examples: thin derivations that wrap an existing example's
          # native binary (or JVM wrapper) into a `dockerTools.buildLayeredImage`
          # output. The images build on any Linux platform; only the per-image
          # VM tests (`docker-image-*`) are gated further to x86_64-linux because
          # `pkgs.testers.runNixOSTest` needs a KVM-capable builder of the same
          # arch, and we only count on that for x86_64 in CI.
          let
            example-scala-native-docker = pkgs.callPackage ./examples/scala-native-docker/derivation.nix {
              inherit example-scala-native;
            };
            example-scala3-jvm-docker = pkgs.callPackage ./examples/scala3-jvm-docker/derivation.nix {
              inherit example;
            };
            example-scala3-native-image-docker = pkgs.callPackage ./examples/scala3-native-image-docker/derivation.nix {
              inherit example-scala3-native-image;
            };
            mkDockerImageTest = { name, pkg, expected }: pkgs.testers.runNixOSTest {
              inherit name;
              nodes.machine = { ... }: {
                virtualisation.docker.enable = true;
                # Shared headroom sized for the largest image (JVM, with
                # openjdk-21 alone ~300 MB) extracted into docker's overlayfs
                # snapshot dir. Smaller images don't need this much, but a
                # single shared value keeps the helper simple.
                virtualisation.diskSize = 8192;
              };
              testScript = ''
                machine.wait_for_unit("docker.service")
                machine.succeed("docker load < ${pkg}")
                output = machine.succeed("docker run --rm ${pkg.imageName}:${pkg.imageTag}").strip()
                assert output == ${builtins.toJSON expected}, f"got {output!r}"
              '';
            };
          in {
            inherit example-scala-native-docker example-scala3-jvm-docker example-scala3-native-image-docker;
          } // nixpkgs.lib.optionalAttrs (system == "x86_64-linux") {
            docker-image-scala-native = mkDockerImageTest {
              name = "scala-cli-nix-docker-image-scala-native";
              pkg = example-scala-native-docker;
              expected = "hello from scala native!";
            };
            docker-image-scala3-jvm = mkDockerImageTest {
              name = "scala-cli-nix-docker-image-scala3-jvm";
              pkg = example-scala3-jvm-docker;
              expected = "hello world!";
            };
            docker-image-scala3-native-image = mkDockerImageTest {
              name = "scala-cli-nix-docker-image-scala3-native-image";
              pkg = example-scala3-native-image-docker;
              expected = "hello from graalvm native image!";
            };
            # NixOS-container example: boots a host VM that declares a
            # `containers.hello-http4s` (systemd-nspawn) running the JVM
            # hello-http4s app, then curls the forwarded host port.
            example-hello-http4s-nixos-container =
              pkgs.callPackage ./examples/hello-http4s-nixos-container/derivation.nix {
                inherit example-hello-http4s;
              };
          }
        ) // nixpkgs.lib.listToAttrs (builtins.map
          # 4-target matrix (JVM/Native × Scala 3.3.4/3.6.4). Each target
          # produces its own derivation (keyed `<platform>-<version>` per
          # lib.nix's nixKey), so building all four exercises the
          # platform×version key-naming path. The binaries print a single
          # shared greeting — distinguishability comes from the derivation
          # keys, not the output.
          (key: {
            name = "example-scala3-cross-platform-version-${key}";
            value = mkOutputCheck {
              name = "example-scala3-cross-platform-version-${key}";
              pkg = example-scala3-cross-platform-version."${key}";
              binName = "example-scala3-cross-platform-version";
              expected = "hello from cross-platform-version!";
            };
          })
          [ "jvm-3_3_4" "jvm-3_6_4" "native-3_3_4" "native-3_6_4" ])
      );
    };
}

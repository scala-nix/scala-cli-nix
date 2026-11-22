{ scala-cli-nix }:

# Multi-module: `module = "app"` selects which sbt project to build. `app`
# dependsOn `core`, so the build packages both and puts both jars on the
# runtime classpath. `app`'s test scope lands in passthru.tests.
scala-cli-nix.buildSbtApp {
  pname = "example-sbt-multi";
  version = "0.1.0";
  src = ./.;
  module = "app";
  lockFile = ./scala.lock.json;
}

{ scala-cli-nix }:

scala-cli-nix.buildSbtApp {
  pname = "example-sbt";
  version = "0.1.0";
  src = ./.;
  lockFile = ./scala.lock.json;
}

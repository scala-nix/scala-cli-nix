# Pre-wired demo deployment of the cross JVM+Native http4s example
# (examples/hello-http4s) on top of the generic `services.http-apps` module.
#
# This is the dogfooding showcase scala-cli-nix used to wire directly in its
# server01 configuration.nix. It is exposed as `nixosModules.demo-apps` so any
# host can enable the whole demo with a single `services.demo-apps.enable` —
# the host just has to apply scala-cli-nix's overlay (the example derivations
# resolve `pkgs.scala-cli-nix` from it).
#
# It runs the *same* hello-http4s binary three ways, each behind its own caddy
# vhost, to exercise every `http-apps` deployment mode:
#   - host systemd unit (native + jvm),
#   - declarative nixos-container (native),
#   - oci-containers / dockerd (native, from a locally-built image).
{ config, lib, pkgs, ... }:

let
  cfg = config.services.demo-apps;

  hello-http4s = pkgs.callPackage ../examples/hello-http4s/derivation.nix { };
  hello-http4s-docker = pkgs.callPackage ../examples/hello-http4s-docker/derivation.nix {
    example-hello-http4s = hello-http4s;
  };
in
{
  imports = [ ./http-apps.nix ];

  options.services.demo-apps = {
    enable = lib.mkEnableOption "the scala-cli-nix hello-http4s demo deployment";

    baseDomain = lib.mkOption {
      type = lib.types.str;
      example = "scala-cli-nix.example.com";
      description = ''
        Base domain for the demo vhosts. Each app is served at
        `<app>.<baseDomain>` (e.g. `hello-native.<baseDomain>`).
      '';
    };
  };

  config = lib.mkIf cfg.enable {
    services.http-apps = {
      hello-native = {
        package = hello-http4s.native;
        domain = "hello-native.${cfg.baseDomain}";
        port = 8080;
        environment.PLATFORM = "native";
      };
      hello-jvm = {
        package = hello-http4s.jvm;
        domain = "hello-jvm.${cfg.baseDomain}";
        port = 8081;
        environment.PLATFORM = "jvm";
      };
      # Same native binary as `hello-native`, but run inside a declarative
      # nixos-container (systemd-nspawn) instead of directly on the host —
      # demonstrates the `container.enable` path in http-apps.
      hello-native-container = {
        package = hello-http4s.native;
        domain = "hello-native-container.${cfg.baseDomain}";
        port = 8082;
        environment.PLATFORM = "native";
        container.enable = true;
      };
      # Third deployment style: the native binary baked into an OCI image
      # and managed by `virtualisation.oci-containers` (dockerd).
      hello-native-docker = {
        domain = "hello-native-docker.${cfg.baseDomain}";
        port = 8083;
        environment.PLATFORM = "native";
        docker.image = "${hello-http4s-docker.imageName}:${hello-http4s-docker.imageTag}";
        docker.imageFile = hello-http4s-docker;
      };
    };
  };
}

{ config, lib, ... }:

let
  cfg = config.services.http-apps;

  appOpts = { name, ... }: {
    options = {
      package = lib.mkOption {
        type = lib.types.nullOr lib.types.package;
        default = null;
        description = ''
          Derivation that provides the HTTP server binary. The module invokes
          `lib.getExe`, so the package must set `meta.mainProgram` (every
          derivation produced by `buildScalaCliApp(s)` does). Required for the
          host and `container.enable` deployment modes; ignored when
          `docker.image` is set.
        '';
      };

      domain = lib.mkOption {
        type = lib.types.str;
        example = "myapp.example.com";
        description = "Public hostname served by caddy; reverse-proxied to 127.0.0.1:<port>.";
      };

      port = lib.mkOption {
        type = lib.types.port;
        description = "Loopback TCP port the app listens on. Exposed to the unit as $PORT.";
      };

      environment = lib.mkOption {
        type = lib.types.attrsOf lib.types.str;
        default = { };
        description = "Extra environment variables for the systemd unit.";
      };

      container = lib.mkOption {
        type = lib.types.submodule {
          options.enable = lib.mkOption {
            type = lib.types.bool;
            default = false;
            description = ''
              When true, the app runs inside a declarative nixos-container
              (systemd-nspawn) on its own private veth pair instead of
              directly on the host. Caddy still reverse-proxies to
              127.0.0.1:<port>, which the container forwards into the guest.
            '';
          };
        };
        default = { };
        description = "Optional nixos-container wrapper for this app.";
      };

      docker = lib.mkOption {
        type = lib.types.submodule {
          options.image = lib.mkOption {
            type = lib.types.nullOr lib.types.str;
            default = null;
            example = "example-hello-http4s-docker:0.1.0";
            description = ''
              OCI image reference (`name:tag`) to run. When set, the app is
              deployed via `virtualisation.oci-containers` (NixOS-managed
              docker unit) instead of as a host systemd unit. Mutually
              exclusive with `container.enable`.
            '';
          };
          options.imageFile = lib.mkOption {
            type = lib.types.nullOr lib.types.package;
            default = null;
            description = ''
              Locally-built image tarball (e.g. produced by
              `dockerTools.buildLayeredImage`) loaded into dockerd before the
              container starts. Use this when the image isn't pushed to a
              registry the host can pull from.
            '';
          };
        };
        default = { };
        description = "Optional docker (oci-containers) deployment for this app.";
      };
    };
  };

  # Stable index for each containerised app, used to pick a non-overlapping
  # /30 from 192.168.<subnetBase>.x. `lib.attrNames` returns keys in sorted
  # order, so the assignment is deterministic across evaluations.
  subnetBase = 100;
  containerApps = lib.filterAttrs (_: app: app.container.enable) cfg;
  containerIndex = name:
    lib.lists.findFirstIndex (n: n == name) null (lib.attrNames containerApps);
  hostAddressFor = name: "192.168.${toString subnetBase}.${toString (containerIndex name * 4 + 1)}";
  localAddressFor = name: "192.168.${toString subnetBase}.${toString (containerIndex name * 4 + 2)}";

  unitFor = name: app: {
    description = "http-app ${name}";
    wantedBy = [ "multi-user.target" ];
    after = [ "network.target" ];
    environment = app.environment // { PORT = toString app.port; };
    serviceConfig = {
      ExecStart = lib.getExe app.package;
      Restart = "on-failure";
      RestartSec = "10s";
      DynamicUser = true;
    };
  };

  isDocker = app: app.docker.image != null;
  isContainer = app: app.container.enable;

  hostApps = lib.filterAttrs (_: app: !isContainer app && !isDocker app) cfg;
  dockerApps = lib.filterAttrs (_: isDocker) cfg;
in
{
  options.services.http-apps = lib.mkOption {
    type = lib.types.attrsOf (lib.types.submodule appOpts);
    default = { };
    description = ''
      Declarative HTTP applications. Each entry is exposed via caddy on the
      configured domain (reverse-proxied to 127.0.0.1:<port>) and runs in one
      of three modes:

      - Default: a `DynamicUser` systemd unit on the host running `package`.
      - `container.enable = true`: the same unit wrapped in a declarative
        nixos-container (systemd-nspawn) on a private veth pair, with the
        listen port forwarded to the host.
      - `docker.image = "name:tag"`: managed by `virtualisation.oci-containers`
        (NixOS-generated `docker-<name>` unit). Locally-built images can be
        loaded automatically via `docker.imageFile`.

      Caddy and dockerd are enabled automatically when at least one app needs
      them.
    '';
  };

  config = lib.mkIf (cfg != { }) {
    assertions = lib.mapAttrsToList (name: app: {
      assertion = !(isContainer app && isDocker app);
      message = "services.http-apps.${name}: container.enable and docker.image are mutually exclusive.";
    }) cfg ++ lib.mapAttrsToList (name: app: {
      assertion = isDocker app || app.package != null;
      message = "services.http-apps.${name}: `package` is required unless `docker.image` is set.";
    }) cfg;

    # Host-level systemd units for the default deployment mode.
    systemd.services = lib.mapAttrs' (name: app: {
      name = "http-app-${name}";
      value = unitFor name app;
    }) hostApps;

    # Per-containerised-app: a nixos-container running the same unit
    # inside the guest. The forwarded port lands on 127.0.0.1:<port>,
    # which caddy reverse-proxies to identically to the host case.
    containers = lib.mapAttrs (name: app: {
      autoStart = true;
      privateNetwork = true;
      hostAddress = hostAddressFor name;
      localAddress = localAddressFor name;
      forwardPorts = [{
        hostPort = app.port;
        containerPort = app.port;
        protocol = "tcp";
      }];
      config = { ... }: {
        systemd.services."http-app-${name}" = unitFor name app;
        networking.firewall.allowedTCPPorts = [ app.port ];
        system.stateVersion = "24.11";
      };
    }) containerApps;

    # Docker deployment via NixOS-managed oci-containers. We bind the
    # container's port back to 127.0.0.1:<port> so caddy's reverse_proxy
    # line is identical to the other modes. `imageFile`, when set, makes
    # the unit `docker load` the tarball before `docker run`.
    virtualisation.oci-containers.containers = lib.mapAttrs (_name: app: {
      image = app.docker.image;
      imageFile = app.docker.imageFile;
      ports = [ "127.0.0.1:${toString app.port}:${toString app.port}" ];
      environment = app.environment // { PORT = toString app.port; };
    }) dockerApps;

    # oci-containers needs a backend; dockerd is the default. Only enable
    # it if at least one app actually uses docker.
    virtualisation.docker.enable = lib.mkIf (dockerApps != { }) true;

    services.caddy = {
      enable = true;
      virtualHosts = lib.mapAttrs' (_name: app: {
        name = app.domain;
        value.extraConfig = ''
          reverse_proxy 127.0.0.1:${toString app.port}
        '';
      }) cfg;
    };

    networking.firewall.allowedTCPPorts = [ 80 443 ];
  };
}

{ config, lib, ... }:

let
  cfg = config.services.http-apps;

  appOpts = { name, ... }: {
    options = {
      package = lib.mkOption {
        type = lib.types.package;
        description = ''
          Derivation that provides the HTTP server binary. The module invokes
          `lib.getExe`, so the package must set `meta.mainProgram` (every
          derivation produced by `buildScalaCliApp(s)` does).
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

  hostApps = lib.filterAttrs (_: app: !app.container.enable) cfg;
in
{
  options.services.http-apps = lib.mkOption {
    type = lib.types.attrsOf (lib.types.submodule appOpts);
    default = { };
    description = ''
      Declarative HTTP applications. Each entry runs as a `DynamicUser` systemd
      unit bound to a loopback port and is fronted by caddy on the configured
      domain. Caddy is enabled automatically when at least one app is declared.

      Setting `container.enable = true` on an entry wraps the unit in a
      declarative nixos-container instead; the listening port is forwarded
      from the container to the host so the caddy reverse-proxy line stays
      identical.
    '';
  };

  config = lib.mkIf (cfg != { }) {
    # Host-level systemd units for the non-containerised apps.
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

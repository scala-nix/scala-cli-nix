{ package, hostPort ? 8080, containerPort ? 8080 }:

{ lib, ... }:

{
  # Declarative nixos-container (systemd-nspawn). The container gets its own
  # private network namespace; we expose `containerPort` on the host as
  # `hostPort` so callers on the host can reach the JVM app at
  # http://127.0.0.1:<hostPort>.
  containers.hello-http4s = {
    autoStart = true;
    privateNetwork = true;
    hostAddress = "192.168.100.1";
    localAddress = "192.168.100.2";
    forwardPorts = [{
      hostPort = hostPort;
      containerPort = containerPort;
      protocol = "tcp";
    }];

    config = { ... }: {
      systemd.services.hello-http4s = {
        description = "hello-http4s JVM";
        wantedBy = [ "multi-user.target" ];
        after = [ "network.target" ];
        environment = {
          PLATFORM = "jvm";
          PORT = toString containerPort;
        };
        serviceConfig = {
          ExecStart = lib.getExe package;
          Restart = "on-failure";
          RestartSec = "10s";
          DynamicUser = true;
        };
      };

      networking.firewall.allowedTCPPorts = [ containerPort ];
      system.stateVersion = "24.11";
    };
  };
}

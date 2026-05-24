{ testers, example-hello-http4s, curl }:

let
  port = 8080;
  domain = "hello-native-container.test";
in
testers.runNixOSTest {
  name = "scala-cli-nix-hello-http4s-nixos-container";

  nodes.machine = { ... }: {
    imports = [ ../../hetzner-nixos/modules/http-apps.nix ];

    services.http-apps.hello-native-container = {
      package = example-hello-http4s.native;
      inherit domain;
      inherit port;
      environment.PLATFORM = "native";
      container.enable = true;
    };

    # Caddy resolves the vhost via /etc/hosts so the test can curl
    # the domain end-to-end (reverse-proxy -> forwarded port -> guest).
    networking.hosts."127.0.0.1" = [ domain ];

    environment.systemPackages = [ curl ];

    # The native binary is small, but the container still boots a full
    # NixOS userland — give the host VM modest headroom.
    virtualisation.memorySize = 1024;
    virtualisation.diskSize = 4096;
  };

  testScript = ''
    machine.wait_for_unit("container@hello-native-container.service")
    machine.wait_for_unit("caddy.service")
    machine.wait_until_succeeds(
      "curl --fail --silent http://${domain}/ > /tmp/out",
      timeout=120,
    )
    output = machine.succeed("cat /tmp/out").strip()
    assert output == "hello from http4s native!", f"got {output!r}"
  '';
}

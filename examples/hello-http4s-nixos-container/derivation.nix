{ testers, example-hello-http4s, curl }:

let
  port = 8080;
  domain = "hello-jvm-container.test";
in
testers.runNixOSTest {
  name = "scala-cli-nix-hello-http4s-nixos-container";

  nodes.machine = { ... }: {
    imports = [ ../../hetzner-nixos/modules/http-apps.nix ];

    services.http-apps.hello-jvm-container = {
      package = example-hello-http4s.jvm;
      inherit domain;
      inherit port;
      environment.PLATFORM = "jvm";
      container.enable = true;
    };

    # Caddy resolves the vhost via /etc/hosts so the test can curl
    # the domain end-to-end (reverse-proxy -> forwarded port -> guest).
    networking.hosts."127.0.0.1" = [ domain ];

    environment.systemPackages = [ curl ];

    # NixOS containers boot a full guest userland; give the host VM
    # headroom for the JRE running inside.
    virtualisation.memorySize = 2048;
    virtualisation.diskSize = 4096;
  };

  testScript = ''
    machine.wait_for_unit("container@hello-jvm-container.service")
    machine.wait_for_unit("caddy.service")
    machine.wait_until_succeeds(
      "curl --fail --silent http://${domain}/ > /tmp/out",
      timeout=120,
    )
    output = machine.succeed("cat /tmp/out").strip()
    assert output == "hello from http4s jvm!", f"got {output!r}"
  '';
}

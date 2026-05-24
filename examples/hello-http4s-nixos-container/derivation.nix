{ testers, example-hello-http4s, curl }:

let
  hostPort = 8080;
  containerModule = import ./module.nix {
    package = example-hello-http4s.jvm;
    inherit hostPort;
    containerPort = 8080;
  };
in
testers.runNixOSTest {
  name = "scala-cli-nix-hello-http4s-nixos-container";

  nodes.machine = { ... }: {
    imports = [ containerModule ];

    # Curl is used from the host to probe the forwarded container port.
    environment.systemPackages = [ curl ];

    # NixOS containers boot a full guest userland; give the host VM
    # headroom for the JRE running inside.
    virtualisation.memorySize = 2048;
    virtualisation.diskSize = 4096;
  };

  testScript = ''
    machine.wait_for_unit("container@hello-http4s.service")
    # The JVM app takes a moment to bind; wait until the forwarded port
    # actually accepts connections.
    machine.wait_until_succeeds(
      "curl --fail --silent http://127.0.0.1:${toString hostPort}/ > /tmp/out",
      timeout=120,
    )
    output = machine.succeed("cat /tmp/out").strip()
    assert output == "hello from http4s jvm!", f"got {output!r}"
  '';
}

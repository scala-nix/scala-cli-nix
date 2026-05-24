{ dockerTools, cacert, lib, example-hello-http4s }:

# Layered image wrapping hello-http4s.native. The image is intentionally
# minimal: just the native binary (with s2n-tls on its RPATH) plus the
# system CA bundle so TLS works out of the box.
dockerTools.buildLayeredImage {
  name = "example-hello-http4s-docker";
  tag = example-hello-http4s.native.version;
  contents = [ cacert ];
  config = {
    Cmd = [ (lib.getExe example-hello-http4s.native) ];
    Env = [
      "PLATFORM=native"
      "PORT=8080"
    ];
    ExposedPorts."8080/tcp" = { };
  };
}

{ dockerTools, cacert, lib, example-hello-http4s }:

# Layered image wrapping hello-http4s.jvm. The image is intentionally
# minimal: just the JVM wrapper + per-artifact JARs (via dockerTools'
# closure tracing) and the system CA bundle so the JRE can do TLS.
dockerTools.buildLayeredImage {
  name = "example-hello-http4s-docker";
  tag = example-hello-http4s.jvm.version;
  contents = [ cacert ];
  config = {
    Cmd = [ (lib.getExe example-hello-http4s.jvm) ];
    Env = [
      "PLATFORM=jvm"
      "PORT=8080"
    ];
    ExposedPorts."8080/tcp" = { };
  };
}

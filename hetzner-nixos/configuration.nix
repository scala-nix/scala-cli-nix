{
  modulesPath,
  lib,
  pkgs,
  ...
}:
let
  hello-http4s = pkgs.callPackage ../examples/hello-http4s/derivation.nix { };

  # Same hello-http4s.jvm binary as `services.http-apps.hello-jvm`, but
  # wrapped in a declarative nixos-container (systemd-nspawn). Caddy then
  # reverse-proxies the forwarded host port. The module is shared with
  # examples/hello-http4s-nixos-container.
  hello-jvm-container-port = 8082;
  hello-jvm-container-module = import ../examples/hello-http4s-nixos-container/module.nix {
    package = hello-http4s.jvm;
    hostPort = hello-jvm-container-port;
    containerPort = 8080;
  };
in
{
  imports = [
    (modulesPath + "/profiles/qemu-guest.nix")
    ./modules/http-apps.nix
    hello-jvm-container-module
  ];

  services.http-apps = {
    hello-native = {
      package = hello-http4s.native;
      domain = "hello-native.scala-cli-nix.kubukoz.com";
      port = 8080;
      environment.PLATFORM = "native";
    };
    hello-jvm = {
      package = hello-http4s.jvm;
      domain = "hello-jvm.scala-cli-nix.kubukoz.com";
      port = 8081;
      environment.PLATFORM = "jvm";
    };
  };

  # Caddy vhost for the containerised JVM app. It only needs the
  # reverse-proxy line — `services.http-apps` already enables caddy and
  # opens 80/443.
  services.caddy.virtualHosts."hello-jvm-container.scala-cli-nix.kubukoz.com".extraConfig = ''
    reverse_proxy 127.0.0.1:${toString hello-jvm-container-port}
  '';

  # Hetzner cloud cx-series boots in BIOS mode (not UEFI). Disko needs a
  # 1MiB bios_grub partition for GRUB's stage 1.5 to live in on a GPT disk,
  # and the bootloader has to be GRUB targeting the whole disk.
  disko.devices.disk.main = {
    type = "disk";
    device = "/dev/sda";
    content = {
      type = "gpt";
      partitions = {
        boot = {
          size = "1M";
          type = "EF02"; # BIOS boot partition
        };
        root = {
          size = "100%";
          content = {
            type = "filesystem";
            format = "ext4";
            mountpoint = "/";
          };
        };
      };
    };
  };

  # `device` is provided by disko's GRUB integration based on disk.main.
  boot.loader.grub = {
    enable = true;
    efiSupport = false;
  };

  networking.hostName = "server01";
  networking.useDHCP = lib.mkDefault true;

  nix.settings.experimental-features = [
    "nix-command"
    "flakes"
  ];

  services.openssh.enable = true;
  services.openssh.settings.PasswordAuthentication = false;
  services.openssh.settings.PermitRootLogin = "prohibit-password";

  users.users.root.openssh.authorizedKeys.keys = [
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJbf71nFwkbLYlyceqJe35I4rHVc/8apmenfSQPVVzxF kubukoz@kubukoz-max.local"
    # nix-ci deploy key (see nix-ci.nix deploy.server01).
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKtOMFEJkH37S3sHD3WS9XScOyx1b2noFgQ4edrxOcxE nix-ci@scala-cli-nix"
  ];

  environment.systemPackages = with pkgs; [
    git
    neovim
    sysz
    duf
    dua
  ];

  system.stateVersion = "24.11";
}

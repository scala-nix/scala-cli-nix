{
  modulesPath,
  lib,
  pkgs,
  ...
}:
let
  hello-http4s = pkgs.callPackage ../examples/hello-http4s/derivation.nix { };
in
{
  imports = [
    (modulesPath + "/profiles/qemu-guest.nix")
    ./modules/http-apps.nix
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
    # Same JVM binary as `hello-jvm`, but run inside a declarative
    # nixos-container (systemd-nspawn) instead of directly on the host —
    # demonstrates the `container.enable` path in http-apps.
    hello-jvm-container = {
      package = hello-http4s.jvm;
      domain = "hello-jvm-container.scala-cli-nix.kubukoz.com";
      port = 8082;
      environment.PLATFORM = "jvm";
      container.enable = true;
    };
  };

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

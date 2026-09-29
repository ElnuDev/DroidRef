{
  description = "DroidRef - reference image viewer for Android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        inherit (pkgs) lib;

        # Must match compileSdk / buildToolsVersion in app/ and sticker/build.gradle.
        platformVersion = "37.0";
        buildToolsVersion = "37.0.0";
        # The emulator image lags compileSdk: SurfaceFlinger in the API 37 image
        # asserts on a host GPU feature (ReadColorBufferDma) that software
        # rendering lacks. x86_64 so it runs natively under KVM.
        emulatorPlatformVersion = "36";
        emulatorAbi = "x86_64";
        emulatorImageType = "google_apis";

        jdk = pkgs.jdk21;
        # Keep in sync with gradle/wrapper/gradle-wrapper.properties.
        gradle = pkgs.gradle_9.override { java = jdk; };

        # androidenv adds a system image for every listed platform; hide the
        # compileSdk ones so only the emulator's image (several GB) is fetched.
        androidRepo =
          let
            repo = lib.importJSON "${pkgs.path}/pkgs/development/mobile/androidenv/repo.json";
          in
          repo // { images = removeAttrs repo.images [ platformVersion ]; };

        # Just enough to build the APK, so `nix build` skips the emulator.
        buildSdk =
          (pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ platformVersion ];
            buildToolsVersions = [ buildToolsVersion ];
            includeCmake = false;
          }).androidsdk;

        androidSdk =
          (pkgs.androidenv.composeAndroidPackages {
            repo = androidRepo;
            platformVersions = [
              platformVersion
              emulatorPlatformVersion
            ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = [ emulatorImageType ];
            abiVersions = [ emulatorAbi ];
            includeCmake = false;
          }).androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";
        buildSdkRoot = "${buildSdk}/libexec/android-sdk";

        # AGP downloads a generic-Linux aapt2 from Maven, which can't run on
        # NixOS; point it at the patched one from the SDK instead.
        aapt2 = "${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";

        apk = pkgs.stdenv.mkDerivation (finalAttrs: {
          pname = "droidref";
          version = "1.1.0";

          src = lib.fileset.toSource {
            root = ./.;
            fileset =
              lib.fileset.difference
                (lib.fileset.unions [
                  ./app
                  ./sticker
                  ./build.gradle
                  ./settings.gradle
                  ./gradle.properties
                ])
                (
                  lib.fileset.unions [
                    # Local Gradle output from the dev shell.
                    (lib.fileset.maybeMissing ./app/build)
                    (lib.fileset.maybeMissing ./sticker/build)
                  ]
                );
          };

          nativeBuildInputs = [
            gradle
            jdk
          ];

          # Regenerate with:
          #   $(nix build .#apk.mitmCache.updateScript --print-out-paths --no-link)
          mitmCache = gradle.fetchDeps {
            pkg = finalAttrs.finalPackage;
            data = ./deps.json;
          };
          # Record from the real build: the generic nixDownloadDeps task trips
          # over AGP's ambiguous variants, and AGP resolves some deps lazily.
          gradleUpdateTask = "assembleDebug";
          gradleBuildTask = "assembleDebug";

          env = {
            JAVA_HOME = jdk.home;
            ANDROID_HOME = buildSdkRoot;
            ANDROID_SDK_ROOT = buildSdkRoot;
          };

          gradleFlags = [
            "-Pandroid.aapt2FromMavenOverride=${buildSdkRoot}/build-tools/${buildToolsVersion}/aapt2"
            "-Dorg.gradle.java.home=${jdk.home}"
          ];

          # AGP writes the debug signing key under the Android user home.
          preConfigure = ''
            export ANDROID_USER_HOME="$(mktemp -d)"
          '';

          doCheck = false;

          installPhase = ''
            runHook preInstall
            install -Dm644 app/build/outputs/apk/debug/app-debug.apk $out/droidref.apk
            runHook postInstall
          '';

          meta = {
            description = "Reference image viewer for Android (debug-signed APK)";
            homepage = "https://github.com/ElnuDev/DroidRef";
            license = lib.licenses.gpl3Only;
            platforms = [ system ];
          };
        });

        # Boots an emulator, installs the APK and launches it.
        emulator = pkgs.androidenv.emulateApp {
          name = "droidref-emulator";
          app = apk;
          platformVersion = emulatorPlatformVersion;
          abiVersion = emulatorAbi;
          systemImageType = emulatorImageType;
          package = "xyz.ruin.droidref";
          activity = ".MainActivity";
          sdkExtraArgs = {
            buildToolsVersions = [ buildToolsVersion ];
            includeCmake = false;
          };
          # Reuse one AVD across runs; by default emulateApp makes a fresh
          # multi-GB one under $TMPDIR every time and never cleans it up.
          # (Expanded at runtime, not by Nix.) Delete this directory to apply
          # changes to configOptions below.
          androidUserHome = "\${XDG_STATE_HOME:-$HOME/.local/state}/droidref-emulator";
          deviceName = "droidref";
          # Without a device profile avdmanager defaults to a 320x640 screen and
          # 96 MB of RAM, which can't boot modern Android. (The avdmanager
          # emulateApp pins is too old to know current Pixel profiles.)
          configOptions = {
            "hw.ramSize" = "4096";
            "hw.lcd.width" = "1080";
            "hw.lcd.height" = "2400";
            "hw.lcd.density" = "420";
            "hw.cpu.ncore" = "4";
            # The default 800 MB /data fills up during first boot.
            "disk.dataPartition.size" = "4G";
            "hw.gpu.enabled" = "yes";
            # The emulator's bundled GL can't use NixOS's host drivers, so
            # render in software (ANGLE on SwiftShader).
            "hw.gpu.mode" = "swangle";
            "hw.keyboard" = "yes";
          };
        };
      in
      {
        packages = {
          inherit apk emulator;
          default = apk;
        };

        apps = {
          emulator = {
            type = "app";
            program = lib.getExe emulator;
          };
          default = self.apps.${system}.emulator;
        };

        devShells.default = pkgs.mkShell {
          packages = [
            androidSdk
            gradle
            jdk
            pkgs.kotlin-language-server
            pkgs.scrcpy # mirror/control a physical device or emulator
          ];

          JAVA_HOME = jdk.home;
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";

          shellHook = ''
            export PATH="${sdkRoot}/emulator:${sdkRoot}/platform-tools:${sdkRoot}/build-tools/${buildToolsVersion}:$PATH"

            # Keep AVDs and debug keys out of the Nix store.
            export ANDROID_USER_HOME="''${ANDROID_USER_HOME:-$HOME/.android}"
            export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"

            if [ ! -e "$ANDROID_AVD_HOME/droidref.avd" ]; then
              echo "Create an emulator with:"
              echo "  avdmanager create avd -n droidref -k 'system-images;android-${emulatorPlatformVersion};${emulatorImageType};${emulatorAbi}'"
            fi
          '';
        };
      }
    );
}

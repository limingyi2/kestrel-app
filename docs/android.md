# The Android app

Kestrel is a local-first Android agent built on the OpenMinis 1.13 GPL-3.0 app. Its chat history,
agent data, provider configuration and credentials stay on the device. Kestrel does not require an
account and the Android app does not request nanoMuse Cloud account, allowance, profile or chat-sync
APIs. A provider may be configured with an API key or one of the authentication methods that
provider supports. Provider credentials are stored by the Android app and are sent only to the
configured provider when it handles a request.

The app keeps the upstream `com.openminis.app` Kotlin package and its Linux sandbox contracts.
Kestrel code lives under `io.github.nanomuse.*`; changes inside upstream files carry a
`// nanoMuse:` comment. The app id is `io.github.nanomuse.app`. The scheme the system receives is
`nanomuse://`; in-app links retain upstream's `minis://` names.

## Install and first run

Install the arm64 APK on Android 8.0 or newer. On first launch, Kestrel shows **Welcome to Kestrel**,
then offers **Configure model** or **Skip for now**. Skipping completes the walkthrough and opens
the app without a provider, API key or model; the completion state is saved across restarts. The
optional model and Hands pages can also be skipped. Add a provider later from **Settings → Models**.
Sending a message without a chat model shows that configuration path instead of starting a request.
Chat and configuration remain on the device after restart.

Settings → Models keeps separate choices for chat, screen operation, pictures, clips and Hands.
The built-in provider catalogue supplies endpoint presets and capability information. Existing
provider types, API-key storage, provider OAuth, model groups, custom endpoints, and model discovery
remain available. A saved API key is not sent to nanoMuse Cloud.

On upgrade, Kestrel removes a legacy nanoMuse Cloud provider and its stored account token locally,
without contacting the relay. Existing conversations are adopted as local conversations and remain
visible. The app does not initialize the old account profile or conversation-sync workers. The
legacy account and data-control links show a local Relay/Bridge pause notice.

## Local agent and safety

| Component | Android behavior |
| --- | --- |
| Linux runtime | Alpine rootfs, proot, shell, files, browser, memory, scheduled tasks and native Android offload handlers remain available. Rootfs paths, command names, socket protocol and Agent Core are unchanged. |
| Skills and MCP | Agent Skills, the bundled connector catalogue, custom MCP servers and their own vendor authorization flows remain available. |
| Hands | Optional accessibility and overlay access lets the agent operate the phone. Existing local permission grants, Sentinel risk checks and approval cards remain in force. |
| Files and sessions | Chat sessions, attachments, memory, goals, routines, feed data, avatar and provider settings remain in app storage across restarts. |
| Cross-device Reach | The Reach and Hub code and wire protocol remain in the Android source. Their account-authenticated UI entry points are paused until Kestrel Relay/Bridge is available. No Hub socket is started from the legacy nanoMuse account token. |

Shell, file, Hands, browser, MCP and remote action approvals continue to use their existing local
permission and sandbox paths. Removing the account system does not grant tools additional access.

## Build and tests

The repository CI uses JDK 21, Android SDK CMake 3.22.1, NDK r27c and Go 1.25+ for native
dependencies. Build native dependencies first when they are not already present, then run:

```sh
cd android/src/android
./gradlew :app:testDebugUnitTest --console=plain --no-daemon
./gradlew :app:assembleDebug --console=plain --no-daemon
```

The debug APK is `android/src/android/app/build/outputs/apk/debug/app-debug.apk`; the build
configuration selects arm64-v8a by default. The emulator is x86_64; test the arm64 APK on an
Android device.

## Further reading

- [Android sandbox and device features](every-device.md)
- [Hands and the operator](gui.md)
- [Provider keys](own-key.md)
- [Permissions and privacy](privacy.md)
- [Build the Android app](../android/BUILDING.md)

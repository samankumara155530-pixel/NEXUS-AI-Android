# Controller and Target APK Split

## Goal

Produce two signed Android APK variants from the same NEXUS AI source tree:

- **Controller** — starts device pairing and displays the paired-device dashboard.
- **Target** — claims a pairing code and does not display controller pairing-generation or paired-device management controls.

Both variants retain the existing NEXUS AI application features outside the Device Link role-specific area.

## Compatibility and installation

- Both variants deliberately retain the existing application ID: `com.nexusai.app`.
- Both variants use the existing release signing configuration and keystore.
- They are intended for two different physical phones. Installing one variant over the other on the same phone replaces that installation because the package ID is shared.
- Do not change the application ID, signing key, or keystore for this split; doing so would break normal in-place updates.
- Release version remains 1.4.7 / versionCode 13 during this implementation. The 1.5.0 version bump is reserved for the final release build after acceptance tests.

## Role assignment and authorization

The Gradle product flavor sets a build-time role marker:

- `controllerRelease` → `BuildConfig.NEXUS_DEVICE_ROLE = "controller"`
- `targetRelease` → `BuildConfig.NEXUS_DEVICE_ROLE = "target"`

The marker controls the visible Device Link UI only. It is not treated as proof of authorization.

The backend remains authoritative:

- `POST /devices/pair/start` assigns the initiating installation the `controller` role and rejects an existing target.
- `POST /devices/pair/claim` calls the authenticated Supabase claim RPC, which assigns the claiming installation the `target` role and rejects a controller installation.
- Keep the backend checks even though the APKs hide the opposite role's controls.

## FCM device wake

- Firebase Android configuration is injected by GitHub Actions from the `FIREBASE_GOOGLE_SERVICES_JSON_BASE64` secret and is not committed to the repository.
- Both variants use Firebase Cloud Messaging and register their installation token after successful pairing.
- The target requests Android notification permission after pairing when required by Android 13+.
- A `NEXUS_WAKE / SYNC_PRESENCE` data message triggers an authenticated `POST /devices/heartbeat` request. If notifications are permitted, the target displays a visible wake notification.
- This confirms push delivery and presence sync only. It does **not** implement remote screen capture, remote touch input, camera streaming, or microphone streaming.

## Build workflow

GitHub Actions builds the two signed release variants as separate matrix jobs:

- Gradle task: `assembleControllerRelease`
- Gradle task: `assembleTargetRelease`
- Artifacts: `NEXUS-AI-controller-release-apk` and `NEXUS-AI-target-release-apk`

The workflow validates the Firebase JSON package name against `com.nexusai.app`, uses the existing NEXUS signing secrets, and removes the temporary keystore and Firebase JSON from the runner after each job.

## Acceptance checklist

1. Both release variants compile and are signed with the existing release keystore.
2. Controller APK shows Generate Pairing Code and Paired Devices; it does not show Claim Pairing Code.
3. Target APK shows Claim Pairing Code; it does not show Generate Pairing Code or the controller dashboard.
4. Pairing from the controller assigns `controller`; claiming on the target assigns `target`.
5. Backend rejects a target attempting to start pairing and rejects a controller attempting to claim as target.
6. Both paired installations register FCM tokens against their own stable installation IDs.
7. A controller wake request is delivered to the target, updates target `last_seen_at` through heartbeat, and displays a notification when notification permission is granted.
8. No claim is made that remote screen or touch control is implemented until those features have their own consent and Android permission implementation and tests.

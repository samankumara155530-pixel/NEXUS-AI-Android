# NEXUS Remote Control Roadmap

## Current verified baseline

- Android app has same-account device pairing UI.
- Backend exposes authenticated pairing start/claim, device list, and revoke routes.
- Supabase stores device and pairing records with RLS enabled and authenticated table grants.
- Android manifest currently declares camera, microphone, notification, and microphone foreground-service permissions.
- The current manifest does not declare a MediaProjection foreground service, an AccessibilityService, or a remote-control foreground service. The current app therefore must not be represented as already supporting remote screen capture or touch control.

## Product goals

Build a user-visible, consent-based remote assistance system for devices owned or administered by the same account. Support Wi-Fi and mobile data. Each device must show its paired state and last-seen state. The target device must show a persistent foreground notification while a remote session is active, with an obvious stop control.

## Delivery stages

### Stage 1 — Device inventory and session foundation
- Add a paired-device list UI using the existing authenticated GET /devices endpoint.
- Show device name, platform, pairing date, last-seen time, and revoked state.
- Add explicit session request, accept, reject, and terminate states.
- Add short-lived, single-use session credentials; never reuse the account access token as a media/session credential.
- Add server-side authorization ensuring both devices belong to the same authenticated account.
- Add audit events for session start, consent, stop, and revocation.

### Stage 2 — Remote screen viewing
- On the target Android device, start screen capture only after the local user explicitly approves Android's MediaProjection consent dialog.
- Run capture in a foreground service with the appropriate Android 14/15 foreground-service type and permissions.
- Stop capture when the user ends the session, the service is stopped, or the OS revokes the projection.
- Stream over an authenticated WebRTC connection using short-lived session credentials and DTLS-SRTP.
- Do not attempt silent capture, bypass Android consent, or hide the active-session indicator.

### Stage 3 — Remote interaction
- Treat touch input as a separate, opt-in capability.
- Explain the AccessibilityService use clearly and require the device owner to enable it in Android Settings.
- Provide a visible remote-control indicator and immediate local stop/revoke control.
- Do not enable unattended input or bypass lock-screen/security restrictions.

### Stage 4 — Other device capabilities
- Camera and microphone: explicit per-session local approval, visible indicators, and Android runtime/foreground-service permissions.
- Files and gallery: use Android Photo Picker / Storage Access Framework and user-selected items; avoid broad storage access where possible.
- Location: request runtime location permission and respect approximate/precise selection and background-location rules.
- Notifications: use Android's notification-listener special access, clearly explain scope, and provide a local disable control.

## Security requirements

- HTTPS/WSS for signaling; WebRTC DTLS-SRTP for media.
- Short-lived, one-time session tokens scoped to device pair and capabilities.
- Server-side ownership checks on every request; RLS remains enabled.
- Rate limits and pairing-code expiry; hash pairing codes at rest.
- Device revocation immediately blocks new sessions and terminates active sessions.
- No stealth mode, hidden capture, persistence against user choice, or permission bypass.

## Acceptance tests

1. Only devices belonging to the same authenticated account appear in its device list.
2. A revoked device cannot start or join a session.
3. A remote screen session cannot begin until the target user accepts Android's MediaProjection prompt.
4. A persistent notification is visible during screen capture and stopping locally terminates the stream.
5. Network changes between Wi-Fi and mobile data do not expose media or credentials; reconnection requires valid session authorization.
6. Touch, camera, microphone, files, location, and notifications each have separate permission/consent tests.

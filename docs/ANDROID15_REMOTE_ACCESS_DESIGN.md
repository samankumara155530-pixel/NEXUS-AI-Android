# NEXUS Remote Access Design — Android 15

## User-approved product behavior

NEXUS should keep a paired-device connection available over Wi-Fi or mobile data so the controller does not need to repeatedly open the target app for ordinary device presence and session signaling. Camera and microphone are separate capabilities and must never implicitly enable one another.

A previously granted Android runtime permission is remembered by the OS until revoked or reset. NEXUS may remember the owner's capability preference, but that preference is not a replacement for Android permission or service-start rules.

## Session model

1. Pair devices under the same authenticated account.
2. On the target device, the owner completes an explicit setup flow and separately enables Camera and Microphone capabilities.
3. A visible connection service may maintain device presence and receive session requests. It must not capture audio/video merely because it is connected.
4. The owner can choose a policy for future requests: require local confirmation for every session, or allow pre-authorized sessions for a specific paired controller. This choice must be explicit, revocable, and shown in settings.
5. When a pre-authorized session starts, show Android's privacy indicators and an ongoing notification with a Stop action. Do not hide indicators or notification.
6. Camera and microphone sessions start and stop independently. Stopping one must not stop or start the other.
7. If Android denies background service start, permission is revoked, the process is killed, or device policy disallows capture, report that state to the controller and ask the owner to reopen/approve as required. Never attempt to bypass these restrictions.

## Android 14/15 implementation constraints

- Declare CAMERA, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MICROPHONE, and POST_NOTIFICATIONS as applicable.
- Camera and microphone foreground services must declare the correct service types and type-specific permissions.
- Camera and microphone are while-in-use permissions. A background process generally cannot start a new camera/microphone foreground service unless a documented exemption applies. A permission granted earlier does not remove this restriction.
- Start camera/microphone capture from an eligible, user-visible context. Do not use boot receivers, silent background launches, hidden activities, accessibility, or notification tricks to evade while-in-use restrictions.
- Keep CameraX/Camera2 and AudioRecord capture code isolated; do not couple camera startup to microphone startup.
- Use a foreground service notification and Android privacy indicators during active capture.

## Capability UX

- Device presence: Connected / Offline / Reconnecting.
- Camera: Front/Back, Live Preview, Capture Photo, Stop.
- Microphone: Start Audio, Mute/Stop, independent status.
- Session permission: separate Camera and Microphone capability switches.
- Trusted controller list: show device identity, last access, revoke access.
- Local privacy controls: Stop all sessions and revoke trusted access.

## Delivery sequence

1. Verify device identity and deduplicate registrations using a stable per-installation ID, not device model/name.
2. Add explicit per-controller capability grants and revocation in backend; issue short-lived, single-use session credentials.
3. Add a visible connection/presence service without media capture.
4. Implement camera foreground service and CameraX preview/capture, gated by OS eligibility and owner-configured consent policy.
5. Implement microphone foreground service independently.
6. Add WebRTC media transport with authenticated signaling and DTLS-SRTP.
7. Test Android 15 background-start restrictions, process death, reboot, permission revocation, network switching, and local Stop behavior.

## Security and acceptance criteria

- No capture before the owner's configured authorization policy permits the session and Android permits the service start.
- No hidden or silent capture; privacy indicator and ongoing notification remain visible.
- Camera and microphone state are independent.
- Revocation prevents future sessions and invalidates active session credentials.
- No app claims unattended availability where Android does not permit it; show a clear blocked/needs-owner-action state instead.

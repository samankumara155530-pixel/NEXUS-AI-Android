# NEXUS AI Background Heartbeat and Reconnection

## What this change does

- Schedules an immediate heartbeat after a device successfully starts or claims pairing.
- Schedules a persistent periodic heartbeat using Android WorkManager.
- Requires a connected network before running. WorkManager automatically retries when connectivity returns, including Wi-Fi/mobile-data transitions.
- Uses the app's stable installation UUID and the currently refreshed Supabase access token.
- Does not run a permanent foreground service and does not require camera or microphone permissions.

## Android execution limits

Android periodic WorkManager jobs have a minimum 15-minute interval. The operating system may defer execution further because of Doze, battery optimization, OEM background limits, or power-saving modes. This is a best-effort online-presence signal, not a real-time socket or guaranteed 15-minute SLA.

WorkManager persists scheduled work across process death and device reboot. Network constraints allow it to resume when a network becomes available again. No app can guarantee background connectivity if the user force-stops the app, disables background activity, revokes required app permissions, or the device is powered off.

## Backend dependency

The worker calls:

`POST https://nexus-ai-backend-six.vercel.app/devices/heartbeat`

with:
- `Authorization: Bearer <Supabase access token>`
- JSON body containing `deviceId`

The backend PR adding this authenticated route must be merged and deployed before heartbeat success can be verified against production. Until then, a missing route or unpaired device is treated as a non-successful heartbeat.

## Pairing lifecycle

A periodic worker is enqueued only after the existing authenticated Pair Start or Pair Claim request succeeds. The work is uniquely named to prevent duplicate periodic schedules. The immediate work item is also unique.

## Not included yet

- FCM push wake-up / command delivery.
- A real-time WebSocket connection.
- Camera, microphone, screen sharing, or remote-control foreground services.
- A user-facing online/offline status screen.
- APK generation or release.

## Validation still required

1. Merge and deploy the backend heartbeat endpoint.
2. Pair an installation and confirm an authenticated heartbeat updates only its own account/device row.
3. Verify a second installation creates a separate row.
4. Disable Wi-Fi, restore mobile data, and verify the worker retries when Android reports network connectivity.
5. Test process death, reboot, Doze, and OEM battery restrictions on physical devices.

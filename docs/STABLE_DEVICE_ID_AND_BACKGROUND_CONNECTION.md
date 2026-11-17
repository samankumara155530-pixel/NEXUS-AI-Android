# Stable Device Identity and Background Connection

## Implemented in Android client

- A random UUID is generated once per app installation and stored in the app's private preferences.
- The UUID is not derived from IMEI, serial number, phone number, or another hardware identifier.
- Pair-start and pair-claim requests now include `deviceId` alongside the display name.
- The paired-device UI prefers the server's `device_id` / `deviceId` field when grouping records.
- Existing server records without a stable ID retain the legacy name/platform grouping behavior.

## Backend work still required

The Android client change alone does not make device identity durable on the server. The backend must:
1. Accept and validate `deviceId` on both pair-start and pair-claim requests.
2. Persist it in the device registration table with a uniqueness constraint scoped to the authenticated account (and preferably a unique installation ID per account).
3. Return the persisted value as `device_id` in the devices list.
4. Make pairing idempotent for the same account + device ID, updating last-seen/device metadata rather than inserting duplicate rows.
5. Provide an authenticated, rate-limited heartbeat endpoint that updates `last_seen_at` and returns device connection state.

## Background connection implementation plan

Do not claim an always-on real-time connection until the backend heartbeat/signaling contract exists.

Recommended stages:
1. Use WorkManager periodic work for best-effort presence refresh (Android's periodic interval is not real-time; minimum interval is 15 minutes).
2. If near-real-time remote session signaling is required, use a supported push channel such as Firebase Cloud Messaging to wake the app for a visible, user-authorized workflow; do not depend on an indefinitely running background process.
3. Add a visible foreground service only for a genuinely ongoing, user-visible connection/session and comply with Android 14/15 foreground-service type and while-in-use restrictions.
4. Camera and microphone capture remain separate, owner-authorized sessions. A prior runtime permission grant does not itself authorize starting camera/microphone capture from the background.

## Validation required before marking complete

- Pair the same installation repeatedly and verify the server returns one device row.
- Install on a second physical phone and verify it receives a different stable ID.
- Clear app data/reinstall and verify a new ID is expected.
- Verify old rows without device IDs remain visible and can be migrated safely.
- Verify last-seen heartbeat behavior after backend implementation.
- Test network changes, Doze, battery optimization, process death, reboot, and permission revocation on Android 15.

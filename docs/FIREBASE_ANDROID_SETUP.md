# NEXUS AI Firebase Android setup

The Android source now uses Firebase Cloud Messaging for installation-token registration and Target-only wake acknowledgements.

## One-time Firebase Console setup

1. Open the Firebase project used by the NEXUS AI backend.
2. In Project settings → General → Your apps, add an Android app if one does not already exist.
3. The Android package name must be exactly `com.nexusai.app`.
4. Download that app's `google-services.json`.
5. Do not use the Firebase Admin service-account JSON here. The Admin JSON is server-only and must remain in Vercel as `FIREBASE_SERVICE_ACCOUNT_JSON`.

## Configure GitHub Actions

The release workflow reads the Android client config from the GitHub Actions secret `NEXUS_GOOGLE_SERVICES_JSON_BASE64`.

On a trusted computer, encode the downloaded file as one Base64 string:

```bash
base64 -w 0 google-services.json
```

On macOS use `base64 < google-services.json | tr -d '\\n'`.

In GitHub → NEXUS-AI-Android → Settings → Secrets and variables → Actions, create/update secret:

- Name: `NEXUS_GOOGLE_SERVICES_JSON_BASE64`
- Value: the Base64 output above

The workflow decodes it into `app/google-services.json`, verifies the Android package name, and the file is excluded from Git by `.gitignore`.

## Backend prerequisites

- Vercel Production (and Preview if used) must have `FIREBASE_SERVICE_ACCOUNT_JSON` set to the Firebase Admin service-account JSON for the same Firebase project.
- Supabase migrations for `nexus_device_push_tokens` and `nexus_device_push_deliveries` must already be applied.
- The authenticated backend endpoints used by the app are `POST /devices/push/register`, `POST /devices/push/ack`, `POST /devices/:deviceId/push/wake`, and `GET /devices/:deviceId/push/status`.

## Expected device flow

1. Sign in to the same NEXUS account on both phones.
2. Controller generates a pairing code; its stable installation ID is registered as controller.
3. Target enters the code; its stable installation ID is registered as target.
4. Each app obtains its FCM token and registers it against its paired installation.
5. Controller sends the fixed `NEXUS_WAKE / SYNC_PRESENCE` data message to the active Target only.
6. Target acknowledges `received` and then `processed`; Controller polls the delivery record.

FCM acceptance alone is not proof of Target receipt. The UI must report `received` or `processed` only after the Target acknowledgement is stored. Android background delivery remains best-effort and can be affected by force-stop, network availability, battery restrictions, and notification/FCM platform behavior.

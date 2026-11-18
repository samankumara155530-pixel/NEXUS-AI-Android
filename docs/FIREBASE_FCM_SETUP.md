# Firebase Cloud Messaging setup

Firebase project: `nexus-ai-75a96`
Android package: `com.nexusai.app`

## Android configuration (keep out of Git)

The Firebase client configuration belongs at `app/google-services.json` on a developer machine. It is ignored by `.gitignore`.

For the GitHub Actions release workflow, create a repository Actions secret:

- Name: `FIREBASE_GOOGLE_SERVICES_JSON_BASE64`
- Value: Base64 encoding of the complete `google-services.json` file (single line).

The workflow decodes this secret to `app/google-services.json` at build time and checks the Firebase project ID and Android package before Gradle runs. Never put a Firebase Admin service-account JSON in this secret.

On a local machine, keep the file at `app/google-services.json`; the Google Services Gradle plugin is applied only when this file exists.

## Backend setup (separate repository)

The backend requires the Supabase migration:
`supabase/migrations/202611180002_fcm_device_tokens.sql`

It also requires a Vercel sensitive environment variable named:
`FIREBASE_SERVICE_ACCOUNT_JSON`

The value must be the Firebase Admin SDK service-account JSON for project `nexus-ai-75a96`. Keep this private; do not commit it or send it in chat.

## Scope and safety

FCM data messages are restricted to `NEXUS_WAKE / SYNC_PRESENCE`. The Android receiver schedules the existing WorkManager heartbeat only. It does not execute camera, microphone, screen capture, location, or file commands.

Periodic WorkManager heartbeats are best-effort; Android may defer them and the minimum periodic interval is 15 minutes. FCM is a wake hint, not a guaranteed always-on socket.

## Build policy

Do not run the manual release workflow until configuration, backend deployment, migration, and device testing are complete.
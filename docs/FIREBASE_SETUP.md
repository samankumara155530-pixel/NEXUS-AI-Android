# NEXUS AI Firebase setup

The FCM receiver accepts only the data message `type=NEXUS_WAKE` and `action=SYNC_PRESENCE`. It schedules the existing WorkManager heartbeat only. It cannot activate camera, microphone, screen capture, location, files, or arbitrary remote commands.

Before testing on a real phone:
1. Create/select a Firebase project.
2. Register Android package `com.nexusai.app`.
3. Download `google-services.json` and place it at `app/google-services.json`. Do not commit it to a public repository.
4. Configure the backend Vercel project with sensitive `FIREBASE_SERVICE_ACCOUNT_JSON` for Production and Preview.
5. Apply the backend migration `202611180002_fcm_device_tokens.sql` in Supabase and deploy the backend FCM branch.
6. Only then build/install a test APK. No APK is built by this change.

The Google Services Gradle plugin is applied only when the local JSON config exists. Without Firebase configuration, the app source can still build, but FCM token acquisition and delivery cannot work.

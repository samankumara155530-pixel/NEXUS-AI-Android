# NEXUS AI Remote Session — Two-Device Acceptance Test

This test plan is required before publishing Controller and Target APKs. A successful Gradle build is not an end-to-end pass.

## Before installing

1. Confirm Supabase migrations have been applied in order:
   - Backend migration `202611210002_consent_gated_remote_requests.sql`
   - Backend migration `202611210003_add_screen_remote_feature.sql`
   - Backend migration `202611210004_remote_session_approval.sql`
   - Backend migration `202611210005_remote_media_storage.sql`
2. Confirm the private `nexus-remote-media` bucket exists and is not public.
3. Deploy the backend PR to the environment used by the test APK.
4. Install matching signed Controller and Target APKs (same app version/signing key) on two physical Android devices.
5. Sign both devices into the same NEXUS account and verify their paired roles.

## Session approval

- [ ] Controller requests a 30-minute Remote Session.
- [ ] Target foreground: one approval dialog appears.
- [ ] Target background: notification appears; opening NEXUS shows the approval dialog.
- [ ] Approve once; Controller sees the session become active.
- [ ] Camera, Microphone, Gallery and Location requests do not show repeated NEXUS approval dialogs.
- [ ] Android runtime permission prompts still appear when required.
- [ ] Screen Share still displays the Android MediaProjection system consent dialog.
- [ ] Declining a session does not authorize later feature requests.
- [ ] Target Stop Session revokes the session and stops active audio/screen capture.
- [ ] After Stop Session or expiry, the next remote feature request requires a new Target approval.

## Camera and media delivery

- [ ] Camera opens a visible capture choice on Target.
- [ ] Photo capture stores a full-resolution MediaStore photo and uploads a compressed transfer copy.
- [ ] Controller receives the photo and can open it.
- [ ] Video capture opens the system camera app, records with audio, and stops at 30 seconds maximum.
- [ ] Controller receives the video and can play its picture and sound.
- [ ] Canceling Camera does not report a successful capture.
- [ ] A video larger than the 20 MB bucket limit fails with a clear error and does not appear as a completed result.

## Microphone recording

- [ ] First use asks for Android RECORD_AUDIO permission.
- [ ] A visible ongoing NEXUS recording notification appears with a Stop action.
- [ ] Recording automatically stops at 30 seconds.
- [ ] Stop action ends recording early and still uploads the saved audio.
- [ ] Controller receives and can play the audio.
- [ ] Denying microphone permission reports failure and does not start recording.
- [ ] Target Stop Session cancels an in-progress recording and removes any not-yet-uploaded local audio.

## Gallery, Location and Screen

- [ ] Gallery picker accepts both photo and video selection.
- [ ] Controller can open/play selected media.
- [ ] Location request asks Android permission if needed and returns a valid location result.
- [ ] Screen Share asks for Android system consent, shows an ongoing notification with Stop, streams previews, and ends after its time limit.
- [ ] Target can stop screen sharing from its notification.
- [ ] Screen capture is not started without Android's system consent.

## Reliability and privacy

- [ ] Test FCM delivery with Target foreground, background, and after reopening the app.
- [ ] Test network loss during upload and confirm the Controller never shows a false completed result.
- [ ] Confirm only the paired Controller can retrieve the media URL.
- [ ] Confirm Storage bucket is private and signed download URLs expire.
- [ ] Confirm media older than seven days is cleaned on a subsequent media upload.
- [ ] Confirm no photo/video/audio is captured covertly and Target always has visible system/app controls.

## Release gate

Publish only when all applicable checks above pass on the actual devices. Record the Android versions, device models, test date, and any failures before signing off.

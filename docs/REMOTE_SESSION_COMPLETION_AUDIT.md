# NEXUS AI Remote Session Completion Audit

Audit date: 2026-11-22
Audited Android base: `fix/controller-target-role-split`
Audited backend base: `feat/remote-session-approval`

This document records what is present in source code versus what remains unverified. Source presence is not proof of end-to-end operation.

## 1. Android Audio Delivery

**Present in source**
- `NexusAudioRecordService` records user-visible AAC audio to a temporary Target cache file, with a foreground notification and Stop action.
- `MainActivity` watches the completed recording, calls `remoteMediaUpload`, and deletes the Target temporary file only after the backend completion endpoint returns success.
- The upload helper requests a short-lived signed upload URL, uploads bytes to private storage, then calls the completion endpoint.

**Still to verify / improve**
- Transfer retry after process death or network loss. The current upload watcher is a Compose `LaunchedEffect`, not a durable WorkManager job.
- Confirm Controller receives a completed media URL and can play the recording on both devices.
- Confirm cleanup of orphaned upload objects when completion fails.

Status: implementation exists; durable delivery and physical-device verification remain open.

## 2. Screen + Microphone

**Present in source**
- `NexusScreenCaptureService` uses Android MediaProjection consent, a foreground notification with Stop, and uploads compressed JPEG preview frames.
- Controller polls remote request status and updates the displayed frame.

**Missing**
- This is periodic still-frame preview (approximately one frame per 1.2 seconds), not a low-latency continuous screen stream.
- The screen capture service does not capture or transmit microphone audio alongside the screen.
- No combined Screen + Microphone control/state is implemented in this service.

**Required work**
- Design and implement an owner-visible combined session with explicit microphone opt-in, Android microphone foreground-service requirements, MediaProjection consent, separate Stop controls, and robust teardown.
- Evaluate WebRTC or another suitable real-time transport instead of repeated JSON/base64 frame polling.
- Do not bypass Android consent, privacy indicators, or foreground-service restrictions.

Status: incomplete.

## 3. FCM Remote Session

**Present in source**
- FCM token registration, token refresh handling, wake acknowledgement, approval notifications, and approved-session checks exist.
- FCM request handling stores pending request details and notifies the Target; capture itself is not started by an arbitrary FCM payload.

**Still to verify**
- FCM delivery/acknowledgement on the two actual devices while foregrounded, backgrounded, force-stopped, and after network interruption.
- Token refresh and re-registration after reinstall / app data reset.
- Expiry, revoke, duplicate delivery, and stale-session behavior.

Status: code exists; device verification remains open.

## 4. Secure Media Transfer

**Present in source**
- Backend has authenticated upload-url and completion endpoints.
- Upload paths are scoped to user, active Target device, and request ID.
- Signed upload/download URLs are used with the `nexus-remote-media` bucket.

**Still to verify / improve**
- Confirm private bucket and owner policies in the deployed Supabase project.
- Ensure completion verifies the uploaded object's actual existence and actual size/type rather than trusting only the client-supplied size.
- Ensure retry cleanup cannot delete an already completed object or race a parallel upload.
- Ensure media download authorization is limited to the paired Controller and active account/session.
- Verify signed URL expiry and seven-day retention cleanup.

Status: core mechanism exists; security hardening and deployed integration tests remain open.

## 5. Two-phone Testing

Not proven by source inspection. Required on the actual Controller and Target phones:
- exactly one active Controller and one active Target;
- FCM token registered for both and wake/approval delivered;
- session approval and stop/expiry;
- microphone recording upload, Controller playback, retry after network loss;
- camera photo and video with audio;
- gallery, location;
- screen preview and combined screen + microphone after implementation;
- private media authorization, URL expiry, and cleanup.

## Release gate

Do not publish APKs or describe the system as complete until:
1. Android and backend CI builds/tests pass;
2. deployed backend and Supabase configuration are verified;
3. Controller/Target APKs install and authenticate on two physical devices;
4. every applicable acceptance test above passes with evidence.

# NEXUS AI Remote Screen Sharing

The Screen feature is a consent-gated, visible, low-frame-rate screen-sharing session.

## Flow

1. The Controller sends a `screen` remote request to its paired Target.
2. The Target displays an in-app approval dialog.
3. After approval, Android's own MediaProjection system dialog is shown.
4. Only after the user approves that system dialog does the Target start the visible MediaProjection foreground service.
5. The Target streams small JPEG screen frames to the authenticated backend approximately once every 1.2 seconds.
6. The Controller polls the approved session and displays the latest received frame.
7. The Target user can stop sharing from the persistent foreground-service notification. Sessions also stop automatically after 60 seconds.

The Target can decline either approval step. The capture service is never started without Android system consent.

## Current limitations

- This is low-frame-rate screen viewing, not smooth real-time video.
- Remote touch input, typing, and device control are not implemented.
- The Target user retains control and can stop sharing at any time.
- Each request is limited to a 60-second session.

## Backend database and deployment

Apply the NEXUS remote-request migrations in order:

- `202611210002_consent_gated_remote_requests.sql`
- `202611210003_add_screen_remote_feature.sql`

The backend's `server.js` changes must be deployed after the screen feature allow-list and screen-frame endpoint updates.

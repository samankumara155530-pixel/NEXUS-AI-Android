# NEXUS AI Remote Screen Preview

The Screen feature is a consent-gated, one-time screen preview (not a hidden recorder).

## Flow

1. The Controller sends a `screen` remote request to its paired Target.
2. The Target displays an in-app approval dialog.
3. After approval, Android's own MediaProjection system dialog is shown.
4. Only after the user approves that system dialog does the Target start the visible MediaProjection foreground service.
5. The service captures one frame, reduces it to a small JPEG preview, posts it as the completed remote-request result, and stops.
6. The Controller displays the returned preview.

The Target can decline either approval step. The capture service is not started without Android system consent.

## Current limitations

- This implementation returns one screen image per request.
- It is not continuous live video streaming.
- It does not provide remote touch input or control.
- Secure continuous streaming requires a separate authenticated WebRTC/signalling and session lifecycle implementation.

## Backend database

Apply the NEXUS remote-request migrations in order:

- `202611210002_consent_gated_remote_requests.sql`
- `202611210003_add_screen_remote_feature.sql`

The backend's `server.js` must be deployed after the screen feature allow-list update.

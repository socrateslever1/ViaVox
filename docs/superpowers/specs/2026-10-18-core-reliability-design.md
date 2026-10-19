# ViaVox - Core Reliability Design

**Date:** 2026-10-18
**Scope:** first critical delivery for Android, web, security, and reproducible builds

## Purpose

ViaVox must remain useful without an account. Street and nearby-reference narration must continue on Android while the screen is locked or another app is visible, until the user explicitly pauses tracking. Speech must not depend on a paid cloud API.

An optional account may be introduced later only for cloud synchronization. Current favorites, routes, settings, and trip history remain local and do not require login.

## Product decisions

- The free core has no login gate.
- Android speech uses installed Portuguese `TextToSpeech` voices.
- Web speech uses voices exposed by the browser through `speechSynthesis`.
- ViaVox does not operate or configure Azure/Francisca speech in this scope.
- The Cloudflare voice worker, broker URL, access token, and Francisca-specific UI and runtime code are removed.
- No Azure or Cloudflare credential is required to build or use the application.

## Android background behavior

`ViaVoxTrackingService` remains a location foreground service after the Activity receives `ON_STOP`. Locking the screen, opening another app, or temporarily removing ViaVox from view must not stop free-mode street and POI narration.

Tracking stops only when the user presses the in-app pause control or the notification stop action. Both paths persist the manual-pause state. Returning to the app must not restart a session that the user explicitly paused.

If Android destroys a running service, a sticky restart may resume only when tracking was not manually paused and location permission is still available. A restart without those conditions terminates immediately.

The service is started while the Activity is visible, preserving modern Android foreground-service restrictions. Fine location is the supported mode for street, POI, side-of-road, and routing precision. Approximate-only permission is reported as limited and must not produce precision claims. Notification permission is requested independently and denial is communicated without treating it as a location denial.

This delivery guarantees background continuation for the service-owned free narration flow. Trip recording and route-guidance state currently owned by `NavigationViewModel` remain foreground-session features; documentation must not claim that those features survive process death. Moving them into a persistent navigation session is a separate delivery.

## Free voice selection

Android lists ready Portuguese voices installed by the active TTS engine. Automatic selection prefers Brazilian Portuguese, then higher reported voice quality, with network requirement used as a final preference rather than hiding higher-quality voices. Each option states whether it is online or offline. If the selected voice becomes unavailable, ViaVox falls back to the best ready Portuguese voice and then to the engine default.

The web client loads available browser voices, prefers `pt-BR`, persists the selected voice identifier locally, and exposes a preview. It falls back to an appropriate Portuguese/default voice when the stored voice no longer exists. No request is sent to a ViaVox speech server.

## Local data and backup safety

Precise trip history and application preferences are not exported through Android Auto Backup or device-to-device transfer. Android backup configuration explicitly excludes the history database and preferences. This does not delete local data and does not introduce cloud synchronization.

The project documentation states which external map, geocoding, POI, and routing services receive coordinates during use. No analytics or new telemetry is added.

## Build and CI reliability

- Add the Gradle Wrapper pinned to the Gradle version already used by CI.
- Add and commit `package-lock.json`; CI uses `npm ci`.
- Ignore Gradle caches and module build outputs, and remove already tracked cache artifacts from version control without deleting unrelated user files.
- Run Android and web validation on pull requests as well as pushes to `main`.
- Include configuration and lockfile changes in workflow path filters.
- Keep Android debug APK publishing explicitly development-only.
- Align user-agent and documentation versions with Android version `0.6.0`.

## Testing strategy

Behavior changes follow red-green-refactor:

- Unit-test the tracking-session policy for automatic resume, explicit pause, and sticky restart.
- Unit-test Android voice ranking and fallback independently of the Android TTS engine.
- Unit-test web voice ranking and stored-voice fallback.
- Preserve and run the existing Android road, GPS-quality, POI, parsing, and speech tests.
- Run Android unit tests, Android lint, debug build, web tests, TypeScript build, and `git diff --check` before completion.

Device-only validation remains explicit: locked-screen GPS delivery, OEM battery restrictions, audio focus with other media, notification visibility, and real installed-voice quality require a physical Android device and are not proven by unit tests or compilation.

## Error handling

- Missing or failed TTS engines produce an actionable local message and never attempt paid cloud speech.
- Losing a previously selected voice triggers deterministic fallback.
- Permission denial does not start a partially functional tracking service silently.
- Service restart does not override explicit user pause.
- Web voice enumeration handles delayed `voiceschanged` delivery and an empty voice list.

## Out of scope for this delivery

- User accounts and cloud synchronization.
- Paid or hosted neural voice services.
- Reworking route-progress/map-matching logic.
- Migrating trip recording or route state into the service.
- History pagination/database migration.
- Full UI redesign.
- Production Play Store signing and deployment.

## Completion criteria

The delivery is complete when the paid voice path is absent, anonymous local speech remains available, Android service lifecycle behavior matches the rules above, backup exclusions are configured, deterministic build inputs are committed, relevant automated checks pass, and remaining physical-device evidence is reported accurately.

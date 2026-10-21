# Free and Open Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver stable spoken geography, trustworthy POI narration and 2D forward-facing navigation without paid or secret-bearing providers.

**Architecture:** Keep one structured speech path in the Android foreground service, resolve the bundled Manaus polygon data locally, and make map/camera behavior explicit on Android and web. Remove unused Google/Gemini paths rather than retaining insecure optional configuration.

**Tech Stack:** Kotlin/JVM, Jetpack Compose, React/TypeScript, MapLibre GL JS, GeoJSON, Vitest/JUnit.

**Spec:** `docs/superpowers/specs/2026-10-21-free-open-navigation-design.md`

## Global Constraints

- Cost must remain R$0 with no mandatory key, card or paid Google service.
- Preserve APK and web targets and existing public data/history contracts.
- Do not claim CICOM and DIP share jurisdiction without separate authoritative data.
- Write behavior tests before production changes and verify every runnable platform locally.
- Preserve visible map attribution and public tile-service usage limits.

## Review Focus

- A first fix containing zone, neighbourhood, road and CICOM must produce one ordered speech event.
- GPS fixes around the Coroado/Aleixo boundary must not oscillate spoken neighbourhoods.
- A nearby POI must not be described as the user's current place without inside/arrival evidence.
- Active navigation must use route/GPS course rather than a badly oriented phone compass.
- Missing network tiles, geocoding or satellite imagery must retain usable navigation and clear fallback behavior.

---

### Task 1: Structured geographic and CICOM speech

**Files:**
- Modify: `app/src/main/java/com/example/viavox/service/GeographicSpeechPlanner.kt`
- Modify: `app/src/main/java/com/example/viavox/service/RoadDetectionEngine.kt`
- Test: `app/src/test/java/com/example/viavox/GeographicTransitionEngineTest.kt`
- Test: `app/src/test/java/com/example/viavox/SpeechOrchestratorTest.kt`

**Interfaces:**
- Produces: `GeographicTransition(zone, neighbourhood, road, policeCircumscription)` and one ordered `sentence()`.
- Consumes: `Place.policeDistrict` and `RoadDetectionEngine.enablePoliceCircumscription`.

- [x] Add failing tests for first-entry and neighbourhood-only CICOM speech.
- [ ] Run the focused Android tests and confirm the missing-CICOM failures.
- [x] Add CICOM to the structured transition and pass it only when enabled.
- [ ] Run focused and full Android unit tests.

### Task 2: Polygon neighbourhood resolution and boundary stability

**Files:**
- Modify: `app/src/main/java/com/example/viavox/data/ManausDistrictResolver.kt`
- Modify: `app/src/main/java/com/example/viavox/data/GeneralCircumscriptionService.kt`
- Test: `app/src/test/java/com/example/viavox/ManausDistrictResolverTest.kt`
- Modify: `app/src/main/assets/bairros_manaus.geojson`
- Modify: `public/bairros_manaus.geojson`

**Interfaces:**
- Produces: polygon-backed `resolveDistrict(lat, lon, displayName)` with deterministic edge handling.
- Consumes: bundled GeoJSON and stable display-name aliases.

- [x] Add failing point-in-polygon tests for UFAM, Bola do Coroado and an Aleixo point.
- [ ] Add a failing boundary-stability test.
- [x] Replace overlapping rectangles with a local polygon index and hysteresis-compatible result.
- [ ] Run focused and full Android unit tests.

### Task 3: Trustworthy POI wording

**Files:**
- Modify: `app/src/main/java/com/example/viavox/model/PointOfInterest.kt`
- Modify: `app/src/main/java/com/example/viavox/service/PoiAnnouncementCoordinator.kt`
- Modify: `app/src/main/java/com/example/viavox/data/PoiSelectionPolicy.kt`
- Test: `app/src/test/java/com/example/viavox/PointOfInterestTest.kt`
- Test: `app/src/test/java/com/example/viavox/PoiAnnouncementCoordinatorTest.kt`

**Interfaces:**
- Produces: passing-by speech with side and an explicit arrival/inside speech path.
- Consumes: filtered civic POIs, relative direction and arrival evidence.

- [x] Add failing wording and false-arrival regression tests.
- [x] Implement separate pass-by and inside/arrival messages.
- [x] Keep existing deduplication and strict relevance checks.
- [ ] Run focused and full Android unit tests.

### Task 4: Flat, forward-facing maps and open reference imagery

**Files:**
- Modify: `app/src/main/java/com/example/viavox/ui/components/OpenStreetMapComponent.kt`
- Modify: `app/src/main/java/com/example/viavox/model/NavigationCameraMath.kt`
- Modify: `app/src/test/java/com/example/viavox/NavigationCameraAndProgressTest.kt`
- Modify: `src/main.tsx`
- Modify: `src/navigation.ts`
- Modify: `src/navigation.test.ts`
- Modify: `src/style.css`

**Interfaces:**
- Produces: 2D map state, route-course camera heading and optional licensed reference imagery selection.
- Consumes: route geometry, GPS bearing/speed and platform map renderer.

- [x] Add failing camera-heading tests for navigation versus exploration.
- [x] Set web pitch and Android tilt to zero and remove the 3D control.
- [x] Prefer route/GPS course during active navigation while retaining compass exploration.
- [x] Add a legally reusable, labelled reference-imagery option with attribution/fallback, or record a ruling to omit it if no production-compatible zero-cost endpoint is available.
- [ ] Run web tests/build and Android tests.

### Task 5: Remove paid-provider and embedded-secret paths

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/src/main/java/com/example/viavox/model/ViaVoxConfig.kt`
- Modify: `app/src/main/java/com/example/viavox/data/NominatimService.kt`
- Modify: `app/src/main/java/com/example/viavox/viewmodel/ViaVoxViewModel.kt`
- Modify: `app/src/main/java/com/example/viavox/ui/components/SettingsDialog.kt`
- Modify: `app/src/main/java/com/example/viavox/service/ViaVoxTrackingService.kt`
- Delete: `app/src/main/java/com/example/viavox/data/GeminiMapsGroundingService.kt`
- Delete: `app/src/main/java/com/example/viavox/data/GooglePlacesEnricher.kt`
- Test: `app/src/test/java/com/example/viavox/FranciscaConfigTest.kt`

**Interfaces:**
- Produces: keyless APK configuration and OpenStreetMap-only reverse geocoding.
- Consumes: existing Nominatim result contract.

- [ ] Add a failing configuration/source regression test that rejects paid-provider settings.
- [x] Remove Gemini/Google configuration, build plugins and enrichment calls.
- [x] Preserve migration tolerance for old preferences without reading or exposing their values.
- [ ] Run Android tests/build and secret-pattern scans.

### Task 6: Background control, documentation and final verification

**Files:**
- Modify: `app/src/main/java/com/example/viavox/service/ViaVoxTrackingService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `README.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/PLANO_IMPLEMENTACAO_REAL_ANDROID.md`
- Modify: `docs/MAP_STACK_RESEARCH.md`

**Interfaces:**
- Produces: foreground-service control documentation and accurate project status for APK/web.
- Consumes: existing notification actions and `BackgroundSessionPolicy`.

- [ ] Add/extend tests for closing controls without losing an explicitly active navigation session.
- [x] Keep the persistent Android notification with open/stop/end controls; do not add overlay permission.
- [x] Document web background limitations, data provenance and completed/remaining work.
- [ ] Run all tests, typecheck/build, Android build when tooling is available, `git diff --check`, secret scan and final diff review.

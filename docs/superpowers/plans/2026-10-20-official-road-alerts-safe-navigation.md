# Official Road Alerts and Safe Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver verified Manaus road alerts and a compact, truthful navigation mode on web and Android without mandatory paid providers.

**Architecture:** A validated local GeoJSON source becomes the primary alert feed and merges with the existing Overpass cache as a supplement. Pure domain helpers validate, normalize, deduplicate, classify road restrictions and format location text; platform UI renders their state without inventing data.

**Tech Stack:** TypeScript, React, Vitest, MapLibre, Kotlin, Compose, GeoJSON, existing OkHttp/Overpass adapters.

**Spec:** `docs/superpowers/specs/2026-10-20-official-road-alerts-safe-navigation-design.md`

## Global Constraints

- No paid key, commercial traffic feed, or credential is added.
- Official radar records require provenance and review date.
- Never infer or label dangerous areas from districts, neighborhoods, poverty, police jurisdiction, or reputation.
- No real-time traffic or signal-cycle claim without a verified enabled feed.
- Preserve navigation to a selected destination even when its approach is a service/no-exit road.
- Web and Android receive equivalent official alert types and messages.

## Review Focus

- A malformed official point must be ignored rather than breaking map load; test in Task 1.
- A nearby OSM point must not duplicate an official point; test in Task 1.
- A final destination on a no-exit road must remain navigable; test in Task 2.
- A reverse-geocoder failure must leave the driving UI usable with an unavailable location; test in Task 3.
- Active navigation must hide search and setup UI without hiding next-maneuver information; test in Task 3.

---

### Task 1: Verified road-alert domain and data

**Files:**
- Create: `public/radares-manaus.geojson`, `src/roadAlerts.ts`, `src/roadAlerts.test.ts`
- Modify: `src/main.tsx`, `app/src/main/assets/radares-manaus.geojson`, `app/src/main/java/com/example/viavox/service/SafetyAlertEngine.kt`, `app/src/test/java/com/example/viavox/SafetyAlertEngineTest.kt`

**Interfaces:**
- Produces `parseOfficialRoadAlerts(input: unknown): OfficialRoadAlert[]` and `mergeRoadAlerts(official, osm): RoadAlert[]`.
- Consumes the static GeoJSON in both clients.

- [ ] Write failing tests for provenance validation, invalid coordinates, type validation, and near-point deduplication.
- [ ] Run focused Vitest and Android unit test commands; record unavailable Android tooling exactly if unavailable.
- [ ] Implement the TypeScript parser/merger and add reviewed official IMMU data points.
- [ ] Implement matching Android loader/parser and alert types for speed, speed-hump, and red-light cameras.
- [ ] Run tests, web type/build checks, and `git diff --check`.
- [ ] Commit `feat: add official Manaus road alerts`.

### Task 2: Route suitability without fake safety claims

**Files:**
- Create: `src/routeSuitability.ts`, `src/routeSuitability.test.ts`
- Modify: `src/navigation.ts`, `src/main.tsx`, `app/src/main/java/com/example/viavox/data/NavigationNetworkService.kt`, `app/src/test/java/com/example/viavox/NavigationParsingTest.kt`

**Interfaces:**
- Produces `classifyRoadAccess(tags): RoadSuitability` and `selectSuitableRoute(routes, destination): NavigationRoute`.
- Consumes provider/OSM road metadata only when it is present and valid.

- [ ] Write failing tests for private/closed/no-exit/service classification, alternative fallback, and final-destination exception.
- [ ] Run focused tests and verify expected red failure.
- [ ] Implement conservative route classification and selection; leave routes eligible when metadata is absent.
- [ ] Add Android parser compatibility without claiming that OSRM avoided a road unless data demonstrates it.
- [ ] Run web suite/build and Android verification where tooling exists.
- [ ] Commit `feat: classify unsuitable road segments`.

### Task 3: Compact driving interface and current-location state

**Files:**
- Create: `src/drivingMode.ts`, `src/drivingMode.test.ts`
- Modify: `src/main.tsx`, `src/style.css`, `app/src/main/java/com/example/viavox/ui/components/NavigationControls.kt`, `app/src/main/java/com/example/viavox/ui/screens/ViaVoxMainScreen.kt`

**Interfaces:**
- Produces `formatCurrentLocation(place): string` and `getDrivingModeVisibility(status): DrivingModeVisibility`.
- Consumes existing `Place`, navigation session, current speed, and route alert state.

- [ ] Write failing tests for readable location, unavailable values, and navigation-only visibility rules.
- [ ] Run focused Vitest and verify expected red failure.
- [ ] Implement a compact bottom driving card, location action, safe speech priority, and restoration on end navigation.
- [ ] Apply the equivalent Android card copy and visibility state.
- [ ] Run tests, Vite build, diff check, and Android validation if available.
- [ ] Commit `feat: add compact driving mode`.

### Task 4: Documentation, integration review, and verification

**Files:**
- Modify: `README.md`, `docs/MAP_STACK_RESEARCH.md`

- [ ] Document official-data provenance, review/update process, feature limitations, and the disabled-provider policy.
- [ ] Run `npm.cmd test`, `npm.cmd run build`, and `git diff --check`.
- [ ] Attempt Android unit/build command and record its outcome without conflating it with web evidence.
- [ ] Perform self-review of changed files for false claims, secrets, stale static data, mobile overlap, and regression risk.
- [ ] Commit `docs: document official alert data`.

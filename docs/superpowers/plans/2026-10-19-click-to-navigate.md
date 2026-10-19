# Click-to-Navigate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a normal background-map click select a destination, show an explicit **Navigate here** action, calculate valid route alternatives only after confirmation, and start guidance on web and Android.

**Architecture:** Keep map hit-testing separate from navigation state. A click creates a pending destination; confirmation requests validated OSRM alternatives; route preview recommends the shortest-duration route while explicitly stating that live traffic and verified risk-zone data are unavailable. Web and Android implement the same state transitions in their existing domain/ViewModel patterns.

**Tech Stack:** React 19, TypeScript 5.8, MapLibre GL JS, Vitest, Kotlin 2.1, Jetpack Compose, MapLibre Native, JUnit 4, OSRM

**Spec:** `docs/superpowers/specs/2026-10-18-zero-cost-safe-navigation-design.md`

## Global Constraints

- No login, paid API key, or billable provider.
- A normal background-map click selects a point; clicks consumed by an interactive map feature do not.
- Routing begins only after explicit **Navigate here** confirmation.
- Never claim live traffic or risk avoidance without a verified data source.
- Preserve the destination-free voice locator and all existing search, CICOM, ArcGIS, radar, toll and speedometer behavior.
- Web and Android are separate targets and must be validated separately.

## Review Focus

- A drag ending on the map must not be interpreted as a destination click.
- Clicking an ArcGIS/CICOM/district feature must not also select the background point.
- A second click while routing is in flight must prevent the first response from replacing it.
- Missing GPS must preserve the selected point and explain why routing cannot begin.
- OSRM returning one route, malformed alternatives, or duplicate alternatives must remain recoverable.

---

### Task 1: Route alternatives and pending-destination domain

**Files:**
- Modify: `src/navigation.ts`
- Modify: `src/navigation.test.ts`
- Modify: `app/src/main/java/com/example/viavox/model/NavigationModels.kt`
- Modify: `app/src/main/java/com/example/viavox/data/NavigationNetworkService.kt`
- Modify: `app/src/test/java/com/example/viavox/NavigationParsingTest.kt`

**Interfaces:**
- Produces web `parseOsrmRoutes(data, destination): NavigationRoute[]` and `rankNavigationRoutes(routes): NavigationRoute[]`.
- Produces Android `NavigationNetworkService.parseOsrmAlternatives(body, destination): List<NavigationRoute>` and `routeAlternatives(startLat, startLon, destination): Result<List<NavigationRoute>>`.
- Ranking orders by duration, then distance, and removes malformed or geometry-identical duplicates.

- [ ] Write failing web tests for one route, three alternatives, malformed entries, duplicate geometry, and duration/distance tie-breaking.
- [ ] Run `npm.cmd test -- src/navigation.test.ts` and verify failures are caused by the missing plural parser/ranker.
- [ ] Implement the minimal plural parser and ranking functions while retaining `parseOsrmResponse` as a compatibility wrapper for the first valid route.
- [ ] Run the focused web tests and verify they pass.
- [ ] Write failing Android parser tests for alternatives, malformed entries and ranking.
- [ ] Change the Android OSRM request from `alternatives=false` to `alternatives=3`, implement the plural parser, and retain the single-route method as a compatibility wrapper.
- [ ] Run the Android unit tests through an available Gradle distribution; if the checkout still lacks a wrapper, record the exact unavailable command rather than claiming Android validation.
- [ ] Commit only the domain, provider and test files.

### Task 2: Web normal-click selection and confirmation

**Files:**
- Modify: `src/main.tsx`
- Modify: `src/style.css`
- Test: `src/navigation.test.ts`

**Interfaces:**
- Consumes `parseOsrmRoutes` and `rankNavigationRoutes` from Task 1.
- Produces `pendingDestination: SearchPlace | null`, a background `onMapClick(latitude, longitude)` callback, and route-selection state.

- [ ] Add failing state tests proving selection is pending before confirmation, cancellation returns to idle, and stale route results cannot replace a newer map point.
- [ ] Run the focused test and verify the new assertions fail for the missing state transitions.
- [ ] Replace the MapLibre `contextmenu`-only destination callback with a normal map `click` handler that ignores interactive layers and map gestures already consumed by feature handlers; retain long-press/right-click only as an optional equivalent action.
- [ ] Change `selectMapDestination` to store and render the pending point without calling OSRM.
- [ ] Add a compact pending card with **Navigate here** and **Cancel**; confirmation requires a usable GPS fix and then requests alternatives with `alternatives=3`.
- [ ] Add a route-choice preview, recommended-route label, and the text **No live traffic data** and **No verified risk zones available**.
- [ ] Preserve existing **Start navigation**, stop, reroute and arrival behavior for the chosen route.
- [ ] Run `npm.cmd test`, `npm.cmd run build`, and `git diff --check`.
- [ ] Perform desktop and mobile browser checks for point selection, feature-click exclusion, cancellation, preview and navigation start.
- [ ] Commit only the web files owned by this task.

### Task 3: Android normal-click selection and confirmation

**Files:**
- Modify: `app/src/main/java/com/example/viavox/ui/components/OpenStreetMapComponent.kt`
- Modify: `app/src/main/java/com/example/viavox/viewmodel/NavigationViewModel.kt`
- Modify: `app/src/main/java/com/example/viavox/ui/components/NavigationControls.kt`
- Modify: `app/src/main/java/com/example/viavox/ui/screens/ViaVoxMainScreen.kt`
- Create: `app/src/test/java/com/example/viavox/NavigationViewModelTest.kt`

**Interfaces:**
- Consumes Android route alternatives from Task 1.
- Produces `selectMapPoint(latitude, longitude)` as a pending-only transition and `confirmSelectedDestination()` as the only route-request transition for map-selected points.

- [ ] Write failing ViewModel tests proving a map click selects without network routing, confirmation requests routing, cancellation clears the point, and a stale response is ignored.
- [ ] Run the focused Android test and verify the failures are caused by current immediate calculation.
- [ ] Extend the existing `addOnMapClickListener`: ArcGIS hits keep their current selection behavior; otherwise the background coordinate calls the destination callback and returns `true`. Keep long-press as an equivalent shortcut.
- [ ] Split `selectMapPoint` from `confirmSelectedDestination` in the ViewModel and expose alternative selection without breaking search-result selection.
- [ ] Update `NavigationRouteCard`: when selected with no route, show enabled **Navigate here**; while calculating, disable duplicate submission; after alternatives arrive, show the recommendation and **Start navigation**.
- [ ] Add truthful **No live traffic data** and **No verified risk zones available** labels without removing speedometer, radar or toll UI.
- [ ] Run Android unit tests and `assembleDebug`; if tooling is unavailable, report the missing wrapper/distribution as an unverified target.
- [ ] Verify on an emulator/device that tap, drag, POI tap, cancel and navigation start are distinct behaviors.
- [ ] Commit only the Android files owned by this task.

### Task 4: Integration documentation and final verification

**Files:**
- Modify: `README.md`
- Modify: `.github/workflows/web-build.yml` only if current triggers do not cover the touched files.

**Interfaces:**
- Consumes the completed web and Android flows.
- Produces accurate user instructions and an explicit capability matrix.

- [ ] Document normal-click selection, confirmation, alternative routes, local voice, existing radar/speedometer support and the absence of live traffic/risk data.
- [ ] Run the full web suite, TypeScript build, Vite production build and `git diff --check`.
- [ ] Run Android tests and debug assembly when the Gradle toolchain is available; do not use web evidence as APK evidence.
- [ ] Inspect the final diff and status to confirm unrelated CICOM, ArcGIS and user changes were not overwritten.
- [ ] Perform a final requirements review against the spec and record traffic/risk/road-sign corridor work as the next independent plan rather than presenting it as implemented.

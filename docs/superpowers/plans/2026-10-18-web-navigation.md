# Web Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add visible, usable destination search, map selection, routing, route progress, rerouting, and arrival to the ViaVox web application without a paid API or login.

**Architecture:** Keep provider parsing and navigation math in a pure TypeScript module covered by unit tests. The React screen owns browser/network state, while `MapCanvas` only renders the destination and route and reports map selections. Photon and OSRM remain replaceable best-effort providers; failures never stop the voice locator.

**Tech Stack:** React 19, TypeScript 5.8, Vite 7, Vitest, MapLibre GL, Photon, OSRM

**Spec:** `docs/superpowers/specs/2026-10-18-core-reliability-design.md`

## Global Constraints

- No login and no paid API.
- Preserve the destination-free voice locator as the primary independent flow.
- Preserve current uncommitted neighborhood/circumscription work.
- Search only after explicit user action; bound results to eight.
- Route only after destination selection or confirmed deviation.
- Show provider failures as recoverable UI states.

## Review Focus

- Empty or two-character searches must not call Photon.
- Malformed provider responses must produce a recoverable error, not crash the page.
- A later search must not be overwritten by an earlier slow response.
- Route progress must not jump to a distant future maneuver at a crossing.
- Arrival must be announced only once.

---

### Task 1: Pure navigation domain and provider parsing

**Files:**
- Create: `src/navigation.ts`
- Create: `src/navigation.test.ts`
- Modify: `package.json`
- Create: `package-lock.json`

**Interfaces:**
- Produces: `SearchPlace`, `RouteStep`, `NavigationRoute`, `parsePhotonResponse`, `parseOsrmResponse`, `findRouteProgress`, and `isSustainedDeviation`.

- [ ] Write failing tests for valid/malformed Photon and OSRM data, bounded results, route progress at crossings, deviation confirmation, and arrival threshold.
- [ ] Install deterministic web dependencies and run the focused test to observe the expected failures.
- [ ] Implement the pure module with validated coordinates and bounded collections.
- [ ] Run the focused tests and the full web test suite.
- [ ] Commit only task-owned files.

### Task 2: Search, selection, map route, and guidance UI

**Files:**
- Modify: `src/main.tsx`
- Modify: `src/style.css`
- Test: `src/navigation.test.ts`

**Interfaces:**
- Consumes: pure navigation types and functions from Task 1.
- Produces: destination search panel, selectable results, long-press map destination, route overlay, route summary, start/stop controls, turn guidance, rerouting, and arrival state.

- [ ] Add failing state-transition tests for destination selection, stale request rejection, start/stop, reroute cooldown, and one-time arrival.
- [ ] Run tests to observe behavior failures.
- [ ] Integrate explicit Photon search and OSRM route requests with abort controllers and user-facing errors.
- [ ] Extend `MapCanvas` to render route/destination and accept a long-press destination.
- [ ] Add responsive controls that remain usable beside the existing voice-location card.
- [ ] Run tests, typecheck, and production web build.
- [ ] Commit only the web navigation files, preserving pre-existing edits.

### Task 3: Final web verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `.github/workflows/web-build.yml`

**Interfaces:**
- Consumes: the completed web navigation flow.
- Produces: reproducible local/CI commands and accurate zero-cost-provider limitations.

- [ ] Change CI installation to `npm ci` and include lock/config paths in triggers.
- [ ] Document `npm install`, `npm run dev`, navigation behavior, provider limitations, and the independent voice locator.
- [ ] Run `npm test`, `npm run build`, and `git diff --check`.
- [ ] Inspect the final diff for unrelated or overwritten local changes.

# ViaVox Zero-Cost Safe Navigation Design

## Objective

Extend ViaVox so a person can click any selectable point on the web map, confirm **Navigate here**, receive route choices and follow visual and spoken guidance. The destination-free voice locator remains independently usable. No login, paid API key, or billable provider is required.

## Product truth

- The public OSRM service provides road-network routing, not guaranteed live traffic for Brazil.
- The interface must say **No live traffic data** whenever it cannot prove that current traffic influenced a route.
- ViaVox must not infer that a district, neighborhood, police jurisdiction, or socioeconomic area is dangerous.
- Risk avoidance operates only on explicitly configured, sourced and time-bounded risk zones. With no verified zones, the interface says **No verified risk zones available** and does not alter the route.
- Radar, toll, sign and speed-limit coverage depends on OpenStreetMap data and can be incomplete or outdated.

## User flow

1. A normal click on an unhandled map point creates a temporary destination marker.
2. A compact destination card displays coordinates or reverse-geocoded place information and the actions **Navigate here** and **Cancel**.
3. **Navigate here** requests route alternatives only after the explicit confirmation.
4. The route preview lists available routes with distance, estimated duration and applicable warnings. ViaVox recommends the shortest-duration valid route after safety exclusions.
5. The user starts navigation explicitly.
6. During navigation, ViaVox shows the next maneuver, remaining distance, estimated duration, GPS speed and known road alerts, while speaking important guidance.
7. Confirmed sustained deviation triggers a rate-limited recalculation. Arrival is announced once.

Clicks consumed by existing district, CICOM, marker or map-control interactions do not select a destination. Keyboard users can activate the same destination and navigation actions through semantic buttons.

## Route selection

The client asks OSRM for up to three alternatives where the public profile supports them. Responses are validated before reaching MapLibre. Each valid route receives:

- duration and distance from OSRM;
- intersections with configured risk-zone polygons;
- known road-alert counts along a bounded route corridor;
- a clear traffic-data capability flag.

Routes intersecting an enabled verified risk zone are rejected when another valid route exists. If every route intersects a zone, ViaVox presents the alternatives with a warning instead of silently refusing navigation. The recommendation is the valid route with the lowest duration; distance breaks ties.

This score never claims current congestion without a verified traffic feed. GPS speed may describe the user's current movement but cannot predict traffic ahead.

## Risk-zone data

Risk zones are optional GeoJSON polygons shipped or fetched from a configured trusted source. Every zone requires:

- stable identifier and human-readable label;
- source name and source date;
- severity/category defined by that source;
- valid polygon geometry;
- expiry or review date.

Invalid, expired or unsourced zones are ignored. Police jurisdiction layers remain informational and are never converted into risk zones. The web client performs local point/segment intersection checks and transmits no additional personal data for this feature.

## Road alerts and speed

OpenStreetMap/Overpass supplies only available mapped features near the active route, including fixed speed cameras, toll booths, traffic signals/signs and `maxspeed` values. Queries are corridor-bounded, cached and rate-limited. Provider failure is recoverable and never blocks navigation.

The speedometer uses browser geolocation speed when finite and reliable. Implausible or unavailable values display `--`. Speed-limit comparison is informational: missing limits do not default to a fabricated value. Warnings are deduplicated and announced once per approach.

## Components and state

### `MapCanvas`

- Emits a normal background-map selection.
- Stops selection when an existing interactive layer or control handled the event.
- Renders pending destination, route alternatives, selected route and alert markers.

### Navigation domain

Pure TypeScript owns provider parsing, alternative ranking, risk-zone intersection, route progress, deviation confirmation, alert deduplication and arrival. The React screen owns requests, abort controllers and presentation.

### React screen

Uses explicit states: `idle`, `destination-selected`, `loading-routes`, `route-preview`, `navigating`, `arrived`, and recoverable `error`. A new selection or request aborts obsolete work so a slow response cannot overwrite a newer choice.

## Voice behavior

Speech uses the browser/device `speechSynthesis` API only. Maneuvers, material route changes, overspeed warnings and nearby road alerts have priority. Informational location narration yields to active navigation and resumes without login or cloud speech.

## Provider discipline

- Search: Photon, only after explicit search.
- Reverse geocoding: Nominatim, rate-limited and cached.
- Routing: public OSRM demo service for development and low-volume use.
- Road data: Overpass, bounded and cached.
- Map: MapLibre with the existing OpenFreeMap style.

Requests have timeouts, cancellation, stale-result rejection and user-facing recovery. The design keeps provider adapters replaceable because public community services offer no production SLA. High-volume deployment requires self-hosting or an explicitly approved provider, but the application remains functional at zero direct API cost for its intended development/low-volume use.

## Security and privacy

- No API keys, credentials or precise-location history are stored in the repository.
- Destination and live coordinates remain in memory unless a future opt-in save feature is added.
- Provider error bodies and internal stack traces are not rendered to users.
- External response coordinates, strings and GeoJSON are validated before use.
- Links or redirects derived from provider data are not executed.

## Responsive and accessible behavior

The destination card and route selector remain above the existing bottom voice card on small screens and do not cover primary map controls. Controls are touch-sized, keyboard accessible, labeled and do not rely only on color. Route warnings include text and icons.

## Verification

Automated tests cover:

- normal map click selection versus consumed layer clicks;
- explicit confirmation before routing;
- malformed and stale provider responses;
- alternative route ranking and tie-breaking;
- verified, expired and malformed risk zones;
- all-routes-risky fallback;
- missing or implausible speed values;
- route-corridor alert filtering and deduplication;
- reroute cooldown and one-time arrival;
- provider failures that preserve the voice locator.

Final validation includes the TypeScript build, unit suite, production Vite build, `git diff --check`, a local HTTP smoke test and separate desktop/mobile browser checks. Android remains a separate target and requires its own build evidence.

## Completion criteria

- One normal click selects a map destination and visibly offers **Navigate here**.
- Routing starts only after confirmation and presents valid alternatives.
- Guidance, rerouting, arrival and local voice work without login or paid APIs.
- Speedometer and available route alerts are visible without fabricated data.
- Risk and traffic limitations are explicit and technically enforced.
- The original destination-free voice locator continues to work independently.

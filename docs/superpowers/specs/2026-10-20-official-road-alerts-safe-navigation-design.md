# ViaVox Official Road Alerts and Clean Navigation Design

## Objective

Make navigation useful while driving: use verified Manaus radar data, preserve the existing free routing stack, avoid unsuitable roads when OpenStreetMap explicitly marks them, and present location and guidance in a compact driving interface.

## Product boundaries

- The feature runs without login, paid API keys, or a required commercial provider.
- IMMU radar data is treated as an official, curated source; OpenStreetMap/Overpass remains a supplemental source only.
- The application must not infer danger from neighborhood, socioeconomic profile, police jurisdiction, or informal reputation.
- Route avoidance based on safety is permitted only for a sourced, dated, geometrically valid zone explicitly configured as an opt-in verified restriction. No such source is enabled in this delivery.
- The application must never claim live traffic, real-time signal phase, crime prediction, or a safer route when it cannot prove that result.

## Road-alert data

### Official Manaus radar base

The repository contains a reviewed GeoJSON feature collection for Manaus road alerts. Each feature requires:

- stable `id`;
- `type`: `speed_camera`, `speed_hump_camera`, or `red_light_camera`;
- point geometry in WGS84;
- `sourceName`, `sourceUrl`, `sourceDate`, and `reviewedAt`;
- human-readable `location` and optional `direction` and `maxspeedKmh`.

The browser and Android client load this local data first. Existing Overpass results are merged only when they do not duplicate an official point. Alerts announce type, distance, and known speed limit. They do not announce a fabricated speed limit or signal timing.

### Update discipline

The GeoJSON is not fetched at runtime from an HTML page. An update must be reviewed, include its source date, and pass schema validation in tests. The UI states the source and review date so stale coverage is visible.

## Route suitability

The navigation domain classifies road metadata returned by the route provider or available in an OSM route-data adapter:

- hard reject: `access=private`, `motor_vehicle=no`, `vehicle=no`, or `highway=construction`;
- avoid when an alternative exists: `noexit=yes`, `highway=service` with `service=driveway|alley|parking_aisle`, and `access=destination`;
- never reject the final destination approach merely because it matches an avoidable classification.

OSRM alternatives are ranked only after malformed and duplicate alternatives are discarded. If road metadata cannot be verified for an alternative, it remains eligible and the UI does not claim that unsuitable roads were avoided.

## Navigation interface

Entering active navigation switches to a driving mode. Search results, layer controls, destination-selection cards, and nonessential map HUDs are hidden. A compact, accessible bottom card remains visible and contains:

- next maneuver and distance to it;
- remaining route distance and estimated duration;
- current GPS speed or `--` when unavailable/unreliable;
- current textual location, formatted as road and neighborhood when available;
- the most relevant upcoming official road alert.

The map remains interactive for pan/zoom, but a normal background tap during active navigation does not replace the destination accidentally. Ending navigation restores the regular interface.

## Current location

Reverse geocoding is request-cancelled, cached by rounded coordinates, and rate-limited. It runs only after the person moves materially or after a minimum interval. The visual text updates when a usable road/neighborhood result is available. Voice location narration is user initiated and never preempts a maneuver, arrival, or road-alert announcement.

## Provider adapters

Public OSRM, Photon, Nominatim and Overpass retain their existing low-volume discipline. Provider adapters for HERE, TomTom, and Waze are not enabled or bundled with credentials. Waze for Cities requires approved partnership; HERE and TomTom require account credentials and cost-policy approval. A future adapter must be server mediated so no commercial API key is exposed in the web client.

## Failure handling

- Invalid or stale official alert data is rejected during validation and does not block map use.
- Failure to load optional OSM road metadata, traffic, or reverse geocoding leaves navigation active with an explicit neutral state.
- A destination on a dead-end or restricted-looking segment remains routable; the app warns only if the provider supplies a concrete restriction.
- A missing location, speed, road name, or neighborhood renders as an unavailable value, not invented text.

## Web and Android parity

Both targets receive the same official radar base, alert classifications, deduplication semantics, and pending-destination behavior. Web implements the driving card and data loader in TypeScript. Android implements equivalent model parsing and alert presentation in Kotlin. Each platform needs independent build evidence.

## Verification and completion criteria

- Schema tests reject alert features missing provenance, invalid coordinates, invalid type, or invalid geometry.
- Merge tests prove official alerts win over nearby duplicate OSM data.
- Route-suitability tests prove private/no-exit metadata is avoided only when a valid alternative remains and does not prevent arrival at the selected destination.
- Location formatter tests cover street, neighborhood, unavailable data, cache/rate limit, and stale requests.
- Navigation-mode UI tests cover entering/exiting driving mode and hiding nonessential controls.
- The web unit suite, type check, Vite production build, and `git diff --check` pass.
- Android unit/build validation is reported separately and is not implied by web validation.

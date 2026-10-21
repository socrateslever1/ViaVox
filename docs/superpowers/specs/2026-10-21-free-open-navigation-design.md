# ViaVox Free and Open Navigation Design

## Goal

Preserve the useful spoken-navigation behavior from 0.7.1 while making the
Android APK and web application depend only on free, open-source technology
and legally reusable map data.

## Voice contract

- Announce a zone once when entering it.
- Announce a neighbourhood once, immediately after the transition is
  confirmed.
- Announce a road once, immediately after the road transition is confirmed.
- Keep the order Zone -> Neighbourhood -> Road -> CICOM in one structured
  speech event so duplicate callbacks cannot repeat the same information.
- CICOM speech is optional. When enabled, say `Area da <ordinal> CICOM` and do
  not claim that the same-numbered DIP has the same jurisdiction.
- At boundaries, require stable polygon evidence before changing the spoken
  neighbourhood. Do not alternate between names because of GPS noise.

## Places of interest

- Ordinary commerce and unreliable map labels remain silent.
- For a reliable nearby reference, say that the user is passing it and include
  left or right when direction is known.
- Say `Voce esta em <place>` only when the application has arrival/inside
  evidence, never merely because a point is within a distance radius.
- A POI is not repeated during the same pass.

## Map and navigation

- Default to a flat 2D road map. Remove the 3D perspective toggle.
- During active navigation, orient the camera using route/GPS course and keep
  the road ahead visible. Device compass remains useful outside navigation.
- Provide an optional open satellite/reference layer only when its license and
  tile terms permit production use. It must be labelled as reference imagery,
  include attribution, and fall back to the road map.
- Android navigation continues in its foreground location service with a
  persistent, closable control. A system overlay is not mandatory and no
  special draw-over-other-apps permission is added without an explicit choice.
- Web navigation is best-effort while the page/PWA is active; the web platform
  cannot guarantee background GPS and speech.

## Zero-cost and security constraints

- No mandatory API key, billing account, credit card, Google Maps Platform or
  Gemini dependency.
- Do not embed provider secrets in the APK or web bundle.
- Use MapLibre, OpenStreetMap-derived data, OSRM, Nominatim/Photon and native
  device TTS under their licenses and public-service usage policies.
- Public community tile services are interactive-view only: identify ViaVox,
  retain attribution, respect cache headers and never bulk-download/offline
  prefetch from a server that forbids it.

## Data provenance

- Resolve Manaus neighbourhoods from polygon GeoJSON rather than overlapping
  hand-written rectangles.
- Record the dataset source and update date in project documentation.
- Treat CICOM and DIP as separate institutions and datasets. Unknown or
  unverified jurisdiction remains unspoken instead of being invented.

## Platforms and validation

- Android and web share behavior goals but use platform-appropriate lifecycle
  and map implementations.
- Unit tests cover speech composition, polygon containment/boundaries, camera
  heading choice and POI wording.
- Web tests and build, Android unit tests/build, diff checks and a final source
  review are required. Device route behavior remains explicitly unverified
  until tested outdoors on an APK.

import { describe, expect, it } from 'vitest';
import {
  createNavigationSession,
  findRouteProgress,
  isArrival,
  shouldApplyRequestResult,
  isSustainedDeviation,
  parseOsrmRoutes,
  parseOsrmResponse,
  parsePhotonResponse,
  navigationCameraTarget,
  routeBearingAtPosition,
  selectNavigationRoute,
  startNavigation,
  stopNavigation,
  updateNavigationSession,
  type NavigationRoute,
  type SearchPlace
} from './navigation';

describe('navigation camera', () => {
  it('uses the road ahead instead of the phone orientation', () => {
    const route: NavigationRoute = {
      destination,
      distanceMeters: 200,
      durationSeconds: 30,
      coordinates: [
        { latitude: -3.1000, longitude: -60.0000 },
        { latitude: -3.1000, longitude: -59.9990 },
        { latitude: -3.1000, longitude: -59.9980 }
      ],
      steps: []
    };
    const bearing = routeBearingAtPosition(route, { latitude: -3.1000, longitude: -59.9997 });
    expect(bearing).toBeCloseTo(90, 1);
    const target = navigationCameraTarget(
      { latitude: -3.1000, longitude: -59.9997 },
      bearing!,
      55
    );
    expect(target.longitude).toBeGreaterThan(-59.9997);
    expect(target.latitude).toBeCloseTo(-3.1000, 5);
  });

  it('accepts the compact longitude-latitude route used by the map', () => {
    expect(routeBearingAtPosition(
      { coordinates: [[-60.0000, -3.1000], [-59.9990, -3.1000]] },
      { latitude: -3.1000, longitude: -59.9998 }
    )).toBeCloseTo(90, 1);
  });
});

const destination: SearchPlace = {
  id: 'destination',
  name: 'Teatro Amazonas',
  detail: 'Centro, Manaus',
  latitude: -3.1303,
  longitude: -60.0234
};

describe('parsePhotonResponse', () => {
  it('returns at most eight valid named places and ignores malformed features', () => {
    const valid = Array.from({ length: 10 }, (_, index) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [-60.02 - index / 1000, -3.1] },
      properties: { osm_id: index + 1, osm_type: 'N', name: `Local ${index + 1}`, city: 'Manaus' }
    }));
    const result = parsePhotonResponse({
      type: 'FeatureCollection',
      features: [
        { geometry: { coordinates: ['invalid', null] }, properties: { name: 'Inválido' } },
        ...valid
      ]
    });

    expect(result).toHaveLength(8);
    expect(result[0]).toEqual({
      id: 'N:1',
      name: 'Local 1',
      detail: 'Manaus',
      latitude: -3.1,
      longitude: -60.02
    });
  });

  it('returns an empty list for a malformed response', () => {
    expect(parsePhotonResponse({ features: 'not-an-array' })).toEqual([]);
  });
});

describe('parseOsrmResponse', () => {
  it('parses, ranks, and deduplicates valid route alternatives', () => {
    const routes = parseOsrmRoutes({
      code: 'Ok',
      routes: [
        {
          distance: 2100,
          duration: 420,
          geometry: { coordinates: [[-60.03, -3.11], [-60.0234, -3.1303]] },
          legs: [{ steps: [] }]
        },
        {
          distance: 1600,
          duration: 360,
          geometry: { coordinates: [[-60.032, -3.11], [-60.0234, -3.1303]] },
          legs: [{ steps: [] }]
        },
        {
          distance: 1400,
          duration: 360,
          geometry: { coordinates: [[-60.031, -3.11], [-60.0234, -3.1303]] },
          legs: [{ steps: [] }]
        },
        {
          distance: 1400,
          duration: 360,
          geometry: { coordinates: [[-60.031, -3.11], [-60.0234, -3.1303]] },
          legs: [{ steps: [] }]
        },
        { distance: 1, duration: 1, geometry: { coordinates: [['invalid', null]] } }
      ]
    }, destination);

    expect(routes).toHaveLength(3);
    expect(routes.map(route => [route.durationSeconds, route.distanceMeters])).toEqual([
      [360, 1400],
      [360, 1600],
      [420, 2100]
    ]);
  });

  it('parses route geometry and Portuguese maneuver instructions', () => {
    const route = parseOsrmResponse({
      code: 'Ok',
      routes: [{
        distance: 1800,
        duration: 420,
        geometry: { coordinates: [[-60.03, -3.11], [-60.025, -3.12], [-60.0234, -3.1303]] },
        legs: [{ steps: [{
          distance: 350,
          name: 'Avenida Eduardo Ribeiro',
          maneuver: { type: 'turn', modifier: 'right', location: [-60.025, -3.12] }
        }] }]
      }]
    }, destination);

    expect(route.distanceMeters).toBe(1800);
    expect(route.coordinates).toHaveLength(3);
    expect(route.steps[0]).toMatchObject({
      instruction: 'Vire à direita na Avenida Eduardo Ribeiro',
      geometryIndex: 1
    });
  });

  it('rejects a response without usable route geometry', () => {
    expect(() => parseOsrmResponse({ code: 'Ok', routes: [{ geometry: { coordinates: [] } }] }, destination))
      .toThrow('Rota sem geometria válida');
  });

  it('rejects non-numeric route coordinates instead of passing invalid data to the map', () => {
    expect(() => parseOsrmResponse({
      code: 'Ok',
      routes: [{ geometry: { coordinates: [['invalid', null]] } }]
    }, destination)).toThrow('Rota sem geometria válida');
  });
});

describe('route progress', () => {
  const loopRoute: NavigationRoute = {
    destination,
    distanceMeters: 3000,
    durationSeconds: 600,
    coordinates: [
      { latitude: 0, longitude: 0 },
      { latitude: 0, longitude: 0.001 },
      { latitude: 0.001, longitude: 0.001 },
      { latitude: 0.001, longitude: 0 },
      { latitude: 0, longitude: 0 }
    ],
    steps: [
      { latitude: 0, longitude: 0.001, instruction: 'Primeira conversão', distanceMeters: 100, geometryIndex: 1 },
      { latitude: 0.001, longitude: 0, instruction: 'Segunda conversão', distanceMeters: 100, geometryIndex: 3 },
      { latitude: 0, longitude: 0, instruction: 'Chegada', distanceMeters: 0, geometryIndex: 4 }
    ]
  };

  it('does not jump to the final maneuver where a route crosses its starting point', () => {
    const progress = findRouteProgress(loopRoute, { latitude: 0, longitude: 0.0001 }, 0, 0);

    expect(progress.geometryIndex).toBeLessThanOrEqual(1);
    expect(progress.stepIndex).toBe(0);
    expect(progress.nextInstruction).toBe('Primeira conversão');
  });

  it('requires three consecutive off-route fixes before rerouting', () => {
    expect(isSustainedDeviation([180, 190], 20)).toBe(false);
    expect(isSustainedDeviation([180, 190, 170], 20)).toBe(true);
    expect(isSustainedDeviation([180, 20, 190], 20)).toBe(false);
  });

  it('accepts arrival inside 35 meters and rejects a farther point', () => {
    expect(isArrival(34.9)).toBe(true);
    expect(isArrival(35.1)).toBe(false);
  });
});

describe('navigation session', () => {
  const route: NavigationRoute = {
    destination,
    distanceMeters: 1200,
    durationSeconds: 300,
    coordinates: [
      { latitude: -3.12, longitude: -60.03 },
      { latitude: -3.125, longitude: -60.026 },
      destination
    ],
    steps: [{
      latitude: -3.125,
      longitude: -60.026,
      instruction: 'Vire à direita',
      distanceMeters: 400,
      geometryIndex: 1
    }]
  };

  it('moves from route preview to navigation and back to idle', () => {
    const preview = selectNavigationRoute(createNavigationSession(), route);
    expect(preview.status).toBe('preview');

    const navigating = startNavigation(preview);
    expect(navigating.status).toBe('navigating');
    expect(navigating.nextInstruction).toBe('Vire à direita');

    expect(stopNavigation(navigating)).toEqual(createNavigationSession());
  });

  it('rejects stale or aborted provider responses', () => {
    expect(shouldApplyRequestResult(4, 4, false)).toBe(true);
    expect(shouldApplyRequestResult(3, 4, false)).toBe(false);
    expect(shouldApplyRequestResult(4, 4, true)).toBe(false);
  });

  it('requests rerouting only after sustained deviation and outside cooldown', () => {
    let session = startNavigation(selectNavigationRoute(createNavigationSession(), route));
    const farAway = { latitude: -3.2, longitude: -60.2 };

    session = updateNavigationSession(session, farAway, 10, 100_000).session;
    session = updateNavigationSession(session, farAway, 10, 101_000).session;
    const deviation = updateNavigationSession(session, farAway, 10, 102_000);
    expect(deviation.shouldReroute).toBe(true);

    session = updateNavigationSession(deviation.session, farAway, 10, 103_000).session;
    session = updateNavigationSession(session, farAway, 10, 104_000).session;
    const cooldown = updateNavigationSession(session, farAway, 10, 105_000);
    expect(cooldown.shouldReroute).toBe(false);
  });

  it('announces arrival once', () => {
    const navigating = startNavigation(selectNavigationRoute(createNavigationSession(), route));
    const arrived = updateNavigationSession(navigating, destination, 8, 200_000);
    expect(arrived.session.status).toBe('arrived');
    expect(arrived.announcement).toBe('Você está em Teatro Amazonas.');

    const repeated = updateNavigationSession(arrived.session, destination, 8, 201_000);
    expect(repeated.announcement).toBeNull();
  });
});

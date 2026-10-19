import { describe, expect, it } from 'vitest';
import {
  findRouteProgress,
  isArrival,
  isSustainedDeviation,
  parseOsrmResponse,
  parsePhotonResponse,
  type NavigationRoute,
  type SearchPlace
} from './navigation';

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

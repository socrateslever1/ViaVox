import { describe, expect, it } from 'vitest';
import { mergeRoadAlerts, parseOfficialRoadAlerts, parseOsmRoadAlerts } from './roadAlerts';

const feature = { type: 'Feature', geometry: { type: 'Point', coordinates: [-60.02, -3.1] }, properties: { id: 'immu:1', type: 'speed_camera', location: 'Av. Teste', sourceName: 'IMMU', sourceUrl: 'https://immu.manaus.am.gov.br/index.php?r=site/inforadar', sourceDate: '2026-06-28', reviewedAt: '2026-10-20' } };

describe('official road alerts', () => {
  it('accepts only reviewed official point features', () => {
    expect(parseOfficialRoadAlerts({ type: 'FeatureCollection', features: [feature, { ...feature, properties: { ...feature.properties, sourceName: '' } }, { ...feature, geometry: { type: 'Point', coordinates: ['bad', -3.1] } }, { ...feature, properties: { ...feature.properties, type: 'unknown' } }] })).toEqual([expect.objectContaining({ id: 'immu:1', type: 'speed_camera' })]);
  });
  it('keeps an official alert when OSM has a nearby duplicate', () => {
    const official = parseOfficialRoadAlerts({ type: 'FeatureCollection', features: [feature] });
    expect(mergeRoadAlerts(official, [{ id: 'osm:1', type: 'speed_camera', lat: -3.1001, lon: -60.0201 }])).toHaveLength(1);
  });

  it('rejects malformed collections, non-point geometry and out-of-range coordinates', () => {
    expect(parseOfficialRoadAlerts({ features: [feature] })).toEqual([]);
    expect(parseOfficialRoadAlerts({
      type: 'FeatureCollection',
      features: [{ ...feature, geometry: { type: 'LineString', coordinates: [-60.02, -3.1] } }]
    })).toEqual([]);
    expect(parseOfficialRoadAlerts({
      type: 'FeatureCollection',
      features: [{ ...feature, geometry: { type: 'Point', coordinates: [-181, 91] } }]
    })).toEqual([]);
  });

  it('rejects invalid provenance URLs and dates', () => {
    expect(parseOfficialRoadAlerts({
      type: 'FeatureCollection',
      features: [{ ...feature, properties: { ...feature.properties, sourceUrl: 'javascript:alert(1)' } }]
    })).toEqual([]);
    expect(parseOfficialRoadAlerts({
      type: 'FeatureCollection',
      features: [{ ...feature, properties: { ...feature.properties, reviewedAt: '20/10/2026' } }]
    })).toEqual([]);
  });

  it('does not discard a nearby OSM alert of a different type', () => {
    const official = parseOfficialRoadAlerts({ type: 'FeatureCollection', features: [feature] });
    expect(mergeRoadAlerts(official, [{ id: 'osm:toll', type: 'toll_booth', lat: -3.1001, lon: -60.0201 }]))
      .toEqual([expect.objectContaining({ id: 'immu:1' }), expect.objectContaining({ id: 'osm:toll' })]);
  });
});

describe('OSM road alerts', () => {
  it('validates Overpass elements before exposing them to the map', () => {
    expect(parseOsmRoadAlerts({
      elements: [
        { id: 10, lat: -3.1, lon: -60.02, tags: { highway: 'speed_camera', maxspeed: '60 km/h' } },
        { id: 11, lat: -3.11, lon: -60.03, tags: { barrier: 'toll_booth' } },
        { id: 12, lat: 'bad', lon: -60.03, tags: { highway: 'speed_camera' } },
        { id: 13, lat: 95, lon: -60.03, tags: { highway: 'speed_camera' } },
        { id: 14, lat: -3.12, lon: -60.04, tags: { amenity: 'school' } }
      ]
    })).toEqual([
      expect.objectContaining({ id: 'osm:10', type: 'speed_camera', maxspeedKmh: 60 }),
      expect.objectContaining({ id: 'osm:11', type: 'toll_booth' })
    ]);
  });
});

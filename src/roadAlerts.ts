export type RoadAlertType =
  | 'speed_camera'
  | 'speed_hump_camera'
  | 'red_light_camera'
  | 'toll_booth';

export interface RoadAlert {
  id: string;
  type: RoadAlertType;
  lat: number;
  lon: number;
  location?: string;
  direction?: string;
  maxspeedKmh?: number;
  source?: 'official' | 'osm';
}

export interface OfficialRoadAlert extends RoadAlert {
  source: 'official';
  sourceName: string;
  sourceUrl: string;
  sourceDate: string;
  reviewedAt: string;
}

const OFFICIAL_TYPES = new Set<RoadAlertType>([
  'speed_camera',
  'speed_hump_camera',
  'red_light_camera'
]);
const EARTH_RADIUS_METERS = 6_371_000;
const DUPLICATE_DISTANCE_METERS = 45;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonEmptyString(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized ? normalized : null;
}

function isCoordinate(lat: unknown, lon: unknown): lat is number {
  return typeof lat === 'number' && Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
    typeof lon === 'number' && Number.isFinite(lon) && lon >= -180 && lon <= 180;
}

function isIsoDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return date.toISOString().slice(0, 10) === value;
}

function isHttpUrl(value: string): boolean {
  try {
    const protocol = new URL(value).protocol;
    return protocol === 'http:' || protocol === 'https:';
  } catch {
    return false;
  }
}

function optionalSpeed(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 && value <= 300
    ? value
    : undefined;
}

export function parseOfficialRoadAlerts(input: unknown): OfficialRoadAlert[] {
  if (!isRecord(input) || input.type !== 'FeatureCollection' || !Array.isArray(input.features)) return [];

  const alerts: OfficialRoadAlert[] = [];
  const seen = new Set<string>();
  for (const rawFeature of input.features) {
    if (!isRecord(rawFeature) || rawFeature.type !== 'Feature') continue;
    const geometry = rawFeature.geometry;
    const properties = rawFeature.properties;
    if (!isRecord(geometry) || geometry.type !== 'Point' || !Array.isArray(geometry.coordinates)) continue;
    if (!isRecord(properties)) continue;

    const [lon, lat] = geometry.coordinates;
    const id = nonEmptyString(properties.id);
    const type = properties.type;
    const location = nonEmptyString(properties.location);
    const sourceName = nonEmptyString(properties.sourceName);
    const sourceUrl = nonEmptyString(properties.sourceUrl);
    const sourceDate = nonEmptyString(properties.sourceDate);
    const reviewedAt = nonEmptyString(properties.reviewedAt);
    if (!id || seen.has(id) || !OFFICIAL_TYPES.has(type as RoadAlertType) || !isCoordinate(lat, lon) ||
      !location || !sourceName || !sourceUrl || !isHttpUrl(sourceUrl) ||
      !sourceDate || !isIsoDate(sourceDate) || !reviewedAt || !isIsoDate(reviewedAt)) continue;

    seen.add(id);
    alerts.push({
      id,
      type: type as RoadAlertType,
      lat,
      lon,
      location,
      direction: nonEmptyString(properties.direction) ?? undefined,
      maxspeedKmh: optionalSpeed(properties.maxspeedKmh),
      source: 'official',
      sourceName,
      sourceUrl,
      sourceDate,
      reviewedAt
    });
  }
  return alerts;
}

export function parseOsmRoadAlerts(input: unknown): RoadAlert[] {
  if (!isRecord(input) || !Array.isArray(input.elements)) return [];

  const alerts: RoadAlert[] = [];
  const seen = new Set<string>();
  for (const rawElement of input.elements) {
    if (!isRecord(rawElement) || !isRecord(rawElement.tags) || !isCoordinate(rawElement.lat, rawElement.lon)) continue;
    const lat = rawElement.lat;
    const lon = rawElement.lon as number;
    const idValue = rawElement.id;
    if (typeof idValue !== 'string' && typeof idValue !== 'number') continue;
    const id = `osm:${idValue}`;
    if (seen.has(id)) continue;

    const type: RoadAlertType | null = rawElement.tags.highway === 'speed_camera'
      ? 'speed_camera'
      : rawElement.tags.barrier === 'toll_booth'
        ? 'toll_booth'
        : null;
    if (!type) continue;

    const speedText = nonEmptyString(rawElement.tags.maxspeed);
    const speedMatch = speedText ? /^(\d{1,3})\b/.exec(speedText) : null;
    const parsedSpeed = speedMatch ? Number(speedMatch[1]) : undefined;
    seen.add(id);
    alerts.push({
      id,
      type,
      lat,
      lon,
      location: nonEmptyString(rawElement.tags.name) ?? undefined,
      maxspeedKmh: optionalSpeed(parsedSpeed),
      source: 'osm'
    });
  }
  return alerts;
}

function metersBetween(left: RoadAlert, right: RoadAlert): number {
  const lat1 = left.lat * Math.PI / 180;
  const lat2 = right.lat * Math.PI / 180;
  const deltaLat = (right.lat - left.lat) * Math.PI / 180;
  const deltaLon = (right.lon - left.lon) * Math.PI / 180;
  const a = Math.sin(deltaLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) ** 2;
  return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function isCamera(type: RoadAlertType): boolean {
  return type !== 'toll_booth';
}

function sameAlertFamily(left: RoadAlert, right: RoadAlert): boolean {
  return isCamera(left.type) === isCamera(right.type);
}

export function mergeRoadAlerts(official: OfficialRoadAlert[], osm: RoadAlert[]): RoadAlert[] {
  const merged: RoadAlert[] = [...official];
  for (const alert of osm) {
    const isDuplicate = merged.some(existing =>
      sameAlertFamily(existing, alert) && metersBetween(existing, alert) < DUPLICATE_DISTANCE_METERS
    );
    if (!isDuplicate) merged.push({ ...alert, source: 'osm' });
  }
  return merged;
}

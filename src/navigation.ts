export interface SearchPlace {
  id: string;
  name: string;
  detail: string;
  latitude: number;
  longitude: number;
}

export interface NavigationStep {
  latitude: number;
  longitude: number;
  instruction: string;
  distanceMeters: number;
  geometryIndex: number;
}

export interface NavigationRoute {
  destination: SearchPlace;
  distanceMeters: number;
  durationSeconds: number;
  coordinates: { latitude: number; longitude: number }[];
  steps: NavigationStep[];
}

export function parsePhotonResponse(data: any): SearchPlace[] {
  if (!data || !Array.isArray(data.features)) return [];
  const results: SearchPlace[] = [];

  for (const f of data.features) {
    if (!f || !f.geometry || !Array.isArray(f.geometry.coordinates)) continue;
    const [lon, lat] = f.geometry.coordinates;
    if (typeof lon !== 'number' || typeof lat !== 'number' || !Number.isFinite(lon) || !Number.isFinite(lat)) {
      continue;
    }
    const props = f.properties || {};
    const name = props.name?.trim();
    if (!name) continue;

    const id = `${props.osm_type || 'N'}:${props.osm_id || results.length}`;
    const detail = [props.street, props.district, props.city, props.state].filter(Boolean).join(', ') || props.city || '';

    results.push({
      id,
      name,
      detail,
      latitude: lat,
      longitude: lon
    });

    if (results.length >= 8) break;
  }

  return results;
}

function formatManeuver(type: string, modifier: string | undefined, name: string): string {
  const street = name ? ` na ${name}` : '';
  if (type === 'depart') return `Siga em frente${street}`;
  if (type === 'arrive') return 'Você chegou ao seu destino';
  if (type === 'roundabout') return `Na rotatória, pegue a saída${street}`;

  switch (modifier) {
    case 'right':
      return `Vire à direita${street}`;
    case 'left':
      return `Vire à esquerda${street}`;
    case 'slight right':
      return `Mantenha-se à direita${street}`;
    case 'slight left':
      return `Mantenha-se à esquerda${street}`;
    case 'sharp right':
      return `Curva acentuada à direita${street}`;
    case 'sharp left':
      return `Curva acentuada à esquerda${street}`;
    case 'uturn':
      return 'Faça o retorno';
    case 'straight':
    default:
      return `Siga em frente${street}`;
  }
}

export function parseOsrmResponse(data: any, destination: SearchPlace): NavigationRoute {
  const route = data?.routes?.[0];
  const rawCoords: [number, number][] = route?.geometry?.coordinates;
  if (!rawCoords || rawCoords.length === 0) {
    throw new Error('Rota sem geometria válida');
  }

  const coordinates = rawCoords.map(([lon, lat]) => ({ latitude: lat, longitude: lon }));
  const steps: NavigationStep[] = [];

  const rawSteps = route.legs?.[0]?.steps || [];
  for (const st of rawSteps) {
    const loc = st.maneuver?.location;
    if (!loc) continue;
    const [lon, lat] = loc;

    // Find closest index in coordinates
    let bestIdx = 0;
    let bestDist = Infinity;
    coordinates.forEach((c, idx) => {
      const d = Math.hypot((c.latitude - lat) * 111139, (c.longitude - lon) * 111139);
      if (d < bestDist) {
        bestDist = d;
        bestIdx = idx;
      }
    });

    steps.push({
      latitude: lat,
      longitude: lon,
      instruction: formatManeuver(st.maneuver?.type, st.maneuver?.modifier, st.name),
      distanceMeters: st.distance || 0,
      geometryIndex: bestIdx
    });
  }

  return {
    destination,
    distanceMeters: route.distance || 0,
    durationSeconds: route.duration || 0,
    coordinates,
    steps
  };
}

export function findRouteProgress(
  route: NavigationRoute,
  userPos: { latitude: number; longitude: number },
  currentGeoIdx: number,
  currentStepIdx: number
) {
  // Search only in a forward window of the geometry
  const windowEnd = Math.min(route.coordinates.length, currentGeoIdx + 15);
  let bestGeoIdx = currentGeoIdx;
  let bestDist = Infinity;

  for (let i = currentGeoIdx; i < windowEnd; i++) {
    const c = route.coordinates[i];
    const d = Math.hypot((c.latitude - userPos.latitude) * 111139, (c.longitude - userPos.longitude) * 111139);
    if (d < bestDist) {
      bestDist = d;
      bestGeoIdx = i;
    }
  }

  // Find step corresponding to progress
  let stepIdx = currentStepIdx;
  for (let i = currentStepIdx; i < route.steps.length; i++) {
    if (route.steps[i].geometryIndex >= bestGeoIdx) {
      stepIdx = i;
      break;
    }
  }

  const nextInstruction = route.steps[stepIdx]?.instruction || 'Siga o percurso';

  return {
    geometryIndex: bestGeoIdx,
    stepIndex: stepIdx,
    nextInstruction
  };
}

export function isSustainedDeviation(distances: number[], threshold: number): boolean {
  if (distances.length < 3) return false;
  return distances.every(d => d > threshold);
}

export function isArrival(distMeters: number): boolean {
  return distMeters <= 35.0;
}

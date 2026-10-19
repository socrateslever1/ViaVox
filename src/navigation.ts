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

function parseOsrmRoute(route: any, destination: SearchPlace): NavigationRoute {
  const rawCoords: [number, number][] = route?.geometry?.coordinates;
  if (
    !Array.isArray(rawCoords) ||
    rawCoords.length === 0 ||
    rawCoords.some(point =>
      !Array.isArray(point) ||
      point.length < 2 ||
      !Number.isFinite(point[0]) ||
      !Number.isFinite(point[1])
    )
  ) {
    throw new Error('Rota sem geometria válida');
  }

  const coordinates = rawCoords.map(([lon, lat]) => ({ latitude: lat, longitude: lon }));
  const steps: NavigationStep[] = [];

  const rawSteps = route.legs?.[0]?.steps || [];
  for (const st of rawSteps) {
    const loc = st.maneuver?.location;
    if (!Array.isArray(loc) || !Number.isFinite(loc[0]) || !Number.isFinite(loc[1])) continue;
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

  const distanceMeters = Number(route.distance);
  const durationSeconds = Number(route.duration);
  if (!Number.isFinite(distanceMeters) || distanceMeters < 0 || !Number.isFinite(durationSeconds) || durationSeconds < 0) {
    throw new Error('Rota com dist\u00e2ncia ou dura\u00e7\u00e3o inv\u00e1lida');
  }

  return {
    destination,
    distanceMeters,
    durationSeconds,
    coordinates,
    steps
  };
}

function geometryKey(route: NavigationRoute): string {
  return route.coordinates.map(point => `${point.latitude},${point.longitude}`).join(';');
}

export function rankNavigationRoutes(routes: NavigationRoute[]): NavigationRoute[] {
  const uniqueRoutes = new Map<string, NavigationRoute>();
  for (const route of routes) {
    if (
      route.coordinates.length < 2 ||
      !Number.isFinite(route.distanceMeters) || route.distanceMeters < 0 ||
      !Number.isFinite(route.durationSeconds) || route.durationSeconds < 0
    ) {
      continue;
    }

    const key = geometryKey(route);
    const existing = uniqueRoutes.get(key);
    if (!existing || route.durationSeconds < existing.durationSeconds || (
      route.durationSeconds === existing.durationSeconds && route.distanceMeters < existing.distanceMeters
    )) {
      uniqueRoutes.set(key, route);
    }
  }

  return [...uniqueRoutes.values()].sort((first, second) =>
    first.durationSeconds - second.durationSeconds || first.distanceMeters - second.distanceMeters
  );
}

export function parseOsrmRoutes(data: any, destination: SearchPlace): NavigationRoute[] {
  if (!Array.isArray(data?.routes)) {
    throw new Error('Rota sem geometria v\u00e1lida');
  }

  const routes = data.routes.flatMap((route: any) => {
    try {
      return [parseOsrmRoute(route, destination)];
    } catch {
      return [];
    }
  });
  const rankedRoutes = rankNavigationRoutes(routes);
  if (rankedRoutes.length === 0) {
    throw new Error('Rota sem geometria v\u00e1lida');
  }
  return rankedRoutes;
}

export function parseOsrmResponse(data: any, destination: SearchPlace): NavigationRoute {
  return parseOsrmRoutes(data, destination)[0];
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

export type NavigationSessionStatus = 'idle' | 'preview' | 'navigating' | 'arrived';

export interface NavigationSession {
  status: NavigationSessionStatus;
  route: NavigationRoute | null;
  geometryIndex: number;
  stepIndex: number;
  nextInstruction: string;
  deviationSamples: number[];
  lastRerouteAt: number;
  lastAnnouncedStep: number;
  arrivalAnnounced: boolean;
}

export interface NavigationUpdate {
  session: NavigationSession;
  announcement: string | null;
  shouldReroute: boolean;
}

export function createNavigationSession(): NavigationSession {
  return {
    status: 'idle',
    route: null,
    geometryIndex: 0,
    stepIndex: 0,
    nextInstruction: '',
    deviationSamples: [],
    lastRerouteAt: 0,
    lastAnnouncedStep: -1,
    arrivalAnnounced: false
  };
}

export function selectNavigationRoute(
  _current: NavigationSession,
  route: NavigationRoute
): NavigationSession {
  return {
    ...createNavigationSession(),
    status: 'preview',
    route,
    nextInstruction: route.steps[0]?.instruction || 'Siga o percurso'
  };
}

export function startNavigation(session: NavigationSession): NavigationSession {
  if (!session.route) return session;
  return {
    ...session,
    status: 'navigating',
    geometryIndex: 0,
    stepIndex: 0,
    deviationSamples: [],
    lastAnnouncedStep: -1,
    arrivalAnnounced: false,
    nextInstruction: session.route.steps[0]?.instruction || 'Siga o percurso'
  };
}

export function stopNavigation(_session: NavigationSession): NavigationSession {
  return createNavigationSession();
}

export function shouldApplyRequestResult(
  requestId: number,
  activeRequestId: number,
  aborted: boolean
): boolean {
  return !aborted && requestId === activeRequestId;
}

export function updateNavigationSession(
  session: NavigationSession,
  position: { latitude: number; longitude: number },
  accuracyMeters: number,
  nowMs: number
): NavigationUpdate {
  const route = session.route;
  if (!route || session.status === 'idle' || session.status === 'preview' || session.status === 'arrived') {
    return { session, announcement: null, shouldReroute: false };
  }

  if (distanceMeters(position, route.destination) <= 35) {
    return {
      session: {
        ...session,
        status: 'arrived',
        nextInstruction: 'Chegou ao destino',
        deviationSamples: [],
        arrivalAnnounced: true
      },
      announcement: session.arrivalAnnounced ? null : 'Você chegou ao destino.',
      shouldReroute: false
    };
  }

  const progress = findRouteProgress(route, position, session.geometryIndex, session.stepIndex);
  const routeDistance = distanceToRouteMeters(position, route.coordinates);
  const deviationSamples = [...session.deviationSamples, routeDistance].slice(-3);
  const threshold = Math.max(65, Math.max(0, accuracyMeters) * 2 + 65);
  const outsideCooldown = session.lastRerouteAt === 0 || nowMs - session.lastRerouteAt >= 45_000;
  const shouldReroute = outsideCooldown && isSustainedDeviation(deviationSamples, threshold);
  const step = route.steps[progress.stepIndex];
  const metersUntilStep = step ? distanceMeters(position, step) : Number.POSITIVE_INFINITY;
  const shouldAnnounceStep = metersUntilStep <= 70 && progress.stepIndex > session.lastAnnouncedStep;

  return {
    session: {
      ...session,
      geometryIndex: progress.geometryIndex,
      stepIndex: progress.stepIndex,
      nextInstruction: progress.nextInstruction,
      deviationSamples: shouldReroute ? [] : deviationSamples,
      lastRerouteAt: shouldReroute ? nowMs : session.lastRerouteAt,
      lastAnnouncedStep: shouldAnnounceStep ? progress.stepIndex : session.lastAnnouncedStep
    },
    announcement: shouldAnnounceStep ? progress.nextInstruction : null,
    shouldReroute
  };
}

export function distanceMeters(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number }
): number {
  const earthRadius = 6_371_000;
  const lat1 = a.latitude * Math.PI / 180;
  const lat2 = b.latitude * Math.PI / 180;
  const dLat = (b.latitude - a.latitude) * Math.PI / 180;
  const dLon = (b.longitude - a.longitude) * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return earthRadius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function distanceToRouteMeters(
  point: { latitude: number; longitude: number },
  coordinates: { latitude: number; longitude: number }[]
): number {
  if (coordinates.length < 2) return coordinates[0] ? distanceMeters(point, coordinates[0]) : Infinity;
  let best = Infinity;
  for (let index = 0; index < coordinates.length - 1; index += 1) {
    best = Math.min(best, distanceToSegmentMeters(point, coordinates[index], coordinates[index + 1]));
  }
  return best;
}

function distanceToSegmentMeters(
  point: { latitude: number; longitude: number },
  start: { latitude: number; longitude: number },
  end: { latitude: number; longitude: number }
): number {
  const latScale = 111_320;
  const lonScale = latScale * Math.cos(point.latitude * Math.PI / 180);
  const ax = (start.longitude - point.longitude) * lonScale;
  const ay = (start.latitude - point.latitude) * latScale;
  const bx = (end.longitude - point.longitude) * lonScale;
  const by = (end.latitude - point.latitude) * latScale;
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSquared = dx * dx + dy * dy;
  const projection = lengthSquared === 0 ? 0 : -(ax * dx + ay * dy) / lengthSquared;
  const t = Math.max(0, Math.min(1, projection));
  return Math.hypot(ax + t * dx, ay + t * dy);
}

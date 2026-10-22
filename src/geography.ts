export interface DistrictPolygon {
  name: string;
  zone: string;
  ring: [number, number][];
}

export function parseDistrictPolygons(data: any): DistrictPolygon[] {
  if (!Array.isArray(data?.features)) return [];
  return data.features.flatMap((feature: any) => {
    const ring = feature?.geometry?.type === 'Polygon'
      ? feature.geometry.coordinates?.[0]
      : null;
    const name = typeof feature?.properties?.name === 'string' ? feature.properties.name.trim() : '';
    const zone = typeof feature?.properties?.zona === 'string' ? feature.properties.zona.trim() : '';
    if (!name || !zone || !Array.isArray(ring) || ring.length < 4) return [];
    const coordinates = ring.filter((point: any) =>
      Array.isArray(point) && Number.isFinite(point[0]) && Number.isFinite(point[1])
    ) as [number, number][];
    return coordinates.length >= 4 ? [{ name, zone, ring: coordinates }] : [];
  });
}

export function resolveDistrictPolygon(
  polygons: DistrictPolygon[],
  latitude: number,
  longitude: number
): DistrictPolygon | null {
  return polygons.find(polygon => pointInPolygon(polygon.ring, latitude, longitude)) ?? null;
}

function pointInPolygon(ring: [number, number][], latitude: number, longitude: number): boolean {
  let inside = false;
  let previous = ring[ring.length - 1];
  for (const current of ring) {
    const [x, y] = current;
    const [previousX, previousY] = previous;
    const crosses = (y > latitude) !== (previousY > latitude);
    if (crosses) {
      const intersection = (previousX - x) * (latitude - y) / (previousY - y) + x;
      if (longitude < intersection) inside = !inside;
    }
    previous = current;
  }
  return inside;
}

export function extractCicom(policeLabel: string): string {
  return policeLabel.match(/\b\d{1,2}\s*[ªa]\s*CICOM\b/i)?.[0] ?? '';
}

export interface GeographicAnnouncementParts {
  zone?: string;
  neighbourhood?: string;
  road?: string;
  cicom?: string;
  venueName?: string;
  isInitial?: boolean;
  isRoadTransition?: boolean;
  isBoundaryCrossing?: boolean;
}

export function geographicAnnouncement(parts: GeographicAnnouncementParts): string {
  const cleanZone = parts.zone?.trim() || '';
  const cleanNeighbourhood = (parts.neighbourhood?.trim() && parts.neighbourhood !== 'Bairro não identificado')
    ? parts.neighbourhood.trim()
    : '';
  const rawCicom = parts.cicom?.trim() || '';
  const cicom = extractCicom(rawCicom) || rawCicom;
  const road = parts.road?.trim() || '';
  const venue = parts.venueName?.trim() || '';

  const zonePhrase = cleanZone
    ? (cleanZone.toLowerCase().startsWith('zona') ? `na ${cleanZone}` : `na Zona ${cleanZone}`)
    : '';
  const neighbourhoodPhrase = cleanNeighbourhood ? `no bairro ${cleanNeighbourhood}` : '';
  const cicomPhrase = cicom
    ? (cicom.toLowerCase().includes('área da') ? cicom : `na área da ${cicom}`)
    : '';

  // 1. Boundary crossing while on the same road
  if (parts.isBoundaryCrossing) {
    const enteringParts = [
      zonePhrase ? `entrando ${zonePhrase}` : '',
      neighbourhoodPhrase ? (zonePhrase ? neighbourhoodPhrase : `entrando ${neighbourhoodPhrase}`) : '',
      cicomPhrase
    ].filter(Boolean);
    if (!enteringParts.length) return '';
    const res = enteringParts.join(', ');
    return `${res.charAt(0).toUpperCase()}${res.slice(1)}.`;
  }

  // 2. Road transition (new road detected)
  if (parts.isRoadTransition) {
    const prefix = venue ? `Agora você está no ${venue}` : (road ? `Agora você está na ${road}` : '');
    const boundaryParts = [
      zonePhrase ? `entrando ${zonePhrase}` : '',
      neighbourhoodPhrase ? (zonePhrase ? neighbourhoodPhrase : `entrando ${neighbourhoodPhrase}`) : '',
      cicomPhrase
    ].filter(Boolean);

    if (prefix && boundaryParts.length) {
      return `${prefix}, ${boundaryParts.join(', ')}.`;
    }
    if (prefix) {
      return `${prefix}.`;
    }
    if (boundaryParts.length) {
      return `${boundaryParts.join(', ')}.`;
    }
    return '';
  }

  // 3. Initial identification or full scenario (Cenário Completo: "Você está agora na Zona X, no bairro Y, na área da Nª CICOM, Rua Z.")
  const contextParts = [zonePhrase, neighbourhoodPhrase, cicomPhrase].filter(Boolean);

  if (venue) {
    const ctx = contextParts.length ? `, ${contextParts.join(', ')}` : '';
    const rd = road ? `, ${road}` : '';
    return `Você está agora no ${venue}${ctx}${rd}.`;
  }

  if (contextParts.length && road) {
    return `Você está agora ${contextParts.join(', ')}, ${road}.`;
  }

  if (contextParts.length) {
    return `Você está agora ${contextParts.join(', ')}.`;
  }

  if (road) {
    return `Você está agora na ${road}.`;
  }

  return '';
}

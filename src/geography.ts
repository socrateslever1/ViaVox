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

export function geographicAnnouncement(parts: {
  zone?: string;
  neighbourhood?: string;
  road?: string;
  cicom?: string;
}): string {
  const sentences = [
    parts.zone ? `Entrando na ${parts.zone}` : '',
    parts.neighbourhood ? `Bairro ${parts.neighbourhood}` : '',
    parts.road || '',
    parts.cicom ? `Área da ${parts.cicom}` : ''
  ].filter(Boolean);
  return sentences.length ? `${sentences.join('. ')}.` : '';
}

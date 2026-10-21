import { describe, expect, it } from 'vitest';
import {
  extractCicom,
  geographicAnnouncement,
  parseDistrictPolygons,
  resolveDistrictPolygon
} from './geography';

describe('Manaus polygon geography', () => {
  const polygons = parseDistrictPolygons({
    features: [{
      properties: { name: 'COROADO', zona: 'Zona Leste' },
      geometry: {
        type: 'Polygon',
        coordinates: [[[-60.00, -3.11], [-59.97, -3.11], [-59.97, -3.08], [-60.00, -3.08], [-60.00, -3.11]]]
      }
    }]
  });

  it('resolves only coordinates inside the polygon', () => {
    expect(resolveDistrictPolygon(polygons, -3.10, -59.98)?.name).toBe('COROADO');
    expect(resolveDistrictPolygon(polygons, -3.12, -59.98)).toBeNull();
  });

  it('speaks one ordered event and never equates DIP with CICOM', () => {
    expect(extractCicom('11º DIP · 11ª CICOM')).toBe('11ª CICOM');
    expect(geographicAnnouncement({
      zone: 'Zona Leste',
      neighbourhood: 'Coroado',
      road: 'Avenida Cosme Ferreira',
      cicom: '11ª CICOM'
    })).toBe('Entrando na Zona Leste. Bairro Coroado. Avenida Cosme Ferreira. Área da 11ª CICOM.');
  });
});

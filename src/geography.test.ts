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

  it('speaks full initial scenario with zone, neighbourhood, cicom and road', () => {
    expect(extractCicom('11º DIP · 11ª CICOM')).toBe('11ª CICOM');
    expect(geographicAnnouncement({
      zone: 'Zona Leste',
      neighbourhood: 'Coroado',
      road: 'Avenida Cosme Ferreira',
      cicom: '11ª CICOM'
    })).toBe('Você está agora na Zona Leste, no bairro Coroado, na área da 11ª CICOM, Avenida Cosme Ferreira.');

    // User scenario: Zona Norte, bairro Novo Israel, 18ª CICOM, Avenida Torquato Tapajós
    expect(geographicAnnouncement({
      zone: 'Zona Norte',
      neighbourhood: 'Novo Israel',
      cicom: '18ª CICOM',
      road: 'Avenida Torquato Tapajós'
    })).toBe('Você está agora na Zona Norte, no bairro Novo Israel, na área da 18ª CICOM, Avenida Torquato Tapajós.');
  });

  it('respects 1x cadence: road transition speaks only new road when neighbourhood/cicom unchanged', () => {
    expect(geographicAnnouncement({
      road: 'Rua das Flores',
      isRoadTransition: true
    })).toBe('Agora você está na Rua das Flores.');
  });

  it('speaks boundary crossing on same road or with road transition', () => {
    expect(geographicAnnouncement({
      neighbourhood: 'Chapada',
      cicom: '22ª CICOM',
      isBoundaryCrossing: true
    })).toBe('Entrando no bairro Chapada, na área da 22ª CICOM.');

    expect(geographicAnnouncement({
      road: 'Avenida Brasil',
      neighbourhood: 'Compensa',
      cicom: '8ª CICOM',
      isRoadTransition: true
    })).toBe('Agora você está na Avenida Brasil, entrando no bairro Compensa, na área da 8ª CICOM.');
  });
});

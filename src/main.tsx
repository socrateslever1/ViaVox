import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl } from 'maplibre-gl';
import type { LngLatLike } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import 'maplibre-gl/dist/maplibre-gl.css';
import './style.css';
import {
  createNavigationSession,
  parseOsrmRoutes,
  parsePhotonResponse,
  selectNavigationRoute,
  shouldApplyRequestResult,
  startNavigation,
  stopNavigation,
  updateNavigationSession,
  type NavigationSession,
  type NavigationRoute,
  type SearchPlace
} from './navigation';

setWorkerUrl(workerUrl);

// --- Official Manaus Districts, Zones & Police Jurisdictions (DIP / CICOM) ---
interface DistrictInfo {
  name: string;
  zone: string;
  dip: string;
  cicom: string;
}

const MANAUS_DISTRICTS: Record<string, DistrictInfo> = {
  japiim: { name: 'Japiim', zone: 'Zona Sul', dip: '3º DIP', cicom: '3ª CICOM' },
  cachoeirinha: { name: 'Cachoeirinha', zone: 'Zona Sul', dip: '1º DIP', cicom: '1ª CICOM' },
  'praca 14 de janeiro': { name: 'Praça 14 de Janeiro', zone: 'Zona Sul', dip: '1º DIP', cicom: '1ª CICOM' },
  centro: { name: 'Centro', zone: 'Zona Sul', dip: '24º DIP', cicom: '24ª CICOM' },
  aparecida: { name: 'Aparecida', zone: 'Zona Sul', dip: '24º DIP', cicom: '24ª CICOM' },
  educandos: { name: 'Educandos', zone: 'Zona Sul', dip: '2º DIP', cicom: '2ª CICOM' },
  'santa luzia': { name: 'Santa Luzia', zone: 'Zona Sul', dip: '2º DIP', cicom: '2ª CICOM' },
  betania: { name: 'Betânia', zone: 'Zona Sul', dip: '7º DIP', cicom: '7ª CICOM' },
  'morro da liberdade': { name: 'Morro da Liberdade', zone: 'Zona Sul', dip: '2º DIP', cicom: '2ª CICOM' },
  'sao lazaro': { name: 'São Lázaro', zone: 'Zona Sul', dip: '7º DIP', cicom: '7ª CICOM' },
  crespo: { name: 'Crespo', zone: 'Zona Sul', dip: '7º DIP', cicom: '7ª CICOM' },
  'distrito industrial i': { name: 'Distrito Industrial I', zone: 'Zona Sul', dip: '7º DIP', cicom: '7ª CICOM' },

  adrianopolis: { name: 'Adrianópolis', zone: 'Zona Centro-Sul', dip: '16º DIP', cicom: '16ª CICOM' },
  'nossa senhora das gracas': { name: 'Nossa Senhora das Graças', zone: 'Zona Centro-Sul', dip: '22º DIP', cicom: '22ª CICOM' },
  chapada: { name: 'Chapada', zone: 'Zona Centro-Sul', dip: '22º DIP', cicom: '22ª CICOM' },
  'sao geraldo': { name: 'São Geraldo', zone: 'Zona Centro-Sul', dip: '22º DIP', cicom: '22ª CICOM' },
  'parque dez de novembro': { name: 'Parque Dez de Novembro', zone: 'Zona Centro-Sul', dip: '23º DIP', cicom: '23ª CICOM' },
  'parque 10 de novembro': { name: 'Parque Dez de Novembro', zone: 'Zona Centro-Sul', dip: '23º DIP', cicom: '23ª CICOM' },
  flores: { name: 'Flores', zone: 'Zona Centro-Sul', dip: '12º DIP', cicom: '12ª CICOM' },
  aleixo: { name: 'Aleixo', zone: 'Zona Centro-Sul', dip: '16º DIP', cicom: '16ª CICOM' },

  alvorada: { name: 'Alvorada', zone: 'Zona Centro-Oeste', dip: '10º DIP', cicom: '10ª CICOM' },
  'dom pedro': { name: 'Dom Pedro', zone: 'Zona Centro-Oeste', dip: '10º DIP', cicom: '10ª CICOM' },
  redencao: { name: 'Redenção', zone: 'Zona Centro-Oeste', dip: '17º DIP', cicom: '17ª CICOM' },
  planalto: { name: 'Planalto', zone: 'Zona Centro-Oeste', dip: '17º DIP', cicom: '17ª CICOM' },
  'da paz': { name: 'Da Paz', zone: 'Zona Centro-Oeste', dip: '17º DIP', cicom: '17ª CICOM' },

  compensa: { name: 'Compensa', zone: 'Zona Oeste', dip: '8º DIP', cicom: '8ª CICOM' },
  'sao jorge': { name: 'São Jorge', zone: 'Zona Oeste', dip: '21º DIP', cicom: '21ª CICOM' },
  gloria: { name: 'Glória', zone: 'Zona Oeste', dip: '5º DIP', cicom: '5ª CICOM' },
  'santo antonio': { name: 'Santo Antônio', zone: 'Zona Oeste', dip: '5º DIP', cicom: '5ª CICOM' },
  'vila da prata': { name: 'Vila da Prata', zone: 'Zona Oeste', dip: '21º DIP', cicom: '21ª CICOM' },
  'sao raimundo': { name: 'São Raimundo', zone: 'Zona Oeste', dip: '5º DIP', cicom: '5ª CICOM' },
  'ponta negra': { name: 'Ponta Negra', zone: 'Zona Oeste', dip: '19º DIP', cicom: '19ª CICOM' },
  taruma: { name: 'Tarumã', zone: 'Zona Oeste', dip: '20º DIP', cicom: '20ª CICOM' },

  coroado: { name: 'Coroado', zone: 'Zona Leste', dip: '11º DIP', cicom: '11ª CICOM' },
  'sao jose operario': { name: 'São José Operário', zone: 'Zona Leste', dip: '9º DIP', cicom: '9ª CICOM' },
  'tancredo neves': { name: 'Tancredo Neves', zone: 'Zona Leste', dip: '14º DIP', cicom: '14ª CICOM' },
  'armando mendes': { name: 'Armando Mendes', zone: 'Zona Leste', dip: '25º DIP', cicom: '25ª CICOM' },
  'zumbi dos palmares': { name: 'Zumbi dos Palmares', zone: 'Zona Leste', dip: '25º DIP', cicom: '25ª CICOM' },
  'jorge teixeira': { name: 'Jorge Teixeira', zone: 'Zona Leste', dip: '30º DIP', cicom: '30ª CICOM' },
  mauazinho: { name: 'Mauazinho', zone: 'Zona Leste', dip: '29º DIP', cicom: '29ª CICOM' },
  'colonia antonio aleixo': { name: 'Colônia Antônio Aleixo', zone: 'Zona Leste', dip: '28º DIP', cicom: '28ª CICOM' },

  'cidade nova': { name: 'Cidade Nova', zone: 'Zona Norte', dip: '6º DIP', cicom: '6ª CICOM' },
  'novo aleixo': { name: 'Novo Aleixo', zone: 'Zona Norte', dip: '27º DIP', cicom: '27ª CICOM' },
  'cidade de deus': { name: 'Cidade de Deus', zone: 'Zona Norte', dip: '13º DIP', cicom: '13ª CICOM' },
  'monte das oliveiras': { name: 'Monte das Oliveiras', zone: 'Zona Norte', dip: '26º DIP', cicom: '26ª CICOM' },
  'colonia santo antonio': { name: 'Colônia Santo Antônio', zone: 'Zona Norte', dip: '18º DIP', cicom: '18ª CICOM' },
  'colonia terra nova': { name: 'Colônia Terra Nova', zone: 'Zona Norte', dip: '18º DIP', cicom: '18ª CICOM' },
  'nova cidade': { name: 'Nova Cidade', zone: 'Zona Norte', dip: '15º DIP', cicom: '15ª CICOM' },
  'santa etelvina': { name: 'Santa Etelvina', zone: 'Zona Norte', dip: '26º DIP', cicom: '26ª CICOM' }
};

type Coordinates = { lat: number; lon: number };

type Place = {
  road: string;
  neighbourhood: string;
  zone: string;
  police: string;
  city: string;
  venueName: string;
};

type SafetyCamera = {
  id: string;
  lat: number;
  lon: number;
  type: 'speed_camera' | 'toll_booth';
  maxspeed?: number;
};

type NavStep = {
  instruction: string;
  distance: number;
  location: [number, number]; // [lon, lat]
  type: string;
  modifier?: string;
  name: string;
};

type NavRoute = {
  coordinates: [number, number][]; // [[lon, lat], ...]
  distanceMeters: number;
  durationSeconds: number;
  steps: NavStep[];
};

type SearchResult = SearchPlace;

const MAP_STYLES = {
  bright: 'https://tiles.openfreemap.org/styles/bright',
  dark: 'https://tiles.openfreemap.org/styles/dark',
  liberty: 'https://tiles.openfreemap.org/styles/liberty'
};

const clean = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

function normalizeRoad(name: string): string {
  const s = name.trim().replace(/\s+/g, ' ');
  if (!s) return 'Via não identificada';
  return s
    .replace(/^R\.\s*/i, 'Rua ')
    .replace(/^Av\.\s*/i, 'Avenida ')
    .replace(/^Trav\.\s*/i, 'Travessa ')
    .replace(/^Al\.\s*/i, 'Alameda ')
    .replace(/^Rod\.\s*/i, 'Rodovia ');
}

function normalizeKey(str: string): string {
  return str
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]/g, '');
}

function metersBetween(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = (lat2 - lat1) * 111139;
  const dLon = (lon2 - lon1) * 111139 * Math.cos(((lat1 + lat2) / 2) * (Math.PI / 180));
  return Math.hypot(dLat, dLon);
}

// Map Component
function MapCanvas({
  position,
  heading,
  followUser,
  vehicleType,
  route,
  destination,
  safetyCameras,
  styleUrl,
  showBairrosLayer = true,
  showCicomsLayer = false,
  showSedesLayer = true,
  onUserDrag,
  onMapSelect,
  onSelectPolygon = () => {}
}: {
  position: Coordinates | null;
  heading: number | null;
  followUser: boolean;
  vehicleType: 'car' | 'motorcycle';
  route: NavRoute | null;
  destination: Coordinates | null;
  safetyCameras: SafetyCamera[];
  styleUrl: string;
  showBairrosLayer?: boolean;
  showCicomsLayer?: boolean;
  showSedesLayer?: boolean;
  onUserDrag: () => void;
  onMapSelect: (latitude: number, longitude: number) => void;
  onSelectPolygon?: (info: { title: string; desc: string }) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const locationMarker = useRef<Marker | null>(null);
  const destMarker = useRef<Marker | null>(null);
  const cameraMarkers = useRef<Marker[]>([]);
  const onDrag = useRef(onUserDrag);
  onDrag.current = onUserDrag;
  const onMapSelection = useRef(onMapSelect);
  onMapSelection.current = onMapSelect;

  // Initialize MapLibre
  useEffect(() => {
    if (!container.current) return;
    const instance = new MapLibreMap({
      container: container.current,
      style: styleUrl,
      center: [-60.0235, -3.1025],
      zoom: 15.5,
      pitch: 45
    });
    map.current = instance;

    instance.addControl(new NavigationControl({ showZoom: false, showCompass: true }), 'top-right');
    instance.on('dragstart', () => onDrag.current());
    instance.on('click', event => onMapSelection.current(event.lngLat.lat, event.lngLat.lng));
    instance.on('contextmenu', event => onMapSelection.current(event.lngLat.lat, event.lngLat.lng));

    instance.on('load', () => {
      // Add Route line layer
      if (!instance.getSource('viavox-route')) {
        instance.addSource('viavox-route', {
          type: 'geojson',
          data: {
            type: 'Feature',
            properties: {},
            geometry: { type: 'LineString', coordinates: [] }
          }
        });

        // Route casing / glow
        instance.addLayer({
          id: 'viavox-route-casing',
          type: 'line',
          source: 'viavox-route',
          layout: { 'line-join': 'round', 'line-cap': 'round' },
          paint: {
            'line-color': '#0284c7',
            'line-width': 9,
            'line-opacity': 0.8
          }
        });

        // Route core line
        instance.addLayer({
          id: 'viavox-route-core',
          type: 'line',
          source: 'viavox-route',
          layout: { 'line-join': 'round', 'line-cap': 'round' },
          paint: {
            'line-color': '#38bdf8',
            'line-width': 5.5,
            'line-opacity': 1.0
          }
        });
      }

      // 1. Bairros Source and Layers (Coloridos por Zona)
      if (!instance.getSource('bairros-source')) {
        instance.addSource('bairros-source', {
          type: 'geojson',
          data: '/bairros_manaus.geojson'
        });

        instance.addLayer({
          id: 'bairros-fill',
          type: 'fill',
          source: 'bairros-source',
          paint: {
            'fill-color': ['get', 'color'],
            'fill-opacity': 0.28
          }
        });

        instance.addLayer({
          id: 'bairros-border',
          type: 'line',
          source: 'bairros-source',
          paint: {
            'line-color': ['get', 'color'],
            'line-width': 2.2,
            'line-opacity': 0.95
          }
        });

        instance.addLayer({
          id: 'bairros-label',
          type: 'symbol',
          source: 'bairros-source',
          layout: {
            'text-field': ['concat', ['get', 'name'], '\n(', ['get', 'zona'], ')'],
            'text-size': 11,
            'text-font': ['Open Sans Semibold', 'Arial Unicode MS Bold'],
            'text-anchor': 'center',
            'text-max-width': 8
          },
          paint: {
            'text-color': '#ffffff',
            'text-halo-color': '#091728',
            'text-halo-width': 2.5
          }
        });

        instance.on('click', 'bairros-fill', (e) => {
          if (e.features && e.features[0]) {
            const p = e.features[0].properties as any;
            onSelectPolygon({
              title: p.name,
              desc: `${p.zona} · Bairro Oficial de Manaus`
            });
          }
        });
      }

      // 2. CICOMs Source and Layers (Circunscricoes Territoriais Policiais)
      if (!instance.getSource('cicoms-source')) {
        instance.addSource('cicoms-source', {
          type: 'geojson',
          data: '/cicoms_manaus.geojson'
        });

        instance.addLayer({
          id: 'cicoms-fill',
          type: 'fill',
          source: 'cicoms-source',
          paint: {
            'fill-color': ['get', 'color'],
            'fill-opacity': 0.22
          }
        });

        instance.addLayer({
          id: 'cicoms-border',
          type: 'line',
          source: 'cicoms-source',
          paint: {
            'line-color': ['get', 'color'],
            'line-width': 2.2,
            'line-opacity': 0.95
          }
        });

        instance.addLayer({
          id: 'cicoms-label',
          type: 'symbol',
          source: 'cicoms-source',
          layout: {
            'text-field': ['get', 'name'],
            'text-size': 10.5,
            'text-font': ['Open Sans Semibold', 'Arial Unicode MS Bold'],
            'text-anchor': 'center'
          },
          paint: {
            'text-color': '#fef08a',
            'text-halo-color': '#172554',
            'text-halo-width': 2.5
          }
        });

        instance.on('click', 'cicoms-fill', (e) => {
          if (e.features && e.features[0]) {
            const p = e.features[0].properties as any;
            onSelectPolygon({
              title: p.name,
              desc: 'Subárea e Circunscrição Territorial Policial da CICOM'
            });
          }
        });
      }

      // 3. Sedes Policiais Source & Circle Layer
      if (!instance.getSource('sedes-source')) {
        instance.addSource('sedes-source', {
          type: 'geojson',
          data: '/sedes_policiais.geojson'
        });

        instance.addLayer({
          id: 'sedes-circle',
          type: 'circle',
          source: 'sedes-source',
          paint: {
            'circle-color': '#2563eb',
            'circle-radius': 7,
            'circle-stroke-width': 2.5,
            'circle-stroke-color': '#ffffff'
          }
        });

        instance.addLayer({
          id: 'sedes-label',
          type: 'symbol',
          source: 'sedes-source',
          layout: {
            'text-field': ['get', 'name'],
            'text-size': 10.5,
            'text-offset': [0, 1.4],
            'text-anchor': 'top'
          },
          paint: {
            'text-color': '#bfdbfe',
            'text-halo-color': '#0f172a',
            'text-halo-width': 2
          }
        });
      }
    });

    return () => {
      cameraMarkers.current.forEach(m => m.remove());
      cameraMarkers.current = [];
      locationMarker.current?.remove();
      destMarker.current?.remove();
      instance.remove();
      map.current = null;
    };
  }, [styleUrl]);

  // Update Route geometry
  useEffect(() => {
    if (!map.current || !map.current.isStyleLoaded()) return;
    const source = map.current.getSource('viavox-route') as any;
    if (source) {
      source.setData({
        type: 'Feature',
        properties: {},
        geometry: {
          type: 'LineString',
          coordinates: route ? route.coordinates : []
        }
      });
    }
  }, [route]);

  // Update Bairros, CICOMs, and Sedes Layer Visibility
  useEffect(() => {
    if (!map.current || !map.current.isStyleLoaded()) return;
    const vb = showBairrosLayer ? 'visible' : 'none';
    ['bairros-fill', 'bairros-border', 'bairros-label'].forEach(l => {
      if (map.current?.getLayer(l)) map.current.setLayoutProperty(l, 'visibility', vb);
    });

    const vc = showCicomsLayer ? 'visible' : 'none';
    ['cicoms-fill', 'cicoms-border', 'cicoms-label'].forEach(l => {
      if (map.current?.getLayer(l)) map.current.setLayoutProperty(l, 'visibility', vc);
    });

    const vs = showSedesLayer ? 'visible' : 'none';
    ['sedes-circle', 'sedes-label'].forEach(l => {
      if (map.current?.getLayer(l)) map.current.setLayoutProperty(l, 'visibility', vs);
    });
  }, [showBairrosLayer, showCicomsLayer, showSedesLayer]);

  // Destination Marker
  useEffect(() => {
    if (!map.current) return;
    if (!destination) {
      destMarker.current?.remove();
      destMarker.current = null;
      return;
    }
    const pt: LngLatLike = [destination.lon, destination.lat];
    if (!destMarker.current) {
      const el = document.createElement('div');
      el.className = 'dest-pin';
      el.innerHTML = '<span>★</span>';
      destMarker.current = new Marker({ element: el }).setLngLat(pt).addTo(map.current);
    } else {
      destMarker.current.setLngLat(pt);
    }
  }, [destination]);

  // Safety Cameras Markers
  useEffect(() => {
    if (!map.current) return;
    cameraMarkers.current.forEach(m => m.remove());
    cameraMarkers.current = [];

    safetyCameras.forEach(cam => {
      const el = document.createElement('div');
      el.className = cam.type === 'speed_camera' ? 'radar-pin' : 'toll-pin';
      el.innerText = cam.type === 'speed_camera' ? (cam.maxspeed ? `${cam.maxspeed}` : 'RAD') : 'PED';
      const m = new Marker({ element: el }).setLngLat([cam.lon, cam.lat]).addTo(map.current!);
      cameraMarkers.current.push(m);
    });
  }, [safetyCameras]);

  // Position and Follow User
  useEffect(() => {
    if (!position || !map.current) return;
    const point: LngLatLike = [position.lon, position.lat];

    if (!locationMarker.current) {
      const dot = document.createElement('div');
      dot.className = vehicleType === 'motorcycle' ? 'gps-pin-moto' : 'gps-pin-car';
      locationMarker.current = new Marker({ element: dot }).setLngLat(point).addTo(map.current);
    } else {
      locationMarker.current.getElement().className =
        vehicleType === 'motorcycle' ? 'gps-pin-moto' : 'gps-pin-car';
      locationMarker.current.setLngLat(point);
    }

    if (followUser) {
      map.current.easeTo({
        center: point,
        zoom: route ? 17.0 : 16.5,
        pitch: 48,
        bearing: heading ?? map.current.getBearing(),
        duration: 550
      });
    }
  }, [position, heading, followUser, vehicleType, route]);

  return <div ref={container} className="map-surface" aria-label="Mapa ViaVox" />;
}

export function App() {
  const [position, setPosition] = useState<Coordinates | null>(null);
  const [place, setPlace] = useState<Place | null>(null);
  const [status, setStatus] = useState('Obtendo localização GPS…');
  const [active, setActive] = useState(false);
  const [followUser, setFollowUser] = useState(true);
  const [accuracy, setAccuracy] = useState<number | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [speedKmh, setSpeedKmh] = useState(0);

  // Settings & Config
  const [vehicleType, setVehicleType] = useState<'car' | 'motorcycle'>(() =>
    (localStorage.getItem('viavox.vehicle') as any) || 'car'
  );
  const [mapStyleKey, setMapStyleKey] = useState<keyof typeof MAP_STYLES>(() =>
    (localStorage.getItem('viavox.map_style') as any) || 'bright'
  );
  const [voice, setVoice] = useState(() => localStorage.getItem('viavox.voice') !== 'false');
  const [voiceRate, setVoiceRate] = useState(() => Number(localStorage.getItem('viavox.rate') || '0.95'));
  const [enablePolice, setEnablePolice] = useState(
    () => localStorage.getItem('viavox.police') !== 'false'
  );
  const [enableSafety, setEnableSafety] = useState(
    () => localStorage.getItem('viavox.safety') !== 'false'
  );
  const [showSettings, setShowSettings] = useState(false);

  // Map Layers (Bairros, CICOMs, Sedes)
  const [showBairrosLayer, setShowBairrosLayer] = useState(
    () => localStorage.getItem('viavox.layer_bairros') !== 'false'
  );
  const [showCicomsLayer, setShowCicomsLayer] = useState(
    () => localStorage.getItem('viavox.layer_cicoms') === 'true'
  );
  const [showSedesLayer, setShowSedesLayer] = useState(
    () => localStorage.getItem('viavox.layer_sedes') !== 'false'
  );
  const [showLayersModal, setShowLayersModal] = useState(false);
  const [selectedPolygonInfo, setSelectedPolygonInfo] = useState<{ title: string; desc: string } | null>(null);

  // Search & Navigation
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [destination, setDestination] = useState<Coordinates | null>(null);
  const [pendingDestination, setPendingDestination] = useState<SearchPlace | null>(null);
  const [routeOptions, setRouteOptions] = useState<NavigationRoute[]>([]);
  const [activeRoute, setActiveRoute] = useState<NavRoute | null>(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [navigationSession, setNavigationSession] = useState<NavigationSession>(() => createNavigationSession());
  const [navigationError, setNavigationError] = useState('');
  const [isRouting, setIsRouting] = useState(false);

  // Safety Features (Speed cameras & tolls)
  const [safetyCameras, setSafetyCameras] = useState<SafetyCamera[]>([]);
  const alertedCameraIds = useRef<Set<string>>(new Set());
  const lastSafetyQueryTime = useRef(0);
  const lastSafetyQueryPoint = useRef<Coordinates | null>(null);

  // Internal references
  const watch = useRef<number | null>(null);
  const lastLookup = useRef(0);
  const lastPoint = useRef<Coordinates | null>(null);
  const confirmedRoad = useRef('');
  const confirmedNeighbourhood = useRef('');
  const confirmedVenue = useRef('');
  const pending = useRef({ road: '', count: 0 });
  const busy = useRef(false);
  const session = useRef(0);
  const navigationSessionRef = useRef(navigationSession);
  const selectedDestinationRef = useRef<SearchPlace | null>(null);
  const searchAbort = useRef<AbortController | null>(null);
  const routeAbort = useRef<AbortController | null>(null);
  const searchRequestId = useRef(0);
  const routeRequestId = useRef(0);

  useEffect(() => {
    navigationSessionRef.current = navigationSession;
  }, [navigationSession]);

  // Persist settings
  useEffect(() => {
    localStorage.setItem('viavox.vehicle', vehicleType);
  }, [vehicleType]);

  useEffect(() => {
    localStorage.setItem('viavox.map_style', mapStyleKey);
  }, [mapStyleKey]);

  useEffect(() => {
    localStorage.setItem('viavox.voice', String(voice));
    if (!voice) window.speechSynthesis?.cancel();
  }, [voice]);

  useEffect(() => {
    localStorage.setItem('viavox.rate', String(voiceRate));
  }, [voiceRate]);

  useEffect(() => {
    localStorage.setItem('viavox.police', String(enablePolice));
  }, [enablePolice]);

  useEffect(() => {
    localStorage.setItem('viavox.safety', String(enableSafety));
  }, [enableSafety]);

  useEffect(() => {
    localStorage.setItem('viavox.layer_bairros', String(showBairrosLayer));
  }, [showBairrosLayer]);

  useEffect(() => {
    localStorage.setItem('viavox.layer_cicoms', String(showCicomsLayer));
  }, [showCicomsLayer]);

  useEffect(() => {
    localStorage.setItem('viavox.layer_sedes', String(showSedesLayer));
  }, [showSedesLayer]);

  // Voice narration helper
  function speakLocal(text: string) {
    if (!voice || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'pt-BR';
    u.rate = voiceRate;
    window.speechSynthesis.speak(u);
  }

  function say(text: string) {
    if (!voice || !text.trim()) return;
    speakLocal(text);
  }

  // --- Query Speed Cameras & Tolls (Overpass API) ---
  async function querySafetyFeatures(lat: number, lon: number) {
    if (!enableSafety) return;
    const now = Date.now();
    if (
      lastSafetyQueryPoint.current &&
      metersBetween(lastSafetyQueryPoint.current.lat, lastSafetyQueryPoint.current.lon, lat, lon) < 400 &&
      now - lastSafetyQueryTime.current < 180000
    ) {
      return;
    }
    lastSafetyQueryTime.current = now;
    lastSafetyQueryPoint.current = { lat, lon };

    try {
      const q = `[out:json][timeout:5];(node["highway"="speed_camera"](around:2500,${lat},${lon});node["barrier"="toll_booth"](around:3500,${lat},${lon}););out body qt;`;
      const res = await fetch('https://overpass-api.de/api/interpreter', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: q
      });
      if (!res.ok) return;
      const data = await res.json();
      const elements: any[] = data.elements || [];
      const feats: SafetyCamera[] = elements.map(el => {
        const type = el.tags?.highway === 'speed_camera' ? 'speed_camera' : 'toll_booth';
        const rawSpeed = el.tags?.maxspeed;
        const maxspeed = rawSpeed ? parseInt(rawSpeed, 10) : undefined;
        return {
          id: String(el.id),
          lat: el.lat,
          lon: el.lon,
          type,
          maxspeed: Number.isFinite(maxspeed) ? maxspeed : undefined
        };
      });
      setSafetyCameras(feats);
    } catch {
      // Overpass soft-fail
    }
  }

  // Check upcoming cameras on path
  function checkSafetyAlerts(userLat: number, userLon: number, userBearing: number | null, userSpeedKmh: number) {
    if (!enableSafety || userSpeedKmh < 15 || safetyCameras.length === 0) return;

    safetyCameras.forEach(cam => {
      if (alertedCameraIds.current.has(cam.id)) return;
      const dist = metersBetween(userLat, userLon, cam.lat, cam.lon);
      if (dist >= 80 && dist <= 450) {
        // Bearing check if heading available
        if (userBearing !== null) {
          const dLat = cam.lat - userLat;
          const dLon = cam.lon - userLon;
          const angle = ((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;
          const diff = Math.abs((((angle - userBearing + 540) % 360) - 180));
          if (diff > 60) return; // Not in driving direction
        }

        alertedCameraIds.current.add(cam.id);
        const distRound = Math.round(dist);
        if (cam.type === 'speed_camera') {
          const limit = cam.maxspeed ? `, limite ${cam.maxspeed} por hora` : '';
          void say(`Atenção: radar à frente em ${distRound} metros${limit}.`);
        } else {
          void say(`Atenção: praça de pedágio à frente em ${distRound} metros.`);
        }
      }
    });
  }

  function previewRoute(domainRoute: NavigationRoute) {
    const navRoute: NavRoute = {
      coordinates: domainRoute.coordinates.map(point => [point.longitude, point.latitude]),
      distanceMeters: domainRoute.distanceMeters,
      durationSeconds: domainRoute.durationSeconds,
      steps: domainRoute.steps.map(step => ({
        instruction: step.instruction,
        distance: step.distanceMeters,
        location: [step.longitude, step.latitude],
        type: 'turn',
        name: ''
      }))
    };
    setActiveRoute(navRoute);
    setCurrentStepIndex(0);
    setFollowUser(true);
    const preview = selectNavigationRoute(navigationSessionRef.current, domainRoute);
    navigationSessionRef.current = preview;
    setNavigationSession(preview);
  }

  // --- Calculate Route with OSRM (Public, Zero Cost) ---
  async function calculateRoute(start: Coordinates, target: SearchPlace) {
    routeAbort.current?.abort();
    const controller = new AbortController();
    routeAbort.current = controller;
    const requestId = ++routeRequestId.current;
    setIsRouting(true);
    setNavigationError('');
    setStatus('Calculando melhor rota…');
    try {
      const url = `https://router.project-osrm.org/route/v1/driving/${start.lon},${start.lat};${target.longitude},${target.latitude}?overview=full&geometries=geojson&steps=true&alternatives=true`;
      const res = await fetch(url, { signal: controller.signal });
      if (!res.ok) throw new Error('Falha ao traçar rota.');
      const data = await res.json();
      if (!shouldApplyRequestResult(requestId, routeRequestId.current, controller.signal.aborted)) return;
      if (!data.routes || data.routes.length === 0) throw new Error('Nenhuma rota encontrada.');

      const routes = parseOsrmRoutes(data, target);
      const domainRoute = routes[0];
      previewRoute(domainRoute);
      setRouteOptions(routes);
      setPendingDestination(null);
      setStatus('Rota pronta para iniciar');
    } catch (err: any) {
      if (controller.signal.aborted) return;
      setNavigationError(err.message || 'Erro ao calcular rota');
      setStatus(err.message || 'Erro ao calcular rota');
    } finally {
      if (requestId === routeRequestId.current) setIsRouting(false);
    }
  }

  function beginNavigation() {
    const nextSession = startNavigation(navigationSessionRef.current);
    if (!nextSession.route) return;
    navigationSessionRef.current = nextSession;
    setNavigationSession(nextSession);
    setFollowUser(true);
    setStatus('Navegação ativa');
    const km = (nextSession.route.distanceMeters / 1000).toFixed(1);
    const minutes = Math.max(1, Math.round(nextSession.route.durationSeconds / 60));
    say(`Navegação iniciada. Distância de ${km} quilômetros, tempo estimado de ${minutes} minutos.`);
  }

  function endNavigation(announce = true) {
    routeAbort.current?.abort();
    const nextSession = stopNavigation(navigationSessionRef.current);
    navigationSessionRef.current = nextSession;
    setNavigationSession(nextSession);
    selectedDestinationRef.current = null;
    setActiveRoute(null);
    setRouteOptions([]);
    setPendingDestination(null);
    setDestination(null);
    setCurrentStepIndex(0);
    setNavigationError('');
    setStatus(active ? 'Localização ativa' : 'Navegação encerrada');
    if (announce) say('Navegação encerrada.');
  }

  // --- Turn-by-turn guidance ---
  useEffect(() => {
    const current = navigationSessionRef.current;
    if (!position || current.status !== 'navigating') return;
    const update = updateNavigationSession(
      current,
      { latitude: position.lat, longitude: position.lon },
      accuracy ?? 0,
      Date.now()
    );
    navigationSessionRef.current = update.session;
    setNavigationSession(update.session);
    setCurrentStepIndex(update.session.stepIndex);
    if (update.announcement) say(update.announcement);
    if (update.session.status === 'arrived') setStatus('Você chegou ao destino');
    if (update.shouldReroute && selectedDestinationRef.current) {
      void calculateRoute(position, selectedDestinationRef.current).then(() => beginNavigation());
    }
  }, [position, accuracy]);

  // --- Explicit free destination search with Photon (OpenStreetMap) ---
  async function handleSearch() {
    const text = searchQuery.trim();
    if (text.length < 2) {
      setNavigationError('Digite ao menos 2 caracteres para buscar.');
      return;
    }
    searchAbort.current?.abort();
    const controller = new AbortController();
    searchAbort.current = controller;
    const requestId = ++searchRequestId.current;
    setIsSearching(true);
    setNavigationError('');
    try {
      const near = position ? `&lat=${position.lat}&lon=${position.lon}` : '';
      const url = `https://photon.komoot.io/api/?q=${encodeURIComponent(text)}&limit=6${near}`;
      const res = await fetch(url, {
        signal: controller.signal,
        headers: { 'Accept-Language': 'pt-BR,pt;q=0.9' }
      });
      if (!res.ok) throw new Error('Não foi possível pesquisar o destino agora.');
      const results = parsePhotonResponse(await res.json());
      if (!shouldApplyRequestResult(requestId, searchRequestId.current, controller.signal.aborted)) return;
      setSearchResults(results);
      if (results.length === 0) setNavigationError('Nenhum local encontrado. Informe também a cidade ou estado.');
    } catch (error) {
      if (controller.signal.aborted) return;
      setNavigationError(error instanceof Error ? error.message : 'Erro ao pesquisar destino.');
    } finally {
      if (requestId === searchRequestId.current) setIsSearching(false);
    }
  }

  function selectDestination(result: SearchResult) {
    selectedDestinationRef.current = result;
    setDestination({ lat: result.latitude, lon: result.longitude });
    setPendingDestination(result);
    setSearchQuery(result.name);
    setSearchResults([]);
    setNavigationError('');
    setStatus('Destino selecionado. Confirme para calcular a rota.');
  }

  function confirmDestination() {
    if (!pendingDestination) return;
    if (!position) {
      setNavigationError('Aguarde a localização GPS para calcular a rota.');
      return;
    }
    void calculateRoute(position, pendingDestination);
  }

  function selectMapDestination(latitude: number, longitude: number) {
    const result: SearchPlace = {
      id: `map:${latitude.toFixed(6)},${longitude.toFixed(6)}`,
      name: 'Ponto escolhido no mapa',
      detail: `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`,
      latitude,
      longitude
    };
    selectDestination(result);
  }

  // --- Reverse Geocoding with Circumscription & DIP/CICOM Identification ---
  async function lookup(lat: number, lon: number) {
    if (busy.current) return;
    const requestSession = session.current;
    busy.current = true;
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=18&addressdetails=1`;
      const res = await fetch(url, { headers: { 'Accept-Language': 'pt-BR' } });
      if (!res.ok) throw new Error('Sem conexão para consultar endereço');
      const json = await res.json();
      if (requestSession !== session.current) return;

      const a = json.address || {};
      const rawRoad =
        clean(a.road) ||
        clean(a.pedestrian) ||
        clean(a.residential) ||
        clean(a.path) ||
        'Via não identificada';
      const road = normalizeRoad(rawRoad);
      const city = clean(a.city) || clean(a.town) || clean(a.municipality) || clean(a.village) || '';
      const displayName = clean(json.display_name).toLowerCase();

      // Circumscription resolution
      let rawNeighbourhood =
        clean(a.suburb) ||
        clean(a.neighbourhood) ||
        clean(a.quarter) ||
        clean(a.borough) ||
        '';

      // Anti-leakage: if suburb matches the city name, discard it
      if (rawNeighbourhood.toLowerCase() === city.toLowerCase() || rawNeighbourhood.toLowerCase() === 'manaus') {
        rawNeighbourhood = '';
      }

      let neighbourhood = rawNeighbourhood;
      let zone = '';
      let police = '';

      // Manaus Official District Resolver (Prevents generic "bairro Manaus")
      const isManaus = city.toLowerCase() === 'manaus' || displayName.includes('manaus');
      if (isManaus) {
        const foundKey = Object.keys(MANAUS_DISTRICTS).find(
          k =>
            (neighbourhood && normalizeKey(neighbourhood) === k) ||
            displayName.includes(k)
        );
        if (foundKey) {
          const d = MANAUS_DISTRICTS[foundKey];
          neighbourhood = d.name;
          zone = d.zone;
          police = `${d.dip} · ${d.cicom}`;
        }
      }

      if (!neighbourhood) {
        const cd = clean(a.city_district);
        if (cd && cd.toLowerCase() !== city.toLowerCase()) neighbourhood = cd;
      }

      if (!neighbourhood) neighbourhood = 'Bairro não identificado';

      // Venue / Notable Building priority (Hospital, Mall, University, Public Building)
      const rawVenue = clean(json.name);
      const venueName = rawVenue && rawVenue !== road ? rawVenue : '';

      const newPlace: Place = {
        road,
        neighbourhood,
        zone,
        police,
        city,
        venueName
      };

      setPlace(newPlace);
      setStatus(activeRoute ? 'Navegação ativa' : 'Localização ativa');

      // Check for venue entry
      if (venueName && confirmedVenue.current !== venueName) {
        confirmedVenue.current = venueName;
        void say(`Você está no ${venueName}, bairro ${neighbourhood}.`);
        return;
      }

      // Check for road and neighbourhood announcements
      if (road === 'Via não identificada') return;

      const norm = normalizeKey(road);
      const currentNorm = confirmedRoad.current ? normalizeKey(confirmedRoad.current) : '';

      // First road identified
      if (!currentNorm) {
        confirmedRoad.current = road;
        confirmedNeighbourhood.current = neighbourhood;
        pending.current = { road: '', count: 0 };
        const bairroPart = neighbourhood !== 'Bairro não identificado' ? `, bairro ${neighbourhood}` : '';
        void say(`Você está na ${road}${bairroPart}.`);
        return;
      }

      // Same road: check if crossed into a new neighbourhood
      if (norm === currentNorm) {
        pending.current = { road: '', count: 0 };
        if (
          neighbourhood !== 'Bairro não identificado' &&
          normalizeKey(neighbourhood) !== normalizeKey(confirmedNeighbourhood.current)
        ) {
          confirmedNeighbourhood.current = neighbourhood;
          const zonePart = zone ? `, ${zone}` : '';
          const policePart = enablePolice && police ? `. Circunscrição do ${police}` : '';
          void say(`Entrando no bairro ${neighbourhood}${zonePart}${policePart}.`);
        }
        return;
      }

      // Road transition confirmation
      if (normalizeKey(pending.current.road) === norm) {
        pending.current.count += 1;
        if (pending.current.count >= 2) {
          confirmedRoad.current = road;
          pending.current = { road: '', count: 0 };
          const changedNeighbourhood =
            neighbourhood !== 'Bairro não identificado' &&
            normalizeKey(neighbourhood) !== normalizeKey(confirmedNeighbourhood.current);
          if (changedNeighbourhood) confirmedNeighbourhood.current = neighbourhood;
          const bairroPart = changedNeighbourhood ? `, bairro ${neighbourhood}` : '';
          void say(`Agora você está na ${road}${bairroPart}.`);
        }
      } else {
        pending.current = { road, count: 1 };
      }
    } catch (e: any) {
      if (requestSession === session.current) {
        setStatus(e.message || 'Sem conexão para consultar endereço');
      }
    } finally {
      busy.current = false;
    }
  }

  // Stop tracking
  function stop() {
    session.current++;
    lastLookup.current = 0;
    lastPoint.current = null;
    confirmedRoad.current = '';
    confirmedNeighbourhood.current = '';
    confirmedVenue.current = '';
    pending.current = { road: '', count: 0 };
    if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
    watch.current = null;
    setActive(false);
    setPlace(null);
    setStatus('Acompanhamento pausado');
    window.speechSynthesis?.cancel();
  }

  // Start tracking
  function start() {
    if (watch.current !== null) return;
    session.current++;
    if (!navigator.geolocation) {
      setStatus('GPS indisponível no dispositivo');
      return;
    }
    setStatus('Obtendo sinal GPS…');
    setFollowUser(true);

    watch.current = navigator.geolocation.watchPosition(
      p => {
        const { latitude: lat, longitude: lon, accuracy: acc, speed, heading: h } = p.coords;
        setPosition({ lat, lon });
        setHeading(typeof h === 'number' && Number.isFinite(h) ? h : null);
        setAccuracy(Math.round(acc));
        const currentSpeedKmh = speed ? Math.round(speed * 3.6) : 0;
        setSpeedKmh(currentSpeedKmh);
        setActive(true);

        // Safety cameras check
        void querySafetyFeatures(lat, lon);
        checkSafetyAlerts(lat, lon, typeof h === 'number' && Number.isFinite(h) ? h : null, currentSpeedKmh);

        if (acc > 65) {
          setStatus(`Sinal GPS impreciso (±${Math.round(acc)} m)`);
          return;
        }

        const now = Date.now();
        const prior = lastPoint.current;
        const meters = prior ? metersBetween(prior.lat, prior.lon, lat, lon) : Infinity;

        // Adaptive lookup interval (3s in motion, 40s stationary)
        if (
          lastLookup.current === 0 ||
          (now - lastLookup.current >= 3000 && (meters >= 12 || now - lastLookup.current >= 40000))
        ) {
          lastLookup.current = now;
          lastPoint.current = { lat, lon };
          void lookup(lat, lon);
        }
      },
      e => {
        if (e.code === 1) {
          stop();
          setStatus('Permissão de GPS necessária');
        } else {
          setStatus('Aguardando sinal de GPS…');
        }
      },
      { enableHighAccuracy: true, maximumAge: 2000, timeout: 15000 }
    );
  }

  useEffect(() => {
    start();
    return () => {
      session.current++;
      if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
      watch.current = null;
      window.speechSynthesis?.cancel();
    };
  }, []);

  // Format subtitle
  const subtitle = place
    ? [
        place.neighbourhood !== 'Bairro não identificado' ? place.neighbourhood : '',
        place.zone ? `(${place.zone})` : '',
        place.city
      ]
        .filter(Boolean)
        .join(' ')
    : '';

  // Current navigation turn instruction
  const currentStep = activeRoute?.steps[currentStepIndex];
  const distToNextManeuver =
    activeRoute && position && currentStep
      ? Math.round(metersBetween(position.lat, position.lon, currentStep.location[1], currentStep.location[0]))
      : null;

  return (
    <div className="viewport">
      {/* Real Map Surface */}
      <div className="map-layer">
        <MapCanvas
          position={position}
          heading={heading}
          followUser={followUser}
          vehicleType={vehicleType}
          route={activeRoute}
          destination={destination}
          safetyCameras={safetyCameras}
          styleUrl={MAP_STYLES[mapStyleKey]}
          showBairrosLayer={showBairrosLayer}
          showCicomsLayer={showCicomsLayer}
          showSedesLayer={showSedesLayer}
          onUserDrag={() => setFollowUser(false)}
          onMapSelect={selectMapDestination}
          onSelectPolygon={setSelectedPolygonInfo}
        />
      </div>

      {/* Top Bar with Search & Vehicle Personalization */}
      <header className="top-bar">
        <div className="brand-card">
          <div className="brand-icon">V</div>
          <strong>ViaVox</strong>
        </div>

        <form
          className="search-box-container"
          onSubmit={event => {
            event.preventDefault();
            void handleSearch();
          }}
        >
          <div className="search-input-wrapper">
            <span>🔍</span>
            <input
              type="text"
              className="search-input"
              placeholder="Para onde você quer ir? (Waze / G. Maps)"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              aria-label="Destino"
            />
            {searchQuery && (
              <button
                className="clear-search-btn"
                type="button"
                onClick={() => {
                  setSearchQuery('');
                  setSearchResults([]);
                  setNavigationError('');
                }}
              >
                ✕
              </button>
            )}
            <button className="search-submit-btn" type="submit" disabled={isSearching}>
              {isSearching ? 'Buscando...' : 'Buscar'}
            </button>
          </div>

          {searchResults.length > 0 && (
            <ul className="search-results-list">
              {searchResults.map((res, i) => (
                <li key={res.id || i}>
                  <button type="button" className="search-item" onClick={() => selectDestination(res)}>
                    <span className="search-item-title">{res.name}</span>
                    <span className="search-item-sub">{res.detail}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {navigationError && <div className="navigation-error" role="alert">{navigationError}</div>}
        </form>

        <div className="top-actions">
          {/* Layers Button */}
          <button
            className={`pill-btn ${showLayersModal ? 'active' : ''}`}
            onClick={() => setShowLayersModal(true)}
            title="Camadas e Circunscrições (Bairros e CICOMs)"
          >
            🥞 <span>Camadas</span>
          </button>

          {/* Quick Vehicle Switcher (Carro / Moto) */}
          <button
            className={`pill-btn ${vehicleType === 'motorcycle' ? 'active' : ''}`}
            onClick={() => setVehicleType(v => (v === 'car' ? 'motorcycle' : 'car'))}
            title="Alternar entre Carro e Moto"
          >
            {vehicleType === 'motorcycle' ? '🏍️' : '🚗'}
            <span>{vehicleType === 'motorcycle' ? 'Moto' : 'Carro'}</span>
          </button>

          {/* Settings Button */}
          <button
            className="icon-square-btn"
            onClick={() => setShowSettings(true)}
            aria-label="Configurações"
          >
            ⚙
          </button>
        </div>
      </header>

      {/* Selected Polygon Info Popup */}
      {selectedPolygonInfo && (
        <div className="polygon-info-card">
          <div className="polygon-info-left">
            <span className="polygon-title">{selectedPolygonInfo.title}</span>
            <span className="polygon-desc">{selectedPolygonInfo.desc}</span>
          </div>
          <button
            className="polygon-close"
            onClick={() => setSelectedPolygonInfo(null)}
          >
            ✕
          </button>
        </div>
      )}

      {pendingDestination && (
        <section className="route-preview-card" aria-label="Destino selecionado">
          <div>
            <strong>{pendingDestination.name}</strong>
            <span>Toque em “Navegar até aqui” para calcular a rota.</span>
          </div>
          <div className="route-preview-actions">
            <button type="button" className="route-cancel-btn" onClick={() => endNavigation(false)}>Cancelar</button>
            <button type="button" className="route-start-btn" onClick={confirmDestination} disabled={isRouting}>Navegar até aqui</button>
          </div>
        </section>
      )}

      {navigationSession.status === 'preview' && navigationSession.route && (
        <section className="route-preview-card" aria-label="Prévia da rota">
          <div>
            <strong>{navigationSession.route.destination.name}</strong>
            <span>
              {(navigationSession.route.distanceMeters / 1000).toFixed(1)} km ·{' '}
              {Math.max(1, Math.round(navigationSession.route.durationSeconds / 60))} min
            </span>
          </div>
          <div className="route-preview-actions">
            <button type="button" className="route-cancel-btn" onClick={() => endNavigation(false)}>
              Cancelar
            </button>
            <button type="button" className="route-start-btn" onClick={beginNavigation} disabled={isRouting}>
              Iniciar navegação
            </button>
          </div>
          {routeOptions.length > 1 && (
            <div className="route-preview-actions" aria-label="Alternativas de rota">
              {routeOptions.map((route, index) => (
                <button key={`${route.distanceMeters}-${index}`} type="button" className="route-cancel-btn" onClick={() => previewRoute(route)}>
                  {index === 0 ? 'Melhor rota' : `Alternativa ${index + 1}`} · {Math.max(1, Math.round(route.durationSeconds / 60))} min
                </button>
              ))}
            </div>
          )}
          <small>Trânsito em tempo real e zonas de risco verificadas não estão disponíveis nesta rota gratuita.</small>
        </section>
      )}

      {navigationSession.status === 'arrived' && (
        <section className="route-preview-card arrived" aria-live="polite">
          <div><strong>Você chegou ao destino</strong></div>
          <button type="button" className="route-start-btn" onClick={() => endNavigation(false)}>Concluir</button>
        </section>
      )}

      {/* Turn-by-Turn Navigation Banner */}
      {navigationSession.status === 'navigating' && activeRoute && currentStep && (
        <div className="nav-banner">
          <div className="nav-banner-left">
            <div className="nav-maneuver-icon">
              {currentStep.modifier?.includes('right')
                ? '↱'
                : currentStep.modifier?.includes('left')
                ? '↰'
                : currentStep.type?.includes('roundabout')
                ? '↻'
                : '↑'}
            </div>
            <div className="nav-instruction-text">
              <span className="nav-dist">
                {distToNextManeuver !== null
                  ? distToNextManeuver >= 1000
                    ? `${(distToNextManeuver / 1000).toFixed(1)} km`
                    : `${distToNextManeuver} m`
                  : ''}
              </span>
              <span className="nav-street">{currentStep.instruction}</span>
            </div>
          </div>
          <button
            className="nav-exit-btn"
            onClick={() => endNavigation()}
          >
            Encerrar
          </button>
        </div>
      )}

      {/* Floating Map Tools */}
      <div className="floating-tools">
        {/* Recenter Button */}
        {!followUser && (
          <button
            className="tool-circle-btn"
            onClick={() => setFollowUser(true)}
            title="Recentralizar na minha posição"
          >
            ◎
          </button>
        )}

        {/* Map Style Switcher (Bright / Dark / Liberty) */}
        <button
          className="tool-circle-btn"
          onClick={() => {
            const nextKey = mapStyleKey === 'bright' ? 'dark' : mapStyleKey === 'dark' ? 'liberty' : 'bright';
            setMapStyleKey(nextKey);
          }}
          title="Alternar estilo do mapa"
        >
          {mapStyleKey === 'bright' ? '☀️' : mapStyleKey === 'dark' ? '🌙' : '🗺️'}
        </button>
      </div>

      {/* Bottom Situational HUD Card */}
      <section className="bottom-card">
        <div className="status-row">
          <div className="status-info" role="status">
            <span className={`dot-indicator ${active ? 'live' : ''}`} />
            <span>{status}</span>
            {accuracy !== null && <span className="accuracy">±{accuracy} m</span>}
            {speedKmh > 0 && <span className="accuracy">· {speedKmh} km/h</span>}
          </div>
          <button
            className={`text-btn ${active ? 'pause' : ''}`}
            onClick={active ? stop : start}
          >
            {active ? 'Pausar' : 'Iniciar'}
          </button>
        </div>

        {/* Current Street or Notable Venue Name */}
        <h1 className="road-title">
          {place?.venueName || (place?.road && place.road !== 'Via não identificada' ? place.road : 'Identificando via atual…')}
        </h1>

        <p className="address">{subtitle || 'Aguardando leitura precisa do GPS'}</p>

        {/* Badges: Police District, Venue, Safety */}
        <div className="badges-row">
          {place?.police && enablePolice && (
            <div className="badge-police" title="Circunscrição Policial Competente">
              🛡️ {place.police}
            </div>
          )}
          {place?.venueName && (
            <div className="badge-venue" title="Local / Prédio Identificado">
              🏢 {place.road}
            </div>
          )}
          {safetyCameras.length > 0 && enableSafety && (
            <div className="badge-safety" title="Radares monitorados no percurso">
              📷 {safetyCameras.length} radares próximos
            </div>
          )}
        </div>

        {/* Action Controls */}
        <div className="controls-row">
          <label className="toggle">
            <input
              type="checkbox"
              checked={voice}
              onChange={e => setVoice(e.target.checked)}
            />
            Narração por voz
          </label>

          <button
            className="repeat-btn"
            disabled={!place || place.road === 'Via não identificada'}
            onClick={() => {
              if (!place) return;
              const msg = place.venueName
                ? `Você está no ${place.venueName}, bairro ${place.neighbourhood}.`
                : `Você está na ${place.road}, bairro ${place.neighbourhood}.`;
              void say(msg);
            }}
          >
            🔊 Repetir
          </button>
        </div>
      </section>

      {/* Layers & Circumscriptions Modal */}
      {showLayersModal && (
        <div className="modal-backdrop" onClick={() => setShowLayersModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>
              <span>🥞 Camadas & Circunscrições</span>
              <button
                className="clear-search-btn"
                onClick={() => setShowLayersModal(false)}
              >
                ✕
              </button>
            </h2>

            <div className="settings-section-title">Polígonos & Divisão Territorial</div>

            <div className="setting-toggle-row">
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">🎨 Bairros Oficiais por Cores (Zonas)</span>
                <span className="setting-toggle-desc">
                  Polígonos dos 63 bairros de Manaus divididos pelas cores de cada zona.
                </span>
              </div>
              <input
                type="checkbox"
                checked={showBairrosLayer}
                onChange={e => setShowBairrosLayer(e.target.checked)}
              />
            </div>

            {showBairrosLayer && (
              <div className="zone-legend-grid">
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#f97316' }} />
                  <span>Zona Sul</span>
                </div>
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#8b5cf6' }} />
                  <span>Centro-Sul</span>
                </div>
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#2563eb' }} />
                  <span>Zona Leste</span>
                </div>
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#10b981' }} />
                  <span>Zona Norte</span>
                </div>
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#06b6d4' }} />
                  <span>Zona Oeste</span>
                </div>
                <div className="zone-chip">
                  <span className="zone-dot" style={{ background: '#eab308' }} />
                  <span>Centro-Oeste</span>
                </div>
              </div>
            )}

            <div className="setting-toggle-row" style={{ marginTop: 14 }}>
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">🛡️ Circunscrições das CICOMs / DIPs</span>
                <span className="setting-toggle-desc">
                  Áreas e subáreas territoriais de atuação operacional policial das 30 CICOMs.
                </span>
              </div>
              <input
                type="checkbox"
                checked={showCicomsLayer}
                onChange={e => setShowCicomsLayer(e.target.checked)}
              />
            </div>

            <div className="setting-toggle-row">
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">🏢 Sedes das 30 CICOMs e DIPs</span>
                <span className="setting-toggle-desc">
                  Pontos de localização das companhias interativas e distritos de polícia.
                </span>
              </div>
              <input
                type="checkbox"
                checked={showSedesLayer}
                onChange={e => setShowSedesLayer(e.target.checked)}
              />
            </div>

            <div className="setting-toggle-row">
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">📷 Radares e Fiscalização</span>
                <span className="setting-toggle-desc">
                  Exibe ícones com limite de velocidade de radares fixos no percurso.
                </span>
              </div>
              <input
                type="checkbox"
                checked={enableSafety}
                onChange={e => setEnableSafety(e.target.checked)}
              />
            </div>

            <button className="modal-close-btn" onClick={() => setShowLayersModal(false)}>
              Concluir
            </button>
          </div>
        </div>
      )}

      {/* Complete Settings Modal */}
      {showSettings && (
        <div className="modal-backdrop" onClick={() => setShowSettings(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>
              <span>Configurações ViaVox</span>
              <button
                className="clear-search-btn"
                onClick={() => setShowSettings(false)}
              >
                ✕
              </button>
            </h2>

            <div className="settings-section-title">Veículo & Condução</div>
            <div className="vehicle-selector-row">
              <button
                className={`vehicle-choice-btn ${vehicleType === 'car' ? 'selected' : ''}`}
                onClick={() => setVehicleType('car')}
              >
                🚗 Carro
              </button>
              <button
                className={`vehicle-choice-btn ${vehicleType === 'motorcycle' ? 'selected' : ''}`}
                onClick={() => setVehicleType('motorcycle')}
              >
                🏍️ Moto
              </button>
            </div>

            <div className="settings-section-title">Estilo do Mapa</div>
            <div className="vehicle-selector-row">
              <button
                className={`vehicle-choice-btn ${mapStyleKey === 'bright' ? 'selected' : ''}`}
                onClick={() => setMapStyleKey('bright')}
              >
                ☀️ Claro (Waze/G. Maps)
              </button>
              <button
                className={`vehicle-choice-btn ${mapStyleKey === 'dark' ? 'selected' : ''}`}
                onClick={() => setMapStyleKey('dark')}
              >
                🌙 Escuro
              </button>
              <button
                className={`vehicle-choice-btn ${mapStyleKey === 'liberty' ? 'selected' : ''}`}
                onClick={() => setMapStyleKey('liberty')}
              >
                🗺️ Liberty
              </button>
            </div>

            <div className="settings-section-title">Segurança & Circunscrição</div>
            <div className="setting-toggle-row">
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">Áreas de Atuação Policial</span>
                <span className="setting-toggle-desc">
                  Anuncia DIPs e CICOMs competentes (ex: 3º DIP e 3ª CICOM no Japiim).
                </span>
              </div>
              <input
                type="checkbox"
                checked={enablePolice}
                onChange={e => setEnablePolice(e.target.checked)}
              />
            </div>

            <div className="setting-toggle-row">
              <div className="setting-toggle-info">
                <span className="setting-toggle-title">Alertas de Radares e Pedágios</span>
                <span className="setting-toggle-desc">
                  Avisos por voz antecipados sobre radares de velocidade e praças de pedágio.
                </span>
              </div>
              <input
                type="checkbox"
                checked={enableSafety}
                onChange={e => setEnableSafety(e.target.checked)}
              />
            </div>

            <div className="settings-section-title">Voz & Narração</div>
            <label className="field-label" style={{ display: 'block', fontSize: 13, marginBottom: 4 }}>
              Velocidade da fala: {voiceRate.toFixed(1)}x
            </label>
            <input
              type="range"
              min="0.8"
              max="1.3"
              step="0.05"
              value={voiceRate}
              style={{ width: '100%', marginBottom: 12 }}
              onChange={e => setVoiceRate(Number(e.target.value))}
            />

            <p className="free-voice-note">
              A voz usa o sintetizador do próprio aparelho, sem chave de API e sem cobrança.
            </p>

            <button className="modal-close-btn" onClick={() => setShowSettings(false)}>
              Salvar e Fechar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

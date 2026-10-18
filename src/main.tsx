import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Map as MapLibreMap, Marker, NavigationControl, setWorkerUrl } from 'maplibre-gl';
import type { LngLatLike } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import 'maplibre-gl/dist/maplibre-gl.css';

setWorkerUrl(workerUrl);
import './style.css';

type Place = {
  road: string;
  neighbourhood: string;
  city: string;
  reference: string;
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

function normalizeComparison(name: string): string {
  return normalizeRoad(name)
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]/g, '');
}


type Coordinates = { lat: number; lon: number };

const MAP_STYLE = 'https://tiles.openfreemap.org/styles/dark';

function MapCanvas({
  position,
  followUser,
  heading,
  onUserDrag
}: {
  position: Coordinates | null;
  followUser: boolean;
  heading: number | null;
  onUserDrag: () => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibreMap | null>(null);
  const locationMarker = useRef<Marker | null>(null);
  const onDrag = useRef(onUserDrag);
  onDrag.current = onUserDrag;

  useEffect(() => {
    if (!container.current) return;
    const instance = new MapLibreMap({
      container: container.current,
      style: MAP_STYLE,
      center: [-60.0235, -3.1025],
      zoom: 12,
      pitch: 45,
      attributionControl: true
    });
    map.current = instance;
    instance.addControl(new NavigationControl({ showZoom: false, showCompass: true }), 'top-right');
    instance.on('dragstart', () => onDrag.current());
    return () => {
      locationMarker.current?.remove();
      locationMarker.current = null;
      instance.remove();
      map.current = null;
    };
  }, []);

  useEffect(() => {
    if (!position || !map.current) return;
    const point: LngLatLike = [position.lon, position.lat];
    if (!locationMarker.current) {
      const dot = document.createElement('div');
      dot.className = 'gps-pin';
      dot.setAttribute('aria-label', 'Sua localização');
      locationMarker.current = new Marker({ element: dot }).setLngLat(point).addTo(map.current);
    } else {
      locationMarker.current.setLngLat(point);
    }
    if (followUser) {
      map.current.easeTo({
        center: point,
        zoom: 16.7,
        pitch: 48,
        bearing: heading ?? map.current.getBearing(),
        duration: 650
      });
    }
  }, [position, heading, followUser]);

  return <div ref={container} className="map-surface" aria-label="Mapa interativo ViaVox" />;
}

function App() {
  const [position, setPosition] = useState<Coordinates | null>(null);
  const [place, setPlace] = useState<Place | null>(null);
  const [status, setStatus] = useState('Obtendo localização GPS…');
  const [active, setActive] = useState(false);
  const [followUser, setFollowUser] = useState(true);
  const [accuracy, setAccuracy] = useState<number | null>(null);
  const [heading, setHeading] = useState<number | null>(null);
  const [voice, setVoice] = useState(() => localStorage.getItem('viavox.voice') !== 'false');
  const [voiceRate, setVoiceRate] = useState(() => Number(localStorage.getItem('viavox.rate') || '0.95'));
  const [brokerUrl, setBrokerUrl] = useState(() => localStorage.getItem('viavox.broker') || '');
  const [showSettings, setShowSettings] = useState(false);

  const watch = useRef<number | null>(null);
  const lastLookup = useRef(0);
  const lastPoint = useRef<{ lat: number; lon: number } | null>(null);
  const confirmedRoad = useRef('');
  const pending = useRef({ road: '', count: 0 });
  const busy = useRef(false);
  const session = useRef(0);
  const voiceRef = useRef(voice);
  const voiceRateRef = useRef(voiceRate);
  const brokerUrlRef = useRef(brokerUrl);

  useEffect(() => {
    voiceRef.current = voice;
    localStorage.setItem('viavox.voice', String(voice));
    if (!voice) window.speechSynthesis?.cancel();
  }, [voice]);

  useEffect(() => {
    voiceRateRef.current = voiceRate;
    localStorage.setItem('viavox.rate', String(voiceRate));
  }, [voiceRate]);

  useEffect(() => {
    brokerUrlRef.current = brokerUrl.trim();
    localStorage.setItem('viavox.broker', brokerUrl.trim());
  }, [brokerUrl]);

  function speakLocal(message: string) {
    if (!voiceRef.current || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(message);
    u.lang = 'pt-BR';
    u.rate = voiceRateRef.current;
    window.speechSynthesis.speak(u);
  }

  async function say(message: string) {
    if (!voiceRef.current || !message.trim()) return;
    const endpoint = brokerUrlRef.current;
    if (endpoint.startsWith('https://')) {
      try {
        const res = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: message, voice: 'pt-BR-FranciscaNeural' })
        });
        if (res.ok && (res.headers.get('content-type') || '').startsWith('audio/')) {
          const blob = await res.blob();
          const url = URL.createObjectURL(blob);
          const audio = new Audio(url);
          audio.onended = () => URL.revokeObjectURL(url);
          audio.onerror = () => {
            URL.revokeObjectURL(url);
            speakLocal(message);
          };
          await audio.play();
          return;
        }
      } catch {
        // Fallback to native speech synthesis
      }
    }
    speakLocal(message);
  }

  function buildMessage(p: Place, isTransition: boolean): string {
    const prefix = isTransition ? `Agora você está na ${p.road}` : `Você está na ${p.road}`;
    const bairro =
      p.neighbourhood && p.neighbourhood !== 'Bairro não identificado'
        ? `, bairro ${p.neighbourhood}`
        : '';
    return `${prefix}${bairro}.`;
  }

  async function lookup(lat: number, lon: number) {
    if (busy.current) return;
    const requestSession = session.current;
    busy.current = true;
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=18&addressdetails=1`;
      const res = await fetch(url, { headers: { 'Accept-Language': 'pt-BR' } });
      if (!res.ok) throw Error('Sem conexão para consultar endereço');
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
      const neighbourhood =
        clean(a.suburb) ||
        clean(a.neighbourhood) ||
        clean(a.quarter) ||
        clean(a.city_district) ||
        'Bairro não identificado';
      const city =
        clean(a.city) ||
        clean(a.town) ||
        clean(a.municipality) ||
        clean(a.village) ||
        '';
      const reference = clean(json.name) && clean(json.name) !== road ? clean(json.name) : '';

      const newPlace: Place = { road, neighbourhood, city, reference };
      setPlace(newPlace);
      setStatus('Localização ativa');

      if (road === 'Via não identificada') return;

      const norm = normalizeComparison(road);
      const currentNorm = confirmedRoad.current ? normalizeComparison(confirmedRoad.current) : '';

      if (!currentNorm) {
        confirmedRoad.current = road;
        pending.current = { road: '', count: 0 };
        void say(buildMessage(newPlace, false));
        return;
      }

      if (norm === currentNorm) {
        pending.current = { road: '', count: 0 };
        return;
      }

      if (normalizeComparison(pending.current.road) === norm) {
        pending.current.count += 1;
        if (pending.current.count >= 2) {
          confirmedRoad.current = road;
          pending.current = { road: '', count: 0 };
          void say(buildMessage(newPlace, true));
        }
      } else {
        pending.current = { road, count: 1 };
      }
    } catch (e) {
      if (requestSession === session.current) {
        setStatus(e instanceof Error ? e.message : 'Sem conexão para consultar endereço');
      }
    } finally {
      busy.current = false;
    }
  }

  function stop() {
    session.current++;
    lastLookup.current = 0;
    lastPoint.current = null;
    confirmedRoad.current = '';
    pending.current = { road: '', count: 0 };
    if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
    watch.current = null;
    setActive(false);
    setPlace(null);
    setStatus('Acompanhamento pausado');
    window.speechSynthesis?.cancel();
  }

  function start() {
    if (watch.current !== null) return;
    session.current++;
    if (!navigator.geolocation) {
      setStatus('GPS indisponível no dispositivo');
      return;
    }
    setStatus('Obtendo localização GPS…');
    setFollowUser(true);

    watch.current = navigator.geolocation.watchPosition(
      p => {
        const { latitude: lat, longitude: lon, accuracy: acc } = p.coords;
        setPosition({ lat, lon });
        setHeading(Number.isFinite(p.coords.heading) ? p.coords.heading : null);
        setAccuracy(Math.round(acc));
        setActive(true);
        if (acc > 70) {
          setStatus(`Sinal GPS impreciso (±${Math.round(acc)} m)`);
          return;
        }
        const now = Date.now();
        const prior = lastPoint.current;
        const meters = prior
          ? Math.hypot(
              (lat - prior.lat) * 111000,
              (lon - prior.lon) * 111000 * Math.cos((lat * Math.PI) / 180)
            )
          : Infinity;
        if (
          lastLookup.current === 0 ||
          (now - lastLookup.current >= 8000 && (meters >= 14 || now - lastLookup.current >= 45000))
        ) {
          lastLookup.current = now;
          lastPoint.current = { lat, lon };
          void lookup(lat, lon);
        }
      },
      e => {
        if (e.code === 1) {
          stop();
          setStatus('Permissão de localização necessária');
        } else {
          setStatus('Aguardando sinal de GPS…');
        }
      },
      { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 }
    );
  }

  // StrictMode safe: release the watch before the next effect instance.
  useEffect(() => {
    start();
    return () => {
      session.current++;
      if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
      watch.current = null;
      window.speechSynthesis?.cancel();
    };
  }, []);

  const subtitle = place
    ? `${place.neighbourhood !== 'Bairro não identificado' ? place.neighbourhood : ''}${
        place.city ? (place.neighbourhood !== 'Bairro não identificado' ? ` · ${place.city}` : place.city) : ''
      }`
    : '';

  return (
    <div className="viewport">
      <div className="map-layer">
        <MapCanvas
          position={position}
          followUser={followUser}
          heading={heading}
          onUserDrag={() => setFollowUser(false)}
        />
      </div>

      <header className="top-bar">
        <div className="brand">
          <span className="brandicon">◉</span>
          <strong>ViaVox</strong>
        </div>
        <button
          className="icon-btn"
          onClick={() => setShowSettings(true)}
          aria-label="Configurações"
        >
          ⚙
        </button>
      </header>

      {!followUser && (
        <button
          className="recenter-btn"
          onClick={() => setFollowUser(true)}
          aria-label="Recentralizar mapa"
        >
          ◎
        </button>
      )}

      <section className="bottom-card">
        <div className="status-row">
          <div className="status-info" role="status">
            <span className={`dot-indicator ${active ? 'live' : ''}`} />
            <span>{status}</span>
            {accuracy !== null && <span className="accuracy">±{accuracy} m</span>}
          </div>
          <button
            className={`text-btn ${active ? 'pause' : ''}`}
            onClick={active ? stop : start}
          >
            {active ? 'Pausar' : 'Iniciar'}
          </button>
        </div>

        <h1 className="road-title">
          {place?.road && place.road !== 'Via não identificada'
            ? place.road
            : 'Identificando rua atual…'}
        </h1>

        <p className="address">{subtitle || 'Aguardando coordenadas do GPS'}</p>

        {place?.reference && <div className="reference-pill">📍 {place.reference}</div>}

        <div className="controls-row">
          <label className="toggle">
            <input
              type="checkbox"
              checked={voice}
              onChange={e => setVoice(e.target.checked)}
            />
            Voz ativa
          </label>

          <button
            className="repeat-btn"
            disabled={!place || place.road === 'Via não identificada'}
            onClick={() => place && void say(buildMessage(place, false))}
          >
            🔊 Repetir
          </button>
        </div>
      </section>

      {showSettings && (
        <div className="modal-backdrop" onClick={() => setShowSettings(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>Configurações</h2>

            <label className="field-label">
              Velocidade da voz: {voiceRate.toFixed(1)}x
            </label>
            <input
              type="range"
              min="0.8"
              max="1.3"
              step="0.1"
              value={voiceRate}
              onChange={e => setVoiceRate(Number(e.target.value))}
            />

            <label className="field-label">
              Servidor de voz (HTTPS opcional)
            </label>
            <input
              type="url"
              placeholder="https://seu-servidor.exemplo/tts"
              value={brokerUrl}
              onChange={e => setBrokerUrl(e.target.value)}
            />

            <button className="modal-close" onClick={() => setShowSettings(false)}>
              Concluir
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

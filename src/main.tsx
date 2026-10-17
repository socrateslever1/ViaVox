import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { MapContainer, TileLayer, Marker, useMap } from 'react-leaflet';
import type { LatLngExpression } from 'leaflet';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import './style.css';

const marker = L.divIcon({
  html: '<span class="dot"></span>',
  className: 'pin',
  iconSize: [22, 22],
  iconAnchor: [11, 11]
});

type Place = {
  road: string;
  neighbourhood: string;
  city: string;
  reference: string;
};

const clean = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

function Camera({ position }: { position: LatLngExpression }) {
  const map = useMap();
  useEffect(() => {
    map.setView(position, 17);
  }, [map, position]);
  return null;
}

function App() {
  const [position, setPosition] = useState<LatLngExpression>([-3.119, -60.022]);
  const [place, setPlace] = useState<Place | null>(null);
  const [status, setStatus] = useState('Pronto para localizar');
  const [active, setActive] = useState(false);
  const [voice, setVoice] = useState(() => localStorage.getItem('viavox.voice') !== 'false');
  const [accuracy, setAccuracy] = useState<number | null>(null);

  const watch = useRef<number | null>(null);
  const lastLookup = useRef(0);
  const lastPoint = useRef<{ lat: number; lon: number } | null>(null);
  const lastRoad = useRef('');
  const pending = useRef({ road: '', count: 0 });
  const lastSpeech = useRef(0);
  const busy = useRef(false);
  const session = useRef(0);
  const voiceRef = useRef(voice);

  useEffect(() => {
    voiceRef.current = voice;
    localStorage.setItem('viavox.voice', String(voice));
    if (!voice) window.speechSynthesis?.cancel();
  }, [voice]);

  function say(message: string) {
    if (!voiceRef.current || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(message);
    u.lang = 'pt-BR';
    u.rate = 0.95;
    window.speechSynthesis.speak(u);
    lastSpeech.current = Date.now();
  }

  async function lookup(lat: number, lon: number) {
    if (busy.current) return;
    const requestSession = session.current;
    busy.current = true;
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=18&addressdetails=1`;
      const res = await fetch(url, { headers: { 'Accept-Language': 'pt-BR' } });
      if (!res.ok) throw Error(`Serviço de endereços indisponível (${res.status})`);
      const json = await res.json();
      if (requestSession !== session.current) return;
      const a = json.address || {};
      const road =
        clean(a.road) ||
        clean(a.pedestrian) ||
        clean(a.residential) ||
        clean(a.path) ||
        'Via não identificada';
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
      setPlace({ road, neighbourhood, city, reference });
      setStatus('Localização atualizada');
      if (road === 'Via não identificada') return;
      if (lastRoad.current === road) {
        pending.current = { road: '', count: 0 };
        return;
      }
      pending.current =
        pending.current.road === road
          ? { road, count: pending.current.count + 1 }
          : { road, count: 1 };
      if (pending.current.count >= 2 && Date.now() - lastSpeech.current > 12000) {
        lastRoad.current = road;
        pending.current = { road: '', count: 0 };
        say(
          `Você está na ${road}, bairro ${neighbourhood}${reference ? `, próximo a ${reference}` : ''}.`
        );
      }
    } catch (e) {
      if (requestSession === session.current) {
        setStatus(e instanceof Error ? e.message : 'Erro ao identificar endereço');
      }
    } finally {
      busy.current = false;
    }
  }

  function stop() {
    session.current++;
    if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
    watch.current = null;
    setActive(false);
    setStatus('Localização pausada');
    window.speechSynthesis?.cancel();
  }

  function start() {
    if (watch.current !== null) return;
    session.current++;
    if (!navigator.geolocation) {
      setStatus('GPS indisponível neste navegador');
      return;
    }
    setStatus('Aguardando permissão de localização...');
    lastRoad.current = '';
    pending.current = { road: '', count: 0 };
    lastLookup.current = 0;
    lastPoint.current = null;

    watch.current = navigator.geolocation.watchPosition(
      p => {
        const { latitude: lat, longitude: lon, accuracy: acc } = p.coords;
        setPosition([lat, lon]);
        setAccuracy(Math.round(acc));
        setActive(true);
        if (acc > 80) {
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
        if (now - lastLookup.current >= 11000 && (meters >= 18 || now - lastLookup.current >= 60000)) {
          lastLookup.current = now;
          lastPoint.current = { lat, lon };
          void lookup(lat, lon);
        }
      },
      e => {
        setStatus(`Falha no GPS: ${e.message}`);
        if (e.code === 1) {
          stop();
          setStatus('Permissão de localização negada');
        }
      },
      { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 }
    );
  }

  useEffect(() => {
    return () => {
      if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
      window.speechSynthesis?.cancel();
    };
  }, []);

  return (
    <main>
      <header>
        <div className="brand">
          <span className="brandicon">◉</span>
          <div>
            <strong>ViaVox</strong>
            <small>Sua localização ganha voz</small>
          </div>
        </div>
        <span className="version">BETA 0.2</span>
      </header>

      <section className="map">
        <MapContainer center={position} zoom={14} zoomControl={false} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          />
          <Marker position={position} icon={marker} />
          <Camera position={position} />
        </MapContainer>
      </section>

      <section className="panel">
        <div className="eyebrow">
          <span className={active ? 'live' : ''}>●</span>{' '}
          {active ? 'ACOMPANHANDO TRAJETO' : 'LOCALIZAÇÃO PAUSADA'}{' '}
          {accuracy !== null && <span className="accuracy">GPS ±{accuracy} m</span>}
        </div>
        <h1>{place?.road || 'Onde você está?'}</h1>
        <p className="address">
          {place
            ? `${place.neighbourhood}${place.city ? ` · ${place.city}` : ''}`
            : 'Ative a localização para começar a identificar as ruas.'}
        </p>
        {place?.reference && <p className="reference">📍 Referência: {place.reference}</p>}
        <p className="status" role="status">
          {status}
        </p>

        <button className={active ? 'stop' : 'start'} onClick={active ? stop : start}>
          {active ? '■ Parar narração' : '▶ Iniciar localização'}
        </button>

        <label className="toggle">
          <input
            type="checkbox"
            checked={voice}
            onChange={e => setVoice(e.target.checked)}
          />{' '}
          Narração por voz
        </label>

        <button
          className="repeat"
          disabled={!place}
          onClick={() =>
            place &&
            say(
              `Você está na ${place.road}, bairro ${place.neighbourhood}${
                place.reference ? `, próximo a ${place.reference}` : ''
              }.`
            )
          }
        >
          🔊 Ouvir localização atual
        </button>

        <p className="note">
          Versão Android: construída nativamente com Kotlin, Jetpack Compose e síntese de voz (TTS em português do Brasil). Os nomes de vias e pontos de referência utilizam os dados do OpenStreetMap.
        </p>
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

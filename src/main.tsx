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

type POI = {
  name: string;
  direction: 'RIGHT' | 'LEFT' | 'NEARBY';
};

const clean = (v: unknown) => (typeof v === 'string' ? v.trim() : '');

function Camera({ position }: { position: LatLngExpression }) {
  const map = useMap();
  useEffect(() => {
    map.setView(position, 16);
  }, [map, position]);
  return null;
}

// Simulated vehicle trajectory along real Manaus avenues for instant testing in preview
const SIMULATED_ROUTE = [
  { lat: -3.1025, lon: -60.0235, road: 'Avenida Djalma Batista', neighbourhood: 'Chapada', city: 'Manaus', poi: null },
  { lat: -3.1042, lon: -60.0231, road: 'Avenida Djalma Batista', neighbourhood: 'Chapada', city: 'Manaus', poi: { name: 'Amazonas Shopping', direction: 'RIGHT' as const } },
  { lat: -3.1068, lon: -60.0225, road: 'Avenida Djalma Batista', neighbourhood: 'Chapada', city: 'Manaus', poi: null },
  { lat: -3.1085, lon: -60.0210, road: 'Rua Pará', neighbourhood: 'Nossa Senhora das Graças', city: 'Manaus', poi: null },
  { lat: -3.1110, lon: -60.0185, road: 'Rua Pará', neighbourhood: 'Nossa Senhora das Graças', city: 'Manaus', poi: { name: 'Hospital 28 de Agosto', direction: 'LEFT' as const } },
  { lat: -3.1130, lon: -60.0170, road: 'Rua Paraíba', neighbourhood: 'Adrianópolis', city: 'Manaus', poi: null },
  { lat: -3.1155, lon: -60.0160, road: 'Avenida Maceió', neighbourhood: 'Adrianópolis', city: 'Manaus', poi: { name: 'Parque Municipal do Idoso', direction: 'RIGHT' as const } }
];

function App() {
  const [position, setPosition] = useState<LatLngExpression>([-3.1025, -60.0235]);
  const [place, setPlace] = useState<Place | null>(null);
  const [poi, setPoi] = useState<POI | null>(null);
  const [status, setStatus] = useState('Pronto para localizar');
  const [active, setActive] = useState(false);
  const [simulating, setSimulating] = useState(false);
  const [speed, setSpeed] = useState<number | null>(null);
  const [accuracy, setAccuracy] = useState<number | null>(null);
  const [voice, setVoice] = useState(() => localStorage.getItem('viavox.voice') !== 'false');
  const [voiceRate, setVoiceRate] = useState(0.95);
  const [showApkModal, setShowApkModal] = useState(false);

  const watch = useRef<number | null>(null);
  const simTimer = useRef<number | null>(null);
  const simIndex = useRef(0);
  const lastRoad = useRef('');
  const lastSpeech = useRef(0);
  const busy = useRef(false);
  const session = useRef(0);
  const voiceRef = useRef(voice);
  const voiceRateRef = useRef(voiceRate);

  useEffect(() => {
    voiceRef.current = voice;
    localStorage.setItem('viavox.voice', String(voice));
    if (!voice) window.speechSynthesis?.cancel();
  }, [voice]);

  useEffect(() => {
    voiceRateRef.current = voiceRate;
  }, [voiceRate]);

  function say(message: string) {
    if (!voiceRef.current || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(message);
    u.lang = 'pt-BR';
    u.rate = voiceRateRef.current;
    window.speechSynthesis.speak(u);
    lastSpeech.current = Date.now();
  }

  function handleRoadAnnounce(newRoad: string, newNeighbourhood: string) {
    if (!newRoad || newRoad === 'Via não identificada') return;
    if (lastRoad.current === newRoad) return;

    if (!lastRoad.current) {
      // First identified road announced immediately
      lastRoad.current = newRoad;
      say(`Você está na ${newRoad}, bairro ${newNeighbourhood}.`);
    } else {
      // Transitioning to a new street
      lastRoad.current = newRoad;
      say(`Agora você está na ${newRoad}, bairro ${newNeighbourhood}.`);
    }
  }

  function handlePoiAnnounce(currentPoi: POI) {
    const dirText =
      currentPoi.direction === 'RIGHT'
        ? 'À sua direita'
        : currentPoi.direction === 'LEFT'
        ? 'À sua esquerda'
        : 'Próximo daqui';
    say(`${dirText}, encontra-se ${currentPoi.name}.`);
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
      handleRoadAnnounce(road, neighbourhood);
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
    if (simTimer.current !== null) clearInterval(simTimer.current);
    simTimer.current = null;
    setActive(false);
    setSimulating(false);
    setSpeed(null);
    setStatus('Localização pausada');
    window.speechSynthesis?.cancel();
  }

  function start() {
    stop();
    session.current++;
    if (!navigator.geolocation) {
      setStatus('GPS indisponível neste navegador. Use a "Simulação de Trajeto".');
      return;
    }
    setStatus('Aguardando permissão de localização...');
    lastRoad.current = '';

    watch.current = navigator.geolocation.watchPosition(
      p => {
        const { latitude: lat, longitude: lon, accuracy: acc, speed: spd } = p.coords;
        setPosition([lat, lon]);
        setAccuracy(Math.round(acc));
        if (spd !== null && spd !== undefined) {
          setSpeed(Math.round(spd * 3.6));
        }
        setActive(true);
        setSimulating(false);
        if (acc > 80) {
          setStatus(`Sinal GPS impreciso (±${Math.round(acc)} m)`);
          return;
        }
        void lookup(lat, lon);
      },
      e => {
        setStatus(`GPS não disponível: ${e.message}. Use o botão "Simular Trajeto".`);
        if (e.code === 1) {
          stop();
          setStatus('Permissão de GPS negada. Teste com a "Simulação de Trajeto".');
        }
      },
      { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 }
    );
  }

  function startSimulation() {
    stop();
    session.current++;
    setActive(true);
    setSimulating(true);
    setAccuracy(8);
    setSpeed(36);
    setStatus('Acompanhando trajeto simulado...');
    lastRoad.current = '';
    simIndex.current = 0;

    const step = SIMULATED_ROUTE[0];
    setPosition([step.lat, step.lon]);
    setPlace({
      road: step.road,
      neighbourhood: step.neighbourhood,
      city: step.city,
      reference: ''
    });
    setPoi(null);
    handleRoadAnnounce(step.road, step.neighbourhood);

    simTimer.current = window.setInterval(() => {
      simIndex.current = (simIndex.current + 1) % SIMULATED_ROUTE.length;
      const current = SIMULATED_ROUTE[simIndex.current];
      setPosition([current.lat, current.lon]);
      setPlace({
        road: current.road,
        neighbourhood: current.neighbourhood,
        city: current.city,
        reference: current.poi?.name || ''
      });
      setPoi(current.poi);
      setStatus('Localização atualizada');

      if (current.poi) {
        handlePoiAnnounce(current.poi);
      } else {
        handleRoadAnnounce(current.road, current.neighbourhood);
      }
    }, 6000);
  }

  useEffect(() => {
    return () => {
      if (watch.current !== null) navigator.geolocation.clearWatch(watch.current);
      if (simTimer.current !== null) clearInterval(simTimer.current);
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

        <div className="header-actions">
          <button
            className="badge-btn"
            onClick={() => setShowApkModal(true)}
            title="Informações sobre o APK Android"
          >
            📱 APK & Código
          </button>
          <span className="version">BETA 0.2</span>
        </div>
      </header>

      <section className="map">
        <MapContainer
          center={position}
          zoom={16}
          zoomControl={false}
          style={{ height: '100%', width: '100%' }}
        >
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
          {speed !== null && <span className="speed-badge">{speed} km/h</span>}
          {simulating && <span className="speed-badge">SIMULAÇÃO ATIVA</span>}
        </div>

        <h1>{place?.road || 'Onde você está?'}</h1>
        <p className="address">
          {place
            ? `${place.neighbourhood}${place.city ? ` · ${place.city}` : ''}`
            : 'Inicie a localização ou a simulação para identificar as vias em tempo real.'}
        </p>

        {poi && (
          <div className="poi-card">
            <div className="poi-dir">
              {poi.direction === 'RIGHT'
                ? '👉 À sua direita'
                : poi.direction === 'LEFT'
                ? '👈 À sua esquerda'
                : '📍 Próximo daqui'}
            </div>
            <div className="poi-name">{poi.name}</div>
          </div>
        )}

        <p className="status" role="status">
          {status}
        </p>

        <div className="button-row">
          <button className={active ? 'stop' : 'start'} onClick={active ? stop : start}>
            {active ? '■ Parar narração' : '▶ Iniciar GPS Real'}
          </button>
          {!active && (
            <button className="sim-btn" onClick={startSimulation}>
              🚗 Simular Rota
            </button>
          )}
        </div>

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

        <div className="android-info-box">
          <h3>📱 Aplicativo Nativo Android em app/</h3>
          <p>
            O código nativo completo está pronto na pasta <code>app/</code> com Foreground Service,
            WakeLock para tela bloqueada, síntese de voz nativa e consultas espaciais do OpenStreetMap.
            Clique no botão <strong>📱 APK & Código</strong> no topo para instruções de exportação.
          </p>
        </div>
      </section>

      {showApkModal && (
        <div className="modal-backdrop" onClick={() => setShowApkModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <h2>📱 Como Gerar o APK do ViaVox</h2>
            <p>
              O ViaVox foi reescrito como um <strong>aplicativo nativo Android completo</strong> em
              Kotlin e Jetpack Compose, localizado na pasta <code>app/</code>.
            </p>
            <p>
              Como este projeto foi importado do GitHub com base Web, o preview do AI Studio executa o
              servidor Node.js para visualização interativa instantânea. Para compilar o arquivo{' '}
              <code>.apk</code> ou instalar no seu celular Android:
            </p>
            <ul>
              <li>
                <strong>Opção 1 (Android Studio):</strong> Baixe o projeto pelo menu do AI Studio
                (ícone de configurações/export $\to$ <em>Export to ZIP</em> ou <em>Push to GitHub</em>),
                abra no Android Studio e clique em <code>Build &gt; Build APK(s)</code>.
              </li>
              <li>
                <strong>Opção 2 (Direto no Celular):</strong> Conecte seu smartphone via USB com a
                depuração USB ativada e clique em <strong>Run 'app'</strong> no Android Studio. O app
                rodará com o <code>ForegroundService</code> real, inclusive com a tela desligada.
              </li>
            </ul>
            <p>
              Enquanto isso, você pode testar todo o fluxo de narração por voz e geocodificação aqui mesmo
              no preview usando o botão <strong>"🚗 Simular Rota"</strong> ou o <strong>"▶ Iniciar GPS Real"</strong>!
            </p>
            <button className="modal-close" onClick={() => setShowApkModal(false)}>
              Entendi
            </button>
          </div>
        </div>
      )}
    </main>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

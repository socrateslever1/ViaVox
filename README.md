# ViaVox (Android & Web)

> **Sua localização ganha voz.** Aplicativo funcional em Kotlin e Jetpack Compose que narra automaticamente ao motorista o nome da rua, o bairro e pontos de referência relevantes durante o deslocamento em tempo real, sem necessidade de definir destino, com suporte a operação contínua com tela bloqueada.

O projeto também possui uma interface web responsiva com mapa, pesquisa de destino, prévia de rota e navegação por voz. Ela funciona sem login e utiliza serviços públicos baseados em OpenStreetMap, sem chave de API paga.

## 🌐 Executar a versão web

Requer Node.js 22 ou superior. Na primeira execução, instale as dependências antes de iniciar o Vite:

```powershell
npm install
npm run dev
```

Abra o endereço exibido pelo Vite. No navegador:

1. permita o acesso à localização;
2. digite um destino e pressione **Buscar**;
3. escolha um resultado para visualizar distância e tempo;
4. pressione **Iniciar navegação**.

Também é possível clicar com o botão direito ou manter pressionado um ponto no mapa para escolhê-lo como destino. A voz utiliza o sintetizador local do navegador/aparelho. A busca usa Photon, a rota usa OSRM e o mapa usa OpenFreeMap/MapLibre; nenhum login ou chave paga é necessário.

Para validar a versão web:

```powershell
npm test
npm run build
```

---

## 📱 Recursos e Arquitetura Android Nativa

1. **Foreground Service de Localização Contínua (`ViaVoxTrackingService`)**:
   - Serviço em primeiro plano com tipo `location` (`android:foregroundServiceType="location"`).
   - Notificação persistente contendo a via/bairro atual e ação direta de **Parar**.
   - Aquisição de `WakeLock` parcial (`PARTIAL_WAKE_LOCK`) para garantir o ciclo de rastreamento e narração mesmo com a tela bloqueada ou alternando entre aplicativos.
   - Encerramento completo de recursos e liberação de memória ao selecionar **Parar**.

2. **Identificação Inteligente de Ruas (`RoadDetectionEngine`)**:
   - Anúncio imediato da primeira rua identificada sem exigência de contagens adicionais.
   - Detecção de mudanças reais de rua baseada em velocidade, deslocamento e confirmação temporal.
   - Normalização de abreviações ("Av.", "R.", "Trav.", "Rod.") e remoção de acentos/pontuação para comparação precisa.
   - Prevenção de falsos anúncios em cruzamentos e oscilações momentâneas de sinal de GPS.
   - Cancelamento automático de requisições obsoletas quando novas coordenadas são obtidas.

3. **Módulo de Pontos de Referência Reais (`PoiDiscoveryService`)**:
   - Descoberta espacial de pontos de interesse relevantes no OpenStreetMap via API Overpass.
   - Categorias prioritárias: Hospitais e Unidades de Saúde, Escolas e Faculdades, Praças e Parques, Postos de Combustível e Prédios Públicos / Monumentos.
   - Sistema de cache espacial local por células de 250m com intervalo mínimo de 90s para respeitar as políticas públicas dos servidores OSM.
   - Determinação de posição relativa em tempo real quando o veículo está em movimento:
     - **"À sua direita, encontra-se..."**
     - **"À sua esquerda, encontra-se..."**
     - **"Próximo daqui, encontra-se..."** (quando em baixa velocidade ou sem vetor de direção).
   - Memória de locais recentes para nunca repetir o mesmo ponto durante o trajeto.

4. **Síntese de Voz Natural & Gerenciamento de Foco de Áudio (`TextToSpeechManager`)**:
   - Voz em Português Brasileiro (`pt-BR`).
   - Gerenciamento de foco de áudio (`AudioFocusRequest` com `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` no Android 8.0+), reduzindo o volume de músicas/rádio durante os anúncios.
   - Velocidade da fala ajustável (0.8x a 1.3x) e intervalo configurável entre anúncios não urgentes.
   - Cancelamento imediato de fala ao pausar ou parar o serviço.

5. **Interface e Configurações (Material Design 3)**:
   - Identidade visual ViaVox mantida: Azul-escuro `#12283F` e Verde-turquesa `#54D6B1`.
   - Diálogo de configurações para ajuste de velocidade de voz, frequência de anúncios e ativação/desativação de categorias de pontos de interesse.
   - Compatibilidade total com layout responsivo (smartphone e tablet).

---

## 🛠️ Tecnologias Utilizadas

- **Linguagem**: Kotlin 2.1.0
- **UI Toolkit**: Jetpack Compose com Material Design 3
- **Arquitetura**: MVVM com `TrackingRepository` (StateFlow reativo compartilhado entre Foreground Service e ViewModel)
- **Rede & APIs**: OkHttp 4.12, OpenStreetMap Nominatim e Overpass API
- **Áudio**: Android TextToSpeech nativo + AudioManager AudioFocus
- **Localização**: Android Location API + Foreground Service de localização
- **Testes Unitários**: JUnit 4 (`PlaceTest`, `PointOfInterestTest`, `RoadDetectionEngineTest`)

---

## 🧪 Validação e Testes

- Testes unitários para cálculo de direção relativa (Esquerda/Direita/Próximo) e distância espacial.
- Testes de normalização e comparação de nomes de vias.
- **Validações que dependem de dispositivo físico**:
  - Continuidade de narração por voz com a tela apagada (tela bloqueada).
  - Comportamento das políticas de economia de bateria agressivas de fabricantes (Doze mode / Battery Saver).

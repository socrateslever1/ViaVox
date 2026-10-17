# ViaVox (Android)

> **Sua localização ganha voz.** Aplicativo nativo Android desenvolvido em Kotlin e Jetpack Compose com suporte a rastreamento GPS em tempo real, geocodificação reversa OpenStreetMap e sintetização de voz (TTS em português do Brasil).

---

## 📱 Recursos e Funcionalidades

- **Rastreamento GPS em tempo real**: Monitoramento de latitude, longitude e precisão do sinal com detecção de precisão fraca (> 80 m).
- **Geocodificação Reversa (OpenStreetMap / Nominatim)**: Identificação automática de vias, bairros, cidades e pontos de referência próximos via API do Nominatim com cache inteligente e filtragem de distância (>= 18 m e intervalo mínimo de 11s).
- **Narração por Voz Inteligente (Text-to-Speech)**:
  - Sistema de confirmação dupla da via para evitar falsos anúncios de cruzamentos.
  - Intervalo de respiro de 12 segundos entre narrações automáticas.
  - Botão de repetição manual ("Ouvir localização atual").
  - Interruptor liga/desliga de narração com preferência persistida no dispositivo.
- **Interface Moderna (Material Design 3)**:
  - Layout responsivo para smartphones e tablets (painel lado a lado no modo expandido).
  - Componente de mapa interativo com animação de pulso no marcador GPS.
  - Ícone adaptativo customizado (Material You / Adaptive Icon) com paleta `#12283F` e `#54D6B1`.
  - Suporte total a Edge-to-Edge no Android 15+.

---

## 🛠️ Tecnologias Utilizadas

- **Linguagem**: Kotlin 2.1.0
- **UI Toolkit**: Jetpack Compose com Material Design 3
- **Arquitetura**: MVVM (Model-View-ViewModel) com StateFlow e Coroutines
- **Rede**: OkHttp 4.12
- **Áudio**: Android TextToSpeech nativo (`Locale("pt", "BR")`)
- **Localização**: Android Location API com permissões finas e grosseiras (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)
- **Compilador / Build**: Gradle Kotlin DSL (`build.gradle.kts`) com Version Catalog (`gradle/libs.versions.toml`)

---

## 🚀 Como Executar

### No Android Studio:
1. Abra a pasta do projeto no Android Studio.
2. Aguarde a sincronização do Gradle.
3. Conecte um dispositivo físico ou inicie um emulador Android com suporte a GPS.
4. Execute o app (`Run 'app'`).

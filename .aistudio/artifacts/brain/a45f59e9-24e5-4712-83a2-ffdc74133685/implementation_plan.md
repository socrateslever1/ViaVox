# Arquitetura 100% Gratuita e Livre do ViaVox (Sem Cartão de Crédito)

Com base na sua decisão de manter o ViaVox **100% gratuito e sem necessidade de cartão de crédito**, este plano consolida a arquitetura livre e de custo zero permanente, garantindo total independência de planos pagos do Google Cloud.

---

## Confirmação de Custo Zero (R$ 0,00 Permanente)

> [!NOTE]
> **Decisão confirmada:** Não utilizaremos o Google Maps SDK nem APIs pagas do Google Cloud (Places/Routes).  
> **Custo mensal:** R$ 0,00 (zero).  
> **Cartão de crédito:** Nenhum cartão necessário.

### Composição dos Serviços 100% Gratuitos do ViaVox

| Componente | Tecnologia Escolhida | Custo | Dependência Externa / Chave |
| :--- | :--- | :--- | :--- |
| **Mapa Visual** | MapLibre Native + OpenStreetMap | **R$ 0,00** | Nenhuma chave de API necessária. Sem cota de visualização. |
| **Navegação & Rotas** | OSRM (Open Source Routing Machine) | **R$ 0,00** | Servidor público aberto, sem cobrança. |
| **GPS & Satélite** | Google Play Services Location (`FusedLocationProviderClient`) | **R$ 0,00** | Nativo de todos os celulares Android. Não consome API e não tem custo. |
| **Pesquisa de Endereços** | Nominatim (OpenStreetMap) | **R$ 0,00** | Livre, sem custos. |
| **Narração de Voz (TTS)** | Android Local TextToSpeech (`android.speech.tts`) | **R$ 0,00** | Funciona no próprio processador do celular, sem servidor e 100% offline. |
| **Alertas & Radares** | GeoJSON local revisado (`radares-manaus.geojson`) + Overpass OSM | **R$ 0,00** | Armazenamento direto no app (`assets/`), sem servidor pago. |

---

## 1. Visão Geral das Próximas Ações no Projeto

Com a definição de manter a base gratuita:
1. **Preservar e Otimizar MapLibre**: Garantir máxima fluidez na renderização vetorial e suporte a cache offline de tiles.
2. **Integração de Alertas Viários (Radares e Fiscalização)**:
   - Integrar o carregamento do `radares-manaus.geojson` (recém-criado em `app/src/main/assets/`) no pipeline Android, combinando com dados do Overpass.
   - Manter a regra de priorização oficial (IMMU) sobre os pontos do OSM.
3. **Robustez de GPS e Voz**:
   - Manter o `FusedLocationProviderClient` para posicionamento em segundo plano sem gastar bateria excessiva.
   - Garantir que a fila de voz nativa (`TTSQueue`) continue operando com baixa latência e sem depender de conexão de rede.

---

## 2. Experiência do Usuário (UX)

- **Transparência Total**: O usuário do aplicativo nunca se deparará com telas pedindo faturamento ou bloqueios de cota.
- **Funcionamento em Modo Offline**: Se a conexão falhar ou o motorista estiver em área de sombra, o TTS local continua narrando manobras já baixadas e os alertas locais do `assets/` continuam ativos.
- **Privacidade**: As coordenadas de localização do usuário não são enviadas para plataformas de anúncios de terceiros.

---

## 3. Plano de Verificação

### Testes Automatizados e de Build
- [ ] Executar compilação com `compile_applet` para validar que todas as dependências do MapLibre e Android Compose continuam íntegras.
- [ ] Executar testes de unidade de alerta viário (`RoadAlerts` / deduplicação de radares).

### Validação Manual
- [ ] Verificar que o mapa carrega instantaneamente sem exigir qualquer chave de API no arquivo `.env`.
- [ ] Confirmar que o cálculo de rotas via OSRM responde sem restrições.
- [ ] Validar a síntese de voz usando o motor padrão do aparelho.

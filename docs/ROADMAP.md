# ViaVox — Plano de entrega e pendências
**Atualizado:** 2026-10-18 · **Android:** Kotlin / Compose / MapLibre · **Base auditada:** ViaVox 0.4.3.

## Funcionalidades já confirmadas em campo
- ✅ Detectar e anunciar a rua atual e a mudança de rua automaticamente, sem destino.
- ✅ Detectar a mudança de bairro e falar o bairro quando necessário.
- **Não remover esses comportamentos** nas próximas entregas. A validação de regressão permanece obrigatória.

## Funcionalidades pendentes / parciais

| Ordem | Lote | Prioridade | Pendência | GitHub |
| --- | --- | --- | --- | --- |
| 01 | 1 | P0 | POIs reais OSM: nós, vias e relações, cobertura, distância e cache | [#1](https://github.com/socrateslever1/ViaVox/issues/1) |
| 02 | 1 | P0 | Narrar POIs sem sobreposição com ruas/bairros | [#2](https://github.com/socrateslever1/ViaVox/issues/2) |
| 03 | 1 | P0 | Voz Android confiável, erros e fallback TTS | [#3](https://github.com/socrateslever1/ViaVox/issues/3) |
| 04 | 1 | P0 | Interface moderna funcional e mapa legível | [#4](https://github.com/socrateslever1/ViaVox/issues/4) |
| 05 | 2 | P1 | Buscar endereço, estabelecimento e destino | [#5](https://github.com/socrateslever1/ViaVox/issues/5) |
| 06 | 2 | P1 | Traçar rota real e mostrar ETA | [#6](https://github.com/socrateslever1/ViaVox/issues/6) |
| 07 | 2 | P1 | Navegação com instruções de conversão por voz | [#7](https://github.com/socrateslever1/ViaVox/issues/7) |
| 08 | 2 | P1 | Recalcular rota após desvio | [#8](https://github.com/socrateslever1/ViaVox/issues/8) |
| 09 | 3 | P1 | Gravar percursos e histórico persistente | [#9](https://github.com/socrateslever1/ViaVox/issues/9) |
| 10 | 3 | P1 | Locais salvos, favoritos e pontos personalizados | [#10](https://github.com/socrateslever1/ViaVox/issues/10) |
| 11 | 3 | P2 | Exportar GPX/KML | [#11](https://github.com/socrateslever1/ViaVox/issues/11) |
| 12 | 4 | P2 | Mapas offline licenciados | [#12](https://github.com/socrateslever1/ViaVox/issues/12) |
| 13 | 4 | P1 | Rumo, bússola, map matching e bateria com teste real | [#13](https://github.com/socrateslever1/ViaVox/issues/13) |
| 14 | 4 | P2 | Francisca Neural por servidor Azure seguro | [#14](https://github.com/socrateslever1/ViaVox/issues/14) |
| 15 | Todos | P0 | Testes de regressão, CI e APK instalável | [#15](https://github.com/socrateslever1/ViaVox/issues/15) |

### Critérios para considerar uma função concluída
1. Existe código **integrado ao Android nativo**; não apenas uma tela ilustrativa ou botão sem ação.
2. A funcionalidade tem testes possíveis e, quando aplicável, evidência de teste de campo.
3. O APK compila, tem assinatura válida e pode ser instalado no dispositivo do usuário.
4. Mudança de rua e bairro continuam funcionando, inclusive modo livre sem navegação.
5. Os resultados reais de TTS, GPS, POIs, provedores de rota e mapas são exibidos sem informações inventadas.
6. Cada item só é concluído após atender aos critérios escritos na respectiva issue.

### Detalhes técnicos dos pontos de referência
O código atual `PoiDiscoveryService.kt` usa consultas Overpass principalmente para `node`, filtra referências num limite fixo de 75m e usa uma janela de atualização que pode não acompanhar percursos rápidos. `ViaVoxTrackingService.kt` compartilha a narração com mudanças de via e poderá perder anúncios de POIs. Corrigir consultas, geometrias, fila de áudio, prioridade, deduplicação e tratamento da confiança GPS. **Não marcar como concluído sem anunciar referências verificadas em teste real.**

### ClickUp
A sincronização com o ClickUp está pendente da escolha do espaço de trabalho. Foram identificados dois workspaces, sem lista ViaVox atualmente. Não criar em workspace arbitrário ou duplicar tarefas.

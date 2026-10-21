# ViaVox — Plano de entrega e pendências
**Atualizado:** 2026-10-21 · **Android:** Kotlin / Compose · **Web:** React / MapLibre · **Base auditada:** ViaVox 0.9.8.

O estado detalhado e atual desta entrega está em `STATUS_0.9.8_FREE_OPEN.md`.

## Auditoria de continuidade — 20/10/2026

O cabeçalho e a tabela abaixo registram o planejamento original e não representam sozinhos o estado atual. O código sincronizado está na versão **0.9.8**. Fila única de voz, sessão em segundo plano, progresso de rota, câmera, perfis visuais, favoritos e exportação GPX possuem implementação, mas voz, tela bloqueada, câmera, GPS, CICOM e consumo ainda dependem de CI Android e validação em aparelho Samsung/percurso real.

O plano `2026-10-19-click-to-navigate.md` ficou com o ledger incompleto e foi parcialmente superado pelas decisões posteriores de UX do Android, que preservam pressão longa para escolher um ponto vazio. O plano `2026-10-20-official-road-alerts-safe-navigation.md` está em andamento: o parser/merge web e o carregamento da base local foram integrados, mas os GeoJSON oficiais permanecem vazios porque as páginas atuais do IMMU/Prefeitura informam locais e sentidos sem publicar coordenadas WGS84 autoritativas. Não preencher esses pontos por geocodificação aproximada nem anunciar cobertura oficial antes de revisão. A integração Android dessa base também permanece pendente.

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
| 14 | 4 | Removido | Francisca/Azure removida para manter o produto sem serviço pago ou chave | [#14](https://github.com/socrateslever1/ViaVox/issues/14) |
| 15 | Todos | P0 | Testes de regressão, CI e APK instalável | [#15](https://github.com/socrateslever1/ViaVox/issues/15) |

### Critérios para considerar uma função concluída
1. Existe código **integrado ao Android nativo**; não apenas uma tela ilustrativa ou botão sem ação.
2. A funcionalidade tem testes possíveis e, quando aplicável, evidência de teste de campo.
3. O APK compila, tem assinatura válida e pode ser instalado no dispositivo do usuário.
4. Mudança de rua e bairro continuam funcionando, inclusive modo livre sem navegação.
5. Os resultados reais de TTS, GPS, POIs, provedores de rota e mapas são exibidos sem informações inventadas.
6. Cada item só é concluído após atender aos critérios escritos na respectiva issue.

### Detalhes técnicos dos pontos de referência
O código atual filtra referências cívicas confiáveis com limite estrito de 30 m, precisão GPS, mesma via e direção do deslocamento, usando fila de voz e revalidação antes de falar. A seleção ainda depende da qualidade dos dados comunitários. **Não marcar como concluído sem anunciar referências verificadas em teste real.**

### ClickUp
A sincronização com o ClickUp está pendente da escolha do espaço de trabalho. Foram identificados dois workspaces, sem lista ViaVox atualmente. Não criar em workspace arbitrário ou duplicar tarefas.

## Início do Lote 1 — versão 0.5.0

**Código Android integrado em 18/10/2026 (pendente teste de campo):**

- Busca de pontos de referência ampliada para elementos reais OpenStreetMap do tipo `node`, `way` e `relation`, com centro aproximado quando fornecido pelo Overpass.
- Filtragem de referências em até 135 metros com GPS de precisão adequada e supressão de referências já ultrapassadas conforme rumo confiável.
- Cache de referências, limitação de frequência de consultas, exclusão de anúncios repetidos e retenção de cache anterior em falha de rede.
- Fila de referência que aguarda o anúncio de mudança de rua/bairro, sem interrompê-lo, e revalida posição antes de falar.
- Painel exibindo status real de busca, ausência de cadastro, falha de rede, referência detectada e envio para voz.
- Testes unitários para leitura de elementos OSM, proximidade/direção e prioridade da voz.
- **Não está concluído:** reproduzir e confirmar em campo os nomes reais dos postos, hospitais, escolas e praças e verificar a latência de voz em veículo.

**Próximas ações do Lote 1:** corrigir eventual problema identificado no teste de campo, validar voz Android, melhorar o mapa e integrar os controles de navegação planejados para o Lote 2.

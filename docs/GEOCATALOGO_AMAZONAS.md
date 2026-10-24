# ViaVox — catálogo geográfico local do Amazonas

## Cobertura instalada

- **62 municípios e códigos IBGE**, disponíveis offline desde a primeira execução.
- **Bairros e zonas de Manaus**, dos polígonos GeoJSON incluídos no APK.
- **Pontos de referência**, do catálogo verificado e das sedes policiais já embarcadas.
- **Ruas e pontos adicionais**, armazenados permanentemente em SQLite após serem consultados.
- **Pacote regional semanal**, publicado automaticamente pelo workflow
  `.github/workflows/amazonas-geopack.yml` no release `geopack-am-latest`
  com ruas e referências OSM recortadas pelos limites dos 62 municípios e,
  complementarmente, nomes da Base de Faces de Logradouros IBGE 2022.

O APK continua funcionando sem esse pacote. Quando existir um novo pacote e
o aparelho estiver em **Wi‑Fi não tarifado**, o serviço tenta a atualização
a cada sete dias (ou, se nunca baixou, novamente após 12 horas). Um erro de
rede, pacote truncado ou versão inválida nunca apaga a base anterior.

## Arquitetura

`GeoCatalogDatabase` cria `viavox_geography.db` com:

- `municipalities`: códigos IBGE, nomes, data da fonte.
- `neighborhoods`: município, nome, zona e proveniência.
- `streets`: município, logradouro, ponto representativo e proveniência.
- `places` + `place_bounds` (RTree): geocoordenadas verificadas com
  consulta por janela espacial, sem varrer todos os pontos.
- `aliases`: nome oficial e versão destinada à fala, sempre separados.
- `metadata`: versões e atualizações.

**ATENÇÃO:** o centro de uma rua ou de um polígono *não* representa entrada
segura, faixa de rolamento, sentido de circulação ou localização precisa da
pessoa. O ViaVox ainda usa validação geográfica própria para anunciar uma
mudança real de rua. Um nome importado jamais deve ser associado ao GPS
somente por estar próximo de um ponto representativo.

## 360° e À minha frente

1. Ao toque, o aplicativo publica estado visível e consulta imediatamente a
   base local com RTree.
2. A busca geométrica seleciona pontos na distância escolhida (100, 200, 350,
   500 m). À minha frente, usa rumo válido e cone ±65°.
3. `GeoContextInterpreter` prioriza referências significativas por categoria,
   distância e direção, sem inventar locais.
4. Resposta inicial é enviada assim que o banco fornece pontos; Overpass,
   ArcGIS e cruzamentos enriquecem a tela de forma independente, com
   aproximadamente 6,5 segundos de limite por fonte, sem repetir a mesma fala.
5. Resultados verificados são salvos em SQLite. Pré-consulta contextual ocorre
   no percurso no máximo a cada 2 minutos e após deslocamento >= 160 m.

A ausência de dados locais é indicada como tal: não é tomada como prova de
que ruas ou estabelecimentos não existem.

## Política de voz (não soletrar)

- Nunca alterar `official_name` por causa da TTS.
- Exemplo de alias aprovado:
  `Terminal Rodoviário Engenheiro Huascar Angelim` →
  **`Terminal Rodoviário de Manaus`**.
- Expandir siglas conhecidas (PMAM, CPA, etc.) e abreviações viárias.
- Não pronunciar sequências obscuras como XPT, QWRT; omitir referências
  não identificáveis. Nunca dar um nome de local inventado.
- Prioridade: mudança de zona/bairro/rua; POIs depois.
- IA generativa externa não atua no caminho crítico. A seleção contextual
  local é determinística e auditável; inferências externas necessitariam
  chaves, política de privacidade e aceitação do usuário.

## Fontes / licenças

- **IBGE — Base de Faces de Logradouros 2022, UF Amazonas**:
  https://geoftp.ibge.gov.br/recortes_para_fins_estatisticos/malha_de_setores_censitarios/censo_2022/base_de_faces_de_logradouros_versao_2022_censo_demografico/json/
- **IBGE localidades**: https://servicodados.ibge.gov.br/api/v1/localidades/estados/13/municipios
- **Geofabrik OpenStreetMap Norte**:
  https://download.geofabrik.de/south-america/brazil/norte.html
- **Malhas de municípios**: IBGE, via `henriquemalvar/br-geojson`.
- **ODbL-1.0**: os dados derivados de OpenStreetMap exigem atribuição
  © OpenStreetMap contributors e observância da cláusula de compartilhamento
  conforme o uso; o release informa a origem. Dados IBGE têm suas próprias
  condições de atribuição/reutilização. Não republicar dados restritos das
  prefeituras sem licença verificada.

## Geração e monitoramento

A Action `Amazonas Geographic Data Pack` baixa os extratos, valida os
62 polígonos e converte os dados para `amazonas-geopack.jsonl.gz`.
O formato é versionado, lido em streaming e contém trailer de integridade.
O arquivo fica em `geopack-am-latest`.

O aplicativo informa **números efetivos** do banco. Se o pacote não existir
ainda, não afirmar que todas as ruas já estão instaladas.

## Critérios para homologação em Android real

- 360° em 100 e 500 m, com modo avião, Wi‑Fi e rede móvel.
- À minha frente com bússola calibrada e em movimento; não indicar esquerda/
  direita quando posição aproximada ou orientação inválida.
- Rua, bairro e CICOM somente na área verdadeira de entrada.
- Teste na Praça 14, Torquato Tapajós, Terminal Rodoviário e outros
  municípios (Itacoatiara, Tefé, Parintins).
- Banco persistente após reinicializar, downgrade de sinal, falha de download
  e reinício durante importação.
- Testes de voz Android e verificação de que nenhuma abreviação desconhecida
  vira soletração de letras.

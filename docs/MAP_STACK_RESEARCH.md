# ViaVox — avaliação de projetos cartográficos (2026-10-19)

## Fontes analisadas
- https://github.com/topics/mapa — majoritariamente painéis, GeoJSON, mapas temáticos/SVG e projetos web. O `nucleo-digital/sp-mapas` contém GeoJSON da prefeitura de São Paulo; útil como referência para camadas GIS, **não** é base de Manaus nem motor de navegação Android.
- https://github.com/topics/interactive-map?l=php&o=desc&s=forks — o filtro PHP prioriza WordPress, tours virtuais, mapas de lojas/estabelecimentos. Não oferece SDK nativo de navegação para Jetpack Compose. `Mus9617/Mapas_interactivos` sugere ideias de marcadores públicos/privados, mas não justifica migrar Android para PHP.

## Opções que realmente atendem aos requisitos

| Projeto | Uso no ViaVox | Cuidados |
| --- | --- | --- |
| [MapLibre Native Android](https://github.com/maplibre/maplibre-native) | Manter renderer nativo atual; mapa vetorial com GPU, estilos e camadas | Verificar estilo, qualidade dos tiles, 3D e consumo de GPU no Samsung |
| [MapLibre Navigation Android/KMP](https://github.com/maplibre/maplibre-navigation-android) | Evoluir manobras, progresso de rota, chegada, recálculo e orientação | SDK em migração/KMP; investigar compatibilidade antes de introduzir. Testar offline/background |
| [EOxCloudless 2016](https://cloudless.eox.at/) | Camada Sentinel-2 opcional no APK e web | CC BY 4.0, atribuição obrigatória, imagem 2016–2017, 10 m, sem SLA; não equivale ao Google |
| [Organic Maps](https://github.com/organicmaps/organicmaps) | Referência de trilhas, offline e favoritos totalmente locais | App completo em outra arquitetura; não copiar SDK indiscriminadamente |
| [komoot Photon](https://github.com/komoot/photon) | Pesquisa textual eficiente e correção de nomes de locais | Servidor demo tem idiomas/cotas variáveis; hospedar instância se projeto crescer |
| [Valhalla](https://github.com/valhalla/valhalla) | Rotas e manobras para perfis suportados | Exige infraestrutura própria ou API contratada, ajustes regionais |

## Decisão de implementação atual
1. **Não migrar para PHP/WordPress.** O ViaVox é Android Kotlin/Compose + MapLibre e web separada.
2. **Corrigir busca antes de trocar o mapa**: retirar a dependência `lang=pt` de um Photon público que pode não ter pt indexado; utilizar POIs salvos do SQLite e fallback manual de geocodificação. Nunca inventar a sede da CICOM.
3. Respeitar [política pública do Nominatim](https://operations.osmfoundation.org/policies/nominatim/): apenas busca explícita, cache, User-Agent identificável, máximo 1 req/s **por aplicativo inteiro**, sem autocomplete. O endpoint público é provisório para teste individual; implantar proxy/instância própria ou contratado antes da distribuição em escala. O SDK deve permitir alterar serviço sem atualizar o APK nessa etapa de produção.
4. O Overpass de referências mantém cache/limitação de consultas e tenta outro endpoint público se houver 502. Não sobrecarregar provedores gratuitos; migrar a backend controlado se o uso crescer.
5. Mapa padrão permanece 2D e viário. A camada Sentinel-2 2016 é somente referência opcional. Não integrar mapa premium, chave paga ou camada 2018+ com restrição não comercial.

## Aceite do lote de correções
- Pesquisa por ruas e estabelecimentos: Photon responde sem parâmetro `lang=pt` forçado; fallback quando 400/502; nomes do SQLite podem ser encontrados offline.
- Alias `24 CICOM`: tentar também o endereço publicado na listagem SSP-AM, **Avenida Lourenço da Silva Braga com Rua Lima Bacuri, Centro, Manaus**; fonte: https://www.ssp.am.gov.br/wp-content/uploads/2018/05/Geral-3.pdf (documento histórico), com notícia 2024 https://amazoniaonline.com.br/amazonas/videomonitoramento-e-inaugurada-para-reforcar-combate-a-criminalidade-no-centro-de-manaus/. Não atribuir coordenadas sem retorno real do geocodificador; localização da unidade pode mudar. Locais favoritos com nome `24ª CICOM` devem aparecer.
- HUD: rua destacada; bairro e cidade legíveis; detalhes policiais e diagnóstico técnico somente ao expandir; POIs reais continuam identificados.
- Nenhuma regressão de voz de mudança de via/bairro.
- APK compilado, testes unitários e verificação da assinatura; validação de campo ainda necessária.

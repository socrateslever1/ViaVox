# ViaVox 0.9.8 — estado funcional e migração gratuita/open source

**Atualizado:** 21/10/2026  
**Base sincronizada:** `origin/main` em `8bef467`  
**Alvos:** APK Android nativo e aplicação web/PWA

## Política do produto

O ViaVox não exige cartão, conta de faturamento nem chave de API paga. Foram
removidos os caminhos de Gemini/Google Places e Microsoft Azure/Francisca,
inclusive campos de chave, plugin de secrets e worker de voz. A voz do APK usa
o TTS instalado no Android; a web usa o sintetizador disponível no navegador.

Stack principal:

- MapLibre GL JS para o mapa web e renderer raster Compose no Android;
- dados OpenStreetMap, OpenFreeMap, Nominatim, Photon e Overpass;
- OSRM para cálculo de rota;
- GeoJSON local para bairros, CICOMs, sedes e alertas revisados;
- EOxCloudless Sentinel-2 2016–2017 como camada de imagem opcional.

Serviços comunitários são usados com atribuição e uso moderado. Eles não têm
garantia de disponibilidade e não podem ser tratados como infraestrutura
gratuita ilimitada. O ViaVox não deve fazer download em massa/offline dos tiles
públicos do OpenStreetMap.

## Já existia antes deste lote

- rastreamento Android em foreground service com notificação persistente;
- modo livre sem destino e navegação por rota OSRM;
- primeira rua imediata, confirmação de troca de rua e fila serial de voz;
- progresso na geometria, manobras, recálculo, chegada e restauração de sessão;
- carro, motocicleta e caminhada com divulgação honesta do perfil de rota;
- favoritos, destinos recentes, rotas, gravação de percursos e exportação GPX;
- POIs OSM/ArcGIS filtrados, alertas de segurança e deduplicação;
- mapa web MapLibre, busca Photon, alternativas de rota e camadas GeoJSON;
- testes web e suíte unitária Android mantida no repositório.

## Alterado neste lote

### Voz e geografia

- Android: um único evento segue `Zona -> Bairro -> Rua -> CICOM`, sem a dupla
  reprodução do callback legado.
- CICOM vem desativada por padrão e só entra na fala quando a configuração é
  habilitada explicitamente; o texto falado
  não afirma que DIP e CICOM de mesmo número possuem a mesma jurisdição.
- Web: primeiro contexto e transições passaram a usar a mesma ordem estruturada.
- Bairros de Manaus agora são resolvidos pelos 63 polígonos locais no APK e na
  web. Os retângulos manuais sobrepostos foram removidos do resolver Android.
- Casos de referência foram adicionados para UFAM, Bola do Coroado e o lado do
  Aleixo na divisa.

### Pontos de referência e chegada

- Um POI próximo é narrado como `Você está passando por ...`, com esquerda ou
  direita somente quando rumo, velocidade, geometria e precisão permitem.
- Proximidade simples não produz mais `Você está em ...` na web.
- `Você está em <nome>` fica reservado à chegada confirmada da navegação, dentro
  do limiar de 35 metros e com velocidade aceita pelo motor Android.

### Mapa e câmera

- Perspectiva 3D removida; Android e web iniciam e navegam em 2D.
- Durante a navegação, a câmera usa curso GPS/segmento da rota e olha à frente;
  a orientação física do telefone não gira a rota ativa.
- Camada opcional Sentinel-2 aberta adicionada com atribuição visível. É uma
  imagem de referência antiga, resolução aproximada de 10 m e sem tempo real;
  não equivale ao satélite do Google.

### Custo e segurança

- removidos Gemini Maps Grounding, Google Places Enricher e suas configurações;
- removidos Azure Francisca, Cloudflare voice worker, endpoints e tokens;
- removido o plugin Gradle de secrets e o arquivo de exemplo de chave;
- o APK continua usando apenas voz local configurável do Android.

## Segundo plano

No APK, navegação, gravação ou a preferência explícita de voz em segundo plano
mantêm o foreground service. A notificação abre o ViaVox e permite parar o
serviço, encerrar a rota ou finalizar o percurso. Não foi adicionada a permissão
especial de sobreposição sobre outros aplicativos; a notificação é o controle
suspenso seguro do sistema.

Na web, navegador e sistema operacional podem suspender GPS e voz quando a aba
fica em segundo plano. Portanto a web só garante navegação enquanto a página/PWA
permanece ativa. Continuidade confiável com tela bloqueada é responsabilidade do
APK.

## Validação executada neste lote

- `npm test`: 23 testes aprovados;
- `npm run build`: build web aprovado;
- `git diff --check`: sem erro de whitespace;
- busca de runtime: nenhuma referência a Gemini, Google Places, Azure ou
  Francisca em `app/` e `src/`.

O ambiente local não possui Java, Gradle nem Android SDK. Por isso a compilação,
os testes unitários Android e a geração/assinatura do APK ainda precisam rodar
no workflow Android do GitHub antes de distribuição.

## Precisa de atenção antes de chamar de concluído

1. Executar CI Android (`assembleDebug` e `testDebugUnitTest`) e corrigir qualquer
   erro de compilação encontrado pelo toolchain real.
2. Instalar o APK sobre a versão atual, preservando banco e preferências.
3. Fazer percurso real na Bola do Coroado/UFAM/Aleixo e verificar que não ocorre
   alternância Coroado -> Aleixo -> Coroado por ruído de GPS.
4. Validar em áudio a pronúncia das 30 CICOMs e revisar a fonte/data da
   circunscrição. A tabela bairro -> DIP/CICOM ainda é uma referência manual;
   não é prova jurídica de competência territorial.
5. Revisar em campo os POIs; dados comunitários podem estar ausentes,
   desatualizados ou com nome inadequado.
6. Testar navegação com tela bloqueada, economia de bateria Samsung, Bluetooth,
   chamadas, perda de rede, retorno à tela e consumo durante pelo menos 30 min.
7. Confirmar disponibilidade e termos do endpoint Sentinel-2 antes de cada
   publicação; manter fallback para o mapa viário.
8. Validar visualmente web e APK em celular pequeno, tablet e orientação
   horizontal. Build automatizado não comprova layout ou comportamento em rota.
9. Dividir o bundle web principal, atualmente com cerca de 1,34 MB minificado
   (377 KB gzip); o build passa, mas o Vite alerta acima de 500 KB.

## Revisão dos nomes de CICOM e DIP

A Carta de Serviços da SSP-AM consultada em 21/10/2026 lista as unidades de
`1º DIP / 1º CICOM` a `30º DIP / 30º CICOM` e seus endereços. O ViaVox mantém
`1ª CICOM` na interface e na fala por concordância com “Companhia”, forma também
usada em notícias oficiais da PMAM. Essa fonte confirma os identificadores e
sedes, mas não publica os limites WGS84 dos 193 setores presentes no GeoJSON
local. Por isso o aplicativo não chama a camada de “competência oficial” e deixa
a narração desativada até escolha expressa do usuário.

Fonte primária: <https://www.ssp.am.gov.br/wp-content/uploads/2024/05/NOVA-CARTA-DE-SERVICO.pdf>.

## Dados e licenças relevantes

- OpenStreetMap: ODbL, com atribuição visível; servidores públicos possuem
  política própria e não permitem prefetch/offline em massa.
- MapLibre: renderer open source sob licença BSD.
- EOxCloudless 2016: CC BY 4.0, atribuição obrigatória, endpoint público sem SLA
  e sujeito a uso justo. Camadas mais novas não foram usadas porque possuem
  restrição não comercial ou licença paga.
- `bairros_manaus.geojson`: deve receber no próximo ciclo metadados explícitos
  de origem municipal, data de extração e procedimento de atualização.

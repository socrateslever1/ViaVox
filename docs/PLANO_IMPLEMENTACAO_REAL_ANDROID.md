# ViaVox Android — plano de implantação real (pós-0.7.1)

Status: **PLANEJADO / NÃO IMPLEMENTADO**. Este documento é um contrato de engenharia e aceite; marcar tarefas concluídas somente depois de alteração efetiva do código, testes verdes e validação em aparelho real.

Data-base: 19/10/2026. Branch auditada: main, commit 33569c8296ba2f7258958bd4829b3f9c88599d05.

## 0. Diagnóstico comprovado no código

- **Voz:** \`TextToSpeechManager.queueLocalSpeech\` chama \`TextToSpeech.QUEUE_FLUSH\` e \`speakLocally\` executa \`tts?.stop()\`. \`ViaVoxTrackingService\` chama \`speakIfAllowed\` diretamente a partir de mudanças de rua, POIs, radar e instruções de navegação. \`PoiAnnouncementCoordinator\` só coordena POI; não há uma fila global de falas nem conclusão única/ack entre voz local e Francisca.
- **Zona:** \`RoadDetectionEngine\` controla rua e bairro; \`Place.toNeighbourhoodTransitionMessage\` inclui a zona *dentro* da fala de bairro, não como transição independente. A primeira rua inclui bairro junto; ruas seguintes incluem bairro somente se mudar. A validação de zona requer limites geográficos oficiais/atribuição de fonte e histerese.
- **POIs:** existem categorias configuráveis, mas falta opção global consistente e normalização dedicada à **fala** dos nomes. ArcGIS \`shortLabel\` reduz algumas duplicações; não corrige siglas, abreviações e soletração de TTS.
- **Segundo plano:** \`MainActivity\` trata \`ON_STOP\` chamando \`navigation.onAppHidden()\` e \`viewModel.onAppHidden()\`; este último chama \`stopService\`. \`NavigationViewModel.onAppHidden\` finaliza o percurso. Portanto o comportamento com tela bloqueada não está implementado de fato, apesar da existência de um foreground service.
- **Câmera:** \`OpenStreetMapComponent\` usa \`INITIAL_ZOOM = 17.2\`, mas a prévia de rota força zoom **13.6** e centro no ponto mediano das coordenadas. Ao navegar, conserva o zoom atual, inclusive se era o afastado da prévia. \`animateCamera\` é chamado para sucessivos fixes de GPS; orientação usa sensor + course do GPS sem controlador de câmera/posição na tela específico de navegação.
- **Veículo:** \`ViaVoxConfig.vehicleType\` altera cor/halo do puck; \`NavigationNetworkService.route\` consulta OSRM público com \`route/v1/driving\` para ambos. Não há perfil motorístico distinto nem ícone de forma diferente. NÃO anunciar "rotas para motos" enquanto provider real não implementado.
- **Rotas:** \`NavigationViewModel.monitorNavigation\` usa \`minByOrNull(distanceToManeuver)\` para próxima instrução. Isso não é progresso linear na geometria: alças, rotatórias e retornos podem selecionar instrução errada. Recalculo depende apenas de distância transversal e contagem de fixes; não há testes de campo suficientes.
- **Persistência:** \`ViaVoxHistoryDatabase\` possui tabelas \`favorites\`, \`saved_routes\`, \`trips\`, \`trip_points\` e APIs de consulta/exclusão. \`saveCurrentPoint\` cria nome genérico a partir da rua, não apresenta fluxo de nomeação/confirmar/desfazer; os comandos estão dispersos; a gravação vive no ViewModel e não persiste com tela bloqueada. Há suporte técnico parcial, não uma biblioteca de viagens pronta.
- **Mapas:** MapLibre + sobreposição ArcGIS/OSM. Não substituir o motor antes de estabilizar orientação, câmera, voz e percursos. As informações de órgãos públicos/circunscrições são cartográficas e podem estar desatualizadas.

## 1. Contrato de comportamento da voz

### Eventos e prioridade
Criar modelos explícitos \`SpeechEvent\`, \`SpeechPriority\`, \`SpeechQueueState\` com: \`eventId\`, \`type\`, \`rawText\`, \`normalizedText\`, \`placeKey\`, \`createdAt\`, \`expiresAt\`, \`priority\`, \`dedupeKey\` e \`sourceAccuracy\`.

- Quando houver entrada *simultânea* em zona, bairro e rua: pronunciar **Zona → Bairro → Rua** nessa ordem, preferencialmente em UMA única frase para evitar que a rua fique minutos atrasada.
  Ex.: "Entrando na Zona Centro-Sul. Bairro Petrópolis. Rua Benjamin Constant."
- Se só o bairro mudar: "Entrando no bairro Petrópolis."
- Se só a rua mudar: "Rua Benjamin Constant." Assim que confirmada a transição GPS/cartográfica — sem aguardar o cooldown de bairro ou POI.
- Se só a zona mudar: "Entrando na Zona Centro-Sul."
- Na primeira fixação confiável: anunciar o contexto inicial, sem repetir em cada fix.
- Não pronunciar zona/bairro sem confirmação cartográfica. Se limites de zona forem incertos, conservar estado anterior, não inventar transição.
- Com navegação ativa, uma instrução de conversão prestes a vencer é crítica e pode **adiar** evento de POI; não interromper áudio em andamento arbitrariamente. Em borda zona/bairro/rua, coalescer eventos numa sequência curta. POI é sempre prioridade mais baixa e pode expirar sem ser narrado.
- Respeitar exatamente a opção \`narratePoiEnabled\` e cada categoria de POI. Desligado significa zero buscas remotas específicas de narração e zero anúncios (camada visual do mapa pode permanecer ligada separadamente).
- Antirrepetição por evento + chave da região. Não anunciar a mesma rua a cada fix nem a mesma zona/bairro dentro da mesma permanência. Reentrada real deve permitir novo anúncio.
- Sinal GPS ruim, cruzamento e ruas paralelas exigem confirmação e histerese (não trocar por leituras isoladas).

### Motor de fala único
Criar \`SpeechOrchestrator\` no serviço, com fila **serial** (um utterance em execução no máximo), exclusão mútua entre Android TTS/Francisca e status de confirmação:
1. \`enqueue\` apenas adiciona/mescla/cancela evento obsoleto; nunca executa TTS diretamente.
2. Revalidar evento no momento da reprodução; descartar POI já ultrapassado e manobra obsoleta.
3. Iniciar a próxima fala **somente após** \`onDone\`, \`onError\` ou timeout controlado; usar IDs de utterance e estado. Não usar \`QUEUE_FLUSH\` em fala normal; reservar interrupção explícita apenas para cancelar/encerrar ou emergência justificada.
4. Implementar áudio focus/ducking com \`AudioManager\`, tratamento de chamadas, Bluetooth e telas bloqueadas; conferir políticas de áudio do Android.
5. No provider Francisca, conclusão deve ser ligada ao término **real do áudio reproduzido**, não só ao retorno da requisição de voz.
6. Não reportar "anunciado" quando só entrou na fila; distinguir \`detectado\`, \`aguardando\`, \`falando\`, \`concluído\` e \`falhou\`.

### Pronúncia real
Criar \`SpeechTextNormalizer\` + testes em pt-BR, separados do nome exibido no mapa. Mapeamentos controlados, com exceções configuráveis:
- \`24ª CICOM\` → "vigésima quarta CICOM" ou variante fonética validada, pronunciando "CICOM" como palavra, não letras isoladas.
- \`UPA\`, \`DIP\`, \`PMAM\` → pronúncia aprovada em áudio e leitura natural de siglas; alguns acrônimos legitimamente devem ser soletrados.
- \`BR-174\`, \`AM-010\` → leitura compreensível, sem dígitos ou hifens confusos.
- Remover duplicações do tipo "COMANDO GERALCMDOGERAL" antes de gerar fala.
- Não modificar textos originais na base GIS; só o \`spokenText\`.

**Aceite Voz (obrigatório):** em replay de 30–50 eventos artificiais e trajeto real:
- ZERO sobreposição de duas falas; ZERO fala interrompida por outra fala comum;
- toda transição validada de zona e bairro gera no máximo um anúncio até haver reentrada;
- rua validada isoladamente começa assim que a voz atual permitir, sem esperar POI;
- simultaneidade produz a ordem Zona→Bairro→Rua;
- seletor global e categorias desligados silenciam POIs; nenhuma regressão de rua/bairro;
- pelo menos 20 nomes/categorias reais avaliados com áudio Android offline e, se configurada, Francisca.

## 2. Sessão real em segundo plano e gravação persistente

- Criar \`TrackingSessionManager\` e \`TripRecorder\` sob \`ViaVoxTrackingService\` (ou service dedicado se houver razão justificada). Um único fluxo de localização alimenta texto, navegação, gravação e POIs. UI é observadora, não proprietária da sessão.
- Permitir dois modos claros: \`Somente com o aplicativo aberto\` (economia de bateria) e \`Continuar durante navegação/gravação\` (explícito e consentido).
- Criar foreground service **enquanto o app está visível**, notificação persistente com destino/rua e ações \`Pausar\`, \`Retomar\`, \`Encerrar\`. Ao bloquear tela, o serviço iniciado deve continuar quando permitido; \`ON_STOP\` deve pausar apenas renderização, não derrubar sessão em andamento. Respeitar Android 12–17, permissões e restrições de foreground location. Não solicitar \`ACCESS_BACKGROUND_LOCATION\` automaticamente; avaliar apenas se o produto precisar iniciar GPS a partir de background e a política justificar.
- Restaurar estado da sessão na UI ao reabrir (destino, rota atual, progresso, gravação), sem iniciar outra sessão duplicada.
- Persistir no SQLite transições/estado suficiente para recuperar percurso já coletado em caso de fechamento do processo; tratar retomada de uma gravação interrompida explicitamente, sem prometer continuidade durante morte do processo.
- Compartilhar GPS/câmera da forma correta: com tela bloqueada não renderizar mapa; combinar accuracy/velocidade e atualização adaptativa para bateria.
- Se o usuário explicitamente encerrar/pausar, desligar sensores e notificação.
- Para a navegação em segundo plano, manter manobras e voz; se sem conectividade, apresentar limites claros e não simular rerouting.

**Aceite segundo plano:** percorrer trajeto de 30 minutos, bloquear por 10–15 min com sessão ativa, confirmar voz de mudanças e manobras, pontos persistidos, notificação e retomada exata da UI. Repetir usando economia de bateria Samsung e documentar limitações do sistema sem hacks/requests abusivas.

## 3. Motor de navegação, orientação, câmera e perfis

### Progresso de rota real
- Criar \`RouteProgressEngine\`: snap GPS somente quando confiança adequada; projetar a posição na geometria da rota; distância acumulada no polilinha; progressivo por segmento/índice, sem retornar para trás em loops; manobras por \`routeStepIndex\` e distância restante até a manobra AO LONGO DA ROTA, não menor distância euclidiana.
- Rerouting baseado em múltiplas posições + confiança/corredor viário + tempo mínimo, com cooldown; não recalcular por salto isolado de GPS.
- Limiar de anúncios de manobra adaptado à velocidade, sem "0 m, siga em frente" indevido; clareza em retorno, rotatória, saída, chegada. Prioridade de navegação integrada à fila de voz.
- Tratar falhas de servidor, sem rota, perda de GPS, chegada e retorno do segundo plano por estados explícitos.
- Separar \`RouteProvider\` interface de OSRM/Valhalla; OSRM demo só serve como fallback de protótipo para carro, sujeito a uso justo.

### Carro versus motocicleta = efeito real
- Modelar \`VehicleProfile.CAR\` e \`MOTORCYCLE\`, de \`routeRequest\` até orientação, indicador e preferências. Alterar veículo durante rota deve exibir confirmação e recalcular **se houver backend de perfil compatível**.
- Para perfil real moto, avaliar Valhalla \`motorcycle\` (beta) ou backend com restrições viárias locais auditáveis; instalar chave/configuração e testes de rotas por perfil. **Sem provider específico validado, indicar claramente "perfil de carro usado para a rota" e não prometer resultado diferente.**
- Desenhar indicadores realmente distintos (forma silhueta carro/moto e seta de orientação), não apenas halo de cor; atualização instantânea de preferência sem reiniciar o app.
- Precisão do heading: usar course GPS acima da velocidade limiar e fusão de sensores/compasso em baixa velocidade apenas quando confiável. Suavizar ângulo, lidar com rumo 359°→0° e oscilações; não prometer direção exata quando parado e sensor descalibrado.

### Câmera MapLibre específica para navegação
- Estados \`FREE\`, \`OVERVIEW\`, \`FOLLOW_NORTH\`, \`FOLLOW_HEADING\`.
- **Prévia:** usar \`LatLngBounds\` de origem+destino+geometria inteira e padding das barras, não ponto mediano + zoom fixo.
- **Navegando:** zoom configurável, típico 16.5–18 para manobras urbanas; ajustar suavemente pela velocidade e proximidade de curvas. Preservar zoom manual até toque em \`Recentralizar\` ou modo \`Seguir\`.
- Puck posicionado visualmente próximo ao terço inferior da tela, revelando o caminho à frente. Heading-up quando houver direção confiável e opção norte acima. Não colocar pointer "à frente" falsamente sem bearing.
- Animar posição e câmera por interpolação temporal; separar fix de GPS de frame de render; sem \`animateCamera\` concorrentes a cada update.
- Durante navegação reduzir UI a **próxima manobra + distância + ETA + encerrar** em card compacto superior/inferior; rua atual permanece legível, mas só uma faixa, sem dois cards grandes no meio. Botões secundários em menu/ocultos, com toques largos.

**Aceite navegação:** 3 percursos urbanos de Manaus, contendo rotatória, mudança de via e retorno; sem manobra regressiva/0m falsa; recomputar após um desvio real; câmera segue direção sem saltos; alternância moto/car muda ícone comprovadamente e perfil de rota somente quando backend dá suporte. Registrar gravação de tela e coordenadas anonimizadas de diagnóstico.

## 4. Biblioteca real de lugares, rotas e percursos

- Tela/aba \`Biblioteca\` com **Lugares | Rotas | Percursos**, quantidades, busca local, ordenação e estados vazios informativos. Entradas acessíveis com um toque a partir do mapa.
- \`Salvar aqui\` mostra confirmação com posição GPS, nome editável, categoria, localização e ação \`Salvar\`. Ao salvar, botão muda para \`Salvo\` e oferece \`Renomear\`, \`Remover\` e \`Navegar\`; não duplicar silenciosamente pontos iguais.
- Em cada local salvo: visualizar marcador e endereço/fonte, editar nome, excluir com confirmação e opção desfazer (quando aplicável).
- Rotas salvas: mostrar origem, destino, distância, data e perfil; recalcular antes de navegar se muito antiga, sem usar geometria antiga para manobras como se fosse atual.
- Percursos: título, início/fim, duração, distância, número de pontos, miniatura da trilha, renomear, visualizar no mapa, excluir com confirmação e exportar **GPX** (com consentimento e sem upload automático).
- Migração de schema do SQLite deve preservar todos os favoritos/rotas/percursos já existentes; **nunca \`DROP TABLE\` ao atualizar**. Testes de migração de BD e recuperação após encerramento.
- Informar claramente o que é salvo localmente versus sincronizado; **nenhuma nuvem** até pedido explícito do usuário.

**Aceite biblioteca:** salvar 5 locais e 2 rotas, gravar 2 percursos, fechar e reabrir o aplicativo; todos os itens persistem; editar/excluir, exportar GPX, navegar a um favorito; botão "salvar aqui" reflete imediatamente estado salvo.

## 5. Bateria, recursos e observabilidade

- Um único cliente Fused Location Provider; intervalo adaptativo por uso: navegação ativa, gravação, passeio sem rota, parado, tela apagada. Nunca emitir request remoto de geocoding/POI a cada fix.
- Reduzir updates de GeoJSON e recriação de bitmaps e rota nos casos sem mudança real; ao bloquear tela, suspender renderização/compasso se desnecessários, mantendo localização/voz conforme modo autorizado.
- Cache espacial GIS/POI; eliminar consultas redundantes e listeners duplicados de MapLibre.
- Instrumentar métricas locais **sem dados pessoais em logs públicos**: latência GPS→rua confirmada, tempo de fala na fila, estados de TTS, tempo de câmera, off-route, número/intervalo de solicitações de rede, consumo de CPU e bateria.
- Antes/depois: medir 60 minutos na mesma região/dispositivo e condições comparáveis, mesmo brilho e tipo de conexão. Meta inicial: redução de 20–30% no consumo adicional do ViaVox comparado ao baseline da versão 0.7.1; **meta a validar**, não alegação de ganho prévio.
- GPS fraco deve resultar em indicador de incerteza; não manter navegação falsamente "precisa" ou alterar nome da rua por salto.

## 6. Sequência de implementação e gates de entrega

### Lote A — voz e geografia (P0)
**Arquivos previstos:** novos \`service/SpeechOrchestrator.kt\`, \`service/SpeechTextNormalizer.kt\`, \`service/GeographicTransitionEngine.kt\`; refatorar \`TextToSpeechManager.kt\`, \`ViaVoxTrackingService.kt\`, \`RoadDetectionEngine.kt\`, \`PoiAnnouncementCoordinator.kt\`, \`model/ViaVoxConfig.kt\`, \`ui/components/SettingsDialog.kt\`.
- [ ] Eventos de zona, bairro e rua separados com fonte geográfica confiável, histerese e ordem.
- [ ] Fila TTS única Android/Francisca; conclusão/erro/timeout sem atropelar.
- [ ] Normalizador de siglas e POIs; toggle global de narração POI e categorias.
- [ ] Testes de concorrência, repetição, reentrada, ortografia e voz em aparelho.
**Gate A:** todos os testes verdes + zero falas concorrentes em 30–50 eventos.

### Lote B — sessões, segundo plano e banco (P0)
**Arquivos previstos:** \`MainActivity.kt\`, \`ViaVoxViewModel.kt\`, \`ViaVoxTrackingService.kt\`, \`LocationTrackingManager.kt\`, \`NavigationViewModel.kt\`, \`ViaVoxHistoryDatabase.kt\`, \`AndroidManifest.xml\` e novos \`TrackingSessionManager.kt\`, \`TripRecorder.kt\`.
- [ ] Não parar serviço ao receber \`ON_STOP\` com sessão explicitamente ativa.
- [ ] Permissões/notificação e ações do foreground service corretas.
- [ ] Persistência da gravação fora do ViewModel; recuperação da sessão e da interface.
- [ ] Testes de tela bloqueada e restrições Android 14–17.
**Gate B:** 30 min de percurso, >=10 min com tela bloqueada, fala e registro intactos.

### Lote C — navegação verdadeira e câmera (P0/P1)
**Arquivos previstos:** \`NavigationViewModel.kt\`, \`NavigationNetworkService.kt\`, \`OpenStreetMapComponent.kt\`, \`NavigationControls.kt\`; novos \`RouteProgressEngine.kt\`, \`MapCameraController.kt\`, \`VehicleProfile.kt\`, \`RouteProvider.kt\`.
- [ ] Geometria e manobras com progresso linear e testes de rotatórias.
- [ ] Câmera follow-heading, zoom urbano próximo, marcador no terço inferior.
- [ ] Ícones realmente distintos carro/moto; backend moto validado e nunca perfil falso.
- [ ] HUD de navegação compacto, sem painéis empilhados.
**Gate C:** 3 trajetos reais com gravação de evidência, sem manobras fora de sequência.

### Lote D — Biblioteca, qualidade visual e energia (P1)
**Arquivos previstos:** \`ViaVoxHistoryDatabase.kt\`, \`NavigationViewModel.kt\`, \`NavigationControls.kt\`; novas telas \`SavedPlacesScreen\`, \`SavedRoutesScreen\`, \`TripHistoryScreen\`.
- [ ] Biblioteca de salvos/rotas/percursos com gerenciamento integral.
- [ ] Botão salvar/desfazer e edição; GPX; testes de migração.
- [ ] Avaliação de consumo, performance e legibilidade em veículo.
**Gate D:** banco persiste após reinício; função gerenciável de ponta a ponta; bateria comparada com baseline.

## 7. Política de engenharia: proibido placebo

Não concluir item porque foi criado botão, variável ou um mock. Critério universal para marcar pronto:
1. Fluxo de ponta a ponta conecta UI → ViewModel → serviço/repositório/backend → persistência/motor → resposta visual ou áudio.
2. Testes automatizados cobrem sucesso, falha, reentrada, concorrência e limiares reais; CI **APK build + testes + assinatura** aprovado.
3. Evidência em **Samsung Android** com vídeo/Logcat no caso de voz, câmera, segundo plano, consumo de energia e gravação.
4. **Não remover nem substituir dados salvos** em migrações ou em instalação por cima da APK atual.
5. Falha externa (roteamento, TTS Francisca, ArcGIS, Photon) tem fallback honesto; nunca simular áudio/rota/POI/progresso.
6. Documentar limitações verificadas e modo de reprodução para falhas. Fechar item só com critérios de aceite satisfeitos.
7. Preservar o comportamento de rua atual que o usuário já validou, salvo mudança explícita na sequência solicitada; testes de regressão obrigatórios.

**Importante:** nenhum item acima está declarado implementado por este plano. A implementação ocorrerá lote a lote no código, com commits identificáveis, CI e testes de campo.

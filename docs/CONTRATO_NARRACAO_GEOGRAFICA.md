# ViaVox — Contrato de narração geográfica (23/10/2026)

A prioridade é anunciar **a mudança real, não repetir um endereço completo a cada atualização**. O usuário pode alterar o modo na tela inicial.

## Modo 1 — Explorador
**Zona → Bairro → Rua.**

- Entrada em nova zona: "Entrando na Zona Sul."
- Entrada em novo bairro: "Bairro Centro."
- Entrada em nova rua: "Rua 10 de Julho."
- Primeira identificação simultânea: "Entrando na Zona Sul. Bairro Centro. Rua 10 de Julho."
- A mesma rua nunca deve ser repetida apenas por ser longa; não repetir bairro ou zona sem mudança.

## Modo 2 — Territorial
**Zona → CICOM → CPA → Bairro → Rua.**

- CICOM: anunciar na primeira identificação e em cada mudança confirmada de circunscrição.
- CPA: anunciar **uma única vez ao entrar em sua área**, nunca a cada rua/bairro ou troca de CICOM do mesmo CPA.
- A identificação usa a camada GeoJSON de setores de CICOM já incluída em `app/src/main/assets`, quando há GPS de precisão suficiente, com mapeamento conservador CICOM/CPA para Manaus.
- **Fora de cobertura ou sem confirmação, omitir CICOM/CPA; nunca inventar uma unidade.** A base é um cadastro local e não substitui informação operacional atualizada da PMAM.

## Modo 3 — Acessibilidade
**Zona → Bairro → Rua**, seguidos de orientações relevantes do ambiente.

- Consulta **Ao meu redor (360°)** busca referências em um raio configurável (100, 200, 350 ou 500 metros).
- Consulta **À minha frente** exige orientação confiável da bússola ou rumo de deslocamento.
- Interseções mapeadas, continuação de via, pontos de referência e posição esquerda/direita são ditos somente com dados adequados.
- **Tour** é opção adicional, desligada por padrão; narra pontos culturais, históricos e estabelecimentos relevantes com controle de repetição.
- Não afirma que uma travessia é segura nem que uma conversão é legal a partir da simples geometria do mapa.

## Regra de entrada imediata

A cada nova amostra confiável do GPS, consultar **polígonos locais de bairro e CICOM** sem esperar a geocodificação online da rua. Em pontos próximos a limites, não anunciar antes de haver precisão suficiente para atribuir a área. Se não há um polígono correspondente, usar resposta do serviço de endereços, que depende de rede e da qualidade do dado.

O nome da rua depende da validação da posição na via e pode chegar alguns segundos depois da zona/bairro; manter esses anúncios na fila na ordem em que forem confirmados.

## Testes de aceite

- Entrar em bairro de outra zona sem mudar de rua: anunciar somente a nova zona e o bairro.
- Entrar em outra rua no mesmo bairro: anunciar somente a rua.
- Trocar da 24ª para a 3ª CICOM dentro do CPA Sul: anunciar a nova CICOM, **sem repetir CPA Sul**.
- Sair do CPA Sul para outra área e voltar: anunciar CPA Sul novamente na reentrada.
- Trocar de zona/bairro antes de identificar a rua: anunciar território imediatamente após confirmação GPS; anunciar a rua depois, sem perder a primeira fala.
- Percorrer uma avenida longa: sem repetições desnecessárias.
- Em Acessibilidade, orientar e anunciar referências apenas quando a qualidade dos dados permitir; Tour continua opcional.
- Testar com tela bloqueada, deslocamentos reais, erros de rede e baixa precisão antes de homologar.

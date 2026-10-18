# ViaVox · Microsoft Francisca Neural API

O aplicativo Android ja suporta a voz `pt-BR-FranciscaNeural` por HTTPS, com fallback para a voz offline do Android.

## API oficial da Microsoft

Documentacao: https://learn.microsoft.com/pt-br/azure/ai-services/speech-service/rest-text-to-speech

Endpoint REST (varia pela regiao do seu recurso Azure): `https://{regiao}.tts.speech.microsoft.com/cognitiveservices/v1`

**Nao configure esse endpoint REST diretamente no app**, pois ele exige credenciais secretas que nao devem estar no APK. Este Cloudflare Worker atua como intermediario.

## Publicar a API no Cloudflare Workers

Crie um recurso Azure AI Speech e obtenha a regiao e a chave no portal https://portal.azure.com/.

No diretorio `voice-worker`, com acesso ao Wrangler e sua conta Cloudflare:

```sh
npm exec wrangler -- login
npm exec wrangler -- secret put AZURE_SPEECH_KEY
npm exec wrangler -- secret put AZURE_SPEECH_REGION
npm exec wrangler -- secret put VIAVOX_CLIENT_TOKEN
npm exec wrangler -- deploy
```

A URL real sera fornecida pelo Cloudflare apos a publicacao. O endereco configurado no app devera ser:

`https://<subdominio-real>.workers.dev/tts`

Na tela Configuracoes do ViaVox, informe a URL real e o **mesmo** token salvo em `VIAVOX_CLIENT_TOKEN` no Cloudflare. Nao coloque a chave Azure no campo de token: use um token proprio, aleatorio e forte.

A rota publica `GET /health` retorna apenas status. `POST /tts` exige header `Authorization: Bearer <seu-token>` e JSON:

```json
{"text":"Voce esta na Rua Aristides Rocha.","voice":"pt-BR-FranciscaNeural"}
```

A resposta e `audio/mpeg`. O token de cliente fica nas preferencias do aplicativo, nao no codigo-fonte; para distribuicao comercial, substitua o token compartilhado por autenticacao individual com limites de uso.

Opcional para a versao web: configure `ALLOWED_ORIGIN` com a origem exata do seu Cloudflare Pages (ex.: `https://viavox.pages.dev`). Essa regra nao e necessaria no Android nativo.

**Importante:** sem implantar o Worker, configurar as variaveis e o recurso Azure, a voz Francisca nao estara ativa; a voz Android permanece operacional. A Microsoft pode cobrar conforme o consumo/plano do Azure. Esta API exige conectividade e deve receber protecoes de limite de uso antes de ser publicada amplamente.

# ViaVox

Sua localização ganha voz. MVP experimental React + TypeScript + Capacitor.

## Desenvolvimento

```bash
npm install
npm run dev
npm run build
```

A API de geolocalização do navegador requer HTTPS fora de localhost. Autorize a localização e ative a narração. O mapa inicia visualmente em Manaus, mas só informa uma rua após obter uma localização real.

## Cloudflare Pages
Build: `npm run build`. Diretório de saída: `dist`.

## Android
```bash
npm install
npm run build
npx cap add android
npx cap sync android
npx cap open android
```

O funcionamento contínuo com tela bloqueada **não está implementado**: exige serviço Android nativo de localização em primeiro plano e permissões apropriadas.

## Limitações atuais
- Nominatim público está sujeito a políticas de uso e limites de taxa; esta implementação é demonstrativa e não adequada a produção sem backend/cache ou provedor apropriado.
- Os pontos de referência não são garantidos nem pesquisados como POIs próximos; nome do resultado reverso pode estar ausente.
- GPS e síntese de voz web podem ser interrompidos pelo sistema quando a tela é bloqueada.
- Validar build, permissões, privacidade e condições de uso antes de lançar.

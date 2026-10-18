/**
 * ViaVox Francisca broker for Cloudflare Workers.
 * All Azure credentials stay in Cloudflare secrets, never in the APK or GitHub.
 * Set AZURE_SPEECH_KEY, AZURE_SPEECH_REGION and VIAVOX_CLIENT_TOKEN via wrangler secret.
 */
const encoder = new TextEncoder();
const MAX_CHARS = 250;

function xmlEscape(value) {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

async function matchesSecret(actual, expected) {
  const a = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(actual)));
  const b = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(expected)));
  let different = 0;
  for (let i = 0; i < a.length; i++) different |= a[i] ^ b[i];
  return different === 0;
}

function reply(body, status = 200, origin = "") {
  const headers = { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" };
  if (origin) {
    headers["Access-Control-Allow-Origin"] = origin;
    headers["Access-Control-Allow-Headers"] = "authorization, content-type";
    headers["Access-Control-Allow-Methods"] = "POST, OPTIONS";
    headers["Vary"] = "Origin";
  }
  return new Response(JSON.stringify(body), { status, headers });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const allowedOrigin = env.ALLOWED_ORIGIN || "";
    const suppliedOrigin = request.headers.get("Origin") || "";
    const corsOrigin = allowedOrigin && suppliedOrigin === allowedOrigin ? allowedOrigin : "";

    if (request.method === "OPTIONS") {
      if (!corsOrigin) return reply({ error: "Origin not allowed" }, 403);
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": corsOrigin,
          "Access-Control-Allow-Methods": "POST, OPTIONS",
          "Access-Control-Allow-Headers": "authorization, content-type",
          "Access-Control-Max-Age": "600",
          "Vary": "Origin"
        }
      });
    }
    if (url.pathname === "/health" && request.method === "GET") {
      return reply({ status: "ok", voice: "pt-BR-FranciscaNeural" });
    }
    if (url.pathname !== "/tts") return reply({ error: "Not found" }, 404);
    if (request.method !== "POST") return reply({ error: "Method not allowed" }, 405);

    if (!env.AZURE_SPEECH_KEY || !env.AZURE_SPEECH_REGION || !env.VIAVOX_CLIENT_TOKEN) {
      return reply({ error: "Voice broker is not configured" }, 503, corsOrigin);
    }

    const bearer = request.headers.get("Authorization") || "";
    const token = bearer.startsWith("Bearer ") ? bearer.slice(7) : "";
    if (!token || !(await matchesSecret(token, env.VIAVOX_CLIENT_TOKEN))) {
      return reply({ error: "Unauthorized" }, 401, corsOrigin);
    }

    if (!/^application\/json/i.test(request.headers.get("Content-Type") || "")) {
      return reply({ error: "Content-Type must be application/json" }, 415, corsOrigin);
    }
    if (Number(request.headers.get("Content-Length") || 0) > 3000) {
      return reply({ error: "Request too large" }, 413, corsOrigin);
    }

    let payload;
    try {
      payload = await request.json();
    } catch {
      return reply({ error: "Invalid JSON" }, 400, corsOrigin);
    }
    const text = typeof payload?.text === "string" ? payload.text.trim() : "";
    if (!text || text.length > MAX_CHARS) {
      return reply({ error: "Text length must be 1–250 characters" }, 400, corsOrigin);
    }

    const region = String(env.AZURE_SPEECH_REGION).trim().toLowerCase();
    if (!/^[a-z0-9-]+$/.test(region)) return reply({ error: "Invalid Azure region" }, 503, corsOrigin);
    const speechUrl = `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;
    const ssml = `<speak version="1.0" xml:lang="pt-BR" xmlns="http://www.w3.org/2001/10/synthesis"><voice name="pt-BR-FranciscaNeural">${xmlEscape(text)}</voice></speak>`;

    try {
      const upstream = await fetch(speechUrl, {
        method: "POST",
        headers: {
          "Ocp-Apim-Subscription-Key": env.AZURE_SPEECH_KEY,
          "Content-Type": "application/ssml+xml; charset=utf-8",
          "X-Microsoft-OutputFormat": "audio-16khz-64kbitrate-mono-mp3",
          "User-Agent": "ViaVox/1.0"
        },
        body: ssml
      });
      if (!upstream.ok) {
        return reply({ error: "Voice temporarily unavailable" }, 502, corsOrigin);
      }
      const headers = {
        "Content-Type": "audio/mpeg",
        "Cache-Control": "private, no-store",
        "X-Content-Type-Options": "nosniff"
      };
      if (corsOrigin) {
        headers["Access-Control-Allow-Origin"] = corsOrigin;
        headers["Vary"] = "Origin";
      }
      return new Response(upstream.body, { status: 200, headers });
    } catch {
      return reply({ error: "Voice connection failed" }, 502, corsOrigin);
    }
  }
};

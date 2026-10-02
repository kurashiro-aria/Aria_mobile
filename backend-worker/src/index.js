const MAX_BODY_BYTES = 256 * 1024;
const MAX_MESSAGE_CHARS = 256 * 1024;
const MAX_VOICE_TEXT_CHARS = 1200;
const REQUEST_TTL_MS = 10 * 60 * 1000;
const RATE_WINDOW_MS = 60 * 1000;

// These maps are intentionally best-effort. Durable Objects/KV can replace them
// later when the gateway needs globally consistent limits across isolates.
const recentRequests = new Map();
const rateBuckets = new Map();

const VOICE_IDS = new Set(["Leda", "Achernar", "Vindemiatrix", "Sulafat", "Aoede"]);
const VOICE_EMOTIONS = new Set(["neutral", "happy", "amused", "thinking", "surprised", "confused", "annoyed", "angry", "embarrassed", "sad", "affectionate", "playful", "serious", "tired", "excited"]);
const VOICE_STYLES = new Set(["natural", "playful", "flirty", "teasing", "sarcastic_light", "affectionate", "shy", "serious", "focused", "excited", "comforting", "soft", "whisper"]);

function json(status, payload, extra = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", ...extra },
  });
}

function constantTimeEqual(left, right) {
  if (typeof left !== "string" || typeof right !== "string") return false;
  let mismatch = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i += 1) {
    mismatch |= (left.charCodeAt(i) || 0) ^ (right.charCodeAt(i) || 0);
  }
  return mismatch === 0;
}

function clientAddress(request) {
  return request.headers.get("CF-Connecting-IP") || "unknown";
}

function allowedByRateLimit(request, env, now) {
  const limit = Math.max(1, Number(env.ARIA_RATE_LIMIT_PER_MINUTE || 30));
  const key = clientAddress(request);
  const current = (rateBuckets.get(key) || []).filter((stamp) => now - stamp < RATE_WINDOW_MS);
  if (current.length >= limit) {
    rateBuckets.set(key, current);
    return false;
  }
  current.push(now);
  rateBuckets.set(key, current);
  return true;
}

function cleanup(now) {
  for (const [id, stamp] of recentRequests) {
    if (now - stamp > REQUEST_TTL_MS) recentRequests.delete(id);
  }
}

async function readJson(request) {
  const declaredLength = request.headers.get("content-length");
  const length = declaredLength === null ? 0 : Number(declaredLength);
  if (!Number.isFinite(length) || length < 0 || length > MAX_BODY_BYTES) throw new Error("invalid_body");
  const raw = await request.text();
  if (new TextEncoder().encode(raw).byteLength > MAX_BODY_BYTES) throw new Error("invalid_body");
  try { return JSON.parse(raw); } catch { throw new Error("invalid_json"); }
}

function validatePayload(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "invalid_json";
  if (typeof body.requestId !== "string" || !body.requestId.trim() || body.requestId.length > 100) return "request_id_required";
  if (typeof body.message !== "string" || !body.message.trim() || body.message.length > MAX_MESSAGE_CHARS) return "message_required";
  const generation = body.generation === undefined ? {} : body.generation;
  const maxTokens = generation.maxOutputTokens === undefined ? 384 : generation.maxOutputTokens;
  if (!generation || typeof generation !== "object" || Array.isArray(generation) || !Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > 4096) return "invalid_generation";
  return null;
}

function validateVoicePayload(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "invalid_json";
  if (typeof body.requestId !== "string" || !body.requestId.trim() || body.requestId.length > 100) return "request_id_required";
  if (typeof body.text !== "string" || !body.text.trim() || body.text.length > MAX_VOICE_TEXT_CHARS) return "text_required";
  if (!VOICE_EMOTIONS.has(body.emotion)) return "invalid_emotion";
  if (!VOICE_STYLES.has(body.expressionStyle)) return "invalid_expression_style";
  if (typeof body.intensity !== "number" || !Number.isFinite(body.intensity) || body.intensity < 0 || body.intensity > 1) return "invalid_intensity";
  if (!VOICE_IDS.has(body.voiceId)) return "invalid_voice";
  return null;
}

function voiceStyle(emotion, intensity, expressionStyle) {
  const strength = intensity >= 0.7 ? "clearly" : intensity >= 0.4 ? "moderately" : "subtly";
  const delivery = {
    happy: "bright and cheerful", amused: "lightly amused and warm", playful: "playful with light mischief",
    affectionate: "warm, close and gentle", sad: "quiet and slower, without exaggerated drama",
    surprised: "lively with a slight quickening", embarrassed: "soft and slightly shy",
    excited: "energetic and enthusiastic", serious: "calm, clear and serious", angry: "firm but controlled",
    annoyed: "mildly annoyed but controlled", tired: "soft and unhurried", confused: "thoughtful and uncertain",
    thinking: "thoughtful with natural pauses", neutral: "natural, soft and conversational",
  }[emotion] || "natural, soft and conversational";
  const expression = {
    playful: "playful", flirty: "close and playfully charming, never exaggerated", teasing: "gently teasing",
    sarcastic_light: "with very light irony", affectionate: "warm and affectionate", shy: "soft and shy",
    serious: "serious and measured", focused: "focused and clear", excited: "lively and excited",
    comforting: "reassuring and gentle", soft: "especially soft", whisper: "whispered softly", natural: "natural",
  }[expressionStyle] || "natural";
  return `${strength} ${delivery}; ${expression}; natural pacing and volume`;
}

async function callGemini(message, maxTokens, env, fetchImpl) {
  const model = env.ARIA_LLM_MODEL || "gemini-2.5-flash";
  const base = (env.ARIA_GEMINI_BASE_URL || "https://generativelanguage.googleapis.com/v1beta").replace(/\/$/, "");
  if (!env.ARIA_LLM_API_KEY) throw Object.assign(new Error("provider_unavailable"), { status: 503 });
  const url = `${base}/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(env.ARIA_LLM_API_KEY)}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(1000, Number(env.ARIA_LLM_TIMEOUT_MS || 90000)));
  try {
    const response = await fetchImpl(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: message }] }], generationConfig: { maxOutputTokens: maxTokens } }),
      signal: controller.signal,
    });
    if (!response.ok) {
      const providerError = response.status === 429
        ? "provider_rate_limited"
        : response.status === 401 || response.status === 403
          ? "provider_unauthorized"
          : response.status === 404
            ? "provider_model_not_found"
            : response.status === 400
              ? "provider_bad_request"
              : "provider_request_failed";
      throw Object.assign(new Error(providerError), { status: response.status === 429 ? 429 : 502 });
    }
    let payload;
    try { payload = await response.json(); } catch { throw Object.assign(new Error("provider_invalid_response"), { status: 502 }); }
    const text = payload?.candidates?.[0]?.content?.parts?.map((part) => part?.text || "").join("").trim();
    if (!text) throw Object.assign(new Error("provider_empty_response"), { status: 502 });
    return { text, model };
  } catch (error) {
    if (error?.name === "AbortError") throw Object.assign(new Error("provider_timeout"), { status: 504 });
    throw error;
  } finally { clearTimeout(timer); }
}

function decodeBase64(value) {
  if (typeof value !== "string" || !value) throw Object.assign(new Error("provider_empty_audio"), { status: 502 });
  let raw;
  try { raw = atob(value); } catch { throw Object.assign(new Error("provider_invalid_audio"), { status: 502 }); }
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) bytes[i] = raw.charCodeAt(i);
  if (bytes.length < 44) throw Object.assign(new Error("provider_empty_audio"), { status: 502 });
  return bytes;
}

function pcmToWav(pcm) {
  const header = new ArrayBuffer(44);
  const view = new DataView(header);
  const write = (offset, value) => { for (let i = 0; i < value.length; i += 1) view.setUint8(offset + i, value.charCodeAt(i)); };
  write(0, 'RIFF'); view.setUint32(4, 36 + pcm.length, true); write(8, 'WAVE');
  write(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, 24000, true); view.setUint32(28, 48000, true);
  view.setUint16(32, 2, true); view.setUint16(34, 16, true); write(36, 'data'); view.setUint32(40, pcm.length, true);
  const audio = new Uint8Array(44 + pcm.length); audio.set(new Uint8Array(header)); audio.set(pcm, 44); return audio;
}

async function readStreamingAudio(response) {
  if (!response.body?.getReader) throw Object.assign(new Error('provider_invalid_audio'), { status: 502 });
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''; const chunks = [];
  const consume = (text) => {
    buffer += text; const events = buffer.split(/\n\n/); buffer = events.pop() || '';
    for (const event of events) {
      const line = event.split('\n').find((item) => item.startsWith('data:')); if (!line) continue;
      const raw = line.slice(5).trim(); if (!raw || raw === '[DONE]') continue;
      let payload; try { payload = JSON.parse(raw); } catch { continue; }
      const delta = payload?.delta || payload?.data?.delta;
      if (payload?.event_type === 'error' || payload?.data?.event_type === 'error') throw Object.assign(new Error('provider_request_failed'), { status: 502 });
      if (delta?.type === 'audio' && typeof delta.data === 'string') {
        let binary; try { binary = atob(delta.data); } catch { throw Object.assign(new Error('provider_invalid_audio'), { status: 502 }); }
        const bytes = new Uint8Array(binary.length); for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i); chunks.push(bytes);
      }
    }
  };
  while (true) { const part = await reader.read(); if (part.done) break; consume(decoder.decode(part.value, { stream: true })); }
  consume(decoder.decode()); const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  if (total === 0) throw Object.assign(new Error('provider_empty_audio'), { status: 502 });
  const pcm = new Uint8Array(total); let offset = 0; for (const chunk of chunks) { pcm.set(chunk, offset); offset += chunk.length; }
  return pcmToWav(pcm);
}

async function callGeminiTts(body, env, fetchImpl) {
  const model = env.ARIA_TTS_MODEL || 'gemini-3.8-flash-lite-tts';
  const base = (env.ARIA_GEMINI_BASE_URL || 'https://generativelanguage.googleapis.com/v1beta').replace(/\/$/, '');
  if (!env.ARIA_LLM_API_KEY) throw Object.assign(new Error('provider_unavailable'), { status: 503 });
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(1000, Number(env.ARIA_TTS_TIMEOUT_MS || 90000)));
  try {
    const response = await fetchImpl(`${base}/interactions`, {
      method: 'POST', headers: { 'content-type': 'application/json', accept: 'text/event-stream', 'x-goog-api-key': env.ARIA_LLM_API_KEY },
      body: JSON.stringify({ model, input: [{ type: 'user_input', content: [{ type: 'text', text: body.text, annotations: [{ type: 'speech_metadata', style: voiceStyle(body.emotion, body.intensity, body.expressionStyle) }] }] }], response_format: { type: 'audio' }, generation_config: { speech_config: [{ voice: body.voiceId }] }, stream: true }), signal: controller.signal,
    });
    if (!response.ok) { const providerError = response.status === 429 ? 'provider_rate_limited' : response.status === 401 || response.status === 403 ? 'provider_unauthorized' : response.status === 404 ? 'provider_model_not_found' : response.status === 400 ? 'provider_bad_request' : 'provider_request_failed'; throw Object.assign(new Error(providerError), { status: response.status === 429 ? 429 : 502 }); }
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('text/event-stream')) return { audio: await readStreamingAudio(response), model };
    let payload; try { payload = await response.json(); } catch { throw Object.assign(new Error('provider_invalid_response'), { status: 502 }); }
    const audio = payload?.output_audio?.data || payload?.steps?.flatMap((step) => step?.type === 'model_output' ? step.content || [] : []).filter((part) => part?.type === 'audio').at(-1)?.data;
    return { audio: decodeBase64(audio), model };
  } catch (error) { if (error?.name === 'AbortError') throw Object.assign(new Error('provider_timeout'), { status: 504 }); throw error; } finally { clearTimeout(timer); }
}
export function createWorker({ fetchImpl = fetch, now = () => Date.now() } = {}) {
  return { fetch: (request, env = {}) => handle(request, env, fetchImpl, now) };
}

async function handle(request, env, fetchImpl, now) {
  const started = now();
  const url = new URL(request.url);
  if (url.pathname === "/health" || url.pathname === "/v1/health") {
    return json(env.ARIA_LLM_API_KEY ? 200 : 503, { status: env.ARIA_LLM_API_KEY ? "ok" : "unavailable", protocol: 1, provider: env.ARIA_LLM_PROVIDER || "gemini", voice: { available: Boolean(env.ARIA_LLM_API_KEY), model: env.ARIA_TTS_MODEL || "gemini-3.8-flash-lite-tts" } });
  }
  const isVoice = url.pathname === "/v1/voice/synthesize";
  if (!isVoice && url.pathname !== "/v1/brain/respond" && url.pathname !== "/v1/chat") return json(404, { error: "not_found" });
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" }, { allow: "POST" });
  if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") return json(415, { error: "content_type_required" });
  if (env.ARIA_CLOUD_CLIENT_TOKEN && !constantTimeEqual(request.headers.get("X-ARIA-Client"), env.ARIA_CLOUD_CLIENT_TOKEN)) return json(401, { error: "unauthorized" });
  const current = now();
  cleanup(current);
  if (!allowedByRateLimit(request, env, current)) return json(429, { error: "rate_limited" });
  if (request.headers.get("X-ARIA-Protocol") !== "1") return json(400, { error: "unsupported_protocol" });
  let body;
  try { body = await readJson(request); } catch (error) { return json(400, { error: error.message }); }
  const validationError = isVoice ? validateVoicePayload(body) : validatePayload(body);
  if (validationError) return json(400, { error: validationError });
  if (recentRequests.has(body.requestId)) return json(409, { error: "duplicate_request", requestId: body.requestId });
  recentRequests.set(body.requestId, current);
  try {
    if (isVoice) {
      const providerStarted = now();
      const result = await callGeminiTts(body, env, fetchImpl);
      const providerMs = Math.max(0, now() - providerStarted);
      return new Response(result.audio, { status: 200, headers: {
        "content-type": "audio/wav", "cache-control": "private, no-store",
        "X-ARIA-Request-Id": body.requestId, "X-ARIA-Voice-Id": body.voiceId,
        "X-ARIA-Model": result.model, "X-ARIA-Gateway-Ms": String(Math.max(0, now() - started)),
        "X-ARIA-Provider-Ms": String(providerMs),
      } });
    }
    const result = await callGemini(body.message, body.generation?.maxOutputTokens || 384, env, fetchImpl);
    return json(200, { requestId: body.requestId, reply: result.text, model: result.model, provider: "gemini", finishReason: "stop", gatewayProcessingMs: Math.max(0, now() - started) });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    return json(status, { error: ["provider_rate_limited", "provider_timeout", "provider_request_failed", "provider_unauthorized", "provider_model_not_found", "provider_bad_request", "provider_invalid_response", "provider_empty_response", "provider_empty_audio", "provider_invalid_audio", "provider_unavailable"].includes(error?.message) ? error.message : "internal_error", requestId: body.requestId });
  }
}

export default createWorker();

const MAX_BODY_BYTES = 256 * 1024;
const MAX_MESSAGE_CHARS = 256 * 1024;
const REQUEST_TTL_MS = 10 * 60 * 1000;
const RATE_WINDOW_MS = 60 * 1000;

// These maps are intentionally best-effort. Durable Objects/KV can replace them
// later when the gateway needs globally consistent limits across isolates.
const recentRequests = new Map();
const rateBuckets = new Map();

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

export function createWorker({ fetchImpl = fetch, now = () => Date.now() } = {}) {
  return { fetch: (request, env = {}) => handle(request, env, fetchImpl, now) };
}

async function handle(request, env, fetchImpl, now) {
  const started = now();
  const url = new URL(request.url);
  if (url.pathname === "/health" || url.pathname === "/v1/health") {
    return json(env.ARIA_LLM_API_KEY ? 200 : 503, { status: env.ARIA_LLM_API_KEY ? "ok" : "unavailable", protocol: 1, provider: env.ARIA_LLM_PROVIDER || "gemini" });
  }
  if (url.pathname !== "/v1/brain/respond" && url.pathname !== "/v1/chat") return json(404, { error: "not_found" });
  if (request.method !== "POST") return json(405, { error: "method_not_allowed" }, { allow: "POST" });
  if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") return json(415, { error: "content_type_required" });
  if (env.ARIA_CLOUD_CLIENT_TOKEN && !constantTimeEqual(request.headers.get("X-ARIA-Client"), env.ARIA_CLOUD_CLIENT_TOKEN)) return json(401, { error: "unauthorized" });
  const current = now();
  cleanup(current);
  if (!allowedByRateLimit(request, env, current)) return json(429, { error: "rate_limited" });
  if (request.headers.get("X-ARIA-Protocol") !== "1") return json(400, { error: "unsupported_protocol" });
  let body;
  try { body = await readJson(request); } catch (error) { return json(400, { error: error.message }); }
  const validationError = validatePayload(body);
  if (validationError) return json(400, { error: validationError });
  if (recentRequests.has(body.requestId)) return json(409, { error: "duplicate_request", requestId: body.requestId });
  recentRequests.set(body.requestId, current);
  try {
    const result = await callGemini(body.message, body.generation?.maxOutputTokens || 384, env, fetchImpl);
    return json(200, { requestId: body.requestId, reply: result.text, model: result.model, provider: "gemini", finishReason: "stop", gatewayProcessingMs: Math.max(0, now() - started) });
  } catch (error) {
    const status = Number.isInteger(error?.status) ? error.status : 500;
    return json(status, { error: ["provider_rate_limited", "provider_timeout", "provider_request_failed", "provider_unauthorized", "provider_model_not_found", "provider_bad_request", "provider_invalid_response", "provider_empty_response", "provider_unavailable"].includes(error?.message) ? error.message : "internal_error", requestId: body.requestId });
  }
}

export default createWorker();

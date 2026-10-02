import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../src/index.js";

const env = { ARIA_LLM_API_KEY: "synthetic-key", ARIA_CLOUD_CLIENT_TOKEN: "client-token", ARIA_LLM_MODEL: "gemini-2.5-flash" };
const headers = { "content-type": "application/json", "X-ARIA-Protocol": "1", "X-ARIA-Client": "client-token" };
const request = (body, extra = {}) => new Request("https://gateway.test/v1/brain/respond", { method: "POST", headers: { ...headers, ...extra }, body: JSON.stringify(body) });

test("health does not expose secrets and reports voice capability", async () => {
  const response = await createWorker().fetch(new Request("https://gateway.test/health"), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok", protocol: 1, provider: "gemini", voice: { available: true, model: "gemini-3.8-flash-lite-tts" } });
});

const voiceRequest = (body, extra = {}) => new Request("https://gateway.test/v1/voice/synthesize", { method: "POST", headers: { ...headers, ...extra }, body: JSON.stringify(body) });
const voiceBody = (overrides = {}) => ({ requestId: "voice-1", text: "Hola Kura.", emotion: "happy", intensity: 0.65, expressionStyle: "playful", voiceId: "Leda", ...overrides });
const wavBase64 = btoa("RIFF" + "x".repeat(64));

test("voice request uses exact transcript, validated direction and selected candidate", async () => {
  const fetchImpl = async (url, options) => {
    assert.match(url, /\/v1beta\/interactions$/);
    assert.equal(options.headers["x-goog-api-key"], "synthetic-key");
    const payload = JSON.parse(options.body);
    assert.equal(payload.model, "gemini-3.8-flash-lite-tts");
    assert.equal(payload.input[0].content[0].text, "Hola Kura.");
    assert.match(payload.input[0].content[0].annotations[0].style, /playful/);
    assert.equal(payload.generation_config.speech_config[0].voice, "Leda");
    return new Response(JSON.stringify({ steps: [{ type: "model_output", content: [{ type: "audio", data: wavBase64 }] }] }), { status: 200 });
  };
  const response = await createWorker({ fetchImpl }).fetch(voiceRequest(voiceBody()), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "audio/wav");
  assert.equal(response.headers.get("X-ARIA-Request-Id"), "voice-1");
  assert.ok((await response.arrayBuffer()).byteLength > 44);
});

test("voice rejects auth, invalid fields, unknown voices and oversized text", async () => {
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody(), { "X-ARIA-Client": "wrong" }), env)).status, 401);
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody({ emotion: "invented" })), env)).status, 400);
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody({ intensity: 2 })), env)).status, 400);
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody({ expressionStyle: "anime" })), env)).status, 400);
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody({ voiceId: "Unknown" })), env)).status, 400);
  assert.equal((await createWorker().fetch(voiceRequest(voiceBody({ text: "x".repeat(1201) })), env)).status, 400);
});

test("voice maps provider timeout, failure and empty audio without leaking secrets", async () => {
  const failed = await createWorker({ fetchImpl: async () => new Response("", { status: 500 }) })
    .fetch(voiceRequest(voiceBody({ requestId: "voice-fail" })), env);
  assert.equal(failed.status, 502);
  assert.equal((await failed.json()).error, "provider_request_failed");
  const empty = await createWorker({ fetchImpl: async () => new Response(JSON.stringify({ steps: [] }), { status: 200 }) })
    .fetch(voiceRequest(voiceBody({ requestId: "voice-empty" })), env);
  assert.equal(empty.status, 502);
  assert.equal((await empty.json()).error, "provider_empty_audio");
});

test("voice provider timeout is mapped safely", async () => {
  const timeoutEnv = { ...env, ARIA_TTS_TIMEOUT_MS: "1000" };
  const fetchImpl = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
  });
  const response = await createWorker({ fetchImpl })
    .fetch(voiceRequest(voiceBody({ requestId: "voice-timeout" })), timeoutEnv);
  assert.equal(response.status, 504);
  assert.equal((await response.json()).error, "provider_timeout");
});

test("valid request returns the same requestId and Gemini response", async () => {
  const fetchImpl = async (url, options) => {
    assert.match(url, /models\/gemini-2\.5-flash:generateContent/);
    assert.equal(JSON.parse(options.body).contents[0].parts[0].text, "ARIA_CLOUD_OK");
    return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "ARIA_CLOUD_OK" }] } }] }), { status: 200 });
  };
  const response = await createWorker({ fetchImpl }).fetch(request({ requestId: "synthetic-1", message: "ARIA_CLOUD_OK", generation: { maxOutputTokens: 10 } }), env);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).requestId, "synthetic-1");
});

test("rejects missing or incorrect token and oversized payload", async () => {
  assert.equal((await createWorker().fetch(request({ requestId: "bad-1", message: "x" }, { "X-ARIA-Client": "wrong" }), env)).status, 401);
  assert.equal((await createWorker().fetch(request({ message: "x" }), env)).status, 400);
  const oversized = new Request("https://gateway.test/v1/brain/respond", { method: "POST", headers, body: JSON.stringify({ requestId: "big", message: "x" }) });
  Object.defineProperty(oversized, "text", { value: async () => "x".repeat(256 * 1024 + 1) });
  assert.equal((await createWorker().fetch(oversized, env)).status, 400);
});

test("maps Gemini errors and rejects duplicates", async () => {
  const fetchImpl = async () => new Response("", { status: 429 });
  const worker = createWorker({ fetchImpl });
  assert.equal((await worker.fetch(request({ requestId: "dup-1", message: "x" }), env)).status, 429);
  assert.equal((await worker.fetch(request({ requestId: "dup-1", message: "x" }), env)).status, 409);
});

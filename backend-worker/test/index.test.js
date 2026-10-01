import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../src/index.js";

const env = { ARIA_LLM_API_KEY: "synthetic-key", ARIA_CLOUD_CLIENT_TOKEN: "client-token", ARIA_LLM_MODEL: "gemini-2.5-flash" };
const headers = { "content-type": "application/json", "X-ARIA-Protocol": "1", "X-ARIA-Client": "client-token" };
const request = (body, extra = {}) => new Request("https://gateway.test/v1/brain/respond", { method: "POST", headers: { ...headers, ...extra }, body: JSON.stringify(body) });

test("health does not expose secrets", async () => {
  const response = await createWorker().fetch(new Request("https://gateway.test/health"), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "ok", protocol: 1, provider: "gemini" });
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

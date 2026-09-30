#!/usr/bin/env python3
"""ARIA Stage-3 deterministic development backend. No LLM and no secrets."""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.getenv("ARIA_BACKEND_HOST", "0.0.0.0")
PORT = int(os.getenv("ARIA_BACKEND_PORT", "8787"))
MAX_BODY = 256 * 1024

class Handler(BaseHTTPRequestHandler):
    server_version = "AriaDevBackend/1"

    def log_message(self, fmt, *args):
        # Do not log request bodies/prompts.
        print("%s - %s" % (self.address_string(), fmt % args))

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/v1/health":
            return self.send_json(200, {"status": "ok", "protocol": 1})
        self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/brain/respond":
            return self.send_json(404, {"error": "not_found"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY:
                return self.send_json(400, {"error": "invalid_body"})
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            request_id = request.get("requestId")
            message = request.get("message")
            generation = request.get("generation", {})
            if not isinstance(request_id, str) or not request_id.strip():
                return self.send_json(400, {"error": "request_id_required"})
            if not isinstance(message, str) or not message.strip():
                return self.send_json(400, {"error": "message_required"})
            max_tokens = generation.get("maxOutputTokens", 384)
            if not isinstance(max_tokens, int) or max_tokens < 1 or max_tokens > 4096:
                return self.send_json(400, {"error": "invalid_generation"})
            started = time.monotonic()
            # Deterministic transport proof only. Stage 4 will replace this backend responder.
            reply = "Recibí tu solicitud mediante el backend ARIA de prueba. La conexión Cloud está funcionando."
            processing_ms = int((time.monotonic() - started) * 1000)
            self.send_json(200, {
                "requestId": request_id,
                "reply": reply,
                "model": "aria-stage3-deterministic",
                "finishReason": "stop",
                "serverProcessingMs": processing_ms
            })
        except (json.JSONDecodeError, UnicodeDecodeError):
            self.send_json(400, {"error": "invalid_json"})
        except Exception:
            self.send_json(500, {"error": "internal_error"})

if __name__ == "__main__":
    print(f"ARIA development backend listening on {HOST}:{PORT}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()

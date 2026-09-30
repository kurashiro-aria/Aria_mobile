# ARIA Stage 3 development backend

This is a deterministic transport test server. It does **not** call an LLM and contains no secrets.

Run with Python 3:

```bash
python backend/server.py
```

Configuration is external: `ARIA_BACKEND_HOST` (default `0.0.0.0`) and `ARIA_BACKEND_PORT` (default `8787`). See `.env.example`. The server intentionally uses plain HTTP because it is development-only; production ARIA endpoints must be HTTPS. Do not expose this server directly to the public Internet.

Endpoints:

- `GET /v1/health` -> backend availability.
- `POST /v1/brain/respond` -> validates protocol v1 request and returns a deterministic response preserving `requestId`.

Example request body:

```json
{"requestId":"uuid","message":"prepared ARIA prompt","generation":{"maxOutputTokens":384}}
```

No prompt/body is intentionally written to application logs. There is no authentication in this development backend; production authentication belongs at the ARIA backend boundary, not as a provider API key embedded in the APK.

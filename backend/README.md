# ARIA backend

The default provider is deterministic `mock`, so local tests need no credentials. Set `ARIA_LLM_PROVIDER=openai-compatible` to use the configured OpenAI-compatible endpoint from the backend process. The provider key never belongs in the APK.

Run with Python 3:

```bash
python backend/server.py
```

Configuration is external; see `.env.example`. Put TLS termination in a reverse proxy or managed HTTPS service for deployment. The development server intentionally uses plain HTTP and must not be exposed directly to the public Internet.

Endpoints:

- `GET /v1/health` -> backend availability.
- `POST /v1/brain/respond` -> validates protocol v1 request and returns a deterministic response preserving `requestId`.

Example request body:

```json
{"requestId":"uuid","message":"prepared ARIA prompt","generation":{"maxOutputTokens":384}}
```

The Android client sends `X-ARIA-Protocol: 1`. Configure the APK endpoint with Gradle property `ARIA_CLOUD_ENDPOINT`; leave it empty to use the development Mock brain.

No prompt/body is intentionally written to application logs. There is no authentication in this development backend; production authentication belongs at the ARIA backend boundary, not as a provider API key embedded in the APK.

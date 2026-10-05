# Pocket TTS in ARIA Mobile

ARIA 0.2.51 integrates Pocket TTS as an optional, fully local voice engine. The
chat pipeline remains authoritative and independent: a TTS failure never removes
or delays the visible Cloud/Gemini response.

## Pinned runtime and model

- Kyutai Pocket TTS architecture and official voices: upstream Pocket TTS,
  source code under MIT and model/official voice derivatives under CC BY 4.0.
- Android integration foundation: PocketTTS Android Engine v0.5.2, commit
  `6750c9fb410ca7fe71dfc8ce0eb655efb20aedb2`, MIT.
- Native inference: PocketTTS.cpp snapshot `e801e7d6c2692121a39e80ae525cb5265174a495`, MIT.
- ONNX Runtime Android 1.20.0, ARM64, MIT.
- SentencePiece 0.2.1, Apache-2.0.
- dr_libs commit `50bb723e6a459dbb781e26cefee4fd9ca6714d6a`, public domain or MIT-0.
- Spanish FP32 pack: `PocketTTS-spanish-FP32.zip`, 207,487,086 bytes,
  SHA-256 `f83dc41bd0d5c7634d385fdadef496881d398ca99d1886ff25d90cb59e384412`.
- Verified extracted payload: 439,054,031 bytes (about 418.7 MiB).
- Included official Spanish voice: Lola.

The model pack is not part of the APK. ARIA downloads it atomically to
`files/voice/pocket`, verifies the complete archive, safely extracts it and
only then marks it installed. An update reuses this private directory.

## Runtime flow

`visible reply -> text cleaner -> PocketVoiceEngine -> JNI -> PocketTTS.cpp ->
streaming float PCM -> PCM16 -> AudioTrack`

There is one reusable native engine. A new request cancels the previous
request, and leaving the Activity stops synthesis/audio. Android system TTS is
kept only as an explicit or pre-audio emergency fallback. It is never allowed
to start after Pocket has produced its first PCM block.

## Voice conditioning

Pocket supports real reference-WAV conditioning. Imported WAV files are copied
to ARIA's private Pocket voice directory with profile metadata including model,
sample rate, source fingerprint and creation time. PocketTTS.cpp persists both
the Mimi voice embedding (`.emb`) and voice-conditioned transformer state
(`.kv`) beneath `voices/.cache`, so a reference does not need full processing
for every reply.

## Prosody and diagnostics

ARIA routes the resolved `Emotion` and `ExpressionStyle` into the voice layer.
The pinned Pocket runtime documents sampling temperature but not safe,
per-utterance pitch, speed or emotion controls. Version 0.2.51 therefore keeps
those semantic values for diagnostics and future profiles while using only the
model pack's real temperature. It does not fake emotion using pitch shifts.

Available diagnostics include model load, first PCM/audio, generation, total,
audio duration, RTF and process PSS before/after synthesis. Voice-profile work
inside the current asynchronous native stream is included in time to first
audio and is shown that way rather than reported as a fabricated standalone
number.

## Known limits

- The Android integration is community-maintained and must be performance-
  tested on the Xiaomi 17T.
- Only `arm64-v8a` is built.
- The initial downloadable pack is FP32 and may use substantial RAM.
- Model and official voice licensing is separate from ARIA's APK code license.
- Advanced expressive acting remains future work; quality and latency take
  priority in 0.2.51.

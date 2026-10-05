# Pocket audio pipeline — ARIA 0.2.52

## Audited format

The pinned `PocketTTS.cpp` decoder (`e801e7d`) declares `SR = 24000` and its
stream callback exposes `const float*` plus a sample count. The C API copies
`count * sizeof(float)` bytes and JNI creates a Java `FloatArray(count)`.
Therefore the native contract is 24,000 Hz, mono, float32 PCM. Values are
expected near `[-1, +1]`.

ARIA converts each native float sample once to signed little-endian PCM16,
clamping only values outside `[-1, +1]` and replacing non-finite samples with
silence. AudioTrack is configured for 24,000 Hz, mono, `ENCODING_PCM_16BIT`.

## Confirmed playback defect in 0.2.51

The 0.2.51 path called `AudioTrack.play()` before writing the first PCM block.
Pocket's first streaming block is intentionally small, so playback began with
an empty track and had no bounded preroll. At synthesis completion ARIA also
called `stop()`, `flush()`, and `release()` immediately, without waiting for
the playback head to consume written frames. Those two ordering defects can
produce an initial underrun, discontinuities, or truncated queued audio even
when Pocket and JNI produced valid PCM.

0.2.52 writes a one-second bounded preroll before `play()`, handles partial
writes until the entire block is accepted, preserves chunk order, and waits
for the playback head before releasing AudioTrack. Its two-second AudioTrack
capacity leaves room for the next decoder chunk. The code records Android's
underrun counter.

## Diagnostic WAV

`Voz -> Diagnóstico Pocket -> GENERAR PRUEBA DE AUDIO` runs one inference for:

> Hola Kura, soy Aria. Esta es una prueba de calidad de audio.

The exact PCM16 bytes sent to AudioTrack are also written atomically to
`cacheDir/pocket-diagnostics/pocket_audio_quality.wav`. No second synthesis or
post-processing is used. The diagnostics panel reports sample count, duration,
min/max, absolute peak, RMS, DC offset, NaN, infinity, clipping, and AudioTrack
underruns.

Interpretation on the physical device:

- clean WAV plus distorted speaker playback: Android playback path;
- distorted WAV and speaker playback: Pocket/native/JNI/PCM before AudioTrack;
- both clean but robotic timbre: transport is fixed and voice quality is a
  separate follow-up.

## Pocket parameters retained

- Spanish FP32 model pack
- temperature: `0.7`
- LSD decode steps: `1` (upstream default)
- threads: `2` from the installed manifest
- sample rate: 24 kHz

No model, voice, temperature, decode-step, emotion, or conditioning tuning is
part of 0.2.52.

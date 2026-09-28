#!/usr/bin/env python3
"""Isolated, offline Qwen3-TTS listening probe. Not imported by ARIA/Android.

Use only locally downloaded official checkpoints and an authorized reference WAV.
The 0.6B models do not have documented instruction-based style control.
"""

import argparse
import json
import os
from pathlib import Path
import time

PHRASE = "Hola, Kura. Estoy aquí contigo. Cuéntame cómo te fue hoy."
STYLES = {
    "neutral": None,
    "feliz": "Habla en español con alegría ligera y natural; conserva tu timbre.",
    "triste": "Habla en español con tristeza contenida y un ritmo algo más lento; conserva tu timbre.",
    "carinosa": "Habla en español con calidez y cercanía, sin exagerar; conserva tu timbre.",
    "juguetona": "Habla en español de forma juguetona y ligeramente pícara; conserva tu timbre.",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Ruta local del checkpoint oficial")
    parser.add_argument("--output", type=Path, required=True, help="Directorio de salida fuera del repositorio")
    parser.add_argument("--mode", choices=("custom-06", "base-06", "custom-17"), required=True)
    parser.add_argument("--reference", type=Path, help="WAV autorizado para 0.6B Base")
    parser.add_argument("--reference-text", help="Transcripción literal de la referencia")
    parser.add_argument("--speaker", default="Serena", help="Mismo preset en todas las muestras")
    args = parser.parse_args()
    if not args.model.is_dir() or not (args.model / "config.json").is_file():
        parser.error("Falta el modelo local con config.json; no se descarga nada automáticamente")
    expected = {"custom-06": "0.6B-CustomVoice", "base-06": "0.6B-Base",
                "custom-17": "1.7B-CustomVoice"}[args.mode]
    if expected not in args.model.name:
        parser.error(f"El directorio debe corresponder a {expected}; evita ejecutar la tarea equivocada")
    if args.mode == "base-06" and (not args.reference or not args.reference.is_file()
                                    or not args.reference_text):
        parser.error("base-06 requiere --reference WAV autorizada y --reference-text literal")
    args.output.mkdir(parents=True, exist_ok=True)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    import torch  # noqa: E402 - lazy: preflight works without dependencies
    import soundfile as sf  # noqa: E402
    from qwen_tts import Qwen3TTSModel  # noqa: E402

    # CPU is the reproducible default; desktop GPU benchmarks must be recorded separately.
    started = time.monotonic()
    model = Qwen3TTSModel.from_pretrained(str(args.model), device_map="cpu", dtype=torch.float32)
    load_seconds = time.monotonic() - started
    records = []
    # 0.6B: neutral only. Generating "emotional" filenames without a supported control
    # would misleadingly claim a capability that the official model table does not show.
    for style in (STYLES if args.mode == "custom-17" else ("neutral",)):
        began = time.monotonic()
        if args.mode == "base-06":
            wavs, sr = model.generate_voice_clone(
                text=PHRASE, language="Spanish", ref_audio=str(args.reference),
                ref_text=args.reference_text)
        else:
            kwargs = dict(text=PHRASE, language="Spanish", speaker=args.speaker)
            if STYLES[style] is not None:
                kwargs["instruct"] = STYLES[style]
            wavs, sr = model.generate_custom_voice(**kwargs)
        filename = f"{args.mode}_{style}.wav"
        sf.write(args.output / filename, wavs[0], sr)
        records.append(dict(file=filename, style=style, phrase=PHRASE,
                            instruction=STYLES[style] if args.mode == "custom-17" else None,
                            sample_rate=sr, audio_seconds=len(wavs[0]) / sr,
                            synthesis_seconds=round(time.monotonic() - began, 3)))
    (args.output / f"{args.mode}_manifest.json").write_text(
        json.dumps(dict(model=str(args.model), mode=args.mode, speaker=args.speaker if args.mode != "base-06" else None,
                        load_seconds=round(load_seconds, 3), hardware="desktop CPU, not Android",
                        whisper="no verificado; muestra omitida", samples=records),
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(records)} muestra(s) y manifiesto en {args.output}")


if __name__ == "__main__":
    main()

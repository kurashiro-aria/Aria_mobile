#!/usr/bin/env python3
"""Generate one Spanish ARIA voice sample locally with OpenVoice V2.

This is an opt-in desktop experiment, not an Android dependency. The reference
recording and generated audio stay outside the repository. Use only audio that
you have permission to use as a reference voice.
"""

import argparse
from pathlib import Path
import tempfile


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True, help="Authorized speaker audio")
    parser.add_argument("--text", required=True, help="Spanish phrase to generate")
    parser.add_argument("--output", type=Path, required=True, help="Output WAV path")
    parser.add_argument("--checkpoints", type=Path, default=Path("checkpoints_v2"))
    parser.add_argument("--speed", type=float, default=1.0)
    return parser.parse_args()


def main() -> None:
    args = arguments()
    if not args.reference.is_file():
        raise SystemExit(f"No encuentro el audio de referencia: {args.reference}")
    if not args.text.strip():
        raise SystemExit("El texto de prueba está vacío")
    if not 0.7 <= args.speed <= 1.3:
        raise SystemExit("--speed debe estar entre 0.7 y 1.3")
    converter_dir = args.checkpoints / "converter"
    for required in (converter_dir / "config.json", converter_dir / "checkpoint.pth"):
        if not required.is_file():
            raise SystemExit(f"Falta el checkpoint de OpenVoice V2: {required}")

    # Import heavy dependencies only after validating the input and checkpoints.
    import torch
    from melo.api import TTS
    from openvoice import se_extractor
    from openvoice.api import ToneColorConverter

    device = "cuda:0" if torch.cuda.is_available() else "cpu"
    converter = ToneColorConverter(str(converter_dir / "config.json"), device=device)
    converter.load_ckpt(str(converter_dir / "checkpoint.pth"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="aria-voice-") as temporary:
        target_se, _ = se_extractor.get_se(
            str(args.reference), converter, target_dir=temporary, vad=True
        )
        model = TTS(language="ES", device=device)
        speaker_ids = model.hps.data.spk2id
        if not speaker_ids:
            raise SystemExit("MeloTTS no entregó una voz base para español")
        speaker_key, speaker_id = next(iter(speaker_ids.items()))
        embedding_name = speaker_key.lower().replace("_", "-") + ".pth"
        embedding = args.checkpoints / "base_speakers" / "ses" / embedding_name
        if not embedding.is_file():
            raise SystemExit(f"Falta la voz base de OpenVoice V2: {embedding}")
        source_se = torch.load(str(embedding), map_location=device, weights_only=True)
        base_audio = Path(temporary) / "base.wav"
        model.tts_to_file(args.text, speaker_id, str(base_audio), speed=args.speed)
        converter.convert(
            audio_src_path=str(base_audio),
            src_se=source_se,
            tgt_se=target_se,
            output_path=str(args.output),
            message="@MyShell",
        )
    print(f"Audio de prueba guardado en {args.output}")


if __name__ == "__main__":
    main()

import asyncio
import io
import wave
import os
import numpy as np
from piper import PiperVoice
from config import settings

_model_dir = os.path.join(os.path.dirname(__file__), "..", "models", "piper")
_model_path = os.path.join(_model_dir, settings.TTS_VOICE)
_voice = PiperVoice.load(_model_path)


def _synthesize_sync(text: str) -> bytes:
    all_audio = []
    for chunk in _voice.synthesize(text):
        # Convert float32 [-1, 1] to int16 PCM
        pcm = (chunk.audio_float_array * 32767).astype(np.int16)
        all_audio.append(pcm.tobytes())

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(_voice.config.sample_rate)
        wav.writeframes(b"".join(all_audio))
    return buf.getvalue()


async def synthesize(text: str) -> bytes:
    return await asyncio.to_thread(_synthesize_sync, text)

import io
import asyncio
import tempfile
import os
from faster_whisper import WhisperModel
from config import settings

model = WhisperModel(settings.WHISPER_MODEL, compute_type="int8")


def _transcribe_sync(audio_bytes: bytes) -> str:
    # faster-whisper needs a file path, write to temp file
    with tempfile.NamedTemporaryFile(suffix=".webm", delete=False) as f:
        f.write(audio_bytes)
        tmp_path = f.name

    try:
        segments, _ = model.transcribe(tmp_path, language="en")
        return " ".join(seg.text.strip() for seg in segments)
    finally:
        os.unlink(tmp_path)


async def transcribe(audio_bytes: bytes) -> str:
    # Run in thread pool to avoid blocking the event loop
    return await asyncio.to_thread(_transcribe_sync, audio_bytes)

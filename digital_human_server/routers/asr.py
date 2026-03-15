from fastapi import APIRouter, UploadFile, File
from models.schemas import ASRResponse
from services import asr_service

router = APIRouter()


@router.post("/asr", response_model=ASRResponse)
async def asr(audio: UploadFile = File(...)):
    audio_bytes = await audio.read()
    text = await asr_service.transcribe(audio_bytes)
    return ASRResponse(text=text)

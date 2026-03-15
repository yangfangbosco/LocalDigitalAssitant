from fastapi import APIRouter
from fastapi.responses import Response
from models.schemas import TTSRequest
from services import tts_service

router = APIRouter()


@router.post("/tts")
async def tts(req: TTSRequest):
    audio_bytes = await tts_service.synthesize(req.text)
    return Response(content=audio_bytes, media_type="audio/wav")

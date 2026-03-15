import json
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from models.schemas import ChatRequest
from services import llm_service

router = APIRouter()


@router.post("/chat")
async def chat(req: ChatRequest):
    async def event_stream():
        async for content in llm_service.stream_chat(req.message):
            data = json.dumps({"content": content})
            yield f"data: {data}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )

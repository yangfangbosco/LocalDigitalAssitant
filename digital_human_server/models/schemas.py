from pydantic import BaseModel


class ChatRequest(BaseModel):
    message: str


class TTSRequest(BaseModel):
    text: str


class ASRResponse(BaseModel):
    text: str

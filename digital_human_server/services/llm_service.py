from typing import AsyncGenerator
from openai import AsyncOpenAI
from config import settings

client = AsyncOpenAI(api_key=settings.OPENAI_API_KEY, base_url=settings.OPENAI_BASE_URL)

conversation_history: list[dict] = []


async def stream_chat(message: str) -> AsyncGenerator[str, None]:
    conversation_history.append({"role": "user", "content": message})

    messages = [{"role": "system", "content": settings.SYSTEM_PROMPT}] + conversation_history

    response = await client.chat.completions.create(
        model=settings.LLM_MODEL,
        messages=messages,
        stream=True,
    )

    full_response = ""
    async for chunk in response:
        delta = chunk.choices[0].delta
        if delta.content:
            full_response += delta.content
            yield delta.content

    conversation_history.append({"role": "assistant", "content": full_response})


def clear_history():
    conversation_history.clear()

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    OPENAI_API_KEY: str = ""
    OPENAI_BASE_URL: str = "https://api.openai.com/v1"
    LLM_MODEL: str = "gpt-4o"
    WHISPER_MODEL: str = "base.en"
    SYSTEM_PROMPT: str = "You are a helpful digital human assistant."
    TTS_VOICE: str = "en-US-AriaNeural"
    CORS_ORIGINS: list[str] = ["*"]

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()

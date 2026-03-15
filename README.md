# Local Digital Human Assistant

A fully local, privacy-first digital human assistant that runs entirely on your machine. No cloud APIs required.

The system features a life-size vertical screen interface with a digital human avatar that listens, thinks, and speaks back to you — all powered by local AI models.

---

**本地数字人助手** — 一个完全在本地运行的数字人交互系统，无需任何云端 API。系统包含一个竖屏数字人界面，支持语音输入、语音活动检测（VAD）、大模型流式回复和语音合成播放。

---

## Architecture / 架构

```
┌──────────────────────────────┐
│    Frontend (H5 Page)        │
│  - Video playback (listen/   │
│    talking states)           │
│  - Mic recording + VAD       │
│  - Streaming text display    │
│  - TTS audio playback        │
└──────────┬───────────────────┘
           │ HTTP API
┌──────────▼───────────────────┐
│    Backend (FastAPI)         │
│  - /api/asr  (faster-whisper)│
│  - /api/chat (Ollama LLM)   │
│  - /api/tts  (Piper TTS)    │
└──────────────────────────────┘
```

| Module | Engine | Model | Size |
|--------|--------|-------|------|
| ASR | faster-whisper | base.en | ~74MB |
| LLM | Ollama | Qwen2.5 3B | ~1.9GB |
| TTS | Piper | en_US-amy-medium | ~60MB |

## Requirements / 环境要求

- macOS (Apple Silicon recommended / 推荐 Apple Silicon)
- Python 3.10+
- Homebrew
- ~4GB free disk space (for models / 模型需要约 4GB 磁盘空间)

## Quick Start / 快速开始

### 1. Clone the repo / 克隆仓库

```bash
git clone https://github.com/yangfangbosco/LocalDigitalAssitant.git
cd LocalDigitalAssitant
```

### 2. Install Ollama & pull LLM model / 安装 Ollama 并下载大模型

```bash
brew install ollama
brew services start ollama

# Wait a few seconds for Ollama to start, then pull the model
# 等待几秒让 Ollama 启动，然后拉取模型
ollama pull qwen2.5:3b
```

### 3. Set up Python environment / 配置 Python 环境

```bash
cd digital_human_server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 4. Download ASR model / 下载语音识别模型

The ASR model (faster-whisper base.en) will be downloaded automatically on first run.

语音识别模型（faster-whisper base.en）会在首次运行时自动下载。

### 5. Download TTS model / 下载语音合成模型

```bash
mkdir -p models/piper

# Download Piper voice model / 下载 Piper 语音模型
curl -L -O --output-dir models/piper \
  "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx"

curl -L -O --output-dir models/piper \
  "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json"
```

### 6. Configure environment / 配置环境变量

```bash
cp .env.example .env
# Edit .env if you want to change models or settings
# 如需修改模型或配置，编辑 .env 文件
```

### 7. Start the server / 启动服务

```bash
source venv/bin/activate
python main.py
```

Open **http://localhost:8000** in your browser.

浏览器打开 **http://localhost:8000** 即可使用。

## Usage / 使用方法

1. The digital human avatar plays in a loop (listening state)
2. Click the **microphone button** (right side of screen) to start recording
3. Speak in English — the system will auto-detect when you stop (VAD, ~1.5s silence)
4. Or click the **send button** to manually send
5. Your speech is transcribed and displayed, then sent to the LLM
6. The LLM response streams in while TTS audio plays back
7. The avatar switches to "talking" video during playback
8. After all audio finishes, the avatar returns to listening state

---

1. 数字人视频循环播放（聆听状态）
2. 点击屏幕右侧的**麦克风按钮**开始录音
3. 用英语说话 — 系统会自动检测停顿（VAD，约 1.5 秒静默）
4. 也可以点击**发送按钮**手动发送
5. 语音转写结果显示后发送给大模型
6. 大模型流式回复的同时播放语音合成音频
7. 播放期间数字人切换为"说话"视频
8. 所有音频播完后恢复聆听状态

## Debug Mode / 调试模式

Click the **DBG** button (bottom-right corner) to open a text input panel for testing without a microphone.

点击右下角的 **DBG** 按钮可打开文字输入面板，无需麦克风即可测试。

## Configuration / 配置说明

Edit `digital_human_server/.env` to customize:

| Variable | Description | Default |
|----------|-------------|---------|
| `OPENAI_API_KEY` | API key (use `ollama` for local) | `ollama` |
| `OPENAI_BASE_URL` | LLM API endpoint | `http://localhost:11434/v1` |
| `LLM_MODEL` | LLM model name | `qwen2.5:3b` |
| `WHISPER_MODEL` | ASR model name | `base.en` |
| `SYSTEM_PROMPT` | System prompt for LLM | See .env.example |
| `TTS_VOICE` | Piper voice model filename | `en_US-amy-medium.onnx` |

## Using Cloud LLM Instead / 使用云端大模型

To use OpenAI or other compatible APIs instead of local Ollama:

如需使用 OpenAI 或其他兼容 API 替代本地 Ollama：

```env
OPENAI_API_KEY=sk-your-key-here
OPENAI_BASE_URL=https://api.openai.com/v1
LLM_MODEL=gpt-4o
```

## Project Structure / 项目结构

```
LocalDigitalAssitant/
├── digital_human_h5/          # Frontend / 前端
│   ├── index.html             # Main page / 主页面
│   ├── listen.mp4             # Listening state video / 聆听状态视频
│   └── taking.mp4             # Talking state video / 说话状态视频
├── digital_human_server/      # Backend / 后端
│   ├── main.py                # FastAPI entry point / 入口
│   ├── config.py              # Configuration / 配置
│   ├── requirements.txt       # Dependencies / 依赖
│   ├── .env.example           # Environment template / 环境变量模板
│   ├── models/                # Data models & TTS models / 数据模型和TTS模型
│   ├── routers/               # API routes / API 路由
│   │   ├── asr.py             # POST /api/asr
│   │   ├── chat.py            # POST /api/chat (SSE)
│   │   └── tts.py             # POST /api/tts
│   └── services/              # Business logic / 业务逻辑
│       ├── asr_service.py     # faster-whisper
│       ├── llm_service.py     # OpenAI-compatible LLM
│       └── tts_service.py     # Piper TTS
└── digital_human_management/  # Management platform (WIP) / 管理平台（开发中）
```

## License

MIT

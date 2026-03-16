#!/bin/bash
# Fetch native C++ dependencies for Android build

set -e
CPP_DIR="app/src/main/cpp"
cd "$(dirname "$0")"

echo "=== Fetching whisper.cpp ==="
if [ ! -d "$CPP_DIR/whisper.cpp" ]; then
  git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git "$CPP_DIR/whisper.cpp"
else
  echo "  Already exists, skipping"
fi

echo ""
echo "=== Fetching llama.cpp ==="
if [ ! -d "$CPP_DIR/llama.cpp" ]; then
  git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$CPP_DIR/llama.cpp"
else
  echo "  Already exists, skipping"
fi

echo ""
echo "=== Installing Android NDK ==="
if [ -z "$ANDROID_HOME" ]; then
  ANDROID_HOME="$HOME/Library/Android/sdk"
fi

if [ ! -d "$ANDROID_HOME/ndk" ] || [ -z "$(ls -A $ANDROID_HOME/ndk 2>/dev/null)" ]; then
  echo "Installing NDK via sdkmanager..."
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;26.1.10909125" "cmake;3.22.1" 2>/dev/null || \
  echo "  Please install NDK 26+ and CMake 3.22+ via Android Studio SDK Manager"
else
  echo "  NDK already installed"
fi

echo ""
echo "=== Done ==="
echo "Next steps:"
echo "1. Download models and place in app/src/main/assets/:"
echo "   - whisper-base.en.bin (from https://huggingface.co/ggerganov/whisper.cpp)"
echo "   - qwen2.5-3b-q4.gguf (from https://huggingface.co/Qwen, convert with llama.cpp)"
echo "   - piper-amy-medium.onnx + .json (from https://huggingface.co/rhasspy/piper-voices)"
echo "2. Build: ./gradlew assembleDebug"
echo "3. Install: adb install app/build/outputs/apk/debug/app-debug.apk"

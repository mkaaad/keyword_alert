#!/bin/bash
# Download SenseVoiceSmall int8 model + tokens.txt
set -euo pipefail

cd "$(dirname "$0")"
MODEL_DIR="assets/models"
mkdir -p "$MODEL_DIR"

MODEL_FILE="$MODEL_DIR/model.int8.onnx"
TOKENS_FILE="$MODEL_DIR/tokens.txt"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"

if [ -f "$MODEL_FILE" ] && [ -s "$MODEL_FILE" ] && [ -f "$TOKENS_FILE" ] && [ -s "$TOKENS_FILE" ]; then
    echo "Model already exists: $(du -h "$MODEL_FILE" | cut -f1)"
    echo "Tokens already exists: $(du -h "$TOKENS_FILE" | cut -f1)"
    exit 0
fi

echo "Downloading SenseVoiceSmall model..."
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

if command -v wget >/dev/null 2>&1; then
    wget -q --show-progress -O "$TMPDIR/model.tar.bz2" "$MODEL_URL"
else
    curl -L --progress-bar -o "$TMPDIR/model.tar.bz2" "$MODEL_URL"
fi

tar -xjf "$TMPDIR/model.tar.bz2" -C "$TMPDIR"

INT8_MODEL=$(find "$TMPDIR" -type f -name '*int8*.onnx' | head -1)
TOKENS=$(find "$TMPDIR" -type f -name 'tokens.txt' | head -1)

[ -n "$INT8_MODEL" ] || { echo "int8 model not found in archive"; exit 1; }
[ -n "$TOKENS" ] || { echo "tokens.txt not found in archive"; exit 1; }

cp "$INT8_MODEL" "$MODEL_FILE"
cp "$TOKENS" "$TOKENS_FILE"

# Keep a placeholder so empty-dir packaging still works if someone deletes models
touch "$MODEL_DIR/.gitkeep"

echo "Model saved:  $(du -h "$MODEL_FILE" | cut -f1)"
echo "Tokens saved: $(du -h "$TOKENS_FILE" | cut -f1)"

#!/bin/bash
# Download SenseVoiceSmall int8 model
set -e

MODEL_DIR="assets/models"
mkdir -p "$MODEL_DIR"

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
MODEL_FILE="$MODEL_DIR/model.int8.onnx"

if [ -f "$MODEL_FILE" ]; then
    echo "Model already exists: $(du -h "$MODEL_FILE" | cut -f1)"
    exit 0
fi

echo "Downloading SenseVoiceSmall model..."
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

wget -q --show-progress -O "$TMPDIR/model.tar.bz2" "$MODEL_URL" || { echo "Download failed"; exit 1; }
tar -xjf "$TMPDIR/model.tar.bz2" -C "$TMPDIR"
INT8_MODEL=$(find "$TMPDIR" -name "*int8*" -name "*.onnx" | head -1)
[ -z "$INT8_MODEL" ] && { echo "int8 model not found"; exit 1; }
cp "$INT8_MODEL" "$MODEL_FILE"
echo "Model saved: $(du -h "$MODEL_FILE" | cut -f1)"

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/shared/src/main/assets"
mkdir -p "$ASSETS"

curl -L --fail --retry 3   "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx"   -o "$ASSETS/silero_vad.onnx"

curl -L --fail --retry 3   "https://tfhub.dev/google/lite-model/yamnet/tflite/1?lite-format=tflite"   -o "$ASSETS/yamnet.tflite"

curl -L --fail --retry 3   "https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv"   -o "$ASSETS/yamnet_class_map.csv"

echo "Models downloaded to $ASSETS"

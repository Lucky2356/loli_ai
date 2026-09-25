#!/usr/bin/env bash
# Скачивает офлайн-модель русской речи Vosk и кладёт её в assets приложения (app/vosk-assets).
# Модель встраивается в APK, поэтому пользователю ничего скачивать не нужно.
# Лицензия модели: Apache 2.0 (https://alphacephei.com/vosk/models).
set -euo pipefail

MODEL="vosk-model-small-ru-0.22"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/app/vosk-cache"
TARGET="$ROOT/app/vosk-assets/vosk/model-ru"

if [ -f "$TARGET/am/final.mdl" ]; then
  echo "Модель уже на месте: $TARGET"
  exit 0
fi

mkdir -p "$CACHE"
ZIP="$CACHE/$MODEL.zip"
if [ ! -s "$ZIP" ]; then
  echo "Скачиваю $MODEL…"
  curl -fL --retry 5 --retry-delay 5 --connect-timeout 30 -o "$ZIP.part" "https://alphacephei.com/vosk/models/$MODEL.zip"
  mv "$ZIP.part" "$ZIP"
fi

TMP="$(mktemp -d)"
unzip -q "$ZIP" -d "$TMP"
rm -rf "$TARGET"
mkdir -p "$(dirname "$TARGET")"
mv "$TMP/$MODEL" "$TARGET"
rm -rf "$TMP"
test -f "$TARGET/am/final.mdl"
echo "Модель установлена в $TARGET ($(du -sh "$TARGET" | cut -f1))"

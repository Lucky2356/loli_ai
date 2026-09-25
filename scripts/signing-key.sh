#!/usr/bin/env bash
# Готовит постоянный ключ подписи APK для CI.
#  1. Если задан секрет LOLI_KEYSTORE_BASE64 — используется он (с паролями из секретов).
#  2. Иначе — ключ из кэша Actions (.signing), созданный при первой сборке.
#  3. Если кэша нет — создаётся новый ключ со случайным паролем; workflow сохранит его в кэш.
# Пароли маскируются в логах и передаются дальше только через GITHUB_ENV.
set -euo pipefail
DIR=".signing"
mkdir -p "$DIR"

if [ -n "${LOLI_KEYSTORE_BASE64:-}" ]; then
  echo "$LOLI_KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/loli.jks"
  echo "::add-mask::${LOLI_KEYSTORE_PASSWORD_SECRET}"
  {
    echo "LOLI_KEYSTORE_FILE=$RUNNER_TEMP/loli.jks"
    echo "LOLI_KEYSTORE_PASSWORD=${LOLI_KEYSTORE_PASSWORD_SECRET}"
    echo "LOLI_KEY_ALIAS=${LOLI_KEY_ALIAS_SECRET}"
    echo "LOLI_KEY_PASSWORD=${LOLI_KEY_PASSWORD_SECRET:-$LOLI_KEYSTORE_PASSWORD_SECRET}"
  } >> "$GITHUB_ENV"
  echo "Подпись ключом из секретов репозитория"
  exit 0
fi

if [ ! -f "$DIR/loli.jks" ] || [ ! -f "$DIR/password" ]; then
  echo "Создаю постоянный ключ подписи (хранится в кэше Actions)"
  PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
  keytool -genkeypair -keystore "$DIR/loli.jks" -storetype PKCS12 -alias loli -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Loli Assistant, O=Loli" >/dev/null 2>&1
  printf '%s' "$PASS" > "$DIR/password"
else
  echo "Ключ подписи восстановлен из кэша"
fi

PASS="$(cat "$DIR/password")"
echo "::add-mask::$PASS"
{
  echo "LOLI_KEYSTORE_FILE=$PWD/$DIR/loli.jks"
  echo "LOLI_KEYSTORE_PASSWORD=$PASS"
  echo "LOLI_KEY_ALIAS=loli"
  echo "LOLI_KEY_PASSWORD=$PASS"
} >> "$GITHUB_ENV"

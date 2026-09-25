#!/usr/bin/env bash
# Проверяет миграции и RLS на чистом PostgreSQL.
# DATABASE_URL — строка подключения суперпользователя к пустой тестовой БД.
# Никогда не запускайте этот скрипт против боевого проекта Supabase: он создаёт тестовых пользователей.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${DATABASE_URL:?Укажите DATABASE_URL тестовой базы}"
export PGOPTIONS="--client-min-messages=warning"
psql() { command psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -q "$@"; }

psql -f supabase/tests/00_supabase_shim.sql
for f in supabase/migrations/*.sql; do
  echo "Миграция: $f"
  psql -f "$f"
done
# Миграции должны быть идемпотентными (повторный запуск не ломает схему).
for f in supabase/migrations/*.sql; do psql -f "$f"; done
psql -f supabase/tests/10_rls_test.sql

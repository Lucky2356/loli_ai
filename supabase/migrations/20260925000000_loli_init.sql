-- =====================================================================
-- Лоли — схема данных Supabase (PostgreSQL).
-- Общая для Android и будущего Windows-клиента.
--
-- Принципы:
--  * Каждая строка принадлежит пользователю (user_id = auth.uid()); RLS обязателен.
--  * id генерирует клиент (UUID) — это позволяет создавать записи офлайн.
--  * updated_at ставит клиент (момент изменения), server_updated_at — сервер (курсор синхронизации).
--  * Удаление мягкое (deleted = true) — tombstone нужен, чтобы удаление дошло до других устройств.
--  * Триггер loli_sync_guard реализует last-write-wins: устаревшее обновление игнорируется,
--    сменить владельца строки нельзя.
-- =====================================================================

-- ---------- Общие функции ----------

create or replace function public.loli_sync_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    -- Last-write-wins: более старая версия не перезаписывает более новую.
    if new.updated_at < old.updated_at then
      return null;
    end if;
    -- Владелец строки неизменен.
    new.user_id := old.user_id;
  else
    new.user_id := coalesce(new.user_id, auth.uid());
  end if;
  -- clock_timestamp(), а не now(): у строк одной транзакции будут разные метки,
  -- постраничная выгрузка по курсору не пропустит строки.
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

-- ---------- Заметки и идеи ----------

create table if not exists public.notes (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null default 'note' check (kind in ('note', 'idea')),
  title text not null default '' check (char_length(title) <= 1000),
  content text not null default '' check (char_length(content) <= 200000),
  tags text[] not null default '{}',
  pinned boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- Расходы ----------

create table if not exists public.expenses (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  amount_minor bigint not null check (amount_minor > 0),
  currency text not null default 'RUB' check (currency ~ '^[A-Z]{3}$'),
  category text not null default 'Другое' check (char_length(category) <= 100),
  description text not null default '' check (char_length(description) <= 1000),
  occurred_on date not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- Задачи ----------

create table if not exists public.tasks (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  title text not null check (char_length(title) <= 1000),
  details text not null default '' check (char_length(details) <= 100000),
  due_date date,
  due_time time,
  done boolean not null default false,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- Напоминания ----------

create table if not exists public.reminders (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  text text not null check (char_length(text) <= 2000),
  trigger_at timestamptz not null,
  recurrence text check (recurrence is null or recurrence ~ '^FREQ=(HOURLY|DAILY|WEEKLY|MONTHLY)'),
  time_zone text not null default 'UTC',
  active boolean not null default true,
  last_fired_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- Долгосрочная память ----------

create table if not exists public.memories (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  content text not null check (char_length(content) <= 10000),
  category text not null default 'other' check (char_length(category) <= 50),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- История разговоров ----------

create table if not exists public.conversation_messages (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  conversation_id uuid not null,
  role text not null check (role in ('user', 'assistant')),
  content text not null check (char_length(content) <= 50000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

-- ---------- Индексы, триггеры и RLS для синхронизируемых таблиц ----------

do $$
declare
  t text;
begin
  foreach t in array array['notes', 'expenses', 'tasks', 'reminders', 'memories', 'conversation_messages']
  loop
    execute format('create index if not exists %I on public.%I (user_id, server_updated_at)', t || '_user_sync_idx', t);

    execute format('drop trigger if exists loli_sync_guard on public.%I', t);
    execute format(
      'create trigger loli_sync_guard before insert or update on public.%I for each row execute function public.loli_sync_guard()', t);

    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon', t);
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);

    execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
    execute format('create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))', t || '_select_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check (user_id = (select auth.uid()))', t || '_insert_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
    execute format('create policy %I on public.%I for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t || '_update_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_delete_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using (user_id = (select auth.uid()))', t || '_delete_own', t);
  end loop;
end;
$$;

create index if not exists expenses_user_date_idx on public.expenses (user_id, occurred_on) where not deleted;

-- ---------- Профиль: имя ассистента и настройки AI (без API-ключа!) ----------

create table if not exists public.profiles (
  id uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  assistant_name text not null default 'Лоли' check (char_length(assistant_name) between 1 and 40),
  ai_provider text check (ai_provider is null or char_length(ai_provider) <= 40),
  ai_model text check (ai_model is null or char_length(ai_model) <= 200),
  ai_endpoint text check (ai_endpoint is null or char_length(ai_endpoint) <= 500),
  updated_at timestamptz not null default now(),
  server_updated_at timestamptz not null default clock_timestamp()
);

create or replace function public.loli_profile_guard()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' then
    if new.updated_at < old.updated_at then
      return null;
    end if;
    new.id := old.id;
  end if;
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

drop trigger if exists loli_profile_guard on public.profiles;
create trigger loli_profile_guard before insert or update on public.profiles
  for each row execute function public.loli_profile_guard();

alter table public.profiles enable row level security;
revoke all on public.profiles from anon;
grant select, insert, update, delete on public.profiles to authenticated;

drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles for select to authenticated using (id = (select auth.uid()));
drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own on public.profiles for insert to authenticated with check (id = (select auth.uid()));
drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));
drop policy if exists profiles_delete_own on public.profiles;
create policy profiles_delete_own on public.profiles for delete to authenticated using (id = (select auth.uid()));

-- Функции-триггеры не должны вызываться клиентами напрямую.
revoke execute on function public.loli_sync_guard() from public, anon, authenticated;
revoke execute on function public.loli_profile_guard() from public, anon, authenticated;

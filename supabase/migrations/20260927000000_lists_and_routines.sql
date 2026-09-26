-- 1.7.0: списки покупок и сценарии. Та же схема синхронизации и те же правила доступа, что у остальных таблиц.
-- Секретные заметки на сервер не попадают вовсе — для них таблицы нет.

create table if not exists public.shopping_items (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  list_name text not null default 'Покупки' check (char_length(list_name) <= 200),
  text text not null check (char_length(text) <= 500),
  done boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

create table if not exists public.routines (
  id uuid primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  trigger_phrase text not null check (char_length(trigger_phrase) <= 200),
  commands text[] not null default '{}' check (cardinality(commands) <= 20),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted boolean not null default false,
  server_updated_at timestamptz not null default clock_timestamp()
);

do $$
declare
  t text;
begin
  foreach t in array array['shopping_items', 'routines']
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

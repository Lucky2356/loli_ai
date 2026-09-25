-- =====================================================================
-- ТОЛЬКО ДЛЯ ТЕСТОВ на «голом» PostgreSQL (CI). В настоящем Supabase всё это уже есть.
-- Воспроизводит минимум Supabase: схему auth, таблицу users, функцию auth.uid()
-- (читает sub из request.jwt.claims, как PostgREST) и роли anon/authenticated.
-- =====================================================================
do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end;
$$;

create schema if not exists auth;
create table if not exists auth.users (id uuid primary key, email text);

create or replace function auth.uid() returns uuid
language sql stable
as $$
  select nullif(coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
  ), '')::uuid
$$;

grant usage on schema auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
grant usage on schema public to anon, authenticated;

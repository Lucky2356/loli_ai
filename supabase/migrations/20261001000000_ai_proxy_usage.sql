-- «Облако Лоли»: учёт запросов к серверу-посреднику AI (supabase/functions/ai-proxy).
-- Счётчик на пользователя и день; увеличивается атомарно, только через функцию с проверкой лимита.

create table if not exists public.ai_usage (
    user_id uuid not null references auth.users (id) on delete cascade,
    day date not null default (now() at time zone 'utc')::date,
    requests integer not null default 0,
    primary key (user_id, day)
);

alter table public.ai_usage enable row level security;

-- Пользователь видит только свой счётчик (например, «осталось N запросов»), менять напрямую не может.
drop policy if exists "ai_usage_select_own" on public.ai_usage;
create policy "ai_usage_select_own" on public.ai_usage
    for select using (auth.uid() = user_id);

-- Атомарно: +1 запрос, если лимит не исчерпан. Возвращает true — можно отвечать.
create or replace function public.ai_usage_take(p_user uuid, p_limit integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    used integer;
begin
    insert into public.ai_usage (user_id, day, requests)
    values (p_user, (now() at time zone 'utc')::date, 1)
    on conflict (user_id, day) do update set requests = public.ai_usage.requests + 1
    where public.ai_usage.requests < p_limit
    returning requests into used;
    return used is not null;
end;
$$;

-- Вызывать может только сервер (service role), не клиент.
revoke all on function public.ai_usage_take(uuid, integer) from public, anon, authenticated;

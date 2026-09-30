-- «Облако Лоли»: вернуть единицу суточного лимита, если провайдер AI не ответил (5xx, таймаут).
-- Вызывает только сервер (service role), как и ai_usage_take.

create or replace function public.ai_usage_refund(p_user uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.ai_usage
       set requests = greatest(requests - 1, 0)
     where user_id = p_user
       and day = (now() at time zone 'utc')::date;
end;
$$;

revoke all on function public.ai_usage_refund(uuid) from public, anon, authenticated;

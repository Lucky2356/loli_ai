-- =====================================================================
-- Тесты безопасности (RLS) и логики синхронизации на уровне БД.
-- Запуск: scripts/test-supabase.sh (в CI — на PostgreSQL 16).
-- Любая ошибка/проваленный assert останавливает выполнение (ON_ERROR_STOP).
-- =====================================================================
\set ON_ERROR_STOP on
\set QUIET on

insert into auth.users (id, email) values
  ('aaaaaaaa-0000-0000-0000-000000000001', 'alice@example.com'),
  ('bbbbbbbb-0000-0000-0000-000000000002', 'bob@example.com')
on conflict do nothing;

-- 0. RLS включён на ВСЕХ таблицах схемы public.
do $$
declare missing text;
begin
  select string_agg(relname, ', ') into missing
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity;
  assert missing is null, 'RLS выключен на таблицах: ' || missing;
end $$;

-- 1. Алиса создаёт данные; user_id проставляется сервером.
begin;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true) \g /dev/null
insert into public.notes (id, kind, title, content, created_at, updated_at)
  values ('10000000-0000-0000-0000-000000000001', 'idea', 'Приложение для холодильника', 'учёт продуктов', now(), '2026-09-25T10:00:00Z');
insert into public.expenses (id, amount_minor, currency, category, occurred_on, updated_at)
  values ('20000000-0000-0000-0000-000000000001', 85000, 'RUB', 'Продукты', '2026-09-25', now());
insert into public.memories (id, content, category) values ('30000000-0000-0000-0000-000000000001', 'Секрет Алисы', 'fact');
insert into public.tasks (id, title) values ('40000000-0000-0000-0000-000000000001', 'Купить молоко');
insert into public.reminders (id, text, trigger_at, recurrence) values ('50000000-0000-0000-0000-000000000001', 'Почта', now() + interval '1 day', 'FREQ=WEEKLY;INTERVAL=1;BYDAY=MO;TIME=09:00');
insert into public.conversation_messages (id, conversation_id, role, content) values ('60000000-0000-0000-0000-000000000001', gen_random_uuid(), 'user', 'привет');
insert into public.profiles (id, assistant_name, ai_provider) values ('aaaaaaaa-0000-0000-0000-000000000001', 'Лоли', 'openai');
do $$ begin
  assert (select user_id from public.notes where id = '10000000-0000-0000-0000-000000000001') = 'aaaaaaaa-0000-0000-0000-000000000001', 'user_id не проставлен';
  assert (select count(*) from public.notes) = 1, 'Алиса должна видеть свою заметку';
end $$;
commit;

-- 2. Боб не видит, не меняет и не удаляет чужие данные.
begin;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-0000-0000-000000000002","role":"authenticated"}', true) \g /dev/null
do $$
declare n int;
begin
  assert (select count(*) from public.notes) = 0, 'Боб видит чужие заметки';
  assert (select count(*) from public.expenses) = 0, 'Боб видит чужие расходы';
  assert (select count(*) from public.memories) = 0, 'Боб видит чужую память';
  assert (select count(*) from public.tasks) = 0, 'Боб видит чужие задачи';
  assert (select count(*) from public.reminders) = 0, 'Боб видит чужие напоминания';
  assert (select count(*) from public.conversation_messages) = 0, 'Боб видит чужую историю';
  assert (select count(*) from public.profiles) = 0, 'Боб видит чужой профиль';

  update public.notes set title = 'взлом', updated_at = now() + interval '1 year' where id = '10000000-0000-0000-0000-000000000001';
  get diagnostics n = row_count;
  assert n = 0, 'Боб изменил чужую заметку';

  delete from public.expenses where id = '20000000-0000-0000-0000-000000000001';
  get diagnostics n = row_count;
  assert n = 0, 'Боб удалил чужой расход';
end $$;

-- 2a. Вставка от имени другого пользователя запрещена.
do $$ begin
  begin
    insert into public.notes (id, user_id, title) values (gen_random_uuid(), 'aaaaaaaa-0000-0000-0000-000000000001', 'подброс');
    raise exception 'Боб вставил строку от имени Алисы';
  exception when insufficient_privilege then null;
  end;
end $$;

-- 2b. Upsert с id чужой строки (как делает синхронизация) не должен перезаписать её.
do $$ begin
  begin
    insert into public.memories (id, content, category, updated_at)
      values ('30000000-0000-0000-0000-000000000001', 'взлом', 'fact', now() + interval '1 year')
      on conflict (id) do update set content = excluded.content, updated_at = excluded.updated_at;
    raise exception 'Upsert Боба перезаписал чужую строку';
  exception when insufficient_privilege then null;
  end;
end $$;
commit;

-- 3. Анонимный пользователь не имеет доступа вообще.
begin;
set local role anon;
do $$ begin
  begin
    perform count(*) from public.notes;
    raise exception 'anon читает заметки';
  exception when insufficient_privilege then null;
  end;
end $$;
commit;

-- 4. Last-write-wins, неизменность владельца, server_updated_at.
begin;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-0000-0000-0000-000000000001","role":"authenticated"}', true) \g /dev/null
do $$
declare before_server timestamptz;
begin
  select server_updated_at into before_server from public.notes where id = '10000000-0000-0000-0000-000000000001';

  -- Более старая версия (пришла с другого устройства с опозданием) игнорируется.
  update public.notes set title = 'старая версия', updated_at = '2026-09-25T09:00:00Z' where id = '10000000-0000-0000-0000-000000000001';
  assert (select title from public.notes where id = '10000000-0000-0000-0000-000000000001') = 'Приложение для холодильника', 'LWW: старая версия перезаписала новую';

  -- Более новая применяется и двигает курсор синхронизации.
  perform pg_sleep(0.01);
  update public.notes set content = 'учёт продуктов\n• штрихкоды', updated_at = '2026-09-25T11:00:00Z' where id = '10000000-0000-0000-0000-000000000001';
  assert (select content from public.notes where id = '10000000-0000-0000-0000-000000000001') like '%штрихкоды%', 'LWW: новая версия не применилась';
  assert (select server_updated_at from public.notes where id = '10000000-0000-0000-0000-000000000001') > before_server, 'server_updated_at не обновился';

  -- Передать запись другому пользователю нельзя.
  begin
    update public.notes set user_id = 'bbbbbbbb-0000-0000-0000-000000000002', updated_at = '2026-09-25T12:00:00Z' where id = '10000000-0000-0000-0000-000000000001';
  exception when insufficient_privilege then null;
  end;
  assert (select user_id from public.notes where id = '10000000-0000-0000-0000-000000000001') = 'aaaaaaaa-0000-0000-0000-000000000001', 'владелец строки изменился';

  -- Мягкое удаление видно владельцу (tombstone для синхронизации).
  update public.tasks set deleted = true, updated_at = now() + interval '1 second' where id = '40000000-0000-0000-0000-000000000001';
  assert (select deleted from public.tasks where id = '40000000-0000-0000-0000-000000000001'), 'tombstone не сохранился';
end $$;

-- 4a. Проверки данных на стороне БД.
do $$ begin
  begin
    insert into public.expenses (id, amount_minor, occurred_on) values (gen_random_uuid(), -100, current_date);
    raise exception 'отрицательная сумма принята';
  exception when check_violation then null;
  end;
  begin
    insert into public.expenses (id, amount_minor, currency, occurred_on) values (gen_random_uuid(), 100, 'рубли', current_date);
    raise exception 'некорректная валюта принята';
  exception when check_violation then null;
  end;
  begin
    insert into public.reminders (id, text, trigger_at, recurrence) values (gen_random_uuid(), 'x', now(), 'DROP TABLE');
    raise exception 'некорректное правило повторения принято';
  exception when check_violation then null;
  end;
end $$;
commit;

-- 5. Пакетная вставка: у строк разные server_updated_at (курсор синхронизации не пропустит строки).
begin;
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-0000-0000-0000-000000000002","role":"authenticated"}', true) \g /dev/null
insert into public.tasks (id, title) select gen_random_uuid(), 'задача ' || g from generate_series(1, 50) g;
do $$ begin
  assert (select count(distinct server_updated_at) from public.tasks) = 50, 'одинаковые server_updated_at в пакете';
end $$;
commit;

\echo 'RLS и серверные правила синхронизации: все проверки пройдены'

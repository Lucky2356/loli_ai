-- Ежегодные напоминания (дни рождения, годовщины): разрешаем FREQ=YEARLY.
alter table public.reminders drop constraint if exists reminders_recurrence_check;
alter table public.reminders
  add constraint reminders_recurrence_check
  check (recurrence is null or recurrence ~ '^FREQ=(HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY)');

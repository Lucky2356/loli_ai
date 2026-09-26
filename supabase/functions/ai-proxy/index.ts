// «Облако Лоли» — сервер-посредник AI (Supabase Edge Function, Deno).
//
// Приложение шлёт OpenAI-совместимый запрос на /functions/v1/ai-proxy/chat/completions с токеном
// вошедшего пользователя. Функция проверяет пользователя, дневной лимит и пересылает запрос провайдеру
// (по умолчанию OpenRouter) с ключом разработчика. Ключ хранится только в секретах Supabase.
//
// Секреты (supabase secrets set …):
//   LOLI_AI_KEY        — ключ провайдера (обязательно)
//   LOLI_AI_BASE_URL   — OpenAI-совместимый адрес, по умолчанию https://openrouter.ai/api/v1
//   LOLI_AI_MODEL      — модель, по умолчанию deepseek/deepseek-chat
//   LOLI_AI_DAILY_LIMIT — запросов на пользователя в сутки, по умолчанию 100
import { createClient } from "npm:@supabase/supabase-js@2";

const BASE = (Deno.env.get("LOLI_AI_BASE_URL") ?? "https://openrouter.ai/api/v1").replace(/\/+$/, "");
const MODEL = Deno.env.get("LOLI_AI_MODEL") ?? "deepseek/deepseek-chat";
const DAILY_LIMIT = Number(Deno.env.get("LOLI_AI_DAILY_LIMIT") ?? "100");
const MAX_TOKENS = 1500;
const MAX_CHARS = 24_000;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function error(status: number, message: string): Response {
  return json(status, { error: { message } });
}

Deno.serve(async (req) => {
  const path = new URL(req.url).pathname;
  if (req.method !== "POST" || !path.endsWith("/chat/completions")) return error(404, "not found");

  const key = Deno.env.get("LOLI_AI_KEY");
  if (!key) return error(503, "Облако Лоли не настроено: нет ключа провайдера");

  // Кто спрашивает: только вошедшие пользователи Лоли.
  const auth = req.headers.get("Authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL")!;
  const userClient = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: auth } } });
  const { data: { user } } = await userClient.auth.getUser();
  // Анонимные сессии не считаются: иначе лимит легко обойти, создавая новых «пользователей».
  if (!user || (user as { is_anonymous?: boolean }).is_anonymous) return error(401, "Войдите в аккаунт Лоли, чтобы пользоваться облаком");

  // Сначала проверяем запрос, потом списываем лимит: кривой запрос не должен съедать попытки.
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return error(400, "Некорректный запрос");
  }
  const raw = Array.isArray(body.messages) ? body.messages : [];
  // Только обычные реплики с текстом: никаких tool/function-ролей и вложенных объектов.
  const messages: { role: string; content: string }[] = [];
  for (const m of raw) {
    const role = (m as { role?: unknown })?.role;
    const content = (m as { content?: unknown })?.content;
    if ((role !== "system" && role !== "user" && role !== "assistant") || typeof content !== "string") {
      return error(400, "Некорректное сообщение в запросе");
    }
    messages.push({ role, content });
  }
  const size = messages.reduce((n, m) => n + m.content.length, 0);
  if (messages.length === 0 || messages.length > 40 || size > MAX_CHARS) return error(413, "Слишком длинный запрос");
  const temperature = typeof body.temperature === "number" && isFinite(body.temperature) ? Math.min(Math.max(body.temperature, 0), 1.5) : 0.3;
  const upstreamBody: Record<string, unknown> = {
    model: MODEL,
    messages,
    max_tokens: Math.max(1, Math.min(Number(body.max_tokens ?? body.max_completion_tokens ?? 800) || 800, MAX_TOKENS)),
    temperature,
  };
  const format = body.response_format as { type?: unknown } | undefined;
  if (format?.type === "json_object") upstreamBody.response_format = { type: "json_object" };

  // Дневной лимит — атомарно в базе.
  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: allowed, error: limitError } = await admin.rpc("ai_usage_take", { p_user: user.id, p_limit: DAILY_LIMIT });
  if (limitError) return error(500, "Не удалось проверить лимит");
  if (!allowed) return error(429, `Лимит облака на сегодня исчерпан (${DAILY_LIMIT} запросов). Завтра снова можно.`);

  const upstream = await fetch(`${BASE}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${key}`,
      "HTTP-Referer": "https://github.com/Lucky2356/loli_ai",
      "X-Title": "Loli Assistant",
    },
    body: JSON.stringify(upstreamBody),
  });
  const text = await upstream.text();
  // Ошибки провайдера не раскрывают ключ: отдаём статус и короткое сообщение.
  if (!upstream.ok) return error(upstream.status === 429 ? 429 : 502, `Провайдер AI ответил ${upstream.status}`);
  return new Response(text, { status: 200, headers: { "Content-Type": "application/json" } });
});

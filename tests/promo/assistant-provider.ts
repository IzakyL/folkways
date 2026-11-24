import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

// The assistant's model, for the promo takes. Recorded once against DeepSeek through this loopback, then played back
// from the recording, so a retake gets the same words, scripts and tool calls as the take that was approved.
//
//   live    forwards each request to DeepSeek with the key from .env and keeps what came back;
//   replay  answers each request with the next recorded turn, streamed out a little at a time as the model did.
//
// FOLKWAYS_PROMO_ASSISTANT=live forces a fresh recording; otherwise a segment replays when its recording exists, and
// goes on live past its end when a key is at hand. FOLKWAYS_PROMO_ASSISTANT=replay never goes live.
// The key never leaves this process: the game is given a placeholder and the recording keeps no headers.

// The checkout root, as the test harness takes it: every run starts there.
export const RECORDINGS = join(process.cwd(), "tests", "promo", "assistant");
const KEY_ENV = "FOLKWAYS_ASSISTANT_LIVE_DEEPSEEK_KEY";
const UPSTREAM = "https://api.deepseek.com";
export const PROMO_MODEL = "deepseek-v4-flash";
const LIVE = join(process.cwd(), "tests", "out", "promo", "keep", "assistant-live");

export type ToolCall = { id: string; name: string; arguments: string };
/** One model turn, folded from its stream: what it thought, said and called, and why it stopped. */
export type Turn = { reasoning?: string; content?: string; tool_calls?: ToolCall[]; finish_reason: string };
/** What the game sent for the turn, kept short: the newest messages only, to read a recording by. */
export type Asked = { messages: number; last: unknown[] };
export type Recording = {
  segment: string;
  model: string;
  recorded: string;
  /** Values that differ between takes (a hull's UUID, a colony's id), written as {{NAME}} in the turns. */
  placeholders: string[];
  turns: Array<{ asked: Asked; turn: Turn }>;
};

export type Provider = {
  mode: "live" | "replay";
  port: number;
  /** Values for this take's placeholders; set before the first request that needs them. */
  values: Record<string, string>;
  turns: Array<{ asked: Asked; turn: Turn }>;
  served: number;
  /** A replayed recording ran out and went on live. */
  extended?: boolean;
  /** Requests replay had no turn left for. */
  overrun: number;
  save(extra?: Record<string, unknown>): string | undefined;
  close(): Promise<void>;
};

export function recordingPath(segment: string) {
  return join(RECORDINGS, `${segment}.json`);
}

export function providerMode(segment: string): "live" | "replay" {
  const forced = process.env.FOLKWAYS_PROMO_ASSISTANT;
  if (forced === "live" || forced === "replay") return forced;
  return existsSync(recordingPath(segment)) ? "replay" : "live";
}

export async function startProvider(segment: string, mode = providerMode(segment), port = 0): Promise<Provider> {
  const key = process.env[KEY_ENV];
  if (mode === "live" && !key) throw new Error(`Set ${KEY_ENV} in .env to record the ${segment} segment live`);
  const recorded: Recording | undefined = mode === "replay"
    ? JSON.parse(readFileSync(recordingPath(segment), "utf8")) : undefined;
  const provider: Provider = {
    mode, port: 0, values: {}, turns: [], served: 0, overrun: 0,
    save: () => undefined,
    close: async () => undefined,
  };
  const server: Server = createServer((request, response) => {
    void (async () => {
      const body = await read(request);
      let parsed: any = {};
      try { parsed = JSON.parse(body); } catch { /* a non-JSON request is passed on as it came */ }
      const asked: Asked = { messages: parsed.messages?.length ?? 0, last: (parsed.messages ?? []).slice(-2).map(brief) };
      // A recording that ends early goes on live, when a key is at hand: a take can be extended a step at a time.
      const live = mode === "live" || (!recorded!.turns[provider.served] && !!key
        && process.env.FOLKWAYS_PROMO_ASSISTANT !== "replay");
      if (live) {
        if (mode === "replay") {
          provider.served++;
          provider.extended = true;
        }
        await forward(request, response, body, key!, turn => {
          provider.turns.push({ asked, turn });
          // Kept as it grows, to follow a live take and to read what went wrong when one fails.
          mkdirSync(LIVE, { recursive: true });
          writeFileSync(join(LIVE, `${segment}.json`), JSON.stringify(provider.turns, null, 1));
        });
        return;
      }
      const next = recorded!.turns[provider.served];
      provider.served++;
      if (!next) {
        provider.overrun++;
        await stream(response, { content: "", finish_reason: "stop" }, parsed.stream !== false);
        return;
      }
      provider.turns.push({ asked, turn: next.turn });
      await stream(response, fill(next.turn, provider.values), parsed.stream !== false);
    })().catch(error => {
      if (!response.headersSent) response.writeHead(502, { "Content-Type": "text/plain" });
      response.end(String(error));
    });
  });
  await new Promise<void>(resolve => server.listen(port, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("The loopback provider has no port");
  provider.port = address.port;
  provider.save = (extra = {}) => {
    if (mode !== "live" && !provider.extended) return undefined;
    const placeholders = Object.keys(provider.values);
    const out: Recording & Record<string, unknown> = {
      segment, model: PROMO_MODEL, recorded: new Date().toISOString(), placeholders, ...extra,
      turns: provider.turns.map(({ asked, turn }) => ({ asked: blank(asked, provider.values),
        turn: blank(turn, provider.values) })),
    };
    const path = recordingPath(segment);
    mkdirSync(dirname(path), { recursive: true });
    const text = JSON.stringify(out, null, 2) + "\n";
    if (key && text.includes(key)) throw new Error("The recording would hold the provider key");
    writeFileSync(path, text);
    return path;
  };
  provider.close = async () => {
    server.closeAllConnections();
    await new Promise<void>(resolve => server.close(() => resolve()));
  };
  return provider;
}

/** The assistant's client settings and a placeholder key, pointing it at the loopback provider. */
export function assistantSeed(provider: Provider) {
  return {
    settings: JSON.stringify({
      entries: [{ id: "promo", provider: "deepseek", name: "DeepSeek", model: PROMO_MODEL,
        baseUrl: `http://127.0.0.1:${provider.port}` }],
      selectedEntryId: "promo",
      autoApprove: [],
    }),
    credentials: JSON.stringify({ "folkways_assistant.entry.promo": { apiKey: "promo-loopback" } }),
  };
}

function read(request: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    request.on("data", chunk => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    request.on("error", reject);
  });
}

// A message as the recording shows it: tool results and long words cut short.
function brief(message: any) {
  const text = (value: unknown) => {
    const s = typeof value === "string" ? value : JSON.stringify(value ?? "");
    return s.length > 600 ? s.slice(0, 600) + "…" : s;
  };
  return { role: message.role, content: text(message.content),
    ...(message.tool_calls ? { tool_calls: message.tool_calls.map((call: any) => call.function?.name) } : {}) };
}

async function forward(request: IncomingMessage, response: ServerResponse, body: string, key: string,
  done: (turn: Turn) => void) {
  const upstream = await fetch(UPSTREAM + (request.url ?? "/chat/completions"), {
    method: request.method ?? "POST",
    headers: { "Content-Type": "application/json", Accept: request.headers.accept ?? "text/event-stream",
      Authorization: `Bearer ${key}` },
    body: request.method === "GET" ? undefined : body,
  });
  response.writeHead(upstream.status, { "Content-Type": upstream.headers.get("content-type") ?? "text/event-stream" });
  const folded = new Fold();
  const decoder = new TextDecoder();
  let pending = "";
  const reader = upstream.body!.getReader();
  let refused = "";
  for (;;) {
    const { value, done: ended } = await reader.read();
    if (ended) break;
    response.write(value);
    pending += decoder.decode(value, { stream: true });
    if (!upstream.ok) refused += Buffer.from(value).toString("utf8");
    let cut: number;
    while ((cut = pending.indexOf("\n")) >= 0) {
      folded.line(pending.slice(0, cut).trim());
      pending = pending.slice(cut + 1);
    }
  }
  folded.line(pending.trim());
  response.end();
  if (upstream.ok) done(folded.turn());
  // The assistant only says the request failed; what the provider answered is kept here.
  else console.error(`provider answered ${upstream.status}: ${refused.slice(0, 2000)}`);
}

/** Folds a chat-completions stream (or a whole JSON reply) back into one turn. */
class Fold {
  private reasoning = "";
  private content = "";
  private calls: ToolCall[] = [];
  private finish = "stop";

  line(line: string) {
    if (!line) return;
    const data = line.startsWith("data:") ? line.slice(5).trim() : line;
    if (data === "[DONE]") return;
    let chunk: any;
    try { chunk = JSON.parse(data); } catch { return; }
    for (const choice of chunk.choices ?? []) {
      const delta = choice.delta ?? choice.message ?? {};
      if (delta.reasoning_content) this.reasoning += delta.reasoning_content;
      if (delta.content) this.content += delta.content;
      for (const call of delta.tool_calls ?? []) {
        const index = call.index ?? this.calls.length;
        const slot = this.calls[index] ??= { id: "", name: "", arguments: "" };
        if (call.id) slot.id = call.id;
        if (call.function?.name) slot.name += call.function.name;
        if (call.function?.arguments) slot.arguments += call.function.arguments;
      }
      if (choice.finish_reason) this.finish = choice.finish_reason;
    }
  }

  turn(): Turn {
    return { ...(this.reasoning ? { reasoning: this.reasoning } : {}), ...(this.content ? { content: this.content } : {}),
      ...(this.calls.length ? { tool_calls: this.calls } : {}), finish_reason: this.finish };
  }
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

// The recorded turn streamed back in small pieces, at about the pace the model wrote it, so the chat fills in on
// camera rather than appearing at once.
async function stream(response: ServerResponse, turn: Turn, streaming: boolean) {
  const id = `promo-${Date.now()}`;
  if (!streaming) {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ id, object: "chat.completion", model: PROMO_MODEL, choices: [{ index: 0,
      message: { role: "assistant", content: turn.content ?? "", reasoning_content: turn.reasoning,
        tool_calls: turn.tool_calls?.map(call => ({ id: call.id, type: "function",
          function: { name: call.name, arguments: call.arguments } })) }, finish_reason: turn.finish_reason }] }));
    return;
  }
  response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
  const send = (delta: Record<string, unknown>, finish: string | null = null) => response.write(
    `data: ${JSON.stringify({ id, object: "chat.completion.chunk", model: PROMO_MODEL,
      choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
  send({ role: "assistant", content: "" });
  for (const piece of pieces(turn.reasoning ?? "", 24)) {
    send({ reasoning_content: piece });
    await sleep(12);
  }
  for (const piece of pieces(turn.content ?? "", 4)) {
    send({ content: piece });
    await sleep(22);
  }
  for (const [index, call] of (turn.tool_calls ?? []).entries()) {
    send({ tool_calls: [{ index, id: call.id, type: "function", function: { name: call.name, arguments: "" } }] });
    for (const piece of pieces(call.arguments, 160)) {
      send({ tool_calls: [{ index, function: { arguments: piece } }] });
      await sleep(10);
    }
  }
  send({}, turn.finish_reason);
  response.end("data: [DONE]\n\n");
}

function pieces(text: string, size: number) {
  const out: string[] = [];
  const chars = [...text];
  for (let i = 0; i < chars.length; i += size) out.push(chars.slice(i, i + size).join(""));
  return out;
}

function fill<T>(value: T, values: Record<string, string>): T {
  let text = JSON.stringify(value);
  for (const [name, actual] of Object.entries(values)) text = text.split(`{{${name}}}`).join(actual);
  return JSON.parse(text);
}

function blank<T>(value: T, values: Record<string, string>): T {
  let text = JSON.stringify(value);
  for (const [name, actual] of Object.entries(values)) if (actual) text = text.split(actual).join(`{{${name}}}`);
  return JSON.parse(text);
}

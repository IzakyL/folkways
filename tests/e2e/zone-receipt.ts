import { defineTask, javaTask, screen, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { frames, safeAction } from "../shared/bw-helpers";

export type ActionBar = {
  text: string;
  remaining_ticks: number;
  via?: "task" | "wait";
  waited_ms?: number;
  cleared?: { text: string | null; remaining_ticks: number };
  clear_error?: string;
};

export async function readActionBar(client: MinecraftClient): Promise<ActionBar> {
  const hud: any = await safeAction(() => screen.hud(client));
  return { text: hud?.actionBar?.text ?? "", remaining_ticks: hud?.actionBar?.remainingTicks ?? 0 };
}

/** Lapses the HUD's action-bar message at once (its overlay timer to 0), answering what it held. */
const CLEAR_ACTION_BAR = defineTask<void, Record<string, any>>({
  name: "folkways.zone.clear-action-bar",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.gui.Gui", "net.minecraft.network.chat.Component"],
    body: `
Minecraft minecraft = (Minecraft) ctx.client();
if (minecraft == null) {
    throw Fail.unavailable("no Minecraft client in the task context");
}
Gui gui = minecraft.gui;
if (gui == null) {
    throw Fail.unavailable("no gui on " + minecraft.getClass().getName());
}

Map<String, Object> out = new LinkedHashMap<String, Object>();
java.lang.reflect.Field timer = field("overlayMessageTime");
java.lang.reflect.Field message = field("overlayMessageString");
Component was = (Component) message.get(gui);
out.put("was_ticks", Integer.valueOf(timer.getInt(gui)));
out.put("was_text", was == null ? null : was.getString());

timer.setInt(gui, 0);

out.put("now_ticks", Integer.valueOf(timer.getInt(gui)));
out.put("ok", Boolean.TRUE);
return out;
`,
    members: `
private static java.lang.reflect.Field field(String name) throws Exception {
    java.lang.reflect.Field found = Gui.class.getDeclaredField(name);
    found.setAccessible(true);
    return found;
}
`,
  }),
});

const fastClear = new WeakMap<object, string | null>();

async function waitOutActionBar(client: MinecraftClient, timeoutMs: number, why: string | null): Promise<ActionBar> {
  const started = Date.now();
  const deadline = started + timeoutMs;
  let bar = await readActionBar(client);
  while (bar.remaining_ticks > 0 && Date.now() < deadline) {
    await safeAction(() => frames(client, 10));
    bar = await readActionBar(client);
  }
  const waited = Date.now() - started;
  if (bar.remaining_ticks > 0) {
    bar.clear_error = `waited out ${timeoutMs}ms for "${bar.text}" to lapse and it still has `
      + `${bar.remaining_ticks} ticks left`
      + (why ? `; the one-shot clear was unavailable: ${why}` : "");
  } else if (why) {
    bar.clear_error = why;
  }
  bar.via = "wait";
  bar.waited_ms = waited;
  return bar;
}

export async function drainActionBar(client: MinecraftClient, timeoutMs = 8_000): Promise<ActionBar> {
  const known = fastClear.get(client as unknown as object);
  if (known) {
    return waitOutActionBar(client, timeoutMs, known);
  }

  const started = Date.now();
  let value: any;
  try {
    value = await client.run(CLEAR_ACTION_BAR, undefined, { timeoutMs: 20_000 });
  } catch (error: any) {
    const why = `the client task refused the action-bar clear: ${String(error?.message ?? error)}`;
    fastClear.set(client as unknown as object, why);
    return waitOutActionBar(client, timeoutMs, why);
  }

  const cleared = value?.ok === true && Number(value?.now_ticks) === 0;
  if (!cleared) {
    const why = `the action-bar clear did not land: value=${JSON.stringify(value)}`;
    fastClear.set(client as unknown as object, why);
    return waitOutActionBar(client, timeoutMs, why);
  }

  fastClear.set(client as unknown as object, null);
  return {
    text: "",
    remaining_ticks: 0,
    via: "task",
    waited_ms: Date.now() - started,
    cleared: { text: value.was_text ?? null, remaining_ticks: Number(value.was_ticks ?? 0) },
  };
}

export const FIRST_CORNER = /^First corner\./;

export const ZONE_MARKED = /marked\. Ctrl-scroll/;

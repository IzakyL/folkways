import {
  BlockwrightConnectionClosedError,
  BlockwrightInstanceGoneError,
  BlockwrightRequestTimeoutError,
  errorSummary,
  errorToString,
} from "@izakyl/blockwright-client";
import { waitForElement, type Element, type ElementSelector, type PanelHolder, panelElements, aimPoint, LDLIB2_SYMBOLS, type Box } from "../ldlib2";
import {
  MinecraftCommandError,
  MinecraftTaskError,
  MinecraftTimeoutError,
  isMinecraftError,
  input,
  reflect,
  screen,
  world,
  type MinecraftClient,
  type MinecraftServer,
} from "@izakyl/blockwright-minecraft";

export { waitForElement };

type Vec3 = { x: number; y: number; z: number };

/** What {@link safeAction} answers instead of throwing when the game refused or failed a call. */
export interface SafeFailure {
  status: "error";
  reason: string;
  error: string;
}

export function isSafeFailure(value: unknown): value is SafeFailure {
  return typeof value === "object" && value !== null && (value as SafeFailure).status === "error"
    && typeof (value as SafeFailure).error === "string";
}

function instanceLost(error: unknown): boolean {
  return error instanceof BlockwrightInstanceGoneError
    || error instanceof BlockwrightConnectionClosedError
    || error instanceof BlockwrightRequestTimeoutError;
}

/**
 * Runs `fn`, turning a failure the game answered (a task error, a command that did not take, a wait that ran
 * out) into a {@link SafeFailure}. A dead instance or a dropped connection still throws.
 */
export async function safeAction<T>(fn: () => Promise<T>): Promise<T | SafeFailure> {
  try {
    return await fn();
  } catch (error) {
    if (!(error instanceof MinecraftTaskError || error instanceof MinecraftCommandError)) {
      throw error;
    }
    const failure: SafeFailure = { status: "error", reason: errorSummary(error), error: errorToString(error) };
    console.warn(`[blockwright] safeAction swallowed: ${failure.reason}`);
    return failure;
  }
}

/**
 * safeAction for the adapter helpers too (tests/ldlib2, ui): those refuse with a plain Error
 * ("not actionable", "no LDLib2 panel is open"). Such a refusal becomes a SafeFailure here; a dead instance
 * or a dropped connection still throws.
 */
export async function softly<T>(fn: () => Promise<T>): Promise<T | SafeFailure> {
  try {
    return await fn();
  } catch (error) {
    if (instanceLost(error) || !(error instanceof Error)) {
      throw error;
    }
    return { status: "error", reason: errorSummary(error), error: errorToString(error) };
  }
}

export type CommandOutcome =
  | (world.CommandResult & { status: "ok" })
  | { command: string; status: "error"; success?: undefined; output: string[]; reason: string };

/** Runs a console command and answers what happened, never throwing for a command the game refused. */
export async function tryCommand(server: MinecraftServer, command: string): Promise<CommandOutcome> {
  try {
    const result = await world.command(server, command, { allowFailure: true });
    return { ...result, status: "ok" };
  } catch (error) {
    if (instanceLost(error)) throw error;
    return { command, status: "error", output: [], reason: errorSummary(error) };
  }
}

export function commandPos(pos: Vec3): string {
  return `${Math.trunc(pos.x)} ${Math.trunc(pos.y)} ${Math.trunc(pos.z)}`;
}

export function distance(a: Vec3, b: Vec3): number {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

/** Lets the client catch up with the server and draw `count` more frames, with or without a screen open. */
export async function frames(client: MinecraftClient, count: number): Promise<void> {
  await client.settle({ frames: count, requireNoScreen: false });
}

export type ScreenRead = { status: "ok"; screen: screen.Info | null };

export async function safeScreen(client: MinecraftClient): Promise<ScreenRead | SafeFailure> {
  return safeAction(async () => ({ status: "ok" as const, screen: await screen.current(client) }));
}

export function screenOpen(result: unknown): boolean {
  const value = result as { status?: string; screen?: unknown } | undefined;
  return value?.status === "ok" && value.screen != null;
}

export function screenName(result: unknown): string {
  const value = result as
    | { screen?: { className?: string | null } | null; reason?: string; error?: string }
    | undefined;
  return value?.screen?.className ?? value?.reason ?? value?.error ?? "unknown";
}

export function reflectVec3(result: unknown): Vec3 {
  return { x: reflect.number(result, "x"), y: reflect.number(result, "y"), z: reflect.number(result, "z") };
}

/** Moves the cursor, lets `frameCount` frames render so the UI updates its hover, then presses and releases the left button. */
export async function pressAtHovered(
  client: MinecraftClient,
  x: number,
  y: number,
  frameCount = 2,
): Promise<input.ButtonResult> {
  await input.move(client, { x, y });
  await frames(client, frameCount);
  const press = await input.button(client, { button: "left", action: "press" });
  await input.button(client, { button: "left", action: "release" });
  return press;
}

export const PANEL_HOLDERS: PanelHolder[] = ["screen", "container"];

const symbol = (as: string) => {
  const found = LDLIB2_SYMBOLS.find((candidate) => candidate.as === as);
  if (!found) throw new Error(`tests/ldlib2 no longer declares symbol ${as}`);
  return found;
};

const MINECRAFT_SCREEN: reflect.SymbolDeclaration[] = [
  { as: "minecraft", className: "net.minecraft.client.Minecraft", method: "getInstance", descriptor: "()Lnet/minecraft/client/Minecraft;", static: true },
  { as: "minecraft.screen", className: "net.minecraft.client.Minecraft", field: "screen" },
];

export const PANEL_ROUTES: Record<PanelHolder, reflect.SymbolDeclaration[]> = {
  screen: [...MINECRAFT_SCREEN, ...["screen.modularUI", "modularUI.ui", "ui.rootElement"].map(symbol)],
  container: [...MINECRAFT_SCREEN, ...["screen.menu", "menu.modularUI", "modularUI.ui", "ui.rootElement"].map(symbol)],
};

export function contentBox(element: Element): Box {
  return { x: element.contentX, y: element.contentY, width: element.contentWidth, height: element.contentHeight };
}

export async function carriedItem(client: MinecraftClient): Promise<{ id: string; count: number } | null> {
  const carried = (await input.cursor(client)).carried;
  if (!carried || typeof carried.id !== "string") {
    return null;
  }
  return { id: carried.id, count: typeof carried.count === "number" ? carried.count : 1 };
}

export async function clickSlot(client: MinecraftClient, slotIndex: number): Promise<input.ButtonResult> {
  const slots = await screen.slots(client);
  const slot = slots.find((candidate) => candidate.index === slotIndex);
  if (!slot) {
    throw new Error(`no slot with index ${slotIndex} on the open screen (${slots.length} slots)`);
  }
  return pressAtHovered(client, slot.x + 8, slot.y + 8);
}

/** Waits in the game until any screen is open; false when none opened within `timeoutMs`. */
export async function waitForAnyScreen(client: MinecraftClient, timeoutMs: number): Promise<boolean> {
  try {
    await client.waitUntil({
      name: "waitForAnyScreen",
      clock: "frame",
      check: "return ((net.minecraft.client.Minecraft) ctx.client()).screen != null;",
      timeoutMs,
    });
    return true;
  } catch (error) {
    if (isMinecraftError(error, "wait-timeout")) return false;
    throw error;
  }
}

type IntrospectTarget =
  | { kind: "entity"; uuid?: string; id?: number }
  | { kind: "blockentity"; position: Vec3 }
  | { kind: "handle"; handle: string };

/** Dumps an object (an entity, a block entity, a handle) as a value tree, within `limits`. */
export async function introspect(
  server: MinecraftServer,
  options: {
    target: IntrospectTarget;
    dimension?: string;
    limits?: reflect.ValueLimits;
  },
): Promise<{ found: boolean; tree: any }> {
  const { target, dimension, limits } = options;
  let handle: string | undefined;
  let pinned = false;
  if (target.kind === "handle") {
    handle = target.handle;
  } else {
    const root = await reflect.query(server, { root: target as reflect.Root, dimension, select: {}, handles: true });
    if (root.found === false || !root.tree?.handle) {
      return { found: false, tree: undefined };
    }
    handle = root.tree.handle;
    pinned = true;
  }
  try {
    const dumped = await reflect.invoke(server, {
      target: { kind: "static", className: "java.util.Objects" },
      method: "requireNonNull",
      args: [{ $handle: handle }],
      argTypes: ["java.lang.Object"],
      limits,
    });
    return { found: true, tree: dumped.returned };
  } finally {
    if (pinned) {
      await reflect.release(server, handle).catch(() => undefined);
    }
  }
}

/**
 * Presses at an LDLib2 element's centre once it is actionable, without checking what LDLib2 handed the press
 * to (for a press that replaces the panel before its mouse-down can be read back).
 */
export async function clickUnverified(
  client: MinecraftClient,
  selector: ElementSelector,
  options: { timeoutMs?: number } = {},
): Promise<input.ButtonResult> {
  const element = await waitForElement(client, selector, { state: "actionable", ...options });
  if (!element) {
    throw new Error(`LDLib2 element ${JSON.stringify(selector)} is not actionable`);
  }
  const { x, y } = aimPoint(element);
  return pressAtHovered(client, x, y);
}

export { panelElements };

/** world.waitFor that answers a timeout instead of throwing: `{matched, ticks, reason}`. */
export async function waitMatched(
  server: MinecraftServer,
  condition: string,
  options: world.WaitForOptions,
): Promise<{ matched: boolean; ticks: number; reason: string }> {
  try {
    const held = await world.waitFor(server, condition, options);
    return { matched: true, ticks: held.ticks, reason: "matched" };
  } catch (error) {
    if (!(error instanceof MinecraftTimeoutError)) throw error;
    const detail = error.detail as { reason?: string } | undefined;
    return { matched: false, ticks: error.ticks ?? options.maxTicks ?? 0, reason: detail?.reason ?? error.code };
  }
}

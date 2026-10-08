import type { UiWaitState } from "@izakyl/blockwright-ui";
import { defineTask, input, isMinecraftError, javaTask, reflect } from "@izakyl/blockwright-minecraft";
import type { CallOptions, MinecraftClient, MinecraftTimeoutError, ui } from "@izakyl/blockwright-minecraft";
import { GLFW_KEY_ENTER, formatDecimal as round, waitBudget } from "@izakyl/blockwright-minecraft/internal";
import { LDLIB2 } from "./module.ts";
import { HOVERED_PATH, LDLIB2_SYMBOLS, MOUSE_DOWN_PATH, PANEL_ROUTES, type PanelHolder } from "./routes.ts";
import type { Box, Element, ElementSelector } from "./rules.ts";

/**
 * Checks this game's LDLib2 has every member the package reads: `undefined` when it has, else one sentence
 * on what is missing (LDLib2 not installed, or a build that renamed something).
 */
export async function missingMembers(client: MinecraftClient, options: CallOptions = {}): Promise<string | undefined> {
  const checked = await reflect.symbols(client, LDLIB2_SYMBOLS, options);
  if (checked.missing === 0) {
    return undefined;
  }
  const missing = checked.symbols.filter((symbol) => !symbol.present);
  return `LDLib2 is missing ${missing.length} of ${checked.declared} members this adapter reads: `
    + missing.slice(0, 5).map((symbol) => `${symbol.as ?? symbol.className}${symbol.reason ? ` (${symbol.reason})` : ""}`).join("; ")
    + (missing.length > 5 ? `; and ${missing.length - 5} more` : "");
}

/** One read of the open panel. */
export interface PanelRead {
  holder: PanelHolder;
  /** The open screen's class. */
  screen: string;
  /** A maxNodes / maxDepth limit cut the tree short. */
  truncated: boolean;
  /** Preorder. */
  elements: Element[];
}

export interface PanelReadOptions extends CallOptions {
  /**
   * The deepest depth read, the root being depth 0 (as `reflect.query`'s maxDepth): the children of an
   * element at this depth are not read, and the read is marked truncated. Default 24.
   */
  maxDepth?: number;
  /** The most elements read; more marks the read truncated. Default 8000. */
  maxNodes?: number;
}

type PanelLimits = Pick<PanelReadOptions, "maxDepth" | "maxNodes">;

function panelArgs(options: PanelLimits = {}): Record<string, unknown> {
  return {
    routes: PANEL_ROUTES,
    ...(options.maxDepth !== undefined ? { maxDepth: options.maxDepth } : {}),
    ...(options.maxNodes !== undefined ? { maxNodes: options.maxNodes } : {}),
  };
}

const IMPORTS = ["blockwright.ldlib2.*"];

function runOptions({ signal, timeoutMs }: CallOptions): CallOptions {
  return { ...(signal ? { signal } : {}), ...(timeoutMs !== undefined ? { timeoutMs } : {}) };
}

/**
 * The UI read probe: answers `null` when the open screen holds no LDLib2 panel, else a {@link PanelRead}.
 * Every holder route is tried in the game, in one probe.
 */
export function panelProbe(options: PanelLimits = {}): ui.Probe {
  return { body: "return Ldlib2.read(ctx, args);", imports: IMPORTS, modules: [LDLIB2], args: panelArgs(options) };
}

/**
 * The elements of a read; throws on a tree a limit cut short (`truncated`: maxNodes / maxDepth), and on one
 * whose encoding was cut (an element that is not whole, e.g. a `{"$truncated": true}` marker), rather than
 * handing out a partial one.
 */
export function elementsOf(read: PanelRead): Element[] {
  const elements = wholeElements(read);
  if (read.truncated) {
    throw new Error(
      `the LDLib2 element tree was cut short at ${elements.length} elements (maxNodes / maxDepth); `
        + "raise them rather than asserting on a partial tree",
    );
  }
  return elements;
}

/** `read.elements`, checked to be whole: every element an object with its className and preorder index, none cut. */
function wholeElements(read: PanelRead): Element[] {
  const elements: unknown = read.elements;
  if (!Array.isArray(elements)) {
    throw new Error(`the LDLib2 panel read answered no element list (elements: ${JSON.stringify(elements)?.slice(0, 200)})`);
  }
  for (let at = 0; at < elements.length; at++) {
    const element = elements[at] as Record<string, unknown> | null;
    if (element === null || typeof element !== "object" || typeof element.className !== "string" || element.index !== at || cut(element)) {
      throw new Error(
        `the LDLib2 panel read of ${elements.length} elements was cut in encoding at element ${at} `
          + `(${JSON.stringify(element)?.slice(0, 200)}) — the rest of the list cannot be trusted`,
      );
    }
  }
  return elements as Element[];
}

/** Whether a JSON value carries one of the agent's limit markers (`$truncated`, `$more`). */
function cut(value: unknown): boolean {
  if (value === null || typeof value !== "object") {
    return false;
  }
  if (Array.isArray(value)) {
    return value.some(cut);
  }
  return Object.entries(value).some(([key, inner]) => key === "$truncated" || key === "$more" || cut(inner));
}

const PANEL = defineTask<Record<string, unknown>, PanelRead>({
  name: "ldlib2.panel",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ imports: IMPORTS, body: "return Ldlib2.panel(ctx, Args.of(ctx));" }),
});

/** The open panel, read in one client-thread turn. No panel ends `not-found`, naming the open screen. */
export async function readPanel(client: MinecraftClient, options: PanelReadOptions = {}): Promise<PanelRead> {
  const read = await client.run(PANEL, panelArgs(options), runOptions(options));
  wholeElements(read);
  return read;
}

/** The open panel's elements in preorder; throws when a limit cut the tree short. */
export async function panelElements(client: MinecraftClient, options: PanelReadOptions = {}): Promise<Element[]> {
  return elementsOf(await readPanel(client, options));
}


// ---- waiting, in the game ----

export interface WaitForElementOptions extends CallOptions {
  /** Default `actionable`. */
  state?: UiWaitState;
}

/** What a click needs, answered by the wait that found the element actionable. */
interface ElementTarget {
  element: Element;
  label: string;
  aim: { x: number; y: number };
  holder: PanelHolder;
  /** Handle of the ModularUI the element was found in. */
  ui: string;
  /** Handles of the element and its descendants (capped at 512). */
  subtree: string[];
  /** The handles among `ui` and `subtree` this target made (no one held them before); released after the action. */
  owned: string[];
}

const CHECK = "return Ldlib2.check(ctx, args);";

function waitSpec(selector: ElementSelector, state: UiWaitState, options: CallOptions, target: boolean) {
  return {
    name: "ldlib2.waitForElement",
    clock: "frame" as const,
    modules: [LDLIB2],
    imports: IMPORTS,
    args: { ...panelArgs(), selector, state, ...(target ? { target: true } : {}) },
    check: CHECK,
    ...(options.timeoutMs !== undefined ? { timeoutMs: options.timeoutMs } : {}),
    ...(options.signal ? { signal: options.signal } : {}),
  };
}

/**
 * Waits in the game — checked after every rendered frame, one task — until the element `selector` picks
 * is actionable (the default: drawn, opaque, with area, not clipped away, active) or absent (not there, or
 * not hittable). `timeoutMs` is the in-game budget (default 10000). Answers the element, or undefined once
 * it is absent. A timeout throws a MinecraftTimeoutError whose `last` says what the game last saw
 * (geometry, and the viewport clipping it).
 */
export async function waitForElement(
  client: MinecraftClient,
  selector: ElementSelector,
  options: WaitForElementOptions = {},
): Promise<Element | undefined> {
  const state = options.state ?? "actionable";
  const done = await client.waitUntil<{ element?: Element; absent?: boolean }>(waitSpec(selector, state, options, false));
  return state === "absent" ? undefined : done.value.element;
}

async function waitForTarget(client: MinecraftClient, selector: ElementSelector, options: CallOptions): Promise<ElementTarget> {
  return (await client.waitUntil<ElementTarget>(waitSpec(selector, "actionable", options, true))).value;
}

const RELEASE = defineTask<{ handles: string[] }, { released: number }>({
  name: "ldlib2.release",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ imports: IMPORTS, body: "return Ldlib2.release(ctx, Args.of(ctx));" }),
});

/** Waits for the element's target, runs `act` on it, then releases the handles the target pinned for itself. */
async function withTarget<T>(
  client: MinecraftClient,
  selector: ElementSelector,
  options: CallOptions,
  act: (target: ElementTarget) => Promise<T>,
): Promise<T> {
  const target = await waitForTarget(client, selector, options);
  try {
    return await act(target);
  } finally {
    if (target.owned?.length) {
      await client.run(RELEASE, { handles: target.owned }).catch(() => undefined);
    }
  }
}

// ---- acting ----

/** Frames drawn with the cursor in place before LDLib2's hovered element is trusted. */
export const HOVER_REFRESH_FRAMES = 2;

const SCROLL_INTO_VIEW_TURNS = 16;
const MAX_WHEEL_LINES = 10;
/**
 * LDLib2 animates a wheel turn over several frames; each re-read waits (in the game) for it to stop, this long
 * at most unless the caller gives a `timeoutMs`.
 */
const SCROLL_SETTLE_MS = 5_000;

interface Located {
  element: Element;
  label: string;
  clip?: Box;
  clipLabel?: string;
  clippingAncestors: number;
  revealed: boolean;
  revealedVertically: boolean;
}

const LOCATE = defineTask<Record<string, unknown>, Located>({
  name: "ldlib2.locate",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ imports: IMPORTS, body: "return Ldlib2.locate(ctx, Args.of(ctx));" }),
});

interface HoverAnswer {
  cursor: { x: number; y: number };
  hovered?: { class: string; handle: string } | null;
  within?: boolean;
}

const WHEEL = defineTask<{ x: number; y: number; yOffset: number; count: number }, unknown>({
  name: "ldlib2.wheel",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ imports: IMPORTS, body: "return Ldlib2.wheel(ctx, Args.of(ctx));" }),
});

const HOVER = defineTask<Record<string, unknown>, HoverAnswer>({
  name: "ldlib2.hover",
  side: "client",
  modules: [LDLIB2],
  source: javaTask({ imports: IMPORTS, body: "return Ldlib2.hover(ctx, Args.of(ctx));" }),
});

function hoverAt(client: MinecraftClient, at: { x: number; y: number }, frames: number, target?: ElementTarget, signal?: AbortSignal) {
  return client.run(HOVER, {
    x: at.x,
    y: at.y,
    frames,
    ...(target ? { ui: target.ui, hoveredPath: HOVERED_PATH, within: target.subtree } : {}),
  }, signal ? { signal } : {});
}

export interface ScrollIntoViewOptions extends CallOptions {
  /** Wheel turns before giving up; default 16. A turn wheels as many lines as the rest of the way looks to need (up to 10). */
  turns?: number;
  /** Frames drawn after each wheel turn before re-reading; default 2. */
  hoverFrames?: number;
}

/**
 * Wheels the viewport(s) clipping the element until it is wholly inside them (or as much of it as they
 * can show), re-reading it in the game after each turn. Throws, with the geometry, when it is not on the
 * panel, when its viewport is itself clipped away, when only horizontal scrolling would help, or when the
 * turns run out. `timeoutMs` is the in-game budget of each re-read — how long it may wait for the scroll to
 * stop moving (default 5000) — and, with some slack above it, the deadline of each re-read and wheel turn.
 * A re-read that runs out of budget throws, naming the element's last position and the turns so far.
 */
export async function scrollElementIntoView(
  client: MinecraftClient,
  selector: ElementSelector,
  options: ScrollIntoViewOptions = {},
): Promise<Element> {
  const turns = options.turns ?? SCROLL_INTO_VIEW_TURNS;
  const frames = options.hoverFrames ?? HOVER_REFRESH_FRAMES;
  const budget = waitBudget(options.timeoutMs, options.signal, SCROLL_SETTLE_MS);
  const settleMs = budget.wait.timeoutMs;
  const run = budget.run;
  const history: string[] = [];
  const label = JSON.stringify(selector);
  let direction: 1 | -1 = 1;
  let before: number | undefined;
  let pxPerLine: number | undefined;
  let lines = 1;
  let last: Located | undefined;

  for (let turn = 0; turn <= turns; turn++) {
    let at: Located;
    try {
      at = last = await client.run(LOCATE, { ...panelArgs(), selector, ...(turn === 0 ? {} : { afterFrames: frames, settleMs }) }, run);
    } catch (error) {
      if (!isMinecraftError(error, "wait-timeout")) {
        throw error;
      }
      const seen = (error as MinecraftTimeoutError).last as Partial<Located> | null | undefined;
      const where = seen?.element
        ? `it was last at y ${round(seen.element.y)}..${round(seen.element.y + seen.element.height)}`
          + (seen.clip ? ` in a viewport y ${round(seen.clip.y)}..${round(seen.clip.y + seen.clip.height)}` : "")
        : last
          ? `before this turn it was at y ${round(last.element.y)}..${round(last.element.y + last.element.height)} (no reading this turn)`
          : "no reading came back";
      throw new Error(
        `LDLib2 element ${label} did not stop moving within ${settleMs} ms after wheel turn ${turn - 1}: ${where}`
          + (history.length ? ` (${history.join("; ")})` : "")
          + " — the scroll kept animating, or each read of the panel took too long to see it settle",
        { cause: error },
      );
    }
    const { element: target, clip } = at;
    if (clip === undefined || at.revealed) {
      return target;
    }
    if (clip.width <= 0 || clip.height <= 0) {
      throw new Error(
        `LDLib2 element ${label} sits in a viewport that is itself clipped away (${at.clippingAncestors} clipping `
          + `ancestor(s) intersect to ${round(clip.width)}x${round(clip.height)} at ${round(clip.x)},${round(clip.y)}) — `
          + "scrolling the inner viewport cannot bring it back, the outer one has to move first",
      );
    }
    if (at.revealedVertically) {
      throw new Error(
        `LDLib2 element ${label} is clipped on x by ${at.clipLabel}: viewport x ${round(clip.x)}..${round(clip.x + clip.width)}, `
          + `element spans ${round(target.x)}..${round(target.x + target.width)} — a mouse wheel only scrolls vertically, `
          + "so this one has to be reached by widening the panel or by moving the horizontal scroller",
      );
    }
    if (before === undefined) {
      // Above the viewport: wheel up (positive) to bring it down.
      direction = target.y < clip.y ? 1 : -1;
    } else {
      const moved = Math.abs(target.y - before);
      // Only a move the wrong way flips the wheel (a turn at the end of the travel does not move it at all).
      const away = target.y < clip.y ? target.y < before : target.y > before;
      if (away) {
        direction = direction === 1 ? -1 : 1;
      } else if (moved > 0) {
        pxPerLine = moved / lines;
      }
    }
    before = target.y;
    // Once a turn has shown how far one line scrolls, wheel as many lines as the rest of the way needs.
    const remaining = target.y < clip.y ? clip.y - target.y : target.y + target.height - (clip.y + clip.height);
    lines = pxPerLine === undefined ? 1 : Math.min(MAX_WHEEL_LINES, Math.max(1, Math.ceil(remaining / pxPerLine)));
    const point = { x: Math.round(clip.x + clip.width / 2), y: Math.round(clip.y + clip.height / 2) };
    await client.run(WHEEL, { x: point.x, y: point.y, yOffset: direction, count: lines }, run);
    history.push(`turn ${turn}: element y ${round(target.y)}, wheel ${direction * lines} at ${point.x},${point.y}`);
  }

  const { element: target, clip } = last!;
  throw new Error(
    `LDLib2 element ${label} would not come wholly inside ${last!.clipLabel}'s viewport after ${turns} wheel turn(s): `
      + `viewport y ${round(clip!.y)}..${round(clip!.y + clip!.height)}, element still spans ${round(target.y)}..`
      + `${round(target.y + target.height)} (${history.join("; ")}) — either the viewport does not scroll far enough `
      + "or the wheel is not reaching it",
  );
}

export interface ElementActionOptions extends CallOptions {
  /** Wheel the element into view first. */
  scrollIntoView?: boolean;
  /** Scroll-into-view turns; default 16. */
  turns?: number;
  /** Frames drawn with the cursor in place before the hover is trusted; default 2. */
  hoverFrames?: number;
}

export interface HoverElementOptions extends ElementActionOptions {
  /** Default true: fail when LDLib2's hovered element is not the target or inside it. */
  verifyHover?: boolean;
}

export interface HoverElementResult {
  element: Element;
  x: number;
  y: number;
  /** What LDLib2 calls hovered afterwards. */
  hovered: { class: string; handle: string } | null;
}

/**
 * Waits (in the game, `timeoutMs` being the budget) until the element is actionable, puts the cursor on its
 * centre and checks, after the frames LDLib2 needs, that the element LDLib2 calls hovered is it or inside
 * it. With `verifyHover: false` the check is reported but not enforced.
 */
export async function hoverElement(
  client: MinecraftClient,
  selector: ElementSelector,
  options: HoverElementOptions = {},
): Promise<HoverElementResult> {
  if (options.scrollIntoView) {
    await scrollElementIntoView(client, selector, scrollOptions(options));
  }
  return withTarget(client, selector, options, async (target) => {
    const { x, y } = target.aim;
    const answer = await hoverAt(client, target.aim, options.hoverFrames ?? HOVER_REFRESH_FRAMES, target, options.signal);
    const hovered = answer.hovered ?? null;
    if (options.verifyHover !== false) {
      if (!hovered) {
        throw new Error(
          `moved the cursor to (${round(x)}, ${round(y)}) for ${JSON.stringify(selector)} (${target.label}), but LDLib2 `
            + "records no element as hovered — the mod reads what is hovered from real cursor geometry, so that pixel "
            + "is outside the whole panel",
        );
      }
      if (!answer.within) {
        throw new Error(
          `hovered (${round(x)}, ${round(y)}) aiming at ${JSON.stringify(selector)} (${target.label}), but LDLib2 says the `
            + `hovered element is ${describe(hovered)}, which is outside that element's subtree (${target.subtree.length} `
            + "handle(s)) — a hover cannot be dispatched by handle, so this would read a tooltip for the wrong element",
        );
      }
    }
    return { element: target.element, x, y, hovered };
  });
}

export interface ClickElementOptions extends ElementActionOptions {
  button?: input.MouseButtonName;
  modifiers?: input.KeyModifiers;
}

export interface ClickElementResult {
  element: Element;
  x: number;
  y: number;
  /** The press, with LDLib2's mouse-down element read right after it (`observed`). */
  press: input.ButtonResult;
}

/**
 * Waits (in the game) until the element is actionable, puts the cursor on its centre, then presses only
 * once LDLib2's hovered element is inside the element's subtree (re-checked each frame while an earlier
 * hover lingers, until `timeoutMs`), and checks LDLib2 handed the mouse-down to that subtree. The press and
 * the check use the ModularUI pinned by handle, so a button that replaces the screen on mouse-down is still
 * judged by the UI it was aimed at. Handles pinned only for the click are released when it finishes, so a
 * handle in `press.observed` may no longer resolve.
 */
export async function clickElement(
  client: MinecraftClient,
  selector: ElementSelector,
  options: ClickElementOptions = {},
): Promise<ClickElementResult> {
  if (options.scrollIntoView) {
    await scrollElementIntoView(client, selector, scrollOptions(options));
  }
  const started = Date.now();
  const timeoutMs = options.timeoutMs ?? 10_000;
  return withTarget(client, selector, options, async (target) => {
    const { x, y } = target.aim;
    const button = options.button ?? "left";
    const modifiers = options.modifiers !== undefined ? { modifiers: options.modifiers } : {};
    await hoverAt(client, target.aim, options.hoverFrames ?? HOVER_REFRESH_FRAMES, undefined, options.signal);

    // An earlier hover lingers until LDLib2 renders with the cursor here: the press waits (in the game, frame
    // by frame) for the hovered element to be inside the target's subtree, for what is left of the budget.
    let press: input.ButtonResult;
    try {
      press = await input.button(client, {
        button,
        action: "press",
        ...modifiers,
        hovered: {
          root: { kind: "handle", handle: target.ui, path: HOVERED_PATH },
          within: target.subtree,
          timeoutMs: Math.max(1, timeoutMs - (Date.now() - started)),
        },
        observe: { root: { kind: "handle", handle: target.ui, path: MOUSE_DOWN_PATH }, select: { id: PANEL_ROUTES.select.id! }, handles: true },
      });
    } catch (error) {
      if (!isMinecraftError(error, "hover-mismatch")) {
        throw error;
      }
      const hovered = ((error as { detail?: { hovered?: { class: string; handle?: string } | null } }).detail?.hovered) ?? null;
      throw new Error(
        `moved the cursor to (${round(x)}, ${round(y)}) for ${JSON.stringify(selector)} (${target.label}), but LDLib2 `
          + (hovered ? `kept ${describe(hovered)} hovered, which is outside that element's subtree` : "records no element as hovered")
          + ` (${target.subtree.length} handle(s)) — no click sent`,
        { cause: error },
      );
    }

    let hit: { class: string; id?: string; handle?: string } | undefined;
    try {
      const observed = press.observed;
      if (observed?.found && observed.tree) {
        const id = observed.tree.values?.id;
        hit = { class: observed.tree.class, ...(typeof id === "string" ? { id } : {}), ...(observed.tree.handle ? { handle: observed.tree.handle } : {}) };
      }
    } finally {
      await input.button(client, { button, action: "release", ...modifiers });
    }
    if (!hit) {
      throw new Error(`clicked (${round(x)}, ${round(y)}) for ${JSON.stringify(selector)}, but LDLib2 recorded no element for this mouse-down`);
    }
    if (hit.handle !== undefined && !target.subtree.includes(hit.handle)) {
      throw new Error(
        `clicked (${round(x)}, ${round(y)}) aiming at ${JSON.stringify(selector)} (${target.label}), but LDLib2 handed the `
          + `click to ${describe(hit)}, which is outside that element's subtree (${target.subtree.length} handle(s)) — `
          + "the target is laid out there but something else is on top",
      );
    }
    return { element: target.element, x, y, press };
  });
}

export interface TypeIntoElementOptions extends ClickElementOptions {
  /** Press Enter after typing. */
  submit?: boolean;
}

/** Clicks the element (focusing it) and types `text`. */
export async function typeIntoElement(
  client: MinecraftClient,
  selector: ElementSelector,
  text: string,
  options: TypeIntoElementOptions = {},
): Promise<ClickElementResult> {
  const clicked = await clickElement(client, selector, options);
  const run = options.signal ? { signal: options.signal } : {};
  await input.key(client, { text }, run);
  if (options.submit) {
    await input.key(client, { keyCode: GLFW_KEY_ENTER }, run);
  }
  return clicked;
}

/** What a hover or click passes on to the scroll before it: the in-game budget is the action's, not each turn's. */
function scrollOptions({ turns, hoverFrames, signal }: ElementActionOptions): ScrollIntoViewOptions {
  return { ...(turns !== undefined ? { turns } : {}), ...(hoverFrames !== undefined ? { hoverFrames } : {}), ...(signal ? { signal } : {}) };
}

function describe(element: { class: string; id?: string; handle?: string }): string {
  return element.class + (element.id ? ` id=${element.id}` : "") + (element.handle ? ` handle=${element.handle}` : "");
}


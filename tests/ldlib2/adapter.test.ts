import assert from "node:assert/strict";
import test from "node:test";
import { Run, type ModuleSpec, type Output, type RunEnd, type TaskSpec } from "@izakyl/blockwright-client";
import { MinecraftTimeoutError, SyncLedger, minecraft, ui } from "@izakyl/blockwright-minecraft";
import type { GameInstance } from "@izakyl/blockwright-minecraft";
import { UiWaitTimeoutError, type UiNode } from "@izakyl/blockwright-ui";
import {
  clickElement,
  elementsOf,
  hoverElement,
  readPanel,
  ldlib2Adapter,
  scrollElementIntoView,
  typeIntoElement,
  waitForElement,
  type Element,
  type PanelRead,
} from "./index.ts";
import { LDLIB2 } from "./module.ts";
import { ELEMENT_ROUTES, LDLIB2_SYMBOLS, PANEL_ROUTES, symbolPath } from "./routes.ts";
import { anchorOf, resolveAnchor } from "./rules.ts";

interface Answer {
  outputs?: unknown[];
  end?: RunEnd;
}

/** An in-memory client instance: records ensured modules and tasks, answers each run from `answer`. */
class FakeClient implements GameInstance {
  readonly instanceId = "client-1";
  readonly role = "client" as const;
  readonly ensured: string[][] = [];
  readonly tasks: TaskSpec[] = [];
  readonly instanceDir = "/nonexistent/client-1";
  readonly tracePath = "/nonexistent/client-1.ndjson";

  answer: (task: TaskSpec) => Answer;

  constructor(answer: (task: TaskSpec) => Answer) {
    this.answer = answer;
  }

  async ensureModules(modules: readonly ModuleSpec[]) {
    this.ensured.push(modules.map((module) => module.name));
    return { loaded: modules.map((module) => module.name), skipped: [] };
  }

  run<T = unknown>(task: TaskSpec): Run<T> {
    this.tasks.push(task);
    const answer = this.answer(task);
    return new Run<T>((controller) => {
      queueMicrotask(() => {
        for (const value of answer.outputs ?? []) controller.output({ kind: "json", value } as Output<T>);
        controller.end(answer.end ?? { status: "ok" });
      });
      return { cancel: async () => {} };
    });
  }

  args(marker: string): Array<Record<string, any>> {
    return this.tasks.filter((task) => task.source.includes(marker)).map((task) => task.args as Record<string, any>);
  }
}

function fake(answer: (task: TaskSpec) => Answer) {
  const instance = new FakeClient(answer);
  return { instance, mc: minecraft.client(instance, { sync: new SyncLedger() }) };
}

const failed = (code: string, detail: unknown = {}): Answer => ({ end: { status: "error", error: { code, message: code, detail } } });
const answered = (value: unknown): Answer => ({ outputs: [value] });

const PKG = "com.lowdragmc.lowdraglib2.gui.ui.elements.";

function el(depth: number, over: Partial<Element> = {}): Omit<Element, "index"> {
  const x = over.x ?? 0;
  const y = over.y ?? 0;
  const width = over.width ?? 20;
  const height = over.height ?? 20;
  return {
    className: `${PKG}UIElement`, x, y, width, height, contentX: x, contentY: y, contentWidth: width, contentHeight: height,
    visible: true, active: true, displayed: true, opacity: 1, overflowVisible: true, depth, ...over,
  };
}

function panel(...rows: Array<Omit<Element, "index">>): PanelRead {
  return { holder: "container", screen: "some.ContainerScreen", truncated: false, elements: rows.map((row, index) => ({ ...row, index })) };
}

// root
// ├─ #list (id)
// │  ├─ row            (no id)  -> #list/0
// │  │  └─ Button "Buy" -> #list/0/0
// │  └─ row            (no id)  -> #list/1
// │     └─ Button "Buy" (bw-role-tab) -> #list/1/0
// ├─ dup (id "dup") ×2, so neither anchors
// │  └─ Label           -> /1/0
// └─ grid (id)
//    └─ ItemSlot bound to menu slot 63
const PANEL = panel(
  el(0),
  el(1, { id: "list" }),
  el(2),
  el(3, { className: `${PKG}Button`, text: "Buy" }),
  el(2),
  el(3, { className: `${PKG}Button`, text: "Buy", classes: ["bw-role-tab", "big"] }),
  el(1, { id: "dup" }),
  el(2, { className: `${PKG}Label`, text: "hello" }),
  el(1, { id: "dup" }),
  el(1, { id: "grid" }),
  el(2, { className: `${PKG}ItemSlot`, x: 40, y: 40, width: 18, height: 18, slot: { index: 63, item: { id: "minecraft:diamond", count: 2 } } }),
);

const VANILLA = {
  screen: { className: "some.ContainerScreen", superClassName: "x", title: "t", width: 320, height: 240, closesOnEsc: true, pauses: false, container: true },
  widgets: [],
  slots: [62, 63].map((index) => ({ index, x: 8, y: 8, width: 16, height: 16, active: true })),
};

/** The host's read: p0 is the vanilla probe, p1 this adapter's. */
function reading(read: PanelRead | null) {
  return (task: TaskSpec): Answer => (task.source.includes("part(() ->")
    ? answered({ p0: { ok: VANILLA }, p1: { ok: read } })
    : answered(null));
}

function keys(nodes: UiNode[]): string[] {
  return nodes.flatMap((each) => [each.key, ...keys(each.children)]);
}

test("the read is one in-game probe carrying every route; nodes are keyed by id or anchor", async () => {
  const { instance, mc } = fake(reading(PANEL));
  const view = await ui.host([ldlib2Adapter]).read(mc);
  assert.equal(instance.tasks.length, 1);
  const task = instance.tasks[0]!;
  assert.match(task.source, /return Ldlib2\.read\(ctx, args\);/);
  assert.match(task.source, /import blockwright\.ldlib2\.\*;/);
  assert.deepEqual((task.args as any).p1, { routes: PANEL_ROUTES });
  assert.deepEqual(instance.ensured.at(-1), [
    "blockwright.minecraft.core", "blockwright.minecraft.core.client", "blockwright.minecraft.client", LDLIB2.name,
  ]);
  const layer = view.layers.find((each) => each.adapter === "ldlib2")!;
  assert.deepEqual(keys(layer.roots), [
    "/", "#list", "#list/0", "#list/0/0", "#list/1", "#list/1/0", "/1", "/1/0", "/2", "#grid", "#grid/0",
  ]);
  const byKey = new Map(keys(layer.roots).map((key, at) => [key, at]));
  const flat = (nodes: UiNode[]): UiNode[] => nodes.flatMap((node) => [node, ...flat(node.children)]);
  const nodes = flat(layer.roots);
  assert.equal(nodes[byKey.get("#list/0/0")!]!.role, "button");
  assert.equal(nodes[byKey.get("#list/1/0")!]!.role, "tab", "a bw-role class wins over the class name");
  assert.deepEqual(nodes[byKey.get("#grid/0")!]!.item, { id: "minecraft:diamond", count: 2 });
  // The ItemSlot draws menu slot 63 itself, so the vanilla entry for it is dropped; 62 stays.
  const vanilla = view.layers.find((each) => each.adapter === ui.ADAPTER_ID)!;
  assert.deepEqual(vanilla.roots.map((node) => node.key), [ui.menuSlotKey(62)]);
});

test("selectors prefer a unique id and fall back to the anchor, flagging a root-relative path as fragile", async () => {
  const { mc } = fake(reading(PANEL));
  const host = ui.host([ldlib2Adapter]);
  const view = await host.read(mc);
  const entries = [...view.refOf.values()].map((ref) => host.entry(ref)).filter((entry) => entry.adapter === "ldlib2");
  const selectors = entries.map((entry) => [entry.selector, entry.fragile]);
  assert.deepEqual(selectors.find(([selector]) => (selector as any).id === "grid"), [{ id: "grid" }, false]);
  assert.deepEqual(selectors.find(([selector]) => JSON.stringify((selector as any).path) === "[0,0]" && (selector as any).within === "list"),
    [{ within: "list", path: [0, 0] }, false]);
  assert.deepEqual(selectors.find(([selector]) => JSON.stringify((selector as any).path) === "[1,0]" && (selector as any).within === undefined),
    [{ path: [1, 0] }, true]);
});

test("an id-less element is anchored at its nearest uniquely-id'd ancestor and found again from there", () => {
  const elements = PANEL.elements;
  const buy = elements[5]!;
  assert.deepEqual(anchorOf(elements, buy), { within: "list", path: [1, 0] });
  assert.equal(resolveAnchor(elements, "list", [1, 0]), buy);
  assert.deepEqual(anchorOf(elements, elements[7]!), { path: [1, 0] });
});

test("no panel is simply not this adapter's screen; a failing probe is its error", async () => {
  const none = await ui.host([ldlib2Adapter]).read(fake(reading(null)).mc);
  assert.deepEqual(none.layers.map((layer) => layer.adapter), [ui.ADAPTER_ID]);
  assert.deepEqual(none.errors, []);
  const broken = await ui.host([ldlib2Adapter]).read(fake(() => answered({ p0: { ok: null }, p1: { error: { code: "exception", message: "boom" } } })).mc);
  assert.deepEqual(broken.errors, ["ldlib2: reading the LDLib2 panel failed (exception): boom"]);
  const cut = await ui.host([ldlib2Adapter]).read(fake(reading({ ...PANEL, truncated: true })).mc);
  assert.match(cut.errors[0]!, /cut short/);
});

test("a read whose encoding was cut fails loudly instead of handing out a partial element list", async () => {
  const elements = PANEL.elements.map((element) => ({ ...element })) as unknown[];
  elements[4] = { ...(elements[4] as object), width: { $truncated: true } };
  elements[5] = { $truncated: true };
  const encodedCut = { ...PANEL, elements } as PanelRead;
  assert.throws(() => elementsOf(encodedCut), /cut in encoding at element 4/);
  const hostRead = await ui.host([ldlib2Adapter]).read(fake(reading(encodedCut)).mc);
  assert.match(hostRead.errors[0]!, /cut in encoding at element 4/);
  await assert.rejects(readPanel(fake(() => answered(encodedCut)).mc), /cut in encoding at element 4/);
  assert.equal(elementsOf(PANEL), PANEL.elements);
});

test("the pure rules, routes and module are exported from the package root", async () => {
  const root = await import("./index.ts");
  for (const name of ["selectElement", "aimPoint", "ancestorsOf", "childrenOf", "clippingAncestors", "visibleBox", "isActionable",
    "isHittable", "elementsAtPoint", "elementLabel", "LDLIB2_SYMBOLS", "symbolPath", "elementsOf", "LDLIB2", "HOVER_REFRESH_FRAMES"]) {
    assert.ok(name in root, `${name} is exported`);
  }
  assert.equal(root.LDLIB2, LDLIB2);
  assert.equal(root.HOVER_REFRESH_FRAMES, 2);
});

test("verify checks every declared LDLib2 member and names what is missing", async () => {
  const { instance, mc } = fake(() => answered({ declared: 2, missing: 0, symbols: [] }));
  assert.equal(await ldlib2Adapter.verify(mc), undefined);
  assert.deepEqual(instance.args("Symbols.check")[0], { symbols: LDLIB2_SYMBOLS });
  instance.answer = () => answered({
    declared: 2, missing: 1,
    symbols: [{ as: "ui.rootElement", className: "x", present: false, reason: "no class" }, { as: "y", className: "y", present: true }],
  });
  assert.equal(await ldlib2Adapter.verify(mc), "LDLib2 is missing 1 of 2 members this adapter reads: ui.rootElement (no class)");
});

test("every element route and every path the Java walks is declared, so a renamed member goes red up front", () => {
  const declared = new Set(LDLIB2_SYMBOLS);
  for (const route of Object.values(ELEMENT_ROUTES)) {
    for (const symbol of route) assert.ok(declared.has(symbol), `${symbol.as} is declared`);
  }
  assert.deepEqual(PANEL_ROUTES.holders, { screen: "modularUI", container: "menu.getModularUI()" });
  assert.equal(PANEL_ROUTES.root, "ui.rootElement");
  assert.equal(PANEL_ROUTES.children, "getChildren()");
  assert.equal(PANEL_ROUTES.select.opacity, "getStyle().opacity()");
  assert.equal(PANEL_ROUTES.select.overflowVisible, "getStyle().overflowVisible()");
  assert.equal(PANEL_ROUTES.select.contentHeight, symbolPath(ELEMENT_ROUTES.contentHeight!));
});

test("waits are one in-game task on the frame clock; a timeout throws UiWaitTimeoutError with what was last seen", async () => {
  let timeout = false;
  const { instance, mc } = fake(() => (timeout
    ? failed("wait-timeout", { elapsedMs: 300, last: { elements: 4, target: { label: "row", clippedBy: { label: "port" } } } })
    : answered({ value: { element: PANEL.elements[1] }, ticks: 2, elapsedMs: 33 })));
  const host = ui.host([ldlib2Adapter]);
  const waited = await host.waitFor(mc, { adapter: "ldlib2", selector: { id: "list" } }, "actionable", { timeoutMs: 500 });
  assert.deepEqual(Object.keys(waited), ["elapsedMs"]);
  const task = instance.tasks.at(-1)!;
  assert.match(task.source, /return Ldlib2\.check\(ctx, args\);/);
  assert.equal(task.thread, "client");
  assert.deepEqual(task.args, { clock: "frame", args: { routes: PANEL_ROUTES, selector: { id: "list" }, state: "actionable" }, wait: { timeoutMs: 500 } });
  timeout = true;
  const missed = await host.waitFor(mc, { adapter: "ldlib2", selector: { id: "row" } }, "absent").then(
    () => assert.fail("the wait should have thrown"),
    (thrown: unknown) => thrown,
  );
  assert.ok(missed instanceof UiWaitTimeoutError);
  assert.equal(missed.elapsedMs, 300);
  assert.match(missed.detail!, /clippedBy/);
  assert.ok(missed.cause instanceof MinecraftTimeoutError);
  await assert.rejects(waitForElement(mc, { id: "row" }), MinecraftTimeoutError);
  timeout = false;
  assert.deepEqual(await waitForElement(mc, { id: "list" }), PANEL.elements[1]);
  assert.equal(await waitForElement(mc, { id: "list" }, { state: "absent" }), undefined);
});

const TARGET = {
  element: { ...el(3, { className: `${PKG}Button`, id: "confirm", x: 10, y: 20, width: 40, height: 12 }), index: 3 },
  label: "confirm",
  aim: { x: 30, y: 26 },
  holder: "container",
  ui: "h1",
  subtree: ["h7", "h8"],
  owned: ["h8"],
};

/** A client whose waits answer TARGET, whose presses go through `press` and whose other input succeeds. */
function pointer(press: (args: Record<string, any>) => Answer) {
  return fake((task) => {
    if (task.source.includes("Ldlib2.check")) return answered({ value: TARGET, ticks: 0, elapsedMs: 1 });
    if (task.source.includes("Ldlib2.hover")) {
      const args = task.args as Record<string, any>;
      return answered({ cursor: { x: args.x, y: args.y }, ...(args.ui ? { hovered: { class: `${PKG}Button`, handle: "h7" }, within: true } : {}) });
    }
    if (task.source.includes("Mouse.buttonTask")) return press(task.args as Record<string, any>);
    return answered({ at: "ok" });
  });
}

const pressed = (handle: string | null): Answer => answered({
  at: "mouse.button", action: "press",
  observed: handle ? { found: true, tree: { class: `${PKG}Button`, index: 0, handle, values: { id: "confirm" } } } : { found: false, tree: null },
});

test("a click waits for the target in the game, hovers it, presses only on it, and checks LDLib2's mouse-down", async () => {
  const { instance, mc } = pointer((args) => (args.action === "press" ? pressed("h8") : answered({ action: "release" })));
  const done = await clickElement(mc, { id: "confirm" }, { button: "right", modifiers: ["shift"] });
  assert.deepEqual({ x: done.x, y: done.y }, { x: 30, y: 26 });
  assert.equal((instance.args("Ldlib2.check")[0]!.args as any).target, true);
  assert.deepEqual(instance.args("Ldlib2.hover"), [{ x: 30, y: 26, frames: 2 }]);
  const [press, release] = instance.args("Mouse.buttonTask");
  const { hovered: { timeoutMs, ...hovered }, ...rest } = press as Record<string, any>;
  assert.deepEqual({ ...rest, hovered }, {
    button: "right", action: "press", modifiers: ["shift"],
    hovered: { root: { kind: "handle", handle: "h1", path: "getLastHoveredElement()" }, within: ["h7", "h8"] },
    observe: { root: { kind: "handle", handle: "h1", path: "getLastMouseDownElement()" }, select: { id: "getId()" }, handles: true },
  });
  assert.ok(timeoutMs > 0 && timeoutMs <= 10_000, "the press waits in the game for the rest of the budget");
  assert.deepEqual(release, { button: "right", action: "release", modifiers: ["shift"] });
  assert.deepEqual(instance.args("Ldlib2.release"), [{ handles: ["h8"] }], "the handles the target pinned for itself are released");
});

test("the press waits for a lingering hover in the game: one press, no re-hover round trips", async () => {
  const { instance, mc } = pointer((args) => (args.action === "release" ? answered({ action: "release" }) : pressed("h7")));
  await clickElement(mc, { id: "confirm" }, { timeoutMs: 4_000 });
  assert.deepEqual(instance.args("Ldlib2.hover").map((args) => args.frames), [2]);
  assert.deepEqual(instance.args("Mouse.buttonTask").map((args) => args.action), ["press", "release"]);
  assert.ok((instance.args("Mouse.buttonTask")[0] as any).hovered.timeoutMs <= 4_000);
});

test("a hover that never lands on the target sends no click, and says what kept the hover", async () => {
  const { instance, mc } = pointer((args) => (args.action === "release"
    ? answered({})
    : failed("hover-mismatch", { hovered: { class: `${PKG}Button`, handle: "h99" } })));
  await assert.rejects(clickElement(mc, { id: "confirm" }, { timeoutMs: 0 }), /kept com\.lowdragmc.*Button handle=h99 hovered.*no click sent/);
  assert.deepEqual(instance.args("Mouse.buttonTask").map((args) => args.action), ["press"]);
});

test("a mouse-down LDLib2 hands to something outside the target fails the click, after releasing", async () => {
  const outside = pointer((args) => (args.action === "press" ? pressed("h42") : answered({})));
  await assert.rejects(clickElement(outside.mc, { id: "confirm" }), /handed the click to .*handle=h42, which is outside/);
  assert.deepEqual(outside.instance.args("Mouse.buttonTask").map((args) => args.action), ["press", "release"]);
  const nothing = pointer((args) => (args.action === "press" ? pressed(null) : answered({})));
  await assert.rejects(clickElement(nothing.mc, { id: "confirm" }), /recorded no element for this mouse-down/);
});

test("hoverElement checks LDLib2's own hovered element against the target's subtree", async () => {
  const { instance, mc } = pointer(() => answered({}));
  const hovered = await hoverElement(mc, { id: "confirm" });
  assert.deepEqual(hovered.hovered, { class: `${PKG}Button`, handle: "h7" });
  assert.deepEqual(instance.args("Ldlib2.hover")[0], { x: 30, y: 26, frames: 2, ui: "h1", hoveredPath: "getLastHoveredElement()", within: ["h7", "h8"] });
  instance.answer = (task) => (task.source.includes("Ldlib2.hover")
    ? answered({ cursor: {}, hovered: { class: "other.Row", handle: "h3" }, within: false })
    : answered({ value: TARGET, ticks: 0, elapsedMs: 1 }));
  await assert.rejects(hoverElement(mc, { id: "confirm" }), /hovered element is other\.Row handle=h3, which is outside/);
  instance.answer = (task) => (task.source.includes("Ldlib2.hover")
    ? answered({ cursor: {}, hovered: null, within: false })
    : answered({ value: TARGET, ticks: 0, elapsedMs: 1 }));
  await assert.rejects(hoverElement(mc, { id: "confirm" }), /records no element as hovered/);
});

function located(y: number, clip = { x: 0, y: 100, width: 100, height: 100 }) {
  const element = { ...el(2, { id: "row", x: 0, y, width: 100, height: 20 }), index: 2 };
  const revealed = y >= clip.y && y + 20 <= clip.y + clip.height;
  return answered({ element, label: "row", clip, clipLabel: "port", clippingAncestors: 1, revealed, revealedVertically: revealed });
}

test("scrollElementIntoView wheels (one event per line) at the viewport's centre until the row is wholly inside, re-reading in the game", async () => {
  const ys = [260, 230, 170];
  const { instance, mc } = fake((task) => (task.source.includes("Ldlib2.locate") ? located(ys.shift()!) : answered({ at: "mouse.scroll" })));
  const row = await scrollElementIntoView(mc, { id: "row" });
  assert.equal(row.y, 170);
  assert.deepEqual(instance.args("Ldlib2.locate").map((args) => [args.afterFrames, args.settleMs]), [[undefined, undefined], [2, 5000], [2, 5000]]);
  // One line first; once that showed 30px a line, the 50px still to go take two.
  assert.deepEqual(instance.args("Ldlib2.wheel"), [{ x: 50, y: 150, yOffset: -1, count: 1 }, { x: 50, y: 150, yOffset: -1, count: 2 }]);
});

test("each re-read settles on the caller's timeoutMs, and a re-read that times out names where the row last was", async () => {
  const ys = [260, 230, 170];
  const { instance, mc } = fake((task) => (task.source.includes("Ldlib2.locate") ? located(ys.shift()!) : answered({})));
  await scrollElementIntoView(mc, { id: "row" }, { timeoutMs: 12_000 });
  assert.deepEqual(instance.args("Ldlib2.locate").map((args) => args.settleMs), [undefined, 12_000, 12_000]);
  assert.ok(instance.tasks.filter((task) => task.source.includes("Ldlib2.locate")).every((task) => task.timeoutMs === 17_000));

  let turn = 0;
  const slow = fake((task) => {
    if (!task.source.includes("Ldlib2.locate")) return answered({});
    if (turn++ === 0) return located(260);
    const last = { element: { ...el(2, { id: "row", y: 240, height: 20 }), index: 2 }, clip: { x: 0, y: 100, width: 100, height: 100 } };
    return failed("wait-timeout", { clock: "frame", reason: "timeoutMs", ticks: 1, elapsedMs: 800, last });
  });
  await assert.rejects(scrollElementIntoView(slow.mc, { id: "row" }, { timeoutMs: 800 }),
    /did not stop moving within 800 ms after wheel turn 0: it was last at y 240\.\.260 in a viewport y 100\.\.200 \(turn 0: element y 260/);
});

test("a row above the viewport is wheeled up, and a wheel that moves it the wrong way flips", async () => {
  const ys = [40, 30, 60, 100];
  const { instance, mc } = fake((task) => (task.source.includes("Ldlib2.locate") ? located(ys.shift()!) : answered({})));
  await scrollElementIntoView(mc, { id: "row" });
  assert.deepEqual(instance.args("Ldlib2.wheel").map((args) => args.yOffset * args.count), [1, -1, -2]);
});

test("a wheel turn that does not move the row does not flip the direction", async () => {
  const ys = [260, 260, 260, 250, 170];
  const { instance, mc } = fake((task) => (task.source.includes("Ldlib2.locate") ? located(ys.shift()!) : answered({})));
  await scrollElementIntoView(mc, { id: "row" });
  assert.deepEqual(instance.args("Ldlib2.wheel").map((args) => args.yOffset * args.count), [-1, -1, -1, -7]);
});

test("scrolling fails loudly with the geometry: turns run out, horizontal clipping, a clipped-away viewport", async () => {
  const stuck = fake((task) => (task.source.includes("Ldlib2.locate") ? located(260) : answered({})));
  await assert.rejects(scrollElementIntoView(stuck.mc, { id: "row" }, { turns: 2 }),
    /would not come wholly inside port's viewport after 2 wheel turn\(s\): viewport y 100\.\.200, element still spans 260\.\.280/);
  const sideways = fake(() => answered({
    element: { ...el(2, { id: "row", x: 150, y: 120, width: 100, height: 20 }), index: 2 },
    label: "row", clip: { x: 0, y: 100, width: 100, height: 100 }, clipLabel: "port", clippingAncestors: 1, revealed: false, revealedVertically: true,
  }));
  await assert.rejects(scrollElementIntoView(sideways.mc, { id: "row" }), /clipped on x by port.*only scrolls vertically/);
  const gone = fake(() => located(120, { x: 0, y: 100, width: 100, height: -40 }));
  await assert.rejects(scrollElementIntoView(gone.mc, { id: "row" }), /itself clipped away/);
});

test("typing clicks the element to focus it, then types and presses Enter", async () => {
  const { instance, mc } = pointer((args) => (args.action === "press" ? pressed("h7") : answered({})));
  await typeIntoElement(mc, { id: "confirm" }, "oak", { submit: true });
  assert.deepEqual(instance.args("Keys.key"), [{ text: "oak" }, { keyCode: 257 }]);
});

test("the adapter acts through clickElement / hoverElement / typeIntoElement, scrolling into view first", async () => {
  const ys = [120];
  const { instance, mc } = pointer((args) => (args.action === "press" ? pressed("h7") : answered({})));
  const base = instance.answer;
  instance.answer = (task) => (task.source.includes("Ldlib2.locate") ? located(ys[0]!) : base(task));
  const host = ui.host([ldlib2Adapter]);
  const clicked = await host.act(mc, { adapter: "ldlib2", selector: { id: "confirm" } }, { kind: "click", modifiers: ["ctrl"] }, { timeoutMs: 700 });
  assert.equal(clicked.summary, 'ctrl+clicked {"id":"confirm"}; LDLib2 took the mouse-down inside it');
  assert.deepEqual(clicked.point, { x: 30, y: 26 });
  assert.equal(instance.args("Ldlib2.locate").length, 1, "scrolled into view first (already there)");
  assert.equal(instance.args("Ldlib2.check").at(-1)!.wait.timeoutMs, 700);
  assert.equal(await ldlib2Adapter.act(mc, { id: "confirm" }, { kind: "click", double: true }, {}), undefined);
  assert.equal(await ldlib2Adapter.act(mc, { id: "confirm" }, { kind: "scroll", lines: 2 }, {}), undefined);
});

test("codegen names the new functions", () => {
  assert.deepEqual(ldlib2Adapter.codegen({ id: "confirm" }, { kind: "click", button: "right", modifiers: ["shift"] }), {
    imports: [{ from: "../ldlib2", names: ["clickElement"] }],
    code: 'await clickElement(client, {"id":"confirm"}, {"scrollIntoView":true,"button":"right","modifiers":["shift"]});',
  });
  assert.equal(ldlib2Adapter.codegen({ id: "confirm" }, { kind: "hover" })?.code, 'await hoverElement(client, {"id":"confirm"}, { scrollIntoView: true });');
  assert.equal(ldlib2Adapter.codegen({ within: "list", path: [0] }, { kind: "scrollIntoView" })?.code,
    'await scrollElementIntoView(client, {"within":"list","path":[0]});');
  assert.deepEqual(ldlib2Adapter.codegen({ id: "name" }, { kind: "type", text: "oak", submit: true }), {
    imports: [{ from: "../ldlib2", names: ["typeIntoElement"] }],
    code: 'await typeIntoElement(client, {"id":"name"}, "oak", {"scrollIntoView":true,"submit":true});',
  });
  assert.equal(ldlib2Adapter.codegen({ id: "confirm" }, { kind: "click", double: true }), undefined);
  assert.equal(ldlib2Adapter.codegen({ id: "confirm" }, { kind: "scroll", lines: 1 }), undefined);
});

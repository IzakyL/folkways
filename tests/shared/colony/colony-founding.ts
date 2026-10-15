import { drainActionBar, readActionBar } from "../ui/zone-receipt";
import { boardActs, scrollCellIntoView, wheelCell, type BoardAct } from "../ui/site-panel";
import {
  input,
  player,
  reflect,
  screen,
  tick,
  world,
  type MinecraftClient,
  type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { errorSummary } from "@izakyl/blockwright-client";
import { clickElement, panelElements } from "../../ldlib2";
import { ADMIT_SETTLERS, BIND_COLONY, MARK_MEMBERS, runColonyTask } from "./colony-tasks";
import {
  commandPos,
  frames,
  isSafeFailure,
  safeAction,
  tryCommand,
  safeScreen,
  screenName,
  screenOpen,
  pressAtHovered,
  waitForElement,
  clickUnverified,
  softly,
} from "../harness/bw-helpers";
import { GROUND, type Box, type Stage } from "../harness/stage";

/** Whether an `execute` condition holds now; throws when the game cannot evaluate it. */
export function probe(server: MinecraftServer, condition: string): Promise<boolean> {
  return world.probe(server, condition);
}

export {
  commandPos,
  errorSummary,
  tryCommand,
  safeAction,
  safeScreen,
  screenName,
  screenOpen,
};

export type Vec = { x: number; y: number; z: number };

export const TRADE_TOOLS = {
  farming: ["minecraft:wooden_hoe"],
  herding: ["minecraft:shears"],
  building: ["minecraft:wooden_pickaxe", "minecraft:wooden_axe", "minecraft:wooden_shovel"],
  fishing: ["minecraft:fishing_rod"],
} as const;

export function toolChestItems(tools: readonly string[], residents: number, firstSlot = 0): string {
  const entries: string[] = [];
  for (let resident = 0; resident < residents; resident++) {
    for (const tool of tools) {
      entries.push(`{Slot:${firstSlot + entries.length}b,id:"${tool}",count:1}`);
    }
  }
  return entries.join(",");
}

export async function stockToolsForResidents(
  server: MinecraftServer, chest: Vec, tools: readonly string[], sets: number, firstSlot = 0,
) {
  let slot = firstSlot;
  for (let set = 0; set < sets; set++) {
    for (const tool of tools) {
      await world.command(server,
        `item replace block ${commandPos(chest)} container.${slot} with ${tool} 1`);
      slot++;
    }
  }
  await tick.sprint(server, 5);
  return { chest, tools: [...tools], sets, slots: [firstSlot, slot - 1] };
}

const PANEL_SCREEN = /ModularUIContainerScreen/;

const RESIDENT_TYPE = "folkways:resident";
const BOOK_ITEM = "folkways:colony_book";
const DEFAULT_FOOD = "minecraft:cooked_beef";
const OPT_IN_ATTEMPTS = 4;
const TICKS_PER_DAY = 24000;
const GATHER_TICKS = 260;

const ADMIT_CALLS = 8;

const FOUNDING_PAD = 20;
const FOUNDING_HEIGHT = 6;
const WALL_HEIGHT = 3;

/** The cells a founding at `origin` clears and walls in: what a stage must lay under it. */
export function foundingBox(origin: Vec): Box {
  return {
    min: { x: origin.x - FOUNDING_PAD, y: origin.y, z: origin.z - FOUNDING_PAD },
    max: { x: origin.x + FOUNDING_PAD, y: origin.y + FOUNDING_HEIGHT, z: origin.z + FOUNDING_PAD },
  };
}

export type FoundingLayout = {
  origin: Vec;
  residentCount: number;
  foodItem?: string;
  /** Where the colony is founded; the ground unless a twin spec says otherwise. */
  stage?: Stage;
};

export type FoundingResult = {
  origin: Vec;
  bedFeet: Vec[];
  foodChest: Vec;
  standBlock: Vec;
  residents: number;
  evidence: any;
};

export async function foundColonyWithResidents(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  layout: FoundingLayout,
): Promise<FoundingResult> {
  const origin = layout.origin;
  const foodItem = layout.foodItem ?? DEFAULT_FOOD;
  const n = layout.residentCount;
  const stage = layout.stage ?? GROUND;
  const evidence: any = { origin, residentCount: n, foodItem, steps: [] };

  const bedFeet: Vec[] = [];
  for (let i = 0; i < n; i++) {
    bedFeet.push({ x: origin.x + i * 2, y: origin.y, z: origin.z });
  }
  const foodChest: Vec = { x: origin.x - 2, y: origin.y, z: origin.z };
  const standBlock: Vec = { x: origin.x - 4, y: origin.y, z: origin.z };

  const minX = origin.x - FOUNDING_PAD;
  const maxX = origin.x + FOUNDING_PAD;
  const minZ = origin.z - FOUNDING_PAD;
  const maxZ = origin.z + FOUNDING_PAD;
  const fill = (from: Vec, to: Vec, block: string) =>
    tryCommand(server, `fill ${commandPos(stage.at(from))} ${commandPos(stage.at(to))} ${block}`);
  await fill({ x: minX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + FOUNDING_HEIGHT, z: maxZ }, "minecraft:air");
  await fill({ x: minX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: minZ }, "minecraft:smooth_stone");
  await fill({ x: minX, y: origin.y, z: maxZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");
  await fill({ x: minX, y: origin.y, z: minZ }, { x: minX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");
  await fill({ x: maxX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");

  await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
  await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
  await tick.sprint(server, 5);

  evidence.steps.push(await createColonyViaBook(server, client, playerName, standBlock, stage));

  for (const foot of bedFeet) {
    await tryCommand(server, `setblock ${commandPos(stage.at(foot))} minecraft:red_bed[part=foot,facing=east]`);
    await tryCommand(server, `setblock ${commandPos(stage.at({ x: foot.x + 1, y: foot.y, z: foot.z }))} minecraft:red_bed[part=head,facing=east]`);
  }
  await tick.sprint(server, 3);
  for (const foot of bedFeet) {
    evidence.steps.push(await optInViaBook(server, client, playerName, foot, "bed", stage));
  }

  await tryCommand(server, `setblock ${commandPos(stage.at(foodChest))} minecraft:chest{Items:[]}`);
  await tick.sprint(server, 3);
  evidence.steps.push(await optInViaBook(server, client, playerName, foodChest, "chest", stage));
  const foodStacks = Math.max(2, Math.ceil((n * 16) / 64));
  for (let slot = 0; slot < foodStacks; slot++) {
    await tryCommand(server, `item replace block ${commandPos(stage.at(foodChest))} container.${slot} with ${foodItem} 64`);
  }
  await tick.sprint(server, 5);

  const residents = await admitSettlers(server, client, playerName, standBlock, n, n * 2 + 4, evidence, stage);
  evidence.finalResidents = residents;
  if (residents < n) {
    throw new Error(`founding: only ${residents}/${n} settlers admitted (evidence: ${JSON.stringify(evidence.steps.slice(-3))})`);
  }
  await tryCommand(server, "time set day");
  return { origin, bedFeet, foodChest, standBlock, residents, evidence };
}

export async function foundColonyFast(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  layout: FoundingLayout,
): Promise<FoundingResult> {
  const origin = layout.origin;
  const foodItem = layout.foodItem ?? DEFAULT_FOOD;
  const n = layout.residentCount;
  const stage = layout.stage ?? GROUND;
  const evidence: any = { origin, residentCount: n, foodItem, path: "task", steps: [] };

  const bedFeet: Vec[] = [];
  for (let i = 0; i < n; i++) {
    bedFeet.push({ x: origin.x + i * 2, y: origin.y, z: origin.z });
  }
  const foodChest: Vec = { x: origin.x - 2, y: origin.y, z: origin.z };
  const standBlock: Vec = { x: origin.x - 4, y: origin.y, z: origin.z };

  const minX = origin.x - FOUNDING_PAD;
  const maxX = origin.x + FOUNDING_PAD;
  const minZ = origin.z - FOUNDING_PAD;
  const maxZ = origin.z + FOUNDING_PAD;
  const fill = (from: Vec, to: Vec, block: string) =>
    tryCommand(server, `fill ${commandPos(stage.at(from))} ${commandPos(stage.at(to))} ${block}`);
  await fill({ x: minX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + FOUNDING_HEIGHT, z: maxZ }, "minecraft:air");
  await fill({ x: minX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: minZ }, "minecraft:smooth_stone");
  await fill({ x: minX, y: origin.y, z: maxZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");
  await fill({ x: minX, y: origin.y, z: minZ }, { x: minX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");
  await fill({ x: maxX, y: origin.y, z: minZ }, { x: maxX, y: origin.y + WALL_HEIGHT, z: maxZ }, "minecraft:smooth_stone");

  await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
  await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
  await tick.sprint(server, 5);

  const bind = await runColonyTask(server, BIND_COLONY, { player: playerName });
  const colonyId = String(bind.colony_id);
  evidence.steps.push({ step: "createColony", via: "task", ...bind });
  if (!(await actsForColony(server, playerName))) {
    throw new Error(
      `foundColonyFast: the task founded ${colonyId}, but ${playerName} has no folkways:current_colony `
        + `(task receipt ${JSON.stringify(bind)})`);
  }
  evidence.colonyId = colonyId;

  for (const foot of bedFeet) {
    await tryCommand(server, `setblock ${commandPos(stage.at(foot))} minecraft:red_bed[part=foot,facing=east]`);
    await tryCommand(server, `setblock ${commandPos(stage.at({ x: foot.x + 1, y: foot.y, z: foot.z }))} minecraft:red_bed[part=head,facing=east]`);
  }
  await tryCommand(server, `setblock ${commandPos(stage.at(foodChest))} minecraft:chest{Items:[]}`);
  await tick.sprint(server, 3);

  const cells = [...bedFeet, foodChest];
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player: playerName,
    colony_id: colonyId,
    cells: cells.map((cell) => stage.at(cell)).flatMap((cell) => [cell.x, cell.y, cell.z]),
  });
  evidence.steps.push({ step: "optIn", via: "task", ...marked });
  for (const cell of cells) {
    if (!(await isColonyMember(server, cell, stage))) {
      throw new Error(
        `foundColonyFast: ${commandPos(stage.at(cell))} is still not a colony member after MemberToggle.toggleAt. `
          + `The task itself read ${JSON.stringify(marked.cells)}; `
          + `if refusal is set there, the block simply cannot join, panel or not`);
    }
  }

  const foodStacks = Math.max(2, Math.ceil((n * 16) / 64));
  for (let slot = 0; slot < foodStacks; slot++) {
    await tryCommand(server, `item replace block ${commandPos(stage.at(foodChest))} container.${slot} with ${foodItem} 64`);
  }
  await tick.sprint(server, 5);

  let residents = await countResidents(server);
  for (let call = 0; call < ADMIT_CALLS && residents < n; call++) {
    const admitted = await runColonyTask(server, ADMIT_SETTLERS, {
      player: playerName,
      colony_id: colonyId,
      count: n - residents,
    });
    await tick.sprint(server, 20);
    residents = await countResidents(server);
    evidence.steps.push({ step: "admit", via: "task", call, residents, ...admitted });
  }
  evidence.finalResidents = residents;
  if (residents < n) {
    throw new Error(
      `foundColonyFast: only recruited ${residents}/${n} residents. `
        + `Endorsements.called receipts: ${JSON.stringify(evidence.steps.slice(-2))} - `
        + `whichever refusal is set is what kept them out`);
  }
  await tryCommand(server, "time set day");
  return { origin, bedFeet, foodChest, standBlock, residents, evidence };
}

export async function razeFoundingSet(server: MinecraftServer, origin: Vec, stage: Stage = GROUND) {
  const min = { x: origin.x - FOUNDING_PAD, y: origin.y, z: origin.z - FOUNDING_PAD };
  const max = { x: origin.x + FOUNDING_PAD, y: origin.y + FOUNDING_HEIGHT, z: origin.z + FOUNDING_PAD };
  await world.command(server, `fill ${commandPos(stage.at(min))} ${commandPos(stage.at(max))} minecraft:air`);
  await tick.sprint(server, 5);
  await tryCommand(server, `kill @e[type=minecraft:item,x=${min.x},y=${min.y - 1},z=${min.z},`
    + `dx=${max.x - min.x},dy=${max.y - min.y + 1},dz=${max.z - min.z}]`);
  await tick.sprint(server, 5);
  const left = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["minecraft:item"], limit: 200 })
    .catch(() => ({ entities: [] }));
  return { min, max, itemsLeft: (left.entities ?? []).length };
}

/** Whether the player has a colony they act for: the one any colony book in hand reaches. */
export function actsForColony(server: MinecraftServer, playerName: string): Promise<boolean> {
  return probe(server, `if data entity ${playerName} "neoforge:attachments"."folkways:current_colony"`);
}

export async function createColonyViaBook(server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: Vec,
  stage: Stage = GROUND) {
  const panel = await openColonyPanelViaBook(server, client, playerName, standBlock, stage);
  // A player who already keeps a colony opens on its pages; the panel's title lists their colonies and New Colony.
  const elements = await panelElements(client);
  if (!elements.some((element) => element.id === "folkways.colony.create" && element.width > 0)) {
    await clickElement(client, { id: "folkways.charter.heading" });
    await tick.sprint(server, 2);
  }
  const click = await clickElement(client, { id: "folkways.colony.create" });
  await tick.sprint(server, 10);
  await screen.dismiss(client);
  const bound = await actsForColony(server, playerName);
  if (!bound) {
    throw new Error(`createColony: clicked folkways.colony.create, but ${playerName} acts for no colony`
      + ` (panel ${screenName(panel)}, click receipt ${JSON.stringify(summarize(click))})`);
  }
  return { step: "createColony", click: summarize(click), bound };
}

export type BookGesture = "point" | "box" | "line";

const GESTURE_RECEIPT: Record<BookGesture, RegExp> = {
  point: /^Book: taking blocks and bodies in$/,
  box: /^Book: marking out ground$/,
  line: /^Book: drawing a line$/,
};

const STANDING_EYE_HEIGHT = 1.5;

/**
 * Waits for the released sneak key to reach the player: a book left click while still crouched drops the
 * drawing instead of marking a corner, and a loaded client can take several frames to stand up.
 */
async function standUp(client: MinecraftClient, timeoutMs = 5_000): Promise<number> {
  const deadline = Date.now() + timeoutMs;
  let eyeHeight = NaN;
  while (true) {
    const state: any = await safeAction(() => player.state(client));
    eyeHeight = Number(state?.eyeHeight);
    if (eyeHeight > STANDING_EYE_HEIGHT) return eyeHeight;
    if (Date.now() > deadline) {
      throw new Error(`setBookGesture: the player still crouches ${timeoutMs}ms after sneak was released (eye height ${eyeHeight})`);
    }
    await safeAction(() => frames(client, 2));
  }
}

export async function setBookGesture(server: MinecraftServer, client: MinecraftClient, playerName: string, gesture: BookGesture) {
  const step: any = { gesture, attempts: [] };
  await safeAction(() => screen.dismiss(client));
  step.sneak = await safeAction(() => input.key(client, { keybind: "key.sneak", action: "press" }));
  try {
    for (let attempt = 1; attempt <= 4; attempt++) {
      await drainActionBar(client);
      await safeAction(() => frames(client, 20));
      await tick.sprint(server, 10);
      const scroll = await safeAction(() => input.scroll(client, { yOffset: 1 }));
      await tick.sprint(server, 5);
      const bar = await readActionBar(client);
      step.attempts.push({ attempt, scroll, bar });
      if (GESTURE_RECEIPT[gesture].test(bar.text) && bar.remaining_ticks > 0) {
        step.switched = true;
        break;
      }
    }
  } finally {
    await safeAction(() => input.key(client, { keybind: "key.sneak", action: "release" }));
  }
  if (step.switched) {
    step.eye_height = await standUp(client);
    return step;
  }
  throw new Error(
    `setBookGesture(${gesture}): the action bar never reported this mode - sneak press receipt ${JSON.stringify(step.sneak)}, `
      + `then ${JSON.stringify(step.attempts)}`);
}

export async function optInViaBook(server: MinecraftServer, client: MinecraftClient, playerName: string, target: Vec, kind: "bed" | "chest" | "worksite",
  stage: Stage = GROUND) {
  const step: any = { target, kind, attempts: [] };
  if (await isColonyMember(server, target, stage)) {
    step.member = true;
    step.attempts.push({ attempt: 0, action: "already-member" });
    return step;
  }
  step.mode = await setBookGesture(server, client, playerName, "point");
  for (let attempt = 1; attempt <= OPT_IN_ATTEMPTS; attempt++) {
    if (await isColonyMember(server, target, stage)) {
      step.member = true;
      step.attempts.push({ attempt, action: "already-member" });
      await screen.dismiss(client);
      return step;
    }
    const seen = stage.stand(target);
    await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await screen.dismiss(client);
    const use = await softly(() => stage.click({ server, client }, target, { player: playerName }));
    await tick.sprint(server, 5);
    const memberAfter = await isColonyMember(server, target, stage);
    step.attempts.push({ attempt, use: summarize(use), memberAfter });
    if (memberAfter) {
      step.member = true;
      return step;
    }
  }
  throw new Error(`opt-in(${kind}): ${target.x},${target.y},${target.z} never became a colony member after ${OPT_IN_ATTEMPTS} attempts`);
}

export async function openColonyPanelViaBook(server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: Vec,
  stage: Stage = GROUND) {
  const seen = stage.stand(standBlock);
  await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
  await tick.sprint(server, 5);
  await screen.dismiss(client);
  await safeAction(() => player.useBlock(client, stage.at(standBlock), { face: "up" }));
  await softly(() => screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 10_000 }));
  const panel = await safeScreen(client);
  if (isSafeFailure(panel)) {
    throw new Error(
      `panel: the client is not responding (${screenName(panel)}) - no way to tell whether the panel opened, since it can no longer be asked. `
      + `Check this instance's crash-reports/ and client trace first; do not tune the panel timeout.`);
  }
  if (!screenOpen(panel) || !PANEL_SCREEN.test(screenName(panel))) {
    throw new Error(`panel: ColonyShell did not open (got ${screenName(panel)})`);
  }
  return panel;
}

const PRIORITY_NOTCHES = 4;

async function vocationHandle(server: MinecraftServer, vocation: string): Promise<string> {
  const parsed = await reflect.invoke(server, {
    target: { kind: "static", className: "net.minecraft.resources.ResourceLocation" },
    method: "parse",
    args: [vocation],
    argTypes: ["java.lang.String"],
    returnHandle: true,
    limits: { maxDepth: 1 },
  });
  const id = parsed?.handle;
  if (!id) {
    throw new Error(`vocation ${vocation}: ResourceLocation.parse returned no handle (${JSON.stringify(parsed)})`);
  }
  const required = await reflect.invoke(server, {
    target: { kind: "static", className: "io.github.izakyl.folkways.core.api.vocation.Vocations" },
    method: "required",
    args: [{ $handle: id }],
    argTypes: ["net.minecraft.resources.ResourceLocation"],
    returnHandle: true,
    limits: { maxDepth: 1 },
  });
  const handle = required?.handle;
  if (!handle) {
    throw new Error(`vocation ${vocation}: Vocations.required returned no handle (${JSON.stringify(required)})`);
  }
  return handle;
}

export async function vocationPriority(server: MinecraftServer, residentUuid: string, vocation: string): Promise<number> {
  const licence = await reflect.invoke(server, {
    target: { kind: "entity", uuid: residentUuid },
    path: "licences()",
    method: "of",
    args: [{ $handle: await vocationHandle(server, vocation) }],
    argTypes: ["io.github.izakyl.folkways.core.api.vocation.Vocation"],
    returnType: "java.util.Optional",
    returnHandle: true,
    limits: { maxDepth: 1 },
  });
  const optional = licence?.handle;
  if (!optional) {
    throw new Error(`${vocation}: Licences.of returned no handle (${JSON.stringify(licence)})`);
  }
  const present = reflect.boolean(await reflect.invoke(server, {
    target: { kind: "handle", handle: optional },
    method: "isPresent",
    returnType: "boolean",
    limits: { maxDepth: 1 },
  }));
  if (!present) {
    return 0;
  }
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: optional },
    path: "get().keenness()",
    method: "number",
    returnType: "int",
    limits: { maxDepth: 1 },
  });
  return reflect.number(result);
}

export async function setVocationPriorityViaPanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: Vec,
  residentUuid: string, vocation: string, target: number,
) {
  const rowId = `folkways.resident.row.${residentUuid.slice(0, 8)}`;
  const cellId = `folkways.resident.vocation.${vocation.replace(/[:/]/g, ".")}.${residentUuid.slice(0, 8)}`;
  const before = await vocationPriority(server, residentUuid, vocation);
  const steps: any = { row: rowId, cell: cellId, before, target, gestures: 0 };
  let current = before;
  try {
    steps.open = await openColonyPanelViaBook(server, client, playerName, standBlock);
    steps.tab = await softly(() => clickElement(client, { id: "folkways.tab.residents" }));
    await waitForElement(client, { id: rowId }, { timeoutMs: 10_000 });
    await waitForElement(client, { id: cellId }, { timeoutMs: 8_000 });
    steps.scrolled = await scrollCellIntoView(server, client, rowId, cellId);
    if ((target === 0) !== (current === 0)) {
      steps.toggle = await softly(() => clickElement(client, { id: cellId }));
      steps.gestures++;
      await tick.sprint(server, 5);
      current = await vocationPriority(server, residentUuid, vocation);
      steps.after_toggle = current;
    }
    for (let turn = 0; turn < PRIORITY_NOTCHES && current !== target && target !== 0; turn++) {
      await scrollCellIntoView(server, client, rowId, cellId);
      steps[`scroll_${turn}`] = await wheelCell(server, client, cellId, target > current ? 1 : -1);
      steps.gestures++;
      await tick.sprint(server, 5);
      current = await vocationPriority(server, residentUuid, vocation);
      steps[`after_${turn}`] = current;
    }
  } finally {
    await screen.dismiss(client);
  }
  const after = await vocationPriority(server, residentUuid, vocation);
  return { ok: after === target, before, after, target, steps };
}

const SELECTOR_CLOSE = "folkways.selector.close";
const SELECTOR_SEARCH = "folkways.selector.search";
const SELECTOR_RESULT = "folkways.selector.result.";
const SELECTOR_CONFIRM = "folkways.selector.confirm";

// Opens the item picker from the control that asks for it, then picks one item there and confirms.
export async function pickViaSelector(
  client: MinecraftClient,
  opener: string,
  registryId: string,
  query = "",
) {
  const evidence: any = { opener, open: await clickUnverified(client, { id: opener }) };
  Object.assign(evidence, await chooseInSelector(client, registryId, query, evidence.open));
  await waitForElement(client, { id: SELECTOR_CLOSE }, { state: "absent", timeoutMs: 8_000 });
  return evidence;
}

export async function chooseInSelector(
  client: MinecraftClient,
  registryId: string,
  query = "",
  opened: unknown = null,
) {
  const resultId = SELECTOR_RESULT + registryId.replace(/[:/]/g, ".");
  const evidence: any = { registry_id: registryId, result_id: resultId, query };
  try {
    await waitForElement(client, { id: SELECTOR_CLOSE }, { timeoutMs: 8_000 });
  } catch (error) {
    const elements = await panelElements(client);
    throw new Error(
      `the selector did not open. ${error instanceof Error ? error.message : String(error)}\n`
        + `the open attempt returned ${JSON.stringify(opened)}; the panel had ${elements.length} elements, `
        + `${JSON.stringify(elements.filter((e) => e.id?.startsWith("folkways.selector")).map((e) => e.id))} of them the picker's. `
        + `The picker opens on a click of the setting's button, from its rows or the board's edit act.`,
    );
  }
  if (query) {
    await searchSelector(client, query, resultId, evidence);
  } else {
    try {
      await waitForElement(client, { id: resultId }, { timeoutMs: 8_000 });
    } catch (error) {
      throw await selectorMiss(client, error, evidence);
    }
  }
  evidence.pick = await clickUnverified(client, { id: resultId });
  // Picked, or ticked as a rule, the choice stands once it is confirmed.
  evidence.confirm = await clickUnverified(client, { id: SELECTOR_CONFIRM });
  return evidence;
}

const SEARCH_ATTEMPTS = 3;
const KEY_BACKSPACE = 259;
const KEY_END = 269;

async function searchSelector(client: MinecraftClient, query: string, resultId: string, evidence: any) {
  evidence.attempts = [];
  let miss: unknown;
  for (let attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
    const tried: any = { attempt };
    evidence.attempts.push(tried);
    tried.focus = await clickUnverified(client, { id: SELECTOR_SEARCH });
    if (attempt > 0) {
      await input.key(client, { keyCode: KEY_END });
      for (let stroke = 0; stroke < query.length + 4; stroke++) {
        await input.key(client, { keyCode: KEY_BACKSPACE });
      }
    }
    tried.search = await input.key(client, { text: query });
    try {
      await waitForElement(client, { id: resultId }, { timeoutMs: 4_000 });
      return;
    } catch (error) {
      miss = error;
    }
  }
  throw await selectorMiss(client, miss, evidence);
}

async function selectorMiss(client: MinecraftClient, error: unknown, evidence: unknown) {
  const seen = (await panelElements(client)).map((element) => element.id || element.className);
  return new Error(`${String(error)}\nevidence: ${JSON.stringify(evidence)}`
    + `\nselector element tree right now: ${JSON.stringify(seen)}`);
}

const SETTLERS_TAB = "folkways.page.person";
const SETTLERS_BOARD = "folkways.person";

async function admitAct(
  server: MinecraftServer, client: MinecraftClient, attempts = 20,
): Promise<BoardAct> {
  let seen: BoardAct[] = [];
  for (let attempt = 0; attempt < attempts; attempt++) {
    seen = await boardActs(client, "admit", SETTLERS_BOARD);
    if (seen.length > 0) {
      return seen[0];
    }
    await safeAction(() => frames(client, 3));
    await tick.sprint(server, 5);
  }
  throw new Error(`admit: no Board.Act.Admit on the ${SETTLERS_TAB} page`
    + ` (admit actions read: ${JSON.stringify(seen.map((one) => one.id))})`);
}

export async function admitSettlers(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  standBlock: Vec,
  target: number,
  rounds: number,
  evidence?: any,
  stage: Stage = GROUND,
): Promise<number> {
  let residents = await countResidents(server);
  if (residents >= target) return residents;

  await openColonyPanelViaBook(server, client, playerName, standBlock, stage);
  await softly(() => clickElement(client, { id: SETTLERS_TAB }));

  for (let round = 0; round < rounds && residents < target; round++) {
    const before = residents;
    const act = await admitAct(server, client);
    const press = await softly(() => clickElement(client, { id: act.id }));
    residents = await sprintUntilResidentCountRises(server, before);
    if (residents === before) {
      await tryCommand(server, `time add ${TICKS_PER_DAY}`);
      await tick.sprint(server, GATHER_TICKS);
    }
    if (evidence) evidence.steps.push({ admitRound: round, act: act.id, press, residents });
  }
  await screen.dismiss(client);
  return residents;
}

async function sprintUntilResidentCountRises(
  server: MinecraftServer,
  before: number,
  { maxTicks = 200, burst = 20 }: { maxTicks?: number; burst?: number } = {},
): Promise<number> {
  let count = await countResidents(server);
  for (let ticks = 0; ticks < maxTicks && count <= before; ticks += burst) {
    await tick.sprint(server, burst);
    count = await countResidents(server);
  }
  return count;
}

export async function waitForGroundedPlayer(server: MinecraftServer): Promise<{ name: string; pos: Vec }> {
  let last: any = null;
  for (let i = 0; i < 60; i++) {
    const players = await world.players(server).catch(() => []);
    const p = players[0];
    if (p?.position) {
      const grounded = p.onGround === true;
      const stable = last && Math.abs(last.y - p.position.y) < 0.05;
      if (grounded && stable) {
        return { name: p.name ?? "BlockwrightBot", pos: p.position };
      }
      last = p.position;
    }
    await tick.sprint(server, 10);
  }
  const players = await world.players(server).catch(() => []);
  const p = players[0] ?? { name: "BlockwrightBot", position: { x: 0, y: 64, z: 0 } };
  return { name: p.name ?? "BlockwrightBot", pos: p.position ?? { x: 0, y: 64, z: 0 } };
}

export async function countResidents(server: MinecraftServer): Promise<number> {
  let result;
  try {
    // Filtered by type on the server: an unfiltered read stops at its limit, and a big world's animals and golems would
    // crowd residents out of it.
    result = await world.entities(server, { dimension: "minecraft:overworld", types: [RESIDENT_TYPE], limit: 1000 });
  } catch {
    return 0;
  }
  if (result.limited) throw new Error("more than 1000 residents in the overworld: countResidents cannot count them all");
  return (result.entities ?? []).filter((e: any) => entityTypeMatches(e.type, RESIDENT_TYPE)).length;
}

export async function isColonyMember(server: MinecraftServer, target: Vec, stage: Stage = GROUND): Promise<boolean> {
  return world.probe(server, `if data block ${commandPos(stage.at(target))} "neoforge:attachments"."folkways:colony_member"`);
}

export function entityTypeMatches(actual: string | null | undefined, expected: string) {
  if (actual === null || actual === undefined) {
    throw new Error(
      `entity type did not resolve to a namespaced id (got ${JSON.stringify(actual)}, expected ${expected}). ` +
        "BW could not resolve this entity through the registry; check the entity's typeRaw field. " +
        "This is NOT the same as the entity not matching.",
    );
  }
  return actual === expected;
}

function summarize(r: any) {
  return r?.status ?? (r ? "ok" : "null");
}

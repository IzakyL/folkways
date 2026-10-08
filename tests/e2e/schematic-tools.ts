import { copyFileSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { input, media, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import type { Vec } from "./colony-founding";
import { drawTexts, type DrawCaptureResult } from "./draw-capture";
import { drainActionBar, readActionBar } from "./zone-receipt";
import { clickUnverified, frames, isSafeFailure, safeAction, safeScreen, screenName, softly, waitForElement } from "../shared/bw-helpers";

export const SCHEMATIC_ITEM = "create:schematic";

export const TOOL_MENU_KEY = 342;

export const HANDOFF_LABEL = "Hand Off";

const MAX_NOTCHES = 8;

export function placeSchematicFile(client: MinecraftClient, source: string, name: string) {
  const dir = path.join(client.instance.instanceDir, "schematics");
  mkdirSync(dir, { recursive: true });
  copyFileSync(source, path.join(dir, name));
  return { name, dir, path: path.join(dir, name) };
}

export function deployedSchematic(name: string, owner: string, anchor: Vec, bounds: Vec) {
  return (
    `${SCHEMATIC_ITEM}[create:schematic_file="${name}",create:schematic_owner="${owner}",` +
    `create:schematic_deployed=true,create:schematic_anchor=[I;${anchor.x},${anchor.y},${anchor.z}],` +
    `create:schematic_bounds=[I;${bounds.x},${bounds.y},${bounds.z}],` +
    `create:schematic_rotation="none",create:schematic_mirror="none"]`
  );
}

export async function equipHandoffTool(client: MinecraftClient, options: { whileFocused?: () => Promise<void> } = {}) {
  await safeAction(() => frames(client, 20));
  const focus = await safeAction(() =>
    input.key(client, { keyCode: TOOL_MENU_KEY, action: "press" }),
  );
  await safeAction(() => frames(client, 10));
  const seen: string[][] = [];
  try {
    for (let notch = 1; notch <= MAX_NOTCHES; notch++) {
      await safeAction(() => input.scroll(client, { yOffset: 1 }));
      await safeAction(() => frames(client, 12));
      const drawn = await hudTexts(client);
      seen.push(drawn);
      if (drawn.includes(HANDOFF_LABEL)) {
        if (options.whileFocused) await options.whileFocused();
        return { focus, notches: notch, drawn };
      }
    }
  } finally {
    await safeAction(() =>
      input.key(client, { keyCode: TOOL_MENU_KEY, action: "release" }),
    );
    await safeAction(() => frames(client, 6));
  }
  throw new Error(
    `scrolled all ${MAX_NOTCHES} notches without selecting the folkways entry (${HANDOFF_LABEL}). Each notch drew: ` +
      JSON.stringify(seen),
  );
}

export async function hudTexts(client: MinecraftClient) {
  const recording = await media.startDraws(client, { channel: "hud", maxFrames: 8 });
  await safeAction(() => frames(client, 4));
  const capture = (await recording.stop()) as DrawCaptureResult;
  return [...new Set(drawTexts(capture).map((entry) => entry.text))];
}

export async function rightClickWorld(client: MinecraftClient) {
  const press = await safeAction(() =>
    input.button(client, { button: "right", action: "press" }),
  );
  const release = await safeAction(() =>
    input.button(client, { button: "right", action: "release" }),
  );
  return { press, release };
}

export const HANDOFF_WINDOW = "folkways.window.blueprint_handoff";

export async function openHandoffScreen(client: MinecraftClient) {
  const click = await rightClickWorld(client);
  const window = await softly(() => waitForElement(client, { id: HANDOFF_WINDOW }, { timeoutMs: 8_000 }));
  const seen = await safeScreen(client);
  return { click, screen: seen, window };
}

export const HANDOFF_AIR = "folkways.blueprint.handoff.air";
export const HANDOFF_CONFIRM = "folkways.blueprint.handoff.confirm";

const PLACED = "Build order placed: ";

export type SchematicSpec = {
  file: string;
  size: Vec;
  count: number;
  materials: Record<string, number>;
  cells: Array<{ x: number; y: number; z: number; block: string }>;
};

export function schematicSpec(asset: string): SchematicSpec {
  return JSON.parse(readFileSync(path.resolve(asset).replace(/\.nbt$/, ".json"), "utf8"));
}

export function schematicCells(spec: SchematicSpec, anchor: Vec): Vec[] {
  return spec.cells.map((cell) => ({ x: anchor.x + cell.x, y: anchor.y + cell.y, z: anchor.z + cell.z }));
}

export async function handOffSchematic(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  options: { asset: string; anchor: Vec; excavate?: boolean },
) {
  const spec = schematicSpec(options.asset);
  const placed = placeSchematicFile(client, path.resolve(options.asset), spec.file);

  await world.command(server, `item replace entity ${playerName} weapon.offhand from entity ${playerName} weapon.mainhand`);
  await world.command(
    server,
    `item replace entity ${playerName} weapon.mainhand with ${deployedSchematic(spec.file, playerName, options.anchor, spec.size)}`,
  );
  await tick.sprint(server, 10);
  await safeAction(() => frames(client, 10));

  const equipped = await equipHandoffTool(client);
  const opened = await openHandoffScreen(client);
  if (!opened.window || isSafeFailure(opened.window)) {
    throw new Error(
      `right-click did not open the handoff config page: ${HANDOFF_WINDOW} is not on the panel; the screen was `
        + `${JSON.stringify(screenName(opened.screen))}`,
    );
  }
  if (options.excavate) {
    await softly(() => clickElement(client, { id: HANDOFF_AIR }));
    await safeAction(() => frames(client, 3));
  }

  await drainActionBar(client, 10_000);
  const click = await clickUnverified(client, { id: HANDOFF_CONFIRM });
  await tick.sprint(server, 20);
  const bar = await readActionBar(client);
  await safeAction(() => screen.dismiss(client));

  await world.command(server, `item replace entity ${playerName} weapon.mainhand from entity ${playerName} weapon.offhand`);
  await world.command(server, `item replace entity ${playerName} weapon.offhand with minecraft:air`);
  await tick.sprint(server, 5);

  if (!bar.text.startsWith(PLACED)) {
    throw new Error(
      `the handoff did not place a build order. After confirming, the action bar read ${JSON.stringify(bar.text)}, ` +
        `while the success message starts with ${JSON.stringify(PLACED)}. Empty string = nothing handled the click; ` +
        `any other message = it was handled but took another branch, so read the message itself.`,
    );
  }
  return {
    file: spec.file,
    size: spec.size,
    anchor: options.anchor,
    cells: schematicCells(spec, options.anchor),
    materials: spec.materials,
    receipt: { click, bar, equipped, screen: screenName(opened.screen), placed },
  };
}

import { launchPair, type E2EPair } from "../shared/pair";
import { camera, input, player, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { stillWorld, walkSpeed } from "@izakyl/blockwright-cinema";
import { sleepMs } from "../e2e/draw-capture";
import { VisualRun } from "../visual/visual-kit";
import { outPath, tracePath } from "../out-paths";
import { seedShaders } from "./shaders";
import { placeServerModels, reloadServerModels, type ModelManifest } from "../e2e/model-folder";
import { seededOffThreadServer } from "../shared/mod-settings";
import { safeAction, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

export const PROMO_CONFIG = "blockwright.toml#promo";

export const PROMO_SKY_CONFIG = "blockwright.toml#promo-sky";

export const TAKES_ROOT = outPath("promo", "takes");

export const RUN_ID = newRunId("FOLKWAYS_PROMO_RUN_ID");

export function take(scope: string) {
  return new VisualRun(scope, TAKES_ROOT);
}

export type PromoPair = E2EPair & { clientInstance: string };
export type SeededModel = { source: string; name: string; manifest?: ModelManifest };

export async function launchPromoPair(
  scope: string, seed?: { models?: SeededModel[]; config?: string; zones?: Record<string, number> },
): Promise<PromoPair> {
  const clientInstance = `promo-${scope}-client-${RUN_ID}`;
  const serverInstance = `promo-${scope}-server-${RUN_ID}`;
  seedShaders(clientInstance);
  const pair = await launchPair({
    config: seed?.config ?? PROMO_CONFIG,
    server: {
      trace: tracePath("promo", `${scope}-server`),
      instance: seededOffThreadServer(serverInstance, seed?.zones),
    },
    client: { trace: tracePath("promo", `${scope}-client`), instance: clientInstance },
  });
  if (seed?.models?.length) {
    await placeServerModels(pair.server, seed.models);
    await reloadServerModels(pair.server);
  }
  return { ...pair, clientInstance };
}

// Takes carry the game's sound effects for the edit, which lays its own music over them; the promo profiles
// mute the game's music, so the track holds only what happens on screen.
export function promoClip(run: VisualRun, client: any, server: any, name: string, options: any) {
  return run.clip(client, server, name, { fps: 60, audio: true, ...options });
}

export async function cinematicWorld(server: MinecraftServer) {
  await stillWorld(server);
}

export async function setTickRate(server: MinecraftServer, rate: number) {
  return tryCommand(server, `tick rate ${rate}`);
}

export type Look = { yaw: number; pitch: number };

export async function setWalkSpeed(server: MinecraftServer, playerName: string, blocksPerSecond: number) {
  return { blocks_per_second: blocksPerSecond, attribute: await walkSpeed(server, playerName, blocksPerSecond) };
}

// The takes call this in pairs, as F1 is pressed: once to clear the picture, once more to bring the GUI back.
const hudHidden = new WeakSet<MinecraftClient>();

export async function hideHud(client: MinecraftClient) {
  const hide = !hudHidden.has(client);
  const result = await safeAction(() => camera.hud(client, !hide));
  if (hide) hudHidden.add(client); else hudHidden.delete(client);
  return result;
}

export async function writeLook(client: MinecraftClient, look: Look) {
  return safeAction(() => player.place(client, { yaw: look.yaw, pitch: look.pitch }));
}

export async function setFov(client: MinecraftClient, degrees: number) {
  await camera.fov(client, degrees);
}

export async function setKey(client: MinecraftClient, keybind: string, pressed: boolean) {
  return safeAction(() => input.key(client, { keybind, action: pressed ? "press" : "release" }));
}

export { sleepMs };

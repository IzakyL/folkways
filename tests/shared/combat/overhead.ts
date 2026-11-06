import { expect } from "@playwright/test";
import { input, screen, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook } from "../colony/colony-founding";

export const KEY_V = 86;

/** Presses and releases V: opens the overhead view, or closes it when open. */
export async function tapV(client: MinecraftClient) {
  await input.key(client, { keyCode: KEY_V, action: "press" });
  await input.key(client, { keyCode: KEY_V, action: "release" });
}

/** Clicks the RTS chip on the open overhead toolbar; the #rts profile registers it. */
export async function pickRtsTool(client: MinecraftClient) {
  const chip = (await screen.widgets(client)).find(widget => /^RTS$/.test(widget.label?.trim() ?? ""));
  expect(chip, "the overhead toolbar must expose the RTS tool").toBeTruthy();
  await screen.clickWidget(client, { label: chip!.label });
}

/** Opens the overhead view with V and switches it to the RTS tool. */
export async function openRtsTool(client: MinecraftClient) {
  await tapV(client);
  await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
  await pickRtsTool(client);
}

/**
 * Founds a colony of `residentCount` around `origin` and binds the held book to it by opening and closing its
 * panel once, as a player would before using V.
 */
export async function foundBookColony(server: MinecraftServer, client: MinecraftClient, player: string,
  origin: { x: number; y: number; z: number }, residentCount: number) {
  const founding = await foundColonyFast(server, client, player, { origin, residentCount });
  await openColonyPanelViaBook(server, client, player, founding.standBlock);
  await screen.dismiss(client);
  return { ...founding, colony: String(founding.evidence.colonyId) };
}

/**
 * Lays `block` over the x/z box one below `y` and clears `headroom` blocks of air above it. A fill that changes
 * nothing is not an error here: the floor is already what the scene needs.
 */
export async function layFloor(server: MinecraftServer, min: { x: number; z: number }, max: { x: number; z: number },
  y: number, block = "minecraft:stone", headroom = 5) {
  await world.command(server, `fill ${min.x} ${y - 1} ${min.z} ${max.x} ${y - 1} ${max.z} ${block}`, { allowFailure: true });
  await world.command(server, `fill ${min.x} ${y} ${min.z} ${max.x} ${y + headroom} ${max.z} minecraft:air`, { allowFailure: true });
}

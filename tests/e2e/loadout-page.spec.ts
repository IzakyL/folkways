import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { defineTask, javaTask, screen, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, typeIntoElement, type Element } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { markSeeded } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { clickTab } from "../shared/ui/colony-pages";
import { scrollCellIntoView, typeCell } from "../shared/ui/site-panel";
import { frames } from "../shared/harness/bw-helpers";
import { setVisualLanguage } from "../shared/capture/ponder";

const KEY = "folkways.loadouts.";
const PROFILE = "Archers of the northern river watch";
const READ = defineTask<void, { profiles: string[]; assignments: string[]; target: number; threshold: number }>({
  name: "folkways.loadout-ux.read", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
    "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
  ], body: `
var colony = Colonies.all(ctx.server()).getFirst(); var profiles = LoadoutProfiles.of(colony);
var supply = profiles.all().stream().flatMap(p -> p.entries().stream())
 .filter(e -> e.kind() == LoadoutProfile.Kind.SUPPLY).findFirst();
return Map.of("profiles", profiles.all().stream().map(p -> p.id().toString()).toList(),
 "assignments", colony.residents().stream()
  .map(r -> profiles.assigned(r.id()).map(p -> p.name()).orElse("NONE")).toList(),
 "target", supply.map(e -> e.target()).orElse(0), "threshold", supply.map(e -> e.threshold()).orElse(0));
` }),
});

function element(elements: Element[], id: string) {
  const found = elements.find(row => row.id === id);
  expect(found, id).toBeTruthy();
  return found!;
}

const WINDOW = defineTask<{ width: number; height: number }, boolean>({
  name: "folkways.loadout-ux.window", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
Args args = Args.of(ctx);
Minecraft.getInstance().getWindow().setWindowed((int)args.integer("width"), (int)args.integer("height"));
return true;
` }),
});

async function resizeWindow(client: MinecraftClient, width: number, height: number) {
  await client.run(WINDOW, { width, height });
  await frames(client, 8);
}

async function pickItem(client: MinecraftClient, id: string) {
  await typeIntoElement(client, { id: "folkways.selector.search" }, id);
  await clickElement(client, { id: `folkways.selector.result.${id.replace(":", ".")}` });
  await clickElement(client, { id: "folkways.selector.confirm" });
  await frames(client, 5);
}

test("loadout panes and immediate assignment work in Chinese and English", async ({}, testInfo) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    const clientInstance = `loadout-ux-client-${run}`;
    seedInstance(clientInstance, { cwd: process.cwd() })
      .write("config/folkways-windows.properties", "colony=40,40,1100,620\n");
    markSeeded(clientInstance);
    pair = await launchPair({
      server: { instance: `loadout-ux-server-${run}` }, client: { instance: clientInstance },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 2 });
    await setVisualLanguage(client, "zh_cn");
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickTab(client, KEY + "title");
    expect(element(await panelElements(client), "folkways.window.colony").width).toBe(1100);
    await typeIntoElement(client, { id: KEY + "name" }, PROFILE);
    await clickElement(client, { id: KEY + "create" });
    await expect.poll(async () => (await server.run(READ)).profiles.length).toBe(1);
    await expect.poll(async () => element(await panelElements(client), KEY + "add").text).toContain("添加");
    await clickElement(client, { id: KEY + "add" });
    const menu = await panelElements(client);
    for (const action of ["weapon", "weapon_variants", "supply", "observation_gear", "head", "chest", "legs", "feet"]) {
      expect(element(menu, KEY + action).displayed).toBe(true);
    }
    for (const removed of ["exact.0", "enchantments.0", "held_weapon"]) {
      expect(menu.some(row => row.id === KEY + removed)).toBe(false);
    }
    await clickElement(client, { id: KEY + "weapon" });
    await pickItem(client, "minecraft:bow");
    await expect.poll(async () => (await server.run(READ)).target).toBeGreaterThan(0);
    await typeCell(server, client, KEY + "target.1", "24");
    await typeCell(server, client, KEY + "threshold.1", "6");
    await clickElement(client, { id: KEY + "amount.1" });
    await expect.poll(async () => {
      const reading = await server.run(READ);
      return [reading.target, reading.threshold];
    }).toEqual([24, 6]);
    await clickElement(client, { id: KEY + "edit.1" });
    await pickItem(client, "minecraft:spectral_arrow");
    await clickElement(client, { id: KEY + "up.1" });
    await expect.poll(async () => element(await panelElements(client), KEY + "target.0").active).toBe(true);
    await typeCell(server, client, KEY + "target.0", "4096");
    await clickElement(client, { id: KEY + "amount.0" });
    await expect.poll(async () => (await server.run(READ)).target).toBe(4096);
    await clickElement(client, { id: KEY + "add" });
    await clickElement(client, { id: KEY + "chest" });
    await pickItem(client, "minecraft:iron_chestplate");
    await expect.poll(async () => element(await panelElements(client), KEY + "entry.2").displayed).toBe(true);
    const wide = await panelElements(client);
    const left = element(wide, KEY + "profiles"), right = element(wide, KEY + "details");
    expect(left.x + left.width).toBeLessThanOrEqual(right.x);
    const target = element(wide, KEY + "target.0");
    for (const action of ["threshold.0", "amount.0", "up.0", "down.0", "remove.0", "edit.0"]) {
      const control = element(wide, KEY + action);
      expect(Math.abs(control.y + control.height / 2 - target.y - target.height / 2)).toBeLessThan(2);
      expect(control.x + control.width).toBeLessThanOrEqual(right.x + right.width);
    }
    await clickElement(client, { id: KEY + "assign_residents" });
    const profiles = (await server.run(READ)).profiles;
    for (const slot of [0, 1]) {
      await clickElement(client, { id: KEY + `resident.${slot}` });
      await clickElement(client, { id: KEY + `choose.${profiles[0]}` });
      await expect.poll(async () => element(await panelElements(client), KEY + `resident.${slot}`).text)
        .toContain(PROFILE);
    }
    await expect.poll(async () => (await server.run(READ)).assignments).toEqual([PROFILE, PROFILE]);
    await clickElement(client, { id: KEY + "resident.0" });
    await clickElement(client, { id: KEY + "choose.none" });
    await expect.poll(async () => (await server.run(READ)).assignments.filter(name => name === "NONE").length).toBe(1);
    await clickElement(client, { id: "folkways.window.close.loadout_residents" });
    await screen.dismiss(client);
    await setVisualLanguage(client, "en_us");
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickTab(client, KEY + "title");
    await clickElement(client, { id: KEY + "profile.0" });
    await expect.poll(async () => element(await panelElements(client), KEY + "add").text).toContain("Add");
    await resizeWindow(client, 480, 720);
    const narrow = await panelElements(client);
    expect(element(narrow, KEY + "details").y).toBeGreaterThan(element(narrow, KEY + "profiles").y);
    await scrollCellIntoView(server, client, KEY + "entry.0", KEY + "edit.0");
    await clickElement(client, { id: KEY + "edit.0" });
    await clickElement(client, { id: "folkways.selector.close" });
    await clickElement(client, { id: KEY + "delete" }, { scrollIntoView: true });
    await expect.poll(async () => (await server.run(READ)).assignments).toEqual(["NONE", "NONE"]);
    for (let index = 0; index < 5; index++) {
      await typeCell(server, client, KEY + "name", `Profile ${index}`);
      await clickElement(client, { id: KEY + "create" });
      await expect.poll(async () => (await server.run(READ)).profiles.length).toBe(index + 1);
    }
    await clickElement(client, { id: KEY + "next.profiles" });
    await expect.poll(async () => element(await panelElements(client), KEY + "profile.0").text)
      .toContain("Profile 4");
    await clickElement(client, { id: KEY + "previous.profiles" });
    await expect.poll(async () => element(await panelElements(client), KEY + "profile.0").text)
      .toContain("Profile 0");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

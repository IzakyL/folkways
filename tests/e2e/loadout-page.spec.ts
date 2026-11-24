import { expect, test } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { defineTask, javaTask, screen, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, typeIntoElement, type Element } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { markSeeded } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { typeCell } from "../shared/ui/site-panel";
import { frames } from "../shared/harness/bw-helpers";
import { setVisualLanguage } from "../shared/capture/ponder";
import { openEditor } from "../shared/ui/colony-pages";

const KEY = "folkways.equipment.";
const EDITOR = "folkways_core.equipment";
const PROFILE = "Archers of the northern river watch";
const READ = defineTask<void, { profiles: string[]; assignments: string[]; target: number; threshold: number; spectralFirst: boolean; chestplate: boolean }>({
  name: "folkways.loadout-ux.read", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles",
    "io.github.izakyl.folkways.core.item.api.Goods",
    "net.minecraft.world.item.ItemStack",
    "net.minecraft.world.item.Items",
  ], body: `
var colony = Colonies.all(ctx.server()).getFirst(); var profiles = EquipmentProfiles.of(colony);
var entries = profiles.all().stream().findFirst().map(p -> p.entries()).orElse(List.of());
var supply = entries.stream().filter(e -> !e.slotted() && !e.weapon()).findFirst();
boolean spectralFirst = !entries.isEmpty() && Goods.matches(new ItemStack(Items.SPECTRAL_ARROW), entries.getFirst().items());
boolean chestplate = entries.stream().anyMatch(e -> e.slotted()
    && Goods.matches(new ItemStack(Items.IRON_CHESTPLATE), e.items()));
return Map.of("profiles", profiles.all().stream().map(p -> p.id().toString()).toList(),
 "assignments", colony.residents().stream()
  .map(r -> profiles.assigned(r.id()).map(p -> p.name()).orElse("NONE")).toList(),
 "target", supply.map(e -> e.target()).orElse(0), "threshold", supply.map(e -> e.threshold()).orElse(0),
 "spectralFirst", spectralFirst, "chestplate", chestplate);
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

test("equipment profile panes and immediate assignment work in Chinese and English", async ({}, testInfo) => {
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
    expect(element(await panelElements(client), "folkways.window.colony").width).toBe(1100);
    await openEditor(client, EDITOR);
    await clickElement(client, { id: KEY + "create" });
    await expect.poll(async () => (await server.run(READ)).profiles.length).toBe(1);
    await typeCell(server, client, KEY + "name", PROFILE);
    await clickElement(client, { id: KEY + "rename" });
    await expect.poll(async () => element(await panelElements(client), KEY + "profile.0").text).toBe(PROFILE);
    await expect.poll(async () => element(await panelElements(client), KEY + "add").text).toBe("+ 添加");
    await clickElement(client, { id: KEY + "add" });
    await pickItem(client, "minecraft:bow");
    await expect.poll(async () => (await server.run(READ)).target).toBeGreaterThan(0);
    await clickElement(client, { id: KEY + "carried.1" });
    await typeCell(server, client, KEY + "target", "24");
    await typeCell(server, client, KEY + "threshold", "6");
    await clickElement(client, { id: KEY + "amount" });
    await expect.poll(async () => {
      const reading = await server.run(READ);
      return [reading.target, reading.threshold];
    }).toEqual([24, 6]);
    await clickElement(client, { id: KEY + "edit" });
    await pickItem(client, "minecraft:spectral_arrow");
    await clickElement(client, { id: KEY + "left" });
    await expect.poll(async () => (await server.run(READ)).spectralFirst).toBe(true);
    await typeCell(server, client, KEY + "target", "4096");
    await clickElement(client, { id: KEY + "amount" });
    await expect.poll(async () => (await server.run(READ)).target).toBe(4096);
    await clickElement(client, { id: "folkways.window.close.equipment_entry" });
    await clickElement(client, { id: KEY + "slot.folkways_core.chest" });
    await pickItem(client, "minecraft:iron_chestplate");
    await expect.poll(async () => (await server.run(READ)).chestplate).toBe(true);
    const wide = await panelElements(client);
    const left = element(wide, KEY + "profiles"), right = element(wide, KEY + "details");
    expect(left.x + left.width).toBeLessThanOrEqual(right.x);
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
    await clickElement(client, { id: "folkways.window.close.equipment_residents" });
    await screen.dismiss(client);
    await setVisualLanguage(client, "en_us");
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await openEditor(client, EDITOR);
    await clickElement(client, { id: KEY + "profile.0" });
    await expect.poll(async () => element(await panelElements(client), KEY + "add").text).toBe("+ Add");
    // The equipment editor stacks below 340 GUI units.
    await resizeWindow(client, 320, 720);
    await expect.poll(async () => {
      const narrow = await panelElements(client);
      return element(narrow, KEY + "details").y - element(narrow, KEY + "profiles").y;
    }).toBeGreaterThan(0);
    await clickElement(client, { id: KEY + "carried.0" }, { scrollIntoView: true });
    await clickElement(client, { id: KEY + "edit" });
    await clickElement(client, { id: "folkways.selector.close" });
    await clickElement(client, { id: "folkways.window.close.equipment_entry" });
    await clickElement(client, { id: KEY + "delete" }, { scrollIntoView: true });
    await expect.poll(async () => (await server.run(READ)).assignments).toEqual(["NONE", "NONE"]);
    await expect.poll(async () => (await panelElements(client)).some(row => row.id === KEY + "profile.0"))
      .toBe(false);
    for (let index = 0; index < 5; index++) {
      await clickElement(client, { id: KEY + "create" }, { scrollIntoView: true });
      await expect.poll(async () => (await server.run(READ)).profiles.length).toBe(index + 1);
      await expect.poll(async () => (await panelElements(client)).find(row => row.id === KEY + `profile.${index}`)?.text)
        .toBe(`Profile ${index + 1}`);
    }
    for (const index of [4, 0]) {
      await clickElement(client, { id: KEY + `profile.${index}` }, { scrollIntoView: true });
      await expect.poll(async () => element(await panelElements(client), KEY + `profile.${index}`).classes)
        .toContain("folkways_chosen");
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

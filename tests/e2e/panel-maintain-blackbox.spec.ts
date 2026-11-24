import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, visibleBox } from "../ldlib2";
import { BOOK_TABS, cardRows, openBlockCard, openSection, panelSection, textUnder, waitForPresent } from "../shared/ui/book-cards";
import { typeCell } from "../shared/ui/site-panel";
import { ORDER_COUNT, ORDER_DELIVER, ORDER_ITEM, ORDER_LOW, ORDER_MAINTAIN, ORDERS_HERE, filedOrdersAt, setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer,
  entityTypeMatches,
} from "../shared/colony/colony-founding";
import { prepareSafeWalkwayOn, safeWalkwayBox } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeAction, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { waitForResourceReload } from "../shared/harness/resource-reload";
import { cover, twins } from "../shared/harness/stage";

const RESIDENT_TYPE = "folkways_vanilla:resident";
const ITEM_TYPE = "minecraft:item";
const DEMAND_ITEM = "minecraft:cobblestone";
const MAINTAIN_COUNT = 1;
const SOURCE_STOCK = 16;
const FILTER = "folkways.card.items.filter";
const TAKE = "folkways.card.flag.orders_take";
const STORE = "folkways.card.flag.orders_store";
const FILTER_CHIP = "folkways.card.choice.orders_filter_mode.folkways_core:filter/";

const CONTAINER_LANGUAGE = defineTask<{ code: string }, { ok: true }>({
  name: "folkways.container-preview.language",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
String code = Args.of(ctx).string("code");
mc.getLanguageManager().setSelected(code);
mc.options.languageCode = code;
mc.reloadResourcePacks();
return Map.of("ok", true);
`,
  }),
});

const CONTAINER_SETTLED = defineTask<{ code: string; scale: number }, { settled: boolean; width: number; height: number }>({
  name: "folkways.container-preview.settled",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
Args args = Args.of(ctx);
int scale = (int) args.integer("scale");
if (mc.options.guiScale().get() != scale) {
    mc.options.guiScale().set(scale);
    mc.resizeDisplay();
}
boolean settled = mc.getOverlay() == null && mc.getLanguageManager().getSelected().equals(args.string("code"));
return Map.of("settled", settled, "width", mc.getWindow().getGuiScaledWidth(), "height", mc.getWindow().getGuiScaledHeight());
`,
  }),
});

for (const stage of twins()) test(stage.title("container card Maintain order replenishes the bound chest"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", stage.name("panel-server"));
  const clientTrace = tracePath("e2e", stage.name("panel-client"));

  let server: any;
  let client: any;
  let targetChest: any;
  let sourceChest: any;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: serverTrace, instance: seededServer(stage.name(`panel-server-${runId}`)) },
      client: { trace: clientTrace, instance: stage.name(`panel-client-${runId}`) },
      snapshot: () => ({ containers: [targetChest, sourceChest].map((chest) => chest && stage.at(chest)), entityTypes: [RESIDENT_TYPE, ITEM_TYPE] }),
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    targetChest = { x: origin.x + 5, y: origin.y, z: origin.z - 2 };
    sourceChest = { x: origin.x - 5, y: origin.y, z: origin.z + 5 };
    await stage.lay(server, cover(foundingBox(origin), safeWalkwayBox(origin, targetChest, sourceChest)));
    const founded = await foundColonyFast(server, client, playerName, { origin, residentCount: 3, stage });
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");

    await prepareSafeWalkwayOn(server, stage, origin, targetChest, sourceChest);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=${ITEM_TYPE},distance=..48]`);
    for (const chest of [targetChest, sourceChest]) {
      await tryCommand(server, `setblock ${commandPos(stage.at(chest))} minecraft:chest{Items:[]}`);
    }
    await tick.sprint(server, 3);
    for (const chest of [targetChest, sourceChest]) {
      await optInViaBook(server, client, playerName, chest, "chest", stage);
    }
    await tryCommand(server, `item replace block ${commandPos(stage.at(sourceChest))} container.0 with ${DEMAND_ITEM} ${SOURCE_STOCK}`);
    for (let slot = 0; slot < 9; slot++) {
      await tryCommand(server, `item replace entity ${playerName} inventory.${slot} with ${DEMAND_ITEM} 1`);
    }
    await tick.sprint(server, 20);
    const sourceStocked = await chestHasAtLeast(server, stage.at(sourceChest), DEMAND_ITEM, SOURCE_STOCK);
    expect(sourceStocked).toBe(true);

    // General storage and requested delivery are independent. Protect the source first so the order
    // cannot be fulfilled until the player explicitly permits taking from it again.
    await containerAccess(server, client, playerName, sourceChest, stage, "take");
    await containerAccess(server, client, playerName, targetChest, stage, "store");
    await exerciseFilterPicker(server, client, playerName, targetChest, sourceChest, stage);
    await setMaintainDemandViaUI(server, client, playerName, targetChest, DEMAND_ITEM, 1, stage);
    await openBlockCard(server, client, playerName, targetChest, stage);
    await assertFilterState(server, client, "only", 1);
    for (const mode of ["except", "only"] as const) {
      await pressCard(server, client, FILTER_CHIP + mode);
      await assertFilterState(server, client, mode, 1);
    }
    expect(await cardRows(client, ORDERS_HERE)).toHaveLength(1);
    await pressCard(server, client, FILTER);
    await filterSearch(server, client, "*minecraft:dirt");
    await waitForElement(client, { id: "folkways.selector.result.minecraft.dirt" });
    await clickElement(client, { id: "folkways.selector.result.minecraft.dirt" });
    await finishPicker(server, client, "confirm");
    await assertFilterState(server, client, "only", 0);
    await screen.dismiss(client);

    await tick.sprint(server, 200);
    expect(await chestItemCount(server, stage.at(targetChest), DEMAND_ITEM),
      "a protected container must not supply an order").toBe(0);
    expect(await chestItemCount(server, stage.at(sourceChest), DEMAND_ITEM)).toBe(SOURCE_STOCK);
    await containerAccess(server, client, playerName, sourceChest, stage, "take");

    const final = await pollReplenish(server, stage.at(targetChest), stage.at(sourceChest));
    expect(final.targetHasStock).toBe(true);
    expect(final.sourceHasRemainder).toBe(true);
    expect(await filedOrdersAt(server, playerName, targetChest, DEMAND_ITEM, stage))
      .toEqual([{ high: MAINTAIN_COUNT, standing: true }]);
    await openColonyPanelViaBook(server, client, playerName, founded.standBlock, stage);
    const orders = await openSection(server, client, BOOK_TABS.stock, "folkways_vanilla:orders", "STOCK")
      .catch(async error => {
        const section = panelSection("folkways_vanilla:orders", "STOCK");
        const relevant = new Set(["folkways.metrics.search", "folkways.book.sections.stock", section]);
        const tree = await panelElements(client);
        const controls = tree.filter(element => relevant.has(element.id ?? ""))
          .map(({ id, displayed }) => ({ id, displayed }));
        throw new Error(`${error.message} (Stock & orders controls: ${JSON.stringify(controls)})`, { cause: error });
      });
    const stock = await panelElements(client);
    expect(orders.map(row => textUnder(stock, row.id))).toEqual(expect.arrayContaining([
      expect.stringContaining("Cobblestone"),
    ]));
    await screen.dismiss(client);
    // How the form is drawn does not depend on where the chest stands: its geometry is checked on the ground.
    if (stage.kind === "ground") {
      await setMaintainDemandViaUI(server, client, playerName, targetChest, "minecraft:iron_ingot", 8, stage);
      await previewChineseContainer(server, client, playerName, targetChest, stage);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function pollReplenish(server: any, targetChest: any, sourceChest: any) {
  await safeAction(() => world.waitFor(
    server,
    `if items block ${commandPos(targetChest)} container.* ${DEMAND_ITEM}`
      + ` if items block ${commandPos(sourceChest)} container.* ${DEMAND_ITEM}`,
    { maxTicks: 2400, stepTicks: 20 },
  ));
  return await replenishSnapshot(server, targetChest, sourceChest, 0, "after_predicate_sprint");
}

async function replenishSnapshot(server: any, targetChest: any, sourceChest: any, order: number, label: string) {
  const [entitiesResult, targetHas, sourceRemainder] = await Promise.all([
    world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }),
    chestHasAtLeast(server, targetChest, DEMAND_ITEM, MAINTAIN_COUNT),
    chestHasAtLeast(server, sourceChest, DEMAND_ITEM, 1),
  ]);
  const residents = (entitiesResult.entities ?? []).filter((e: any) => entityTypeMatches(e.type, RESIDENT_TYPE)).length;
  return { order, label, targetHasStock: targetHas, sourceHasRemainder: sourceRemainder, residents };
}

async function chestItemCount(server: any, chest: any, itemId: string) {
  const result: any = await world.container(server, chest, { dimension: "minecraft:overworld" });
  if (!result || result.found === false || result.isContainer === false) {
    throw new Error(`container read at ${commandPos(chest)} is not usable: ${JSON.stringify(result)}`);
  }
  let total = 0;
  for (const slot of result.slots ?? []) {
    if (slot?.id === itemId) total += slot.count;
  }
  return total;
}

async function chestHasAtLeast(server: any, chest: any, itemId: string, count: number) {
  return (await chestItemCount(server, chest, itemId)) >= count;
}

async function pressCard(server: any, client: any, id: string) {
  await waitForPresent(server, client, id);
  await clickElement(client, { id });
  await tick.sprint(server, 5);
  await frames(client, 3);
}

async function finishPicker(server: any, client: any, action: "close" | "confirm") {
  await clickElement(client, { id: `folkways.selector.${action}` });
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent" });
  await tick.sprint(server, 5);
  await frames(client, 3);
}

async function containerAccess(server: any, client: any, playerName: string, chest: any, stage: any,
  mode: "take" | "store") {
  await openBlockCard(server, client, playerName, chest, stage);
  await pressCard(server, client, mode === "take" ? TAKE : STORE);
  await screen.dismiss(client);
}

async function exerciseFilterPicker(server: any, client: any, playerName: string, chest: any, source: any, stage: any) {
  await openBlockCard(server, client, playerName, chest, stage);
  if (stage.kind === "ground") {
    await assertContainerForm(server, client, 0);
  }
  const result = (item: string) => `folkways.selector.result.minecraft.${item}`;
  await assertFilterState(server, client, "except", 0);
  await pressCard(server, client, FILTER);
  await waitForElement(client, { id: "folkways.selector.close" });
  for (const item of ["cobblestone", "dirt"]) {
    await filterSearch(server, client, `*minecraft:${item}`);
    await waitForElement(client, { id: result(item) });
    await clickElement(client, { id: result(item) });
    expect(await selectedFilterRules(client)).toContain(`minecraft:${item}`);
  }
  await finishPicker(server, client, "confirm");
  await assertFilterState(server, client, "except", 2);
  await pressCard(server, client, FILTER_CHIP + "only");
  await assertFilterState(server, client, "only", 2);
  // Reopen to prove the common picker keeps a multi-selection; cancel a staged removal.
  await pressCard(server, client, FILTER);
  await waitForElement(client, { id: "folkways.selector.close" });
  await filterSearch(server, client, "*minecraft:cobblestone");
  await waitForElement(client, { id: result("cobblestone") });
  expect(await selectedFilterRules(client)).toEqual(["minecraft:cobblestone", "minecraft:dirt"]);
  await clickElement(client, { id: result("cobblestone") });
  await finishPicker(server, client, "close");
  await pressCard(server, client, FILTER);
  await waitForElement(client, { id: "folkways.selector.close" });
  await filterSearch(server, client, "*minecraft:cobblestone");
  await waitForElement(client, { id: result("cobblestone") });
  expect(await selectedFilterRules(client), "cancel must keep the previous multi-selection")
    .toEqual(["minecraft:cobblestone", "minecraft:dirt"]);
  await clickElement(client, { id: result("cobblestone") });
  await finishPicker(server, client, "confirm");
  await assertFilterState(server, client, "only", 1);
  // Changing mode keeps dirt selected; cobblestone must still enter for the order.
  await pressCard(server, client, FILTER_CHIP + "except");
  await assertFilterState(server, client, "except", 1);
  // Filters belong to a container, rather than the shared settings page.
  await openBlockCard(server, client, playerName, source, stage);
  await assertFilterState(server, client, "except", 0);
  await pressCard(server, client, FILTER);
  await waitForElement(client, { id: "folkways.selector.close" });
  expect(await selectedFilterRules(client), "the source does not inherit the target's filter").toEqual([]);
  await finishPicker(server, client, "close");
  await openBlockCard(server, client, playerName, chest, stage);
  await assertFilterState(server, client, "except", 1);
  await pressCard(server, client, FILTER);
  await waitForElement(client, { id: "folkways.selector.close" });
  expect(await selectedFilterRules(client), "reopening the target keeps its own filter").toEqual(["minecraft:dirt"]);
  await finishPicker(server, client, "close");
  await pressCard(server, client, FILTER_CHIP + "only");
  await assertFilterState(server, client, "only", 1);

  await screen.dismiss(client);
}

async function assertFilterState(server: any, client: any, mode: "except" | "only", count: number) {
  await expect.poll(async () => {
    await tick.sprint(server, 3);
    const tree = await panelElements(client);
    const selected = tree.find(element => element.id === FILTER_CHIP + mode)?.classes?.includes("folkways_chosen");
    const summary = textUnder(tree, FILTER);
    return { selected, summary };
  }).toMatchObject({ selected: true, summary: count === 0 ? /none/i : count === 1 ? /dirt/i : /cobblestone.*1/i });
}

async function assertContainerForm(server: any, client: any, expectedOrders: number) {
  const rows = await cardRows(client, ORDERS_HERE);
  expect(rows).toHaveLength(expectedOrders);
  for (const id of [TAKE, STORE, FILTER, ORDER_ITEM, ORDER_LOW, ORDER_COUNT, ORDER_MAINTAIN, ORDER_DELIVER,
    ...rows.flatMap(row => row.acts)]) {
    await assertControlFits(server, client, id);
  }
}

async function assertControlFits(server: any, client: any, id: string) {
  await waitForPresent(server, client, id);
  const tree = await panelElements(client);
  const control = tree.find(element => element.id === id)!;
  expect(control.width, id).toBeGreaterThan(0);
  expect(control.height, id).toBeGreaterThan(0);
  expect(visibleBox(tree, control), id).toMatchObject({ width: control.width, height: control.height });
}

async function previewChineseContainer(server: any, client: any, playerName: string, chest: any, stage: any) {
  await screen.dismiss(client);
  await client.run(CONTAINER_LANGUAGE, { code: "zh_cn" });
  await waitForResourceReload(client);
  for (const scale of [1, 3]) {
    await screen.dismiss(client);
    let viewport: { settled: boolean; width: number; height: number } | undefined;
    for (let attempt = 0; attempt < 120; attempt++) {
      viewport = await client.run(CONTAINER_SETTLED, { code: "zh_cn", scale });
      if (viewport?.settled) break;
      await frames(client, 5);
    }
    expect(viewport?.settled, `Chinese resource reload must finish at GUI scale ${scale}`).toBe(true);
    await openBlockCard(server, client, playerName, chest, stage);
    if (scale === 1) {
      await assertContainerForm(server, client, 2);
    }
    for (const flag of [TAKE, STORE]) {
      await assertControlFits(server, client, flag);
      const tree = await panelElements(client);
      // A setting's name is drawn rather than held as text; the card's own words show the language.
      expect(textUnder(tree, "folkways.card.card")).toMatch(/[\u3400-\u9fff]/);
      const control = tree.find(element => element.id === flag)!;
      expect(control.x).toBeGreaterThanOrEqual(0);
      expect(control.y).toBeGreaterThanOrEqual(0);
      expect(control.x + control.width).toBeLessThanOrEqual(viewport!.width);
      expect(control.y + control.height).toBeLessThanOrEqual(viewport!.height);
    }
  }
  await screen.dismiss(client);
}

async function filterSearch(server: any, client: any, query: string) {
  await typeCell(server, client, "folkways.selector.search", query);
}

async function selectedFilterRules(client: any): Promise<string[]> {
  const elements = await panelElements(client);
  return elements.filter(element => /^folkways\.selector\.rule\.\d+$/.test(element.id ?? ""))
    .map(row => {
      const labels: string[] = [];
      for (let at = row.index + 1; at < elements.length && elements[at].depth > row.depth; at++) {
        const text = elements[at].text;
        if (text && text !== "×") labels.push(text);
      }
      return labels.join("");
    }).sort();
}

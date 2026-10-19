import { expect, test } from "@playwright/test";
import { defineTask, javaTask, input, media, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, scrollElementIntoView, ancestorsOf, type Element } from "../ldlib2";
import { boardAct, boardActs, boardRows, openSite, settlePanel } from "../shared/ui/site-panel";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer,
  entityTypeMatches,
} from "../shared/colony/colony-founding";
import { prepareSafeWalkwayOn, safeWalkwayBox } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath, shotPath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeAction, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { cover, twins } from "../shared/harness/stage";

const RESIDENT_TYPE = "folkways:resident";
const ITEM_TYPE = "minecraft:item";
const DEMAND_ITEM = "minecraft:cobblestone";
const MAINTAIN_COUNT = 1;
const SOURCE_STOCK = 16;

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

for (const stage of twins()) test(stage.title("container panel carry-set MAINTAIN stock replenishes the bound chest"), async ({}, testInfo) => {
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
    await foundColonyFast(server, client, playerName, { origin, residentCount: 3, stage });
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
    await tick.sprint(server, 200);
    expect(await chestItemCount(server, stage.at(targetChest), DEMAND_ITEM),
      "a protected container must not supply an order").toBe(0);
    expect(await chestItemCount(server, stage.at(sourceChest), DEMAND_ITEM)).toBe(SOURCE_STOCK);
    await containerAccess(server, client, playerName, sourceChest, stage, "take");

    const final = await pollReplenish(server, stage.at(targetChest), stage.at(sourceChest));
    expect(final.targetHasStock).toBe(true);
    expect(final.sourceHasRemainder).toBe(true);
    await setMaintainDemandViaUI(server, client, playerName, targetChest, "minecraft:iron_ingot", 8, stage);
    await previewChineseContainer(server, client, playerName, targetChest, stage);
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

// Resolve controls by their role in the form, independently of the number of existing orders.
async function openContainerRules(server: any, client: any, playerName: string, chest: any, stage: any) {
  await screen.dismiss(client);
  const seen = stage.stand(chest);
  await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
  await openSite(server, client, chest, stage);
  const edits = await boardActs(client, "edit", "folkways.orders");
  expect(edits.length, "only filter and the three order draft fields are editable").toBe(4);
  const byRow = new Map<number, number>();
  for (const edit of edits) byRow.set(edit.row, (byRow.get(edit.row) ?? 0) + 1);
  const filter = [...byRow].find(([, count]) => count === 1)?.[0];
  const draft = [...byRow].find(([, count]) => count === 3)?.[0];
  expect(filter, "the filter has its own picker").toBeDefined();
  expect(draft, "the order draft has item, threshold, and target fields").toBeDefined();
  const actions = await boardActs(client, "do", "folkways.orders");
  const admission = actions.find(act => act.row > filter! && act.row < draft!)?.row;
  expect(admission, "filter actions belong between the filter picker and order form").toBeDefined();
  return { first: 1, filter: filter!, admission: admission!, draft: draft! };
}

async function containerAccess(server: any, client: any, playerName: string, chest: any, stage: any,
  mode: "take" | "store") {
  const rules = await openContainerRules(server, client, playerName, chest, stage);
  const row = rules.first + ["take", "store"].indexOf(mode);
  await clickBoardAction(client, (await boardAct(client, "do", "folkways.orders", row)).id);
  await settlePanel(server, client);
  await screen.dismiss(client);
}

async function exerciseFilterPicker(server: any, client: any, playerName: string, chest: any, source: any, stage: any) {
  const rules = await openContainerRules(server, client, playerName, chest, stage);
  await assertContainerForm(client, rules);
  await media.screenshot(client, { path: shotPath("e2e", stage.name("container-overview")) });
  const opener = (await boardAct(client, "edit", "folkways.orders", rules.filter)).id;
  const result = (item: string) => `folkways.selector.result.minecraft.${item}`;
  await assertFilterState(client, rules, "black", 0);
  await clickBoardAction(client, opener);
  await waitForElement(client, { id: "folkways.selector.close" });
  for (const item of ["cobblestone", "dirt"]) {
    await filterSearch(server, client, `*minecraft:${item}`);
    await waitForElement(client, { id: result(item) });
    await clickElement(client, { id: result(item) });
    expect(await selectedFilterRules(client)).toContain(`minecraft:${item}`);
  }
  await media.screenshot(client, { path: shotPath("e2e", stage.name("container-multi-filter")) });
  await clickElement(client, { id: "folkways.selector.confirm" });
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent" });
  await settlePanel(server, client);
  await assertFilterState(client, rules, "black", 2);
  await clickBoardAction(client, (await boardAct(client, "do", "folkways.orders", rules.admission, 0)).id);
  await settlePanel(server, client);
  await assertFilterState(client, rules, "white", 2);
  // Reopen to prove the common picker keeps a multi-selection; cancel a staged removal.
  await clickBoardAction(client, opener);
  await waitForElement(client, { id: "folkways.selector.close" });
  await filterSearch(server, client, "*minecraft:cobblestone");
  await waitForElement(client, { id: result("cobblestone") });
  expect(await selectedFilterRules(client)).toEqual(["minecraft:cobblestone", "minecraft:dirt"]);
  await clickElement(client, { id: result("cobblestone") });
  await clickElement(client, { id: "folkways.selector.close" });
  await settlePanel(server, client);
  await clickBoardAction(client, opener);
  await waitForElement(client, { id: "folkways.selector.close" });
  await filterSearch(server, client, "*minecraft:cobblestone");
  await waitForElement(client, { id: result("cobblestone") });
  expect(await selectedFilterRules(client), "cancel must keep the previous multi-selection")
    .toEqual(["minecraft:cobblestone", "minecraft:dirt"]);
  await clickElement(client, { id: result("cobblestone") });
  await clickElement(client, { id: "folkways.selector.confirm" });
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent" });
  await settlePanel(server, client);
  await assertFilterState(client, rules, "white", 1);
  // Changing mode keeps dirt selected; cobblestone must still enter for the order.
  await clickBoardAction(client, (await boardAct(client, "do", "folkways.orders", rules.admission, 0)).id);
  await settlePanel(server, client);
  await assertFilterState(client, rules, "black", 1);
  await scrollElementIntoView(client, { id: `folkways.board.row.folkways.orders.${rules.filter}` });
  await media.screenshot(client, { path: shotPath("e2e", stage.name("container-rules")) });
  await scrollElementIntoView(client, { id: `folkways.board.row.folkways.orders.${rules.draft}` });
  await media.screenshot(client, { path: shotPath("e2e", stage.name("container-order-form")) });
  // Filters belong to a container, rather than the shared settings page.
  const sourceRules = await openContainerRules(server, client, playerName, source, stage);
  await assertFilterState(client, sourceRules, "black", 0);
  await clickBoardAction(client, (await boardAct(client, "edit", "folkways.orders", sourceRules.filter)).id);
  await waitForElement(client, { id: "folkways.selector.close" });
  expect(await selectedFilterRules(client), "the source does not inherit the target's filter").toEqual([]);
  await clickElement(client, { id: "folkways.selector.close" });
  const reopened = await openContainerRules(server, client, playerName, chest, stage);
  await assertFilterState(client, reopened, "black", 1);
  await clickBoardAction(client, (await boardAct(client, "edit", "folkways.orders", reopened.filter)).id);
  await waitForElement(client, { id: "folkways.selector.close" });
  expect(await selectedFilterRules(client), "reopening the target keeps its own filter").toEqual(["minecraft:dirt"]);
  await clickElement(client, { id: "folkways.selector.close" });

  await screen.dismiss(client);
}

async function assertFilterState(client: any, rules: { filter: number; admission: number }, mode: "black" | "white", count: number) {
  const actions = (await boardActs(client, "do", "folkways.orders")).filter(action => action.row === rules.admission);
  expect(actions.length, "blacklist and whitelist share one mutually exclusive mode button").toBe(1);
  await scrollElementIntoView(client, { id: actions[0].id });
  const tree = await panelElements(client);
  const summary = descendantsText(tree, `folkways.board.row.folkways.orders.${rules.filter}`);
  expect(summary, "filter summary names the current mode").toMatch(mode === "black" ? /blacklist/i : /whitelist/i);
  expect(summary.match(/[0-9]+/g), "filter summary counts selected items").toContain(String(count));
}

async function assertContainerForm(client: any, rules: { first: number; filter: number; admission: number; draft: number }, expectedOrders = 0) {
  const accessHeading = `folkways.board.row.folkways.orders.${rules.first - 1}`;
  const filterHeading = `folkways.board.row.folkways.orders.${rules.filter - 1}`;
  await scrollElementIntoView(client, { id: accessHeading });
  const elements = await panelElements(client);
  const access = elements.find(element => element.id === accessHeading)!;
  const filter = elements.find(element => element.id === filterHeading)!;
  expect(Math.abs(access.y - filter.y), "access and filter sections should share the top row").toBeLessThanOrEqual(1);
  expect(filter.x, "the filter section belongs beside access controls").toBeGreaterThan(access.x);
  for (const row of [rules.first, rules.first + 1]) {
    const toggle = await boardAct(client, "do", "folkways.orders", row);
    await scrollElementIntoView(client, { id: toggle.id });
    assertDrawnText(await panelElements(client), toggle.id, "access status button");
  }
  const filterActions = (await boardActs(client, "do", "folkways.orders"))
    .filter(act => act.row === rules.admission);
  for (const action of filterActions) {
    await scrollElementIntoView(client, { id: action.id });
    const tree = await panelElements(client);
    assertDrawnText(tree, action.id, "filter action button");
    expect(tree.find(element => element.id === action.id)!.width,
      "mode button should fit its short content, rather than stretching across the form").toBeLessThanOrEqual(110);
  }
  const fields = (await boardActs(client, "edit", "folkways.orders"))
    .filter(act => act.row === rules.draft);
  for (const field of fields) {
    await scrollElementIntoView(client, { id: field.id });
    const tree = await panelElements(client);
    const control = tree.find(element => element.id === field.id)!;
    if (control.className.endsWith(".TextField")) {
      const parent = tree.slice(0, control.index).reverse().find(element => element.depth === control.depth - 1)!;
      // A number alone is ambiguous: its containing field must also paint a readable caption.
      expect(control.width, "order number inputs need usable horizontal space").toBeGreaterThanOrEqual(48);
      const captions = assertDrawnText(tree, parent, "order number field caption");
      for (const caption of captions) {
        expect(caption.height, "numeric captions should fit in at most two lines").toBeLessThanOrEqual(36);
      }
    } else {
      assertDrawnText(tree, field.id, "order item button");
    }
  }
  const laidOut = await panelElements(client);
  const numbers = fields.map(field => laidOut.find(element => element.id === field.id)!)
    .filter(element => element.className.endsWith(".TextField"));
  expect(numbers.length, "the order form needs threshold and target inputs").toBe(2);
  expect(Math.abs(numbers[0].y - numbers[1].y), "threshold and target inputs should align horizontally")
    .toBeLessThanOrEqual(1);
  const actions = await boardActs(client, "do", "folkways.orders");
  const submit = actions.find(action => action.row > rules.draft)!.row;
  const picker = await boardAct(client, "edit", "folkways.orders", rules.filter);
  for (const button of [picker, ...actions.filter(action => action.row === submit)]) {
    await scrollElementIntoView(client, { id: button.id });
    const tree = await panelElements(client);
    assertDrawnText(tree, button.id, "filter picker or order submission button");
  }
  const orderRows = [...new Set(actions.filter(action => action.row > submit).map(action => action.row))];
  expect(orderRows.length, "filing another item must preserve each independent order").toBe(expectedOrders);
  for (const row of orderRows) {
    const token = `folkways.board.row.folkways.orders.${row}`;
    await scrollElementIntoView(client, { id: token });
    const tree = await panelElements(client);
    const entry = tree.find(element => element.id === token)!;
    const heading = tree.find(element => element.id === `folkways.board.row.folkways.orders.${submit + 1}`)!;
    expect(entry.visible, "existing order must be visible after scrolling to it").toBe(true);
    expect(entry.height, "existing order must occupy a nonempty row").toBeGreaterThan(0);
    expect(entry.width, "each existing order needs the full section width").toBeGreaterThanOrEqual(heading.width - 2);
    expect(entry.width, "existing orders need enough space for item, stock and action").toBeGreaterThanOrEqual(240);
  }
  // Return to the heading so the overview records the form's top instead of the last action clicked.
  const top = (await boardRows(client)).find(row => row.page === "folkways.orders")!;
  await scrollElementIntoView(client, { id: top.id });
}

function assertDrawnText(elements: Element[], target: string | Element, purpose: string) {
  const parent = typeof target === "string" ? elements.find(element => element.id === target)! : target;
  expect(parent, `${purpose} must exist`).toBeDefined();
  if (/^folkways[.]board[.](do|edit)[.]/.test(parent.id ?? "")) assertUnclippedButton(elements, parent, purpose);
  const labels: Element[] = [];
  for (let at = parent.index + 1; at < elements.length && elements[at].depth > parent.depth; at++) {
    const element = elements[at];
    if (!element.text?.trim() || !/(^|\.)(Label|TextElement)$/.test(element.className) || !element.displayed) continue;
    let depth = element.depth;
    let shown = true;
    for (let ancestor = at - 1; ancestor >= parent.index && depth > parent.depth; ancestor--) {
      if (elements[ancestor].depth < depth) {
        depth = elements[ancestor].depth;
        if (!elements[ancestor].displayed) shown = false;
      }
    }
    if (shown) labels.push(element);
  }
  expect(labels.length, `${purpose} must draw text`).toBeGreaterThan(0);
  for (const label of labels) {
    const message = `${purpose}: ${JSON.stringify(label.text)} needs a painted box inside its control`;
    expect(label.visible, message).toBe(true);
    expect(label.opacity, message).toBeGreaterThan(0);
    expect(label.width, message).toBeGreaterThan(0);
    expect(label.height, message).toBeGreaterThan(0);
    expect(label.x, message).toBeGreaterThanOrEqual(parent.x - 1);
    expect(label.y, message).toBeGreaterThanOrEqual(parent.y - 1);
    expect(label.x + label.width, message).toBeLessThanOrEqual(parent.x + parent.width + 1);
    expect(label.y + label.height, message).toBeLessThanOrEqual(parent.y + parent.height + 1);
  }
  return labels;
}

function assertUnclippedButton(elements: Element[], button: Element, purpose: string) {
  expect(button.visible, `${purpose} must be visible`).toBe(true);
  expect(button.displayed, `${purpose} must be displayed`).toBe(true);
  expect(button.width, `${purpose} needs a full painted button box`).toBeGreaterThan(0);
  expect(button.height, `${purpose} needs a full painted button box`).toBeGreaterThan(0);
  for (const ancestor of ancestorsOf(elements, button)) {
    const context = `${purpose} ${button.id} clipped by ${ancestor.id ?? ancestor.className} at depth ${ancestor.depth}`;
    expect(ancestor.displayed, context).toBe(true);
    expect(ancestor.visible, context).toBe(true);
    if (ancestor.overflowVisible) continue;
    expect(button.x, context).toBeGreaterThanOrEqual(ancestor.contentX - 1);
    expect(button.x + button.width, context).toBeLessThanOrEqual(ancestor.contentX + ancestor.contentWidth + 1);
    expect(button.y, context).toBeGreaterThanOrEqual(ancestor.contentY - 1);
    expect(button.y + button.height, context).toBeLessThanOrEqual(ancestor.contentY + ancestor.contentHeight + 1);
  }
}

async function previewChineseContainer(server: any, client: any, playerName: string, chest: any, stage: any) {
  await screen.dismiss(client);
  await client.run(CONTAINER_LANGUAGE, { code: "zh_cn" });
  for (const scale of [1, 3]) {
    await screen.dismiss(client);
    let viewport: { settled: boolean; width: number; height: number } | undefined;
    for (let attempt = 0; attempt < 120; attempt++) {
      viewport = await client.run(CONTAINER_SETTLED, { code: "zh_cn", scale });
      if (viewport?.settled) break;
      await frames(client, 5);
    }
    expect(viewport?.settled, `Chinese resource reload must finish at GUI scale ${scale}`).toBe(true);
    const rules = await openContainerRules(server, client, playerName, chest, stage);
    if (scale === 1) await assertContainerForm(client, rules, 2);
    for (const row of [rules.first, rules.first + 1]) {
      const action = await boardAct(client, "do", "folkways.orders", row);
      await scrollElementIntoView(client, { id: action.id });
      const elements = await panelElements(client);
      const control = elements.find(element => element.id === action.id)!;
      expect(control.width).toBeGreaterThan(0);
      expect(control.height).toBeGreaterThan(0);
      expect(control.x).toBeGreaterThanOrEqual(0);
      expect(control.y).toBeGreaterThanOrEqual(0);
      expect(control.x + control.width).toBeLessThanOrEqual(viewport!.width);
      expect(control.y + control.height).toBeLessThanOrEqual(viewport!.height);
      assertDrawnText(elements, action.id, `Chinese access button at GUI scale ${scale}`);
    }
    if (scale === 3) {
      const token = (await boardAct(client, "do", "folkways.orders", rules.first + 1)).id;
      const initial = await panelElements(client);
      const originalText = descendantsText(initial, token);
      await clickBoardAction(client, token);
      await settlePanel(server, client);
      expect(descendantsText(await panelElements(client), token), "compact store toggle responds to a click")
        .not.toEqual(originalText);
      await clickBoardAction(client, token);
      await settlePanel(server, client);
      expect(descendantsText(await panelElements(client), token), "second click restores original permission")
        .toEqual(originalText);
    }
    const top = (await boardRows(client)).find(row => row.page === "folkways.orders")!;
    await scrollElementIntoView(client, { id: top.id });
    await input.move(client, { x: 0, y: 0 });
    await frames(client, 3);
    await media.screenshot(client, {
      path: shotPath("e2e", stage.name(scale === 1 ? "container-overview-zh-cn" : "container-overview-zh-cn-compact")),
    });
  }
  await screen.dismiss(client);
}

function descendantsText(elements: Element[], token: string): string {
  const parent = elements.find(element => element.id === token)!;
  const words: string[] = [];
  for (let at = parent.index + 1; at < elements.length && elements[at].depth > parent.depth; at++) {
    if (elements[at].displayed && elements[at].text?.trim()) words.push(elements[at].text!);
  }
  return words.join(" ");
}

async function filterSearch(server: any, client: any, query: string) {
  await clickElement(client, { id: "folkways.selector.search" });
  await input.key(client, { keyCode: 269 });
  for (let stroke = 0; stroke < 64; stroke++) {
    await input.key(client, { keyCode: 259 });
  }
  await input.key(client, { text: query });
  await settlePanel(server, client);
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

async function clickBoardAction(client: any, token: string) {
  const match = /^folkways\.board\.(?:do|edit)\.(.+)\.(\d+)\.\d+$/.exec(token);
  if (!match) throw new Error(`not a board action: ${token}`);
  await scrollElementIntoView(client, { id: `folkways.board.row.${match[1]}.${match[2]}` });
  await clickElement(client, { id: token }, { scrollIntoView: true });
}

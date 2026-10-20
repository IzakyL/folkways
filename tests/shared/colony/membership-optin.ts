import { defineTask, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, scrollElementIntoView } from "../../ldlib2";
import { chooseInSelector, optInViaBook } from "./colony-founding";
import {
  boardAct,
  boardActs,
  openSite,
  typeCell,
} from "../ui/site-panel";
import { frames, safeAction, tryCommand, softly, waitForElement } from "../harness/bw-helpers";
import { GROUND, type Stage } from "../harness/stage";

type Vec = { x: number; y: number; z: number };

const ORDERS_PAGE = "folkways.orders";
const ACT_MAINTAIN = 0;
const ACT_DELIVER = 1;
const FILED_POLLS = 12;

const READ_ORDER_DRAFT = defineTask<{ player: string | null; chest: Vec; item: string }, {
  count: number; low: number; items: string[]; filed: { high: number; standing: boolean }[];
}>({
  name: "folkways.orders.read-draft", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.colony.Books", "io.github.izakyl.folkways.core.api.terms.Goods",
      "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.core.api.terms.Containers",
      "io.github.izakyl.folkways.plugins.orders.Orders", "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.front.api.Fronts", "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation"],
    body: `
var args = Args.of(ctx);
Object requested = args.value("player");
var actors = ctx.server().getPlayerList().getPlayers().stream()
    .filter(player -> requested == null || player.getGameProfile().getName().equals(requested))
    .filter(player -> Books.acting(player).isPresent()).toList();
var colonies = actors.stream().map(Books::acting).flatMap(Optional::stream).distinct().toList();
if (colonies.size() != 1) throw new IllegalStateException("order draft needs exactly one acting colony");
var settings = Fronts.of(colonies.getFirst()).settings(ResourceLocation.fromNamespaceAndPath("folkways", "orders"));
var items = settings.items("item").map(Goods::members).orElse(Set.of()).stream()
    .map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).sorted().toList();
var chest = (Map<?,?>) args.value("chest");
var cell = new BlockPos(((Number)chest.get("x")).intValue(), ((Number)chest.get("y")).intValue(),
    ((Number)chest.get("z")).intValue());
var level = actors.getFirst().serverLevel();
var into = WorldPos.of(level, Containers.anchor(level, cell));
var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("item"))).getDefaultInstance();
var filed = Orders.targets(colonies.getFirst()).stream()
    .filter(target -> target.into().equals(into) && Goods.matches(item, target.goods()))
    .map(target -> Map.of("high", target.high(), "standing", target.standing())).toList();
return Map.of("count", settings.count("count"), "low", settings.count("low"), "items", items, "filed", filed);
`,
  }),
});

export async function optInContainerViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec, stage: Stage = GROUND,
) {
  return optInViaBook(server, client, playerName, chest, "chest", stage);
}

export async function setMaintainDemandViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1, stage: Stage = GROUND, lowWater = 1,
) {
  return fileOrderViaSitePanel(server, client, playerName, chest, itemId, count, ACT_MAINTAIN, 0, stage, lowWater);
}

// The same order, filed from wherever the player stands and looks: a filmed shot keeps its camera still, and
// holds each step for beatMs so a viewer can follow it.
export async function setMaintainDemandInView(
  server: MinecraftServer, client: MinecraftClient, chest: Vec, itemId: string, count = 1, beatMs = 0,
) {
  return fileOrderViaSitePanel(server, client, null, chest, itemId, count, ACT_MAINTAIN, beatMs);
}

export async function deliverOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1, stage: Stage = GROUND,
) {
  return fileOrderViaSitePanel(server, client, playerName, chest, itemId, count, ACT_DELIVER, 0, stage);
}

// The site's panel open on its order draft, with the draft's item button: what an item dragged in lands on.
export async function openOrderDraft(
  server: MinecraftServer, client: MinecraftClient, chest: Vec,
) {
  const evidence: any = { chest };
  evidence.open = await openSite(server, client, chest);
  evidence.row = await orderDraftRow(client);
  evidence.edit_item = (await boardAct(client, "edit", ORDERS_PAGE, evidence.row, 0)).id;
  evidence.row_id = `folkways.board.row.${ORDERS_PAGE}.${evidence.row}`;
  await scrollElementIntoView(client, { id: evidence.edit_item });
  return evidence;
}

async function fileOrderViaSitePanel(
  server: MinecraftServer, client: MinecraftClient, playerName: string | null, chest: Vec,
  itemId: string, count: number, act: number, beatMs = 0, stage: Stage = GROUND, lowWater = 1,
) {
  const evidence: any = { chest, itemId, count, act };
  const beat = () => (beatMs > 0 ? new Promise((resolve) => setTimeout(resolve, beatMs)) : Promise.resolve());
  if (playerName !== null) {
    const seen = stage.stand(chest);
    await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
  }
  await tick.sprint(server, 5);
  await screen.dismiss(client);

  evidence.open = await openSite(server, client, chest, stage);
  await beat();
  const before = await ordersRowCount(client);
  evidence.rows_before = before;

  const draftRow = await orderDraftRow(client);
  const wanted = await boardAct(client, "edit", ORDERS_PAGE, draftRow, 0);
  evidence.edit_item = wanted.id;
  await softly(() => clickElement(client, { id: wanted.id }, { scrollIntoView: true }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  await beat();

  // The draft names one item, picked straight from the selector the item act opens.
  evidence.pick = await chooseInSelector(client, itemId, `*${itemId}`, evidence.edit_item);
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent", timeoutMs: 8_000 });
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  await beat();

  const countRow = await orderDraftRow(client);
  const howMany = await boardAct(client, "edit", ORDERS_PAGE, countRow, 2);
  evidence.edit_count = howMany.id;
  if (act === ACT_MAINTAIN) {
    const low = await boardAct(client, "edit", ORDERS_PAGE, countRow, 1);
    await scrollElementIntoView(client, { id: low.id });
    evidence.set_low = await typeCell(server, client, low.id, "1");
  }
  await beat();
  await scrollElementIntoView(client, { id: howMany.id });
  evidence.set_count = await typeCell(server, client, howMany.id, String(count));
  if (act === ACT_MAINTAIN && lowWater !== 1) {
    const low = await boardAct(client, "edit", ORDERS_PAGE, countRow, 1);
    evidence.set_low = await typeCell(server, client, low.id, String(lowWater));
  }

  const fileRow = await fileRowIndex(client);
  const pressed = await boardAct(client, "do", ORDERS_PAGE, fileRow, act);
  evidence.act_id = pressed.id;
  const observation = { player: playerName, chest: stage.at(chest), item: itemId };
  // Delivered keys do not prove the setting reached the server: panel refreshes can replace a focused field.
  // Observe the real draft before filing; retries still use the same visible keyboard interaction.
  evidence.draftAcknowledgements = [];
  let acknowledged = false;
  for (let attempt = 0; attempt < 3; attempt++) {
    // Finish scrolling before acknowledging the draft, then click without another scroll gesture.
    await scrollElementIntoView(client, { id: pressed.id });
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    const actual = await server.run(READ_ORDER_DRAFT, observation);
    evidence.draftAcknowledgements.push(actual);
    const itemMatches = actual.items.length === 1 && actual.items[0] === itemId;
    if (itemMatches && actual.count === count && (act !== ACT_MAINTAIN || actual.low === lowWater)) {
      acknowledged = true;
      break;
    }
    if (!itemMatches || attempt === 2) break;
    const retryRow = await orderDraftRow(client);
    if (actual.count !== count) {
      const retryCount = await boardAct(client, "edit", ORDERS_PAGE, retryRow, 2);
      await scrollElementIntoView(client, { id: retryCount.id });
      evidence.set_count = await typeCell(server, client, retryCount.id, String(count));
    }
    if (act === ACT_MAINTAIN && actual.low !== lowWater) {
      const retryLow = await boardAct(client, "edit", ORDERS_PAGE, retryRow, 1);
      await scrollElementIntoView(client, { id: retryLow.id });
      evidence.set_low = await typeCell(server, client, retryLow.id, String(lowWater));
    }
  }
  if (!acknowledged) throw new Error(`order: server draft did not acknowledge ${itemId} count=${count} low=${lowWater}: `
    + JSON.stringify(evidence.draftAcknowledgements));
  await beat();
  evidence.press = await softly(() => clickElement(client, { id: pressed.id }, { scrollIntoView: false }));

  for (let poll = 0; poll < FILED_POLLS; poll++) {
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 3));
    evidence.rows_after = await ordersRowCount(client);
    if (evidence.rows_after > before) {
      break;
    }
  }
  evidence.filedAcknowledgement = await server.run(READ_ORDER_DRAFT, observation);
  if (evidence.filedAcknowledgement.filed.some((filed: { high: number; standing: boolean }) =>
    filed.high !== count || filed.standing !== (act === ACT_MAINTAIN))) {
    throw new Error(`order: the filed server target differs from count=${count} standing=${act === ACT_MAINTAIN}: `
      + JSON.stringify(evidence.filedAcknowledgement));
  }
  await beat();
  await screen.dismiss(client);
  await tick.sprint(server, 20);

  if (!(evidence.rows_after > before)) {
    throw new Error(`order: after pressing ${pressed.id}, the Orders page for the site at ${chest.x},${chest.y},${chest.z}`
      + ` still has only ${evidence.rows_after} rows (${before} before ordering): the order never reached the colony`
      + ` (evidence: ${JSON.stringify(evidence)})`);
  }
  return evidence;
}

async function ordersRowCount(client: MinecraftClient): Promise<number> {
  const submit = await fileRowIndex(client);
  return new Set((await boardActs(client, "do", ORDERS_PAGE))
    .filter((act) => act.row > submit).map((act) => act.row)).size;
}

async function orderDraftRow(client: MinecraftClient): Promise<number> {
  const edits = await boardActs(client, "edit", ORDERS_PAGE);
  const rows = new Map<number, number>();
  for (const edit of edits) rows.set(edit.row, (rows.get(edit.row) ?? 0) + 1);
  const draft = [...rows].find(([, count]) => count === 3);
  if (!draft) throw new Error(`order: no item/threshold/target draft on this page (${JSON.stringify(edits)})`);
  return draft[0];
}

async function fileRowIndex(client: MinecraftClient): Promise<number> {
  const draft = await orderDraftRow(client);
  const acts = (await boardActs(client, "do", ORDERS_PAGE)).filter((act) => act.row > draft);
  const byRow = new Map<number, number>();
  for (const act of acts) {
    byRow.set(act.row, (byRow.get(act.row) ?? 0) + 1);
  }
  for (const [row, count] of byRow) {
    if (count >= 2) {
      return row;
    }
  }
  throw new Error(`order: no order row with two actions on this page (found ${JSON.stringify(acts.map((a) => a.id))})`);
}

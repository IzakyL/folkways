import { defineTask, javaTask, player, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../../ldlib2";
import { chooseInSelector, optInViaBook } from "./colony-founding";
import { typeCell } from "../ui/site-panel";
import { CARD_WINDOW, cardRows, openBlockCard, settleCard, waitForPresent } from "../ui/book-cards";
import { frames, safeAction, softly, waitForElement } from "../harness/bw-helpers";
import { GROUND, type Stage } from "../harness/stage";

type Vec = { x: number; y: number; z: number };

// A held container's card: the order draft (item, low water, how many), the buttons that file it, and the container's
// own orders listed under it.
export const ORDER_ITEM = "folkways.card.items.item";
export const ORDER_LOW = "folkways.card.number.low.field";
export const ORDER_COUNT = "folkways.card.number.count.field";
export const ORDER_MAINTAIN = "folkways.card.button.maintain";
export const ORDER_DELIVER = "folkways.card.button.deliver";
export const ORDERS_HERE = "orders_here";
const ACT_MAINTAIN = 0;
const ACT_DELIVER = 1;
const FILED_POLLS = 12;

const READ_ORDER_DRAFT = defineTask<{ player: string | null; chest: Vec; item: string }, {
  count: number; low: number; items: string[]; filed: { high: number; standing: boolean }[];
}>({
  name: "folkways.orders.read-draft", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.api.Books", "io.github.izakyl.folkways.core.item.api.Goods",
      "io.github.izakyl.folkways.core.world.api.WorldPos", "io.github.izakyl.folkways.core.item.api.Containers",
      "io.github.izakyl.folkways.vanilla.orders.Orders", "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.core.front.api.Fronts", "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation"],
    body: `
var args = Args.of(ctx);
Object requested = args.value("player");
var actors = ctx.server().getPlayerList().getPlayers().stream()
    .filter(player -> requested == null || player.getGameProfile().getName().equals(requested))
    .filter(player -> Books.acting(player).isPresent()).toList();
var colonies = actors.stream().map(Books::acting).flatMap(Optional::stream).distinct().toList();
if (colonies.size() != 1) throw new IllegalStateException("order draft needs exactly one acting colony");
var settings = Fronts.of(colonies.getFirst()).settings(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "orders"));
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

export async function filedOrdersAt(
  server: MinecraftServer, playerName: string, chest: Vec, itemId: string, stage: Stage = GROUND,
) {
  return (await server.run(READ_ORDER_DRAFT, { player: playerName, chest: stage.at(chest), item: itemId })).filed;
}

export async function optInContainerViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec, stage: Stage = GROUND,
) {
  return optInViaBook(server, client, playerName, chest, "chest", stage);
}

export async function setMaintainDemandViaUI(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1, stage: Stage = GROUND, lowWater = 1,
) {
  return fileOrderViaCard(server, client, playerName, chest, itemId, count, ACT_MAINTAIN, 0, stage, lowWater);
}

// The same order, filed from wherever the player stands and looks: a filmed shot keeps its camera still, and
// holds each step for beatMs so a viewer can follow it.
export async function setMaintainDemandInView(
  server: MinecraftServer, client: MinecraftClient, chest: Vec, itemId: string, count = 1, beatMs = 0,
  stage: Stage = GROUND, lowWater = 1,
) {
  return fileOrderViaCard(server, client, null, chest, itemId, count, ACT_MAINTAIN, beatMs, stage, lowWater);
}

export async function deliverOrderViaCard(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec,
  itemId: string, count = 1, stage: Stage = GROUND,
) {
  return fileOrderViaCard(server, client, playerName, chest, itemId, count, ACT_DELIVER, 0, stage);
}

// Open the container card and scroll to the button that opens the order item picker.
export async function openOrderDraft(
  server: MinecraftServer, client: MinecraftClient, playerName: string, chest: Vec, stage: Stage = GROUND,
) {
  const evidence: any = { chest };
  evidence.open = await openBlockCard(server, client, playerName, chest, stage);
  evidence.edit_item = ORDER_ITEM;
  await waitForPresent(server, client, ORDER_ITEM);
  return evidence;
}

// The card of the container the player looks at, opened with the book without moving them.
async function openCardInView(server: MinecraftServer, client: MinecraftClient) {
  await screen.dismiss(client);
  const click = await safeAction(() => player.click(client, { button: "right" }));
  await waitForElement(client, { id: CARD_WINDOW }, { timeoutMs: 8_000 });
  await settleCard(server, client);
  return { click };
}

async function fileOrderViaCard(
  server: MinecraftServer, client: MinecraftClient, playerName: string | null, chest: Vec,
  itemId: string, count: number, act: number, beatMs = 0, stage: Stage = GROUND, lowWater = 1,
) {
  const evidence: any = { chest, itemId, count, act };
  const beat = () => (beatMs > 0 ? new Promise((resolve) => setTimeout(resolve, beatMs)) : Promise.resolve());
  await tick.sprint(server, 5);
  evidence.open = playerName === null ? await openCardInView(server, client)
    : await openBlockCard(server, client, playerName, chest, stage);
  await beat();
  evidence.rows_before = (await cardRows(client, ORDERS_HERE)).length;

  await waitForPresent(server, client, ORDER_ITEM);
  evidence.edit_item = ORDER_ITEM;
  const opened = await softly(() => clickElement(client, { id: ORDER_ITEM }, { scrollIntoView: true }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  await beat();

  // The draft names one item, picked straight from the selector the item button opens.
  evidence.pick = await chooseInSelector(client, itemId, `*${itemId}`, opened);
  await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent", timeoutMs: 8_000 });
  await settleCard(server, client);
  await beat();

  evidence.edit_count = ORDER_COUNT;
  const maintaining = act === ACT_MAINTAIN;
  const pressed = maintaining ? ORDER_MAINTAIN : ORDER_DELIVER;
  evidence.act_id = pressed;
  const observation = { player: playerName, chest: stage.at(chest), item: itemId };
  // A new draft status can redraw the card and reset its scroll position. Wait for the server to accept the
  // picker selection before editing the counts, then file only once every changed value is acknowledged.
  evidence.draftAcknowledgements = [];
  let acknowledged = false;
  for (let attempt = 0; attempt < FILED_POLLS; attempt++) {
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 5));
    const actual = await server.run(READ_ORDER_DRAFT, observation);
    evidence.draftAcknowledgements.push(actual);
    if (actual.items.length !== 1 || actual.items[0] !== itemId) {
      continue;
    }
    if (maintaining && actual.low !== lowWater) {
      await waitForPresent(server, client, ORDER_LOW);
      evidence.set_low = await typeCell(server, client, ORDER_LOW, String(lowWater));
      await beat();
      continue;
    }
    if (actual.count !== count) {
      await waitForPresent(server, client, ORDER_COUNT);
      evidence.set_count = await typeCell(server, client, ORDER_COUNT, String(count));
      await beat();
      continue;
    }
    acknowledged = true;
    break;
  }
  if (!acknowledged) {
    throw new Error(`order: server draft did not acknowledge ${itemId} count=${count} low=${lowWater}: `
      + JSON.stringify(evidence.draftAcknowledgements));
  }
  await waitForPresent(server, client, pressed);
  await beat();
  evidence.press = await softly(() => clickElement(client, { id: pressed }, { scrollIntoView: false }));

  for (let poll = 0; poll < FILED_POLLS; poll++) {
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 3));
    evidence.rows_after = (await cardRows(client, ORDERS_HERE)).length;
    evidence.filedAcknowledgement = await server.run(READ_ORDER_DRAFT, observation);
    // Filing the same item at the same container replaces its target, so the row count can stay unchanged.
    // Require the exact server target as well as its visible row; a missing order is not an acknowledgement.
    const filed = evidence.filedAcknowledgement.filed;
    if (evidence.rows_after > 0 && filed.length === 1
      && filed[0].high === count && filed[0].standing === (act === ACT_MAINTAIN)) {
      break;
    }
  }
  const filed = evidence.filedAcknowledgement.filed;
  if (evidence.rows_after < 1 || filed.length !== 1
    || filed[0].high !== count || filed[0].standing !== (act === ACT_MAINTAIN)) {
    throw new Error(`order: the visible order and server target did not acknowledge count=${count} `
      + `standing=${act === ACT_MAINTAIN} (evidence: ${JSON.stringify(evidence)})`);
  }
  await beat();
  await screen.dismiss(client);
  await tick.sprint(server, 20);

  return evidence;
}

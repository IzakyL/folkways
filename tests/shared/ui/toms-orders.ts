import { defineTask, javaTask, player, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, waitForElement } from "../../ldlib2";
import { runColonyTask, type ColonyTaskValue } from "../colony/colony-tasks";
import { frames } from "../harness/bw-helpers";

// Ordering from the colony at a Tom's Simple Storage terminal (Vanilla's `vanilla.toms`), shared by the client
// spec and the promo takes. Craftable items open a quantity confirmation; "Order…" opens order management.

type Vec = { x: number; y: number; z: number };

/** The terminal button's label in en_us; the minecraft adapter finds vanilla widgets by label. */
export const ORDER_BUTTON = "Order…";

export const TOMS_ORDERS = {
  window: "folkways.window.toms_orders",
  search: "folkways.toms.orders.search",
  offers: "folkways.toms.orders.offers",
  amount: "folkways.toms.orders.amount",
  confirm: "folkways.toms.orders.confirm",
  back: "folkways.toms.orders.back",
  chosen: "folkways.toms.orders.chosen",
  said: "folkways.toms.orders.said",
  requests: "folkways.toms.orders.requests",
  add: (step: 1 | 10 | 64) => `folkways.toms.orders.add.${step}`,
  /** Rows and controls keyed by item id, written as Tokens.of writes it: `minecraft:iron_ingot` → `minecraft.iron_ingot`. */
  offer: (item: string) => `folkways.toms.orders.offer.${token(item)}`,
  request: (item: string) => `folkways.toms.orders.request.${token(item)}`,
  status: (item: string) => `folkways.toms.orders.status.${token(item)}`,
  cancel: (item: string) => `folkways.toms.orders.cancel.${token(item)}`,
} as const;

function token(item: string) {
  return item.replace(":", ".").replaceAll("/", ".");
}

/**
 * Builds a small Tom's network facing the player, with a stock chest and a furnace beside it: two barrels behind an
 * inventory connector, a storage terminal in front of the connector ({@code terminal}, connecting south). The
 * player's current colony (founded if they have none) takes the furnace as a work station. Enrol the terminal and
 * the chest with ENROLL_TOMS_NETWORK once the network has settled for a few ticks.
 */
export const STAGE_TOMS_NETWORK = defineTask<{ player: string; terminal: Vec; stock: Record<string, number> },
  ColonyTaskValue>({
  name: "folkways.toms.stage-network",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.tom.storagemod.block.AbstractStorageTerminalBlock",
      "io.github.izakyl.folkways.core.colony.api.Claimed",
      "io.github.izakyl.folkways.core.colony.api.Colony",
      "io.github.izakyl.folkways.core.colony.charter.Charters",
      "io.github.izakyl.folkways.core.colony.charter.CurrentColony",
      "io.github.izakyl.folkways.core.world.api.WorldPos",
      "io.github.izakyl.folkways.vanilla.wares.WaresContent",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
      "net.minecraft.world.level.block.Blocks",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var player = server.getPlayerList().getPlayerByName(args.string("player"));
var level = player.serverLevel();
var t = (Map<?, ?>) args.value("terminal");
BlockPos terminal = new BlockPos(((Number) t.get("x")).intValue(), ((Number) t.get("y")).intValue(),
    ((Number) t.get("z")).intValue());
BlockPos connector = terminal.south();
level.setBlockAndUpdate(connector, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("toms_storage:inventory_connector")).defaultBlockState());
level.setBlockAndUpdate(connector.east(), Blocks.BARREL.defaultBlockState());
level.setBlockAndUpdate(connector.east(2), Blocks.BARREL.defaultBlockState());
level.setBlockAndUpdate(terminal, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("toms_storage:storage_terminal"))
    .defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING, Direction.SOUTH));
BlockPos chest = connector.west(2);
BlockPos furnace = connector.west(3);
level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
var stock = (Map<?, ?>) args.value("stock");
int slot = 0;
for (var entry : stock.entrySet()) {
    ((Container) level.getBlockEntity(chest)).setItem(slot++, new ItemStack(
        BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(entry.getKey()))),
        ((Number) entry.getValue()).intValue()));
}
Colony colony = CurrentColony.of(player).orElseGet(() -> {
    Colony founded = Charters.found(player, "");
    CurrentColony.choose(player, founded);
    return founded;
});
colony.hold(WaresContent.STATION, new Claimed.Block(WorldPos.of(level, furnace)));
Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("colony_id", colony.id().toString());
out.put("connector", List.of(connector.getX(), connector.getY(), connector.getZ()));
out.put("chest", List.of(chest.getX(), chest.getY(), chest.getZ()));
out.put("furnace", List.of(furnace.getX(), furnace.getY(), furnace.getZ()));
return out;
`,
  }),
});

/** Registers the terminal (and with it the network's barrels) and the stock chest as colony stores, as the book does. */
export const ENROLL_TOMS_NETWORK = defineTask<{ player: string; colony_id: string; terminal: Vec; chest: number[] },
  ColonyTaskValue>({
  name: "folkways.toms.enroll-network",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.front.engine.colony.MemberToggle",
      "net.minecraft.core.BlockPos",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var player = server.getPlayerList().getPlayerByName(args.string("player"));
var colony = Colonies.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var t = (Map<?, ?>) args.value("terminal");
var c = args.list("chest");
Map<String, Object> out = new LinkedHashMap<String, Object>();
for (var entry : Map.of("terminal", new BlockPos(((Number) t.get("x")).intValue(), ((Number) t.get("y")).intValue(),
        ((Number) t.get("z")).intValue()), "chest", new BlockPos(((Number) c.get(0)).intValue(),
        ((Number) c.get(1)).intValue(), ((Number) c.get(2)).intValue())).entrySet()) {
    out.put(entry.getKey(), String.valueOf(MemberToggle.toggleAt(player.serverLevel(), colony, entry.getValue(), player)));
}
return out;
`,
  }),
});

/** Stages and enrols a terminal network for the player; answers the colony id and where the pieces stand. */
export async function stageTomsNetwork(server: MinecraftServer, playerName: string, terminal: Vec,
  stock: Record<string, number> = {}): Promise<ColonyTaskValue> {
  const staged = await runColonyTask(server, STAGE_TOMS_NETWORK, { player: playerName, terminal, stock });
  await tick.sprint(server, 40);
  const enrolled = await runColonyTask(server, ENROLL_TOMS_NETWORK,
    { player: playerName, colony_id: staged.colony_id, terminal, chest: staged.chest });
  await tick.sprint(server, 60);
  return { ...staged, enrolled };
}

/**
 * Opens the terminal the player looks at and waits for its "Order…" button (shown only on colony stores). `label` is
 * the button's text in the client's language (`folkways.toms.orders.open`), en_us by default.
 */
export async function openTomsTerminal(client: MinecraftClient, server: MinecraftServer, terminal: Vec,
  label = ORDER_BUTTON) {
  await player.useBlock(client, terminal, { face: "north" });
  for (let attempt = 0; attempt < 40; attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    const widgets = await screen.widgets(client).catch(() => [] as screen.Widget[]);
    if (widgets.some(widget => widget.label === label && widget.visible)) {
      return;
    }
  }
  throw new Error(`the terminal at ${JSON.stringify(terminal)} never showed "${label}": is it a colony store?`);
}

export type TomsOrder = {
  amount: number;
  catalog: { toggle: string; item: string };
  label?: string;
  pause?: (ms: number) => Promise<void>;
  beforeConfirm?: () => Promise<void>;
};

/** Places an order from the terminal's craftable grid and waits for the confirmation to return to the terminal. */
export async function orderAtTomsTerminal(client: MinecraftClient, server: MinecraftServer, order: TomsOrder) {
  await order.pause?.(1200);
  await screen.clickWidget(client, { label: order.catalog.toggle });
  for (let attempt = 0; attempt < 40; attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    if ((await screen.widgets(client)).some(widget => widget.label === order.catalog.item && widget.visible)) {
      break;
    }
  }
  await order.pause?.(1800);
  await screen.clickWidget(client, { label: order.catalog.item });
  await waitForElement(client, { id: TOMS_ORDERS.chosen, text: order.catalog.item });
  await order.pause?.(1200);
  let left = Math.max(0, Math.floor(order.amount) - 1);
  for (const step of [64, 10, 1] as const) {
    for (; left >= step; left -= step) {
      await clickElement(client, { id: TOMS_ORDERS.add(step) });
      await order.pause?.(450);
    }
  }
  await frames(client, 2);
  await order.beforeConfirm?.();
  await clickElement(client, { id: TOMS_ORDERS.confirm });
  for (let attempt = 0; attempt < 40; attempt++) {
    await tick.sprint(server, 1);
    await frames(client, 2);
    if ((await screen.widgets(client)).some(widget => widget.label === (order.label ?? ORDER_BUTTON) && widget.visible)) {
      return;
    }
  }
  const said = (await panelElements(client)).find(element => element.id === TOMS_ORDERS.said);
  throw new Error(`the terminal did not reopen after submitting the order: ${said?.text ?? ""}`);
}

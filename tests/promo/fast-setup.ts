import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { ADMIT_SETTLERS, BIND_COLONY, MARK_MEMBERS, runColonyTask } from "../e2e/colony-tasks";
import type { Vec } from "../e2e/colony-founding";

export async function foundPromoColonyFast(server: MinecraftServer, player: string, cells: Vec[]) {
  const bound = await runColonyTask(server, BIND_COLONY, { player });
  const colony_id = String(bound.colony_id);
  const members = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: cells.flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  return { colony_id, bound, members };
}

export async function admitPromoSettlersFast(
  server: MinecraftServer, player: string, colony_id: string, count: number,
) {
  const rounds = [];
  let residents = 0;
  while (residents < count) {
    const result = await runColonyTask(server, ADMIT_SETTLERS, {
      player, colony_id, count: count - residents,
    });
    const roster = Number(result.roster);
    if (!(roster > residents)) throw new Error(`Promo admission made no progress: ${JSON.stringify(result)}`);
    residents = roster;
    rounds.push(result);
  }
  return { residents, rounds, via: "task" };
}

export async function setMaintainDemandsFast(
  server: MinecraftServer,
  player: string,
  demands: Array<{ at: Vec; item: string; count: number; low?: number }>,
) {
  return runColonyTask(server, MAINTAIN_DEMANDS, { player, demands });
}

const MAINTAIN_DEMANDS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.maintain-demands",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.terms.ItemFilter",
      "io.github.izakyl.folkways.plugins.orders.OrdersContent",
      "io.github.izakyl.folkways.front.engine.colony.ColonyBoards",
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value",
      "java.util.ArrayList",
      "java.util.List",
      "java.util.Map",
      "java.util.Optional",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var player = server.getPlayerList().getPlayerByName(args.string("player"));
if (player == null) throw new IllegalStateException("No online promo player");
var level = player.serverLevel();
var filed = new ArrayList<Object>();
for (var raw : args.list("demands")) {
    var demand = (Map<?, ?>) raw;
    var cell = (Map<?, ?>) demand.get("at");
    var at = new BlockPos(((Number) cell.get("x")).intValue(),
        ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue());
    var colony = ColonyGround.ofBlock(level, at).orElseThrow(
        () -> new IllegalStateException("Demand container is not a colony member: " + at));
    var front = ColonyFront.of(colony);
    var item = ResourceLocation.parse(String.valueOf(demand.get("item")));
    int count = ((Number) demand.get("count")).intValue();
    front.setSetting(OrdersContent.ID, "item", new Value.Items(List.of(ItemFilter.item(item))));
    int low = demand.containsKey("low") ? ((Number) demand.get("low")).intValue() : 1;
    front.setSetting(OrdersContent.ID, "low", new Value.Count(low));
    front.setSetting(OrdersContent.ID, "count", new Value.Count(count));
    front.setSetting(OrdersContent.ID, "rank", new Value.Count(0));
    var draft = front.settings(OrdersContent.ID);
    if (draft.count("count") != count || draft.count("low") != low
            || !draft.items("item").flatMap(spec -> spec.item()).filter(item::equals).isPresent()) {
        throw new IllegalStateException("Demand draft did not retain " + demand);
    }
    var attempt = ColonyBoards.act(level, colony, OrdersContent.ID, Optional.of(at), "maintain")
        .orElseThrow(() -> new IllegalStateException("No maintain action at " + at));
    if (attempt.refusal().isPresent()) {
        throw new IllegalStateException("Maintain refused: " + attempt.refusal().get().key());
    }
    filed.add(Map.of("at", cell, "item", item.toString(), "count", count,
        "orders", colony.kept(OrdersContent.ID).getList("orders", 10).size()));
}
return Sync.stamp(Map.of("ok", true, "via", "task", "filed", filed));
`,
  }),
});

// Leaves each resident licensed for its own trades only, so a crew posted somewhere works there rather than
// crossing the colony for whatever any trade has open.
export async function licenseCrewFast(server: MinecraftServer, crew: Array<{ uuid: string; trades: string[] }>) {
  return runColonyTask(server, LICENSE_CREW, { crew });
}

const LICENSE_CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.license-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "java.util.ArrayList",
      "java.util.List",
      "java.util.Map",
      "java.util.UUID",
    ],
    body: `
var level = ctx.server().overworld();
var args = Args.of(ctx);
var licensed = new ArrayList<Object>();
for (var raw : args.list("crew")) {
    var one = (Map<?, ?>) raw;
    var entity = level.getEntity(UUID.fromString(String.valueOf(one.get("uuid"))));
    if (entity == null) throw new IllegalStateException("No resident " + one.get("uuid"));
    var trades = ((List<?>) one.get("trades")).stream().map(String::valueOf).toList();
    var body = Bodies.of(entity).orElseThrow();
    var allowed = new ArrayList<String>();
    for (var vocation : Vocations.all()) body.licences().trade(vocation).ifPresent(trade -> {
        boolean allow = trades.contains(vocation.id().getPath());
        body.licences().allow(trade, allow);
        if (allow) allowed.add(vocation.id().getPath());
    });
    if (allowed.size() != trades.size()) {
        throw new IllegalStateException(one.get("uuid") + " could only be licensed for " + allowed + " of " + trades);
    }
    licensed.add(Map.of("uuid", one.get("uuid"), "trades", allowed));
}
return Sync.stamp(Map.of("ok", true, "licensed", licensed));
`,
  }),
});

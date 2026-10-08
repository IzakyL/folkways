import { defineTask, javaTask, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";
import type { Vec } from "../e2e/colony-founding";
import { buildPen, type Pen } from "../e2e/pasture-tools";
import { buildRailLine, LOST_CARRIAGE, type RailResult } from "../e2e/rail-fixture";
import { varyVitals } from "../promo/vitals";
import { WIRE_NETWORK } from "../shared/integration-tasks";
import { commandPos, tryCommand } from "../shared/bw-helpers";

// A freshly founded colony shows every page empty: no stations, no zones, no orders, no sites, and every
// resident at the same priority with no levels. This lays a little of everything inside the founding pad
// (east of the beds, which run from origin.x along origin.z) so each page has rows to draw.

export type Stocked = {
  stores: Vec[];
  stations: Array<{ at: Vec; block: string }>;
  plots: Array<{ crop: string; min: Vec; max: Vec }>;
  pens: Array<{ animal: string; target: number; heads: number; pen: Pen }>;
  pond: { min: Vec; max: Vec };
  sites: Array<{ name: string; anchor: Vec; blocks: number; laid: number }>;
  receipt: Record<string, any>;
};

const STORE_ITEMS: Array<Array<[string, number]>> = [
  [["minecraft:bread", 40], ["minecraft:cooked_beef", 24], ["minecraft:apple", 12], ["minecraft:carrot", 30]],
  [["minecraft:oak_log", 64], ["minecraft:oak_planks", 96], ["minecraft:stick", 32], ["minecraft:cobblestone", 64]],
  [["minecraft:iron_ingot", 18], ["minecraft:coal", 40], ["minecraft:raw_iron", 20], ["minecraft:copper_ingot", 9]],
  [["minecraft:wheat", 20], ["minecraft:wheat_seeds", 48], ["minecraft:white_wool", 14], ["minecraft:leather", 6]],
];

const STATIONS = [
  "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace",
  "minecraft:crafting_table", "minecraft:stonecutter", "minecraft:smithing_table",
];

const DEMANDS: Array<{ store: number; item: string; count: number; low: number }> = [
  { store: 0, item: "minecraft:bread", count: 64, low: 16 },
  { store: 1, item: "minecraft:oak_planks", count: 192, low: 64 },
  { store: 2, item: "minecraft:iron_ingot", count: 32, low: 8 },
  { store: 0, item: "minecraft:cooked_mutton", count: 32, low: 8 },
];

export async function stockColony(
  server: MinecraftServer, playerName: string, colonyId: string, origin: Vec,
): Promise<Stocked> {
  const y = origin.y;
  const at = (dx: number, dz: number, dy = 0): Vec => ({ x: origin.x + dx, y: y + dy, z: origin.z + dz });

  const stores = STORE_ITEMS.map((_, i) => at(i * 2, 3));
  for (const [i, store] of stores.entries()) {
    const items = STORE_ITEMS[i].map(([id, count], slot) => `{Slot:${slot}b,id:"${id}",count:${count}}`).join(",");
    await tryCommand(server, `setblock ${commandPos(store)} minecraft:chest[facing=south]{Items:[${items}]}`);
  }

  const stations = STATIONS.map((block, i) => ({ at: at(i * 2, 6), block }));
  for (const station of stations) {
    await tryCommand(server, `setblock ${commandPos(station.at)} ${station.block}`);
  }
  // Some fuel and work in the cookers, so their rows read part-stocked rather than all bare.
  await tryCommand(server, `item replace block ${commandPos(stations[0].at)} container.0 with minecraft:raw_iron 8`);
  await tryCommand(server, `item replace block ${commandPos(stations[0].at)} container.1 with minecraft:coal 5`);
  await tryCommand(server, `item replace block ${commandPos(stations[1].at)} container.1 with minecraft:charcoal 2`);

  const plots = [
    { crop: "minecraft:wheat", min: at(-12, -12, -1), max: at(-8, -8, -1) },
    { crop: "minecraft:carrots", min: at(-6, -12, -1), max: at(-2, -8, -1) },
  ];
  for (const plot of plots) {
    await tryCommand(server, `fill ${commandPos(plot.min)} ${commandPos(plot.max)} minecraft:farmland[moisture=7]`);
    for (let x = plot.min.x; x <= plot.max.x; x++) {
      for (let z = plot.min.z; z <= plot.max.z; z++) {
        const age = (x * 7 + z * 3) & 7;
        if (age === 0) continue;
        await tryCommand(server, `setblock ${x} ${y} ${z} ${plot.crop}[age=${age}]`);
      }
    }
  }

  const pens = [
    { animal: "sheep", target: 6, heads: 2, pen: penAt(at(3, -12, -1), 4) },
    { animal: "cow", target: 3, heads: 5, pen: penAt(at(10, -12, -1), 4) },
  ];
  for (const plan of pens) {
    await buildPen(server, plan.pen);
    for (let i = 0; i < plan.heads; i++) {
      const spot = { x: plan.pen.min.x + 0.5 + (i % 3), y, z: plan.pen.min.z + 0.5 + Math.floor(i / 3) * 2 };
      await tryCommand(server, `summon minecraft:${plan.animal} ${spot.x} ${spot.y} ${spot.z}`);
    }
  }

  const pond = { min: at(-12, 6, -1), max: at(-8, 10, -1) };
  await tryCommand(server, `fill ${commandPos({ ...pond.min, y: y - 3 })} ${commandPos(pond.max)} minecraft:water`);

  const sites = [
    { name: "Lodge", anchor: at(-6, 6), blocks: lodge(), laid: 0 },
    { name: "Well", anchor: at(14, 3), blocks: well(), laid: 0 },
  ];
  // Half the lodge is already up, so its row shows a meter partway along instead of at zero.
  const lodgeBlocks = sites[0].blocks;
  const raised = lodgeBlocks.filter(block => block.offset[1] <= 1);
  for (const block of raised) {
    const cell = { x: sites[0].anchor.x + block.offset[0], y: sites[0].anchor.y + block.offset[1], z: sites[0].anchor.z + block.offset[2] };
    await tryCommand(server, `setblock ${commandPos(cell)} ${block.state}`);
  }
  sites[0].laid = raised.length;
  await tick.sprint(server, 3);

  const receipt = await runColonyTask(server, STOCK_COLONY, {
    player: playerName,
    colony_id: colonyId,
    members: [...stores, ...stations.map(station => station.at)].map(cell => [cell.x, cell.y, cell.z]),
    demands: DEMANDS.map(demand => ({ ...demand, at: stores[demand.store] })),
    zones: [
      ...plots.map(plot => ({ kind: "folkways:farm", cells: boxCells(plot.min, plot.max), choice: { crop: plot.crop } })),
      ...pens.map(plan => ({
        kind: "folkways:pasture", cells: boxCells(plan.pen.min, plan.pen.max),
        choice: { animal: `minecraft:${plan.animal}` }, count: { target: plan.target },
      })),
      { kind: "folkways:fish", cells: boxCells(pond.min, pond.max) },
    ],
    sites: sites.map(site => ({ name: site.name, anchor: [site.anchor.x, site.anchor.y, site.anchor.z], blocks: site.blocks })),
  });
  receipt.vitals = await varyVitals(server, colonyId);
  return { stores, stations, plots, pens, pond, sites: sites.map(({ blocks, ...site }) => ({ ...site, blocks: blocks.length })), receipt };
}

// A small Create package network south of the stations, laid as the bench lays its own: a stocked chest feeding a
// packager and stock link, a frogport on the packager and one across the yard, joined by a powered chain, and a
// stock ticker with a keeper's seat. The colony takes the network on, so the dispatch page lists it and its ports.
export async function stockDispatch(server: MinecraftServer, playerName: string, colonyId: string, origin: Vec) {
  const at = (dx: number, dz: number, dy = 0): Vec => ({ x: origin.x + dx, y: origin.y + dy, z: origin.z + dz });
  const source = at(-10, 14), packager = at(-10, 15), link = at(-9, 15), send = at(-10, 15, 1);
  const receive = at(3, 16), ticker = at(0, 13), target = at(6, 16);
  const chains = [at(-10, 15, 5), at(3, 15, 5)];
  for (const [cell, block] of [
    [source, "minecraft:chest[facing=north]"], [packager, "create:packager[facing=south]"],
    [link, "create:stock_link[face=wall,facing=east]"], [send, "create:package_frogport"],
    [receive, "create:package_frogport"], [ticker, "create:stock_ticker[facing=south]"],
    [at(0, 14), "create:orange_seat"], [target, "minecraft:chest[facing=south]"],
    ...chains.map((chain): [Vec, string] => [chain, "create:chain_conveyor"]),
    [at(-10, 15, 4), "create:creative_motor[facing=up]"],
  ] as Array<[Vec, string]>) {
    await world.command(server, `setblock ${commandPos(cell)} ${block}`);
  }
  for (let slot = 0; slot < 6; slot++) {
    await world.command(server, `item replace block ${commandPos(source)} container.${slot} with minecraft:spruce_log 64`);
  }
  await tick.sprint(server, 5);
  return runColonyTask(server, WIRE_NETWORK, {
    player: playerName, colony_id: colonyId, source, packager, link, send, receive, ticker, target, chains,
    assign_crew: false,
  });
}

const RAIL_LINE = 24;
const RAIL_ATTEMPTS = 2;

// A two-station Create line outside the east wall, its train scheduled and handed to the colony, for the rail page.
// buildRailLine moves the colony book to the off hand to hand the train over; it goes back to the main hand after.
export async function stockRail(
  server: MinecraftServer, client: MinecraftClient, playerName: string, origin: Vec, anchor: Vec,
): Promise<RailResult> {
  const playerUuid = (await world.players(server)).find((p: any) => p.name === playerName)?.uuid;
  if (!playerUuid) throw new Error(`no online player named ${playerName}`);
  const railOrigin: Vec = { x: origin.x + 30, y: origin.y, z: origin.z + 8 };
  await tryCommand(server, `item replace entity ${playerName} inventory.0 from entity ${playerName} weapon.mainhand`);
  let rail: RailResult | undefined;
  for (let attempt = 1; attempt <= RAIL_ATTEMPTS; attempt++) {
    rail = await buildRailLine(server, client, playerName, playerUuid, railOrigin, RAIL_LINE, "inventory.0", { anchor });
    if (rail.ok || rail.error !== LOST_CARRIAGE) break;
  }
  await tryCommand(server, `item replace entity ${playerName} weapon.mainhand from entity ${playerName} inventory.0`);
  await tryCommand(server, `item replace entity ${playerName} weapon.offhand with minecraft:air`);
  await tryCommand(server, `gamemode survival ${playerName}`);
  // buildRailLine ops the player, and an op is told of every tick sprint in chat, across every shot after.
  await tryCommand(server, `deop ${playerName}`);
  if (!rail?.ok) {
    throw new Error(`the rail line for the rail page did not come up: ${rail?.error ?? ""} ${JSON.stringify(rail?.steps).slice(0, 800)}`);
  }
  return rail;
}

function penAt(min: Vec, size: number): Pen {
  const max = { x: min.x + size - 1, y: min.y, z: min.z + size - 1 };
  const gate = { x: min.x - 1, y: min.y + 1, z: min.z };
  const fences: Vec[] = [];
  for (let x = min.x - 1; x <= max.x + 1; x++) {
    for (let z = min.z - 1; z <= max.z + 1; z++) {
      if (x >= min.x && x <= max.x && z >= min.z && z <= max.z) continue;
      if (x === gate.x && z === gate.z) continue;
      fences.push({ x, y: min.y + 1, z });
    }
  }
  return { min, max, standInside: { x: min.x + size / 2, y: min.y + 1, z: min.z + size / 2 }, fences, gate };
}

function boxCells(min: Vec, max: Vec): number[][] {
  const cells: number[][] = [];
  for (let x = min.x; x <= max.x; x++) {
    for (let y = min.y; y <= max.y; y++) {
      for (let z = min.z; z <= max.z; z++) cells.push([x, y, z]);
    }
  }
  return cells;
}

type BlueprintBlock = { offset: [number, number, number]; state: string };

// A 5x5 cobblestone-floored hut with plank walls, a door gap and a slab roof.
function lodge(): BlueprintBlock[] {
  const blocks: BlueprintBlock[] = [];
  for (let x = 0; x < 5; x++) {
    for (let z = 0; z < 5; z++) {
      blocks.push({ offset: [x, 0, z], state: "minecraft:cobblestone" });
      blocks.push({ offset: [x, 4, z], state: "minecraft:oak_slab" });
      const edge = x === 0 || x === 4 || z === 0 || z === 4;
      if (!edge) continue;
      for (let h = 1; h <= 3; h++) {
        if (x === 2 && z === 4 && h <= 2) continue;
        const corner = (x === 0 || x === 4) && (z === 0 || z === 4);
        blocks.push({ offset: [x, h, z], state: corner ? "minecraft:oak_log" : "minecraft:oak_planks" });
      }
    }
  }
  return blocks;
}

function well(): BlueprintBlock[] {
  const blocks: BlueprintBlock[] = [];
  for (let x = 0; x < 3; x++) {
    for (let z = 0; z < 3; z++) {
      const middle = x === 1 && z === 1;
      if (!middle) blocks.push({ offset: [x, 0, z], state: "minecraft:stone_bricks" });
      if (x !== 1 && z !== 1) {
        blocks.push({ offset: [x, 1, z], state: "minecraft:cobblestone_wall" });
        blocks.push({ offset: [x, 2, z], state: "minecraft:oak_fence" });
      }
      blocks.push({ offset: [x, 3, z], state: "minecraft:stone_brick_slab" });
    }
  }
  return blocks;
}

const STOCK_COLONY = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.visual.stock-colony",
  side: "server",
  timeoutMs: 30_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.core.api.resident.Resident",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.resident.body.Body",
      "io.github.izakyl.folkways.core.api.resident.body.Keenness",
      "io.github.izakyl.folkways.core.api.terms.ItemFilter",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "io.github.izakyl.folkways.front.engine.colony.ColonyBoards",
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value",
      "io.github.izakyl.folkways.front.engine.colony.MemberToggle",
      "io.github.izakyl.folkways.plugins.build.Blueprint",
      "io.github.izakyl.folkways.plugins.build.BlueprintBlock",
      "io.github.izakyl.folkways.plugins.build.BlueprintVolume",
      "io.github.izakyl.folkways.plugins.build.BuildContent",
      "io.github.izakyl.folkways.plugins.orders.OrdersContent",
      "java.lang.reflect.Method",
      "net.minecraft.commands.arguments.blocks.BlockStateParser",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.entity.Entity",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var player = server.getPlayerList().getPlayerByName(args.string("player"));
if (player == null) throw Fail.notFound("no online player named " + args.string("player"));
ServerLevel level = player.serverLevel();
Colony colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id")))
    .orElseThrow(() -> Fail.notFound("no colony " + args.string("colony_id")));
var front = ColonyFront.of(colony);
var out = new LinkedHashMap<String, Object>();

var members = new ArrayList<Object>();
for (Object raw : args.list("members")) {
    BlockPos at = pos(raw);
    if (!ColonyGround.holds(level, colony, at)) {
        var refused = MemberToggle.toggleAt(level, colony, at, player).refusal();
        if (refused.isPresent()) throw Fail.invalid("member " + at.toShortString() + " refused: " + refused.get().key());
    }
    if (!ColonyGround.holds(level, colony, at)) throw Fail.invalid("member " + at.toShortString() + " did not join");
    members.add(at.toShortString());
}
out.put("members", members);

var zones = new ArrayList<Object>();
for (Object raw : args.list("zones")) {
    var zone = (Map<?, ?>) raw;
    var settings = ColonySettings.empty();
    for (var e : mapOf(zone.get("choice")).entrySet()) {
        settings = settings.with(String.valueOf(e.getKey()), new Value.Choice(ResourceLocation.parse(String.valueOf(e.getValue()))));
    }
    for (var e : mapOf(zone.get("count")).entrySet()) {
        settings = settings.with(String.valueOf(e.getKey()), new Value.Count(((Number) e.getValue()).intValue()));
    }
    var cells = new LinkedHashSet<BlockPos>();
    for (Object cell : (List<?>) zone.get("cells")) cells.add(pos(cell));
    var kind = ResourceLocation.parse(String.valueOf(zone.get("kind")));
    var added = front.addZone(level, kind, cells, settings)
        .orElseThrow(() -> Fail.invalid("the colony would not hold a " + kind + " zone of " + cells.size() + " cells"));
    zones.add(Map.of("kind", kind.toString(), "id", added.id().toString(), "cells", cells.size()));
}
out.put("zones", zones);

var orders = new ArrayList<Object>();
for (Object raw : args.list("demands")) {
    var demand = (Map<?, ?>) raw;
    BlockPos at = posOf((Map<?, ?>) demand.get("at"));
    var item = ResourceLocation.parse(String.valueOf(demand.get("item")));
    front.setSetting(OrdersContent.ID, "item", new Value.Items(List.of(ItemFilter.item(item))));
    front.setSetting(OrdersContent.ID, "low", new Value.Count(((Number) demand.get("low")).intValue()));
    front.setSetting(OrdersContent.ID, "count", new Value.Count(((Number) demand.get("count")).intValue()));
    front.setSetting(OrdersContent.ID, "rank", new Value.Count(0));
    var attempt = ColonyBoards.act(level, colony, OrdersContent.ID, Optional.of(at), "maintain")
        .orElseThrow(() -> Fail.invalid("no maintain act at " + at.toShortString()));
    if (attempt.refusal().isPresent()) throw Fail.invalid("maintain " + item + " refused: " + attempt.refusal().get().key());
    orders.add(item.toString());
}
out.put("orders", orders);

Object build = colony.service(BuildContent.ID, Object.class)
    .orElseThrow(() -> Fail.unavailable("the colony has no folkways:build presence"));
Method file = build.getClass().getDeclaredMethod("file", ServerLevel.class, Blueprint.class, BlockPos.class,
    String.class, Optional.class, String.class);
file.setAccessible(true);
var sites = new ArrayList<Object>();
for (Object raw : args.list("sites")) {
    var site = (Map<?, ?>) raw;
    var blocks = new ArrayList<BlueprintBlock>();
    var offsets = new ArrayList<BlockPos>();
    for (Object one : (List<?>) site.get("blocks")) {
        var block = (Map<?, ?>) one;
        BlockPos offset = pos(block.get("offset"));
        var state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
            String.valueOf(block.get("state")), false).blockState();
        blocks.add(new BlueprintBlock(offset, state));
        offsets.add(offset);
    }
    String name = String.valueOf(site.get("name"));
    var volume = BlueprintVolume.around(offsets).orElseThrow(() -> Fail.invalid(name + " is too large for a blueprint"));
    var blueprint = new Blueprint(UUID.randomUUID(), name, blocks, volume, level.getGameTime());
    var filed = (Optional<?>) file.invoke(build, level, blueprint, pos(site.get("anchor")), name,
        Optional.of(player.getUUID()), player.getGameProfile().getName());
    if (filed.isEmpty()) throw Fail.invalid("the build site " + name + " was not filed");
    sites.add(name);
}
out.put("sites", sites);

// BodyPerks is package-private; its xpForLevel is what a level costs.
Method xpForLevel = Class.forName("io.github.izakyl.folkways.core.api.resident.body.BodyPerks")
    .getDeclaredMethod("xpForLevel", int.class);
xpForLevel.setAccessible(true);
// Spread priorities and levels, so the residents table is not one column of 3s and every growth track at 0.
var residents = new ArrayList<Object>();
int i = 0;
for (Resident resident : colony.residents()) {
    Entity entity = level.getEntity(resident.id());
    var body = entity == null ? Optional.<Body>empty() : Bodies.of(entity);
    if (body.isEmpty()) continue;
    int j = 0;
    var set = new LinkedHashMap<String, Object>();
    for (var vocation : Vocations.all()) {
        int number = 1 + (i * 3 + j * 2) % Keenness.values().length;
        int rank = (i + j * 2) % 5;
        var trade = body.get().licences().trade(vocation);
        if (trade.isPresent()) body.get().licences().setKeenness(trade.get(), Keenness.ofNumber(number).orElseThrow());
        if (rank > 0) Bodies.credit(body.get(), vocation, (Integer) xpForLevel.invoke(null, rank) + 3 * i, level.getRandom());
        set.put(vocation.id().getPath(), List.of(number, rank));
        j++;
    }
    residents.add(Map.of("uuid", resident.id().toString(), "set", set));
    i++;
}
out.put("residents", residents);
out.put("ok", Boolean.TRUE);
return Sync.stamp(out);
`,
    members: `
private static BlockPos pos(Object triple) {
    var p = (List<?>) triple;
    return new BlockPos(((Number) p.get(0)).intValue(), ((Number) p.get(1)).intValue(), ((Number) p.get(2)).intValue());
}

private static Map<?, ?> mapOf(Object raw) {
    return raw == null ? Map.of() : (Map<?, ?>) raw;
}

private static BlockPos posOf(Map<?, ?> cell) {
    return new BlockPos(((Number) cell.get("x")).intValue(), ((Number) cell.get("y")).intValue(), ((Number) cell.get("z")).intValue());
}
`,
  }),
});

import { WIRE_NETWORK, NETWORK_STATE } from "../shared/integration-tasks";
import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer } from "../e2e/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../e2e/colony-tasks";
import { BUDGET } from "../shared/budgets";
import { setMaintainDemandsFast } from "./fast-setup";
import { cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, sleepMs, take, writeLook,
  type PromoPair } from "./promo-kit";
import { shaderEvidence } from "./shaders";
import { commandPos, tryCommand, frames, safeAction } from "../shared/bw-helpers";
import { handOffSchematic } from "../e2e/schematic-tools";
import { DATA_VERSION, encodeNbtFile } from "../e2e/colony-nbt";
import { outPath } from "../out-paths";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

const RESIDENTS = 12;
// Resident order matches the colony roster; the first one keeps the stock ticker.
const ROLES = ["keeper", "crafting", "crafting", "building", "building", "building",
  "farming", "farming", "herding", "herding", "fishing", "fishing"];
const CLIP_SECONDS = 60;
const MIN_BUILT = 24;
const MOTOR_RPM = 256;
const PREROLL_TICKS = 2400;

test("colony orders arrive through a Create logistics network", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("logistics");
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("logistics");
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `op ${player}`);
    await cinematicWorld(server);
    const origin = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    const at = (x: number, z: number, dy = 0) => ({ x: origin.x + x, y: origin.y + dy, z: origin.z + z });
    // Beds and the colony stand sit far behind the yard, out of shot.
    const founded = await foundColonyFast(server, client, player, { origin: at(0, -46), residentCount: RESIDENTS });
    const colony_id = founded.evidence.colonyId;
    await cinematicWorld(server);
    for (const [x0, x1] of [[-44, -2], [-1, 40]])
      await tryCommand(server, `fill ${commandPos(at(x0, -23))} ${commandPos(at(x1, 40, 12))} minecraft:air`);
    await world.command(server, `fill ${commandPos(at(-38, -23, -1))} ${commandPos(at(38, 48, -1))} minecraft:stone_bricks`);
    // The camera stands on the ground just behind the keeper, looking up and to the right across the yard. One full-size
    // warehouse (item vault) fills the left edge with its packager, stock link, frogport, stock ticker and seated keeper
    // packed against its east end.
    const vault = { from: at(-22, 22), to: at(-14, 24, 2) };
    const packager = at(-13, 23);
    const link = at(-13, 22);
    const send = at(-13, 23, 1);
    const source = vault.from;
    // Create only hands a stock ticker to a keeper seated beside it, level with it or one block down.
    const ticker = at(-12, 23);
    const seat = at(-11, 23);
    // Parcels land at three frogports, one beside each site that uses logs: the workshop crafting planks and sticks
    // near right, the cabin going up in the middle and a log tower going up far left. The colony collects from the port
    // nearest the chest that asked, so each site draws its own parcels across the net.
    const receive = at(15, 20);
    const target = at(13, 22);
    const store = at(17, 22);
    const bench = at(12, 19);
    const secondBench = at(18, 19);
    const kitChests = [at(19, 22), at(20, 22)];
    const cabinPort = at(4, 11);
    const towerPort = at(-14, 0);
    // Builders lay logs straight from these piles; crafters would otherwise turn every delivered log into planks.
    const logPile = at(5, 5);
    const towerPile = at(-13, -5);
    // Farm far right, sheep pen in the middle distance, fishing pond near right, well apart from each other.
    const fields = [{ min: at(18, 0, -1), max: at(25, 3) }, { min: at(18, 5, -1), max: at(25, 8) }];
    const pen = { min: at(-6, -10, -1), max: at(2, -4) };
    const pond = { min: at(24, 12, -2), max: at(30, 16, -1) };
    const banks = [{ min: at(25, 17, -1), max: at(27, 17, -1) }, { min: at(28, 17, -1), max: at(30, 17, -1) }];
    const yardChests = [at(16, 4), at(5, -6), at(31, 18)];
    // Five pulleys: the warehouse spur, a hub over the yard, and one above each receiving frogport.
    const chains = [at(-10, 21, 4), at(-2, 15, 8), at(15, 17, 5), at(4, 8, 5), at(-14, -3, 5)];
    const edges = [[0, 1], [1, 2], [1, 3], [1, 4], [2, 3]];
    const ports = [
      { port: cabinPort, chain: 3, address: "Cabin" },
      // Left without an address: the colony sends to a pick-up point by where it is, not by what it is called.
      { port: towerPort, chain: 4, address: "" },
    ];
    const motor = chains[1];
    type Placed = [ReturnType<typeof at>, string];
    const blocks: Placed[] = [
      [packager, "create:packager[facing=east]"],
      [link, "create:stock_link[face=wall,facing=north]"],
      [send, "create:package_frogport"],
      ...[receive, cabinPort, towerPort].map(pos => [pos, "create:package_frogport"] as Placed),
      [ticker, "create:stock_ticker[facing=east]"], [seat, "create:orange_seat"],
      [target, "minecraft:chest[facing=south]"], [store, "minecraft:chest[facing=south]"], [bench, "minecraft:crafting_table"],
      [secondBench, "minecraft:crafting_table"],
      ...[logPile, towerPile, ...kitChests, ...yardChests].map(pos => [pos, "minecraft:chest[facing=south]"] as Placed),
      ...chains.map(pos => [pos, "create:chain_conveyor"] as Placed),
      [{ ...motor, y: origin.y - 3 }, `create:creative_motor[facing=up]{ScrollValue:${MOTOR_RPM}}`],
    ];
    await world.command(server,
      `fill ${commandPos(vault.from)} ${commandPos(vault.to)} create:item_vault[axis=x]`);
    for (const chain of chains)
      await world.command(server, `fill ${chain.x} ${origin.y} ${chain.z} ${chain.x} ${chain.y - 1} ${chain.z} create:shaft[axis=y]`);
    await world.command(server, `fill ${motor.x} ${origin.y - 2} ${motor.z} ${motor.x} ${origin.y - 1} ${motor.z} create:shaft[axis=y]`);
    for (const [pos, block] of blocks) await world.command(server, `setblock ${commandPos(pos)} ${block}`);
    // Two wheat plots either side of a water channel, a fenced sheep pen on grass and a fishing pond.
    for (const field of fields)
      await world.command(server, `fill ${commandPos(field.min)} ${commandPos({ ...field.max, y: field.min.y })} minecraft:dirt`);
    await world.command(server, `fill ${commandPos(at(18, 4, -1))} ${commandPos(at(25, 4, -1))} minecraft:water`);
    await world.command(server, `fill ${commandPos(pen.min)} ${commandPos({ ...pen.max, y: pen.min.y })} minecraft:grass_block`);
    for (const [from, to] of [[at(-7, -11), at(3, -11)], [at(-7, -3), at(3, -3)], [at(-7, -11), at(-7, -3)], [at(3, -11), at(3, -3)]])
      await world.command(server, `fill ${commandPos(from)} ${commandPos(to)} minecraft:oak_fence`);
    await world.command(server, `setblock ${commandPos(at(-2, -3))} minecraft:oak_fence_gate[facing=south]`);
    await world.command(server, `fill ${commandPos(pond.min)} ${commandPos(pond.max)} minecraft:water`);
    for (let sheep = 0; sheep < 4; sheep++) {
      const spot = at(-5 + sheep * 2, -7);
      await world.command(server, `summon minecraft:sheep ${spot.x + 0.5} ${spot.y} ${spot.z + 0.5} {Age:0,PersistenceRequired:1b}`);
    }
    // Wheat ripens within seconds, so the farmers keep harvesting and replanting.
    await world.command(server, "gamerule randomTickSpeed 100");
    await tick.sprint(server, 5);
    run.note("stocked", await runColonyTask(server, STOCK_UP, {
      vaults: Array.from({ length: 8 }, () => vault.from), item: "minecraft:oak_log", count: 64 }));
    await tick.sprint(server, 20);
    run.note("members", await runColonyTask(server, MARK_MEMBERS, {
      player, colony_id, cells: [target, store, bench, secondBench, logPile, towerPile, ...kitChests, ...yardChests].flatMap(p => [p.x, p.y, p.z]),
    }));
    const args = { player, colony_id, source, packager, link, send, receive, ticker, target, store, chains,
      send_chains: [0], receive_chain: 2, edges };
    const network = await runColonyTask(server, WIRE_NETWORK, args);
    run.note("network", network);
    run.note("ports", await runColonyTask(server, OPEN_PORTS, { chains, ports }));
    // The book in member mode reads any piece of the network as the whole of it: a chain gives it back, a port far
    // down the line takes it on again, and every port along the chains comes with it.
    // With its motor gone the chains stand still, and the book will not take the network on until they turn again.
    const readings: unknown[] = [];
    const point = async (cells: typeof chains) => {
      const read = await runColonyTask(server, POINT_BOOK, { player, colony_id, network: network.network, cells, chains });
      readings.push({ speeds: read.speeds, flaw: read.flaw });
      return read.clicks as unknown[];
    };
    const motorAt = { ...motor, y: origin.y - 3 };
    const handover = [...await point([chains[1]])];
    await world.command(server, `setblock ${commandPos(motorAt)} minecraft:air`);
    await tick.sprint(server, 10);
    handover.push(...await point([towerPort]));
    await world.command(server, `setblock ${commandPos(motorAt)} create:creative_motor[facing=up]{ScrollValue:${MOTOR_RPM}}`);
    await tick.sprint(server, 10);
    handover.push(...await point([towerPort]));
    run.note("handover", { clicks: handover, readings });
    expect(handover, "the book must hand the network over, refuse it while still, and take it on turning").toEqual([
      { refusal: "", member: false, held: false },
      { refusal: "folkways.dispatch.flaw.still", member: false, held: false },
      { refusal: "", member: false, held: true }]);
    const roles = await runColonyTask(server, ASSIGN_ROLES, { colony_id, roles: ROLES,
      zones: [
        ...fields.map(field => ({ kind: "folkways:farm", ...field, crop: "minecraft:wheat" })),
        { kind: "folkways:pasture", ...pen, animal: "minecraft:sheep", target: 8 },
        ...banks.map(bank => ({ kind: "folkways:fish", ...bank })),
      ],
      stands: { crafting: at(14, 18), building: at(0, 12), farming: at(20, 10), herding: at(-2, -2), fishing: at(25, 18) } });
    run.note("roles", roles);
    // Farmers, herders and fishers stay on their own ground between chores rather than going to bed.
    const tending = new Set((roles.crew as Array<{ id: string; role: string }>)
      .filter(one => !["crafting", "building"].includes(one.role)).map(one => one.id));
    run.note("kits", await runColonyTask(server, KIT_UP, { colony_id, chests: kitChests }));
    // The delivered logs end up in two builds: a cabin with log corners and plank walls crafted on site, and a log tower.
    const cabin = writeBuild("cabin", at(-4, 5), [7, 5, 3], CABIN);
    const tower = writeBuild("tower", at(-20, -8), [5, 5, 6], TOWER);
    await tick.sprint(server, 200);
    const before = await runColonyTask(server, NETWORK_STATE, args);
    run.note("before", before);
    expect(before.stock, "Create must see the warehouse's stock").toBeGreaterThan(64 * 2);
    expect(before.delivered).toBe(0);
    run.note("demands", await setMaintainDemandsFast(server, player, [
      { at: target, item: "minecraft:oak_planks", count: 192, low: 192 },
      { at: store, item: "minecraft:stick", count: 64, low: 64 },
      { at: logPile, item: "minecraft:oak_log", count: 48, low: 48 },
      { at: towerPile, item: "minecraft:oak_log", count: 48, low: 48 },
      // Farmers, herders and fishers only work toward an order, so each yard chest keeps asking for its zone's goods.
      { at: yardChests[0], item: "minecraft:wheat", count: 256, low: 256 },
      { at: yardChests[1], item: "minecraft:white_wool", count: 128, low: 128 },
      { at: yardChests[2], item: "minecraft:cod", count: 128, low: 128 }]));
    await runColonyTask(server, WATCH_PORT, { at: towerPort });
    // A long network takes a while to deliver the first parcel; roll it off camera so the clip opens mid-flow.
    await tick.sprint(server, PREROLL_TICKS);
    run.note("preroll", await runColonyTask(server, NETWORK_STATE, args));
    // Both builds are ordered only now, so the shot catches them going up while the large standing orders keep the crafters busy.
    await world.command(server, `gamemode creative ${player}`);
    await world.command(server, `tp ${player} ${at(0, 14).x} ${origin.y} ${at(0, 14).z} 180 -60`);
    run.note("order", (await handOffSchematic(server, client, player, { asset: cabin.asset, anchor: cabin.anchor })).receipt);
    await world.command(server, `tp ${player} ${at(-18, 2).x} ${origin.y} ${at(-18, 2).z} 180 -60`);
    run.note("tower", (await handOffSchematic(server, client, player, { asset: tower.asset, anchor: tower.anchor })).receipt);
    await tick.sprint(server, 100);
    await world.command(server, `execute if items entity ${player} weapon.mainhand folkways:colony_book`);
    await world.command(server, `gamemode spectator ${player}`);
    // Anyone left without work for a few seconds goes to bed far behind the yard, so only work stays in shot.
    const benched: string[] = [];
    const idleFor = new Map<string, number>();
    const benchIdle = async () => {
      const crew = (await runColonyTask(server, CREW, { colony_id })).crew as
        Array<{ id: string; what: string; x: number; z: number }>;
      for (const one of crew) {
        if (benched.includes(one.id) || tending.has(one.id) || !LEISURE.has(one.what)) { idleFor.delete(one.id); continue; }
        idleFor.set(one.id, (idleFor.get(one.id) ?? 0) + 1);
        if (idleFor.get(one.id)! < 6) continue;
        const bed = at(benched.length * 2, -46);
        await tryCommand(server, `tp ${one.id} ${bed.x + 0.5} ${bed.y} ${bed.z + 0.5}`);
        await runColonyTask(server, RETIRE, { id: one.id });
        await tryCommand(server, `data merge entity ${one.id} {NoAI:1b}`);
        benched.push(one.id);
      }
      return crew;
    };
    for (let beat = 0; beat < 6; beat++) { await benchIdle(); await tick.sprint(server, 20); }
    run.note("crew", { crew: await benchIdle(), benched: [...benched] });
    // From the ground just behind the seated keeper, tilted up so the chains run overhead against the sky.
    const eye = at(-11, 28);
    const look = cameraLookingAt({ ...eye, y: eye.y + 1.62 }, at(0, 2, 7));
    await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z} ${look.yaw} ${look.pitch}`);
    await safeAction(() => screen.dismiss(client));
    await hideHud(client);
    await setFov(client, 80);
    await writeLook(client, look);
    await safeAction(() => frames(client, 20));
    await sleepMs(4_000);
    const samples: any[] = [];
    const gates = await promoClip(run, client, server, "01-logistics", {
      subject: "From the ground right behind the seated keeper, looking up: one full-size warehouse with its packager, frogport, stock ticker and keeper packed together bottom-left ships oak logs over a five-pulley full-speed Create chain network to three frogports, at the workshop, a cabin and a log tower, while residents spread over the yard craft planks and sticks, build, farm wheat, herd sheep and fish",
      worldState: { ...args, ports, network, cabin, tower, camera: { eye, ...look } },
      seconds: CLIP_SECONDS,
      during: async () => {
        const until = Date.now() + (CLIP_SECONDS - 2) * 1_000;
        for (let beat = 0; Date.now() < until; beat++) {
          await sleepMs(1_000);
          const crew = await benchIdle();
          if (beat % 2 === 0) continue;
          const sample = await runColonyTask(server, NETWORK_STATE, args);
          const built = await runColonyTask(server, BUILT, { cells: cabin.cells });
          const laid = await runColonyTask(server, BUILT, { cells: cabin.cells.filter(c => c.block.endsWith("_planks")) });
          samples.push({ ...sample, built: built.built, crew: crew.map(one => `${one.what}@${one.x},${one.z}`),
            crafted: sample.delivered + laid.built + sample.sticks / 2 + sample.chests * 8 });
        }
      },
    });
    run.note("shot", { ...gates, samples, benched: [...benched] });
    run.note("shaders", shaderEvidence(clientInstance));
    expect(samples.some(s => s.crafted >= 32), "delivered logs must be crafted into at least 32 planks' worth of goods").toBe(true);
    expect(samples.some(s => s.sticks >= 32), "the network must deliver at least 32 crafted sticks").toBe(true);
    expect(samples.some(s => s.stock < before.stock), "supplier stock must be consumed").toBe(true);
    expect(samples.some(s => s.seated > 0), "a resident must staff the network desk").toBe(true);
    expect(Math.max(...samples.map(s => s.built)), "builders must lay delivered logs and crafted planks into the cabin")
      .toBeGreaterThanOrEqual(MIN_BUILT);
    const towerBuilt = await runColonyTask(server, BUILT, { cells: tower.cells });
    run.note("tower built", towerBuilt);
    const atTower = await runColonyTask(server, PORT_PARCELS, {});
    run.note("tower port", atTower);
    expect((atTower.addresses as string[]).some(address => address.startsWith("folkways-")),
      "the unaddressed tower port must catch parcels sent to its hidden address").toBe(true);
    expect(towerBuilt.built, "logs sent to the unaddressed tower port must go into the tower").toBeGreaterThan(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const LEISURE = new Set(["idle", "folkways:chatting", "folkways:eating", "folkways:sleeping"]);

const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var crew=new ArrayList<Object>();
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());if(entity==null)continue;
  var what=Bodies.of(entity).flatMap(body->body.doing()).map(doing->doing.toward().orElse(doing.what()).toString()).orElse("idle");
  crew.add(Map.of("id",resident.id().toString(),"what",what,"x",entity.getBlockX(),"z",entity.getBlockZ()));
}
return Map.of("ok",true,"crew",crew);
`,
  }),
});

// Takes every licence away, so the colony stops handing work to a resident who has gone to bed.
// Opens more receiving frogports on the network, each hooked to its own pulley under its own address.
const OPEN_PORTS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-ports",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "com.simibubi.create.content.logistics.packagePort.PackagePortTarget.ChainConveyorFrogportTarget",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var chains=args.list("chains");var opened=new ArrayList<Object>();
for(var raw:args.list("ports")){
  var one=(Map<?,?>)raw;
  var at=pos(one.get("port"));var chain=pos(chains.get(((Number)one.get("chain")).intValue()));
  var port=(PackagePortBlockEntity)level.getBlockEntity(at);
  port.addressFilter=String.valueOf(one.get("address"));
  port.acceptsPackages=true;
  port.target=new ChainConveyorFrogportTarget(chain.subtract(at),0.0f,Optional.empty(),false);
  port.target.setup(port,level,at);
  port.target.register(port,level,at);
  port.filterChanged();
  port.notifyUpdate();
  opened.add(port.addressFilter);
}
return Sync.stamp(Map.of("ok",true,"opened",opened));
`,
    members: `
private static BlockPos pos(Object raw) {
  var p=(Map<?,?>)raw;
  return new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
}
`,
  }),
});

// Hands each resident after the keeper one trade, sends them to its spot and draws the farm, pasture and fishing zones.
const ASSIGN_ROLES = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-roles",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.*",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var front=ColonyFront.of(colony);
var zones=new ArrayList<Object>();
for(var raw:args.list("zones")){
  var z=(Map<?,?>)raw;var min=(Map<?,?>)z.get("min");var max=(Map<?,?>)z.get("max");
  var chosen=ColonySettings.empty();
  if(z.containsKey("crop"))chosen=chosen.with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("crop")))));
  if(z.containsKey("animal"))chosen=chosen.with("animal",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("animal")))))
    .with("target",new ColonySettings.Value.Count(((Number)z.get("target")).intValue()));
  var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse(String.valueOf(z.get("kind"))),
    new BlockPos(((Number)min.get("x")).intValue(),((Number)min.get("y")).intValue(),((Number)min.get("z")).intValue()),
    new BlockPos(((Number)max.get("x")).intValue(),((Number)max.get("y")).intValue(),((Number)max.get("z")).intValue()),chosen)
    .orElseThrow(()->new IllegalStateException("Zone refused: "+z));
  zones.add(zone.id().toString());
}
var roles=args.list("roles");var stands=(Map<?,?>)args.value("stands");
var crew=new ArrayList<Object>();int index=0;var placed=new HashMap<String,Integer>();
for(var resident:colony.residents()){
  String role=String.valueOf(roles.get(index++));
  if(role.equals("keeper"))continue;
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  // Everyone hauls too, since a harvest, a catch or a fleece only counts once it is carried to a chest.
  for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->body.licences().allow(trade,
    vocation.id().getPath().equals(role)||vocation.id().equals(Vocations.HAULING)));
  if(role.equals("farming")){body.pack().insert(new ItemStack(Items.IRON_HOE));body.pack().insert(new ItemStack(Items.WHEAT_SEEDS,64));}
  if(role.equals("herding")){body.pack().insert(new ItemStack(Items.SHEARS));body.pack().insert(new ItemStack(Items.WHEAT,64));}
  if(role.equals("fishing"))body.pack().insert(new ItemStack(Items.FISHING_ROD));
  var stand=(Map<?,?>)stands.get(role);int nth=placed.merge(role,1,Integer::sum)-1;
  entity.teleportTo(((Number)stand.get("x")).intValue()+0.5+nth*2,((Number)stand.get("y")).intValue(),((Number)stand.get("z")).intValue()+0.5);
  crew.add(Map.of("id",resident.id().toString(),"role",role));
}
return Sync.stamp(Map.of("ok",true,"zones",zones,"crew",crew));
`,
  }),
});

const RETIRE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-retire",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var body=Bodies.of(level.getEntity(UUID.fromString(args.string("id")))).orElseThrow();
for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->body.licences().allow(trade,false));
return Sync.stamp(Map.of("ok",true));
`,
  }),
});

const STOCK_UP = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-stock",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.ItemStack",
      "net.neoforged.neoforge.capabilities.Capabilities",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("item")));
int count=(int) args.integer("count");
var left=new ArrayList<Object>();
for(var raw:args.list("vaults")) {
  var c=(Map<?,?>)raw;
  var pos=new BlockPos(((Number)c.get("x")).intValue(),((Number)c.get("y")).intValue(),((Number)c.get("z")).intValue());
  var handler=level.getCapability(Capabilities.ItemHandler.BLOCK,pos,null);
  if(handler==null)throw new IllegalStateException("No inventory at "+pos);
  var rest=new ItemStack(item,count);
  for(int i=0;i<handler.getSlots()&&!rest.isEmpty();i++)rest=handler.insertItem(i,rest,false);
  left.add(rest.getCount());
}
return Sync.stamp(Map.of("ok",true,"left",left));
`,
  }),
});

const KIT_UP = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-kits",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.*",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.vocation.Vocations",
      "io.github.izakyl.folkways.core.api.terms.*",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.*",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var chests=new ArrayList<Container>();
for(var raw:args.list("chests")) {
  var c=(Map<?,?>)raw;
  chests.add((Container)level.getBlockEntity(new BlockPos(((Number)c.get("x")).intValue(),((Number)c.get("y")).intValue(),((Number)c.get("z")).intValue())));
}
var given=new ArrayList<Object>();int slot=0;var chest=chests.get(0);
for(var resident:colony.residents()) {
  var body=Bodies.of(level.getEntity(resident.id())).orElseThrow();
  for(var vocation:Vocations.all()) {
    if(body.licences().of(vocation).isEmpty())continue;
    for(var tool:vocation.kit()) {
      if(Goods.countIn(body.pack(),tool)>0)continue;
      var item=Goods.members(tool).stream().findFirst().orElseThrow();
      if(slot>=chest.getContainerSize()) {
        if(chests.indexOf(chest)==chests.size()-1)throw new IllegalStateException("kit chests are full");
        chest.setChanged();chest=chests.get(chests.indexOf(chest)+1);slot=0;
      }
      chest.setItem(slot++,new ItemStack(item));
      given.add(item.toString());
    }
  }
}
chest.setChanged();
return Sync.stamp(Map.of("ok",true,"given",given));
`,
  }),
});

type Cell = { x: number; y: number; z: number; block: string };

type Pick = (x: number, y: number, z: number, w: number, d: number) => string | undefined;

// A cabin: oak log corners, plank walls, a door and windows left open.
const CABIN: Pick = (x, y, z, w, d) => {
  const edgeX = x === 0 || x === w - 1, edgeZ = z === 0 || z === d - 1;
  if (!edgeX && !edgeZ) return undefined;
  const door = z === d - 1 && x === 3 && y < 2;
  const window = y === 1 && ((z === d - 1 && (x === 1 || x === 5)) || (edgeX && z === 2));
  if (door || window) return undefined;
  return edgeX && edgeZ ? "minecraft:oak_log" : "minecraft:oak_planks";
};

// A hollow log tower with a doorway and a slit window on each side.
const TOWER: Pick = (x, y, z, w, d) => {
  const edgeX = x === 0 || x === w - 1, edgeZ = z === 0 || z === d - 1;
  if (!edgeX && !edgeZ) return undefined;
  const middle = x === 2 || z === 2;
  if (middle && ((z === d - 1 && y < 2) || y === 4)) return undefined;
  return "minecraft:oak_log";
};

function writeBuild(name: string, anchor: { x: number; y: number; z: number }, [w, d, h]: number[], pick: Pick) {
  const local: Cell[] = [];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) for (let z = 0; z < d; z++) {
    const block = pick(x, y, z, w, d);
    if (block) local.push({ x, y, z, block });
  }
  const palette = [...new Set(local.map(c => c.block))];
  const int = (v: number) => ({ k: "int", v });
  const ints = (v: number[]) => ({ k: "list", item: 3, v: v.map(int) });
  const nbt = encodeNbtFile({
    DataVersion: int(DATA_VERSION),
    size: ints([w, h, d]),
    palette: { k: "list", item: 10, v: palette.map(name => ({ k: "compound", v: name.endsWith("_log")
      ? { Name: { k: "string", v: name }, Properties: { k: "compound", v: { axis: { k: "string", v: "y" } } } }
      : { Name: { k: "string", v: name } } })) },
    blocks: { k: "list", item: 10, v: local.map(c => ({ k: "compound", v: {
      pos: ints([c.x, c.y, c.z]), state: int(palette.indexOf(c.block)) } })) },
    entities: { k: "list", item: 0, v: [] },
  } as any);
  const materials: Record<string, number> = {};
  for (const c of local) materials[c.block] = (materials[c.block] ?? 0) + 1;
  const dir = outPath("promo", "takes", "logistics");
  mkdirSync(dir, { recursive: true });
  const asset = path.join(dir, `promo-${name}.nbt`);
  writeFileSync(asset, nbt);
  writeFileSync(asset.replace(/\.nbt$/, ".json"), JSON.stringify({
    file: `promo-${name}.nbt`, size: { x: w, y: h, z: d }, count: local.length, materials, cells: local }, null, 2));
  const cells = local.map(c => ({ x: anchor.x + c.x, y: anchor.y + c.y, z: anchor.z + c.z, block: c.block }));
  return { asset, anchor, cells, materials };
}

// Left-clicks each cell with the book pointing, as the client does, and reads what came of it.
const POINT_BOOK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-point-book",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.engine.colony.ColonyMembership",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.MemberToggle",
      "io.github.izakyl.folkways.plugins.dispatch.DispatchContent",
      "io.github.izakyl.folkways.plugins.dispatch.DispatchPresence",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var player=server.getPlayerList().getPlayerByName(args.string("player"));
var dispatch=(DispatchPresence)colony.service(DispatchContent.ID,Object.class).orElseThrow();
var clicks=new ArrayList<Object>();
for(var raw:args.list("cells")){
  var p=(Map<?,?>)raw;
  var at=new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
  var attempt=MemberToggle.toggleAt(level,colony,at,player);
  var be=level.getBlockEntity(at);
  boolean member=be!=null&&ColonyMembership.owner(be).isPresent();
  boolean held=dispatch.holds(UUID.fromString(args.string("network")));
  clicks.add(Map.of("refusal",attempt.refusal().map(n->n.key()).orElse(""),"member",member,"held",held));
}
var speeds=new ArrayList<Object>();
for(var raw:args.list("chains")){
  var p=(Map<?,?>)raw;
  var chain=(com.simibubi.create.content.kinetics.base.KineticBlockEntity)level.getBlockEntity(new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue()));
  speeds.add(chain.getSpeed()+"/"+chain.getTheoreticalSpeed()+"/"+chain.isOverStressed());
}
var flaw=io.github.izakyl.folkways.plugins.dispatch.PackageNetworks.get().flawOf(level,UUID.fromString(args.string("network")));
return Map.of("ok",true,"clicks",clicks,"speeds",speeds,"flaw",String.valueOf(flaw));
`,
  }),
});

const PORT_WATCH_KEY = "folkways.promo.logisticsPortWatch";

// Watches one port every tick from now on (once per game JVM, kept in a system property), noting the address of every
// parcel that lands in it. A port is emptied by whoever collects there, often on the tick a parcel lands, so a
// sampled read misses deliveries; residents collect in the tick's labor pass, so looking just before it sees each one.
const WATCH_PORT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-watch-port",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.logistics.box.PackageItem",
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
    ],
    body: `
var args=Args.of(ctx);
var p=(Map<?,?>)args.value("at");
var at=new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
var props=System.getProperties();
var watch=props.get("${PORT_WATCH_KEY}") instanceof Map<?,?> old ? (Map<String,Object>)old : null;
if(watch==null){
  var fresh=new ConcurrentHashMap<String,Object>();
  fresh.put("at",at);fresh.put("seen",ConcurrentHashMap.newKeySet());
  NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,ServerTickEvent.Post.class,event->{
    var pos=(BlockPos)fresh.get("at");
    if(!(event.getServer().overworld().getBlockEntity(pos) instanceof PackagePortBlockEntity port))return;
    var seen=(Set<String>)fresh.get("seen");
    for(int i=0;i<port.inventory.getSlots();i++){
      var stack=port.inventory.getStackInSlot(i);
      if(PackageItem.isPackage(stack))seen.add(PackageItem.getAddress(stack));
    }
  });
  props.put("${PORT_WATCH_KEY}",fresh);
  watch=fresh;
}
watch.put("at",at);watch.put("seen",ConcurrentHashMap.newKeySet());
return Map.of("ok",true);
`,
  }),
});

// The addresses of every parcel the watched port caught since the watch began, and the port's filter.
const PORT_PARCELS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-port-parcels",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server=ctx.server();
if(!(System.getProperties().get("${PORT_WATCH_KEY}") instanceof Map<?,?> watch))throw Fail.unavailable("port watch not installed");
var port=(PackagePortBlockEntity)server.overworld().getBlockEntity((BlockPos)watch.get("at"));
return Map.of("ok",true,"addresses",new ArrayList<Object>((Set<?>)watch.get("seen")),"filter",String.valueOf(port.getFilterString()));
`,
  }),
});

const BUILT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.logistics-built",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
int built=0;
for(var raw:args.list("cells")) {
  var c=(Map<?,?>)raw;
  var pos=new BlockPos(((Number)c.get("x")).intValue(),((Number)c.get("y")).intValue(),((Number)c.get("z")).intValue());
  if(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(String.valueOf(c.get("block"))))built++;
}
return Map.of("ok",true,"built",built);
`,
  }),
});

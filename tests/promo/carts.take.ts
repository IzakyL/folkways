import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";
import { shaderEvidence } from "../shared/capture/shaders";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import {
  dressLand, DRAW_PASTURE, markMembers, openMountedScene, ROSTER, standShot, summonHorses, type MountedScene,
  type Pose,
} from "./mounted-stage";
import { followCamera } from "./smooth-camera";
import { promoClip, sleepMs, take, type PromoPair } from "./promo-kit";

// A supply cart line drawn with the book between a wheat farm (station A, west) and a bakery 100 blocks east (station
// B). The colony makes the cart from stock and sets it down, takes a tame horse from the pasture, and the horse navigates
// round lap after lap without a leader. The farmers load wheat at A; the bakers unload it at B and bake bread.
const LENGTH = 100;
const WHEAT = 512;

type Cart = {
  found: boolean; x: number; y: number; z: number; moving: boolean; wheat: number; goods: number; items: string[];
  puller: string; horse: null | { x: number; z: number; leader: string }; lines: Line[];
  near: Array<{ uuid: string; doing: string; d: number; x: number; z: number }>;
};
type Line = { lap: number; cart: string; horse: string;
  aboard: Array<{ count: number; from: number; to: number; loaded: boolean; goods: string }> };
type Sample = { atMs: number; time: number; cart: Cart };
type CardView = { hidden: boolean; book: boolean; cards: number; cartCards: number;
  stock: Array<{ item: string; count: number }>; emptyCarts: number };
type Bakery = { bread: number; wheat: number; transportedWheat: number; stalls: string };

test("carts: a horse-drawn supply cart line carries the farm's wheat to the bakery", async ({}, testInfo) => {
  const run = take("carts-line");
  let pair: PromoPair | undefined;
  try {
    const scene = await openMountedScene(run.scope, run, 5, { x: -30, z: 150 });
    pair = scene.pair;
    const { server, at } = scene;
    const fill = (a: Vec, b: Vec, block: string) => world.command(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
    const set = (a: Vec, block: string) => world.command(server, `setblock ${commandPos(a)} ${block}`);
    const A = at(0, 0);
    const B = at(LENGTH, 0);
    // Full-height road blocks keep the cart's route level with the station and pasture work cells.
    await fill(at(-6, -2, -1), at(LENGTH + 6, 2, -1), "minecraft:coarse_dirt");
    // The horse's stall is ahead of station A, so it approaches the cart from its pulling side.
    const pastureMin = at(10, -8, -1);
    const pastureMax = at(12, -6, -1);
    run.note("pasture", await server.run(DRAW_PASTURE, { colony: scene.colony, min: pastureMin, max: pastureMax }));
    // A three-block opening lets the horse leave without catching on a gate or fence corner.
    await fill(at(9, -9), at(13, -9), "minecraft:oak_fence");
    await fill(at(9, -5), at(13, -5), "minecraft:oak_fence");
    await fill(at(9, -9), at(9, -5), "minecraft:oak_fence");
    await fill(at(13, -9), at(13, -5), "minecraft:oak_fence");
    await fill(at(10, -5), at(12, -5), "minecraft:air");
    await set(at(10, -8), "minecraft:hay_block");
    // Leave station A clear for the horse and cart to turn round on the return lap.
    // The wheat farm south of station A, its granary chest at the roadside.
    await fill(at(-16, 5, -1), at(-4, 15, -1), "minecraft:farmland[moisture=7]");
    await fill(at(-16, 10, -1), at(-4, 10, -1), "minecraft:water");
    await fill(at(-16, 5), at(-4, 9), "minecraft:wheat[age=7]");
    await fill(at(-16, 11), at(-4, 15), "minecraft:wheat[age=7]");
    const granary = at(-2, 4);
    const granary2 = at(-1, 4);
    await set(granary, "minecraft:chest[facing=north,type=right]");
    await set(granary2, "minecraft:chest[facing=north,type=left]");
    for (let slot = 0; slot < 8; slot++) {
      // A component follows the staged wheat through the cart and stores; native bread crafting accepts it.
      await world.command(server, `item replace block ${commandPos(granary)} container.${slot} with ` +
        "minecraft:wheat[minecraft:custom_data={promo_cart_wheat:1b}] 64");
    }
    // The cart the colony will set down is in the cartwright's stock by the road.
    const shed = at(-4, -4);
    await set(shed, "minecraft:barrel[facing=up]");
    await world.command(server, `item replace block ${commandPos(shed)} container.0 with astikorcartsredux:oak_supply_cart`);
    // The bakery past station B: its only door faces the road.
    const bx = LENGTH + 2;
    await fill(at(bx, 5, -1), at(bx + 8, 12, -1), "minecraft:spruce_planks");
    await fill(at(bx, 5), at(bx + 8, 12, 3), "minecraft:stripped_spruce_log");
    await fill(at(bx + 1, 6), at(bx + 7, 11, 3), "minecraft:air");
    await fill(at(bx + 1, 5), at(bx + 7, 5, 2), "minecraft:white_terracotta");
    await fill(at(bx + 1, 12), at(bx + 7, 12, 2), "minecraft:white_terracotta");
    await fill(at(bx, 6), at(bx, 11, 2), "minecraft:white_terracotta");
    await fill(at(bx + 8, 6), at(bx + 8, 11, 2), "minecraft:white_terracotta");
    await fill(at(bx - 1, 4, 4), at(bx + 9, 13, 4), "minecraft:spruce_slab[type=bottom]");
    await fill(at(bx, 5, 4), at(bx + 8, 12, 4), "minecraft:spruce_planks");
    await set(at(bx + 4, 5), "minecraft:oak_door[facing=south,half=lower,hinge=left]");
    await set(at(bx + 4, 5, 1), "minecraft:oak_door[facing=south,half=upper,hinge=left]");
    await fill(at(bx + 4, 3, -1), at(bx + 4, 4, -1), "minecraft:dirt_path");
    for (const x of [bx + 2, bx + 6]) await set(at(x, 5, 1), "minecraft:glass_pane[east=true,west=true]");
    await set(at(bx + 4, 8, 3), "minecraft:lantern[hanging=true]");
    await set(at(bx + 7, 11), "minecraft:smoker[facing=west]");
    const bench = at(bx + 7, 8);
    const bread = at(bx + 7, 6);
    const flour = at(bx + 1, 8);
    await set(bench, "minecraft:crafting_table");
    await set(bread, "minecraft:chest[facing=west]");
    await set(flour, "minecraft:barrel[facing=up]");
    await set(at(bx + 3, 4), "minecraft:lantern");
    run.note("dressed", await dressLand(scene, { x0: -40, z0: -50, x1: LENGTH + 30, z1: 40 }, [
      { x0: 8, z0: -10, x1: 14, z1: -4 }, { x0: -8, z0: -3, x1: LENGTH + 8, z1: 3 }, { x0: -17, z0: 3, x1: 0, z1: 16 },
      { x0: LENGTH, z0: 3, x1: LENGTH + 11, z1: 14 }], [
      { x: -26, z: -20, kind: "fancy_oak" }, { x: 8, z: -24, kind: "oak" }, { x: 22, z: -14, kind: "birch" },
      { x: 34, z: -26, kind: "fancy_oak" }, { x: 48, z: -12, kind: "oak" }, { x: 60, z: -22, kind: "birch" },
      { x: 72, z: -12, kind: "fancy_oak" }, { x: 86, z: -24, kind: "oak" }, { x: 96, z: -14, kind: "birch" },
      { x: 30, z: 14, kind: "oak" }, { x: 52, z: 18, kind: "birch" }, { x: 70, z: 12, kind: "fancy_oak" },
      { x: 88, z: 20, kind: "oak" }, { x: 118, z: -10, kind: "fancy_oak" }, { x: 120, z: 18, kind: "birch" },
      { x: -24, z: 24, kind: "oak" }, { x: 12, z: 22, kind: "fancy_oak" }]));
    await world.command(server, "gamerule randomTickSpeed 30");
    await tick.sprint(server, 5);
    run.note("members", await markMembers(scene, [granary, granary2, shed, bench, bread, flour]));
    const roster: string[] = await server.run(ROSTER, { colony: scene.colony });
    expect(roster.length).toBe(5);
    const [handler, farmerA, farmerB, bakerA, bakerB] = roster;
    run.note("crew", await server.run(PLACE_CREW, { crew: [
      { uuid: handler, at: at(-3, -3), tools: ["minecraft:cooked_beef 32"] },
      { uuid: farmerA, at: at(-10, 4), tools: ["minecraft:iron_hoe", "minecraft:cooked_beef 32"] },
      { uuid: farmerB, at: at(-8, 4), tools: ["minecraft:iron_hoe", "minecraft:cooked_beef 32"] },
      { uuid: bakerA, at: at(bx + 3, 3), tools: ["minecraft:cooked_beef 32"] },
      { uuid: bakerB, at: at(bx + 5, 3), tools: ["minecraft:cooked_beef 32"] },
    ] }));
    // Spawn after dressing the set, immediately before the line can claim and park its horse.
    await summonHorses(scene, [at(11, -7)], false);
    // The line, drawn as the book draws a path: its two points are its stations.
    const line = await server.run(DRAW_LINE, { colony: scene.colony, points: [at(0, 0, -1), at(LENGTH, 0, -1)] });
    run.note("line", line);
    await licenseCrewFast(server, [
      { uuid: handler, trades: ["hauling"] },
      { uuid: farmerA, trades: ["farming", "hauling"] }, { uuid: farmerB, trades: ["farming", "hauling"] },
      { uuid: bakerA, trades: ["crafting", "hauling"] }, { uuid: bakerB, trades: ["crafting", "hauling"] },
    ]);
    const motion: Record<string, any>[] = [];
    await server.run(MOTION, { actor: handler });
    const cart = async () => {
      const c = await server.run(CART, { colony: scene.colony, residents: roster }) as Cart & { motion: any[] };
      motion.push(...c.motion);
      run.note("motion", motion);
      return c;
    };
    // Open the freight order once the colony's cart service is available to the planner.
    let yoked: Cart | null = null;
    for (let i = 0; i < 300 && !yoked; i++) {
      await tick.sprint(server, 40);
      const c: Cart = await cart();
      if (i % 20 === 0) {
        run.note("hitchWatch", c);
      }
      if (c.puller === "minecraft:horse" && c.horse && c.x > A.x + 8) {
        yoked = c;
      }
    }
    run.note("yoked", yoked);
    expect(yoked, "the colony sets down its cart and the pasture horse hitches itself and departs").not.toBeNull();
    const bakery = (): Promise<Bakery> => server.run(BAKERY, { colony: scene.colony, bench });
    const before = await bakery();
    run.note("bakeryBefore", before);
    expect(before.bread, "the scene starts without bread").toBe(0);
    expect(before.wheat).toBe(WHEAT);
    expect(before.transportedWheat).toBe(WHEAT);
    run.note("demands", await setMaintainDemandsFast(server, scene.name, [
      { at: flour, item: "minecraft:wheat", count: WHEAT, low: WHEAT },
      { at: bread, item: "minecraft:bread", count: 64, low: 64 },
    ]));
    run.note("staging", {
      world: "Flat promo plains; fixed morning, clear weather, no spawning",
      set: "Paddock (horse pasture zone) with one tame horse, wheat farm and granary at A, bakery at B, 100-block road",
      cast: "Five founded residents: a cartwright and handler (hauling), two farmers and two bakers (their trade and hauling)",
      honesty: "The line is drawn and wheat-stock and bread orders filed; the crew carries packed lunches, the cart " +
        "is made from stock and set down, the horse claimed and hitched by the line, " +
        "and every load and unload booked and done by the colony's own planner",
    });
    const watch: Sample[] = [];
    const started = Date.now();
    // Off camera, at full speed: the horse returns for the freight booked after its initial departure.
    let ready: Sample | null = null;
    for (let i = 0; i < 1_600 && !ready; i++) {
      await tick.sprint(server, 10);
      const c: Cart = await cart();
      const s = { atMs: Date.now() - started, time: 0, cart: c };
      if (i % 20 === 0) {
        watch.push(s);
        run.note("watch", watch);
      }
      const booked = c.lines.some((l) => l.aboard.some((a) => a.from === 0 && !a.loaded
        && a.goods.includes("Optional[minecraft:wheat]")));
      // Start while the return leg approaches A: loading can finish in the old 40-tick observation gap.
      const comingToA = c.found && Math.hypot(c.x - A.x, c.z - A.z) < 12 && c.horse && !c.horse.leader
        && c.lines.some((l) => l.lap % 2 === 1);
      if (booked && comingToA) {
        ready = s;
      }
    }
    run.note("watch", watch);
    run.note("ready", ready);
    expect(ready, "a batch of wheat is booked on the line while the cart heads for station A").not.toBeNull();

    // A: the cart stands at the farm's station and the farmers put the wheat aboard.
    // The world-space cards render independently of hideGui. F1 also suppresses the held book model.
    await world.command(server, `item replace entity ${scene.name} weapon.mainhand from entity ${scene.name} hotbar.8`);
    run.note("cards", { held: "colony book from hotbar.8", hud: "F1 hideGui: no hotbar/crosshair/hand",
      rendering: "LookLineCards/WorldCards render at AFTER_TRANSLUCENT_BLOCKS, independent of hideGui" });
    const samples: Sample[] = [];
    let pose: Pose = await standShot(scene, at(7, 9, 4), at(-1, 1, -1), 64)
      .then((p) => ({ position: p.eye, yaw: p.yaw, pitch: p.pitch }));
    let cards = await scene.client.run(CARD_VIEW, {});
    for (let waited = 0; cards.cards === 0 && waited < 20; waited++) {
      // Card snapshots refresh every 500ms; a fast renderer can finish standShot's frames before one arrives.
      await sleepMs(100);
      cards = await scene.client.run(CARD_VIEW, {});
    }
    run.note("cardView", cards);
    expect(cards.hidden).toBe(true);
    expect(cards.book).toBe(true);
    expect(cards.cards, "book cards remain available while hideGui is true").toBeGreaterThan(0);
    const loadingCardViews: CardView[] = [];
    await promoClip(run, scene.client, server, "01-loading", {
      seconds: 20,
      fps: 30,
      subject: "The horse brings its supply cart into station A by the wheat farm without a leader; it stands while the " +
        "farmers carry wheat from the granary and load it aboard",
      worldState: { A, granary },
      during: async () => {
        const t0 = Date.now();
        while (Date.now() - t0 < 19_000) {
          samples.push({ atMs: Date.now() - t0, time: 0, cart: await cart() });
          loadingCardViews.push(await scene.client.run(CARD_VIEW, {}));
          await sleepMs(400);
        }
      },
    });
    run.note("loadingSamples", samples);
    run.note("loadingCardViews", loadingCardViews);
    expect(loadingCardViews.some((v) => v.stock.some((item) => item.item === "minecraft:wheat" && item.count === WHEAT)),
      "the cart card updates to wheat 512 after loading").toBe(true);
    expect(loadingCardViews.some((v) => v.cartCards > 0 && v.hidden && v.book),
      "the incoming cart enters the frame with its card and the HUD hidden").toBe(true);
    const loadedAtA = samples.some((s, i) => i > 0 && s.cart.wheat > samples[i - 1].cart.wheat
      && Math.hypot(s.cart.x - A.x, s.cart.z - A.z) < 4);
    // Keep running until the cart leaves A with wheat, then ride along with it for a stretch of the road.
    let left: Sample | null = null;
    for (let i = 0; i < 200 && !left; i++) {
      await tick.sprint(server, 10);
      const c: Cart = await cart();
      if (c.wheat > 0 && c.moving && c.x > A.x + 3) left = { atMs: 0, time: 0, cart: c };
    }
    run.note("left", left);
    expect(left, "the cart leaves station A with wheat aboard").not.toBeNull();
    expect(left!.cart.wheat, "all staged wheat travels aboard the cart before any is baked").toBe(WHEAT);
    const haul: Sample[] = [];
    const hauling = left!.cart;
    pose = await standShot(scene, { x: hauling.x - 4, y: hauling.y + 4, z: hauling.z + 10 },
      { x: hauling.x + 2, y: hauling.y + 1, z: hauling.z }, 60)
      .then((p) => ({ position: p.eye, yaw: p.yaw, pitch: p.pitch }));
    await promoClip(run, scene.client, server, "02-haul", {
      seconds: 18,
      fps: 30,
      subject: "Tracking alongside: the horse navigates independently, the loaded supply cart rolls behind along the road",
      worldState: {},
      during: async () => {
        const t0 = Date.now();
        run.note("followCamera", await followCamera(scene, {
          from: pose, until: () => Date.now() - t0 >= 17_000,
          sample: async () => {
            const c: Cart = await cart();
            haul.push({ atMs: Date.now() - t0, time: 0, cart: c });
            return { eye: { x: c.x - 1, y: c.y + 4, z: c.z + 10 },
              target: { x: c.x + 3, y: c.y + 1, z: c.z } };
          },
        }));
      },
    });
    const haulCards = await scene.client.run(CARD_VIEW, {});
    run.note("haulCardView", haulCards);
    expect(haulCards.stock.find((item) => item.item === "minecraft:wheat")?.count,
      "the rendered cart card shows the full wheat shipment, including its quantity").toBe(WHEAT);
    expect(haulCards.hidden && haulCards.book && haulCards.cartCards > 0,
      "the cart itself keeps its book card while travelling without nearby residents").toBe(true);
    run.note("haulSamples", haul);
    // On to B at full speed, off camera.
    let nearB: Sample | null = null;
    for (let i = 0; i < 300 && !nearB; i++) {
      await tick.sprint(server, 10);
      const c: Cart = await cart();
      // Save the unloading shot for the station: the approach otherwise consumes much of its fixed duration.
      if (c.wheat > 0 && Math.hypot(c.x - B.x, c.z - B.z) < 8) {
        nearB = { atMs: 0, time: 0, cart: c };
      }
    }
    run.note("nearB", nearB);
    expect(nearB, "the cart reaches the bakery's station").not.toBeNull();
    const unloading: Sample[] = [];
    const unloadingCardViews: CardView[] = [];
    await standShot(scene, at(LENGTH - 6, -9, 5), at(LENGTH + 3, 1, 3), 64);
    await promoClip(run, scene.client, server, "03-unloading", {
      seconds: 36,
      fps: 30,
      subject: "At station B the cart stands by the bakery; the bakers take the wheat off and carry it in through the door",
      worldState: { B, bench, bread },
      during: async () => {
        const t0 = Date.now();
        while (Date.now() - t0 < 35_000) {
          unloading.push({ atMs: Date.now() - t0, time: 0, cart: await cart() });
          unloadingCardViews.push(await scene.client.run(CARD_VIEW, {}));
          await sleepMs(400);
        }
      },
    });
    run.note("unloadingSamples", unloading);
    run.note("unloadingCardViews", unloadingCardViews);
    expect(unloadingCardViews.some((v) => v.emptyCarts > 0), "the unloaded cart card explicitly shows empty").toBe(true);
    expect(unloadingCardViews.some((v) => v.cartCards > 0 && v.hidden && v.book),
      "the cart's book card is visible at the bakery with the HUD hidden").toBe(true);
    const peak = Math.max(...unloading.map((s) => s.cart.wheat));
    const unloadedAtB = unloading.some((s, i) => i > 0 && s.cart.wheat < unloading[i - 1].cart.wheat
      && Math.hypot(s.cart.x - B.x, s.cart.z - B.z) < 4);
    // Let the bakers finish their own crafting batch and delivery after unloading, within a bounded wait.
    let after = await bakery();
    for (let waited = 0; after.bread === 0 && waited < 8_000; waited += 100) {
      await tick.sprint(server, 100);
      after = await bakery();
    }
    const baked = await server.run(COUNT, { at: bread, item: "minecraft:bread" });
    // Bread may still be in its maker's pack, so also wait for the order's actual delivery to the chest.
    let delivered = baked.count;
    for (let waited = 0; delivered === 0 && waited < 2_000; waited += 100) {
      await tick.sprint(server, 100);
      delivered = (await server.run(COUNT, { at: bread, item: "minecraft:bread" })).count;
    }
    after = await bakery();
    const usedTransportedWheat = before.transportedWheat - after.transportedWheat;
    run.note("bakeryAfter", after);
    run.note("verdict", { loadedAtA, unloadedAtB, peak, baked: { count: delivered }, usedTransportedWheat });
    expect(loadedAtA, "wheat goes aboard while the cart stands at station A").toBe(true);
    expect(unloadedAtB, "wheat comes off while the cart stands at station B").toBe(true);
    expect(delivered, "the bakery makes and delivers bread before the take ends").toBeGreaterThan(0);
    expect(usedTransportedWheat, "native bread crafting consumes wheat that travelled aboard the cart")
      .toBeGreaterThan(0);
    expect(after.wheat, "every remaining wheat item still has the cart shipment's provenance")
      .toBe(after.transportedWheat);
    expect(usedTransportedWheat, "three transported wheat are consumed for each bread produced")
      .toBe(after.bread * 3);
    await cart();
    // Actual blocks per tick, with 10 blocks at either end excluded for starting, turning and arrival.
    const travelling = motion.filter((s) => s.horseX > A.x + 10 && s.horseX < B.x - 10);
    const stalls = travelling.filter((s) => s.horseV < 0.03);
    const led = travelling.filter((s) => s.leashed || s.leader);
    const trailing = motion.filter((s) => s.hitchedTicks > 20);
    run.note("trailingVerdict", { minimumAngle: Math.min(...trailing.map((s) => s.trailingAngle)),
      threshold: 100, samples: trailing.length });
    expect(trailing.filter((s) => s.trailingAngle <= 100), "the cart trails the horse, including both turnarounds")
      .toEqual([]);
    run.note("motionVerdict", { ticks: travelling.length, threshold: 0.03, excludedStationBlocks: 10,
      minimumHorseSpeed: Math.min(...travelling.map((s) => s.horseV)), stalls, led });
    expect(travelling.length, "at least 100 individual driving ticks are observed").toBeGreaterThan(100);
    expect(stalls, "every mid-leg tick keeps horse speed at or above 0.03 blocks/tick").toEqual([]);
    expect(led, "no travelling horse is leashed or has a leader").toEqual([]);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
    run.expectTaken(["01-loading", "02-haul", "03-unloading"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const PLACE_CREW = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-crew",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity"],
    body: `
var level = ctx.server().overworld();
var out = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("crew")) {
  var row = (Map<?, ?>) raw;
  var body = (ResidentEntity) level.getEntity(UUID.fromString(String.valueOf(row.get("uuid"))));
  for (var t : (List<?>) row.get("tools")) {
    var parts = String.valueOf(t).split(" ");
    body.pack().insert(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(parts[0])), parts.length > 1 ? Integer.parseInt(parts[1]) : 1));
  }
  var p = (Map<?, ?>) row.get("at");
  body.teleportTo(((Number) p.get("x")).doubleValue() + .5, ((Number) p.get("y")).doubleValue(), ((Number) p.get("z")).doubleValue() + .5);
  body.getNavigation().stop();
  out.add(body.id().toString());
}
return out;`,
  }),
});

const DRAW_LINE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-line",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.carts.FolkwaysCartsMod",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var points = new ArrayList<BlockPos>();
for (var raw : a.list("points")) {
  var p = (Map<?, ?>) raw;
  points.add(new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue()));
}
var path = ColonyFront.of(colony).addPath(level, FolkwaysCartsMod.LINE, points, ColonySettings.empty()
    .with("cart", new ColonySettings.Value.Choice(ResourceLocation.parse("astikorcartsredux:supply_cart")))
    .with("round", new ColonySettings.Value.Choice(ResourceLocation.parse("folkways:line_open"))))
    .orElseThrow(() -> new IllegalStateException("line refused"));
return Map.of("path", path.id().toString(), "points", points.size());`,
  }),
});

/** The line's supply cart and horse as the world holds them, with the line's own ledger read from the colony. */
const CART = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-state",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.carts.FolkwaysCartsMod",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.entity.Mob",
      "net.minecraft.world.item.Items",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var out = new LinkedHashMap<String, Object>();
out.put("found", false);
net.minecraft.world.entity.Entity cart = null;
for (var e : level.getAllEntities()) {
  if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("astikorcartsredux:supply_cart") && e.isAlive()) cart = e;
}
if (cart != null) {
  out.put("found", true);
  out.put("x", cart.getX()); out.put("y", cart.getY()); out.put("z", cart.getZ());
  double dx = cart.getX() - cart.xo, dz = cart.getZ() - cart.zo;
  out.put("moving", dx * dx + dz * dz >= 1.0E-4);
  var box = (net.minecraft.world.Container) cart;
  int wheat = 0, goods = 0; var items = new ArrayList<String>();
  for (int i = 0; i < box.getContainerSize(); i++) {
    var s = box.getItem(i);
    if (s.isEmpty()) continue;
    goods += s.getCount(); if (s.is(Items.WHEAT)) wheat += s.getCount(); items.add(s.toString());
  }
  out.put("wheat", wheat); out.put("goods", goods); out.put("items", items);
  var puller = ((com.jusipat.astikorcartsredux.entity.AbstractCargoCart) cart).getPulling();
  out.put("puller", puller == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(puller.getType()).toString());
  if (puller instanceof Mob horse) {
    var holder = horse.getLeashHolder();
    out.put("horse", Map.of("x", horse.getX(), "z", horse.getZ(), "leader", holder == null ? "" : holder.getUUID().toString()));
  } else out.put("horse", null);
  for (var e : level.getAllEntities()) {
    if (e instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h && e.getTags().contains("promo_horse_0")) {
      var holder = h.getLeashHolder();
      out.put("lineHorse", Map.of("x", h.getX(), "z", h.getZ(), "leash", holder == null ? "" : holder.getUUID().toString(),
          "noAi", h.isNoAi()));
    }
  }
  var near = new ArrayList<Object>();
  for (var raw : a.list("residents")) {
    var r = level.getEntity(UUID.fromString(String.valueOf(raw)));
    if (r instanceof ResidentEntity body) {
      near.add(Map.of("uuid", body.id().toString(), "x", body.getX(), "z", body.getZ(), "d", body.distanceTo(cart),
          "doing", body.doing().map(d -> d.toward().orElse(d.what()).toString()).orElse("idle"),
          "gait", body.locomotion().id().toString(), "collides", body.canCollideWith(cart), "y", body.getY(),
          "navigation", body.getNavigation().getClass().getName(),
          "path", String.valueOf(body.getNavigation().getPath() == null ? null
              : body.getNavigation().getPath().getEndNode())));
    }
  }
  out.put("near", near);
} else {
  out.put("x", 0); out.put("y", 0); out.put("z", 0); out.put("moving", false); out.put("wheat", 0); out.put("goods", 0);
  out.put("items", List.of()); out.put("puller", ""); out.put("horse", null); out.put("near", List.of());
}
var lines = new ArrayList<Object>();
var cls = Class.forName("io.github.izakyl.folkways.carts.CartLines", true, FolkwaysCartsMod.class.getClassLoader());
var service = colony.service(FolkwaysCartsMod.OWNER, cls);
if (service.isPresent()) {
  var f = cls.getDeclaredField("states"); f.setAccessible(true);
  var states = (Map<?, ?>) f.get(service.get());
  for (var state : states.values()) {
    var sc = state.getClass();
    var row = new LinkedHashMap<String, Object>();
    var lap = sc.getDeclaredField("lapNumber"); lap.setAccessible(true); row.put("lap", lap.get(state));
    for (var name : List.of("cart", "horse")) {
      var g = sc.getDeclaredField(name); g.setAccessible(true); row.put(name, String.valueOf(g.get(state)));
    }
    var ab = sc.getDeclaredField("aboard"); ab.setAccessible(true);
    var aboard = new ArrayList<Object>();
    for (var c : ((Map<?, ?>) ab.get(state)).values()) {
      var cc = c.getClass();
      var m = new LinkedHashMap<String, Object>();
      for (var name : List.of("count", "from", "to", "loaded", "goods")) {
        var g = cc.getDeclaredField(name); g.setAccessible(true);
        var v = g.get(c);
        m.put(name, v instanceof Number || v instanceof Boolean ? v : String.valueOf(v));
      }
      aboard.add(m);
    }
    row.put("aboard", aboard);
    lines.add(row);
  }
}
out.put("lines", lines);
var observer = level.getEntity(UUID.fromString(a.list("residents").getFirst().toString()));
var motion = observer.getPersistentData().getList("cartMotion", 10);
var rows = new ArrayList<Object>();
for (var entry : motion) {
  var tag = (net.minecraft.nbt.CompoundTag) entry;
  var row = new LinkedHashMap<String, Object>();
  for (var key : tag.getAllKeys()) {
    row.put(key, tag.get(key) instanceof net.minecraft.nbt.NumericTag n ? n.getAsDouble() : tag.getString(key));
  }
  rows.add(row);
}
observer.getPersistentData().put("cartMotion", new net.minecraft.nbt.ListTag());
out.put("motion", rows);
return out;`,
  }),
});

const COUNT = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-count",
  side: "server",
  source: javaTask({
    body: `
var a = Args.of(ctx);
var p = (Map<?, ?>) a.value("at");
var pos = new net.minecraft.core.BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
var container = (net.minecraft.world.Container) ctx.server().overworld().getBlockEntity(pos);
var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(a.string("item")));
int n = 0;
for (int i = 0; i < container.getContainerSize(); i++) if (container.getItem(i).is(item)) n += container.getItem(i).getCount();
return Map.of("count", n);`,
  }),
});

export type { MountedScene };

const BAKERY = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-bakery",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.terms.Containers",
      "io.github.izakyl.folkways.core.api.terms.Reach",
      "io.github.izakyl.folkways.plugins.wares.WaresContent",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.component.DataComponents",
      "net.minecraft.world.Container",
      "net.minecraft.world.entity.item.ItemEntity",
      "net.minecraft.world.item.ItemStack",
      "net.minecraft.world.item.Items",
      "net.minecraft.world.item.crafting.RecipeType",
    ],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var out = new LinkedHashMap<String, Object>();
out.put("stalls", colony.stallsIn(level.dimension()).toString());
out.put("stations", colony.view(level).blocks(WaresContent.STATION).toString());
var p = (Map<?, ?>) a.value("bench");
var bench = new BlockPos(((Number) p.get("x")).intValue(),
    ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
out.put("footings", Reach.workableCells(level, bench).toString());
out.put("breadRecipes", level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
    .filter(r -> r.value().getResultItem(level.registryAccess()).is(Items.BREAD))
    .map(r -> Map.of("recipe", r.id().toString(), "ingredients", r.value().getIngredients().toString())).toList());
// Count every possible resting place, including packs and spills, so moving wheat cannot look like consuming it.
int[] counts = {0, 0, 0};
java.util.function.Consumer<ItemStack> count = stack -> {
  if (stack.is(Items.BREAD)) {
    counts[0] += stack.getCount();
  }
  if (stack.is(Items.WHEAT)) {
    counts[1] += stack.getCount();
    var data = stack.get(DataComponents.CUSTOM_DATA);
    if (data != null && data.copyTag().getBoolean("promo_cart_wheat")) {
      counts[2] += stack.getCount();
    }
  }
};
var stocks = new ArrayList<Object>();
var seen = new HashSet<BlockPos>();
for (var where : colony.storesIn(level.dimension())) {
  var pos = Containers.anchor(level, where.block(level));
  if (!seen.add(pos)) {
    continue;
  }
  var found = Containers.at(level, pos);
  if (found.isEmpty()) {
    continue;
  }
  var box = found.get();
  var items = new ArrayList<String>();
  for (int i = 0; i < box.getContainerSize(); i++) {
    count.accept(box.getItem(i));
    if (!box.getItem(i).isEmpty()) {
      items.add(box.getItem(i).toString());
    }
  }
  stocks.add(Map.of("at", pos.toShortString(), "items", items));
}
out.put("stocks", stocks);
var packs = new ArrayList<Object>();
for (var entity : level.getAllEntities()) {
  Container box = entity instanceof ResidentEntity body ? body.pack()
      : entity instanceof Container container ? container : null;
  if (box != null) {
    var items = new ArrayList<String>();
    for (int i = 0; i < box.getContainerSize(); i++) {
      count.accept(box.getItem(i));
      if (!box.getItem(i).isEmpty()) {
        items.add(box.getItem(i).toString());
      }
    }
    packs.add(Map.of("entity", entity.getUUID().toString(), "items", items));
  } else if (entity instanceof ItemEntity dropped) {
    count.accept(dropped.getItem());
  }
}
out.put("packs", packs);
out.put("bread", counts[0]);
out.put("wheat", counts[1]);
out.put("transportedWheat", counts[2]);
return out;`,
  }),
});

// Read-only observer: post-tick positions also measure Redux's direct cart movement.
const MOTION = defineTask<Record<string, any>, any>({
  name: "folkways.promo.carts-motion",
  side: "server",
  source: javaTask({
    body: `
var level = ctx.server().overworld();
var actor = level.getEntity(UUID.fromString(Args.of(ctx).string("actor")));
var previous = new HashMap<UUID, net.minecraft.world.phys.Vec3>();
long[] lastTick = {-1};
int[] hitchedTicks = {0};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
    net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, event -> {
  if (actor.isRemoved() || event.getServer() != ctx.server() || lastTick[0] == level.getGameTime()) {
    return;
  }
  lastTick[0] = level.getGameTime();
  for (var e : level.getAllEntities()) {
    if (!(e instanceof com.jusipat.astikorcartsredux.entity.SupplyCartEntity cart) || !e.isAlive()) {
      continue;
    }
    var row = new net.minecraft.nbt.CompoundTag();
    row.putLong("tick", level.getGameTime());
    var team = new LinkedHashMap<String, net.minecraft.world.entity.Entity>();
    team.put("cart", cart);
    if (cart.getPulling() instanceof net.minecraft.world.entity.Mob horse) {
      team.put("horse", horse);
      row.putInt("hitchedTicks", ++hitchedTicks[0]);
      var trailing = cart.position().subtract(horse.position()).multiply(1, 0, 1).normalize();
      var facing = net.minecraft.world.phys.Vec3.directionFromRotation(0, horse.getYRot());
      row.putDouble("trailingAngle", Math.toDegrees(Math.acos(Math.clamp(facing.dot(trailing), -1, 1))));
      row.putString("leader", horse.getLeashHolder() == null ? "" : horse.getLeashHolder().getUUID().toString());
      row.putBoolean("leashed", horse.isLeashed());
      row.putBoolean("horseNoAi", horse.isNoAi());
      row.putDouble("horseSpeedSetting", horse.getSpeed());
      if (horse.getLeashHolder() != null) {
        team.put("leader", horse.getLeashHolder());
        row.putDouble("leashDistance", horse.distanceTo(horse.getLeashHolder()));
      }
    }
    for (var entry : team.entrySet()) {
      var body = entry.getValue();
      var last = previous.put(body.getUUID(), body.position());
      row.putDouble(entry.getKey() + "X", body.getX());
      row.putDouble(entry.getKey() + "Z", body.getZ());
      row.putDouble(entry.getKey() + "V", last == null ? 0 : body.position().subtract(last).horizontalDistance());
      if (body instanceof net.minecraft.world.entity.Mob mob) {
        var path = mob.getNavigation().getPath();
        row.putString(entry.getKey() + "Target", path == null ? "null" : path.getTarget().toShortString());
        row.putBoolean(entry.getKey() + "NavDone", mob.getNavigation().isDone());
      }
    }
    var rows = actor.getPersistentData().getList("cartMotion", 10);
    rows.add(row);
    actor.getPersistentData().put("cartMotion", rows);
  }
});
return Map.of("observing", true);`,
  }),
});

const CARD_VIEW = defineTask<Record<string, never>, CardView>({
  name: "folkways.promo.carts-card-view",
  side: "client",
  source: javaTask({
    imports: [
      "net.minecraft.client.Minecraft",
      "io.github.izakyl.folkways.front.engine.item.HeldBook",
      "io.github.izakyl.folkways.front.client.ColonyLookState",
      "io.github.izakyl.folkways.carts.CartsCards",
      "io.github.izakyl.folkways.carts.CartContents",
      "com.jusipat.astikorcartsredux.entity.AbstractCargoCart",
      "io.github.izakyl.folkways.front.api.notice.Line",
    ],
    body: `
var mc = Minecraft.getInstance();
var stock = new ArrayList<Object>();
int empty = 0;
for (int id : CartsCards.shown()) {
    if (mc.level.getEntity(id) instanceof AbstractCargoCart cart) {
        var lines = CartContents.lines(cart);
        boolean any = false;
        for (var line : lines) {
            if (line instanceof Line.Goods goods) {
                for (var item : goods.stacks()) {
                    stock.add(Map.of("item", item.item().toString(), "count", item.count()));
                    any = true;
                }
            }
        }
        if (!any) {
            empty++;
        }
    }
}
return Map.of("hidden", mc.options.hideGui, "book", HeldBook.bound(mc.player).isPresent(),
    "cards", ColonyLookState.residents().size(), "cartCards", CartsCards.shown().size(),
    "stock", stock, "emptyCarts", empty);`,
  }),
});

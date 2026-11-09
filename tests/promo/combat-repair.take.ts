import { expect, test } from "@playwright/test";
import { camera, defineTask, input, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { clickElement, waitForElement } from "../ldlib2";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { frames } from "../shared/harness/bw-helpers";
import { castCombat, openCombat } from "./combat-scene";
import { licenseCrewFast } from "./fast-setup";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import {
  DAMAGE_BASELINE,
  DAMAGE_DIAGNOSTIC,
  DAMAGE_PLANS,
  DAMAGE_HUD,
  DAMAGE_PANEL_INPUT,
  DAMAGE_STATE,
} from "./combat-recovery";
import { promoClip, sleepMs, take, type PromoPair } from "./promo-kit";

// The only incident is native TNT; all subsequent placement and material collection belong to resident AI.
test("combat: on-camera TNT breach and physical-store baseline repair", async ({}, testInfo) => {
  const run = take("combat-repair");
  run.note("verified", false);
  run.note("visualReview", { approved: false, frames: [], notes: "Awaiting actual MP4 frame review" });
  let pair: PromoPair | undefined;
  try {
    const settlement = await openCombat(run.scope, run);
    pair = settlement.pair;
    const o = settlement.at(18, 0, -18);
    const scene = {
      ...settlement,
      o,
      at: (x: number, y: number, z: number) => ({ x: o.x + x, y: o.y + y, z: o.z + z }),
    };
    await world.command(scene.server, "gamerule doTileDrops false");
    await scene.fill(scene.at(-10, -2, -7), scene.at(10, 8, 10), "minecraft:air");
    await scene.fill(scene.at(-10, -2, -7), scene.at(10, -2, 10), "minecraft:bedrock");
    await scene.fill(scene.at(-10, -1, -7), scene.at(10, -1, 10), "minecraft:stone_bricks");
    await scene.fill(scene.at(-4, 0, -3), scene.at(4, 1, -3), "minecraft:stone_bricks");
    const store = scene.at(-7, 0, 4);
    await scene.set(store, "minecraft:barrel");
    await runColonyTask(scene.server, MARK_MEMBERS, {
      player: scene.name, colony_id: scene.colony, cells: [store.x, store.y, store.z],
    });
    const cast = await castCombat(scene, "fire");
    // One resident repairs and fetches its own materials; the other cast member has no work permissions.
    const residents = [cast.residents[0]];
    await licenseCrewFast(scene.server, cast.residents.map(uuid => ({ uuid, trades: [] })));
    const args = { colony: scene.colony, ...o, residents, store };
    run.note("kits", await scene.server.run(REPAIR_KIT, { ...args, allResidents: cast.residents }));
    const fixture = await scene.server.run(DAMAGE_BASELINE, {
      ...args, bounds: [-5, -2, -4, 5, 3, 4], repair: true,
    });
    run.note("baseline", fixture);
    await world.command(scene.server, `gamemode creative ${scene.name}`);
    await world.command(scene.server, `tp ${scene.name} ${o.x} ${o.y + 5} ${o.z + 8}`);
    await screen.dismiss(scene.client);
    await input.key(scene.client, { keyCode: 86 });
    await screen.waitFor(scene.client, "OverheadScreen");
    const mode = (await screen.widgets(scene.client)).find(widget =>
      /^(损管|Damage Control)$/.test(widget.label?.trim() ?? ""));
    expect(mode).toBeTruthy();
    await screen.clickWidget(scene.client, { label: mode!.label });
    const baselineEye = scene.at(0, 23, 19);
    await camera.set(scene.client, {
      position: baselineEye, ...cameraLookingAt(baselineEye, scene.at(0, 0, 0)), fov: 60,
    });
    await tick.sprint(scene.server, 25);
    await frames(scene.client, 8);
    const view = await screen.current(scene.client);
    const pointer = { x: view!.width / 2, y: view!.height / 2 };
    await input.move(scene.client, pointer);
    await expect.poll(async () => {
      await tick.sprint(scene.server, 3);
      await frames(scene.client, 3);
      return scene.client.run(DAMAGE_PANEL_INPUT);
    }).toEqual({ creative: true, bookColony: scene.colony, aimedZone: fixture.zone });
    run.note("baselinePanelInput", await scene.client.run(DAMAGE_PANEL_INPUT));
    await input.click(scene.client, pointer, { button: "right" });
    await waitForElement(scene.client, { id: "folkways.zoneconfig.action.record" });
    await clickElement(scene.client, { id: "folkways.zoneconfig.action.record" });
    await expect.poll(async () => {
      await tick.sprint(scene.server, 3);
      return (await scene.server.run(DAMAGE_PLANS, args)).baselines;
    }).toBe(1);
    await screen.dismiss(scene.client);
    await screen.waitFor(scene.client, "OverheadScreen");
    await input.key(scene.client, { keyCode: 86 });
    await licenseCrewFast(scene.server, [{ uuid: residents[0], trades: ["damage_control", "hauling"] }]);
    await world.command(scene.server, `gamemode spectator ${scene.name}`);
    await world.command(scene.server, `tp ${scene.name} ${o.x} ${o.y + 5} ${o.z + 8}`);
    await world.command(scene.server,
      `execute positioned ${o.x} ${o.y} ${o.z} run kill @e[type=minecraft:item,distance=..25]`);
    await camera.hud(scene.client, true);
    await input.key(scene.client, { keyCode: 86 });
    await screen.waitFor(scene.client, "OverheadScreen");
    await screen.clickWidget(scene.client, { label: mode!.label });
    const pose = { eye: scene.at(15, 18, 21), target: scene.at(-1, 0, 0), fov: 56 };
    await camera.set(scene.client, {
      position: pose.eye, ...cameraLookingAt(pose.eye, pose.target), fov: pose.fov,
    });
    await tick.freeze(scene.server, false);
    await sleepMs(1500);
    await tick.freeze(scene.server, true);
    for (const pixel of [
      { x: 500, y: 180 }, { x: 520, y: 200 }, { x: 480, y: 180 },
      { x: 280, y: 300 }, { x: 360, y: 270 }, { x: 450, y: 250 },
      { x: 550, y: 250 }, { x: 650, y: 300 }, { x: 350, y: 350 },
    ]) {
      await input.move(scene.client, pixel);
      await frames(scene.client, 3);
      if ((await scene.client.run(DAMAGE_HUD)).card) {
        run.note("informationCardPointer", pixel);
        break;
      }
    }
    expect((await scene.client.run(DAMAGE_HUD)).card).toBe(true);
    await scene.client.run(CLEAR_STAGING_MESSAGES);
    const before = await scene.server.run(DAMAGE_STATE, { ...args, ...fixture });
    expect(before.missing).toBe(0);
    expect(before.fires).toBe(0);
    run.note("before", before);
    run.note("camera", pose);
    run.note("designAudit", {
      reviewer: "rts_design_auditor", approved: true,
      scope: "Isolated promo fixture; existing input, authority, finite materials and native permission contracts",
      deferred: "Film integration and generalized stance regeneration",
      limitation: "Static design audit; runtime and visual acceptance are recorded separately",
    });
    run.note("staging", {
      incident: "Native primed TNT, 60-tick fuse; no explosion drops or fire spread",
      materials: "Finite colony barrel; repairer starts with no building materials and has native hauling permission",
      terrain: "Open settlement workshop; shallow bedrock subfloor limits depth without replacing damaged cells",
      baseline: "Real creative-player right click and native Reset baseline button, before detonation",
      speed: "20 ticks/s throughout capture; no tick sprint or commands restore blocks",
    });
    await run.shot(scene.client, "layout", {
      subject: "Intact protected workshop, reachable material barrel and native Damage Control card",
      worldState: { fixture, before, pose, store },
    });
    run.note("observer", await scene.server.run(REPAIR_OBSERVER, { ...args, ...fixture }));
    const samples: any[] = [];
    let completedAt = 0;
    const gates = await promoClip(run, scene.client, scene.server, "01-repair", {
      seconds: 1,
      fps: 30,
      subject: "Native TNT detonates, then a resident fetches physical materials and restores the breach",
      worldState: { fixture, before, store },
      during: async () => {
        const start = Date.now();
        run.note("bomb", await scene.server.run(REPAIR_BOMB, args));
        let reported = 0;
        while (Date.now() - start < 110_000) {
          const state = await scene.server.run(DAMAGE_STATE, { ...args, ...fixture });
          const evidence = await scene.server.run(REPAIR_EVIDENCE, args);
          const hud = await scene.client.run(DAMAGE_HUD);
          const presentation = await scene.client.run(REPAIR_PRESENTATION);
          samples.push({ ...state, evidence, hud, presentation, atMs: Date.now() - start });
          if (Date.now() - start - reported > 5000) {
            run.note("progress", samples.at(-1));
            run.note("repairDiagnostics", await scene.server.run(REPAIR_DIAGNOSTIC, args));
            reported = Date.now() - start;
          }
          if (evidence.maximumMissing >= 10 && state.missing === 0 && completedAt === 0) {
            completedAt = Date.now() - start;
            run.note("completeAtMs", completedAt);
          }
          if (completedAt && Date.now() - start >= completedAt + 2000) {
            break;
          }
          await sleepMs(130);
        }
      },
    });
    const after = await scene.server.run(DAMAGE_STATE, { ...args, ...fixture });
    const evidence = await scene.server.run(REPAIR_EVIDENCE, args);
    run.note("samples", samples);
    run.note("diagnostics", await scene.server.run(DAMAGE_DIAGNOSTIC, args));
    run.note("finalPlans", await scene.server.run(DAMAGE_PLANS, args));
    run.note("summary", { before, after, ...evidence, completedAtMs: completedAt });
    expect(evidence.maximumMissing, "native explosion must remove at least ten baseline blocks").toBeGreaterThanOrEqual(10);
    expect(evidence.fireTicks, "every native repair tick must be fire-free").toBe(0);
    expect(after.fires).toBe(0);
    expect(after.missing, "every exact baseline state restored").toBe(0);
    expect(evidence.restored).toBe(evidence.maximumMissing);
    expect(evidence.storeBefore - evidence.storeNow, "physical store loss equals restored blocks").toBe(evidence.restored);
    expect(after.materials, "no unused material withdrawn into the repairer's pack").toBe(0);
    expect(evidence.repairTicks, "licensed damage-control worker actually repairing").toBeGreaterThan(0);
    expect(samples.every(s => s.hud.active && s.hud.area && s.hud.card && !s.hud.hidden)).toBe(true);
    expect(samples.every(s => s.presentation.overlay === "" && s.presentation.toasts === 0
      && s.presentation.bookColony === scene.colony)).toBe(true);
    expect(gates.capture_width).toBe(1920);
    expect(gates.capture_height).toBe(1080);
    expect(gates.audio_muxed).toBe(true);
    run.expectTaken(["layout", "01-repair"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const REPAIR_IMPORTS = [
  "io.github.izakyl.folkways.core.resident.api.Bodies",
  "net.minecraft.core.BlockPos",
  "net.minecraft.world.Container",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
  "net.minecraft.world.level.block.Blocks",
];

const REPAIR_KIT = defineTask<Record<string, any>, any>({
  name: "folkways.promo.repair-store-kit",
  side: "server",
  source: javaTask({
    imports: REPAIR_IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var store = (Container) level.getBlockEntity(a.blockPos("store"));
store.setItem(0, new ItemStack(Items.STONE_BRICKS, 64));
store.setItem(1, new ItemStack(Items.OAK_PLANKS, 64));
int index = 0;
for (var raw : a.list("allResidents")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(raw.toString()))).orElseThrow();
    for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
        body.pack().setItem(slot, ItemStack.EMPTY);
    }
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 16));
    body.mob().moveTo(a.number("x") - 6.5, a.number("y"), a.number("z") + 4.5 + index++ * 30, 180, 0);
    body.mob().getNavigation().stop();
}
return Map.of("stoneBricks", 64, "oakPlanks", 64, "residentMaterials", 0);`,
  }),
});

const REPAIR_OBSERVER = defineTask<Record<string, any>, any>({
  name: "folkways.promo.repair-native-tick-observer",
  side: "server",
  source: javaTask({
    imports: REPAIR_IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var actor = level.getEntity(UUID.fromString(a.list("residents").getFirst().toString()));
var store = (Container) level.getBlockEntity(a.blockPos("store"));
var data = actor.getPersistentData();
int stock = 0;
for (int i = 0; i < store.getContainerSize(); i++) {
    var stack = store.getItem(i);
    if (stack.is(Items.STONE_BRICKS) || stack.is(Items.OAK_PLANKS)) {
        stock += stack.getCount();
    }
}
data.putInt("storeBefore", stock);
int[] previous = {0};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
    net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, event -> {
        if (actor.isRemoved()) {
            return;
        }
        int missing = 0;
        for (var raw : a.list("baseline")) {
            var row = (Map<?, ?>) raw;
            var at = new BlockPos(((Number) row.get("x")).intValue(),
                ((Number) row.get("y")).intValue(), ((Number) row.get("z")).intValue());
            if (!level.getBlockState(at).toString().equals(row.get("state"))) {
                missing++;
            }
        }
        if (missing > data.getInt("maximumMissing")) {
            data.putInt("maximumMissing", missing);
            data.putLong("detonationTick", level.getGameTime());
        }
        if (missing < previous[0]) {
            data.putInt("restored", data.getInt("restored") + previous[0] - missing);
            if (!data.contains("firstRestoredTick")) {
                data.putLong("firstRestoredTick", level.getGameTime());
            }
            data.putLong("lastRestoredTick", level.getGameTime());
        }
        if (data.getInt("maximumMissing") > 0) {
            var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
            for (var at : BlockPos.betweenClosed(o.offset(-10, -2, -7), o.offset(10, 8, 10))) {
                if (level.getBlockState(at).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock) {
                    data.putInt("fireTicks", data.getInt("fireTicks") + 1);
                    break;
                }
            }
        }
        if (Bodies.of(actor).orElseThrow().doing().map(d -> d.what().toString())
            .orElse("").equals("folkways_vanilla:repairing")) {
            data.putInt("repairTicks", data.getInt("repairTicks") + 1);
        }
        previous[0] = missing;
    });
return Map.of("storeBefore", stock, "startTick", level.getGameTime());`,
  }),
});

const REPAIR_EVIDENCE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.repair-tick-evidence",
  side: "server",
  source: javaTask({
    imports: REPAIR_IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var data = level.getEntity(UUID.fromString(a.list("residents").getFirst().toString())).getPersistentData();
var store = (Container) level.getBlockEntity(a.blockPos("store"));
int stock = 0;
for (int i = 0; i < store.getContainerSize(); i++) {
    var stack = store.getItem(i);
    if (stack.is(Items.STONE_BRICKS) || stack.is(Items.OAK_PLANKS)) {
        stock += stack.getCount();
    }
}
var out = new LinkedHashMap<String, Object>();
for (String key : List.of("maximumMissing", "restored", "fireTicks", "repairTicks", "storeBefore")) {
    out.put(key, data.getInt(key));
}
for (String key : List.of("detonationTick", "firstRestoredTick", "lastRestoredTick")) {
    out.put(key, data.getLong(key));
}
out.put("storeNow", stock);
return out;`,
  }),
});

const REPAIR_PRESENTATION = defineTask<void, any>({
  name: "folkways.promo.repair-visible-feedback",
  side: "client",
  source: javaTask({
    body: `
var mc = net.minecraft.client.Minecraft.getInstance();
var overlay = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
overlay.setAccessible(true);
var time = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageTime");
time.setAccessible(true);
var message = (net.minecraft.network.chat.Component) overlay.get(mc.gui);
var bound = io.github.izakyl.folkways.core.front.engine.item.HeldBook.bound(mc.player);
var visible = mc.getToasts().getClass().getDeclaredField("visible");
visible.setAccessible(true);
return Map.of("overlay", time.getInt(mc.gui) > 0 && message != null ? message.getString() : "",
    "toasts", ((List<?>) visible.get(mc.getToasts())).size(),
    "bookColony", bound.map(book -> book.colonyId().toString()).orElse(""));`,
  }),
});

const REPAIR_BOMB = defineTask<Record<string, any>, any>({
  name: "folkways.promo.repair-primed-tnt",
  side: "server",
  source: javaTask({
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var bomb = new net.minecraft.world.entity.item.PrimedTnt(level,
    a.number("x") + .5, a.number("y"), a.number("z") + .5, null);
bomb.setFuse(60);
level.addFreshEntity(bomb);
return Map.of("uuid", bomb.getUUID().toString(), "tick", level.getGameTime(), "fuse", bomb.getFuse());`,
  }),
});

const REPAIR_DIAGNOSTIC = defineTask<Record<string, any>, any>({
  name: "folkways.promo.repair-retained-stance-diagnostic",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent",
      "io.github.izakyl.folkways.core.world.api.Footing",
      "io.github.izakyl.folkways.core.world.api.Reach",
      "io.github.izakyl.folkways.core.work.api.Node",
      "io.github.izakyl.folkways.core.work.api.WorkSite",
    ],
    body: `
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var presence = colony.service(DamageControlContent.ID, Object.class).orElseThrow();
var field = presence.getClass().getDeclaredField("repairs");
field.setAccessible(true);
var rows = new ArrayList<Object>();
for (var raw : ((Map<?, ?>) field.get(presence)).values()) {
    var getter = raw.getClass().getDeclaredMethod("node");
    getter.setAccessible(true);
    var node = (Node) getter.invoke(raw);
    var at = ((WorkSite.AtBlock) node.spec().site()).pos().block(level);
    var cells = node.spec().stances().cells();
    rows.add(Map.of("at", at.toShortString(), "ready", node.ready(level),
        "oldStances", cells.stream().map(Object::toString).toList(),
        "validOldStances", cells.stream().filter(pos -> Footing.withFeetAt(level, pos.block(level))).count(),
        "freshStances", Reach.workableCells(level, at).stream().map(Object::toString).toList()));
}
return rows;`,
  }),
});

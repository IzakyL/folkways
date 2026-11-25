// Exploratory probe (not a regression test): a colony with several kinds of work and more residents than any one
// kind needs, run on the real clock with off-thread planning as players have it, sampling who is idle and why.
import { test } from "@playwright/test";
import { appendFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, waitForGroundedPlayer, TRADE_TOOLS, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { commissionAll } from "../shared/colony/commission";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { installWorkTap, dumpWorkTap } from "../bench/probes/work-tap";

const RESIDENTS = Number(process.env.PROBE_RESIDENTS ?? 10);
const RUN_TICKS = Number(process.env.PROBE_TICKS ?? 6000);
const SAMPLE_MS = 5000;

const PROBE = defineTask<{ colony_id: string; chest?: number[]; stores?: number[] }, Record<string, any>>({
  name: "folkways.probe.idle",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.runtime.Labor",
      "io.github.izakyl.folkways.core.runtime.LaborSnapshot",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var level = ctx.server().overworld();
var colony = UUID.fromString(Args.of(ctx).string("colony_id"));
var out = new LinkedHashMap<String, Object>();
out.put("game_time", level.getGameTime());
var site = Labor.site(colony, level.dimension());
if (site.isEmpty()) { out.put("labor", false); return out; }
var labor = site.get();
var snap = labor.snapshot(level);
var c = snap.census();
out.put("standing", String.valueOf(c.standing()));
out.put("phases", String.valueOf(c.phases()));
out.put("planned", c.planned());
out.put("unassigned", String.valueOf(c.unassigned()));
out.put("missing", String.valueOf(c.missing()));
out.put("shortages", String.valueOf(snap.shortages()));
out.put("examples", String.join(" | ", snap.examples()));
out.put("heartbeat", labor.consumeHeartbeat());
out.put("stalls", String.join("\\n", labor.stalls()));
var col = io.github.izakyl.folkways.core.front.engine.colony.ColonyGround.of(ctx.server(), colony).orElseThrow();
var presence = col.service(ResourceLocation.parse("folkways_vanilla:build"), Object.class).orElse(null);
if (presence != null) {
  var sb = new StringBuilder();
  var sitesField = presence.getClass().getDeclaredField("sites"); sitesField.setAccessible(true);
  for (var e : ((Map<?, ?>) sitesField.get(presence)).entrySet()) {
    var bsite = e.getValue();
    sb.append("site ").append(e.getKey()).append(':');
    for (var f : bsite.getClass().getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
      f.setAccessible(true);
      var v = f.get(bsite);
      if (v instanceof Map<?, ?> m) sb.append(' ').append(f.getName()).append('=').append(m.size());
      else if (v instanceof Collection<?> cc) sb.append(' ').append(f.getName()).append('=').append(cc.size());
      else if (v instanceof Number || v instanceof Boolean) sb.append(' ').append(f.getName()).append('=').append(v);
    }
    var refused = bsite.getClass().getDeclaredField("refused"); refused.setAccessible(true);
    var why = new TreeMap<String, Integer>();
    for (var r : ((Map<?, ?>) refused.get(bsite)).values()) why.merge(String.valueOf(r), 1, Integer::sum);
    sb.append(" refusedBy=").append(why);
    var stays = bsite.getClass().getDeclaredField("stays"); stays.setAccessible(true);
    for (var st : ((Map<?, ?>) stays.get(bsite)).values()) {
      var b = st.getClass().getDeclaredField("batch"); b.setAccessible(true);
      var d = st.getClass().getDeclaredField("did"); d.setAccessible(true);
      sb.append(" stay[batch=").append(((Collection<?>) b.get(st)).size()).append(",did=").append(d.get(st))
        .append(",at=").append(((io.github.izakyl.folkways.core.work.api.Node) st).spec().site().where())
        .append(",tools=").append(((io.github.izakyl.folkways.core.work.api.Node) st).spec().tools()).append(']');
    }
    sb.append("\\n");
    var sf = labor.getClass().getDeclaredField("survey"); sf.setAccessible(true);
    var survey = sf.get(labor);
    var hf = survey.getClass().getDeclaredField("hands"); hf.setAccessible(true);
    for (var st : ((Map<?, ?>) stays.get(bsite)).values()) {
      var spec = ((io.github.izakyl.folkways.core.work.api.Node) st).spec();
      sb.append("  fit ").append(spec.site().where()).append(':');
      for (var h : ((Map<?, ?>) hf.get(survey)).values()) {
        var hand = (io.github.izakyl.folkways.core.plan.internal.Crew.Hand) h;
        boolean ok = io.github.izakyl.folkways.core.work.support.Tools.canPrepare(hand.who().kind(), spec, hand.tools(), hand.toolRules());
        sb.append(' ').append(hand.id().toString(), 0, 8).append(ok ? "=ok" : "=no").append("[").append(hand.tools().size())
          .append(hand.tools().stream().anyMatch(t -> t.getItem() instanceof net.minecraft.world.item.PickaxeItem) ? "P" : "")
          .append(hand.personalOnly() ? ",personal" : "").append(hand.takes(spec.vocation()) ? "" : ",nolicence").append(']');
      }
      sb.append("\\n");
      var gf = labor.getClass().getDeclaredField("graph"); gf.setAccessible(true);
      var graph = (io.github.izakyl.folkways.core.plan.internal.WeaveView) gf.get(labor);
      var sid = ((io.github.izakyl.folkways.core.work.api.Node) st).id();
      sb.append("   group of ").append(sid.toString(), 0, 8).append(" has=").append(graph.has(sid)).append(':');
      for (var member : graph.group(sid)) {
        var vx = graph.vertex(member);
        if (vx == null) { sb.append(" ").append(member.toString(), 0, 8).append("=gone"); continue; }
        var ms = vx.node().spec();
        sb.append("\\n     ").append(member.toString(), 0, 8).append(' ').append(vx.node().getClass().getSimpleName())
          .append(" voc=").append(ms.vocation().map(v -> v.id().getPath()).orElse("-"))
          .append(" pin=").append(graph.pin(member)).append(" worker=").append(vx.node().worker().map(u -> u.toString().substring(0, 8)).orElse("-"))
          .append(" tools=").append(ms.tools().stream().map(t -> (t.required() ? "!" : "?") + t.need()).toList())
          .append(" fit=");
        for (var h : ((Map<?, ?>) hf.get(survey)).values()) {
          var hand = (io.github.izakyl.folkways.core.plan.internal.Crew.Hand) h;
          boolean ok = io.github.izakyl.folkways.core.work.support.Tools.canPrepare(hand.who().kind(), ms, hand.tools(), hand.toolRules()) && hand.takes(ms.vocation());
          if (ok) sb.append(hand.id().toString(), 0, 4).append(',');
        }
      }
      sb.append("\\n");
    }
  }
  out.put("build", sb.toString());
}
var tools = new StringBuilder();
for (var ent : level.getAllEntities()) {
  var body = io.github.izakyl.folkways.core.resident.api.Bodies.of(ent);
  if (body.isEmpty()) continue;
  tools.append(ent.getUUID().toString(), 0, 8).append(':');
  var pack = body.get().pack();
  for (int i = 0; i < pack.getContainerSize(); i++) {
    var st = pack.getItem(i);
    if (st.isEmpty() || !st.isDamageableItem()) continue;
    tools.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath())
      .append('(').append(st.getMaxDamage() - st.getDamageValue()).append(')');
  }
  if (ent instanceof net.minecraft.world.entity.LivingEntity le && le.getMainHandItem().isDamageableItem())
    tools.append(" hand=").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem()).getPath());
  tools.append(" | ");
}
out.put("tools", tools.toString());
var ch = Args.of(ctx).list("chest");
if (ch != null && level.getBlockEntity(new net.minecraft.core.BlockPos(((Number) ch.get(0)).intValue(), ((Number) ch.get(1)).intValue(), ((Number) ch.get(2)).intValue())) instanceof net.minecraft.world.Container box) {
  var counts = new TreeMap<String, Integer>();
  for (int i = 0; i < box.getContainerSize(); i++) { var st = box.getItem(i); if (!st.isEmpty()) counts.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum); }
  out.put("tool_chest", String.valueOf(counts));
}
var all = Args.of(ctx).list("stores");
var everywhere = new TreeMap<String, Integer>();
for (int k = 0; all != null && k + 2 < all.size(); k += 3) {
  if (level.getBlockEntity(new net.minecraft.core.BlockPos(((Number) all.get(k)).intValue(), ((Number) all.get(k + 1)).intValue(), ((Number) all.get(k + 2)).intValue())) instanceof net.minecraft.world.Container box) {
    for (int i = 0; i < box.getContainerSize(); i++) { var st = box.getItem(i); if (!st.isEmpty() && st.isDamageableItem()) everywhere.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath() + "@" + k / 3, st.getCount(), Integer::sum); }
  }
}
var looseTools = 0;
for (var ent : level.getAllEntities()) if (ent instanceof net.minecraft.world.entity.item.ItemEntity ie && ie.getItem().isDamageableItem()) looseTools += ie.getItem().getCount();
out.put("stored_tools", String.valueOf(everywhere));
out.put("loose_tools", looseTools);
return out;
`,
  }),
});

test("probe: idle residents in a colony with plenty of work", async ({}, testInfo) => {
  test.setTimeout(60 * 60_000);
  ensureOut("e2e");
  const runId = newRunId();
  const dir = outAbs("e2e", "reports", "idle-probe");
  mkdirSync(dir, { recursive: true });
  const samples = path.join(dir, `samples-${runId}.ndjson`);
  writeFileSync(samples, "");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "idle-probe-server"), instance: seededOffThreadServer(`idle-probe-server-${runId}`) },
      client: { trace: tracePath("e2e", "idle-probe-client"), instance: `idle-probe-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await tryCommand(server, `gamemode creative ${player}`);
    const o: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(o);
    // Flat stone floor under the whole set so nothing is lost to terrain.
    await tryCommand(server, `fill ${box.min.x} ${o.y - 1} ${box.min.z} ${box.max.x} ${o.y - 1} ${box.max.z} minecraft:stone`);
    const founding = await foundColonyFast(server, client, player, { origin: o, residentCount: RESIDENTS });
    const colony = String(founding.evidence.colonyId);

    const at = (dx: number, dz: number, dy = 0): Vec => ({ x: o.x + dx, y: o.y + dy, z: o.z + dz });
    const set = (p: Vec, block: string) => tryCommand(server, `setblock ${commandPos(p)} ${block}`);
    const put = (p: Vec, slot: number, item: string, n: number) =>
      tryCommand(server, `item replace block ${commandPos(p)} container.${slot} with ${item} ${n}`);

    // Stores: tools, fuel, ore, building stone, seeds, logs.
    const chests = [at(-16, -4), at(-14, -4), at(-12, -4), at(-10, -4), at(-8, -4), at(-6, -4)];
    for (const c of chests) await set(c, "minecraft:chest");
    let slot = 0;
    for (let i = 0; i < RESIDENTS; i++) for (const t of [...TRADE_TOOLS.farming, ...TRADE_TOOLS.building]) await put(chests[0], slot++, t, 1);
    for (let s = 0; s < 4; s++) await put(chests[1], s, "minecraft:coal", 64);
    for (let s = 0; s < 8; s++) await put(chests[2], s, "minecraft:raw_iron", 64);
    for (let s = 0; s < 20; s++) await put(chests[3], s, "minecraft:cobblestone", 64);
    for (let s = 0; s < 4; s++) await put(chests[4], s, "minecraft:wheat_seeds", 64);
    for (let s = 0; s < 6; s++) await put(chests[5], s, "minecraft:oak_log", 64);
    for (let s = 20; s < 24; s++) await put(chests[3], s, "minecraft:stone_bricks", 64);
    for (let s = 24; s < 27; s++) await put(chests[3], s, "minecraft:dirt", 64);

    // Furnaces, each loaded with ore: someone has to fuel them and carry the ingots away.
    const furnaces = [at(-16, 4), at(-14, 4), at(-12, 4), at(-10, 4), at(-8, 4), at(-6, 4)];
    for (const f of furnaces) await set(f, "minecraft:furnace");
    await tick.sprint(server, 3);
    for (const f of furnaces) await put(f, 0, "minecraft:raw_iron", 64);
    // A stone mound to quarry: hundreds of blocks to break and carry.
    await tryCommand(server, `fill ${commandPos(at(-17, 8))} ${commandPos(at(-7, 15, 3))} minecraft:stone`);
    await tryCommand(server, "gamerule randomTickSpeed 30");

    // Farm field with water.
    await tryCommand(server, `fill ${commandPos(at(2, 6, -1))} ${commandPos(at(11, 15, -1))} minecraft:dirt`);
    await set(at(6, 10, -1), "minecraft:water");
    await tick.sprint(server, 3);

    const members = [...chests, ...furnaces];
    const marked = await runColonyTask(server, MARK_MEMBERS, {
      player, colony_id: colony, cells: members.flatMap((c) => [c.x, c.y, c.z]),
    });

    const farmCells: number[][] = [];
    for (let x = 2; x <= 11; x++) for (let z = 6; z <= 15; z++) if (!(x === 6 && z === 10)) farmCells.push([o.x + x, o.y - 1, o.z + z]);
    const farm = await runColonyTask(server, ADD_FARM, { colony_id: colony, cells: farmCells.flat() });

    // A road to build across the yard: many blocks, all from the stores.
    const road = await commissionAll(server, colony, [
      { pattern: "folkways_vanilla:quarry", name: "probe quarry", min: at(-18, 7, -1), max: at(-6, 16, 4), knobs: { depth: 0 } },
      { pattern: "folkways_vanilla:road", name: "probe road", points: [at(-17, 17, -1), at(17, 17, -1)],
        knobs: { width: 3, lights: false, spacing: 12, surface: ["minecraft:cobblestone"], edge: ["minecraft:stone_bricks"], fill: ["minecraft:dirt"] } },
    ]).catch((e) => ({ error: String(e) }));

    // Loose goods to tidy away.
    for (let i = 0; i < 16; i++) {
      const p = at(-3 + i, 19);
      await tryCommand(server, `summon minecraft:item ${p.x + 0.5} ${p.y + 0.5} ${p.z + 0.5} {Item:{id:"minecraft:oak_planks",count:16},PickupDelay:0}`);
    }
    appendFileSync(samples, JSON.stringify({ setup: { colony, residents: founding.residents, marked, farm, road } }) + "\n");

    await installWorkTap(server);
    await tryCommand(server, "time set 0");
    await tick.freeze(server, false);
    const start = Number((await runColonyTask(server, PROBE, { colony_id: colony, chest: [chests[0].x, chests[0].y, chests[0].z], stores: [...chests, ...furnaces].flatMap((c) => [c.x, c.y, c.z]) })).game_time);
    for (;;) {
      await new Promise((r) => setTimeout(r, SAMPLE_MS));
      const s = await runColonyTask(server, PROBE, { colony_id: colony, chest: [chests[0].x, chests[0].y, chests[0].z], stores: [...chests, ...furnaces].flatMap((c) => [c.x, c.y, c.z]) });
      appendFileSync(samples, JSON.stringify(s) + "\n");
      if (Number(s.game_time) - start >= RUN_TICKS) break;
    }
    await tick.freeze(server, true);
    await dumpWorkTap(server, { work: path.join(dir, `work-${runId}.csv`), exertion: path.join(dir, `exertion-${runId}.csv`), yield: path.join(dir, `yield-${runId}.csv`) });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const ADD_FARM = defineTask<{ colony_id: string; cells: number[] }, Record<string, any>>({
  name: "folkways.probe.add-farm",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var server = ctx.server();
var args = Args.of(ctx);
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var raw = args.list("cells");
var cells = new LinkedHashSet<BlockPos>();
for (int i = 0; i + 2 < raw.size(); i += 3) {
  cells.add(new BlockPos(((Number) raw.get(i)).intValue(), ((Number) raw.get(i + 1)).intValue(), ((Number) raw.get(i + 2)).intValue()));
}
var zone = ColonyFront.of(colony).addZone(server.overworld(), ResourceLocation.parse("folkways_vanilla:farm"), cells, ColonySettings.empty());
return Map.of("ok", true, "added", zone.isPresent(), "cells", cells.size());
`,
  }),
});

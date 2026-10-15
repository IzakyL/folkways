import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, screen, tick, world, type MinecraftServer, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer, openColonyPanelViaBook, vocationPriority } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { deliverOrderViaSitePanel, setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage, type Vec } from "../shared/harness/stage";
import { markSouthRack } from "../shared/cookery/rack";
import { clickElement } from "../ldlib2";
import { scrollCellIntoView } from "../shared/ui/site-panel";
import { frames, waitForElement } from "../shared/harness/bw-helpers";

const K = "kaleidoscope_cookery:";
const KNIFE = K + "iron_kitchen_knife";
const NOODLES = K + "raw_noodles";
const MEAT = K + "raw_cut_small_meats";
const DOUGH = K + "raw_dough";
const INITIAL_DAMAGE = 17;
const BATCHES = 8;
const SERVINGS = BATCHES * 3;
const MAX_TICKS = 14000;

type Tool = { id: string; name: string; damage: number; shape: string; where: string; owner: string };
type Board = { input: string; cuts: number; maximum: number };
type Sample = { tick: number; phase: string; tools: Tool[]; boards: Board[]; totals: Record<string, number>;
  target: number[]; queued: number; loans: number; residents: number };
type Reading = { current: Sample; rows: Sample[]; observerFailure: string };
type Layout = ReturnType<typeof kitchen>;

// This task only observes real inventories and native board state. Its tick listener writes test evidence,
// never a colony task, resident pack, recipe progress, order or tool-pool decision.
const OBSERVE = defineTask<{ mode: string; player: string; rack: Vec; boards: Vec[]; targets: Vec[]; phase: string }, Reading>({
  name: "folkways.cookery-tools.observe",
  side: "server",
  timeoutMs: 20000,
  source: javaTask({
    imports: [
      "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity",
      "com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod",
      "io.github.izakyl.folkways.core.api.colony.Books",
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.core.api.terms.WorldPos",
      "io.github.izakyl.folkways.plugins.cookery.tools.TaskTools",
      "io.github.izakyl.folkways.plugins.cookery.CookeryPresence",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.Container",
      "net.minecraft.world.entity.item.ItemEntity",
      "net.minecraft.world.item.ItemStack",
      "net.neoforged.neoforge.common.NeoForge",
      "net.neoforged.neoforge.event.tick.ServerTickEvent",
    ],
    members: `
private static Colony colony;
private static ServerLevel level;
private static BlockPos rack;
private static List<BlockPos> boards;
private static List<BlockPos> targets;
private static String phase = "initial";
private static String previous = "";
private static String observerFailure = "";
private static final List<Object> rows = new ArrayList<>();
private static java.util.function.Consumer<ServerTickEvent.Post> listener;

private static BlockPos pos(Object raw) {
    var value = (Map<?,?>) raw;
    return new BlockPos(((Number)value.get("x")).intValue(), ((Number)value.get("y")).intValue(),
        ((Number)value.get("z")).intValue());
}
private static Object field(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
}
private static void add(ItemStack stack, String where, String owner, Map<String,Long> totals, List<Object> tools,
                        Set<ItemStack> seen) {
    if (stack.isEmpty() || !seen.add(stack)) return;
    String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    totals.merge(id, (long)stack.getCount(), Long::sum);
    if (stack.is(TagMod.KITCHEN_KNIFE)) {
        var normalized = stack.copy();
        normalized.setDamageValue(0);
        tools.add(Map.of("id", id, "name", stack.getHoverName().getString(), "damage", stack.getDamageValue(),
            "shape", normalized.save(level.registryAccess()).toString(), "where", where, "owner", owner));
    }
}
private static Map<String,Object> sample() throws Exception {
    var totals = new TreeMap<String,Long>();
    var tools = new ArrayList<Object>();
    Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<ItemStack,Boolean>());
    for (var at : colony.storesIn(level.dimension())) {
        var found = colony.store(level, at);
        if (found.isEmpty()) throw new IllegalStateException("offered store unavailable: " + at);
        Container store = found.get();
        for (int slot = 0; slot < store.getContainerSize(); slot++) {
            add(store.getItem(slot), at.block(level).equals(rack) ? "rack" : "store", at.toString(), totals, tools, seen);
        }
    }
    int residents = 0;
    for (var resident : colony.residents()) {
        var entity = level.getEntity(resident.id());
        if (entity == null) throw new IllegalStateException("resident unloaded: " + resident.id());
        var body = Bodies.of(entity).orElseThrow();
        residents++;
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            add(body.pack().getItem(slot), "resident", resident.id().toString(), totals, tools, seen);
        }
    }
    for (var entity : level.getAllEntities()) if (entity instanceof ItemEntity item) {
        add(item.getItem(), "drop", item.getUUID().toString(), totals, tools, seen);
    }
    var nativeBoards = new ArrayList<Object>();
    for (var at : boards) {
        var board = (ChoppingBoardBlockEntity)level.getBlockEntity(at);
        if (board == null) throw new IllegalStateException("board missing: " + at);
        nativeBoards.add(Map.of("input", BuiltInRegistries.ITEM.getKey(board.getCurrentCutStack().getItem()).toString(),
            "cuts", board.getCurrentCutCount(), "maximum", board.getMaxCutCount()));
    }
    var targetCounts = new ArrayList<Long>();
    for (int index = 0; index < targets.size(); index++) {
        var store = colony.store(level, WorldPos.of(level, targets.get(index))).orElseThrow();
        String wanted = index == 0 ? "kaleidoscope_cookery:raw_noodles" : "kaleidoscope_cookery:raw_cut_small_meats";
        long count = 0;
        for (int slot = 0; slot < store.getContainerSize(); slot++) {
            var stack = store.getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(wanted)) count += stack.getCount();
        }
        targetCounts.add(count);
    }
    var service = colony.service(io.github.izakyl.folkways.plugins.cookery.CookeryContent.ID, Object.class).orElseThrow();
    var taskTools = ((CookeryPresence)service).tools();
    var pool = field(taskTools, "pool");
    var queues = (Map<?,?>)field(pool, "queues");
    int queued = queues.values().stream().mapToInt(queue -> ((Collection<?>)queue).size()).sum();
    int loans = ((Map<?,?>)field(pool, "leases")).size();
    var result = new LinkedHashMap<String,Object>();
    result.put("phase", phase); result.put("tools", tools); result.put("boards", nativeBoards);
    result.put("totals", totals); result.put("target", targetCounts); result.put("queued", queued);
    result.put("loans", loans); result.put("residents", residents);
    String fingerprint = result.toString();
    result.put("tick", level.getGameTime());
    if (!fingerprint.equals(previous)) { rows.add(result); previous = fingerprint; }
    return result;
}
`,
    body: `
var args = Args.of(ctx);
String mode = args.string("mode");
if (mode.equals("stop") && colony == null) return Map.of("observerFailure", "observer class was reloaded", "rows", List.of());
if (mode.equals("start")) {
    if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
    var player = ctx.server().getPlayerList().getPlayerByName(args.string("player"));
    colony = Books.acting(player).orElseThrow();
    level = ctx.server().overworld();
    rack = pos(args.value("rack"));
    boards = ((List<?>)args.value("boards")).stream().map(value -> pos(value)).toList();
    targets = ((List<?>)args.value("targets")).stream().map(value -> pos(value)).toList();
    rows.clear(); previous = ""; observerFailure = "";
    listener = event -> { try { sample(); } catch (Throwable broken) { observerFailure = broken.toString(); } };
    NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, listener);
}
phase = args.string("phase");
var current = sample();
var result = Map.of("current", current, "rows", new ArrayList<>(rows), "observerFailure", observerFailure);
rows.clear();
if (mode.equals("stop") && listener != null) { NeoForge.EVENT_BUS.unregister(listener); listener = null; }
return result;
`,
  }),
});

for (const stage of twins()) test(stage.title("shared cookery tools: one named rack knife rotates, explicit maintain two enables parallel work"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId();
  const evidence: Record<string, any> = { stage: stage.kind, samples: [] };
  let pair: E2EPair | undefined;
  let cells: Layout | undefined;
  let playerName = "";
  let observing = false;
  try {
    pair = await launchPair({
      config: "blockwright.toml#" + (stage.kind === "ground" ? "cookery" : "cookery-sable"),
      server: { trace: tracePath("e2e", stage.name("cookery-tools-server-" + runId)), instance: seededServer(stage.name("cookery-tools-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("cookery-tools-client-" + runId)), instance: stage.name("cookery-tools-client-" + runId) },
      snapshot: () => ({ containers: cells ? [cells.supply, cells.noodles, cells.meat].map(at => stage.at(at)) : [],
        entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    playerName = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const box = foundingBox(origin);
    await stage.lay(server, box);
    await world.command(server, "fill " + commandPos(stage.at({ ...box.min, y: origin.y - 1 })) + " "
      + commandPos(stage.at({ ...box.max, y: origin.y - 1 })) + " minecraft:smooth_stone");
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 3, stage });
    evidence.founding = founding.evidence;
    evidence.roles = await cookingRoles(server, client, playerName, founding.standBlock, stage);
    await tick.sprint(server, 40);
    for (const command of ["gamerule doDaylightCycle false", "gamerule doMobSpawning false", "difficulty peaceful", "time set day"]) {
      await world.command(server, command);
    }
    cells = kitchen(origin);
    const block = (at: Vec) => commandPos(stage.at(at));
    for (const at of [cells.supply, cells.noodles, cells.meat]) await world.command(server, "setblock " + block(at) + " minecraft:chest{Items:[]}");
    for (const at of cells.boards) await world.command(server, "setblock " + block(at) + " " + K + "chopping_board");
    await world.command(server, "setblock " + block(cells.table) + " minecraft:crafting_table");
    const knife = { id: KNIFE, count: 1, components: { "minecraft:custom_name": '{"text":"Shared Cookery Knife"}', "minecraft:damage": INITIAL_DAMAGE } };
    await world.command(server, "setblock " + block(cells.rack) + " " + K + "kitchenware_racks[facing=south]{LeftItem:" + JSON.stringify(knife) + "}");
    await tick.sprint(server, 5);
    for (const at of [cells.supply, cells.noodles, cells.meat, cells.table, ...cells.boards]) {
      await optInViaBook(server, client, playerName, at, "worksite", stage);
    }
    evidence.rackMarked = await markSouthRack(server, client, playerName, cells.rack, stage);
    await put(server, stage, cells.supply, 0, "minecraft:iron_ingot", 3);
    await put(server, stage, cells.supply, 1, "minecraft:oak_planks", 1);
    await world.command(server, "gamemode survival " + playerName);
    const read = async (mode: string, phase: string) => {
      const value = await observe(server, stage, cells!, playerName, mode, phase);
      evidence.samples.push(...value.rows);
      expect(value.observerFailure, "the per-tick observer must retain complete physical evidence").toBe("");
      expect(value.current.residents).toBe(3);
      return value.current;
    };
    const initial = await read("start", "one"); observing = true;
    evidence.initial = initial;
    expect(initial.tools).toHaveLength(1);
    const original = initial.tools[0];
    expect(original.where).toBe("rack"); expect(original.name).toBe("Shared Cookery Knife");
    expect(original.damage).toBe(INITIAL_DAMAGE);
    evidence.orders = [
      await deliverOrderViaSitePanel(server, client, playerName, cells.noodles, NOODLES, SERVINGS, stage),
      await deliverOrderViaSitePanel(server, client, playerName, cells.meat, MEAT, SERVINGS, stage),
    ];
    await put(server, stage, cells.supply, 2, DOUGH, BATCHES);
    await put(server, stage, cells.supply, 3, "minecraft:chicken", BATCHES);
    evidence.first = await waitFor(server, read, "one", sample => sample.target.every(count => count === SERVINGS)
      && sample.tools.length === 1 && sample.tools[0].where === "rack" && sample.loans === 0);
    const firstRows = (evidence.samples as Sample[]).filter(sample => sample.phase === "one");
    for (const row of firstRows) {
      expect(row.tools, "busy workers never manufacture another knife").toHaveLength(1);
      expect(row.tools[0].shape, "borrowing preserves every component other than native damage").toBe(original.shape);
      expect(row.totals["minecraft:iron_ingot"] ?? 0, "the automatic pool never spends spare iron while its knife exists").toBe(3);
    }
    expect(firstRows.some(row => row.queued >= 2 && row.loans === 1), "a second ready cooking task actually waits for the busy knife").toBe(true);
    const borrowers = new Set(firstRows.flatMap(row => row.tools.filter(tool => tool.where === "resident").map(tool => tool.owner)));
    expect(borrowers.size, "at least two residents actually borrow the same physical knife").toBeGreaterThanOrEqual(2);
    expect(returnedBetweenBorrowers(firstRows), "one worker returns the knife to the real rack before another worker borrows it").toBe(true);
    assertWear(firstRows, INITIAL_DAMAGE, BATCHES * 2 * 5);
    expect(evidence.first.totals[NOODLES]).toBe(SERVINGS); expect(evidence.first.totals[MEAT]).toBe(SERVINGS);
    expect(evidence.first.totals["minecraft:bone"]).toBe(BATCHES);
    expect(evidence.first.totals[DOUGH] ?? 0).toBe(0); expect(evidence.first.totals["minecraft:chicken"] ?? 0).toBe(0);

    await read("read", "maintain");
    evidence.maintain = await setMaintainDemandViaUI(server, client, playerName, cells.rack, KNIFE, 2, stage, 2);
    evidence.twoIdle = await waitFor(server, read, "maintain", sample => sample.tools.length === 2
      && sample.tools.every(tool => tool.where === "rack") && sample.loans === 0);
    expect(evidence.twoIdle.totals["minecraft:iron_ingot"] ?? 0, "the explicit two-tool target makes exactly one additional iron knife").toBe(0);
    expect(evidence.twoIdle.tools.filter((tool: Tool) => tool.name === original.name)).toHaveLength(1);
    // Supply enough real materials to expose accidental replenishment while both maintained tools are borrowed.
    await put(server, stage, cells.supply, 0, "minecraft:iron_ingot", 6);
    await put(server, stage, cells.supply, 1, "minecraft:oak_planks", 2);
    await read("read", "two");
    // A one-shot delivery raises the target stock to its count; the first 24 dishes are still real stock.
    evidence.parallelOrders = [
      await deliverOrderViaSitePanel(server, client, playerName, cells.noodles, NOODLES, SERVINGS * 2, stage),
      await deliverOrderViaSitePanel(server, client, playerName, cells.meat, MEAT, SERVINGS * 2, stage),
    ];
    await put(server, stage, cells.supply, 2, DOUGH, BATCHES);
    await put(server, stage, cells.supply, 3, "minecraft:chicken", BATCHES);
    evidence.final = await waitFor(server, read, "two", sample => sample.target.every(count => count === SERVINGS * 2)
      && sample.tools.length === 2 && sample.tools.every(tool => tool.where === "rack") && sample.loans === 0);
    const secondRows = (evidence.samples as Sample[]).filter(sample => sample.phase === "two");
    for (const row of secondRows) expect(row.tools, "a promised physical return satisfies the maintained stock without making spare copies").toHaveLength(2);
    expect(secondRows.some(row => new Set(row.tools.filter(tool => tool.where === "resident").map(tool => tool.owner)).size === 2),
      "the explicit extra tool lets two different residents work at the same time").toBe(true);
    expect(secondRows.some(row => row.boards.every(board => board.input !== "minecraft:air")), "both native boards are in use concurrently").toBe(true);
    const named = evidence.final.tools.filter((tool: Tool) => tool.name === original.name);
    expect(named).toHaveLength(1); expect(named[0].shape).toBe(original.shape);
    expect(evidence.final.totals["minecraft:iron_ingot"] ?? 0).toBe(6);
    expect(evidence.final.totals[NOODLES]).toBe(SERVINGS * 2); expect(evidence.final.totals[MEAT]).toBe(SERVINGS * 2);
    expect(evidence.final.totals["minecraft:bone"]).toBe(BATCHES * 2);
    expect(evidence.final.tools.every((tool: Tool) => tool.where === "rack")).toBe(true);
    await screen.dismiss(client);
  } catch (error) {
    pair?.failing(error); evidence.failure = String(error); throw error;
  } finally {
    if (pair && cells && observing) {
      const end = await observe(pair.server, stage, cells, playerName, "stop", "end").catch(error => ({ error: String(error) }));
      evidence.end = end;
      if ("rows" in end) evidence.samples.push(...end.rows);
    }
    try {
      const path = testInfo.outputPath("cookery-tools-evidence.json");
      writeFileSync(path, JSON.stringify(evidence, null, 2));
      await testInfo.attach("cookery-tools-evidence", { path, contentType: "application/json" });
    } finally { await pair?.teardown(testInfo); }
  }
});

const ROLES = defineTask<{ player: string }, { residents: string[]; vocations: string[] }>({
  name: "folkways.cookery-tools.roles",
  side: "server",
  timeoutMs: 20000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.colony.Books", "io.github.izakyl.folkways.core.api.vocation.Vocations"],
    body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony = Books.acting(player).orElseThrow();
return Map.of("residents", colony.residents().stream().map(resident -> resident.id().toString()).toList(),
    "vocations", Vocations.ids().stream().map(Object::toString).toList());
`,
  }),
});

async function cookingRoles(server: MinecraftServer, client: MinecraftClient, player: string, stand: Vec, stage: Stage) {
  const roster = await runColonyTask(server, ROLES, { player });
  expect(roster.residents).toHaveLength(3);
  const changes: unknown[] = [];
  await openColonyPanelViaBook(server, client, player, stand, stage);
  try {
    await clickElement(client, { id: "folkways.tab.residents" });
    for (const resident of roster.residents) for (const vocation of roster.vocations) {
      const enabled = vocation === "folkways:crafting" || vocation === "folkways:hauling";
      const before = await vocationPriority(server, resident, vocation);
      if ((before > 0) === enabled) continue;
      const row = "folkways.resident.row." + resident.slice(0, 8);
      const cell = "folkways.resident.vocation." + vocation.replace(/[:/]/g, ".") + "." + resident.slice(0, 8);
      await scrollCellIntoView(server, client, row, cell);
      await waitForElement(client, { id: cell }, { timeoutMs: 8000 });
      const click = await clickElement(client, { id: cell });
      await tick.sprint(server, 5); await frames(client, 3);
      const after = await vocationPriority(server, resident, vocation);
      expect(after > 0, "the actual Citizens UI enables only crafting and hauling: " + resident + " " + vocation).toBe(enabled);
      changes.push({ resident, vocation, before, after, click });
    }
  } finally { await screen.dismiss(client); }
  return { roster, changes };
}
function kitchen(origin: Vec) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return { supply: at(0, 6), rack: at(0, 9, 1), table: at(0, 12), boards: [at(-8, 9), at(8, 9)],
    noodles: at(-11, 9), meat: at(11, 9) };
}

async function put(server: MinecraftServer, stage: Stage, at: Vec, slot: number, item: string, count: number) {
  const inventory = await world.container(server, stage.at(at), { nbt: true });
  expect(inventory.found).toBe(true);
  const items = (inventory.nbt as { Items?: { Slot: number }[] })?.Items;
  expect(Array.isArray(items)).toBe(true);
  const occupied = new Set(items!.map(stack => stack.Slot));
  const free = [slot, ...Array.from({ length: 27 }, (_, index) => index)].find(candidate => !occupied.has(candidate));
  expect(free, "fixture inputs never overwrite real colony inventory").toBeDefined();
  await world.command(server, "execute unless data block " + commandPos(stage.at(at)) + " Items[{Slot:" + free + "b}] run item replace block "
    + commandPos(stage.at(at)) + " container." + free + " with " + item + " " + count);
}

async function observe(server: MinecraftServer, stage: Stage, cells: Layout, player: string, mode: string, phase: string) {
  return runColonyTask(server, OBSERVE, { mode, player, rack: stage.at(cells.rack), boards: cells.boards.map(at => stage.at(at)),
    targets: [cells.noodles, cells.meat].map(at => stage.at(at)), phase });
}

async function waitFor(server: MinecraftServer, read: (mode: string, phase: string) => Promise<Sample>, phase: string,
  done: (sample: Sample) => boolean) {
  let last: Sample | undefined;
  for (let elapsed = 0; elapsed <= MAX_TICKS; elapsed += 80) {
    last = await read("read", phase);
    if (done(last)) return last;
    await tick.sprint(server, 80);
  }
  throw new Error("cookery-tools phase " + phase + " did not finish in " + MAX_TICKS + " ticks: " + JSON.stringify(last));
}

function returnedBetweenBorrowers(rows: Sample[]) {
  let borrower: string | undefined;
  let returned = false;
  for (const row of rows) {
    const tool = row.tools[0];
    if (tool?.where === "rack" && borrower) returned = true;
    if (tool?.where === "resident") {
      if (borrower && tool.owner !== borrower && returned) return true;
      if (tool.owner !== borrower) returned = false;
      borrower = tool.owner;
    }
  }
  return false;
}

function assertWear(rows: Sample[], initial: number, maximumCalls: number) {
  let damage = initial;
  let previous: Sample | undefined;
  for (const row of rows) {
    const next = row.tools[0].damage;
    expect(next, "native knife damage never decreases").toBeGreaterThanOrEqual(damage);
    expect(next - damage, "there is no duplicated core wear after one native board interaction").toBeLessThanOrEqual(1);
    if (next > damage && previous) expect(row.boards.some((board, index) => board.cuts !== previous!.boards[index].cuts
      || board.input !== previous!.boards[index].input), "damage changes only alongside a real native board interaction").toBe(true);
    damage = next; previous = row;
  }
  // Native Cookery wears on a random 25% of successful cuts/take-outs. Zero wear is legal;
  // every observed increment must be native, and even the all-successes upper bound is finite.
  expect(damage - initial).toBeLessThanOrEqual(maximumCalls);
}

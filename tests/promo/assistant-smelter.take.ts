import { expect, test } from "@playwright/test";
import { defineTask, javaTask, world } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import {
  buildSmelter, ORDER, placeSmeltingOrder, SMELTER_STATE, smelterLayout,
} from "./assistant-smelter-stage";
import {
  askAboutBox, beside, camera, castColony, closeChat, closeStage, converse, LABOR_DIAGNOSIS, leaveTactical, openStage,
  openTacticalAt, press, readyBook, selectFacility, shot, type BoxDraw, type Stage,
} from "./assistant-kit";
import { sleepMs, take } from "./promo-kit";

// Segment 3: a Create fan blowing through lava onto a depot, its drive held by a lever on a clutch. The player draws a
// box with the colony book from the lever to the depot, hands it to the assistant and asks for a smelter. It reads the
// mechanism (the fan, the lava in its stream and the depot), tries the lever on a copy and sees the stream turn hot,
// then defines a workshop for smelting and the player approves it. Then the player orders a stack of iron ingots in the
// colony's Tom's storage terminal and opens the fan's card from above: a resident pulls the lever, carries the raw iron
// to the depot, the ore cooks in the stream, and the ingots go back to the store, the card on screen throughout.

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/assistant/src/main/resources/assets/folkways_assistant/lang/zh_cn.json", "utf8"));
const QUESTION = "鼓风机吹熔岩 能把置物台上的矿烧成锭 拉杆控制离合器 做成工坊吧 仓库缺锭的时候让居民把矿放上去 烧好了再收回仓库";

test("assistant: calibrate a fan smelter and smelt an order through a resident", async ({}, testInfo) => {
  const run = take("assistant-smelter");
  let stage: Stage | undefined;
  try {
    stage = await openStage("smelter", run);
    const s = stage;
    const { server, player } = s;
    const o = s.at(0, 0, 20);
    const smelter = smelterLayout(o);
    run.note("smelter", await buildSmelter(server, o));
    const cast = await castColony(s, [...smelter.beds, smelter.outbox, smelter.terminal], 2, [s.at(-7, 0, 23), s.at(-5, 0, 24)]);
    run.note("cast", cast);
    // Until the order, nobody takes up workshop work: the residents' own wants (tools) would smelt the ore first.
    run.note("trades", await server.run(TRADES, { colony: cast.colony_id, on: false }));
    s.provider.values.COLONY = cast.colony_id;

    // The player stands north-east of the machine, the book in hand: the box runs from the lever on the clutch to the
    // depot, taking in the clutch, the fan and the lava.
    const draw: BoxDraw = {
      stand: { x: o.x + 3.5, y: o.y + 1.4, z: o.z - 3.5 },
      first: smelter.lever, firstAim: { x: smelter.lever.x + 0.5, y: smelter.lever.y + 0.12, z: smelter.lever.z + 0.5 },
      second: smelter.depot, secondAim: { x: smelter.depot.x + 0.5, y: smelter.depot.y + 0.4, z: smelter.depot.z + 0.02 },
    };
    run.note("book", await readyBook(s, draw));
    // The camera films the machine side on, the stream running left to right beside the chat window.
    const chatEye = { x: o.x - 3.5, y: o.y + 4.5, z: o.z - 10.5 };
    const chatLook = beside(chatEye, { x: o.x + 0.5, y: o.y + 0.5, z: o.z + 0.5 }, 0.78, 62);
    await camera(s, { eye: [chatEye], look: [chatLook], fov: 62 });
    await sleepMs(1500);

    const reviews: string[] = [];
    // FOLKWAYS_PROMO_SMELTER_PREDEFINE=1 skips the conversation and defines the guide's fan smelter directly, to work
    // on the second clip (or a new ordering step) without asking the model.
    const asked = process.env.FOLKWAYS_PROMO_SMELTER_PREDEFINE === "1"
      ? (run.note("predefined", await server.run(PREDEFINE, { ...o, definition: JSON.stringify(guideSmelter(o)) })), {})
      : await shot(s, "01-ask", "The player draws a box with the colony book from the clutch lever to the depot and "
      + "hands it to the assistant, asking for a smelter; it reads the mechanism (the fan, the lava in its stream, the "
      + "depot), tries the clutch lever on a copy (shown over the machine), then puts the smelting workshop up for "
      + "review and the player approves it", async mark => {
        await askAboutBox(s, draw, QUESTION, mark);
        await converse(s, async review => {
          reviews.push(review.summary);
          mark(`review-${reviews.length}`);
          await sleepMs(2500);
          await press(s, "folkways.assistant.confirm");
          mark(`approved-${reviews.length}`);
          // Checking the workshop with a trial smelt waits on a resident walking the ore over, so this segment's
          // conversation runs longer than the others.
        }, { idle: ZH["folkways.assistant.ready"], mark, timeoutMs: 720_000 });
        mark("answered");
        await sleepMs(3000);
      });
    run.note("reviews", reviews);
    if (process.env.FOLKWAYS_PROMO_SMELTER_PREDEFINE !== "1") {
      run.note("recording", s.provider.save({ question: QUESTION, reviews }) ?? "replayed");
    }
    const workshops = await server.run(WORKSHOPS, { colony: cast.colony_id });
    run.note("workshops", workshops);
    expect(workshops.defined.length, "the assistant must define the fan as a facility").toBeGreaterThan(0);
    await closeChat(s);

    // At the store's terminal the player orders a stack of ingots and from above the fan's card is opened, all over one
    // still picture of the yard from the south-west: the fan, the lava and the depot left of the middle, the stream
    // blowing away to the right, the store and its terminal at the right, the path between them that the residents
    // walk with the ore and the ingots.
    const yardEye = { x: o.x - 5, y: o.y + 8, z: o.z + 13 };
    const yardLook = { x: o.x + 3.5, y: o.y + 0.5, z: o.z + 4.5 };
    await camera(s, { eye: [yardEye], look: [yardLook], fov: 60 });
    // Empty-handed until the order: the book would put the card of whatever the player faces over the picture.
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    await sleepMs(1000);
    const samples: any[] = [];
    const seen = { carrying: false, loaded: false, smelted: false, collected: false, delivered: false, batches: 0 };
    const workLog: any[] = [];
    const smelted = await shot(s, "02-smelt", "The player orders a stack of iron ingots at the colony's Tom's storage "
      + "terminal and opens the fan's card from above; a resident pulls the clutch lever, carries the raw iron to the "
      + "depot in front of the fan, the ore cooks in the hot stream from the lava, batch after batch, and the residents "
      + "take the ingots back into the store, the camera holding still on the yard and the card on screen throughout",
      async mark => {
        let latest: Awaited<ReturnType<typeof readState>> | undefined;
        let watching = true;
        // A failure in the shot closes the game under the watcher: its own error is kept, not thrown over the shot's.
        let watchFailed: unknown;
        let cooking = false;
        const readState = () => server.run(SMELTER_STATE, o);
        const watch = (async () => {
          const started = Date.now();
          while (watching && Date.now() - started < 480_000) {
            const now = await readState();
            latest = now;
            samples.push({ t: (Date.now() - started) / 1000, ...now });
            const raw = now.residents.find(one => one.pack.some(item => item.startsWith("minecraft:raw_iron")));
            const ingots = now.residents.find(one => one.pack.some(item => item.startsWith("minecraft:iron_ingot")));
            if (raw && !seen.loaded && !seen.carrying) { seen.carrying = true; mark("carrying"); }
            const onDepot = now.depot.some(item => item.startsWith("minecraft:raw_iron"));
            if (onDepot && !cooking) { cooking = true; seen.batches++; mark(`loaded-${seen.batches}`); }
            if (!seen.loaded && onDepot) { seen.loaded = true; mark("loaded"); }
            if (cooking && !onDepot && now.depot.some(item => item.startsWith("minecraft:iron_ingot"))) {
              cooking = false;
              mark(`smelted-${seen.batches}`);
              if (!seen.smelted) { seen.smelted = true; mark("smelted"); }
            }
            if (seen.smelted && ingots && !seen.collected) { seen.collected = true; mark("collected"); }
            if (now.ingots >= ORDER) { seen.delivered = true; mark("delivered"); break; }
            if ((!seen.carrying || seen.collected) && samples.length % 16 === 0) {
              workLog.push({ t: (Date.now() - started) / 1000, ...await server.run(WORK_DEBUG, { colony: cast.colony_id }) });
            }
            await sleepMs(250);
          }
        })().catch(error => { watchFailed = error; });
        await sleepMs(1500);
        await placeSmeltingOrder(s, { terminal: smelter.terminal, count: ORDER, itemLabel: "铁锭",
          beforeConfirm: async () => { mark("filled"); await sleepMs(2500); } });
        mark("ordered");
        await server.run(TRADES, { colony: cast.colony_id, on: true });
        await sleepMs(1500);
        await closeChat(s);
        // The view from above opens on the very same picture, and the fan's card is opened in it.
        await openTacticalAt(s, yardEye, yardLook);
        mark("tactical");
        await sleepMs(800);
        run.note("select-attempts", await selectFacility(s, smelter.fan));
        mark("card");
        await sleepMs(2500);
        const until = async (done: () => boolean, ms: number) => {
          const deadline = Date.now() + ms;
          while (!done() && Date.now() < deadline) await sleepMs(200);
          return done();
        };
        mark("wait-1");
        if (await until(() => seen.carrying || seen.loaded, 120_000) && !seen.loaded) {
          mark("resume-1");
          mark("wait-2");
          await until(() => seen.loaded, 120_000);
          mark("resume-2");
        } else mark("resume-1");
        // The first batch cooks at speed; the later ones are waited out faster.
        await until(() => seen.smelted, 120_000);
        await sleepMs(2500);
        mark("wait-3");
        if (await until(() => seen.collected, 240_000)) {
          await sleepMs(800);
          mark("resume-3");
          await sleepMs(4000);
          mark("wait-4");
        } else mark("resume-3");
        await until(() => seen.delivered, 300_000);
        mark("resume-4");
        watching = false;
        await watch;
        if (watchFailed) throw watchFailed;
        run.note("latest", latest);
        await sleepMs(3500);
        // The film's cut ends a little after this mark.
        mark("cut");
        await sleepMs(2000);
      });
    await leaveTactical(s);
    run.note("smelt-samples", samples.filter((_, i) => i % 4 === 0));
    run.note("seen", seen);
    run.note("work", workLog);
    if (!seen.delivered) run.note("labor", await server.run(LABOR_DIAGNOSIS, { colony: cast.colony_id }));
    const last = await server.run(SMELTER_STATE, o);
    run.note("after", last);
    expect(seen.carrying, "a resident must carry the raw iron to the fan").toBe(true);
    expect(seen.loaded && seen.smelted, "the ore must cook on the depot in the stream").toBe(true);
    expect(last.ingots, "the ingots must end in the store").toBeGreaterThanOrEqual(ORDER);
    expect(last.depot.length + last.ground, "nothing is left on the depot or the ground").toBe(0);
    run.note("marks", { asked, smelted });
  } catch (error) {
    stage?.pair.failing(error);
    throw error;
  } finally {
    await stage?.pair.teardown(testInfo);
    await closeStage(stage);
  }
});

// Whether residents work the colony's workshops and haul for them, as a player sets it on Workforce.
const TRADES = defineTask<{ colony: string; on: boolean }, any>({
  name: "folkways.promo.assistant.smelter-trades", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.colony.api.WorkDefaults",
      "io.github.izakyl.folkways.core.vocation.api.Vocations", "io.github.izakyl.folkways.vanilla.person.PersonBody",
      "io.github.izakyl.folkways.vanilla.wares.WaresContent"],
    body: `
var a = Args.of(ctx);
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
boolean on = Boolean.TRUE.equals(a.value("on"));
WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, on, 1);
WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, on, 1);
return Map.of("ok", true);`,
  }),
});

/** The colony's defined facilities, and whether any workshop now offers iron ingots from raw iron. */
const WORKSHOPS = defineTask<{ colony: string }, { defined: string[]; smelting: boolean; recipes: string[] }>({
  name: "folkways.promo.assistant.smelter-workshops", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
      "io.github.izakyl.folkways.core.mechanics.api.Mechanics"],
    body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var recipes = new ArrayList<String>();
for (var entry : Mechanics.workshops(colony, ctx.server())) {
    entry.facility().ifPresent(facility -> facility.explicitRecipes().forEach(recipe -> recipes.add(String.valueOf(recipe))));
}
boolean smelting = recipes.stream().anyMatch(recipe -> recipe.contains("iron_ingot") && recipe.contains("raw_iron"));
return Map.of("defined", new ArrayList<>(DefinedFacilities.of(colony).orElseThrow().list()), "smelting", smelting,
    "recipes", recipes);`,
  }),
});

/** The guide's fan smelter over this stage, as the assistant would write it. */
function guideSmelter(o: { x: number; y: number; z: number }) {
  const smelt = [
    "def recipes(input):",
    "    if input.get(\"heat\") != \"create:blasting\" and input.get(\"clutch\") != True:",
    "        return []",
    "    return [{\"id\": \"smelter:raw_iron\", \"scale\": 16, \"ticks\": 200,",
    "             \"inputs\": [{\"item\": \"minecraft:raw_iron\", \"count\": 1}],",
    "             \"outputs\": [{\"item\": \"minecraft:iron_ingot\", \"count\": 1}]}]",
    "",
    "def recover(state, failure, input, args):",
    "    return \"stopped\", [collect(\"depot\"), write(\"clutch\", True)]",
    "",
    "def step(state, input, args):",
    "    if input[\"_failure\"] != None:",
    "        return state, [fail(input[\"_failure\"])]",
    "    if state == None:",
    "        clear = [collect(\"depot\")] if input.get(\"depot.count\", 0) > 0 else []",
    "        return \"started\", clear + [write(\"clutch\", False), await(\"heat\", \"create:blasting\", 200, on_fail=\"recover\")]",
    "    if state == \"started\":",
    "        return \"loaded\", [deliver(\"depot\", args[\"recipe\"][\"inputs\"]), await(\"raw\", 0, 600, on_fail=\"recover\")]",
    "    if state == \"loaded\":",
    "        return \"collected\", [collect(\"depot\", args[\"recipe\"][\"outputs\"], on_fail=\"recover\"), write(\"clutch\", True)]",
    "    if state == \"stopped\":",
    "        return state, [fail(\"The fan's stream stopped before the items were done\")]",
    "    return state, [done]",
  ].join("\n");
  return {
    name: "烧炼鼓风机",
    extent: { elements: [{ space: { dimension: "minecraft:overworld" },
      min: [o.x - 2, o.y, o.z - 1], max: [o.x + 3, o.y + 1, o.z + 1] }] },
    anchor: [o.x, o.y, o.z],
    controls: { depot: { offset: [2, 0, 0], part: "folkways_core:item/up" }, clutch: { offset: [-1, 1, 0], part: "lever" } },
    readings: {
      heat: { probe: "folkways_core:create/air_processing", offset: [2, 0, 0] },
      raw: { probe: "folkways_core:create/air_unprocessed", offset: [2, 0, 0], type: "create:blasting" },
    },
    routines: { smelt: { source: smelt } },
    workshop: { routine: "smelt" },
  };
}

const PREDEFINE = defineTask<{ x: number; y: number; z: number; definition: string }, any>({
  name: "folkways.promo.assistant.smelter-predefine", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround", "net.minecraft.core.BlockPos", "com.google.gson.GsonBuilder", "com.google.gson.ToNumberPolicy"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var colony = ColonyGround.ofBlock(level, o.offset(7, 0, 10)).orElseThrow();
@SuppressWarnings("unchecked")
Map<String, Object> data = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.BIG_DECIMAL).create()
    .fromJson(a.string("definition"), Map.class);
var facilities = DefinedFacilities.of(colony).orElseThrow();
var name = String.valueOf(data.get("name"));
var preview = facilities.preview(name, Optional.of(data));
if (!preview.problems().isEmpty()) throw new IllegalStateException("smelter refused: " + preview.problems());
var applied = facilities.apply(preview);
return Map.of("name", name, "applied", String.valueOf(applied));`,
  }),
});

/** Each resident's debug lines and route, and the colony's open orders. */
const WORK_DEBUG = defineTask<{ colony: string }, any>({
  name: "folkways.promo.assistant.smelter-work", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.runtime.Labor", "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.vanilla.orders.Orders", "net.minecraft.world.level.Level"],
    body: `
var id = UUID.fromString(Args.of(ctx).string("colony"));
var level = ctx.server().overworld();
var out = new LinkedHashMap<String, Object>();
var colony = Colonies.of(ctx.server(), id).orElseThrow();
out.put("orders", Orders.targets(colony).stream().map(String::valueOf).toList());
Labor.site(id, Level.OVERWORLD).ifPresent(labor -> {
    for (var resident : colony.residents()) {
        var debug = labor.debugResident(level, resident.id());
        out.put(resident.id().toString().substring(0, 4), Map.of(
            "lines", debug.lines().stream().map(String::valueOf).toList(),
            "route", debug.route().stream().map(String::valueOf).toList()));
    }
});
return out;`,
  }),
});

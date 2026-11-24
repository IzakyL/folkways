import { expect, test } from "@playwright/test";
import { defineTask, javaTask, world } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { buildGate, gateLayout } from "./assistant-gate";
import {
  askAboutBox, beside, camera, castColony, closeChat, closeStage, converse, glidePointer, leaveTactical, openStage,
  openTacticalAt, press, readyBook, selectFacility, shot, type BoxDraw, type Stage,
} from "./assistant-kit";
import { sleepMs, take } from "./promo-kit";

// Segment 2: a drawbridge over a moat, its machine in the open: a Create bearing swings the bridge, driven through
// gearboxes by a valve handle beside the road. The player draws a box with the colony book from the handle to the foot
// of the bridge, hands it to the assistant and asks for the drawbridge as a facility. It reads the mechanism, tries the
// handle both ways on a copy (the trials play back over the real bridge), writes the facility with Open and Close, and
// the player approves. From above the player opens the handle's card and presses Open: a resident turns the handle and
// the bridge comes down across the moat; then Close is pressed and the bridge rises again, one still picture of the
// whole machine with the card on screen throughout.

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/assistant/src/main/resources/assets/folkways_assistant/lang/zh_cn.json", "utf8"));
const QUESTION = "这是个吊桥 转手轮就能放下来 帮我做成设施 加开门和关门两个按钮 让居民去转";

test("assistant: calibrate a valve-driven drawbridge and lower it through a resident", async ({}, testInfo) => {
  const run = take("assistant-door");
  let stage: Stage | undefined;
  try {
    stage = await openStage("door", run);
    const s = stage;
    const { server, player } = s;
    const o = s.at(0, 0, 20);
    const gate = gateLayout(o);
    run.note("gate", await buildGate(server, o));
    // The colony's camp lies on the road's side of the moat, west of the bridge: beds and a pantry.
    const camp = [s.at(-10, 0, 28), s.at(-8, 0, 28)];
    for (const bed of camp) {
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z} minecraft:white_bed[part=foot,facing=north]`);
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z - 1} minecraft:white_bed[part=head,facing=north]`);
    }
    const pantry = s.at(-12, 0, 28);
    await world.command(server, `setblock ${pantry.x} ${pantry.y} ${pantry.z} minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:bread",count:64}]}`);
    const cast = await castColony(s, [...camp, pantry], 2, [s.at(-11, 1, 26), s.at(-13, 1, 24)]);
    run.note("cast", cast);
    s.provider.values.COLONY = cast.colony_id;

    // The player stands on the road before the bridge, the book in hand: the box runs from the raised bridge to the
    // ground under the handle, taking in the gearboxes, the shaft and the bearing.
    const draw: BoxDraw = {
      stand: { x: o.x - 7, y: o.y + 2.2, z: o.z + 0.5 },
      first: { x: o.x + 1, y: o.y + 1, z: o.z + 1 }, firstAim: { x: o.x + 1.02, y: o.y + 1.5, z: o.z + 1.5 },
      second: { x: o.x - 3, y: o.y - 1, z: o.z - 1 }, secondAim: { x: o.x - 2.5, y: o.y - 0.05, z: o.z - 0.5 },
    };
    run.note("book", await readyBook(s, draw));
    // The camera stands high south-west of the bridge: the handle, the gearboxes and the raised bridge right of the
    // chat window, which takes the middle of the picture.
    const chatEye = { x: o.x - 12, y: o.y + 8, z: o.z + 7 };
    const chatLook = beside(chatEye, { x: o.x + 0.5, y: o.y + 1.5, z: o.z + 0.5 }, 0.8, 62);
    await camera(s, { eye: [chatEye], look: [chatLook], fov: 62 });
    await sleepMs(1500);

    const reviews: string[] = [];
    const asked = await shot(s, "01-ask", "The player draws a box with the colony book from the raised bridge to the ground under "
      + "the valve handle and hands it to the assistant, asking for the drawbridge as a facility; it reads the "
      + "mechanism and tries the handle on a copy (shown over the bridge), then puts the facility up for review and the "
      + "player approves it", async mark => {
        await askAboutBox(s, draw, QUESTION, mark);
        await converse(s, async review => {
          reviews.push(review.summary);
          mark(`review-${reviews.length}`);
          await sleepMs(2500);
          await press(s, "folkways.assistant.confirm");
          mark(`approved-${reviews.length}`);
        }, { idle: ZH["folkways.assistant.ready"], mark });
        mark("answered");
        await sleepMs(3000);
      });
    run.note("reviews", reviews);
    run.note("recording", s.provider.save({ question: QUESTION, reviews }) ?? "replayed");
    const facilities = await server.run(FACILITIES, { colony: cast.colony_id });
    run.note("facilities", facilities);
    expect(facilities.length, "the assistant must define the drawbridge as a facility").toBeGreaterThan(0);
    const closed = await server.run(GATE_STATE, o);
    run.note("closed", closed);
    expect(closed.raised && !closed.lowered, "the bridge stands raised until a resident lowers it").toBe(true);
    await closeChat(s);

    // One still picture from above the north-west corner, the card over it: the handle with its gearboxes and shaft in
    // front, the bearing and the raised bridge behind them, the moat and the far bank beyond, and the road back to the
    // camp at the right where the resident comes from.
    await openTacticalAt(s, { x: o.x - 4, y: o.y + 6, z: o.z - 10 }, { x: o.x + 1.5, y: o.y + 1.5, z: o.z + 1.5 });
    const samples: any[] = [];
    const until = async (done: (now: any) => boolean, label: string, mark: (label: string) => void, ms = 60_000) => {
      const started = Date.now();
      let turned = false;
      while (Date.now() - started < ms) {
        const now = await server.run(GATE_STATE, o);
        samples.push({ t: (Date.now() - started) / 1000, phase: label, ...now });
        if (now.turning && !turned) {
          turned = true;
          mark(`turning-${label}`);
        }
        if (done(now)) return true;
        await sleepMs(250);
      }
      return false;
    };
    const opened = await shot(s, "02-open", "From above the player clicks the valve handle; its card shows Open and Close. "
      + "Open is pressed, a resident walks up and turns the handle, the gearboxes and shaft turn the bearing and the "
      + "drawbridge swings down across the moat; Close is pressed and the bridge rises again, the camera holding still "
      + "on the whole machine and the card on screen throughout", async mark => {
        await sleepMs(2000);
        run.note("select-attempts", await selectFacility(s, gate.valve));
        mark("selected");
        await sleepMs(1500);
        await press(s, await findButton(s, /open|lower|down|开门|^开|放/));
        mark("open");
        await sleepMs(900);
        await glidePointer(s.client, { x: 960, y: 1060 }, 400);
        await sleepMs(3500);
        mark("wait-1");
        // A resident near the handle may have lowered the bridge already.
        await until(now => now.turning || now.running || now.lowered, "open", mark);
        mark("resume-1");
        if (!await until(now => now.lowered && !now.running, "open", mark)) throw new Error("the bridge never came down");
        mark("lowered");
        await sleepMs(1200);
        // The bridge lies across the moat a while before it is closed again.
        await sleepMs(7200);
        mark("crossed");
        await sleepMs(800);
        await press(s, await findButton(s, /close|raise|lift|shut|关门|^关|收/));
        mark("close");
        await glidePointer(s.client, { x: 960, y: 1060 }, 400);
        mark("wait-2");
        await until(now => now.turning || now.running, "close", mark);
        mark("resume-2");
        if (!await until(now => now.raised && !now.running, "close", mark)) throw new Error("the bridge never rose again");
        mark("raised");
        await sleepMs(3000);
        // The film's cut ends a little after this mark.
        mark("cut");
        await sleepMs(2000);
      });
    run.note("open-samples", samples);
    await leaveTactical(s);
    const turning = samples.filter(one => one.turning);
    expect(turning.length, "a resident must turn the valve handle").toBeGreaterThan(0);
    // A resident works a handle from where a player could reach it: 4.5 blocks from the eye.
    expect(Math.min(...turning.map(one => one.operator)), "the handle is turned by a resident within reach of it")
      .toBeLessThan(5.5);
    expect(samples.some(one => one.lowered), "the drawbridge must lie across the moat").toBe(true);
    expect(samples.at(-1)?.raised, "the drawbridge must rise again on Close").toBe(true);
    run.note("marks", { asked, opened });
  } catch (error) {
    stage?.pair.failing(error);
    throw error;
  } finally {
    await stage?.pair.teardown(testInfo);
    await closeStage(stage);
  }
});

/** A card button found by what it says or is named: the assistant names the routines, in a word or two. */
async function findButton(s: Stage, wanted: RegExp) {
  const { panelElements } = await import("../ldlib2");
  const buttons = (await panelElements(s.client)).filter(one => one.id?.startsWith("folkways.card.button."));
  s.run.note("card-buttons", buttons.map(one => [one.id, one.text]));
  const found = buttons.find(one => wanted.test(one.id!.slice("folkways.card.button.".length)) || wanted.test(one.text ?? ""));
  if (!found) throw new Error(`the card offers no ${wanted}: ${JSON.stringify(buttons.map(one => [one.id, one.text]))}`);
  return found.id!;
}

const FACILITIES = defineTask<{ colony: string }, string[]>({
  name: "folkways.promo.assistant.facilities", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.facility.api.DefinedFacilities"],
    body: `
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
return new ArrayList<>(DefinedFacilities.of(colony).orElseThrow().list());`,
  }),
});

/** Whether the deck stands upright or lies across the moat, the bearing's turn, and who is at the handle. */
const GATE_STATE = defineTask<{ x: number; y: number; z: number }, {
  raised: boolean; lowered: boolean; running: boolean; angle: number; turning: boolean; operator: number;
}>({
  name: "folkways.promo.assistant.drawbridge-state", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.entity.Entity", "net.minecraft.world.level.block.Blocks", "net.minecraft.world.phys.AABB",
      "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity",
      "com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity"],
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var o = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
boolean raised = true, lowered = true;
for (int z = 1; z <= 2; z++) {
    for (int k = 1; k <= 4; k++) {
        raised &= !level.getBlockState(o.offset(1, k, z)).isAir();
        lowered &= !level.getBlockState(o.offset(1 + k, 0, z)).isAir();
    }
}
var bearing = level.getBlockEntity(o.offset(1, 0, 0)) instanceof MechanicalBearingBlockEntity b ? b : null;
var valvePos = o.offset(-3, 1, -1);
var valve = level.getBlockEntity(valvePos) instanceof ValveHandleBlockEntity v ? v : null;
var centre = valvePos.getCenter();
double operator = level.getEntitiesOfClass(Entity.class, new AABB(valvePos).inflate(8)).stream()
    .filter(e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("folkways_vanilla:resident"))
    .mapToDouble(e -> e.position().distanceTo(centre)).min().orElse(99);
return Map.of("raised", raised, "lowered", lowered, "running", bearing != null && bearing.isRunning(),
    "angle", bearing == null ? 0.0 : (double) bearing.getInterpolatedAngle(0), "turning", valve != null && valve.inUse > 0,
    "operator", operator);`,
  }),
});

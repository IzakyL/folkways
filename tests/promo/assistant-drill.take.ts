import { expect, test } from "@playwright/test";
import { input, tick, world } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { buildDrillWalker, clearDrillGround, DIG_BOX, DIG_STATE, findDrillWalker, PROJECT_POINT, raiseRidge } from "./assistant-drill-stage";
import {
  askAboutBox, beside, camera, castColony, closeChat, closeStage, converse, glidePointer, LABOR_DIAGNOSIS, leaveTactical,
  openStage, openTacticalAt, pointAtShipCell, press, readyBook, selectFacility, SHIP_AIM, shot, type BoxDraw, type Stage,
} from "./assistant-kit";
import { sleepMs, take } from "./promo-kit";
import { panelElements } from "../ldlib2";

// Segment 5: Let it Walk!'s spider with an arm the player built at its nose, three drills hanging from its elbow, before
// a dirt ridge. The player draws a box with the colony book over the drill head, hands it to the assistant and asks for
// a facility that digs a boxed area and nothing outside it; the assistant reads the hull, tries the arm at a few aims to
// see where its end goes, and writes the facility with a dig command that takes a box. In RTS the player selects the
// spider, presses dig on its card and picks a five-wide, four-high stretch of the ridge. A resident at the pilot
// controls sweeps the drills, walks forward and sideways to reach fresh ground, and sweeps again. The last two rows
// of the ridge stay standing. The question names only the arm:
// the assistant calibrates the spider's walk as well, as it does for every machine it makes a facility of.

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/assistant/src/main/resources/assets/folkways_assistant/lang/zh_cn.json", "utf8"));
const QUESTION = "前面机械臂上有三个钻头 做成设施 RTS 里点挖掘 框一块就挖掉";
const { low: LOW, high: HIGH } = DIG_BOX;

test("assistant: calibrate a player-built drill arm and dig a picked box", async ({}, testInfo) => {
  const run = take("assistant-drill");
  let stage: Stage | undefined;
  try {
    stage = await openStage("drill", run);
    const s = stage;
    const { server } = s;
    const pad = s.at(0, 0, 120);
    await clearDrillGround(server, pad);
    run.note("build", await buildDrillWalker(server, pad));
    await tick.freeze(server, false);
    await sleepMs(6000);
    await tick.freeze(server, true);
    const walker = await findDrillWalker(server, pad);
    await raiseRidge(server, pad, true);
    run.note("walker", walker);
    s.provider.values.HULL = walker.hull;
    s.provider.values.HEAD = walker.head;
    const beds = [s.at(12, 0, 112), s.at(14, 0, 112)];
    for (const bed of beds) {
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z} minecraft:white_bed[part=foot,facing=north]`);
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z - 1} minecraft:white_bed[part=head,facing=north]`);
    }
    const cast = await castColony(s, [...beds, walker.pantry], 2, walker.deck);
    run.note("cast", cast);
    s.provider.values.COLONY = cast.colony_id;

    // The player hovers before the spider's nose over the ridge, the book in hand: the box runs along the drill head,
    // from the drill at one end to the drill at the other.
    const c = walker.controls;
    const ends = [...walker.drillStores].sort((a, b) => a.x - b.x);
    if (ends.length !== 3) {
      throw new Error(`expected three drills, found ${JSON.stringify(ends)}`);
    }
    const mid = await server.run(SHIP_AIM, ends[1]);
    const draw: BoxDraw = {
      stand: { x: mid.x, y: mid.y - 1.62, z: mid.z + 4.5 },
      first: ends[0], firstAim: await server.run(SHIP_AIM, ends[0]), second: ends[2],
      // The drill head spans three local X cells beneath the elbow.
      shipSize: [3, 1, 1],
    };
    run.note("book", await readyBook(s, draw));
    const chatEye = { x: c.x + 19, y: c.y + 6, z: c.z + 5 };
    const chatLook = beside(chatEye, { x: c.x + 1, y: c.y - 3, z: c.z + 7 }, 0.74, 62);
    await camera(s, { eye: [chatEye], look: [chatLook], fov: 62 });
    await sleepMs(1500);

    const reviews: string[] = [];
    const asked = await shot(s, "01-ask", "The player draws a box with the colony book over the drill head and hands it to "
      + "the assistant, asking for the drill arm as a facility; the assistant reads the hull, tries the arm at a few aims "
      + "to see where its end goes, and puts a facility with a box-picking dig command up for review", async mark => {
      await askAboutBox(s, draw, QUESTION, mark, async () => {
        // The drill head is its own Sable sublevel. Point at the pilot controls so the assistant can also find the hull.
        run.note("aim-at-controls", await pointAtShipCell(s, walker.controlsStore, { x: 2, y: 1, z: -2 }));
      });
      await converse(s, async review => {
        reviews.push(review.summary);
        mark(`review-${reviews.length}`);
        await sleepMs(2500);
        await press(s, "folkways.assistant.confirm");
        mark(`approved-${reviews.length}`);
      }, { idle: ZH["folkways.assistant.ready"], mark, timeoutMs: 1_080_000 });
      mark("answered");
      await sleepMs(3000);
    });
    run.note("reviews", reviews);
    expect(reviews.length, "the assistant must offer a facility for review").toBeGreaterThan(0);
    run.note("recording", s.provider.save({ question: QUESTION, reviews }) ?? "replayed");
    await closeChat(s);
    await raiseRidge(server, pad);

    const rel = (v: { x: number; y: number; z: number }) => ({ x: pad.x + v.x, y: pad.y + v.y, z: pad.z + v.z });
    const near = rel(LOW);
    const far = rel(HIGH);
    const state = () => server.run(DIG_STATE, {
      pad, low: LOW, high: HIGH, coreStore: walker.coreStore, colony: cast.colony_id,
    });
    await openTacticalAt(s, rel({ x: 22, y: 17, z: 5 }), rel({ x: 1, y: 2, z: 14 }));
    const before = await state();
    run.note("before", before);
    const samples: any[] = [];
    const dug = await shot(s, "02-dig", "In RTS the player selects the spider, presses dig on its card and clicks two corners "
      + "along the ridge; a resident sweeps the drills through the box, walking the spider on between sweeps, and the "
      + "uncut end of the ridge stays standing",
    async mark => {
      await sleepMs(1000);
      // Where the controls stand now: the conversation may have parked or moved the walker. The cell holding their
      // centre, since rounding the hull's half-block centre lands a cell to the side.
      const now = (await findDrillWalker(server, pad)).controls;
      run.note("controls-at-select", now);
      await selectFacility(s, { x: Math.floor(now.x), y: Math.floor(now.y), z: Math.floor(now.z) });
      mark("selected");
      await sleepMs(1200);
      const targets = (await panelElements(s.client)).filter(one => one.id?.startsWith("folkways.card.target."));
      run.note("targets", targets.map(one => one.id));
      const target = targets.find(one => /dig|挖|钻|box|area/i.test(one.id!)) ?? targets[0];
      if (!target) throw new Error(`no dig command on the card: ${targets.map(one => one.id)}`);
      await press(s, target.id!);
      mark("target");
      await sleepMs(900);
      // The near corner by the middle of its face toward the spider, the far one by the middle of its top.
      const first = await s.client.run(PROJECT_POINT, { x: near.x + 0.5, y: near.y + 0.5, z: near.z + 0.02 });
      await glidePointer(s.client, first, 800);
      await sleepMs(200);
      await input.click(s.client, first);
      mark("corner");
      const second = await s.client.run(PROJECT_POINT, { x: far.x + 0.5, y: far.y + 0.98, z: far.z + 0.5 });
      await glidePointer(s.client, second, 1100);
      await sleepMs(200);
      await input.click(s.client, second);
      mark("picked");
      await glidePointer(s.client, { x: 960, y: 1060 }, 400);
      const started = Date.now();
      let last = -1;
      let still = Date.now();
      while (Date.now() - started < 1_200_000) {
        const now = await state();
        samples.push({ t: (Date.now() - started) / 1000, ...now, stray: undefined });
        if (now.insideDug > before.insideDug) {
          mark("first-block");
        }
        if (now.insideDug !== last) {
          last = now.insideDug;
          still = Date.now();
        }
        // A routine can end with cells still standing. Keep the stall timeout for runs that never report an ending.
        if (now.insideStanding === 0 || now.result || (last > 0 && Date.now() - still > 90_000)) {
          run.note("labor-at-stop", await server.run(LABOR_DIAGNOSIS, { colony: cast.colony_id }));
          break;
        }
        if (samples.length % 60 === 0) {
          run.note("dig-progress", { ...now, dug: undefined, stray: undefined });
          run.note(`labor-${samples.length}`, await server.run(LABOR_DIAGNOSIS, { colony: cast.colony_id }));
        }
        await sleepMs(500);
      }
      mark("dug");
      // The cut along the box, the ridge standing either side, held a while.
      await sleepMs(7500);
      mark("cut");
      await sleepMs(1500);
    });
    await leaveTactical(s);
    const after = await state();
    run.note("dig", samples);
    run.note("after", after);
    const dugBlocks = after.insideDug - before.insideDug;
    const fraction = dugBlocks / before.insideStanding;
    run.note("coverage", { dug: dugBlocks, total: before.insideStanding, fraction });
    expect(fraction, `the drills removed ${dugBlocks}/${before.insideStanding} selected blocks`).toBeGreaterThanOrEqual(0.9);
    const beforeStray = new Set(before.stray.map((cell: number[]) => JSON.stringify(cell)));
    const newStray = after.stray.filter((cell: number[]) => !beforeStray.has(JSON.stringify(cell)));
    expect(newStray, "no standing block outside the box is dug").toEqual([]);
    expect(after.groundDug, "the ground below the box stays intact").toBe(before.groundDug);
    run.note("marks", { asked, dug });
  } catch (error) {
    stage?.pair.failing(error);
    throw error;
  } finally {
    await stage?.pair.teardown(testInfo);
    await closeStage(stage);
  }
});

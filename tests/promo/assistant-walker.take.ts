import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { armWalker, buildWalker, clearWalkerGround, GUN_ENTITY, WALKER_STATE } from "./assistant-walker-stage";
import { SHOT_WATCH, stockBattery } from "./assistant-shells";
import {
  askAboutBox, beside, camera, castColony, LABOR_DIAGNOSIS, clickCell, closeChat, closeStage, converse,
  glidePointer, leaveTactical, openStage, openTacticalAt, pointAtShipCell, press, readyBook, selectFacility, SHIP_AIM, shot,
  type BoxDraw, type Stage,
} from "./assistant-kit";
import { sleepMs, take } from "./promo-kit";
import { panelElements } from "../ldlib2";
import type { Vec } from "../shared/colony/colony-founding";

// Segment 4: Let it Walk!'s spider with a big cannon on its deck. The player draws a box with the colony book over the
// pilot controls and the gun, hands it to the assistant and asks for the walker as a facility that walks and fires; the
// assistant reads the hull, tries a short walk and the gun's aim on a copy in its sandbox (the walk and the swing drawn
// over the real spider), and writes the facility. In RTS the player selects the spider and its card stays up while it
// is sent across the meadow, fires on a bunker, walks on and fires on the other bunker twice.

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/assistant/src/main/resources/assets/folkways_assistant/lang/zh_cn.json", "utf8"));
const QUESTION = "背上有驾驶台和炮 做成设施 我想在 RTS 里让它走过去 对着目标开炮";

test("assistant: calibrate a cannon walker and command it in RTS", async ({}, testInfo) => {
  const run = take("assistant-walker");
  let stage: Stage | undefined;
  try {
    stage = await openStage("walker", run);
    const s = stage;
    const { server, player } = s;
    const pad = s.at(0, 0, 120);
    await clearWalkerGround(server, pad);
    run.note("build", await buildWalker(server, pad));
    await tick.freeze(server, false);
    await sleepMs(6000);
    await tick.freeze(server, true);
    const walker = await armWalker(server, pad);
    run.note("walker", walker);
    s.provider.values.HULL = walker.hull;
    await stockBattery(server, walker.store, 8);
    await server.run(SHOT_WATCH, { action: "install" });
    const beds = [s.at(12, 0, 112), s.at(14, 0, 112)];
    for (const bed of beds) {
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z} minecraft:white_bed[part=foot,facing=north]`);
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z - 1} minecraft:white_bed[part=head,facing=north]`);
    }
    const pantry = s.at(10, 0, 112);
    await world.command(server, `setblock ${pantry.x} ${pantry.y} ${pantry.z} minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:bread",count:64}]}`);
    const cast = await castColony(s, [...beds, pantry, walker.store], 2, walker.deck);
    run.note("cast", cast);
    s.provider.values.COLONY = cast.colony_id;

    // The player hovers north of the spider's deck, the book in hand: the box runs from the pilot controls to the
    // gun's muzzle, taking in the mount, the breech and the barrel.
    const c = walker.controls;
    const firstAim = await server.run(SHIP_AIM, walker.controlsStore);
    const draw: BoxDraw = {
      stand: { x: firstAim.x + 5.5, y: firstAim.y + 3.5, z: firstAim.z - 3.5 },
      first: walker.controlsStore, firstAim, second: walker.muzzleStore,
      // The mount is two local cells east of the controls. The muzzle is two more east and two up.
      shipSize: [5, 3, 1],
    };
    run.note("book", await readyBook(s, draw));
    const chatEye = { x: c.x + 21, y: c.y + 8, z: c.z - 13 };
    const chatLook = beside(chatEye, { x: c.x + 3, y: c.y - 1, z: c.z + 3 }, 0.74, 62);
    await camera(s, { eye: [chatEye], look: [chatLook], fov: 62 });
    await sleepMs(1500);

    const reviews: string[] = [];
    const asked = await shot(s, "01-ask", "The player draws a box with the colony book over the walker's pilot controls and "
      + "gun and hands it to the assistant, asking for the cannon walker as a facility; the assistant reads the hull, "
      + "tries a short walk and the gun's aim on a copy in its sandbox (drawn over the real spider) and puts the facility "
      + "up for review", async mark => {
        await askAboutBox(s, draw, QUESTION, mark, async () => {
          // Power assembles the gun into an entity. The book needs the barrel block for the second click.
          const { x, y, z } = walker.powerStore;
          await world.command(server, `setblock ${x} ${y} ${z} minecraft:redstone_block`);
          // The muzzle block becomes an entity. Face the deck controls before using the marked book.
          await pointAtShipCell(s, walker.controlsStore, { x: 0, y: 3.5, z: -4 });
          // The assembled gun gets a fresh id each take; a recording names it, so it is a placeholder.
          void (async () => {
            for (let i = 0; i < 200 && !s.provider.values.GUN; i++) {
              const { uuid } = await server.run(GUN_ENTITY, walker.powerStore);
              if (uuid) Object.assign(s.provider.values, { GUN: uuid, GUN_SHORT: uuid.slice(0, 8) });
              else await sleepMs(300);
            }
          })();
        });
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
    expect(reviews.length, "the assistant must offer a facility for review").toBeGreaterThan(0);
    run.note("recording", s.provider.save({ question: QUESTION, reviews }) ?? "replayed");
    await closeChat(s);

    // Two stops on the meadow, and the two bunkers beyond them.
    const goals = [{ x: pad.x + 2, y: pad.y, z: pad.z + 15 }, { x: pad.x - 7, y: pad.y, z: pad.z + 27 }];
    const bunkers = [{ x: pad.x - 5, y: pad.y + 1, z: pad.z + 44 }, { x: pad.x + 7, y: pad.y + 1, z: pad.z + 50 }];
    // One still picture from high off the spider's east flank for the whole clip, the card over it: the pad at the
    // right, the two stops in the middle and the bunkers at the left, so the spider walks and fires across the frame.
    await openTacticalAt(s, { x: pad.x + 36, y: pad.y + 30, z: pad.z + 24 }, { x: pad.x, y: pad.y + 1, z: pad.z + 22 });
    const samples: any[] = [];
    let fired = 0;
    const commanded = await shot(s, "02-command", "In RTS the player selects the spider and its card opens; it is sent "
      + "across the meadow and a resident at the pilot controls walks it there; the gun's target is picked on the card and "
      + "a bunker clicked: a resident loads and the gun fires; it walks on, and fires twice on the other bunker, the card on "
      + "screen throughout", async mark => {
        await sleepMs(1000);
        const now = await server.run(SHIP_AIM, walker.controlsStore);
        await selectFacility(s, { x: Math.floor(now.x), y: Math.floor(now.y), z: Math.floor(now.z) });
        mark("selected");
        await sleepMs(1500);
        let waits = 0;
        // Sends the spider to a stop and watches it walk there, the card still up.
        const walkTo = async (goal: Vec, n: number) => {
          await clickCell(s, goal, "right");
          mark(`move-${n}`);
          await sleepMs(600);
          await glidePointer(s.client, { x: 960, y: 1060 }, 300);
          const now0 = await server.run(WALKER_STATE, { core: walker.coreStore });
          const started = Date.now();
          mark(`wait-${++waits}`);
          let walked = false;
          while (Date.now() - started < 90_000) {
            const now = await server.run(WALKER_STATE, { core: walker.coreStore });
            samples.push({ t: (Date.now() - started) / 1000, leg: n, ...now });
            if (!walked && Math.hypot(now.x - now0.x, now.z - now0.z) > 2) {
              walked = true;
              mark(`resume-${waits}`);
              mark(`walking-${n}`);
            }
            if (Math.hypot(now.x - goal.x, now.z - goal.z) < 3.5) {
              mark(`arrived-${n}`);
              if (n === 1) mark("arrived");
              break;
            }
            await sleepMs(500);
          }
          if (!walked) mark(`resume-${waits}`);
          await sleepMs(3300);
        };
        // Picks the gun's target on the card, clicks a bunker, and watches the shot.
        const fireAt = async (bunker: Vec, n: number) => {
          // A card may offer aiming apart from firing: the one that fires comes first.
          const targets = (await panelElements(s.client)).filter(one => one.id?.startsWith("folkways.card.target.")
            && !/move|walk|go|走|前往|移动/.test(one.id) && !/走|前往|移动/.test(one.text ?? ""));
          const target = targets.find(one => /shoot|fire|attack|开炮|开火|射击|攻击/.test(`${one.id} ${one.text ?? ""}`))
            ?? targets[0];
          if (!target) throw new Error("the card offers no target to fire at");
          await press(s, target.id!);
          mark(`target-${n}`);
          await sleepMs(700);
          await clickCell(s, bunker);
          mark(`attacked-${n}`);
          if (n === 1) mark("attacked");
          await glidePointer(s.client, { x: 960, y: 1060 }, 300);
          const started = Date.now();
          mark(`wait-${++waits}`);
          let shot = false;
          while (Date.now() - started < 120_000) {
            const shots = await server.run(SHOT_WATCH, { action: "read" });
            samples.push({ t: (Date.now() - started) / 1000, shots });
            if (shots.count > fired && !shot) {
              shot = true;
              mark(`resume-${waits}`);
              mark(`fired-${n}`);
              if (n === 1) mark("fired");
            }
            if (shots.count > fired && !shots.flying) break;
            if (samples.length % 60 === 0) run.note(`labor-${samples.length}`, await server.run(LABOR_DIAGNOSIS, { colony: cast.colony_id }));
            await sleepMs(300);
          }
          if (!shot) mark(`resume-${waits}`);
          fired = (await server.run(SHOT_WATCH, { action: "read" })).count;
          await sleepMs(2500);
        };
        await walkTo(goals[0], 1);
        await fireAt(bunkers[0], 1);
        await sleepMs(2100);
        await walkTo(goals[1], 2);
        await fireAt(bunkers[1], 2);
        await sleepMs(2100);
        await fireAt(bunkers[1], 3);
        await sleepMs(1500);
        mark("cut");
        await sleepMs(1500);
      });
    await leaveTactical(s);
    run.note("walk", samples);
    expect(samples.some(one => Math.hypot(one.x - goals[1].x, one.z - goals[1].z) < 3.5), "the spider must walk where it was sent").toBe(true);
    expect(fired, "the gun must fire on the bunkers").toBeGreaterThanOrEqual(2);
    run.note("marks", { asked, commanded });
  } catch (error) {
    stage?.pair.failing(error);
    throw error;
  } finally {
    await stage?.pair.teardown(testInfo);
    await closeStage(stage);
  }
});

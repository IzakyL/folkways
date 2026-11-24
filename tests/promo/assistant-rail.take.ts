import { expect, test } from "@playwright/test";
import { world } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { BUILD_ORDERS, buildRail, PREDEFINE_RAIL, RAIL_STATE, railLayout, STATION_NAME, type RailState } from "./assistant-rail-stage";
import {
  askAboutBox, beside, camera, castColony, closeChat, closeStage, converse, openStage,
  glidePointer, openCardFromBook, press, readyBook, shot, type BoxDraw, type Stage,
} from "./assistant-kit";
import { panelElements } from "../ldlib2";
import type { Vec } from "../shared/colony/colony-founding";
import { sleepMs, take } from "./promo-kit";

// Segment 1: a new station east of a trunk line, its siding pointing at the trunk but stopping short of it. The player
// draws a box with the colony book from the siding's end to the trunk and hands it to the assistant to join them. The railway it plans waits in the world as ghost track with its box and label: west
// off the siding, a quarter turn, and a turnout curving into the running line. In creative the review window offers to
// build it at once or hand it to the colony; the colony is picked and the change approved. The builders lay it off
// camera, and the second shot flies along the finished section from the station to the junction.

const ZH: Record<string, string> = JSON.parse(readFileSync(
  "mods/assistant/src/main/resources/assets/folkways_assistant/lang/zh_cn.json", "utf8"));
const QUESTION = `${STATION_NAME}还没接上西边的干线 帮我修一段接过去 从站线西头出来 拐个弯并进干线 往北走`;
// FOLKWAYS_PROMO_RAIL_PREDEFINE=1 skips the conversation and commissions the route directly, to work on the stage and
// the second shot without asking the model.
const PREDEFINE = process.env.FOLKWAYS_PROMO_RAIL_PREDEFINE === "1";

test("assistant: connect a station to the line and hand the railway to the colony", async ({}, testInfo) => {
  const run = take("assistant-rail");
  let stage: Stage | undefined;
  try {
    stage = await openStage("rail", run);
    const s = stage;
    const { server, client, player } = s;
    const o = s.at(0, 0, -20);
    const site = railLayout(o);
    run.note("rail", await buildRail(server, client, player, o));
    // The colony's yard lies south of the station: beds, a pantry and the builders' supplies.
    const yard = (x: number, z: number) => ({ x: o.x + x, y: o.y, z: o.z + z });
    const supply = yard(12, 10);
    await world.command(server, `setblock ${supply.x} ${supply.y} ${supply.z} folkways_vanilla:creative_supply_box`);
    const beds = [yard(14, 16), yard(16, 16), yard(18, 16)];
    for (const bed of beds) {
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z} minecraft:white_bed[part=foot,facing=north]`);
      await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z - 1} minecraft:white_bed[part=head,facing=north]`);
    }
    const pantry = yard(12, 16);
    await world.command(server, `setblock ${pantry.x} ${pantry.y} ${pantry.z} minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:bread",count:64}]}`);
    const cast = await castColony(s, [...beds, pantry, supply], 3, [yard(10, 8), yard(13, 9), yard(15, 8)]);
    run.note("cast", cast);
    s.provider.values.COLONY = cast.colony_id;

    // In creative, as a builder plays: the review window then offers to set the railway down at once. The player
    // hovers over the yard, the book in hand: the box runs from the siding's west end to the trunk north of it,
    // taking in the gap the new route crosses.
    const draw: BoxDraw = {
      stand: { x: o.x + 12.5, y: o.y + 14, z: o.z + 9.5 },
      first: site.sidingEnd, firstAim: { x: site.sidingEnd.x + 0.5, y: o.y + 0.2, z: o.z + 0.5 },
      second: { x: o.x, y: o.y, z: o.z - 14 }, secondAim: { x: o.x + 0.5, y: o.y + 0.2, z: o.z - 13.5 },
    };
    if (!PREDEFINE) run.note("book", await readyBook(s, draw));
    else await world.command(server, `gamemode creative ${player}`);
    // High over the yard, looking north-west across the station to the trunk: the whole route, from the siding's end
    // through the turn to the trunk, stands right of the chat window.
    const chatEye = { x: o.x + 30, y: o.y + 22, z: o.z + 22 };
    const chatLook = beside(chatEye, { x: o.x + 8, y: o.y, z: o.z - 4 }, 0.76, 62);
    await camera(s, { eye: [chatEye], look: [chatLook], fov: 62 });
    await sleepMs(1500);
    const around = { min: { x: o.x - 1, y: o.y - 1, z: o.z - 14 }, max: { x: o.x + 21, y: o.y + 2, z: o.z + 14 } };
    const railState = (): Promise<RailState> => server.run(RAIL_STATE, { cells: site.straight, trunk: site.trunk, around });
    run.note("before", await railState());

    const reviews: Array<{ summary: string; handoff: boolean }> = [];
    const asked = PREDEFINE ? await predefine(s, cast.colony_id, o) : await shot(s, "01-ask", "The player draws a box with the colony book from the siding's end to the trunk and asks the assistant to connect the station to the main line; the "
      + "railway it plans appears as ghost track with its box and label, its review window beside the chat offering to "
      + "build at once or hand it to the colony; the colony is picked and the change approved", async mark => {
        await askAboutBox(s, draw, QUESTION, mark);
        await converse(s, async review => {
          reviews.push(review);
          mark(`review-${reviews.length}`);
          await sleepMs(3000);
          if (review.handoff) {
            await press(s, "folkways.assistant.raise");
            await sleepMs(900);
            await press(s, "folkways.assistant.handoff");
            mark("handoff");
            await sleepMs(1500);
          }
          await press(s, "folkways.assistant.confirm");
          mark(`approved-${reviews.length}`);
        }, { idle: ZH["folkways.assistant.ready"], mark });
        mark("answered");
        await sleepMs(1000);
        await closeChat(s);
        mark("chat-closed");
        await sleepMs(5000);
      });
    run.note("reviews", reviews);
    if (!PREDEFINE) {
      run.note("recording", s.provider.save({ question: QUESTION, reviews }) ?? "replayed");
      expect(reviews.some(one => one.handoff), "the railway must come up for review with the build choice").toBe(true);
    }
    await closeChat(s);

    // The site handed over: from above, its card is opened, the order and its progress, the ghost track waiting.
    const handed = await railState();
    run.note("handed", handed);
    const orders = await server.run(BUILD_ORDERS, { colony: cast.colony_id });
    run.note("orders", orders);
    if (orders.length === 0) throw new Error("the railway was not handed to the colony");
    const anchor = orders[0].anchor;
    // The same picture as the asking, a little closer: the player uses the book on the new section and the site's
    // card opens over the ghost track, its progress and what it still needs.
    const closer = { x: o.x + 26, y: o.y + 16, z: o.z + 17 };
    const carded = await shot(s, "02-site", "The player opens the colony book's Facilities page and clicks the "
      + "handed-over railway: the site's card opens, its progress and what it still needs, the ghost track from the "
      + "siding through the turn to the junction on the trunk beside it", async mark => {
        await camera(s, { eye: [closer], look: [beside(closer, { x: o.x + 8, y: o.y, z: o.z - 4 }, 0.8, 60)], glide: 3, fov: 60 });
        await sleepMs(1200);
        run.note("card-waits", await openCardFromBook(s, { x: o.x + 12.5, y: o.y + 6, z: o.z + 9.5 },
          { x: o.x + 14.5, y: o.y - 0.02, z: o.z + 6.5 }, orders[0].id, mark));
        await glidePointer(s.client, { x: 960, y: 1060 }, 400);
        await sleepMs(5000);
        mark("cut");
        await sleepMs(1500);
      });
    run.note("card", (await panelElements(s.client)).filter(one => one.id?.startsWith("folkways.card.")).map(one => [one.id, one.text]));
    await closeChat(s);
    run.note("marks", { asked, carded });
  } catch (error) {
    stage?.pair.failing(error);
    throw error;
  } finally {
    await stage?.pair.teardown(testInfo);
    await closeStage(stage);
  }
});

/** The route commissioned straight to the colony, as the assistant would plan it: west off the siding, a quarter turn
 * north, and along the trunk to the turnout's far end. */
async function predefine(s: Stage, colony: string, o: Vec) {
  const g = o.y - 1;
  const points = [{ x: o.x + 20, y: g, z: o.z }, { x: o.x, y: g, z: o.z }, { x: o.x, y: g, z: o.z - 12 }];
  const args = { colony, player: s.player, points };
  s.run.note("predefine-start", await s.server.run(PREDEFINE_RAIL, args));
  let result = "pending";
  for (let i = 0; i < 60 && (result === "pending" || result.startsWith("drawn")); i++) {
    await sleepMs(500);
    result = (await s.server.run(PREDEFINE_RAIL, { ...args, read: true })).result;
  }
  s.run.note("predefine", result);
  if (!result.startsWith("filed=true")) throw new Error(`the predefined railway was not filed: ${result}`);
  return {};
}

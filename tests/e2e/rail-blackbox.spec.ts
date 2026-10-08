import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import {
  createColonyViaBook,
  tryCommand,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import {
  buildRailLine,
  clickConductorSeat,
  seatedAt,
  trainHasSchedule,
  GIVEN_BACK,
  TAKEN_ON,
} from "./rail-fixture";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "rail-handover-report");

const RAIL_LINE = 24;

test("a train becomes the colony's on one click of the conductor seat, and stops being it on the next", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = { scope: "conductor-seat hand-over: take on, give back, and the click that is just a seat" };

  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "rail-server"), instance: seededServer(`rail-server-${runId}`) },
      client: { trace: tracePath("e2e", "rail-client"), instance: `rail-client-${runId}` },
      snapshot: () => ({ containers: [], entityTypes: ["folkways:resident"] }),
    });
    ({ server, client } = pair);

    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);
    await tryCommand(server, `op ${player.name}`);
    await tryCommand(server, `gamemode creative ${player.name}`);
    const playerUuid = (await world.players(server)).find((p: any) => p.name === player.name)?.uuid;
    expect(playerUuid, "could not read the player uuid, so assembly has no one to sign it").toBeTruthy();

    const origin: Vec = {
      x: Math.round(player.pos.x) + 4,
      y: Math.round(player.pos.y),
      z: Math.round(player.pos.z) + 4,
    };
    await tryCommand(server, `fill ${origin.x - 8} ${origin.y} ${origin.z - 8} ${origin.x + 8} ${origin.y + 4} ${origin.z + 8} minecraft:air`);
    await tryCommand(server, `give ${player.name} folkways:colony_book`);
    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 5);
    report.create = await createColonyViaBook(server, client, player.name, origin);
    const backup = await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);
    expect(backup?.success, "the bound lord's book should back up into inventory.0").toBe(true);

    const railOrigin: Vec = { x: origin.x - 8, y: origin.y, z: origin.z + 4 };
    const rail = await buildRailLine(server, client, player.name, playerUuid, railOrigin, RAIL_LINE, "inventory.0");
    report.rail = {
      ok: rail.ok, assembled: rail.assembled, scheduled: rail.scheduled, handedOver: rail.handedOver,
      carriage: rail.carriageUuid, conductorSeat: rail.conductorSeat, steps: rail.steps,
    };
    expect(rail.assembled, `the train should assemble: ${JSON.stringify(rail.steps).slice(0, 400)}`).toBe(true);
    expect(rail.carriageUuid, "after assembly the world should hold a carriage entity").toBeTruthy();
    const carriageUuid = rail.carriageUuid!;
    const stand = { x: railOrigin.x + 3.5, y: railOrigin.y + 2, z: rail.conductorSeat.z + 0.5 };

    expect(rail.scheduled, "the schedule should be in the train runtime").toBe(true);
    expect(
      rail.handedOver,
      `the action bar should report the hand-over receipt "${TAKEN_ON}": ${JSON.stringify(rail.steps?.hand_over)}`,
    ).toBe(true);
    report.after_take_on = { seat: await seatedAt(server, carriageUuid, playerUuid) };
    expect(report.after_take_on.seat, "the hand-over should not seat the player").toBeUndefined();

    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 5);
    const giveBack = await clickConductorSeat(server, client, player.name, carriageUuid, stand);
    report.give_back = {
      action_bar: giveBack.actionBar,
      held: (await tryCommand(server, `data get entity ${player.name} SelectedItem`))?.output?.[0]?.slice(0, 400),
      schedule_on_train: await trainHasSchedule(server, carriageUuid),
      seat: await seatedAt(server, carriageUuid, playerUuid),
    };
    expect(
      giveBack.actionBar.text,
      `an empty-hand right-click should take the train back and report "${GIVEN_BACK}": ${JSON.stringify(report.give_back)}`,
    ).toContain(GIVEN_BACK);
    expect(report.give_back.held, "the schedule should return to the player with its stations").toContain("create:train_schedule");
    expect(report.give_back.schedule_on_train, "the train runtime should no longer hold a schedule").toBe(false);
    expect(report.give_back.seat, "taking it back should not seat the player either").toBeUndefined();

    await tryCommand(server, `item replace entity ${player.name} weapon.offhand with minecraft:air`);
    await tick.sprint(server, 5);
    const plainSeat = await clickConductorSeat(server, client, player.name, carriageUuid, stand);
    report.plain_seat = {
      action_bar: plainSeat.actionBar,
      seat: await seatedAt(server, carriageUuid, playerUuid),
    };
    expect(
      report.plain_seat.seat,
      `without a book in the offhand the conductor seat is an ordinary seat and the player should sit: ${JSON.stringify(report.plain_seat)}`,
    ).not.toBeUndefined();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(report, null, 2);
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), body);
    testInfo.attach("rail-handover-report", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

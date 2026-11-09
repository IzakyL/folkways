import { expect, test } from "@playwright/test";
import {
  defineTask, input, javaTask, screen, tick,
  type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { checkModeLayers } from "../shared/ui/mode-layers";
import {
  PREPARE_RESIDENTS, READ_CLIENT, PROJECT, RESIZE, type Vec, type Pixel, type ClientState,
} from "../shared/combat/rts-fixtures";
import { foundBookColony, layFloor, openRtsTool, tapV } from "../shared/combat/overhead";

type ServerState = {
  selection: string[]; groups: string[][]; owned: boolean[]; reserved: boolean[];
  group: string; command: string; kind: string; phase: string; result: string;
  members: string[]; memberGroups: string[]; points: Vec[]; cycleStart: number;
  complete: boolean; attackMove: boolean; stop: number; waiting: number; orders: string[];
};
type Observation = { client: ClientState; server: ServerState };
type Context = { colony: string; player: string; residents: string[] };

// Player actions under test go through input.*. The explicit rejected-packet probes below additionally
// exercise server refusal feedback; other Java tasks only arrange fixtures or observe production state.
test("RTS real pointer and keyboard inputs select, bind, queue, stop, suspend and release", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `combat-input-server-${run}` },
      client: { instance: `combat-input-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, "gamerule doDaylightCycle false");
    await mustCommand(server, "time set noon");
    await layFloor(server, { x: origin.x - 19, z: origin.z - 19 }, { x: origin.x + 19, z: origin.z + 19 }, origin.y,
      "minecraft:smooth_stone", 6);
    const { colony } = await foundBookColony(server, client, player.name, origin, 3);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 3 });
    expect(residents).toHaveLength(3);
    const context: Context = { colony, player: player.name, residents };
    const home = `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`;
    await mustCommand(server, home);
    await tick.sprint(server, 2);
    await frames(client, 10);
    await openRtsTool(client);
    await waitFor("combat roster", client, server, context, value => value.client.combat && value.client.roster >= 3);

    const empty = await client.run(READ_CLIENT);
    expectMode(empty, false);
    await input.key(client, { keyCode: 71 });
    const emptyAttack = await client.run(READ_CLIENT);
    expectMode(emptyAttack, true);
    expect(emptyAttack.failure).toBe("");
    expect(emptyAttack.route.request).toBe(empty.route.request);
    expect(emptyAttack.control.request).toBe(empty.control.request);
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), false);
    await input.key(client, { keyCode: 72 });
    const noSelection = await client.run(READ_CLIENT);
    expect(noSelection.failureKey).toBe("folkways.rts.select_first");
    expect(noSelection.failure).not.toBe("");
    expect(noSelection.failureRemaining).toBeGreaterThan(0);
    expect(noSelection.failureRemaining).toBeLessThanOrEqual(3000);
    await failureExpired(client);

    // Keep the box-selection fixture stationary while deselected residents are unowned.
    await server.run(SET_RESIDENT_AI, { residents, enabled: false });
    await tick.sprint(server, 3);
    await frames(client, 10);
    const { boxes, from, to } = await boxAround(client, origin, residents);
    const beforeBox = (await client.run(READ_CLIENT)).control.request;
    await input.drag(client, { from, to, steps: 8, button: "left" });
    await selection(client, server, context, residents, beforeBox);

    // The card frame's top edge belongs to the bar: clicks there neither select nor order.
    const beforeFrame = await client.run(READ_CLIENT);
    const frameTop = boxes[0].height - beforeFrame.barHeight;
    for (const button of ["left", "right"] as const) {
      await input.click(client, { x: boxes[0].width / 2, y: frameTop }, { button });
      if (button === "right") {
        await input.click(client, { x: boxes[0].width / 2, y: frameTop }, { button, modifiers: ["ctrl"] });
      }
      await tick.sprint(server, 2);
      await frames(client, 3);
      const observed = await client.run(READ_CLIENT);
      expect(same(observed.selected, residents)).toBe(true);
      expect(observed.route.request).toBe(beforeFrame.route.request);
      expect(observed.cachedPoints).toBe(beforeFrame.cachedPoints);
      expect(observed.failure).toBe("");
      const authoritative = await server.run(READ_SERVER, context);
      expect(same(authoritative.selection, residents)).toBe(true);
      expect(authoritative.command).toBe("");
    }

    const third = residents[2];
    const shiftClickThird = async () => {
      const at = await project(client, origin, third);
      visible(at);
      await input.click(client, at, { button: "left", modifiers: ["shift"] });
    };
    let before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents, before);

    before = (await client.run(READ_CLIENT)).control.request;
    await input.click(client, { x: boxes[0].width / 2, y: frameTop - 1 }, { button: "left" });
    await selection(client, server, context, [], before);
    before = (await client.run(READ_CLIENT)).control.request;
    const current = await boxAround(client, origin, residents);
    await input.drag(client, { from: current.from, to: current.to, steps: 8, button: "left" });
    await selection(client, server, context, residents, before);

    const bindOne = async (members: string[]) => {
      const request = (await client.run(READ_CLIENT)).control.request;
      await input.key(client, { keyCode: 49, modifiers: ["ctrl"] });
      return waitFor("Ctrl+1 authoritative exact binding", client, server, context, value =>
        value.client.control.request > request && value.client.control.accepted && !value.client.controlPending
        && same(value.client.control.groups[0] ?? [], members) && same(value.server.groups[0] ?? [], members)
        && same(value.client.selected, value.server.selection));
    };
    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    const ab = await bindOne(residents.slice(0, 2));
    expect(ab.server.owned[2], "C loses its final reference after exact ABC -> AB replacement").toBe(false);
    expect(ab.server.reserved[2]).toBe(false);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents, before);
    await bindOne(residents);
    const unbound = await bindOne([]);
    expect(same(unbound.client.selected, residents), "same-set Ctrl+1 keeps visible selection").toBe(true);
    expect(same(unbound.server.selection, residents)).toBe(true);
    expect(unbound.server.owned).toEqual([true, true, true]);

    // A group card shows pressed exactly while its members are the selection, whether recalled by key or card.
    const groupTextures = async (slots: number[]) => {
      await frames(client, 3);
      const groups = (await client.run(READ_CLIENT)).buttons.filter(hit => hit.kind === "GROUP");
      expect(groups).toHaveLength(9);
      expect(groups.filter(hit => hit.texture === "pressed").map(hit => hit.slot)).toEqual(slots);
    };
    const bind = async (slot: number, members: string[]) => {
      const request = (await client.run(READ_CLIENT)).control.request;
      await input.key(client, { keyCode: 48 + slot, modifiers: ["ctrl"] });
      return waitFor(`Ctrl+${slot} binding ACK`, client, server, context, value =>
        value.client.control.request > request && value.client.control.accepted && !value.client.controlPending
          && same(value.client.control.groups[slot - 1] ?? [], members)
          && same(value.server.groups[slot - 1] ?? [], members));
    };
    await bindOne(residents);
    await groupTextures([1]);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, residents.slice(0, 2), before);
    await groupTextures([]);
    await bind(2, residents.slice(0, 2));
    await groupTextures([2]);
    before = (await client.run(READ_CLIENT)).control.request;
    await input.key(client, { keyCode: 49 });
    await selection(client, server, context, residents, before);
    await groupTextures([1]);
    const secondSlot = (await client.run(READ_CLIENT)).buttons.find(hit => hit.kind === "GROUP" && hit.slot === 2)!;
    before = (await client.run(READ_CLIENT)).control.request;
    await input.click(client, {
      x: secondSlot.x + secondSlot.width / 2, y: secondSlot.y + secondSlot.height / 2,
    }, { button: "left" });
    await selection(client, server, context, residents.slice(0, 2), before);
    await groupTextures([2]);
    await bind(2, []);
    before = (await client.run(READ_CLIENT)).control.request;
    await input.key(client, { keyCode: 49 });
    await selection(client, server, context, residents, before);
    await bindOne([]);
    await groupTextures([]);
    await server.run(SET_RESIDENT_AI, { residents, enabled: true });

    const a = { x: origin.x - 5, y: origin.y, z: origin.z + 11 };
    const b = { x: origin.x, y: origin.y, z: origin.z + 14 };
    const c = { x: origin.x + 6, y: origin.y, z: origin.z + 11 };
    const groundClick = async (point: Vec, control = false) => {
      const at = await project(client, point);
      visible(at);
      await input.click(client, at, { button: "right", modifiers: control ? ["ctrl"] : [] });
    };
    let routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(a);
    const moving = await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    expect(moving.server.kind).toBe("DIRECT");
    expect(moving.server.complete).toBe(false);
    const squad = moving.server.group;
    const generation = moving.server.command;
    expect(squad).not.toBe("");
    expect(moving.server.memberGroups).toEqual([squad, squad, squad]);
    expect(same(moving.server.members, residents)).toBe(true);
    for (const intent of ["MOVE", "ATTACK", "WAYPOINT", "STOP", "CONTROL"]) {
      const beforeRejection = await client.run(READ_CLIENT);
      const request = beforeRejection.route.request;
      expect(await client.run(REJECTED_PACKET, { ...origin, intent })).toBe(true);
      let rejected: Observation;
      if (["MOVE", "ATTACK", "WAYPOINT"].includes(intent)) {
        rejected = await waitFor(`${intent} rejection retains the accepted route and reports only failure`,
          client, server, context, value => value.client.route.request > request
            && !value.client.routePending && !value.client.route.accepted && value.client.route.intent === intent);
      } else {
        // Stale requests are deliberately ignored by the client's ACK cache; the server still rejects them.
        await tick.sprint(server, 4);
        await frames(client, 12);
        rejected = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
      }
      expect(rejected.client.overlay, `${intent} must use the bottom failure row, not action-bar text`).toBe("");
      if (["MOVE", "ATTACK", "WAYPOINT"].includes(intent)) {
        expect(rejected.client.failureKey).toBe("folkways.combat.error.rejected");
        expect(rejected.client.failure).not.toBe("");
        expect(rejected.client.failureRemaining).toBeGreaterThan(0);
        expect(rejected.client.failureRemaining).toBeLessThanOrEqual(3000);
      } else {
        expect(rejected.client.failureUntil, "stale replies must not create or extend a failure").toBe(beforeRejection.failureUntil);
      }
      expect(rejected.server.command).toBe(generation);
      expect(samePoints(rejected.server.points, [a])).toBe(true);
      expect(same(rejected.client.selected, residents)).toBe(true);
    }
    await failureExpired(client);

    const completed = await waitFor("ordinary point completes its physical assembly and 20-tick dwell", client, server, context,
      value => value.server.command === generation && value.server.complete
        && value.client.state.command === generation && value.client.state.complete,
      260, 10);
    expect(completed.server.phase).toBe("HOLDING");
    expect(completed.server.waiting).toBe(0);
    expect(completed.server.owned).toEqual([true, true, true]);
    expect(completed.client.cachedPoints, "completed authored history stays available for Ctrl append/review").toBe(1);

    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(c, true);
    await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], -1, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(b, true);
    const loop = await routeAck(client, server, context, routeRequest, "WAYPOINT", [a, b, c], 1, false);
    expect(loop.server.group).toBe(squad);
    expect(loop.server.command, "append/closure keeps the accepted generation").toBe(generation);

    const keys = await client.run(KEY_SETTINGS, { stop: 72 });
    expect(keys.codes).toEqual([71, 72, 74]);
    expect(keys.categories).toEqual(Array(3).fill("key.categories.folkways_core.rts"));
    expect(keys.hintBindings).toContain("key.folkways.rts.attack_move");
    expect((await client.run(READ_CLIENT)).buttons.some(hit =>
      ["ATTACK_MOVE", "STOP", "RELEASE"].includes(hit.kind))).toBe(false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await input.key(client, { keyCode: 72 });
    const stopped = await waitFor("H stop accepted, retaining the actual squad", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && !value.client.routePending && value.server.kind === "STOP" && value.server.points.length === 0);
    expect(stopped.server.group).toBe(squad);
    expect(stopped.server.command).not.toBe(generation);
    expect(stopped.server.owned).toEqual([true, true, true]);
    expect(same(stopped.client.selected, residents)).toBe(true);
    expect(stopped.client.overlay).toBe("");
    expect(stopped.client.failure).toBe("");

    await input.key(client, { keyCode: 71, action: "press" });
    await input.key(client, { keyCode: 71, action: "repeat" });
    expect((await client.run(READ_CLIENT)).attackMode, "holding G toggles once without repeat toggling").toBe(true);
    await input.key(client, { keyCode: 71, action: "release" });
    await input.key(client, { keyCode: 71, modifiers: ["shift"] });
    expect((await client.run(READ_CLIENT)).attackMode, "Shift keeps its selection modifier ownership").toBe(true);
    expectMode(await client.run(READ_CLIENT), true);
    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    const selectedPixel = await project(client, origin, third);
    await input.click(client, selectedPixel, { button: "left" });
    const leftSelection = await selection(client, server, context, [third], before);
    expectMode(leftSelection.client, true);
    expect(leftSelection.server.command).toBe(stopped.server.command);
    before = leftSelection.client.control.request;
    await input.key(client, { keyCode: 49 });
    const recalled = await selection(client, server, context, residents, before);
    expectMode(recalled.client, true);
    before = recalled.client.control.request;
    await input.key(client, { keyCode: 256 });
    const deselected = await selection(client, server, context, [], before);
    expectMode(deselected.client, true);
    before = deselected.client.control.request;
    await input.key(client, { keyCode: 49 });
    const restored = await selection(client, server, context, residents, before);
    expectMode(restored.client, true);
    routeRequest = restored.client.route.request;
    const attackPoint = { x: origin.x + 6, y: origin.y, z: origin.z + 7 };
    const attackPixel = await project(client, attackPoint);
    visible(attackPixel);
    await mustCommand(server, `setblock ${attackPoint.x} ${attackPoint.y} ${attackPoint.z} minecraft:water`);
    await tick.sprint(server, 2);
    await frames(client, 12);
    await input.click(client, attackPixel, { button: "right" });
    const invalid = await client.run(READ_CLIENT);
    expect(invalid.attackMode, "invalid ground preserves the persistent movement mode").toBe(true);
    expect(invalid.route.request).toBe(routeRequest);
    expect(same(invalid.selected, residents), "invalid RMB keeps the selection").toBe(true);
    expect(invalid.failureKey).toBe("folkways.rts.ground_required");
    expect(invalid.failure).not.toBe("");
    expect(await client.run(REJECTED_PACKET, { ...origin, intent: "ATTACK" })).toBe(true);
    const replacedFailure = await waitFor("a new server failure replaces the local ground failure", client, server, context,
      value => value.client.route.request > routeRequest && !value.client.route.accepted
        && value.client.failureKey === "folkways.combat.error.rejected");
    expect(replacedFailure.client.failureUntil).toBeGreaterThan(invalid.failureUntil);
    expect(replacedFailure.client.attackMode).toBe(true);
    routeRequest = replacedFailure.client.route.request;
    await client.run(RESIZE, { width: 1920, height: 1080, scale: 2 });
    await frames(client, 4);
    expect((await client.run(READ_CLIENT)).failureKey, "a window resize keeps the failure row").toBe("folkways.combat.error.rejected");
    await client.run(RESIZE, { width: 1280, height: 720, scale: 1 });
    await failureExpired(client);
    await mustCommand(server,
      `fill ${attackPoint.x - 3} ${attackPoint.y} ${attackPoint.z - 3} ${attackPoint.x + 3} ${attackPoint.y + 1} ${attackPoint.z + 3} minecraft:air`);
    await tick.sprint(server, 2);
    await frames(client, 12);
    const dryAttackPixel = await project(client, attackPoint);
    visible(dryAttackPixel);
    await input.click(client, dryAttackPixel, { button: "right" });
    const attackMove = await routeAck(client, server, context, routeRequest, "MOVE", [attackPoint], -1, true);
    expect(attackMove.server.kind).toBe("ATTACK_MOVE");
    const attackSquad = attackMove.server.group;
    expect(attackSquad).not.toBe("");
    expect(same(attackMove.server.members, residents)).toBe(true);
    expect(attackMove.server.memberGroups).toEqual([attackSquad, attackSquad, attackSquad]);
    expect(attackMove.server.command).not.toBe(stopped.server.command);
    expect(attackMove.server.owned).toEqual([true, true, true]);
    expect(attackMove.server.reserved).toEqual([true, true, true]);
    if (attackSquad !== squad) {
      expect(await server.run(GROUP_EXISTS, { colony, group: squad }), "a new ground route removes the superseded empty squad").toBe(false);
    }
    expectMode(attackMove.client, true);
    expect(same(attackMove.client.selected, residents)).toBe(true);
    routeRequest = attackMove.client.route.request;
    await groundClick(b, true);
    const attackAppend = await routeAck(client, server, context, routeRequest, "WAYPOINT", [attackPoint, b], -1, true);
    expectMode(attackAppend.client, true);
    expect(attackAppend.server.command).toBe(attackMove.server.command);
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(c, true);
    const mismatch = await client.run(READ_CLIENT);
    expect(mismatch.failureKey).toBe("folkways.rts.route_mode_mismatch");
    expect(mismatch.route.request).toBe(routeRequest);
    expect(mismatch.cachedCommand).toBe(attackMove.server.command);
    await failureExpired(client);
    await groundClick(a);
    const moveAgain = await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    expectMode(moveAgain.client, false);
    await input.key(client, { keyCode: 71 });
    await groundClick(c, true);
    expect((await client.run(READ_CLIENT)).failureKey).toBe("folkways.rts.route_mode_mismatch");
    expect((await client.run(READ_CLIENT)).route.request).toBe(moveAgain.client.route.request);
    await failureExpired(client);
    routeRequest = moveAgain.client.route.request;
    await groundClick(attackPoint);
    // A new preference must survive the previous mode's acknowledgement without mutating that order.
    await input.key(client, { keyCode: 71 });
    const inFlight = await routeAck(client, server, context, routeRequest, "MOVE", [attackPoint], -1, true);
    expectMode(inFlight.client, false);
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), true);
    await bindOne([]);
    await checkModeLayers(client, server, { colony, ...origin });
    const modeReturn = await waitFor("mode return keeps the accepted command generation", client, server, context,
      value => value.client.combat && !value.client.controlPending
        && value.server.group === inFlight.server.group && value.server.command === inFlight.server.command);
    expectMode(modeReturn.client, false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    const rebound = await client.run(KEY_SETTINGS, { stop: 75 });
    expect(rebound.codes).toEqual([71, 75, 74]);
    expect(rebound.hintBindings).toContain("key.folkways.rts.stop");
    await input.key(client, { keyCode: 72 });
    expect((await client.run(READ_CLIENT)).route.request, "old H binding must stop issuing commands").toBe(routeRequest);
    await input.key(client, { keyCode: 75 });
    const finalStop = await waitFor("second Stop replaces attack move", client, server, context, value =>
      value.client.route.request > routeRequest && value.client.route.accepted && value.client.route.intent === "STOP"
      && value.server.kind === "STOP" && value.server.points.length === 0 && !value.client.routePending);
    expect(finalStop.server.group).toBe(inFlight.server.group);
    expect(finalStop.server.command).not.toBe(inFlight.server.command);
    const stopGeneration = finalStop.server.command;

    await input.key(client, { keyCode: 258 });
    await frames(client, 4);
    expect((await client.run(READ_CLIENT)).combat, "Tab must leave the combat tool").toBe(false);
    await input.key(client, { keyCode: 71 });
    expect((await client.run(READ_CLIENT)).attackMode, "G is inactive after leaving the RTS tool").toBe(false);
    await tick.sprint(server, 2);
    let returned = false;
    for (let step = 0; step < 12; step++) {
      await input.key(client, { keyCode: 258 });
      await tick.sprint(server, 1);
      await frames(client, 4);
      if ((await client.run(READ_CLIENT)).combat) { returned = true; break; }
    }
    expect(returned, "Tab cycles the book gestures and returns to Combat").toBe(true);
    const resumed = await waitFor("Tab return restores authoritative selection without replacing orders", client, server, context,
      value => value.client.combat && !value.client.controlPending && same(value.client.selected, residents)
        && value.server.group === inFlight.server.group && value.server.command === stopGeneration);
    expect(resumed.server.owned).toEqual([true, true, true]);
    await expect.poll(async () => {
      await frames(client, 10);
      return (await client.run(READ_CLIENT)).overlay;
    }, { timeout: 8_000, message: "the non-RTS book gesture hint from Tab must expire before the next order" }).toBe("");

    await bindOne(residents);
    before = (await client.run(READ_CLIENT)).control.request;
    // Leaving the tool resets the preference; repeated RMB orders keep the new mode.
    await input.key(client, { keyCode: 71 });
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(attackPoint, true);
    const rightAttack = await routeAck(client, server, context, routeRequest, "WAYPOINT", [attackPoint], -1, true);
    expectMode(rightAttack.client, true);
    routeRequest = rightAttack.client.route.request;
    await groundClick(b);
    const repeatedAttack = await routeAck(client, server, context, routeRequest, "MOVE", [b], -1, true);
    expectMode(repeatedAttack.client, true);
    await input.key(client, { keyCode: 74 });
    const released = await waitFor("ClaimRelease removes the selected residents from every control reference", client, server, context,
      value => value.client.control.request > before && value.client.control.accepted && !value.client.controlPending
        && value.client.selected.length === 0 && value.server.selection.length === 0
        && value.server.groups.every(group => group.length === 0)
        && value.server.owned.every(owned => !owned) && value.server.reserved.every(reserved => !reserved)
        && value.server.memberGroups.every(group => group === "") && value.server.orders.every(order => order === "NONE"));
    expect(released.client.overlay).toBe("");
    expect(released.client.failure).toBe("");
    expectMode(released.client, true);

    // Closing V suspends the tool, not the squad. An unbound squad of A and B is sent round a loop; C stays put.
    const members = residents.slice(0, 2);
    await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 3 });
    await server.run(SET_RESIDENT_AI, { residents, enabled: false });
    await tick.sprint(server, 3);
    await frames(client, 10);
    before = (await client.run(READ_CLIENT)).control.request;
    const spread = await boxAround(client, origin, residents);
    await input.drag(client, { from: spread.from, to: spread.to, steps: 8, button: "left" });
    await selection(client, server, context, residents, before);
    before = (await client.run(READ_CLIENT)).control.request;
    await shiftClickThird();
    await selection(client, server, context, members, before);
    await server.run(SET_RESIDENT_AI, { residents: members, enabled: true });
    await input.key(client, { keyCode: 71 });
    expectMode(await client.run(READ_CLIENT), false);
    routeRequest = (await client.run(READ_CLIENT)).route.request;
    await groundClick(a);
    await routeAck(client, server, context, routeRequest, "MOVE", [a], -1, false);
    for (const [point, cycleStart, points] of [[b, -1, [a, b]], [c, -1, [a, b, c]], [b, 1, [a, b, c]]] as const) {
      routeRequest = (await client.run(READ_CLIENT)).route.request;
      await groundClick(point, true);
      await routeAck(client, server, context, routeRequest, "WAYPOINT", [...points], cycleStart, false);
    }
    const patrolling = await waitFor("the unbound squad runs its loop", client, server, context,
      value => value.server.phase !== "IDLE" && value.server.orders.slice(0, 2).every(order => order !== "NONE"));
    expect(patrolling.server.groups.every(group => group.length === 0), "the squad is bound to no Ctrl+number slot").toBe(true);
    const looping = patrolling.server;

    before = patrolling.client.control.request;
    await tapV(client);
    const suspended = await waitFor("closing V keeps the squad's selection, route and orders", client, server, context,
      value => value.client.control.request > before && !value.client.controlPending
        && same(value.server.selection, members) && value.server.group === looping.group);
    expect(suspended.server.groups.every(group => group.length === 0), "suspending invents no shortcut group").toBe(true);
    expectRunning(suspended.server, looping);

    // Out of the client's tracking range, with the book put away for longer than the 100-tick input lease.
    await mustCommand(server, `forceload add ${origin.x - 19} ${origin.z - 19} ${origin.x + 19} ${origin.z + 19}`);
    const remote = { x: origin.x + 400, y: origin.y, z: origin.z + 400 };
    await mustCommand(server, `forceload add ${remote.x - 3} ${remote.z - 3} ${remote.x + 3} ${remote.z + 3}`);
    await tick.sprint(server, 2);
    await layFloor(server, { x: remote.x - 3, z: remote.z - 3 }, { x: remote.x + 3, z: remote.z + 3 }, remote.y,
      "minecraft:smooth_stone", 4);
    await mustCommand(server, `tp ${player.name} ${remote.x + 0.5} ${remote.y} ${remote.z + 0.5}`);
    await frames(client, 20);
    expect((await client.run(READ_TRACKING, { residents: members })).tracked).toEqual([]);
    await mustCommand(server, `item replace entity ${player.name} weapon.mainhand with minecraft:air`);
    for (let interval = 0; interval < 6; interval++) {
      await frames(client, 20);
      await tick.sprint(server, 20);
    }
    expectRunning(await server.run(READ_SERVER, context), looping);

    await mustCommand(server, `item replace entity ${player.name} weapon.mainhand with folkways_core:colony_book`);
    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    const reopened = await waitFor("reopening V restores the selection and route of the unseen squad", client, server, context,
      value => value.client.combat && !value.client.controlPending && same(value.client.control.selection, members)
        && value.client.cachedPoints === 3 && value.client.cachedCommand === looping.command);
    expect(reopened.client.control.groups.every(group => group.length === 0)).toBe(true);
    expect((await client.run(READ_TRACKING, { residents: members })).tracked).toEqual([]);
    for (let interval = 0; interval < 3; interval++) {
      await frames(client, 20);
      await tick.sprint(server, 20);
    }
    const held = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
    expect(same(held.client.control.selection, members), "the open tool keeps the untracked squad selected").toBe(true);
    expect(held.client.cachedPoints).toBe(3);
    expect(held.client.cachedCommand).toBe(looping.command);
    expectRunning(held.server, looping);

    // Back in sight: once the squad is bound to Ctrl+1, a temporary selection of C alone is dropped on close.
    await tapV(client);
    await mustCommand(server, home);
    await expect.poll(async () => {
      await tick.sprint(server, 2);
      await frames(client, 5);
      return (await client.run(READ_TRACKING, { residents })).tracked.length;
    }, { timeout: 15_000, message: "the residents must be client-tracked again before they are clicked" }).toBe(3);
    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await waitFor("the squad is selected again after reopening V", client, server, context,
      value => value.client.combat && !value.client.controlPending && same(value.client.selected, members));
    const bound = await bindOne(members);
    expectRunning(bound.server, looping);
    before = bound.client.control.request;
    const alone = await project(client, origin, third);
    visible(alone);
    await input.click(client, alone, { button: "left" });
    const temporary = await selection(client, server, context, [third], before);
    await tapV(client);
    const dropped = await waitFor("closing V drops the temporary selection and keeps the bound squad", client, server, context,
      value => value.client.control.request > temporary.client.control.request && !value.client.controlPending
        && value.server.selection.length === 0 && same(value.server.groups[0] ?? [], members));
    expectRunning(dropped.server, looping);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// The squad that was looping when the tool was suspended is the same group on the same order, still running.
function expectRunning(state: ServerState, looping: ServerState) {
  expect(state.group).toBe(looping.group);
  expect(state.command).toBe(looping.command);
  expect(state.phase).not.toBe("IDLE");
  expect(state.orders.slice(0, 2).every(order => order !== "NONE"), `orders: ${state.orders}`).toBe(true);
}

const SET_RESIDENT_AI = defineTask<{ residents: string[]; enabled: boolean }, boolean>({
  name: "folkways.combat-rts-input.set-resident-ai", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.Mob"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();
for(var id:a.list("residents")){
  Mob mob=null;
  for(var level:server.getAllLevels()){
    var entity=level.getEntity(UUID.fromString((String)id));
    if(entity instanceof Mob found){mob=found;break;}
  }
  if(mob==null){throw new IllegalStateException("missing fixture resident "+id);}
  mob.getNavigation().stop();
  mob.setDeltaMovement(0,0,0);
  mob.setNoAi(!Boolean.TRUE.equals(a.value("enabled")));
}
return true;`,
  }),
});

function expectMode(state: ClientState, attack: boolean) {
  expect(state.attackMode).toBe(attack);
  expect(state.modeLabel).toBe(attack ? "Attack move" : "Move");
  expect(state.modeHint).toContain("Move / Attack move");
}

function same(actual: string[], expected: string[]) {
  const a = [...actual].sort(), b = [...expected].sort();
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

function samePoints(actual: Vec[], expected: Vec[]) {
  return actual.length === expected.length && actual.every((point, index) =>
    point.x === expected[index].x && point.y === expected[index].y && point.z === expected[index].z);
}

function visible(at: Pixel) {
  expect(at.x, "input must lie inside the rendered viewport").toBeGreaterThan(5);
  expect(at.x).toBeLessThan(at.width - 5);
  expect(at.y, "input must avoid the toolbar").toBeGreaterThan(40);
  expect(at.y, "world input must avoid the 60px cards").toBeLessThan(at.height - 61);
}

async function project(client: MinecraftClient, point: Vec, entity = ""): Promise<Pixel> {
  return client.run(PROJECT, { ...point, entity });
}

// A drag box around the residents as the client draws them now.
async function boxAround(client: MinecraftClient, origin: Vec, residents: string[]) {
  const boxes = await Promise.all(residents.map(entity => project(client, origin, entity)));
  const from = { x: Math.min(...boxes.map(box => box.left)) - 3, y: Math.min(...boxes.map(box => box.top)) - 3 };
  const to = { x: Math.max(...boxes.map(box => box.right)) + 3, y: Math.max(...boxes.map(box => box.bottom)) + 3 };
  visible({ ...boxes[0], x: from.x, y: from.y });
  visible({ ...boxes[0], x: to.x, y: to.y });
  return { boxes, from, to };
}

async function failureExpired(client: MinecraftClient) {
  await expect.poll(async () => {
    await frames(client, 6);
    return (await client.run(READ_CLIENT)).failure;
  }, { timeout: 6_000, message: "failure text must expire after about three wall-clock seconds" }).toBe("");
  const expired = await client.run(READ_CLIENT);
  expect(expired.combat).toBe(true);
  expect(expired.failureRemaining).toBe(0);
}

async function waitFor(label: string, client: MinecraftClient, server: MinecraftServer, context: Context,
  predicate: (value: Observation) => boolean, attempts = 45, ticks = 1): Promise<Observation> {
  let latest: Observation | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await tick.sprint(server, ticks);
    await frames(client, 3);
    latest = { client: await client.run(READ_CLIENT), server: await server.run(READ_SERVER, context) };
    expect(latest.server.phase, `${label}: genuine group failure, ${JSON.stringify(latest.server)}`).not.toBe("FAILED_WAITING");
    if (predicate(latest)) { return latest; }
  }
  throw new Error(`${label} did not converge: ${JSON.stringify(latest)}`);
}

async function selection(client: MinecraftClient, server: MinecraftServer, context: Context, ids: string[], after: number) {
  return waitFor("selection ACK and exclusive ownership", client, server, context, value =>
    value.client.control.request > after && value.client.control.accepted && !value.client.controlPending
      && same(value.client.selected, ids) && same(value.client.control.selection, ids) && same(value.server.selection, ids));
}

async function routeAck(client: MinecraftClient, server: MinecraftServer, context: Context, after: number,
  intent: string, points: Vec[], cycleStart: number, attackMove: boolean) {
  const accepted = await waitFor(`${intent} accepted with exact authored points`, client, server, context, value =>
    value.client.route.request > after && value.client.route.accepted && value.client.route.intent === intent
      && !value.client.routePending && !value.client.replacementPending
      && samePoints(value.client.route.points, points) && samePoints(value.server.points, points)
      && value.server.cycleStart === cycleStart && value.client.route.cycleStart === cycleStart
      && value.server.attackMove === attackMove && value.server.command === value.client.route.command);
  expect(accepted.client.overlay, `${intent} acceptance must not emit action-bar feedback`).toBe("");
  expect(accepted.client.failure, `${intent} acceptance must not create failure feedback`).toBe("");
  return accepted;
}

const GROUP_EXISTS = defineTask<{ colony: string; group: string }, boolean>({
  name: "folkways.combat-rts-input.observe-superseded-squad", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.rts.combat.group.UnitGroups"],
    body: `var a=Args.of(ctx);var colony=Colonies.of((MinecraftServer)ctx.server(),UUID.fromString(a.string("colony"))).orElseThrow();
return UnitGroups.of(colony).group(UUID.fromString(a.string("group"))).isPresent();`,
  }),
});

const READ_SERVER = defineTask<Context, ServerState>({
  name: "folkways.combat-rts-input.observe-server", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.LinkedHashMap", "net.minecraft.server.MinecraftServer",
      "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.resident.api.Bodies",
      "io.github.izakyl.folkways.core.rts.combat.group.UnitGroups", "io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands",
      "io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=a.strings("residents").stream().map(UUID::fromString).toList();var ownership=CombatSelectionOwnership.of(colony);
var snapshot=ownership.snapshot(player);var groups=UnitGroups.of(colony);var commands=ResidentCommands.of(colony);
var group=ids.stream().map(groups::ofResident).flatMap(java.util.Optional::stream).findFirst().orElse(null);
var out=new LinkedHashMap<String,Object>();
out.put("selection",snapshot.selection().stream().map(Object::toString).toList());
out.put("groups",snapshot.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList());
out.put("owned",ids.stream().map(id->{var entity=player.serverLevel().getEntity(id);return entity!=null&&Bodies.of(entity).filter(body->ownership.owns(player,body)).isPresent();}).toList());
out.put("reserved",ids.stream().map(ownership::reserved).toList());
out.put("memberGroups",ids.stream().map(id->groups.ofResident(id).map(g->g.id().toString()).orElse("")).toList());
out.put("orders",ids.stream().map(id->commands.current(id).map(o->o.mode().name()+":"+o.status().name()).orElse("NONE")).toList());
out.put("group",group==null?"":group.id().toString());out.put("command",group==null?"":group.commandId().map(Object::toString).orElse(""));
out.put("kind",group==null?"NONE":group.commandKind().name());out.put("phase",group==null?"NONE":group.phase().name());
out.put("result",group==null?"NONE":group.endReason().name());out.put("members",group==null?List.of():group.members().stream().map(Object::toString).toList());
out.put("points",group==null?List.of():group.pointRoute().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList());
out.put("cycleStart",group==null?-1:group.cycleStart());out.put("complete",group!=null&&group.routeComplete());out.put("attackMove",group!=null&&group.attackMove());
out.put("stop",group==null?-1:group.patrolStop());out.put("waiting",group==null?0:group.waitingMembers());return out;`,
  }),
});

// Settings and window setup only; all command actions above enter through real input events.
const KEY_SETTINGS = defineTask<{ stop: number }, {
  codes: number[]; categories: string[]; conflicts: string[]; hintBindings: string[];
}>({
  name: "folkways.combat-rts-input.key-settings", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.Map", "java.util.Arrays", "net.minecraft.client.Minecraft",
      "com.mojang.blaze3d.platform.InputConstants", "net.minecraft.client.KeyMapping",
      "io.github.izakyl.folkways.core.rts.client.CombatKeys", "io.github.izakyl.folkways.core.front.api.ui.OverheadTools"],
    body: `
CombatKeys.STOP.setKey(InputConstants.Type.KEYSYM.getOrCreate((int) Args.of(ctx).integer("stop")));
KeyMapping.resetMapping();
var keys = List.of(CombatKeys.MOVE_MODE, CombatKeys.STOP, CombatKeys.RELEASE);
var conflicts = Arrays.stream(Minecraft.getInstance().options.keyMappings)
    .filter(k -> !keys.contains(k) && keys.stream().anyMatch(r -> r.getKey().equals(k.getKey())))
    .map(k -> k.getName() + ":" + k.getKey().getName()).toList();
var hints = OverheadTools.selectedHandler().orElseThrow().cues().stream()
    .map(c -> c.binding() == null ? "" : c.binding()).toList();
return Map.of("codes", keys.stream().map(k -> k.getKey().getValue()).toList(),
    "categories", keys.stream().map(KeyMapping::getCategory).toList(), "conflicts", conflicts, "hintBindings", hints);`,
  }),
});

// Invalid ground/mob targets and stale packets exercise real server refusal paths without new UI controls.
const REJECTED_PACKET = defineTask<Vec & { intent: string }, boolean>({
  name: "folkways.combat-rts-input.probe-failure-only-rejections", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.core.world.api.WorldPos", "io.github.izakyl.folkways.core.rts.combat.net.CombatPackets",
      "io.github.izakyl.folkways.core.rts.combat.net.CombatControlPacket",
      "io.github.izakyl.folkways.core.rts.combat.net.CombatStopPacket", "net.neoforged.neoforge.network.PacketDistributor"],
    body: `
var a = Args.of(ctx);
var mc = Minecraft.getInstance();
var current = CombatPackets.controls().orElseThrow();
var ids = current.selection();
var invalid = WorldPos.of(mc.level, new BlockPos((int) a.integer("x"), (int) a.integer("y") + 20,
    (int) a.integer("z")));
return switch (a.string("intent")) {
    case "MOVE" -> CombatPackets.submit(ids, List.of(invalid), -1, false);
    case "ATTACK" -> CombatPackets.attack(ids, UUID.randomUUID());
    case "WAYPOINT" -> CombatPackets.append(ids, invalid, false);
    case "STOP" -> {
        PacketDistributor.sendToServer(new CombatStopPacket(1, current.colonyId(), ids));
        yield true;
    }
    case "CONTROL" -> {
        PacketDistributor.sendToServer(new CombatControlPacket(1, current.colonyId(),
            CombatControlPacket.Operation.RELEASE, 0, ids));
        yield true;
    }
    default -> false;
};`,
  }),
});

const READ_TRACKING = defineTask<{ residents: string[] }, { tracked: string[] }>({
  name: "folkways.combat-rts-input.client-tracking", side: "client",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.client.Minecraft"],
    body: `
var mc=Minecraft.getInstance();var requested=new java.util.HashSet<>(Args.of(ctx).strings("residents").stream().map(UUID::fromString).toList());
var tracked=new java.util.ArrayList<String>();
if(mc.level!=null){for(var entity:mc.level.entitiesForRendering()){if(requested.contains(entity.getUUID()))tracked.add(entity.getUUID().toString());}}
return java.util.Map.of("tracked",tracked);`,
  }),
});

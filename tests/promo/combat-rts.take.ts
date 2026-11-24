import { expect, test } from "@playwright/test";
import { camera, input, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { settlementFeet } from "./combat-settlement";
import { frames } from "../shared/harness/bw-helpers";
import { sleepMs, take, type PromoPair } from "./promo-kit";
import { castCombat, openCombat, recordCombat, sampleCombat, spawnWave } from "./combat-scene";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import {
  project,
  READ_CLIENT,
  routeAck,
  same,
  selection,
  squadIntact,
  visible,
  waitFor,
  type Context,
  type Vec,
} from "./combat-rts-observe";

// Only the opening cast is staged. Every selection and command is real client input.
test("combat: close tactical command demonstration", async ({}, testInfo) => {
  const run = take("combat-rts");
  let pair: PromoPair | undefined;
  try {
    const scene = await openCombat(run.scope, run);
    pair = scene.pair;
    const { server, client, name } = scene;
    const cast = await castCombat(scene, "rts");
    run.note("cast", cast);
    run.note("inputContract", {
      box: "Plain LMB drag, exact server selection",
      move: "RMB ground",
      waypoints: "Ctrl+RMB appends two mandatory points; modifier released after each input",
      attackMove: "G persistently toggles movement mode; plain RMB replaces, same-mode Ctrl+RMB appends",
      groups: "Ctrl+1 binds the exact squad; plain LMB narrows selection; 1 recalls it and presses slot 1",
      stop: "H replaces the route with STOP, retaining selection and ownership",
      authority: "Server tasks only stage or observe; all commands come from real client input",
      presentation: "Close tactical camera, paper/wood toolbar; failures and success overlays must remain empty",
    });
    const context: Context = {
      colony: scene.colony,
      player: name,
      residents: cast.residents,
    };
    await world.command(server, `gamemode creative ${name}`);
    const start = scene.at(0, 0, 17);
    await world.command(server, `tp ${name} ${start.x + 0.5} ${start.y} ${start.z + 0.5} 180 0`);
    await camera.hud(client, true);
    await camera.fov(client, 55);
    await tick.sprint(server, 2);
    await frames(client, 10);
    await input.key(client, { keyCode: 86 });
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8000 });
    const combat = (await screen.widgets(client)).find((w) => /^(Control|控制)$/.test(w.label?.trim() ?? ""));
    expect(combat, "combat mode must render").toBeTruthy();
    await screen.clickWidget(client, { label: combat!.label });
    await input.scroll(client, { yOffset: 1 });
    await frames(client, 20);
    await tick.freeze(server, false);
    await waitFor("combat roster", client, server, context, (s) => s.client.combat && s.client.roster === 3);
    await tick.freeze(server, true);
    const groundClick = async (point: Vec, append = false, button: "left" | "right" = "right") => {
      const pixel = await project(client, point);
      visible(pixel);
      await input.click(client, pixel, {
        button,
        modifiers: append ? ["ctrl"] : [],
      });
      expect((await client.run(READ_CLIENT)).failure).toBe("");
    };
    const [a, b, c, target] = await settlementFeet(scene, [
      scene.at(0, 0, 11),
      scene.at(-2, 0, 13),
      scene.at(2, 0, 16),
      scene.at(0, 0, 21),
    ]);
    await client.run(CLEAR_STAGING_MESSAGES);
    const beginning = await sampleCombat(scene);
    const engagement: any[] = [];
    const cleanFrames: any[] = [];
    let recording = true;
    let monitorError: unknown;
    const monitor = (async () => {
      while (recording) {
        const state = await client.run(READ_CLIENT);
        cleanFrames.push({
          at: Date.now(),
          failure: state.failure,
          overlay: state.overlay,
        });
        await sleepMs(180);
      }
    })().catch((error) => {
      monitorError = error;
    });
    try {
      await recordCombat(
        run,
        scene,
        "01-command",
        "Box select, move, Ctrl waypoints, G attack-move and H stop during movement",
        65,
        async () => {
          const boxes = await Promise.all(cast.residents.map((id) => project(client, scene.o, id)));
          run.note("projectedResidents", boxes);
          const from = {
            x: Math.min(...boxes.map((p) => p.left)) - 3,
            y: Math.min(...boxes.map((p) => p.top)) - 3,
          };
          const to = {
            x: Math.max(...boxes.map((p) => p.right)) + 3,
            y: Math.max(...boxes.map((p) => p.bottom)) + 3,
          };
          visible({ ...boxes[0], ...from });
          visible({ ...boxes[0], ...to });
          const request = (await client.run(READ_CLIENT)).control.request;
          await input.drag(client, { from, to, steps: 12, button: "left" });
          run.note("selection", await selection(client, server, context, cast.residents, request));
          const binding = (await client.run(READ_CLIENT)).control.request;
          await input.key(client, { keyCode: 49, modifiers: ["ctrl"] });
          run.note(
            "groupBound",
            await waitFor(
              "Ctrl+1 binding",
              client,
              server,
              context,
              (s) =>
                s.client.control.request > binding &&
                s.client.control.accepted &&
                !s.client.controlPending &&
                same(s.client.control.groups[0], cast.residents) &&
                same(s.server.groups[0], cast.residents),
            ),
          );
          const narrowed = (await client.run(READ_CLIENT)).control.request;
          const first = await project(client, scene.o, cast.residents[0]);
          await input.click(client, first, { button: "left" });
          await selection(client, server, context, [cast.residents[0]], narrowed);
          await sleepMs(1000);
          expect((await client.run(READ_CLIENT)).buttons.some((p) => p.texture === "pressed")).toBe(false);
          const recall = (await client.run(READ_CLIENT)).control.request;
          await input.key(client, { keyCode: 49 });
          run.note("groupRecalled", await selection(client, server, context, cast.residents, recall));
          await frames(client, 10);
          const grouped = await client.run(READ_CLIENT);
          expect(grouped.buttons.filter((p) => p.kind === "GROUP")).toHaveLength(9);
          expect(grouped.buttons.filter((p) => p.texture === "pressed").map((p) => p.slot)).toEqual([1]);
          run.note("pressedGroup", grouped);
          run.note("movementModeBefore", grouped);
          expect(grouped.attackMode).toBe(false);
          expect(grouped.modeBold).toEqual([true, false]);
          await sleepMs(1000);
          let route = (await client.run(READ_CLIENT)).route.request;
          await groundClick(a);
          const moved = await routeAck(client, server, context, route, "MOVE", [a], -1, false);
          run.note("moveAccepted", moved);
          run.note(
            "moveExecuted",
            await waitFor(
              "move assembly",
              client,
              server,
              context,
              (s) => s.server.complete && s.server.phase === "HOLDING" && squadIntact(s, context),
            ),
          );
          for (const [i, point] of [b, c].entries()) {
            route = (await client.run(READ_CLIENT)).route.request;
            await groundClick(point, true);
            const accepted = await routeAck(
              client,
              server,
              context,
              route,
              "WAYPOINT",
              [a, b, c].slice(0, i + 2),
              -1,
              false,
            );
            expect(accepted.server.command).toBe(moved.server.command);
            run.note(`waypoint${i + 1}`, accepted);
          }
          run.note(
            "waypointsExecuted",
            await waitFor(
              "route assembly",
              client,
              server,
              context,
              (s) => s.server.complete && s.server.phase === "HOLDING" && squadIntact(s, context),
            ),
          );
          run.note("wave", await spawnWave(scene, 6, 20, 20));
          await input.key(client, { keyCode: 71 });
          const toggled = await client.run(READ_CLIENT);
          expect(toggled.attackMode).toBe(true);
          expect(toggled.modeBold).toEqual([false, true]);
          run.note("movementModeToggled", toggled);
          await sleepMs(1500);
          route = (await client.run(READ_CLIENT)).route.request;
          await groundClick(target);
          const attack = await routeAck(client, server, context, route, "MOVE", [target], -1, true);
          expect(attack.server.kind).toBe("ATTACK_MOVE");
          expect(attack.client.attackMode).toBe(true);
          run.note("attackMoveAccepted", attack);
          run.note(
            "attackMoveExecuted",
            await waitFor(
              "attack and assembly",
              client,
              server,
              context,
              (s) => s.server.complete && s.server.phase === "HOLDING" && squadIntact(s, context),
              400,
              1,
              (s) => {
                if (s.server.phase === "ENGAGING") {
                  engagement.push(s);
                }
              },
            ),
          );
          await input.key(client, { keyCode: 71 });
          expect((await client.run(READ_CLIENT)).attackMode).toBe(false);
          route = (await client.run(READ_CLIENT)).route.request;
          await groundClick(a);
          const returning = await routeAck(client, server, context, route, "MOVE", [a], -1, false);
          run.note(
            "movingBeforeStop",
            await waitFor(
              "moving before H",
              client,
              server,
              context,
              (s) =>
                s.server.command === returning.server.command &&
                !s.server.complete &&
                ["LINE", "COLUMN", "REFORMING"].includes(s.server.phase) &&
                squadIntact(s, context),
            ),
          );
          await sleepMs(700);
          route = (await client.run(READ_CLIENT)).route.request;
          await input.key(client, { keyCode: 72 });
          const stopped = await waitFor(
            "H stop",
            client,
            server,
            context,
            (s) =>
              s.client.route.request > route &&
              s.client.route.accepted &&
              s.client.route.intent === "STOP" &&
              !s.client.routePending &&
              s.server.kind === "STOP" &&
              squadIntact(s, context),
          );
          expect(stopped.server.command).not.toBe(attack.server.command);
          run.note("stopAccepted", stopped);
          await sleepMs(1000);
          const held = await sampleCombat(scene);
          await sleepMs(1800);
          const still = await sampleCombat(scene);
          const drift = still.crew.map((p: any) => {
            const initial = held.crew.find((one: any) => one.uuid === p.uuid);
            return Math.hypot(p.pos.x - initial.pos.x, p.pos.z - initial.pos.z);
          });
          expect(drift.every((distance: number) => distance < 0.5)).toBe(true);
          run.note("stoppedPositions", { held, still, drift });
        },
      );
    } finally {
      recording = false;
      await monitor;
      run.note("cleanFrames", cleanFrames);
    }
    expect(monitorError, "feedback monitor covers recording start through stop").toBeUndefined();
    expect(cleanFrames.every((s) => s.failure === "" && s.overlay === "")).toBe(true);
    const after = await sampleCombat(scene);
    const kills = after.crew.reduce((n: number, p: any) => n + p.kills, 0);
    const displacement = after.crew.map((p: any) => {
      const initial = beginning.crew.find((one: any) => one.uuid === p.uuid);
      return Math.hypot(p.pos.x - initial.pos.x, p.pos.z - initial.pos.z);
    });
    run.note("summary", {
      selected: cast.residents.length,
      movePoints: 1,
      waypointPoints: 3,
      attackMovePoints: 1,
      residentKills: kills,
      engagementSamples: engagement.length,
      displacement,
      after,
    });
    expect(engagement.length).toBeGreaterThan(0);
    expect(kills).toBeGreaterThan(0);
    expect(displacement.every((distance: number) => distance > 3)).toBe(true);
    run.expectTaken(["01-command"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

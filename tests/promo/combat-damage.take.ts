import { expect, test } from "@playwright/test";
import { clickElement, waitForElement } from "../ldlib2";
import { camera, input, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { promoClip, take, sleepMs, type PromoPair } from "./promo-kit";
import { castCombat, openCombat } from "./combat-scene";
import { licenseCrewFast } from "./fast-setup";
import { CLEAR_STAGING_MESSAGES } from "./combat-presentation";
import { frames } from "../shared/harness/bw-helpers";
import {
  DAMAGE_BASELINE,
  DAMAGE_PANEL_INPUT,
  DAMAGE_KITS,
  DAMAGE_STAGE,
  DAMAGE_STATE,
  DAMAGE_PHASE,
  DAMAGE_PLANS,
  DAMAGE_HUD,
  DAMAGE_DIAGNOSTIC,
  DAMAGE_PAUSE,
} from "./combat-recovery";

// Only the aftermath and finite resources are staged; native workers suppress and restore the baseline.
test("combat: extinguisher, hose and blast-crater recovery in damage-control mode", async ({}, testInfo) => {
  const run = take("combat-damage");
  run.note("verified", false);
  let pair: PromoPair | undefined;
  try {
    const settlement = await openCombat(run.scope, run);
    pair = settlement.pair;
    const o = settlement.at(18, 0, -18);
    const scene = {
      ...settlement,
      o,
      at: (x: number, y: number, z: number) => ({
        x: o.x + x,
        y: o.y + y,
        z: o.z + z,
      }),
    };
    await scene.fill(scene.at(-15, -3, -11), scene.at(15, 8, 10), "minecraft:air");
    await scene.fill(scene.at(-15, -3, -11), scene.at(15, -2, 10), "minecraft:stone");
    await scene.fill(scene.at(-15, -1, -11), scene.at(15, -1, 10), "minecraft:stone_bricks");
    await scene.fill(scene.at(-14, 0, -8), scene.at(14, 7, -8), "minecraft:stone_bricks");
    for (const x of [-14, 14]) {
      await scene.fill(scene.at(x, 0, -8), scene.at(x, 3, 5), "minecraft:stone_bricks");
    }
    await scene.fill(scene.at(5, -1, -7), scene.at(12, -1, 0), "minecraft:oak_planks");
    await scene.fill(scene.at(12, 0, -7), scene.at(12, 3, -2), "minecraft:oak_planks");
    await scene.fill(scene.at(5, 4, -7), scene.at(12, 4, -5), "minecraft:oak_planks");
    for (const z of [-7, -1]) {
      await scene.fill(scene.at(5, 0, z), scene.at(5, 3, z), "minecraft:oak_planks");
    }
    await scene.fill(scene.at(-15, 0, 11), scene.at(15, 12, 33), "minecraft:air");
    await scene.fill(scene.at(-15, -2, 11), scene.at(15, -1, 33), "minecraft:stone_bricks");
    await scene.set(scene.at(-12, 0, 8), "minecraft:barrel");
    const cast = await castCombat(scene, "fire");
    run.note("cast", cast);
    const args = { colony: scene.colony, ...o, residents: cast.residents };
    run.note("kits", await scene.server.run(DAMAGE_KITS, args));
    const fixture = await scene.server.run(DAMAGE_BASELINE, args);
    // Settlement cameras leave the player in spectator mode, which cannot use the colony book.
    await world.command(scene.server, `gamemode creative ${scene.name}`);
    await screen.dismiss(scene.client);
    await input.key(scene.client, { keyCode: 86 });
    await screen.waitFor(scene.client, "OverheadScreen");
    const damageMode = (await screen.widgets(scene.client)).find(widget =>
      /^(损管|Damage Control)$/.test(widget.label?.trim() ?? ""));
    expect(damageMode).toBeTruthy();
    await screen.clickWidget(scene.client, { label: damageMode!.label });
    const baselineEye = scene.at(0, 30, 24);
    await camera.set(scene.client, {
      position: baselineEye,
      ...cameraLookingAt(baselineEye, scene.at(0, 2, 0)),
      fov: 65,
    });
    await tick.sprint(scene.server, 25);
    await frames(scene.client, 8);
    const baselineView = await screen.current(scene.client);
    const baselinePointer = { x: baselineView!.width / 2, y: baselineView!.height / 2 };
    await input.move(scene.client, baselinePointer);
    await expect.poll(async () => {
      await tick.sprint(scene.server, 3);
      await frames(scene.client, 3);
      return await scene.client.run(DAMAGE_PANEL_INPUT);
    }).toEqual({ creative: true, bookColony: scene.colony, aimedZone: fixture.zone });
    run.note("baselinePanelInput", await scene.client.run(DAMAGE_PANEL_INPUT));
    await input.click(scene.client, baselinePointer, { button: "right" });
    await waitForElement(scene.client, { id: "folkways.card.button.record" });
    await clickElement(scene.client, { id: "folkways.card.button.record" });
    await expect.poll(async () => {
      await tick.sprint(scene.server, 3);
      return (await scene.server.run(DAMAGE_PLANS, args)).baselines;
    }).toBe(1);
    run.note("baseline", fixture);
    await screen.dismiss(scene.client);
    await screen.waitFor(scene.client, "OverheadScreen");
    await input.key(scene.client, { keyCode: 86 });
    await licenseCrewFast(
      scene.server,
      cast.residents.map((uuid) => ({ uuid, trades: [] })),
    );
    await scene.server.run(DAMAGE_PAUSE, { ...args, ...fixture });
    await tick.sprint(scene.server, 30);
    const staged = await scene.server.run(DAMAGE_STAGE, {
      ...args,
      ...fixture,
    });
    run.note("stagingDamage", staged);
    await world.command(scene.server, `gamemode spectator ${scene.name}`);
    await world.command(scene.server, `tp ${scene.name} ${o.x - 3.5} ${o.y} ${o.z + 0.5} 180 0`);
    await camera.hud(scene.client, true);
    await camera.fov(scene.client, 75);
    await tick.sprint(scene.server, 2);
    await input.key(scene.client, { keyCode: 86 });
    await screen.waitFor(scene.client, "OverheadScreen");
    const mode = (await screen.widgets(scene.client)).find((w) =>
      /^(损管|Damage Control)$/.test(w.label?.trim() ?? ""),
    );
    expect(mode).toBeTruthy();
    await screen.clickWidget(scene.client, { label: mode!.label });
    await tick.freeze(scene.server, false);
    await sleepMs(1500);
    await tick.freeze(scene.server, true);
    for (const kind of ["cow", "pig", "sheep", "chicken"]) {
      await world.command(
        scene.server,
        `execute positioned ${o.x} ${o.y} ${o.z} run tp @e[type=minecraft:${kind},distance=..30] ` +
          `${o.x - 35} ${o.y + 2} ${o.z + 40}`,
        { allowFailure: true },
      );
    }
    await world.command(
      scene.server,
      `execute positioned ${o.x} ${o.y} ${o.z} run kill @e[type=minecraft:item,distance=..35]`,
    );
    const cameras = {
      extinguisher: { eye: scene.at(-12, 17, 16), target: scene.at(2, 0, -2), fov: 65 },
      hose: { eye: scene.at(29, 25, 32), target: scene.at(3, 2, 9), fov: 70 },
      repair: { eye: scene.at(-5, 30, -10), target: scene.at(2, 0, -6), fov: 65 },
    };
    const frameRecovery = async (name: keyof typeof cameras) => {
      const pose = cameras[name];
      await camera.set(scene.client, {
        position: pose.eye,
        ...cameraLookingAt(pose.eye, pose.target),
        fov: pose.fov,
      });
      for (const pixel of [
        { x: 120, y: 430 },
        { x: 170, y: 350 },
        { x: 140, y: 250 },
        { x: 400, y: 420 },
        { x: 550, y: 370 },
        { x: 700, y: 300 },
        { x: 450, y: 300 },
        { x: 600, y: 250 },
        { x: 250, y: 400 },
      ]) {
        await input.move(scene.client, pixel);
        await frames(scene.client, 3);
        if ((await scene.client.run(DAMAGE_HUD)).card) {
          run.note(`informationCardPointer-${name}`, pixel);
          return;
        }
      }
      expect((await scene.client.run(DAMAGE_HUD)).card, `${name}: visible native region card`).toBe(true);
    };
    run.note("cameras", cameras);
    for (const name of ["extinguisher", "hose", "repair"] as const) {
      await frameRecovery(name);
      await run.shot(scene.client, `layout-${name}`, {
        subject: "Native damage mode, bounds and information card at the planned camera",
        worldState: { pose: cameras[name] },
      });
    }
    await frameRecovery("extinguisher");
    await frames(scene.client, 12);
    await scene.client.run(CLEAR_STAGING_MESSAGES);
    const before = await scene.server.run(DAMAGE_STATE, {
      ...args,
      ...fixture,
    });
    run.note("before", before);
    expect(before.fires).toBeGreaterThan(80);
    expect(before.missing).toBeGreaterThan(50);
    run.note("presentation", {
      hints: "No separate native hide/fold setting; retain hints and keep the subject to their right",
      framing: "Three damage-mode cameras: close spray, distant hydrant and arc, high-angle material repair",
      hydrant: scene.at(9, 0, 28),
      distance:
        "Paid native hose pulses record eye-to-extinguished-fire distances; every pulse stays at least 20m away",
    });
    await run.shot(scene.client, "00-aftermath", {
      subject: "Preconfigured damage-control view of the irregular breach and fire patches",
      worldState: { before },
    });
    const samples: any[] = [];
    let phase = "extinguisher";
    let completedAt = 0;
    run.note("capturePlan", {
      minimumSeconds: 1,
      watchdogSeconds: 600,
      ending: "Native baseline restoration followed by five seconds; no fixed-duration truncation",
    });
    await promoClip(run, scene.client, scene.server, "01-recovery", {
      seconds: 1,
      fps: 30,
      subject: "Damage-control HUD: a blast crater, native extinguisher and connected hose, then material repairs",
      worldState: { fixture, before, staged },
      during: async () => {
        const start = Date.now();
        let reported = 0;
        await scene.server.run(DAMAGE_PHASE, { ...args, ...fixture, phase });
        while (Date.now() - start < 600_000) {
          const state = await scene.server.run(DAMAGE_STATE, {
            ...args,
            ...fixture,
          });
          const hud = await scene.client.run(DAMAGE_HUD);
          samples.push({ ...state, hud, phase, atMs: Date.now() - start });
          if (Date.now() - start - reported > 5000) {
            run.note("progress", {
              ...state,
              hud,
              phase,
              atMs: Date.now() - start,
            });
            if (phase === "extinguisher" && state.water === before.water) {
              run.note("earlyDiagnostics", await scene.server.run(DAMAGE_DIAGNOSTIC, args));
            }
            reported = Date.now() - start;
          }
          if (phase === "extinguisher" && before.water - state.water >= 30) {
            phase = "hose";
            run.note("hoseHandoff", { ...state, atMs: Date.now() - start });
            await frameRecovery("hose");
            run.note("hoseCameraAtMs", Date.now() - start);
            await scene.server.run(DAMAGE_PHASE, {
              ...args,
              ...fixture,
              phase,
            });
          }
          if (state.fires === 0 && phase === "hose") {
            phase = "repair";
            run.note("repairStart", { ...state, atMs: Date.now() - start });
            await frameRecovery("repair");
            run.note("repairCameraAtMs", Date.now() - start);
            await scene.server.run(DAMAGE_PHASE, {
              ...args,
              ...fixture,
              phase,
            });
          }
          if (state.fires === 0 && state.missing === 0 && completedAt === 0) {
            completedAt = Date.now() - start;
            run.note("completeAtMs", completedAt);
          }
          if (completedAt > 0 && Date.now() - start >= completedAt + 5000) {
            break;
          }
          await sleepMs(180);
        }
      },
    });
    const after = await scene.server.run(DAMAGE_STATE, { ...args, ...fixture });
    run.note("diagnostics", await scene.server.run(DAMAGE_DIAGNOSTIC, args));
    run.note("samples", samples);
    run.note("summary", {
      before,
      after,
      fireBlocksExtinguished: before.fires - after.fires,
      blocksRestored: before.missing - after.missing,
      extinguisherWaterUsed: before.water - after.water,
      hydrantWaterUsed: before.hydrantWater - after.hydrantWater,
      materialsUsed: before.materials - after.materials,
      extinguisherFires: after.extinguisherFires,
      hoseFires: after.hoseFires,
      sharedFires: after.sharedFires,
      hudSamples: samples.length,
      spraySamples: samples.filter((s) => s.hud.sprayParticles > 0).length,
      hoseSamples: samples.filter((s) => s.hud.hoses > 0).length,
      hosePulses: after.hosePulses,
      minimumHoseFireDistance: Math.min(...after.hosePulses.map((p: any) => p.nearestFireDistance)),
      minimumExtinguishedDistance: Math.min(
        ...after.hosePulses.filter((p: any) => p.fires > 0).map((p: any) => p.minExtinguishedDistance),
      ),
      maximumExtinguishedDistance: Math.max(...after.hosePulses.map((p: any) => p.maxExtinguishedDistance)),
    });
    expect(after.fires).toBe(0);
    expect(after.missing).toBe(0);
    expect(after.water).toBeLessThan(before.water);
    expect(after.hydrantWater).toBeLessThan(before.hydrantWater);
    expect(after.extinguisherFires).toBeGreaterThan(0);
    expect(after.hoseFires).toBeGreaterThan(0);
    expect(after.hosePulses.length).toBeGreaterThan(0);
    expect(after.hosePulses.every((p: any) => p.nearestFireDistance >= 20)).toBe(true);
    expect(after.hosePulses.reduce((sum: number, p: any) => sum + p.waterUsed, 0)).toBe(
      before.hydrantWater - after.hydrantWater,
    );
    expect(after.sharedFires, "each extinguished block must have one unambiguous paid tool").toBe(0);
    expect(after.extinguisherFires + after.hoseFires).toBe(before.fires - after.fires);
    expect(after.materials).toBeLessThan(before.materials);
    expect(samples.every((s) => s.hud.active && s.hud.area && s.hud.card && !s.hud.hidden)).toBe(true);
    expect(samples.some((s) => s.hud.sprayParticles > 0)).toBe(true);
    expect(samples.some((s) => s.hud.hoses > 0)).toBe(true);
    run.expectTaken(["01-recovery"]);
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

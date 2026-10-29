import { expect, test, type TestInfo } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { input, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

import { SETUP, READ, RENDER, STOP_AUTONOMY, CLEANUP, GUI_SCALE, INPUT_STATE, CONTROL_STATE, RELEASE_BINDING,
  type Scene, type Reading, type Render, type Evidence, type Pixel, type InputState } from "../shared/combat/epicfight-native";
import { PROJECT } from "../shared/combat/rts-fixtures";

// Java tasks arrange actual world entities and production orders, or read existing state.
// They never write a renderer observation, animation state, or a combat network payload.
test("Epic Fight without RTS renders real resident skins and lands native sword and spear collisions", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, active: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight", server: { instance: `epicfight-native-server-${run}` },
      client: { instance: `epicfight-native-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin);
    await mustCommand(pair.server, `gamemode spectator ${player.name}`);
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + .5} 0 10`);
    for (const weapon of ["minecraft:iron_sword", "epicfight:iron_spear"] as const) {
      active = await pair.server.run(SETUP, { ...origin, weapon, colony: "", player: player.name, autonomous: true });
      const initial = await pair.server.run(READ, active);
      evidence.push({ step: `${weapon} initial real stack`, value: initial });
      expect(initial.patch).toBe(true);
      expect(initial.rts, "the actual server has no RTS dependency").toBe(false);
      expect(initial.weaponCount).toBe(1);
      expect(initial.weaponDamage).toBe(9);
      expect(initial.components).toBe(true);
      expect(initial.targetHealth).toBe(initial.targetMaxHealth);
      expect(initial.targetHasAi).toBe(false);
      let sawNativeAttack = false;
      const hit = await observe(pair, active, evidence, `${weapon} native collision`, row => {
        sawNativeAttack ||= row.sessionType.endsWith(".EpicFightWeaponSession") && row.attackAnimation && row.nativeTarget;
        return sawNativeAttack && row.attacker && row.targetHealth < initial.targetHealth && row.weaponDamage > 9;
      }, 450);
      expect(hit.residentHealth).toBe(20); expect(hit.staffed, "a real production patrol assignment owns this autonomous engagement").toBe(true);
      const rendered = await render(pair, active, evidence);
      expect(rendered.rts, "this profile must exercise the optional integration with no RTS module").toBe(false);
      expect(rendered.texture).toBe("minecraft:textures/entity/player/wide/steve.png");
      expect(rendered.sourceRenderer).toBe("io.github.izakyl.folkways.plugins.person.ResidentSkinRenderer");
      expect(rendered.mesh, "Steve's wide skin uses EF's real native biped mesh").toBe("epicfight:entity/biped");
      await capture(pair.client, info, `${weapon.replace(":", "-")}-native-hit-rendered.png`);
      // Withdraw the actual patrol staffing and relocate its threat; the ordinary runner closes its hand loan.
      await pair.server.run(STOP_AUTONOMY, active);
      const released = await observe(pair, active, evidence, `${weapon} release native hand`, row => !row.session && !row.authorized && row.weaponPlace.startsWith("pack:"), 160);
      for (let elapsed = 0; elapsed < 80; elapsed += 2) {
        await tick.sprint(pair.server, 2);
        const row = await pair.server.run(READ, active);
        evidence.push({ step: `${weapon} after cancellation ${elapsed + 2}`, value: row });
        physical(row);
        expect(row.targetHealth, "no delayed native collision remains after the command was replaced").toBe(released.targetHealth);
        expect(row.weaponDamage).toBe(released.weaponDamage);
        expect(row.session).toBe(false);
        expect(row.authorized).toBe(false);
      }
      await pair.server.run(CLEANUP, active); active = undefined;
    }
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-native-failure.png"); } catch { /* Keep the first failure. */ } }
    throw error;
  }
  finally {
    await saveEvidence(info, "epicfight-native-evidence.json", evidence);
    if (pair && active) { try { await pair.server.run(CLEANUP, active); } catch { /* Keep the first failure. */ } }
    await pair?.teardown(info);
  }
});

test("Epic Fight V command pointer MOVE cancels a real pending native attack and physically arrives without ownership leaks", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, scene: Scene | undefined;
  let revoked: { orderId: string; health: number; damage: number } | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight-acceptance", server: { instance: `epicfight-move-server-${run}` },
      client: { instance: `epicfight-move-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    await pair.client.run(GUI_SCALE);
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin); await mustCommand(pair.server, `gamemode survival ${player.name}`);
    const founded = await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
    await openColonyPanelViaBook(pair.server, pair.client, player.name, founded.standBlock);
    await screen.dismiss(pair.client);
    scene = await pair.server.run(SETUP, { ...origin, colony: String(founded.evidence.colonyId), player: player.name, weapon: "minecraft:iron_sword", autonomous: false });
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + .5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(pair.server, 2); await frames(pair.client, 8);
    await input.key(pair.client, { keyCode: 86 });
    await screen.waitFor(pair.client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(pair.client)).find(widget => /^RTS$/.test(widget.label?.trim() ?? ""));
    expect(combat, "the combined profile exposes the real RTS combat tab").toBeTruthy();
    await screen.clickWidget(pair.client, { label: combat!.label });
    await waitInput(pair, scene, evidence, "real V combat roster", value => value.combat && value.roster >= 1);
    const at = await pair.client.run(PROJECT, { ...origin, entity: scene.resident }); visible(at);
    await input.click(pair.client, at, { button: "left" });
    await waitInput(pair, scene, evidence, "real left-click selects exactly the native resident", value =>
      value.selected.length === 1 && value.selected[0] === scene!.resident && value.selection.length === 1
      && value.selection[0] === scene!.resident && value.owned && value.reserved);

    const enemy = await pair.client.run(PROJECT, { ...origin, entity: scene.monster }); visible(enemy);
    const beforeAttack = (await pair.client.run(INPUT_STATE)).routeRequest;
    await input.click(pair.client, enemy, { button: "right" });
    await waitInput(pair, scene, evidence, "right-click hostile receives authoritative ATTACK ACK", value =>
      value.routeRequest > beforeAttack && value.accepted && value.intent === "ATTACK" && !value.pending);
    const pending = await observe(pair, scene, evidence, "real selected sword enters native pre-contact windup", row =>
      row.order === "ATTACK" && row.sessionType.endsWith(".EpicFightWeaponSession") && row.sessionState === "FIRING"
      && row.attackAnimation && row.nativeTarget && row.authorized && row.preContact, 260);
    expect(pending.weaponDamage, "cancelled native windup has not spent its real item yet").toBe(9);
    expect(pending.targetHealth).toBe(pending.targetMaxHealth);
    // Project and click with the server frozen at the observed native pre-contact frame.
    const destination = { x: origin.x - 5, y: origin.y, z: origin.z + 13 };
    const ground = await pair.client.run(PROJECT, { ...destination, entity: "" }); visible(ground);
    const beforeMove = (await pair.client.run(INPUT_STATE)).routeRequest;
    revoked = { orderId: pending.orderId, health: pending.targetHealth, damage: pending.weaponDamage };
    scene.cancelled = revoked;
    await input.click(pair.client, ground, { button: "right" });
    const moveAck = await waitInput(pair, scene, evidence, "real right-click ground replaces native attack with MOVE", value =>
      value.routeRequest > beforeMove && value.accepted && value.intent === "MOVE" && !value.pending
      && value.groupKind === "DIRECT" && value.points.length === 1
      && value.points[0].x === destination.x && value.points[0].y === destination.y && value.points[0].z === destination.z);
    expect(moveAck.owned && moveAck.reserved).toBe(true);
    const moving = await pair.server.run(READ, scene); evidence.push({ step: "native state immediately after MOVE ACK", value: moving });
    // Upstream ground MOVE creates a HOLD route; it is not ResidentCommands.Mode.MOVE.
    expect(moving.order).toBe("HOLD"); expect(moving.orderId).not.toBe(pending.orderId);
    expect(moving.targetHealth).toBe(pending.targetHealth);
    const leaving = await observe(pair, scene, evidence, "MOVE physically departs the native attack position", row => {
      expect(row.targetHealth).toBe(pending.targetHealth); expect(row.weaponDamage).toBe(9); expect(row.authorized).toBe(false);
      return Math.hypot(row.x - pending.x, row.z - pending.z) > .6 && !row.session;
    }, 80);
    const movingDraw = await render(pair, scene, evidence);
    evidence.push({ step: "real moving native renderer and living motion, without forced STEP/sprint", value: { server: leaving, client: movingDraw } });
    await capture(pair.client, info, "epicfight-native-replacement-moving.png");
    const arrived = await observe(pair, scene, evidence, "replacement MOVE physically reaches its clicked destination", row => {
      expect(row.targetHealth, "every observed tick after MOVE retains the old target's health").toBe(pending.targetHealth);
      expect(row.weaponDamage, "cancelled windup spends no durability").toBe(9);
      expect(row.authorized, "old target loses the real native collision permission").toBe(false);
      return Math.hypot(row.x - destination.x - .5, row.z - destination.z - .5) < 1.5 && !row.session;
    }, 500);
    expect(arrived.using).toBe(false);
    const rendered = await render(pair, scene, evidence); expect(rendered.rts).toBe(true);
    await waitInput(pair, scene, evidence, "completed physical route retains control until RELEASE", value => value.groupComplete && value.owned && value.reserved, 100);
    await capture(pair.client, info, "epicfight-native-pending-cancel-move-arrived.png");
    // Observe a full additional native-animation interval; no delayed damage or hand ownership may reappear.
    for (let elapsed = 0; elapsed < 80; elapsed++) {
      await tick.sprint(pair.server, 1); await frames(pair.client, 2);
      const row = await pair.server.run(READ, scene); evidence.push({ step: "arrived command has no residual native strike", value: row }); physical(row);
      expect(row.targetHealth).toBe(pending.targetHealth); expect(row.weaponDamage).toBe(9);
      expect(row.session || row.authorized || row.using).toBe(false);
    }
    // Upstream deliberately has no command buttons in CombatCards. RELEASE is the bound
    // RTS keyboard command (J by default), shown by the active tool's hint panel.
    const release = await pair.client.run(RELEASE_BINDING);
    evidence.push({ step: "actual upstream RELEASE keyboard binding and active tool hints", value: release });
    expect(release.active).toBe(true); expect(release.type).toBe("KEYSYM");
    expect(release.code).toBeGreaterThan(0); expect(release.hints).toContain(release.name);
    expect((await pair.client.run(INPUT_STATE)).buttons.some(button => button.kind === "RELEASE")).toBe(false);
    await input.key(pair.client, { keyCode: release.code });
    await waitInput(pair, scene, evidence, "real keyboard RELEASE clears server selection and control ownership", value =>
      value.selected.length === 0 && value.selection.length === 0 && !value.owned && !value.reserved);
    const released = await pair.server.run(READ, scene); physical(released);
    expect(released.weaponPlace).toMatch(/^pack:/);
    expect(released.order).toBe("NONE"); expect(released.session || released.using || released.sprinting).toBe(false);
    evidence.push({ step: "final unique physical weapon after real RELEASE", value: released });
    // No synthetic STEP is invoked: the saved client render/animation/motion readings show what actually ran.
    await capture(pair.client, info, "epicfight-native-released-no-control-leak.png");
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-move-failure.png"); } catch { /* Keep the first failure. */ } }
    throw error;
  } finally {
    await saveEvidence(info, "epicfight-move-evidence.json", evidence);
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* Keep the first failure. */ } }
    await pair?.teardown(info);
  }
});

function visible(point: Pixel) {
  expect(point.x).toBeGreaterThan(5); expect(point.x).toBeLessThan(point.width - 5);
  expect(point.y).toBeGreaterThan(40); expect(point.y).toBeLessThan(point.height - 65);
}
async function waitInput(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (value: InputState) => boolean, attempts = 60) {
  let last: InputState | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await tick.sprint(pair.server, 1); await frames(pair.client, 3);
    const native = await pair.server.run(READ, scene); physical(native); evidence.push({ step: `${step} native tick`, value: native });
    if (scene.cancelled && native.orderId !== scene.cancelled.orderId) {
      expect(native.authorized).toBe(false); expect(native.targetHealth).toBe(scene.cancelled.health); expect(native.weaponDamage).toBe(scene.cancelled.damage);
    }
    last = { ...await pair.client.run(INPUT_STATE), ...await pair.server.run(CONTROL_STATE, scene) };
    evidence.push({ step, value: last }); if (done(last)) return last;
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}

function physical(row: Reading) {
  expect(row.weaponCount, `actual pack, owned hand and nearby item entities: ${row.debug}`).toBe(1);
  expect(row.components, "the original real item's components survive borrowing, excluding legitimate durability wear").toBe(true);
  expect(row.residentHealth, "the stationary NoAI target never attacked this proactive guard").toBe(20);
}
async function observe(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (row: Reading) => boolean, budget = 300) {
  let last: Reading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed++) {
    last = await pair.server.run(READ, scene); evidence.push({ step, value: last }); physical(last);
    if (done(last)) return last;
    await tick.sprint(pair.server, 1); await frames(pair.client, 2);
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}
async function render(pair: E2EPair, scene: Scene, evidence: Evidence) {
  let last: Render | undefined;
  const before = await pair.client.run(RENDER, scene);
  for (let attempt = 0; attempt < 30; attempt++) {
    await frames(pair.client, 4); last = await pair.client.run(RENDER, scene);
    evidence.push({ step: "read actual post-draw renderer observation", value: last });
    if (last.present && last.patch && last.draws > before.draws && last.tick >= before.tick) {
      expect(last.texture).toBe(last.expectedTexture); expect(last.mesh).not.toBe("");
      expect(last.entryScale).toBeGreaterThan(0);
      return last;
    }
    await tick.sprint(pair.server, 1);
  }
  throw new Error(`no real patched resident draw: ${JSON.stringify(last)}`);
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal"); await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false"); await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 20} ${p.y - 1} ${p.z - 20} ${p.x + 20} ${p.y - 1} ${p.z + 20} minecraft:stone`);
  const clear = await world.command(pair.server, `fill ${p.x - 20} ${p.y} ${p.z - 20} ${p.x + 20} ${p.y + 7} ${p.z + 20} minecraft:air`, { allowFailure: true });
  if (!clear.success && !clear.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const path = info.outputPath(name); await media.screenshot(client, { path, hideGui: false }); await info.attach(name, { path, contentType: "image/png" });
}
async function saveEvidence(info: TestInfo, name: string, evidence: Evidence) {
  const path = info.outputPath(name); writeFileSync(path, JSON.stringify(evidence, null, 2), "utf8");
  await info.attach(name, { path, contentType: "application/json" });
}

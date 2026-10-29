import { expect, test, type TestInfo } from "@playwright/test";
import { seedInstance } from "@izakyl/blockwright-client";
import { existsSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, media, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { REPO_ROOT } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { waitForResourceReload } from "../shared/harness/resource-reload";
import { MODEL_ASSETS, clientHasModelFile, placeServerModel, readServerLibrary, reloadServerModels } from "../shared/ui/model-folder";
import { mustCommand } from "../shared/ui/world-ui";

const LOOK = "folkways:model/steve/tartaric_acid";
const GEOMETRY = "folkways:geo/resident_models/steve.geo.json";
const TEXTURE = "folkways:textures/entity/resident_models/steve/tartaric_acid.png";
const GEOMETRY_FILE = "assets/folkways/geo/resident_models/steve.geo.json";
type Scene = { colony: string; resident: string; monster: string };
type ServerState = { patch: boolean; nativeTarget: boolean; attackAnimation: boolean; hitByResident: boolean;
  targetHealth: number; targetMaxHealth: number; look: string; armor: string[]; session: boolean };
type Draw = { present: boolean; patch: boolean; overlay: boolean; screen: string; look: string; geometry: string; expectedTexture: string;
  mode: string; resource: boolean; textureResource: boolean; armor: string[]; sequence: number; gameTick: number;
  mesh: string; texture: string; sourceRenderer: string; entryScale: number; meshIdentity: number };
type ReloadState = { state: string; error: string };
type Evidence = Array<{ step: string; value: unknown; stamp?: string }>;
type Connection = { connected: boolean; identity: string; level: boolean; screen: string; overlay: string; pending: boolean };
type PairingRecord = { seq: number; phase: string; connectionIdentity: string; animationsSize: number;
  wireNames: string[]; animationNames: string[]; errors: string[]; comparisonAvailable: boolean;
  serverMissing: string[]; clientMissing: string[] };
type Guard = { queued: number; deferred: number; replayed: number; dropped: number; generation: number;
  session: number; reloading: boolean; reloadFailed: boolean;
  events: Array<{ phase: string; reason: string; connectionId: string; generation: number; queued: number }> };
type Gate = { entered: boolean; released: boolean; reloadDone: boolean; error: string };

for (const scenario of ["startup", "online", "disconnect"] as const) test(scenario === "disconnect"
  ? "Epic Fight discards old deferred pairing on a real disconnect and pairs a rejoined connection"
  : scenario === "online" ? "Epic Fight online server reload preserves real animation pairing, GEO combat and client resource reload"
    : "Epic Fight initially syncs an installed Steve GEO and rebuilds native combat rendering after a real client reload", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  const onlineServerReload = scenario !== "startup";
  try {
    const run = newRunId();
    const serverName = `epicfight-geo-${onlineServerReload ? "online" : "startup"}-server-${run}`;
    let serverOptions: { instance: string; reuseInstanceDir?: boolean } = { instance: serverName };
    if (!onlineServerReload) {
      const seeded = seedInstance(serverName, { cwd: REPO_ROOT });
      const pack = "world/datapacks/folkways-e2e-models";
      seeded.write(`${pack}/pack.mcmeta`, JSON.stringify({ pack: {
        pack_format: 48, description: "Folkways Minecraft 1.21.1 GEO acceptance" } }));
      seeded.copy(path.join(MODEL_ASSETS, "steve"), `${pack}/data/folkways/folkways/resident_model/steve`);
      seeded.write(`${pack}/data/folkways/folkways/resident_model/steve/folkways.json`, JSON.stringify({ weight: 400 }));
      serverOptions = seeded.launch;
      evidence.push({ step: "real data pack installed before dedicated-server startup", value: { instance: seeded.instance, pack } });
    }
    pair = await launchPair({ config: "blockwright.toml#epicfight",
      server: serverOptions, client: { instance: `epicfight-geo-${onlineServerReload ? "online" : "startup"}-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie"] } });
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin);
    await mustCommand(pair.server, `gamemode spectator ${player.name}`);
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + .5} 0 10`);

    const baseline = await waitForInitialPairing(pair, evidence);
    const originalConnection = baseline.connection.identity;
    if (onlineServerReload) {
      await pair.client.run(PREPARATION_GATE, { action: "start" });
      await expect.poll(async () => (await pair!.client.run(PREPARATION_GATE, { action: "state" })).entered,
        { timeout: 15_000, message: "an actual preparation listener must be holding Minecraft's real reload" }).toBe(true);
      evidence.push({ step: "normal data pack installed while the player remains connected", value:
        await placeServerModel(pair.server, path.join(MODEL_ASSETS, "steve"), "steve", { weight: 400 }) });
      await reloadServerModels(pair.server);
      const held = await waitForDeferred(pair, evidence, baseline.guard.deferred, originalConnection);
      expect(held.guard.reloading).toBe(true);
      expect(held.guard.queued).toBeGreaterThan(0);
      expect(held.client.filter(row => row.phase === "client-send" && row.seq > latestSequence(baseline.client)),
        "no half-built CP table may be emitted while the real preparation future is held").toEqual([]);
      evidence.push({ step: "real resource preparation held; actual server packet deferred without sending a CP table", value: held });
      if (scenario === "disconnect") {
        await cancelAndRejoin(pair, evidence, originalConnection, held.guard, baseline.client, baseline.server);
        return;
      }
      await pair.client.run(PREPARATION_GATE, { action: "release" });
      await waitForLiveModel(pair, evidence, originalConnection);
      const accepted = await pairing(pair, evidence, "original native validator accepted the replay on the same connection");
      const validations = accepted.server.filter(row => row.phase === "server-validate" && row.seq > latestSequence(baseline.server));
      expect(validations.length).toBeGreaterThan(0);
      for (const row of validations) assertAccepted(row);
      expect(accepted.guard.replayed).toBeGreaterThan(baseline.guard.replayed);
      expect(accepted.guard.queued).toBe(0);
    }
    const library = await readServerLibrary(pair.server);
    evidence.push({ step: "server library after startup or real online reload", value: { resolved: library.resolved, entries: library.entryIds } });
    expect(library.resolved).toBe(true);
    expect(library.entryIds).toContain(LOOK);
    await waitForLiveModel(pair, evidence, originalConnection);
    evidence.push({ step: "client received actual model files and finished its real resource futures", value: { file: GEOMETRY_FILE } });
    expect((await pairing(pair, evidence, "native pairing after actual model transfer")).connection.identity).toBe(originalConnection);

    scene = await pair.server.run(SETUP, origin);
    let initial: ServerState | undefined;
    for (let attempt = 0; attempt < 120; attempt++) {
      const state = await pair.server.run(SERVER_STATE, scene);
      const armorReady = state.armor.length === ARMOR.length
        && state.armor.every((item, slot) => item === ARMOR[slot]);
      if (attempt % 10 === 0 || attempt === 119 || armorReady) {
        evidence.push({ step: `real profile armor equip tick ${attempt}`, value: state });
      }
      if (armorReady) {
        initial = state;
        break;
      }
      await tick.sprint(pair.server, 1);
    }
    evidence.push({ step: "server native patch, pinned look and physical armor", value: initial });
    expect(initial, "the real LoadoutProfile armor entries must equip from the resident pack within bounded server ticks").toBeDefined();
    initial = initial!;
    expect(initial.patch).toBe(true);
    expect(initial.look).toBe(LOOK);
    expect(initial.armor).toEqual(ARMOR);
    expect(initial.targetHealth).toBe(initial.targetMaxHealth);

    let before: Draw | undefined, hit = false;
    for (let attempt = 0; attempt < 240; attempt++) {
      await frames(pair.client, 2);
      const state = await pair.server.run(SERVER_STATE, scene);
      hit ||= state.nativeTarget && state.attackAnimation;
      const draw = await pair.client.run(DRAW, scene);
      if (attempt % 10 === 0) evidence.push({ step: `native combat sample ${attempt}`, value: { state, draw } });
      if (hit && state.hitByResident && state.targetHealth < initial.targetHealth && draw.present && draw.sequence > 0
          && !draw.overlay && !draw.screen) {
        before = draw;
        evidence.push({ step: "post-hit production GEO draw", value: { state, draw } });
        break;
      }
      await tick.sprint(pair.server, 1);
    }
    expect(hit, "a real Epic Fight attack animation targeted the hostile").toBe(true);
    expect(before, "native collision and a production client draw must both occur").toBeDefined();
    assertGeo(before!);
    await capture(pair.client, info, "epicfight-geo-before-reload.png");

    if (onlineServerReload) {
      const fighting = await pair.server.run(SERVER_STATE, scene);
      const beforeExistingReload = await pairing(pair, evidence, "the existing resident is fighting before another real server reload");
      await reloadServerModels(pair.server);
      await waitForLiveModel(pair, evidence, originalConnection);
      const afterExistingReload = await pairing(pair, evidence, "same resident and connection after reloading the already installed model pack");
      const checked = afterExistingReload.server.filter(row => row.phase === "server-validate" && row.seq > latestSequence(beforeExistingReload.server));
      expect(checked.length).toBeGreaterThan(0); checked.forEach(assertAccepted);
      let continued = false;
      for (let attempt = 0; attempt < 180; attempt++) {
        const state = await pair.server.run(SERVER_STATE, scene);
        if (attempt % 10 === 0) evidence.push({ step: "the same existing guard continues actual native collisions after server reload", value: state });
        if (state.hitByResident && state.targetHealth < fighting.targetHealth) { continued = true; break; }
        await tick.sprint(pair.server, 1); await frames(pair.client, 2);
      }
      expect(continued, "server reload must preserve actual native combat for an existing resident").toBe(true);
    }

    // Minecraft's own reload future drives the production reload listener and its mesh disposal.
    // A unique system property records only that future's completion; it never changes renderer state.
    const token = `folkways.geo.reload.${run}`;
    const started = await pair.client.run(START_RELOAD, { token });
    evidence.push({ step: "Minecraft reloadResourcePacks invoked", value: started });
    expect(started.state).toBe("pending");
    let completion: ReloadState = started;
    for (let attempt = 0; attempt < 120 && completion.state === "pending"; attempt++) {
      await frames(pair.client, 2);
      completion = await pair.client.run(RELOAD_STATUS, { token });
    }
    evidence.push({ step: "real Minecraft reload future result", value: completion });
    expect(completion).toEqual({ state: "complete", error: "" });
    await waitForResourceReload(pair.client);
    expect((await pairing(pair, evidence, "native pairing after the real client reload future completed")).connection.identity).toBe(originalConnection);

    const postReloadBaseline = await pair.client.run(DRAW, scene);
    evidence.push({ step: "draw counter when reload future completed", value: postReloadBaseline });
    let after: Draw | undefined;
    for (let attempt = 0; attempt < 45; attempt++) {
      await frames(pair.client, 3);
      const draw = await pair.client.run(DRAW, scene);
      evidence.push({ step: `post-reload production draw ${attempt}`, value: draw });
      if (draw.present && !draw.overlay && !draw.screen && draw.sequence > Math.max(before!.sequence, postReloadBaseline.sequence)
          && draw.meshIdentity > 0) { after = draw; break; }
      await tick.sprint(pair.server, 1);
    }
    expect(after, "the resident must draw again after the reload future completes").toBeDefined();
    assertGeo(after!);
    expect(after!.mesh).toBe(before!.mesh);
    expect(after!.meshIdentity, "reload must rebuild the converted GEO mesh object").not.toBe(before!.meshIdentity);
    expect(after!.sequence).toBeGreaterThan(before!.sequence);
    await capture(pair.client, info, "epicfight-geo-after-reload.png");
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await pairing(pair, evidence, "native pairing at failure without UI settling"); } catch { /* Preserve the original failure. */ } }
    // A disconnected client cannot pass the UI settle used by screenshots. Preserve its actual JVM logs first.
    if (pair) await attachRuntimeLogs(pair, info);
    if (pair) { try { await capture(pair.client, info, "epicfight-geo-failure.png"); } catch { /* retain original failure */ } }
    throw error;
  } finally {
    if (pair) { try { await pair.client.run(WORKER_GATE, { action: "release" }); } catch { /* Keep the first failure. */ } }
    const file = info.outputPath("epicfight-geo-evidence.json");
    writeFileSync(file, JSON.stringify(evidence, null, 2), "utf8");
    await info.attach("epicfight-geo-evidence.json", { path: file, contentType: "application/json" });
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* retain original failure */ } }
    await pair?.teardown(info);
  }
});

async function pairing(pair: E2EPair, evidence: Evidence, step: string) {
  const server = await pair.server.run(PAIRING);
  const client = await pair.client.run(PAIRING);
  const connection = await pair.client.run(CONNECTION);
  const guard = await pair.client.run(GUARD);
  const value = { server, client, connection, guard };
  const stamp = JSON.stringify([latestSequence(server), latestSequence(client), connection, guard.queued,
    guard.deferred, guard.replayed, guard.dropped, guard.generation, guard.session, guard.reloading]);
  if (evidence.at(-1)?.stamp !== stamp) evidence.push({ step, value, stamp });
  return value;
}
function latestSequence(rows: PairingRecord[]) { return rows.at(-1)?.seq ?? 0; }
function assertAccepted(row: PairingRecord) {
  expect(row.comparisonAvailable, "the observed native comparison must be complete").toBe(true);
  expect(row.errors).toEqual([]); expect(row.serverMissing).toEqual([]); expect(row.clientMissing).toEqual([]);
  expect(row.wireNames.length).toBe(row.animationNames.length);
}
async function waitForInitialPairing(pair: E2EPair, evidence: Evidence) {
  for (let attempt = 0; attempt < 150; attempt++) {
    const value = await pairing(pair, evidence, "initial native pairing and resource reload settle");
    const accepted = value.server.filter(row => row.phase === "server-validate").at(-1);
    if (accepted && value.connection.connected && value.connection.identity && !value.connection.overlay
        && !value.connection.pending && !value.guard.reloading && !value.guard.queued) {
      assertAccepted(accepted); return value;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("initial real native pairing did not settle; see pairing evidence");
}
async function waitForDeferred(pair: E2EPair, evidence: Evidence, before: number, identity: string) {
  for (let attempt = 0; attempt < 150; attempt++) {
    const value = await pairing(pair, evidence, "real server packet arrives during held resource preparation");
    expect(value.connection.connected).toBe(true); expect(value.connection.identity).toBe(identity);
    if (value.guard.deferred > before && value.guard.queued > 0) return value;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error("the real server reload did not trigger a deferred animation pairing request");
}
async function waitForLiveModel(pair: E2EPair, evidence: Evidence, identity: string) {
  const deadline = Date.now() + 120_000;
  let last: Connection | undefined;
  while (Date.now() < deadline) {
    const value = await pairing(pair, evidence, "observing real online reload and animation pairing");
    last = value.connection;
    if (!last.connected || last.identity !== identity || last.screen === "DisconnectedScreen")
      throw new Error(`real connection changed during online server reload: ${JSON.stringify(last)}; see pairing evidence`);
    if (clientHasModelFile(pair.client, GEOMETRY_FILE) && last.connected && last.level
        && !last.pending && !last.overlay && !last.screen && !value.guard.reloading && !value.guard.queued) {
      await pair.client.settle({ timeoutMs: 15_000 });
      await waitForResourceReload(pair.client);
      return;
    }
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  throw new Error(`online server reload did not settle: ${JSON.stringify(last)}`);
}

async function cancelAndRejoin(pair: E2EPair, evidence: Evidence, oldIdentity: string, held: Guard,
    baseline: PairingRecord[], baselineServer: PairingRecord[]) {
  await pair.client.run(DISCONNECT);
  let cleared = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    // Minecraft disconnect discards queued game-thread tasks. Keep immutable observations
    // and the test CF completion on an agent worker while the actual logout takes place.
    const guard = await pair.client.run(GUARD);
    evidence.push({ step: "actual logout drops old deferred requests while resources remain held", value: guard });
    cleared ||= guard.events.some(event => event.phase === "dropped" && event.connectionId === oldIdentity
      && /logout|connection-changed/.test(event.reason));
    if (cleared && guard.dropped >= held.dropped + held.queued) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  expect(cleared, "logging out must discard the actual queued old-connection packets").toBe(true);
  expect((await pair.client.run(GUARD)).dropped).toBeGreaterThanOrEqual(held.dropped + held.queued);
  let newOrigin = "";
  for (let attempt = 0; attempt < 150; attempt++) {
    const guard = await pair.client.run(GUARD);
    const deferred = guard.events.filter(event => event.phase === "deferred" && event.connectionId
      && event.connectionId !== oldIdentity).at(-1);
    if (deferred && guard.reloading && guard.queued > 0) {
      newOrigin = deferred.connectionId;
      evidence.push({ step: "the new real connection also waits on the still-held process-wide future", value: guard });
      break;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  expect(newOrigin, "a new login must be deferred before the original real preparation gate is released").not.toBe("");
  const stillHeld = await pair.client.run(WORKER_GATE, { action: "state" });
  expect(stillHeld.entered && !stillHeld.released).toBe(true);
  expect((await pair.client.run(GUARD)).reloading, "the process-wide future stays tracked after logout").toBe(true);
  await pair.client.run(WORKER_GATE, { action: "release" });
  // Blockwright's configured auto-connect makes another genuine login in this same JVM.
  const deadline = Date.now() + 60_000;
  let rejoined: Awaited<ReturnType<typeof pairing>> | undefined;
  while (Date.now() < deadline) {
    const value = await pairing(pair, evidence, "a new real connection pairs after the held global reload");
    const accepted = value.server.filter(row => row.phase === "server-validate" && row.seq > latestSequence(baselineServer)).at(-1);
    if (value.connection.connected && value.connection.identity === newOrigin
        && value.connection.level && !value.connection.overlay && !value.connection.pending
        && !value.guard.reloading && !value.guard.queued && accepted) {
      assertAccepted(accepted); rejoined = value; break;
    }
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  expect(rejoined, "the same client JVM must genuinely reconnect and pass the native validator").toBeDefined();
  expect(rejoined!.client.some(row => row.phase === "client-send" && row.seq > latestSequence(baseline)
    && row.connectionIdentity === newOrigin), "the new connection must send its own genuine pairing response").toBe(true);
  const accepted = rejoined!.server.filter(row => row.phase === "server-validate" && row.seq > latestSequence(baselineServer)).at(-1)!;
  expect(accepted.connectionIdentity).not.toBe(baselineServer.filter(row => row.phase === "server-validate").at(-1)!.connectionIdentity);
  expect(rejoined!.client.filter(row => row.phase === "client-send" && row.seq > latestSequence(baseline)
    && row.connectionIdentity === oldIdentity), "old deferred payloads must never be replayed into a new connection").toEqual([]);
  expect(rejoined!.guard.queued).toBe(0);
  expect((await world.players(pair.server)).length).toBe(1);
}

const PAIRING = defineTask<void, PairingRecord[]>({ name: "folkways.epicfight.geo.observe-wire-pairing", side: "any", thread: "worker", settle: false,
  source: javaTask({ imports: ["io.github.izakyl.folkways.epicfight.AnimationPairingEvidence"],
    body: `return AnimationPairingEvidence.snapshot();` }) });
const CONNECTION = defineTask<void, Connection>({ name: "folkways.epicfight.geo.observe-live-connection", side: "client", settle: false,
  source: javaTask({ imports: ["java.util.Map", "net.minecraft.client.Minecraft"], body: `
var mc=Minecraft.getInstance();var pending=Minecraft.class.getDeclaredField("pendingReload");pending.setAccessible(true);
return Map.of("connected",mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected(),"level",mc.level!=null,
 "identity",mc.getConnection()==null?"":Integer.toHexString(System.identityHashCode(mc.getConnection().getConnection())),
 "screen",mc.screen==null?"":mc.screen.getClass().getSimpleName(),"overlay",mc.getOverlay()==null?"":mc.getOverlay().getClass().getSimpleName(),"pending",pending.get(mc)!=null);` }) });
const GUARD = defineTask<void, Guard>({ name: "folkways.epicfight.geo.observe-reload-guard", side: "client", thread: "worker", settle: false,
  source: javaTask({ imports: ["io.github.izakyl.folkways.epicfight.client.AnimationPairingReloadGuard"],
    body: `return AnimationPairingReloadGuard.snapshot();` }) });
const PREPARATION_GATE = defineTask<{ action: "start" | "state" | "release" }, Gate>({
  name: "folkways.epicfight.geo.real-resource-preparation-gate", side: "client", settle: false,
  source: javaTask({ imports: ["java.util.Map", "java.util.concurrent.CompletableFuture", "java.util.concurrent.atomic.AtomicBoolean",
    "net.minecraft.client.Minecraft", "net.minecraft.server.packs.resources.ReloadableResourceManager"],
    members: `private static volatile CompletableFuture<Void> gate;
private static volatile CompletableFuture<Void> actualReload;
private static final AtomicBoolean armed=new AtomicBoolean();
private static volatile boolean entered;
private static volatile String error="";`, body: `
var action=Args.of(ctx).string("action");
if(action.equals("start")){
 if(gate!=null)throw new IllegalStateException("one preparation gate per isolated client");
 gate=new CompletableFuture<>();armed.set(true);
 ((ReloadableResourceManager)Minecraft.getInstance().getResourceManager()).registerReloadListener((barrier,resources,prepareProfiler,applyProfiler,background,game)->{
  if(armed.compareAndSet(true,false)){entered=true;return gate.thenCompose(unused->barrier.wait((Void)null)).thenRunAsync(()->{},game);}
  return barrier.wait((Void)null).thenRunAsync(()->{},game);
 });
 actualReload=Minecraft.getInstance().reloadResourcePacks();
 actualReload.whenComplete((unused,failure)->{if(failure!=null)error=failure.toString();});
}else if(action.equals("release")&&gate!=null)gate.complete(null);
return Map.of("entered",entered,"released",gate!=null&&gate.isDone(),"reloadDone",actualReload!=null&&actualReload.isDone(),"error",error);` }) });
const WORKER_GATE = { ...PREPARATION_GATE, thread: "worker" as const };
const DISCONNECT = defineTask<void, boolean>({ name: "folkways.epicfight.geo.actual-network-disconnect", side: "client", settle: false,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.network.chat.Component"], body: `
var connection=Minecraft.getInstance().getConnection();if(connection==null)throw new IllegalStateException("a real connected client is required");
connection.getConnection().disconnect(Component.literal("Folkways real reload cancellation test"));return true;` }) });

const ARMOR = ["minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"];
function assertGeo(draw: Draw) {
  expect(draw.present && draw.patch).toBe(true);
  expect(draw.look).toBe(LOOK);
  expect(draw.geometry).toBe(GEOMETRY);
  expect(draw.expectedTexture).toBe(TEXTURE);
  expect(draw.resource && draw.textureResource).toBe(true);
  expect(draw.armor).toEqual(ARMOR);
  expect(draw.mode, "a fallback skin or vanilla biped cannot satisfy GEO acceptance").toBe("converted_geo");
  expect(draw.mesh).toMatch(/^folkways_epicfight:resident_geo\//);
  expect(draw.texture).toBe(TEXTURE);
  expect(draw.sourceRenderer).toBe("io.github.izakyl.folkways.plugins.person.ResidentGeoBackend");
  expect(draw.entryScale).toBeGreaterThan(0);
  expect(draw.meshIdentity).toBeGreaterThan(0);
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const file = info.outputPath(name);
  await media.screenshot(client, { path: file, hideGui: false });
  await info.attach(name, { path: file, contentType: "image/png" });
}
async function attachRuntimeLogs(pair: E2EPair, info: TestInfo) {
  for (const [side, instance] of [["server", pair.server.instance], ["client", pair.client.instance]] as const) {
    for (const folder of ["logs", "debug", "crash-reports"]) {
      const root = path.join(instance.instanceDir, folder);
      if (!existsSync(root)) continue;
      for (const file of readdirSync(root, { withFileTypes: true })) {
        if (!file.isFile() || !/\.(log|txt)$/.test(file.name)) continue;
        try { await info.attach(`epicfight-geo-${side}-${folder}-${file.name}`, {
          path: path.join(root, file.name), contentType: "text/plain" });
        } catch { /* Preserve the original gameplay failure. */ }
      }
    }
  }
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal");
  await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false");
  await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 12} ${p.y - 1} ${p.z - 12} ${p.x + 12} ${p.y - 1} ${p.z + 12} minecraft:stone`);
  const clear = await world.command(pair.server,
    `fill ${p.x - 12} ${p.y} ${p.z - 12} ${p.x + 12} ${p.y + 7} ${p.z + 12} minecraft:air`, { allowFailure: true });
  if (!clear.success && !clear.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}

const SERVER_IMPORTS = ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.LinkedHashMap",
  "java.util.Optional", "java.util.Set", "java.util.LinkedHashSet",
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "net.minecraft.world.level.block.Blocks", "net.minecraft.core.registries.BuiltInRegistries", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity", "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry",
  "io.github.izakyl.folkways.plugins.patrol.PatrolContent", "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing",
  "io.github.izakyl.folkways.core.api.terms.ItemSpec", "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch",
  "yesman.epicfight.api.animation.types.AttackAnimation"];
const SETUP = defineTask<Vec, Scene>({ name: "folkways.epicfight.geo.real-fixture", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.mint(level.getServer());
int x=(int)a.integer("x"),y=(int)a.integer("y"),z=(int)a.integer("z");
for(int dx=-4;dx<15;dx++)for(int dz=-5;dz<12;dz++){level.setBlockAndUpdate(new BlockPos(x+dx,y-1,z+dz),Blocks.STONE.defaultBlockState());for(int dy=0;dy<4;dy++)level.setBlockAndUpdate(new BlockPos(x+dx,y+dy,z+dz),Blocks.AIR.defaultBlockState());}
var look=ResourceLocation.parse("folkways:model/steve/tartaric_acid");
if(!ResidentLooks.pool(level.registryAccess()).containsKey(look))throw new IllegalStateException("synced Steve look absent from server pool: "+look);
var body=PersonBody.RESIDENT.get().create(level);body.moveTo(x+2.5,y,z+6.5,-90,0);
body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,look));
if(!level.addFreshEntity(body))throw new IllegalStateException("resident spawn refused");
colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
ItemStack weapon=new ItemStack(Items.IRON_SWORD);body.pack().insert(weapon);
var profiles=LoadoutProfiles.of(colony);var profile=profiles.create("Epic Fight GEO sentry");
profiles.add(profile.id(),LoadoutProfile.Entry.weapon(LoadoutRegistry.using(weapon).orElseThrow().profileWeapon(weapon)));
ItemStack spear=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("epicfight:iron_spear")));body.pack().insert(spear);
profiles.add(profile.id(),LoadoutProfile.Entry.weapon(LoadoutRegistry.using(spear).orElseThrow().profileWeapon(spear)));
for(var pair:List.of(Map.entry(EquipmentSlot.HEAD,Items.IRON_HELMET),Map.entry(EquipmentSlot.CHEST,Items.IRON_CHESTPLATE),Map.entry(EquipmentSlot.LEGS,Items.IRON_LEGGINGS),Map.entry(EquipmentSlot.FEET,Items.IRON_BOOTS))){
 var stack=new ItemStack(pair.getValue());body.pack().insert(stack);profiles.add(profile.id(),LoadoutProfile.Entry.armor(pair.getKey(),ItemSpec.of(pair.getValue())));
}
profiles.assign(List.of(body.id()),Optional.of(profile.id()));
var route=colony.hold(PatrolContent.BEAT,new Held.Line(List.of(WorldPos.of(level,body.blockPosition()),
 WorldPos.of(level,body.blockPosition().offset(5,0,0))))).orElseThrow();
PatrolStaffing.of(colony).configure(level,route.id(),Set.of(profile.id()),1);
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);
if(!level.addFreshEntity(target))throw new IllegalStateException("target spawn refused");
return Map.of("colony",colony.id().toString(),"resident",body.id().toString(),"monster",target.getUUID().toString());` }) });
const SERVER_STATE = defineTask<Scene, ServerState>({ name: "folkways.epicfight.geo.native-state", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));if(body==null||target==null)throw new IllegalStateException("native fixture unloaded");
var patch=ResidentEpicFightPatch.of(body);var out=new LinkedHashMap<String,Object>();out.put("patch",patch!=null);
out.put("nativeTarget",patch!=null&&patch.getTarget()==target);
out.put("attackAnimation",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
out.put("hitByResident",target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);
out.put("targetHealth",target.getHealth());out.put("targetMaxHealth",target.getMaxHealth());
out.put("look",body.lookId().orElseThrow().location().toString());
out.put("armor",List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).stream()
 .map(slot->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(body.getItemBySlot(slot).getItem()).toString()).toList());
out.put("session",body.hasWeaponSession());return out;` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.geo.cleanup", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=level.getEntity(UUID.fromString(a.string("resident")));
if(body instanceof ResidentEntity resident){resident.discard();}
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();
Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });
const DRAW = defineTask<Scene, Draw>({ name: "folkways.epicfight.geo.production-draw", side: "client", source: javaTask({ imports: [
  "java.util.List", "java.util.LinkedHashMap", "net.minecraft.client.Minecraft", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.core.registries.BuiltInRegistries", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLook", "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.epicfight.client.ResidentGeoMeshes",
  "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var out=new LinkedHashMap<String,Object>();out.put("present",false);out.put("overlay",mc.getOverlay()!=null);out.put("screen",mc.screen==null?"":mc.screen.getClass().getSimpleName());
for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){
 out.put("present",true);out.put("patch",ResidentEpicFightPatch.of(body)!=null);
 out.put("look",body.lookId().map(key->key.location().toString()).orElse(""));
 var appearance=ResidentLooks.of(body).appearance();if(!(appearance instanceof ResidentLook.Model model))throw new IllegalStateException("client did not receive the pinned model look");
 out.put("geometry",model.geometry().toString());out.put("expectedTexture",model.texture().toString());
 out.put("resource",mc.getResourceManager().getResource(model.geometry()).isPresent());
 out.put("textureResource",mc.getResourceManager().getResource(model.texture()).isPresent());
 out.put("mode",ResidentGeoMeshes.rendering(body));
 out.put("armor",List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).stream()
  .map(slot->BuiltInRegistries.ITEM.getKey(body.getItemBySlot(slot).getItem()).toString()).toList());
 var draw=ResidentRenderEvidence.last(body).orElse(null);
 out.put("sequence",draw==null?0L:draw.sequence());out.put("gameTick",draw==null?-1L:draw.gameTick());
 out.put("mesh",draw==null?"":draw.mesh().toString());out.put("texture",draw==null?"":draw.texture().toString());
 out.put("sourceRenderer",draw==null?"":draw.sourceRenderer());out.put("entryScale",draw==null?0:draw.entryScale());
 out.put("meshIdentity",draw==null||!ResidentGeoMeshes.mesh(body).isPresent()?0:System.identityHashCode(ResidentGeoMeshes.mesh(body).orElseThrow().get()));break;
}return out;` }) });
const START_RELOAD = defineTask<{ token: string }, ReloadState>({ name: "folkways.epicfight.geo.start-real-reload", side: "client", source: javaTask({ imports: ["java.util.Map", "net.minecraft.client.Minecraft"], body: `
var key=Args.of(ctx).string("token");System.setProperty(key,"pending");
Minecraft.getInstance().reloadResourcePacks().whenComplete((unused,error)->
 System.setProperty(key,error==null?"complete":"failed:"+error));
return Map.of("state","pending","error","");` }) });
const RELOAD_STATUS = defineTask<{ token: string }, ReloadState>({ name: "folkways.epicfight.geo.observe-reload-future", side: "client", source: javaTask({ imports: ["java.util.Map"], body: `
var state=System.getProperty(Args.of(ctx).string("token"),"missing");
return Map.of("state",state.startsWith("failed:")?"failed":state,"error",state.startsWith("failed:")?state.substring(7):"");` }) });

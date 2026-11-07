import { expect, test } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";
import { camera, defineTask, javaTask, media, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { seedShaders, shaderEvidence } from "../shared/capture/shaders";

type Scene = { uuid: string; colony: string; x: number; y: number; z: number };
type State = { pulses: number; water: number; burning: boolean; stopped: boolean };
const SPRAY = "com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayParticle";

test("resident extinguisher and hose show native spray only while suppressing", async ({}, info) => {
  test.setTimeout(480_000);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    const clientInstance = `spray-client-${run}`;
    const shaders = process.env.SPRAY_SHADERS === "1";
    if (shaders) {
      seedShaders(clientInstance);
    }
    pair = await launchPair({
      config: `blockwright.toml#${shaders ? "firefighting_visuals" : "firefighting"}`,
      server: { instance: `spray-server-${run}`, startFrozen: false },
      client: { instance: clientInstance },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const player = await waitForGroundedPlayer(pair.server);
    if (shaders) {
      writeFileSync(info.outputPath("shaders.json"), JSON.stringify(shaderEvidence(clientInstance), null, 2));
    }
    await world.command(pair.server, "gamerule doMobSpawning false");
    await world.command(pair.server, "gamerule doFireTick false");
    await world.command(pair.server, "weather clear");
    await world.command(pair.server, "time set noon");
    for (const tool of ["extinguisher", "hose"]) {
      const scene = await pair.server.run(SETUP, { player: player.name, tool });
      const eye = { x: scene.x + 1.5, y: scene.y + 2, z: scene.z - 6 };
      await camera.set(pair.client, {
        position: eye,
        ...cameraLookingAt(eye, { x: scene.x + 1.5, y: scene.y + 1, z: scene.z }),
      });
      await frames(pair.client, 30);
      const capture = async (phase: string) => {
        const path = info.outputPath(`${tool}-${phase}.png`);
        await media.screenshot(pair!.client, { path, hideGui: true });
        await info.attach(`${tool}-${phase}`, { path, contentType: "image/png" });
        return path;
      };
      const idlePath = await capture("idle");
      const initial = await pair.server.run(READ, scene);
      expect((await pair.client.run(PARTICLES, scene))[SPRAY] ?? 0).toBe(0);
      if (tool === "hose") {
        await pair.server.run(CONTROL, { ...scene, active: true, connectionOnly: true });
        await expect.poll(async () => (await pair!.client.run(PARTICLES, scene)).hoses).toBe(1);
        expect((await pair.client.run(PARTICLES, scene))[SPRAY] ?? 0).toBe(0);
        expect((await pair.server.run(READ, scene)).pulses).toBe(0);
        await capture("connected");
        expect((await pair.server.run(READ, scene)).water).toBe(initial.water);
      }
      await pair.server.run(CONTROL, { ...scene, active: true });
      await expect.poll(async () => (await pair!.server.run(READ, scene)).pulses,
        { timeout: 20_000 }).toBeGreaterThan(5);
      const active = await pair.server.run(READ, scene);
      expect(active.water).toBeLessThan(initial.water);
      expect(active.burning).toBe(false);
      await expect.poll(async () => (await pair!.client.run(PARTICLES, scene))[SPRAY] ?? 0).toBeGreaterThan(0);
      const sprayingPath = await capture("spraying");
      const particles = await pair.client.run(PARTICLES, scene);
      expect(particles.aimed).toBeGreaterThan(0);
      await pair.server.run(CONTROL, { ...scene, active: false });
      await expect.poll(async () => (await pair!.server.run(READ, scene)).stopped).toBe(true);
      const stopped = await pair.server.run(READ, scene);
      await expect.poll(async () => (await pair!.client.run(PARTICLES, scene))[SPRAY] ?? 0,
        { timeout: 15_000 }).toBe(0);
      await expect.poll(async () => (await pair!.client.run(PARTICLES, scene)).hoses).toBe(0);
      const stoppedPath = await capture("stopped");
      const after = await pair.server.run(READ, scene);
      expect(after.pulses).toBe(stopped.pulses);
      expect(after.water).toBe(stopped.water);
      expect(initial.water - after.water).toBe(after.pulses * (tool === "hose" ? 10 : 5));
      const differences = {
        idleToSpraying: changedSprayPixels(idlePath, sprayingPath),
        stoppedToSpraying: changedSprayPixels(stoppedPath, sprayingPath),
      };
      expect(differences.idleToSpraying).toBeGreaterThan(100);
      expect(differences.stoppedToSpraying).toBeGreaterThan(100);
      const evidence = { tool, initial, active, stopped, after, particles, differences };
      writeFileSync(info.outputPath(`${tool}-evidence.json`), JSON.stringify(evidence, null, 2));
      await pair.server.run(CLEANUP, scene);
    }
    for (const tool of ["extinguisher", "hose"]) {
      const scene = await pair.server.run(SETUP, { player: player.name, tool, automatic: true });
      const eye = { x: scene.x + 1.5, y: scene.y + 2, z: scene.z - 6 };
      await camera.set(pair.client, {
        position: eye,
        ...cameraLookingAt(eye, { x: scene.x + 1.5, y: scene.y + 1, z: scene.z }),
      });
      await frames(pair.client, 20);
      const initial = await pair.server.run(READ, scene);
      const recording = await media.record(pair.client, { path: info.outputPath(`${tool}-automatic.mp4`), fps: 30 });
      try {
        await pair.server.run(CONTROL, { ...scene, active: true });
        await expect.poll(async () => (await pair!.client.run(PARTICLES, scene))[SPRAY] ?? 0,
          { timeout: 90_000, intervals: [50] }).toBeGreaterThan(0);
        await media.screenshot(pair.client, { path: info.outputPath(`${tool}-automatic-spray.png`), hideGui: true });
        await expect.poll(async () => (await pair!.server.run(READ, scene)).pulses,
          { timeout: 90_000 }).toBeGreaterThan(0);
        const after = await pair.server.run(READ, scene);
        expect(after.burning).toBe(false);
        expect(after.pulses).toBe(1);
        expect(initial.water - after.water).toBe(tool === "hose" ? 10 : 5);
        await expect.poll(async () => (await pair!.client.run(PARTICLES, scene))[SPRAY] ?? 0,
          { timeout: 15_000 }).toBe(0);
        await expect.poll(async () => (await pair!.client.run(PARTICLES, scene)).hoses).toBe(0);
        writeFileSync(info.outputPath(`${tool}-automatic.json`), JSON.stringify({ initial, after }, null, 2));
      } finally {
        await recording.stop();
      }
      await pair.server.run(CLEANUP, scene);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(info);
  }
});

// Compare the jet corridor, excluding the resident, cabinet, sky and GUI. Native particles are also asserted
// separately so animation or shader noise cannot satisfy the visual regression by itself.
function changedSprayPixels(beforePath: string, sprayingPath: string): number {
  const before = media.decodePng(readFileSync(beforePath));
  const spraying = media.decodePng(readFileSync(sprayingPath));
  expect(spraying.width).toBe(before.width);
  expect(spraying.height).toBe(before.height);
  let changed = 0;
  for (let y = Math.floor(before.height * .38); y < before.height * .63; y++) {
    for (let x = Math.floor(before.width * .31); x < before.width * .56; x++) {
      const offset = (y * before.width + x) * 4;
      const delta = Math.abs(before.rgba[offset] - spraying.rgba[offset])
        + Math.abs(before.rgba[offset + 1] - spraying.rgba[offset + 1])
        + Math.abs(before.rgba[offset + 2] - spraying.rgba[offset + 2]);
      if (delta > 60) {
        changed++;
      }
    }
  }
  return changed;
}

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos",
  "net.minecraft.world.level.block.Blocks", "net.minecraft.world.level.material.Fluids",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.phys.Vec3", "java.lang.reflect.Proxy",
  "com.mikoalopex.createfirefightingadd.CreateFireFightingAdd",
  "com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem",
  "com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler",
  "com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity",
  "net.neoforged.neoforge.fluids.FluidStack",
  "net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction",
  "net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.tick.ServerTickEvent",
  "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.colony.api.Claimed",
  "io.github.izakyl.folkways.core.world.api.WorldPos", "io.github.izakyl.folkways.core.work.api.Worker",
  "io.github.izakyl.folkways.core.work.api.Placement", "io.github.izakyl.folkways.core.work.api.Outcome",
  "io.github.izakyl.folkways.effect.Effects", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethods",
  "io.github.izakyl.folkways.plugins.damagecontrol.SuppressionSession",
  "io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent",
  "io.github.izakyl.folkways.core.vocation.api.Vocations",
  "io.github.izakyl.folkways.front.engine.colony.ZoneDrafts",
  "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
];

const SETUP = defineTask<{ player: string; tool: string; automatic?: boolean }, Scene>({
  name: "folkways.spray.setup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
var player = server.getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var origin = player.blockPosition().offset(4, 0, 4);
for (var at : BlockPos.betweenClosed(origin.offset(-3, -1, -3), origin.offset(8, 4, 3))) {
    level.setBlockAndUpdate(at, at.getY() == origin.getY() - 1
        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
}
var colony = Colonies.mint(server);
var body = PersonBody.RESIDENT.get().create(level);
body.setNoAi(true);
body.moveTo(Vec3.atBottomCenterOf(origin));
body.setYRot(-90);
body.yHeadRot = -90;
body.yBodyRot = -90;
level.addFreshEntity(body);
colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
var fire = origin.east(3);
level.setBlockAndUpdate(fire.below(), Blocks.NETHERRACK.defaultBlockState());
var bottle = new ItemStack(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, bottle)
    .setStoredFluid(new FluidStack(Fluids.WATER, 4000));
body.pack().setItem(0, bottle);
body.presentHeldItem(bottle);
boolean hose = Args.of(ctx).string("tool").equals("hose");
var cabinetPos = origin.west();
level.setBlockAndUpdate(cabinetPos, CreateFireFightingAdd.FIRE_HYDRANT_CABINET.get().defaultBlockState());
var cabinet = (FireHydrantCabinetBlockEntity) level.getBlockEntity(cabinetPos);
if (hose) {
    cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_HOSE,
        new ItemStack(CreateFireFightingAdd.FIRE_HOSE_ITEM.get()));
    cabinet.getItemStackHandler().setStackInSlot(FireHydrantCabinetBlockEntity.SLOT_NOZZLE,
        new ItemStack(CreateFireFightingAdd.CONE_NOZZLE_ITEM.get()));
    cabinet.getFluidHandler(null).fill(new FluidStack(Fluids.WATER, 4000), FluidAction.EXECUTE);
    FireHydrantCabinetBlockEntity.tick(level, cabinetPos, cabinet.getBlockState(), cabinet);
}
boolean automatic = Args.of(ctx).flag("automatic", false);
body.getPersistentData().putBoolean("automatic", automatic);
body.getPersistentData().putInt("water", hose ? cabinet.getFluid().getAmount() : 4000);
if (automatic) {
    body.setNoAi(false);
    for (var vocation : Vocations.all()) {
        body.licences().trade(vocation).ifPresent(trade ->
            body.licences().allow(trade, vocation.id().equals(DamageControlContent.ID)));
    }
    ZoneDrafts.zone(level, colony, DamageControlContent.ID, origin.offset(-2, 0, -2), origin.offset(6, 2, 2),
        ColonySettings.empty().with("enabled", new ColonySettings.Value.Flag(true))
            .with("extinguish", new ColonySettings.Value.Flag(true))
            .with("repair", new ColonySettings.Value.Flag(false))
            .with("refill", new ColonySettings.Value.Flag(false))).orElseThrow();
    NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
        if (body.isRemoved()) {
            return;
        }
        var data = body.getPersistentData();
        int water = hose ? cabinet.getFluid().getAmount() : FireExtinguisherItem.getFluid(bottle).getAmount();
        if (water < data.getInt("water")) {
            data.putInt("pulses", data.getInt("pulses") + 1);
        }
        data.putInt("water", water);
        data.putBoolean("burning", level.getBlockState(fire).is(Blocks.FIRE));
    });
    body.getPersistentData().putIntArray("fire", new int[]{fire.getX(), fire.getY(), fire.getZ()});
    return Map.of("uuid", body.getUUID().toString(), "colony", colony.id().toString(),
        "x", body.getX(), "y", body.getY(), "z", body.getZ());
}
var worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(), new Class<?>[]{Worker.class},
    (proxy, method, args) -> switch (method.getName()) {
        case "body" -> body;
        case "resident" -> body.resident();
        case "pack" -> body.pack();
        case "effects" -> Effects.NONE;
        case "within", "route", "supplied" -> List.of();
        case "placement" -> Placement.NOWHERE;
        case "held" -> ItemStack.EMPTY;
        default -> null;
    });
var adapter = SuppressionMethods.all().stream().filter(method -> method.getClass().getSimpleName()
    .equals(hose ? "HoseAdapter" : "ExtinguisherAdapter")).findFirst().orElseThrow();
var plan = adapter.plan(level, worker, WorldPos.of(level, fire)).orElseThrow();
var session = new SuppressionSession[1];
// Reignite only this fixture's fire before a paid pulse, so continuous emission can be photographed.
// All suppression, resource costs and visual packets still go through the real adapter/session.
NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
    if (body.isRemoved()) {
        return;
    }
    var data = body.getPersistentData();
    if (!data.getBoolean("spraying")) {
        if (session[0] != null) {
            session[0].close();
            session[0] = null;
        }
        data.putBoolean("stopped", true);
        return;
    }
    data.putBoolean("stopped", false);
    if (level.getGameTime() % 2 != 0) {
        return;
    }
    if (session[0] == null) {
        session[0] = plan.opening().get().orElse(null);
        if (session[0] == null) {
            data.putString("error", "suppression fixture opening failed: " + plan.refusal().get());
            data.putBoolean("spraying", false);
            return;
        }
    }
    data.putInt("water", hose ? cabinet.getFluid().getAmount() : FireExtinguisherItem.getFluid(bottle).getAmount());
    if (data.getBoolean("connectionOnly")) {
        return;
    }
    // Only the no-AI visual fixture takes this stance directly; automatic scenes use ordinary travel.
    if (!plan.destinations().isEmpty()) {
        body.setPos(Vec3.atBottomCenterOf(plan.destinations().iterator().next().block(level)));
    }
    level.setBlockAndUpdate(fire, Blocks.FIRE.defaultBlockState());
    if (session[0].suppress(level, fire) instanceof Outcome.Done && session[0].performed()) {
        data.putInt("pulses", data.getInt("pulses") + 1);
    }
    data.putInt("water", hose ? cabinet.getFluid().getAmount() : FireExtinguisherItem.getFluid(bottle).getAmount());
    data.putBoolean("burning", level.getBlockState(fire).is(Blocks.FIRE));
});
return Map.of("uuid", body.getUUID().toString(), "colony", colony.id().toString(),
    "x", body.getX(), "y", body.getY(), "z", body.getZ());
` }) });

const CONTROL = defineTask<Scene & { active: boolean; connectionOnly?: boolean }, boolean>({
  name: "folkways.spray.control", side: "server", source: javaTask({ imports: IMPORTS, body: `
var body = ((MinecraftServer) ctx.server()).overworld().getEntity(UUID.fromString(Args.of(ctx).string("uuid")));
body.getPersistentData().putBoolean("spraying", Args.of(ctx).flag("active", false));
body.getPersistentData().putBoolean("connectionOnly", Args.of(ctx).flag("connectionOnly", false));
if (body.getPersistentData().getBoolean("automatic")) {
    var fire = body.getPersistentData().getIntArray("fire");
    body.level().setBlockAndUpdate(new BlockPos(fire[0], fire[1], fire[2]), Blocks.FIRE.defaultBlockState());
}
return true;
` }) });

const READ = defineTask<Scene, State>({
  name: "folkways.spray.read", side: "server", source: javaTask({ imports: IMPORTS, body: `
var body = ((MinecraftServer) ctx.server()).overworld().getEntity(UUID.fromString(Args.of(ctx).string("uuid")));
var data = body.getPersistentData();
if (data.contains("error")) {
    throw new IllegalStateException(data.getString("error"));
}
return Map.of("pulses", data.getInt("pulses"), "water", data.getInt("water"),
    "burning", data.getBoolean("burning"), "stopped", data.getBoolean("stopped"));
` }) });

const PARTICLES = defineTask<Scene, Record<string, number>>({
  name: "folkways.spray.particles", side: "client", source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.particle.Particle"], body: `
var engine = ((Minecraft) ctx.client()).particleEngine;
var field = engine.getClass().getDeclaredField("particles");
field.setAccessible(true);
var counts = new TreeMap<String, Integer>();
int aimed = 0;
for (var queue : ((Map<?, ?>) field.get(engine)).values()) {
    for (var particle : (Collection<?>) queue) {
        counts.merge(particle.getClass().getName(), 1, Integer::sum);
        if (particle.getClass().getName().equals("${SPRAY}")) {
            var pos = ((Particle) particle).getBoundingBox().getCenter();
            if (pos.x >= Args.of(ctx).number("x") && pos.x <= Args.of(ctx).number("x") + 5
                && Math.abs(pos.z - Args.of(ctx).number("z")) < 2) {
                aimed++;
            }
        }
    }
}
var visuals = Class.forName("io.github.izakyl.folkways.firefighting.ResidentSprayVisuals");
var active = visuals.getDeclaredField("ACTIVE");
active.setAccessible(true);
counts.put("hoses", ((Map<?, ?>) active.get(null)).size());
counts.put("aimed", aimed);
return counts;
` }) });

const CLEANUP = defineTask<Scene, boolean>({
  name: "folkways.spray.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
server.overworld().getEntity(UUID.fromString(Args.of(ctx).string("uuid"))).discard();
Colonies.of(server, UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow().raze();
return true;
` }) });

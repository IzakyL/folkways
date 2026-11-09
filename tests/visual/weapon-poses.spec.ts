import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { camera, defineTask, javaTask, media, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Resident = { uuid: string; weapon: string; x: number; y: number; z: number };
type Scene = { residents: Resident[]; colony: string };
type Pose = Resident & { held: boolean; aiming: boolean; nativeError: number; sleeveError: number };

test("SBW residents use native two-arm poses during combat and clear them on release, beside TACZ",
  async ({}, info) => {
    test.setTimeout(360_000);
    let pair: E2EPair | undefined;
    let scene: Scene | undefined;
    try {
      const run = newRunId();
      pair = await launchPair({
        config: "blockwright.toml#weapon_poses",
        server: { instance: `weapon-poses-server-${run}`, startFrozen: false },
        client: { instance: `weapon-poses-client-${run}` },
        snapshot: { entityTypes: ["folkways_vanilla:resident", "minecraft:zombie"] },
      });
      const player = await waitForGroundedPlayer(pair.server);
      await world.command(pair.server, "gamerule doMobSpawning false");
      await world.command(pair.server, "time set noon");
      scene = await pair.server.run(SETUP, { player: player.name });
      await expect.poll(async () => (await pair!.client.run(READ_POSES, scene!)).length).toBe(3);
      expect((await pair.client.run(READ_POSES, scene)).every(row => !row.held && !row.aiming)).toBe(true);
      const variants = await pair.client.run(CHECK_VARIANTS, {});
      expect(variants.checked).toBeGreaterThanOrEqual(36);
      expect(variants.maxError).toBeLessThan(0.00001);
      expect(variants.rejected).toBe(true);
      expect(variants.cleared).toBe(true);
      const variantsPath = info.outputPath("native-variants.json");
      writeFileSync(variantsPath, JSON.stringify(variants, null, 2));
      await info.attach("native-variants", { path: variantsPath, contentType: "application/json" });
      await pair.server.run(ENGAGE, scene);
      await expect.poll(async () => {
        const rows = await pair!.client.run(READ_POSES, scene!);
        writeFileSync(info.outputPath("last-poses.json"), JSON.stringify(rows, null, 2));
        return rows.filter(row => row.held && row.aiming).length;
      }, { timeout: 30_000 }).toBe(3);
      await expect.poll(async () => {
        const targets = await pair!.server.run(DAMAGED, scene!);
        writeFileSync(info.outputPath("last-targets.json"), JSON.stringify(targets, null, 2));
        return targets.length === 3 && targets.every(target => target.hit);
      }, { timeout: 30_000 }).toBe(true);
      await tick.freeze(pair.server, true);
      const poses = await pair.client.run(READ_POSES, scene);
      for (const pose of poses) {
        expect(pose.sleeveError).toBeLessThan(0.00001);
        expect(pose.nativeError).toBeLessThan(0.00001);
      }
      const posesPath = info.outputPath("combat-poses.json");
      writeFileSync(posesPath, JSON.stringify(poses, null, 2));
      await info.attach("combat-poses", { path: posesPath, contentType: "application/json" });
      for (const resident of scene.residents) {
        const body = poses.find(pose => pose.uuid === resident.uuid)!;
        const eye = { x: body.x + 3, y: body.y + 1.8, z: body.z - 3 };
        await camera.set(pair.client, {
          position: eye,
          ...cameraLookingAt(eye, { x: body.x, y: body.y + 1.2, z: body.z }),
        });
        await frames(pair.client, 10);
        const screenshot = info.outputPath(`${body.weapon}-aiming.png`);
        await media.screenshot(pair.client, { path: screenshot, hideGui: true });
        await info.attach(`${body.weapon}-aiming`, { path: screenshot, contentType: "image/png" });
      }
      await pair.server.run(RELEASE, scene);
      await tick.freeze(pair.server, false);
      await expect.poll(async () => (await pair!.client.run(READ_POSES, scene!))
        .filter(row => !row.held && !row.aiming).length, { timeout: 30_000 }).toBe(3);
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      if (pair && scene) {
        await pair.server.run(CLEANUP, scene).catch(() => undefined);
      }
      await pair?.teardown(info);
    }
  },
);

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.resources.ResourceKey", "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.ai.attributes.Attributes",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "net.minecraft.world.level.block.Blocks",
  "com.atsuishio.superbwarfare.data.gun.GunData",
  "com.tacz.guns.api.item.IGun", "com.tacz.guns.api.item.IAmmo", "com.tacz.guns.api.item.gun.FireMode",
  "com.tacz.guns.init.ModItems", "io.github.izakyl.folkways.core.colony.api.Colonies",
  "io.github.izakyl.folkways.core.colony.api.Claimed", "io.github.izakyl.folkways.core.world.api.WorldPos",
  "io.github.izakyl.folkways.vanilla.person.PersonBody", "io.github.izakyl.folkways.vanilla.person.PersonContent",
  "io.github.izakyl.folkways.vanilla.person.ResidentEntity",
  "io.github.izakyl.folkways.vanilla.person.look.ResidentLooks",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutRegistry",
  "io.github.izakyl.folkways.vanilla.patrol.SentryPosts",
  "io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing",
  "net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.tick.EntityTickEvent",
  "net.minecraft.world.entity.monster.Zombie",
];

const SETUP = defineTask<{ player: string }, Scene>({
  name: "folkways.weapon-poses.setup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
var player = server.getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var origin = player.blockPosition().offset(4, 0, 4);
for (int x = -5; x < 15; x++) {
    for (int z = -5; z < 31; z++) {
        level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        for (int y = 0; y < 5; y++) {
            level.setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
    }
}
var colony = Colonies.mint(server);
// Keep real damage observable without letting automatic fire remove a camera subject's target.
NeoForge.EVENT_BUS.addListener(EntityTickEvent.Post.class, event -> {
    if (event.getEntity() instanceof Zombie target && target.getTags().contains("folkways_weapon_poses")
        && target.isAlive() && target.getHealth() < target.getMaxHealth()) {
        target.getPersistentData().putBoolean("folkways_pose_hit", true);
        target.setHealth(target.getMaxHealth());
    }
});
var residents = new ArrayList<Map<String, Object>>();
String[] weapons = {"ak_47", "m_870", "tacz_m4a1"};
for (int n = 0; n < weapons.length; n++) {
    var body = PersonBody.RESIDENT.get().create(level);
    // Separate firing lanes so each sentry's nearest target stays in its own lane.
    body.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + n * 12 + .5);
    body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY, ResourceLocation.parse("folkways_vanilla:test_skin_fallback")));
    level.addFreshEntity(body);
    colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
    body.joinColony(colony.id());
    ItemStack gun;
    ItemStack ammo;
    if (n < 2) {
        gun = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("superbwarfare:" + weapons[n])));
        GunData.from(gun).ammo.set(n == 0 ? 30 : 5);
        GunData.from(gun).save();
        ammo = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(
            "superbwarfare:" + (n == 0 ? "rifle_ammo" : "shotgun_ammo"))), 64);
    } else {
        gun = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
        var item = (IGun) gun.getItem();
        item.setGunId(gun, ResourceLocation.parse("tacz:m4a1"));
        item.setFireMode(gun, FireMode.AUTO);
        item.setCurrentAmmoCount(gun, 30);
        item.setBulletInBarrel(gun, true);
        ammo = new ItemStack(ModItems.AMMO.get(), 64);
        ((IAmmo) ammo.getItem()).setAmmoId(ammo, ResourceLocation.parse("tacz:556x45"));
    }
    body.pack().insert(gun);
    for (int supplies = 0; supplies < 8; supplies++) {
        body.pack().insert(ammo.copy());
    }
    residents.add(Map.of("uuid", body.getUUID().toString(), "weapon", weapons[n],
        "x", body.getX(), "y", body.getY(), "z", body.getZ()));
}
player.teleportTo(origin.getX() + 3, origin.getY(), origin.getZ() - 4);
return Map.of("colony", colony.id().toString(), "residents", residents);
` }) });

const ENGAGE = defineTask<Scene, boolean>({
  name: "folkways.weapon-poses.engage", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
for (var entity : level.getAllEntities()) {
    if (!(entity instanceof ResidentEntity body) || !body.colonyId().equals(Optional.of(colony.id()))) {
        continue;
    }
    ItemStack gun = body.pack().getItem(0);
    var profiles = LoadoutProfiles.of(colony);
    var profile = profiles.create("Pose " + body.getUUID());
    profiles.add(profile.id(), LoadoutProfile.Entry.weapon(
        LoadoutRegistry.using(gun).orElseThrow().profileWeapon(gun)));
    profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    var cells = new LinkedHashSet<WorldPos>();
    for (int x = 0; x < 2; x++) {
        for (int z = 0; z < 2; z++) {
            cells.add(WorldPos.of(level, body.blockPosition().below().offset(x, 0, z)));
        }
    }
    var zone = colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow();
    var staffing = PatrolStaffing.of(colony);
    staffing.configure(level, zone.id(), Set.of(profile.id()), 1);
    if (!staffing.members(zone.id()).equals(List.of(body.id()))) {
        throw new IllegalStateException("the resident with the zone's unique allowed profile must be staffed");
    }
    var target = EntityType.ZOMBIE.create(level);
    target.setNoAi(true);
    target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
    target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
    target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
    target.setHealth(target.getMaxHealth());
    target.moveTo(body.getX() + 8, body.getY(), body.getZ());
    target.addTag("folkways_weapon_poses");
    target.getPersistentData().putUUID("folkways_pose_owner", body.id());
    level.addFreshEntity(target);
}
return true;
` }) });

const DAMAGED = defineTask<Scene, Array<{ uuid: string; owner: string; hit: boolean; x: number; y: number; z: number }>>({
  name: "folkways.weapon-poses.damaged", side: "server", source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer) ctx.server()).overworld();
var targets = level.getEntities(EntityType.ZOMBIE, e -> e.getTags().contains("folkways_weapon_poses"));
return targets.stream().map(e -> Map.of("uuid", e.getUUID().toString(),
    "owner", e.getPersistentData().getUUID("folkways_pose_owner").toString(),
    "hit", e.getPersistentData().getBoolean("folkways_pose_hit"),
    "x", e.getX(), "y", e.getY(), "z", e.getZ())).toList();
` }) });

const RELEASE = defineTask<Scene, boolean>({
  name: "folkways.weapon-poses.release", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
level.getEntities(EntityType.ZOMBIE, e -> e.getTags().contains("folkways_weapon_poses")).forEach(e -> e.discard());
var colony = Colonies.of(server, UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
for (var entity : level.getAllEntities()) {
    if (!(entity instanceof ResidentEntity body) || !body.colonyId().equals(Optional.of(colony.id()))) {
        continue;
    }
    LoadoutProfiles.of(colony).assign(List.of(body.id()), Optional.empty());
    body.stopWeaponSession();
}
return true;
` }) });

const CLIENT_IMPORTS = [
  "net.minecraft.client.Minecraft", "net.minecraft.client.model.HumanoidModel",
  "net.minecraft.client.model.PlayerModel",
  "net.minecraft.client.model.geom.ModelLayers", "net.minecraft.client.model.geom.ModelPart",
  "net.minecraft.world.InteractionHand", "net.minecraft.world.entity.HumanoidArm",
  "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "net.minecraft.util.Mth", "net.minecraft.resources.ResourceLocation",
  "net.neoforged.neoforge.client.extensions.common.IClientItemExtensions",
  "io.github.izakyl.folkways.vanilla.person.ResidentEntity",
  "io.github.izakyl.folkways.vanilla.person.PersonBody",
  "io.github.izakyl.folkways.vanilla.person.ResidentWeaponPoses",
  "io.github.izakyl.folkways.vanilla.person.ResidentSkinRenderer",
  "io.github.izakyl.folkways.firearms.warfare.WarfareWeapons", "com.atsuishio.superbwarfare.data.gun.GunData",
  "com.tacz.guns.api.item.IGun", "net.minecraft.core.registries.BuiltInRegistries",
];

// Evaluate the same native item extension and player model independently of the adapter's sampler.
const NATIVE_MODEL = `
float partial = client.getTimer().getGameTimeDeltaPartialTick(false);
float yaw = Mth.rotLerp(partial, body.yHeadRotO, body.yHeadRot)
    - Mth.rotLerp(partial, body.yBodyRotO, body.yBodyRot);
float pitch = Mth.lerp(partial, body.xRotO, body.getXRot());
float stride = body.walkAnimation.position(partial);
float amount = body.walkAnimation.speed(partial);
var reference = new PlayerModel<ResidentEntity>(client.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
var nativePose = IClientItemExtensions.of(body.getMainHandItem())
    .getArmPose(body, InteractionHand.MAIN_HAND, body.getMainHandItem());
reference.rightArmPose = body.getMainArm() == HumanoidArm.RIGHT ? nativePose : HumanoidModel.ArmPose.EMPTY;
reference.leftArmPose = body.getMainArm() == HumanoidArm.LEFT ? nativePose : HumanoidModel.ArmPose.EMPTY;
reference.prepareMobModel(body, stride, amount, partial);
reference.setupAnim(body, stride, amount, body.tickCount + partial, yaw, pitch);
float nativeError = Math.max(
    Math.max(Math.abs(pose.right().pitch() - reference.rightArm.xRot),
        Math.abs(pose.left().pitch() - reference.leftArm.xRot)),
    Math.max(Math.max(Math.abs(pose.right().yaw() - reference.rightArm.yRot),
        Math.abs(pose.left().yaw() - reference.leftArm.yRot)),
        Math.max(Math.abs(pose.right().roll() - reference.rightArm.zRot),
            Math.abs(pose.left().roll() - reference.leftArm.zRot))));
`;

const READ_POSES = defineTask<Scene, Pose[]>({
  name: "folkways.weapon-poses.read", side: "client", source: javaTask({ imports: CLIENT_IMPORTS, body: `
var client = (Minecraft) ctx.client();
var rows = new ArrayList<Map<String, Object>>();
for (var entity : client.level.entitiesForRendering()) {
    if (!(entity instanceof ResidentEntity body)) {
        continue;
    }
    var optional = ResidentWeaponPoses.of(body);
    float nativeError = 0;
    float sleeveError = 0;
    if (optional.isPresent()) {
        var pose = optional.get();
        if (!(body.getMainHandItem().getItem() instanceof IGun)) {
            ${NATIVE_MODEL.replace("float nativeError =", "nativeError =")}
        }
        var dispatcher = client.getEntityRenderDispatcher().getRenderer(body);
        var field = dispatcher.getClass().getDeclaredField("skinBackend");
        field.setAccessible(true);
        var renderer = (ResidentSkinRenderer) field.get(dispatcher);
        var model = renderer.getModel();
        model.setupAnim(body, 0, 0, body.tickCount, body.yHeadRot - body.yBodyRot, body.getXRot());
        sleeveError = Math.max(Math.abs(model.rightSleeve.xRot - pose.right().pitch()),
            Math.abs(model.leftSleeve.xRot - pose.left().pitch()));
    }
    String weapon = body.getMainHandItem().getItem() instanceof IGun ? "tacz_m4a1"
        : BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()).getPath();
    rows.add(Map.of("uuid", body.getUUID().toString(), "weapon", weapon,
        "x", body.getX(), "y", body.getY(), "z", body.getZ(), "held", !body.getMainHandItem().isEmpty(),
        "aiming", optional.isPresent() && optional.get().right().pitch() < -.5
            && optional.get().left().pitch() < -.5,
        "nativeError", nativeError, "sleeveError", sleeveError));
}
return rows;
` }) });

const CHECK_VARIANTS = defineTask<object, { checked: number; maxError: number; rejected: boolean; cleared: boolean }>({
  name: "folkways.weapon-poses.variants", side: "client", source: javaTask({ imports: CLIENT_IMPORTS, body: `
var client = (Minecraft) ctx.client();
var body = PersonBody.RESIDENT.get().create(client.level);
int checked = 0;
float maxError = 0;
for (var adapter : WarfareWeapons.ALL) {
    var stack = new ItemStack(BuiltInRegistries.ITEM.get(adapter.weapon().item().orElseThrow()));
    body.setItemSlot(EquipmentSlot.MAINHAND, stack);
    for (var hand : HumanoidArm.values()) {
        body.setLeftHanded(hand == HumanoidArm.LEFT);
        for (int angle : new int[]{-25, 0, 35}) {
            body.setXRot(angle);
            body.xRotO = angle;
            body.yHeadRot = 17;
            body.yHeadRotO = 17;
            body.yBodyRot = 0;
            body.yBodyRotO = 0;
            var pose = ResidentWeaponPoses.of(body).orElseThrow();
            ${NATIVE_MODEL}
            maxError = Math.max(maxError, nativeError);
            checked++;
        }
    }
    GunData.from(stack).propertyOverrideString.set("{}");
    GunData.from(stack).save();
    if (ResidentWeaponPoses.of(body).isPresent()) {
        throw new IllegalStateException("unsafe variant acquired a gun pose");
    }
}
body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
boolean rejected = ResidentWeaponPoses.of(body).isEmpty();
body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
return Map.of("checked", checked, "maxError", maxError, "rejected", rejected,
    "cleared", ResidentWeaponPoses.of(body).isEmpty());
` }) });

const CLEANUP = defineTask<Scene, boolean>({
  name: "folkways.weapon-poses.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
level.getEntities(EntityType.ZOMBIE, e -> e.getTags().contains("folkways_weapon_poses")).forEach(e -> e.discard());
Colonies.of(server, UUID.fromString(Args.of(ctx).string("colony"))).ifPresent(c -> c.raze());
return true;
` }) });

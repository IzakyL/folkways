import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { licenseCrewFast } from "./fast-setup";
import { sleepMs } from "./promo-kit";
import type { Settlement } from "./combat-settlement";

export async function combatCast(scene: Settlement, id: string, station = false, options: { ready?: boolean } = {}) {
  const cast = await scene.server.run(CAST, {
    ...scene.o,
    colony: scene.colony,
    id,
    station,
  });
  if (options.ready === false) {
    return cast;
  }
  const crew = [{ uuid: cast.uuid, trades: ["patrolling", "hauling"] }];
  await licenseCrewFast(scene.server, crew);
  const approach: any[] = [];
  for (let i = 0; i < 100; i++) {
    await tick.sprint(scene.server, 10);
    const state = await combatState(scene);
    const member = state.crew.find((p: any) => p.uuid === cast.uuid);
    approach.push({ time: state.time, ...member });
    if (member?.staffing?.[0] === "filled" && member?.postStatus === "watching") {
      return { ...cast, approach };
    }
    await sleepMs(30);
  }
  throw new Error(`Promo cast not ready: ${JSON.stringify(await combatState(scene))}`);
}

export async function combatState(scene: Settlement) {
  return scene.server.run(STATE, { colony: scene.colony });
}

export async function combatWave(scene: Settlement, z: number, health = 40, count = 3, speed = 0.09) {
  return scene.server.run(WAVE, {
    ...scene.o,
    z: scene.o.z + z,
    health,
    count,
    speed,
  });
}

const IMPORTS = [
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.core.api.terms.ItemSpec",
  "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry",
  "io.github.izakyl.folkways.plugins.patrol.SentryPosts",
  "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing",
  "net.minecraft.core.BlockPos",
  "net.minecraft.core.registries.BuiltInRegistries",
  "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
  "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.ai.attributes.Attributes",
  "com.tacz.guns.api.TimelessAPI",
  "com.tacz.guns.api.item.IGun",
  "com.tacz.guns.api.item.IAmmo",
  "com.atsuishio.superbwarfare.data.gun.GunData",
  "com.atsuishio.superbwarfare.data.gun.GunProp",
  "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
  "com.atsuishio.superbwarfare.entity.vehicle.MortarEntity",
  "io.github.izakyl.folkways.warfare.WarfareStations",
];

const CAST = defineTask<Record<string, any>, any>({
  name: "folkways.promo.promo-cast",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
boolean station = Boolean.TRUE.equals(a.value("station"));
var origin = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var at = origin.offset(0, 0, station ? 20 : 12);
net.minecraft.world.entity.Entity emplacement = null;
at = new BlockPos(at.getX(), level.getHeight(
    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
var body = PersonBody.RESIDENT.get().create(level);
body.moveTo(at.getX() - (station ? 3.5 : 0) + .5, at.getY(), at.getZ() + .5, 0, 0);
level.addFreshEntity(body);
colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
body.joinColony(colony.id());
body.addTag("draft_crew");
body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
body.setHealth(200);
body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
body.pack().insert(new ItemStack(Items.COOKED_BEEF, 16));
var id = ResourceLocation.parse(a.string("id"));
ItemStack gun;
ItemStack ammo;
if (station) {
    level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState());
    gun = new ItemStack(Items.BOW);
    var vehicle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(id).create(level);
    if (!(vehicle instanceof MortarEntity)) {
        vehicle.modifyGunData("Main", data -> data.changeAmmoConsumer(1, null));
    }
    vehicle.moveTo(at.getX() + .5, at.getY() + .5, at.getZ() + .5, id.getPath().equals("fh_77bw") ? 90 : 0, 0);
    if (id.getPath().equals("fh_77bw")) {
        vehicle.setTurretYRot(90);
        vehicle.setTurretYRotO(90);
        ((com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity) vehicle)
            .setShootVec(new org.joml.Vector3f(0, 0, 1));
    }
    vehicle.addTag("draft_station");
    level.addFreshEntity(vehicle);
    emplacement = vehicle;
    var data = vehicle.getGunData("Main");
    ammo = vehicle instanceof MortarEntity
        ? new ItemStack(com.atsuishio.superbwarfare.init.ModItems.MORTAR_SHELL.get(), 32)
        : data.selectedAmmoConsumer().stack().copyWithCount(32);
} else if (id.getNamespace().equals("tacz")) {
    gun = new ItemStack(com.tacz.guns.init.ModItems.MODERN_KINETIC_GUN.get());
    var api = (IGun) gun.getItem();
    api.setGunId(gun, id);
    var data = TimelessAPI.getCommonGunIndex(id).orElseThrow().getGunData();
    api.setFireMode(gun, data.getFireModeSet().getFirst());
    api.setCurrentAmmoCount(gun, 1);
    api.setBulletInBarrel(gun, true);
    ammo = new ItemStack(com.tacz.guns.init.ModItems.AMMO.get(), 64);
    ((IAmmo) ammo.getItem()).setAmmoId(ammo, data.getAmmoId());
} else {
    gun = new ItemStack(BuiltInRegistries.ITEM.get(id));
    var data = GunData.from(gun);
    ammo = data.selectedAmmoConsumer().stack().copyWithCount(64);
}
var provider = LoadoutRegistry.using(gun).orElseThrow();
var profiles = LoadoutProfiles.of(colony);
var profile = profiles.create("Promo " + id);
profiles.replace(new LoadoutProfile(profile.id(), profile.name(), List.of(
    LoadoutProfile.Entry.weapon(provider.profileWeapon(gun)),
    LoadoutProfile.Entry.supply(ItemSpec.exact(ammo), ammo.getCount(), 1),
    LoadoutProfile.Entry.supply(ItemSpec.of(Items.SPYGLASS), 1, 1))));
profiles.assign(List.of(body.id()), Optional.of(profile.id()));
body.pack().insert(gun);
body.pack().insert(ammo);
body.pack().insert(new ItemStack(Items.SPYGLASS));
var cells = new LinkedHashSet<WorldPos>();
if (station) {
    cells.add(WorldPos.of(level, at));
    cells.add(WorldPos.of(level, at.offset(-4, 0, 0)));
    cells.add(WorldPos.of(level, at.offset(-4, 0, 2)));
} else {
    for (var p : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(1, 0, 1))) {
        cells.add(WorldPos.of(level, p));
    }
}
var zone = station
    ? colony.hold(SentryPosts.STATION, Held.Entity.of(emplacement)).orElseThrow()
    : colony.hold(SentryPosts.ZONE, new Held.Area(cells)).orElseThrow();
var staffing = PatrolStaffing.of(colony);
var post = SentryPosts.of(colony).zone(level, zone.id()).orElseThrow();
if (SentryPosts.of(colony).capacity(level, post) < 1 || !profiles.get(profile.id()).orElseThrow().hasRanged()) {
    throw new IllegalStateException("Promo staging " + id + " at " + at + " capacity="
        + SentryPosts.of(colony).capacity(level, post) + " ranged="
        + profiles.get(profile.id()).orElseThrow().hasRanged() + " cells=" + post.cells());
}
var slot = staffing.add(level, zone.id(), profile.id());
staffing.pin(level, zone.id(), slot, Optional.of(body.id()));
body.getPersistentData().putUUID("draft_zone", zone.id());
var result = new LinkedHashMap<String, Object>();
result.put("uuid", body.id().toString());
result.put("gun", id.toString());
result.put("ammunition", ammo.toString());
result.put("position", at.toShortString());
result.put("zone", zone.id().toString());
result.put("slot", slot.toString());
result.put("profile", profile.id().toString());
result.put("station", emplacement == null ? "" : emplacement.getUUID().toString());
return result;`,
  }),
});

export const OBSERVE_EVENTS = defineTask<Record<string, never>, any>({
  name: "folkways.promo.promo-event-observers",
  side: "server",
  source: javaTask({
    body: `
java.util.function.Consumer<com.atsuishio.superbwarfare.api.event.ShootEvent.Post> sbw = event -> {
    if (event.getShooter() != null && event.getShooter().getTags().contains("draft_crew")) {
        var data = event.getShooter().getPersistentData();
        data.putInt("shots", data.getInt("shots") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(sbw);
java.util.function.Consumer<com.tacz.guns.api.event.common.GunFireEvent> tacz = event -> {
    if (event.getLogicalSide().isServer() && event.getShooter().getTags().contains("draft_crew")) {
        var data = event.getShooter().getPersistentData();
        data.putInt("shots", data.getInt("shots") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(tacz);
java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> mortar = event -> {
    if (!event.getLevel().isClientSide()
        && event.getEntity() instanceof com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity shell
        && shell.getOwner() != null && shell.getOwner().getTags().contains("draft_crew")) {
        var data = shell.getOwner().getPersistentData();
        data.putInt("shots", data.getInt("shots") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(mortar);
java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> hit = event -> {
    var shooter = event.getSource().getEntity();
    if (shooter != null && shooter.getTags().contains("draft_crew")
        && event.getEntity().getTags().contains("draft_attacker") && event.getNewDamage() > 0) {
        var data = shooter.getPersistentData();
        data.putInt("hits", data.getInt("hits") + 1);
        data.putDouble("damage", data.getDouble("damage") + event.getNewDamage());
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(hit);
java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> death = event -> {
    var shooter = event.getSource().getEntity();
    if (shooter != null && shooter.getTags().contains("draft_crew")
        && event.getEntity().getTags().contains("draft_attacker")) {
        var data = shooter.getPersistentData();
        data.putInt("kills", data.getInt("kills") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(death);
return true;`,
  }),
});

const STATE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.promo-state",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var crew = new ArrayList<Object>();
var enemies = new ArrayList<Object>();
var stations = new ArrayList<Object>();
for (var entity : level.getAllEntities()) {
    if (entity instanceof io.github.izakyl.folkways.plugins.person.ResidentEntity body
        && entity.getTags().contains("draft_crew")) {
        var row = new LinkedHashMap<String, Object>();
        var data = entity.getPersistentData();
        row.put("uuid", body.id().toString());
        row.put("pos", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()));
        row.put("shots", data.getInt("shots"));
        row.put("hits", data.getInt("hits"));
        row.put("damage", data.getDouble("damage"));
        row.put("kills", data.getInt("kills"));
        row.put("held", body.getMainHandItem().toString());
        row.put("doing", body.doing().map(d -> d.what().toString()).orElse("idle"));
        row.put("reloading", com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(body)
            .getDataHolder().reloadStateType.isReloading());
        row.put("pack", body.pack().contents().stream().map(Object::toString).toList());
        int rounds = 0;
        var inventory = new ArrayList<ItemStack>(body.pack().contents());
        inventory.add(body.getMainHandItem());
        for (var stack : inventory) {
            if (stack.getItem() instanceof IAmmo || BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                .matches(".*(ammo|shell).*")) {
                rounds += stack.getCount();
            } else if (stack.getItem() instanceof IGun gun) {
                rounds += gun.getCurrentAmmoCount(stack) + (gun.hasBulletInBarrel(stack) ? 1 : 0);
            } else if (stack.getItem() instanceof com.atsuishio.superbwarfare.item.gun.GunItem) {
                rounds += GunData.from(stack).ammo.get();
            }
        }
        row.put("rounds", rounds);
        row.put("vehicle", body.getVehicle() == null ? "" : body.getVehicle().getUUID().toString());
        if (data.hasUUID("draft_zone")) {
            var zone = data.getUUID("draft_zone");
            row.put("staffing", PatrolStaffing.of(colony).slots(zone).stream().map(s -> s.status()).toList());
            row.put("postStatus", SentryPosts.of(colony).zone(level, zone)
                .map(z -> SentryPosts.of(colony).status(level, body.id(), z)).orElse("missing"));
        }
        crew.add(row);
    }
    if (entity instanceof VehicleEntity station && entity.getTags().contains("draft_station")) {
        stations.add(Map.of("uuid", entity.getUUID().toString(),
            "id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
            "pitch", station.getXRot(), "yaw", station.getYRot(), "x", station.getX(), "y", station.getY(),
            "z", station.getZ(), "passengers", station.getPassengers().size(),
            "turretYaw", station.getTurretYRot(),
            "fireTime", station instanceof MortarEntity mortar
                ? mortar.getEntityData().get(MortarEntity.FIRE_TIME) : 0));
    }
    if (entity instanceof net.minecraft.world.entity.Mob mob && entity.getTags().contains("draft_attacker")) {
        enemies.add(Map.of("uuid", entity.getUUID().toString(), "health", mob.getHealth(),
            "x", mob.getX(), "y", mob.getY(), "z", mob.getZ(),
            "target", mob.getTarget() == null ? "" : mob.getTarget().getUUID().toString()));
    }
}
return Map.of("time", level.getGameTime(), "crew", crew, "enemies", enemies, "stations", stations);`,
  }),
});

const WAVE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.promo-wave",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var ids = new ArrayList<String>();
for (int i = 0; i < a.integer("count"); i++) {
    var mob = EntityType.ZOMBIE.create(level);
    double x = a.number("x") + (i - 1) * 2 + .5;
    double z = a.number("z") + i % 2 + .5;
    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
        net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z));
    mob.moveTo(x, y, z, 180, 0);
    mob.setBaby(false);
    mob.setPersistenceRequired();
    mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(a.number("speed"));
    mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(128);
    mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(a.number("health"));
    mob.setHealth((float) a.number("health"));
    mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
    mob.addTag("draft_attacker");
    level.addFreshEntity(mob);
    ids.add(mob.getUUID().toString());
}
return Map.of("ids", ids, "health", a.number("health"), "z", a.number("z"));`,
  }),
});

export const STATION_DIAGNOSTIC = defineTask<Record<string, never>, any>({
  name: "folkways.promo.promo-station-diagnostic",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var field = WarfareStations.class.getDeclaredField("STATIONS");
field.setAccessible(true);
var adapters = (Map<?, ?>) field.get(null);
var rows = new ArrayList<Object>();
for (var entry : adapters.entrySet()) {
    var vehicle = (VehicleEntity) entry.getKey();
    if (!vehicle.getTags().contains("draft_station")) {
        continue;
    }
    var adapter = entry.getValue();
    var row = new LinkedHashMap<String, Object>();
    for (String name : List.of("direction", "observation", "pending")) {
        var value = adapter.getClass().getDeclaredField(name);
        value.setAccessible(true);
        row.put(name, String.valueOf(value.get(adapter)));
    }
    var safe = adapter.getClass().getDeclaredMethod("trajectorySafe");
    safe.setAccessible(true);
    row.put("trajectorySafe", safe.invoke(adapter));
    row.put("muzzle", vehicle instanceof MortarEntity
        ? vehicle.getEyePosition().toString() : vehicle.getShootPos("Main", 1).toString());
    row.put("actual", vehicle.getShootVec("Main", 1).toString());
    row.put("turretYaw", vehicle.getTurretYRot());
    row.put("turretPitch", vehicle.getTurretXRot());
    row.put("speed", vehicle.getProjectileVelocity("Main"));
    row.put("gravity", vehicle.getProjectileGravity("Main"));
    var point = vehicle instanceof MortarEntity ? vehicle.getEyePosition() : vehicle.getShootPos("Main", 1);
    var velocity = vehicle.getShootVec("Main", 1).normalize().scale(vehicle.getProjectileVelocity("Main"));
    var level = ctx.server().overworld();
    var bodyField = adapter.getClass().getDeclaredField("body");
    bodyField.setAccessible(true);
    var body = (io.github.izakyl.folkways.plugins.person.ResidentEntity) bodyField.get(adapter);
    for (int step = 0; step < 200; step++) {
        var next = point.add(velocity);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(point, next,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.ANY, body));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            row.put("firstTerrainImpact", hit.getLocation().toString());
            break;
        }
        point = next;
        velocity = velocity.add(0, -vehicle.getProjectileGravity("Main"), 0);
    }
    rows.add(row);
}
return rows;`,
  }),
});

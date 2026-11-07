import { camera, defineTask, javaTask, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { type Vec } from "../shared/colony/colony-founding";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast } from "./fast-setup";
import { hideHud, hudShown, promoClip, sleepMs } from "./promo-kit";
import { shaderEvidence } from "../shared/capture/shaders";
import type { VisualRun } from "../shared/capture/visual-kit";

export type CombatScene = Awaited<ReturnType<typeof openCombat>>;
import { openSettlement } from "./combat-settlement";
export const CONFIG = "blockwright.toml#promo-combat";

// All combat takes share the same generated settlement and its surrounding terrain.
export async function openCombat(scope: string, run: VisualRun) {
  const scene = await openSettlement(scope, run);
  const pos = (p: Vec) => `${p.x} ${p.y} ${p.z}`;
  return {
    ...scene,
    set: (p: Vec, block: string) => world.command(scene.server, `setblock ${pos(p)} ${block}`),
    fill: (a: Vec, b: Vec, block: string) => tryCommand(scene.server, `fill ${pos(a)} ${pos(b)} ${block}`),
  };
}

export async function combatCamera(scene: CombatScene, eye: Vec, target: Vec, fov = 55) {
  const { server, client, name } = scene;
  await world.command(server, `item replace entity ${name} weapon.mainhand with minecraft:air`);
  await world.command(server, `gamemode spectator ${name}`);
  const look = cameraLookingAt(eye, target);
  await world.command(server, `tp ${name} ${eye.x} ${eye.y} ${eye.z} ${look.yaw} ${look.pitch}`);
  await camera.set(client, { position: eye, ...look, fov });
  if (hudShown(client)) {
    await hideHud(client);
  }
  await frames(client, 20);
}

export async function castCombat(scene: CombatScene, mode: "rts" | "fire") {
  const cast = await scene.server.run(CAST, {
    colony: scene.colony,
    mode,
    ...scene.o,
  });
  await licenseCrewFast(
    scene.server,
    cast.residents.map((uuid) => ({
      uuid,
      trades: mode === "fire" ? ["damage_control", "building"] : ["patrolling", "hauling"],
    })),
  );
  return cast;
}

export async function sampleCombat(scene: CombatScene) {
  return scene.server.run(SURVEY, { colony: scene.colony });
}

export async function spawnWave(scene: CombatScene, count: number, z = 25, health = 40) {
  return scene.server.run(WAVE, {
    ...scene.o,
    count,
    z: scene.o.z + z,
    health,
  });
}

export async function recordCombat(
  run: VisualRun,
  scene: CombatScene,
  name: string,
  subject: string,
  seconds: number,
  during?: () => Promise<void>,
) {
  const samples: any[] = [];
  let gates: any;
  try {
    gates = await promoClip(run, scene.client, scene.server, name, {
      subject,
      seconds,
      fps: 30,
      worldState: { colony: scene.colony },
      during: async () => {
        const start = Date.now();
        await Promise.all([
          (async () => {
            while (Date.now() - start < seconds * 1000 - 500) {
              samples.push(await sampleCombat(scene));
              await sleepMs(300);
            }
          })(),
          during?.(),
        ]);
      },
    });
  } finally {
    run.note(name, { gates, samples });
  }
  run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  return samples;
}

const IMPORTS = [
  "io.github.izakyl.folkways.core.colony.api.Colonies",
  "io.github.izakyl.folkways.core.colony.api.Claimed",
  "io.github.izakyl.folkways.core.resident.api.Bodies",
  "io.github.izakyl.folkways.core.item.api.ItemSpec",
  "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles",
  "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile",
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
  "com.tacz.guns.api.item.IGun",
  "com.tacz.guns.api.item.IAmmo",
  "com.tacz.guns.api.item.gun.FireMode",
  "com.atsuishio.superbwarfare.data.gun.GunData",
];

const CAST = defineTask<Record<string, any>, { residents: string[]; crew: any[] }>({
  name: "folkways.promo.combat-cast",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var origin = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var mode = a.string("mode");
var crew = new ArrayList<Object>();
var ids = new ArrayList<String>();
for (int i = 0; i < (mode.equals("rts") ? 3 : 2); i++) {
    var body = PersonBody.RESIDENT.get().create(level);
    var at = origin.offset(-3 + i * 6, 0, 4);
    if (mode.equals("rts")) {
        at = origin.offset(-1 + i, 0, 8);
    }
    at = new BlockPos(at.getX(), level.getHeight(
        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ());
    body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
    level.addFreshEntity(body);
    colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
    body.joinColony(colony.id());
    body.addTag("combat_crew");
    body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
    body.setHealth(200);
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 16));
    body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
    var weapon = new ItemStack(Items.IRON_SWORD);
    if (mode.equals("rts")) {
        var profiles = LoadoutProfiles.of(colony);
        var profile = profiles.create("Manual sword squad " + i);
        profiles.add(profile.id(), LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
    body.pack().insert(weapon);
    crew.add(Map.of("uuid", body.id().toString(), "weapon", weapon.toString(), "position", at.toShortString()));
    ids.add(body.id().toString());
}
// Read-only event observers count actual native emissions, normal AI targets and attributed deaths.
java.util.function.Consumer<com.atsuishio.superbwarfare.api.event.ShootEvent.Post> sbw = event -> {
    if (event.getShooter() != null && event.getShooter().getTags().contains("combat_crew")) {
        var data = event.getShooter().getPersistentData();
        data.putInt("combat_shots", data.getInt("combat_shots") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(sbw);
java.util.function.Consumer<com.tacz.guns.api.event.common.GunFireEvent> tacz = event -> {
    if (event.getLogicalSide().isServer() && event.getShooter().getTags().contains("combat_crew")) {
        var data = event.getShooter().getPersistentData();
        data.putInt("combat_shots", data.getInt("combat_shots") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(tacz);
java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> death = event -> {
    var killer = event.getSource().getEntity();
    if (event.getEntity().getTags().contains("combat_attacker") && killer != null
        && killer.getTags().contains("combat_crew")) {
        var data = killer.getPersistentData();
        data.putInt("combat_kills", data.getInt("combat_kills") + 1);
    }
};
net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(death);
return Map.of("residents", ids, "crew", crew);`,
  }),
});

const WAVE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.combat-wave",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var a = Args.of(ctx);
var level = ctx.server().overworld();
var ids = new ArrayList<String>();
for (int i = 0; i < a.integer("count"); i++) {
    var mob = EntityType.ZOMBIE.create(level);
    mob.moveTo(a.number("x") - 7 + (i % 2) * 14 + (i / 2 % 3 - 1) * 1.5 + .5, a.number("y"),
        a.number("z") + (i / 6) * 2 + .5, 180, 0);
    mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.09);
    mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(80);
    mob.setBaby(false);
    mob.setPersistenceRequired();
    mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
    mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(a.number("health"));
    mob.setHealth((float) a.number("health"));
    mob.addTag("combat_attacker");
    level.addFreshEntity(mob);
    ids.add(mob.getUUID().toString());
}
return Map.of("spawned", ids, "health", a.number("health"), "native_ai", true);`,
  }),
});

const SURVEY = defineTask<Record<string, any>, any>({
  name: "folkways.promo.combat-survey",
  side: "server",
  source: javaTask({
    imports: IMPORTS,
    body: `
var level = ctx.server().overworld();
var colony = Colonies.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
var crew = new ArrayList<Object>();
var attackers = new ArrayList<Object>();
for (var entity : level.getAllEntities()) {
    if (entity.getTags().contains("combat_crew")) {
        var body = Bodies.of(entity).orElseThrow();
        var row = new LinkedHashMap<String, Object>();
        row.put("uuid", entity.getUUID().toString());
        row.put("pos", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()));
        row.put("health", body.mob().getHealth());
        row.put("doing", body.doing().map(d -> d.what().toString()).orElse("idle"));
        row.put("held", body.mob().getMainHandItem().toString());
        row.put("shots", entity.getPersistentData().getInt("combat_shots"));
        row.put("kills", entity.getPersistentData().getInt("combat_kills"));
        var operator = com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(body.mob());
        row.put("reloading", operator.getDataHolder().reloadStateType.isReloading());
        int loose = 0, loaded = 0;
        for (var stack : body.pack().contents()) {
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (stack.getItem() instanceof IAmmo || id.endsWith("_ammo")
                || id.equals("superbwarfare:large_shell_he")) {
                loose += stack.getCount();
            }
            if (stack.getItem() instanceof IGun gun) {
                loaded += gun.getCurrentAmmoCount(stack) + (gun.hasBulletInBarrel(stack) ? 1 : 0);
            } else if (id.startsWith("superbwarfare:") && !id.endsWith("_ammo") && !id.contains("shell")) {
                loaded += GunData.from(stack).ammo.get();
            }
        }
        row.put("loose", loose);
        row.put("loaded", loaded);
        if (entity.getPersistentData().hasUUID("combat_zone")) {
            var zone = entity.getPersistentData().getUUID("combat_zone");
            row.put("staffing", PatrolStaffing.of(colony).slots(zone).stream().map(s -> s.status()).toList());
            var posts = SentryPosts.of(colony);
            row.put("postStatus",
                posts.zone(level, zone).map(z -> posts.status(level, body.id(), z)).orElse("missing"));
        }
        crew.add(row);
    }
    if (entity.getTags().contains("combat_attacker") && entity instanceof net.minecraft.world.entity.Mob mob) {
        attackers.add(Map.of("uuid", mob.getUUID().toString(), "alive", mob.isAlive(), "health",
            mob.getHealth(), "target", mob.getTarget() == null ? "" : mob.getTarget().getUUID().toString()));
    }
}
return Map.of("time", level.getGameTime(), "crew", crew, "attackers", attackers);`,
  }),
});

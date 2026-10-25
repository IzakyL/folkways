import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";

type Weapon = "bow" | "crossbow";
type Scene = { colony: string; resident: string; monster: string; weapon: Weapon; x: string; y: string; z: string };
type Reading = {
  time: number; residentHealth: number; residentWasAttacked: boolean; targetHealth: number;
  targetMaxHealth: number; targetHasAi: boolean; arrowHit: boolean; damageSource: string;
  ammunition: number; weaponCount: number; weaponDamage: number; charged: boolean;
  session: boolean; using: boolean; useTicks: number; mainItem: string;
  orderMode: string; orderStatus: string; engaged: boolean; distance: number;
  x: number; z: number; arrows: string[]; doing: string; debug: string;
};

test("GUARD uses real bow and crossbow sessions, and retreat stops pending fire", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let active: Scene | undefined;
  const readings: Array<{ weapon: Weapon; step: string; elapsed: number; reading: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `weapon-sessions-server-${run}` },
      client: { instance: `weapon-sessions-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:arrow"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "difficulty normal");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, `fill ${x - 14} ${y - 1} ${z - 10} ${x + 25} ${y - 1} ${z + 16} minecraft:stone`);
    await tryCommand(server, `fill ${x - 14} ${y} ${z - 10} ${x + 25} ${y + 7} ${z + 16} minecraft:air`);

    const selected = process.env.FOLKWAYS_WEAPON_CASE;
    const weapons: Weapon[] = selected === "bow" || selected === "crossbow" ? [selected] : ["bow", "crossbow"];
    for (const weapon of weapons) {
      const scene = await server.run(SETUP, { weapon, x: String(x), y: String(y), z: String(z) });
      active = scene;
      const initial = await server.run(READ, scene);
      readings.push({ weapon, step: "guard issued without provoking target", elapsed: 0, reading: initial });
      expect(initial.ammunition).toBe(8);
      expect(initial.weaponCount).toBe(1);
      expect(initial.weaponDamage).toBe(5);
      expect(initial.residentWasAttacked).toBe(false);
      expect(initial.targetHasAi).toBe(false);

      async function waitFor(step: string, predicate: (reading: Reading) => boolean, budget = 320) {
        for (let elapsed = 0; elapsed < budget; elapsed += 2) {
          await tick.sprint(server, 2);
          const reading = await server.run(READ, scene);
          readings.push({ weapon, step, elapsed, reading });
          expect(reading.residentHealth, `${weapon}: NoAI target must never harm the guard`).toBe(20);
          expect(reading.residentWasAttacked, `${weapon}: this must be proactive GUARD combat`).toBe(false);
          expect(reading.weaponCount, `${weapon}: actual weapon ownership must stay unique`).toBe(1);
          if (predicate(reading)) return reading;
        }
        throw new Error(`${weapon} ${step} timed out: ${JSON.stringify(readings.at(-1)?.reading)}`);
      }

      const hit = await waitFor("native projectile hit", reading => reading.arrowHit
        && reading.targetHealth < reading.targetMaxHealth && reading.ammunition < initial.ammunition
        && reading.weaponDamage > initial.weaponDamage);
      expect(hit.damageSource).toBe("arrow");
      expect(hit.ammunition).toBeLessThan(8);
      expect(hit.weaponDamage).toBeGreaterThan(5);
      expect(readings.some(row => row.weapon === weapon && row.reading.engaged && row.reading.orderMode === "GUARD"),
        `${weapon}: patrol must engage through the actual GUARD order`).toBe(true);
      expect(readings.some(row => row.weapon === weapon && row.reading.session && row.reading.using),
        `${weapon}: the actual runner must sustain the native draw or reload`).toBe(true);

      // The world is frozen between sprint calls. Replace GUARD while the next native draw/reload is incomplete.
      const interrupted = await waitFor("next draw or reload before retreat", reading => reading.session
        && reading.using && reading.useTicks >= 3 && reading.useTicks <= 12, 240);
      await server.run(RETREAT, scene);
      const released = await waitFor("retreat releases weapon session", reading => reading.orderMode === "RETREAT"
        && !reading.session && !reading.using, 64);
      expect(released.ammunition, `${weapon}: retreat must cancel the pending draw/reload before it spends ammo`)
        .toBe(interrupted.ammunition);
      expect(released.weaponDamage, `${weapon}: cancelling must not cause another native discharge`)
        .toBe(interrupted.weaponDamage);

      // Old arrows may keep flying and may still hurt the target. Only new discharges are forbidden.
      const existingArrows = new Set(released.arrows);
      for (let elapsed = 0; elapsed < 80; elapsed += 4) {
        await tick.sprint(server, 4);
        const reading = await server.run(READ, scene);
        readings.push({ weapon, step: "retreat prevents new discharges", elapsed, reading });
        expect(reading.orderMode).toBe("RETREAT");
        expect(reading.session).toBe(false);
        expect(reading.using).toBe(false);
        expect(reading.ammunition).toBe(released.ammunition);
        expect(reading.weaponDamage).toBe(released.weaponDamage);
        expect(reading.weaponCount).toBe(1);
        expect(reading.residentHealth).toBe(20);
        expect(reading.arrows.every(id => existingArrows.has(id)), `${weapon}: retreat must not create another arrow`).toBe(true);
      }
      await server.run(CLEANUP, scene);
      active = undefined;
      await tick.sprint(server, 4);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && active) {
      try { await pair.server.run(CLEANUP, active); } catch { /* Preserve the original failure and capture pair evidence. */ }
    }
    await testInfo.attach("weapon-session-readings.json", {
      body: JSON.stringify(readings, null, 2), contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.entity.projectile.AbstractArrow",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "net.minecraft.world.item.CrossbowItem",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.component.DataComponents",
  "net.minecraft.network.chat.Component", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
];

const SETUP = defineTask<{ weapon: Weapon; x: string; y: string; z: string }, Scene>({
  name: "folkways.weapons.client.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var colony = Colonies.mint(level.getServer());
var body = PersonBody.RESIDENT.get().create(level);
body.moveTo(x + 2.5, y, z + 2.5);
var weapon = new ItemStack(args.string("weapon").equals("bow") ? Items.BOW : Items.CROSSBOW);
weapon.setDamageValue(5);
weapon.set(DataComponents.CUSTOM_NAME, Component.literal("actual GUARD weapon"));
body.pack().insert(weapon);
body.pack().insert(new ItemStack(Items.ARROW, 8));
level.addFreshEntity(body);
colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
body.joinColony(colony.id());
var monster = EntityType.ZOMBIE.create(level);
monster.setNoAi(true); monster.setPersistenceRequired();
monster.moveTo(x + 8.5, y, z + 2.5);
monster.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80); monster.setHealth(80);
monster.getAttribute(Attributes.ARMOR).setBaseValue(0);
monster.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
level.addFreshEntity(monster);
ResidentCommands.of(colony).guard(body, WorldPos.of(level, new BlockPos(x + 2, y, z + 2)), new Vec3(1, 0, 0));
return Map.of("colony", colony.id().toString(), "resident", body.getUUID().toString(),
  "monster", monster.getUUID().toString(), "weapon", args.string("weapon"),
  "x", args.string("x"), "y", args.string("y"), "z", args.string("z"));
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.weapons.client.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
var target = (Mob)level.getEntity(UUID.fromString(args.string("monster")));
if (body == null || target == null) throw new IllegalStateException("loaded guard and living target are required");
var expected = args.string("weapon").equals("bow") ? Items.BOW : Items.CROSSBOW;
boolean session = body.hasWeaponSession();
var actual = session ? body.getMainHandItem() : body.pack().contents().stream()
  .filter(stack -> stack.is(expected)).findFirst().orElse(ItemStack.EMPTY);
var order = ResidentCommands.of(colony).current(body.id());
var damage = target.getLastDamageSource();
var result = new LinkedHashMap<String, Object>();
result.put("time", level.getGameTime()); result.put("residentHealth", body.getHealth());
result.put("residentWasAttacked", body.getLastHurtByMob() != null);
result.put("targetHealth", target.getHealth()); result.put("targetMaxHealth", target.getMaxHealth());
result.put("targetHasAi", !target.isNoAi());
result.put("arrowHit", damage != null && damage.getDirectEntity() instanceof AbstractArrow && damage.getEntity() == body);
result.put("damageSource", damage == null ? "NONE" : damage.getMsgId());
result.put("ammunition", body.pack().count(Items.ARROW));
result.put("weaponCount", body.pack().count(expected) + (session && body.getMainHandItem().is(expected)
  ? body.getMainHandItem().getCount() : 0));
result.put("weaponDamage", actual.getDamageValue()); result.put("charged", CrossbowItem.isCharged(actual));
result.put("session", session); result.put("using", body.isUsingItem()); result.put("useTicks", body.getTicksUsingItem());
result.put("mainItem", BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()).toString());
result.put("orderMode", order.map(value -> value.mode().name()).orElse("NONE"));
result.put("orderStatus", order.map(value -> value.status().name()).orElse("NONE"));
result.put("engaged", order.map(ResidentCommands.Order::engaged).orElse(false));
result.put("distance", body.distanceTo(target)); result.put("x", body.getX()); result.put("z", body.getZ());
result.put("arrows", level.getEntitiesOfClass(AbstractArrow.class, body.getBoundingBox().inflate(64),
  arrow -> body.getUUID().equals(arrow.getOwner() == null ? null : arrow.getOwner().getUUID()))
  .stream().map(arrow -> arrow.getUUID().toString()).toList());
result.put("doing", body.doing().toString());
result.put("debug", "position=" + body.position() + " doing=" + body.doing() + " balk=" + body.balk()
  + " navDone=" + body.getNavigation().isDone() + " targetPosition=" + target.position());
return result;
` }),
});

const RETREAT = defineTask<Scene, { done: boolean }>({
  name: "folkways.weapons.client.retreat", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var body = (ResidentEntity)level.getEntity(UUID.fromString(args.string("resident")));
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
ResidentCommands.of(colony).retreat(body, WorldPos.of(level, new BlockPos(x - 8, y, z + 2)));
return Map.of("done", true);
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.weapons.client.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var residentId = UUID.fromString(args.string("resident"));
var resident = level.getEntity(residentId);
if (resident != null) {
  level.getEntitiesOfClass(AbstractArrow.class, resident.getBoundingBox().inflate(64),
    arrow -> residentId.equals(arrow.getOwner() == null ? null : arrow.getOwner().getUUID())).forEach(arrow -> arrow.discard());
  resident.discard();
}
var target = level.getEntity(UUID.fromString(args.string("monster")));
if (target != null) target.discard();
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});

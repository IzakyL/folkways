// A four-resident squad patrolling a route of formation stops, for the formation specs: setup, a server reading of
// the group and its members, release and cleanup. Each spec builds its own ground and enemies around it.
import { expect } from "@playwright/test";
import { defineTask, javaTask, tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";

/** One patrol stop: a line of slots from `from` to `to`, facing +x or -x, two ranks deep. */
export type Stop = { from: [number, number, number]; to: [number, number, number]; facing: 1 | -1 };
/** The group under test; `watch` lists cells, as x, y, z in turn, whose standing members each reading reports. */
export type Squad = { colony: string; group: string; enemy: string; watch: number[] };
export type SquadMember = {
  id: string; x: number; y: number; z: number; health: number;
  slot: string; order: string; mode: string; status: string;
  /** Per cell of the squad's `watch` list: whether this member stands on it now. */
  standing: boolean[];
};
export type SquadReading = {
  phase: string; stop: number; members: number; active: number; inSlots: boolean;
  enemyAlive: boolean; enemyHealth: number; enemyMaxHealth: number;
  enemyHasTarget: boolean; residentsProvoked: boolean; ownedWeapons: number[];
  residents: SquadMember[];
};

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
  "net.minecraft.world.entity.Mob", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.colony.api.Claimed",
  "io.github.izakyl.folkways.core.resident.api.Body", "io.github.izakyl.folkways.core.world.api.WorldPos",
  "io.github.izakyl.folkways.core.world.api.Reach", "io.github.izakyl.folkways.vanilla.person.PersonBody",
  "io.github.izakyl.folkways.vanilla.person.PersonContent", "io.github.izakyl.folkways.vanilla.person.ResidentEntity",
  "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
  "io.github.izakyl.folkways.core.item.api.ItemSpec", "net.neoforged.neoforge.common.Tags",
  "io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.core.rts.combat.group.UnitGroups", "io.github.izakyl.folkways.core.rts.combat.group.Deployment",
];

/**
 * Mints a colony of four residents in a column at x+2.5, z+7.5.. two apart, swords and axes in turn in their packs
 * (under the melee loadout when `melee`), groups them and sends the group round `route`.
 */
export const SQUAD_SETUP = defineTask<{ x: number; y: number; z: number; melee: boolean; route: Stop[] }, Omit<Squad, "watch">>({
  name: "folkways.formation.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = (int)args.integer("x"), y = (int)args.integer("y"), z = (int)args.integer("z");
var colony = Colonies.mint(level.getServer());
var bodies = new ArrayList<Body>();
var profiles = LoadoutProfiles.of(colony);
var melee = args.flag("melee", false)
  ? Optional.of(profiles.add(profiles.create("Melee").id(), LoadoutProfile.Entry.weapon(ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS))).id()) : Optional.<UUID>empty();
for (int n = 0; n < 4; n++) {
  var resident = PersonBody.RESIDENT.get().create(level);
  resident.moveTo(x + 2.5, y, z + 7.5 + n * 2);
  resident.pack().insert(new ItemStack(n % 2 == 0 ? Items.IRON_SWORD : Items.IRON_AXE));
  level.addFreshEntity(resident);
  colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
  resident.joinColony(colony.id());
  if (melee.isPresent()) profiles.assign(List.of(resident.id()), melee);
  bodies.add(resident);
}
var group = UnitGroups.of(colony).create(bodies);
var route = new ArrayList<Deployment>();
for (var raw : args.list("route")) {
  var stop = (Map<?, ?>)raw;
  var from = (List<?>)stop.get("from"); var to = (List<?>)stop.get("to");
  route.add(new Deployment(
    WorldPos.of(level, new BlockPos(((Number)from.get(0)).intValue(), ((Number)from.get(1)).intValue(), ((Number)from.get(2)).intValue())),
    WorldPos.of(level, new BlockPos(((Number)to.get(0)).intValue(), ((Number)to.get(1)).intValue(), ((Number)to.get(2)).intValue())),
    new Vec3(((Number)stop.get("facing")).intValue(), 0, 0), new Deployment.Formation(2)));
}
group.patrol(route);
return Map.of("colony", colony.id().toString(), "group", group.id().toString(), "enemy", "");
` }),
});

/** The group's phase and stop, each member's place, slot and order, and the squad's enemy when it has one. */
export const SQUAD_READ = defineTask<Squad, SquadReading>({
  name: "folkways.formation.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
var commands = ResidentCommands.of(colony);
var bodies = colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString()))
  .map(resident -> (Body)level.getEntity(resident.id())).toList();
if (bodies.size() != 4 || bodies.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("four loaded residents required");
Mob enemy = args.string("enemy").isEmpty() ? null : (Mob)level.getEntity(UUID.fromString(args.string("enemy")));
var watch = args.list("watch");
var positions = new ArrayList<Map<String, Object>>();
var ownedWeapons = new ArrayList<Integer>();
for (var body : bodies) {
  var order = commands.current(body.id());
  var slot = group.destinations().get(body.id());
  var standing = new ArrayList<Boolean>();
  for (int i = 0; i + 2 < watch.size(); i += 3) {
    standing.add(Reach.standingAt(body.mob().position(), new BlockPos(((Number)watch.get(i)).intValue(),
      ((Number)watch.get(i + 1)).intValue(), ((Number)watch.get(i + 2)).intValue())));
  }
  var row = new LinkedHashMap<String, Object>();
  row.put("id", body.id().toString()); row.put("x", body.mob().getX()); row.put("y", body.mob().getY()); row.put("z", body.mob().getZ());
  row.put("health", body.mob().getHealth()); row.put("slot", slot == null ? "NONE" : slot.cell().toShortString());
  row.put("order", order.map(value -> value.id().toString()).orElse("NONE"));
  row.put("mode", order.map(value -> value.mode().name()).orElse("NONE"));
  row.put("status", order.map(value -> value.status().name()).orElse("NONE"));
  row.put("standing", standing);
  positions.add(row);
  int count = body.pack().count(Items.IRON_SWORD) + body.pack().count(Items.IRON_AXE);
  if (body.mob() instanceof ResidentEntity resident && resident.hasWeaponSession()) {
    var actual = resident.getMainHandItem();
    if (actual.is(Items.IRON_SWORD) || actual.is(Items.IRON_AXE)) count += actual.getCount();
  }
  ownedWeapons.add(count);
}
var result = new LinkedHashMap<String, Object>();
result.put("phase", group.phase().name()); result.put("stop", group.patrolStop()); result.put("members", group.members().size());
result.put("active", bodies.stream().filter(body -> commands.current(body.id()).isPresent()).count());
result.put("inSlots", group.destinations().size() == 4 && bodies.stream().allMatch(body ->
  Reach.standingAt(body.mob().position(), group.destinations().get(body.id()).block(level))));
result.put("enemyAlive", enemy != null && enemy.isAlive()); result.put("enemyHealth", enemy == null ? 0 : enemy.getHealth());
result.put("enemyMaxHealth", enemy == null ? 0 : enemy.getMaxHealth()); result.put("enemyHasTarget", enemy != null && enemy.getTarget() != null);
result.put("residentsProvoked", bodies.stream().anyMatch(body -> body.mob().getLastHurtByMob() != null));
result.put("ownedWeapons", ownedWeapons); result.put("residents", positions);
return result;
` }),
});

export const SQUAD_RELEASE = defineTask<Squad, { done: boolean }>({
  name: "folkways.formation.release", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var colony = Colonies.of((MinecraftServer)ctx.server(), UUID.fromString(args.string("colony"))).orElseThrow();
UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow().release();
return Map.of("done", true);
` }),
});

/** Releases the group and discards its residents, its colony and its enemy. */
export const SQUAD_CLEANUP = defineTask<Squad, { done: boolean }>({
  name: "folkways.formation.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> {
  UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).ifPresent(group -> group.release());
  for (var resident : List.copyOf(colony.residents())) {
    var entity = level.getEntity(resident.id()); if (entity != null) entity.discard();
  }
  colony.raze();
});
if (!args.string("enemy").isEmpty()) {
  var enemy = level.getEntity(UUID.fromString(args.string("enemy"))); if (enemy != null) enemy.discard();
}
return Map.of("done", true);
` }),
});

/**
 * Reads the squad every `stride` ticks until `done`, for at most `budget` ticks. Every reading must keep all four
 * members alive in the group, and a FAILED group ends the wait.
 */
export async function waitForSquad(server: MinecraftServer, squad: Squad, step: string,
  done: (reading: SquadReading) => boolean, budget: number, stride = 8): Promise<SquadReading> {
  let last: SquadReading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed += stride) {
    await tick.sprint(server, stride);
    last = await server.run(SQUAD_READ, squad);
    expect(last.members, `${step}: actual membership must stay intact`).toBe(4);
    expect(last.residents.every(resident => resident.health > 0), `${step}: all four residents must survive`).toBe(true);
    if (last.phase === "FAILED") throw new Error(`${step}: patrol failed: ${JSON.stringify(last)}`);
    if (done(last)) return last;
  }
  throw new Error(`${step} timed out: ${JSON.stringify(last)}`);
}

import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";

type Scene = { colony: string; group: string; x: string; y: string; z: string };
type Reading = {
  phase: string; stop: number; active: number; members: number; inSlots: boolean;
  people: Array<{ id: string; x: number; y: number; z: number; health: number;
    firstBend: boolean; secondBend: boolean; mode: string; status: string; debug: string }>;
};

test("four residents patrol both ways through single-file bends and two steps", async ({}, testInfo) => {
  test.setTimeout(450_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const evidence: Array<{ step: string; reading: Reading }> = [];
  const firstBend = new Set<string>();
  const secondBend = new Set<string>();
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `formation-terrain-server-${run}` },
      client: { instance: `formation-terrain-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "gamerule doMobSpawning false");
    await tryCommand(server, `fill ${x - 2} ${y} ${z - 2} ${x + 24} ${y + 8} ${z + 24} minecraft:air`);
    scene = await server.run(SETUP, { x: String(x), y: String(y), z: String(z) });

    async function sample(step: string) {
      const reading = await server.run(READ, scene!);
      evidence.push({ step, reading });
      for (const person of reading.people) {
        if (person.firstBend) firstBend.add(person.id);
        if (person.secondBend) secondBend.add(person.id);
      }
      return reading;
    }
    async function waitFor(step: string, stop: number, budget: number) {
      for (let elapsed = 0; elapsed < budget; elapsed += 4) {
        await tick.sprint(server, 4);
        const reading = await sample(step);
        expect(reading.members).toBe(4);
        expect(reading.people.every(person => person.health > 0), "terrain travel must preserve all four residents").toBe(true);
        if (reading.phase === "FAILED") throw new Error(`${step}: ${JSON.stringify(reading)}`);
        if (reading.stop === stop && reading.phase === "HOLDING" && reading.inSlots) return reading;
      }
      throw new Error(`${step} timed out: ${JSON.stringify(evidence.at(-1))}`);
    }
    const initial = await sample("transit column issued");
    expect(initial.phase, "local height matching must preserve sequential column entry").toBe("COLUMN");
    const raised = await waitFor("raised line reached after both bends", 0, 1100);
    expect(raised.active).toBe(4);
    expect(raised.people.every(person => person.mode === "GUARD")).toBe(true);
    expect(raised.people.every(person => Math.abs(person.y - (y + 2)) < .1)).toBe(true);
    expect(firstBend.size, "every entity must physically round the first single-cell bend").toBe(4);
    expect(secondBend.size, "every entity must physically round the second single-cell bend").toBe(4);
    const returned = await waitFor("patrol descends and reforms in its starting room", 1, 1100);
    expect(returned.active).toBe(4);
    expect(returned.people.every(person => Math.abs(person.x - (x + 2.5)) <= .9 && Math.abs(person.y - y) < .1)).toBe(true);
    await server.run(RELEASE, scene);
    const released = await sample("terrain patrol released");
    expect(released.phase).toBe("IDLE");
    expect(released.active).toBe(0);
    for (let elapsed = 0; elapsed < 24; elapsed += 4) await tick.sprint(server, 4);
    expect((await sample("release remains effective")).active).toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair && scene) {
      try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve the original failure. */ }
    }
    await testInfo.attach("formation-terrain-readings.json", {
      body: JSON.stringify(evidence, null, 2), contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
  "net.minecraft.world.level.block.Blocks", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.core.api.terms.Reach", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.group.Deployment",
];

const SETUP = defineTask<{ x: string; y: string; z: string }, Scene>({
  name: "folkways.formation-terrain.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var origin = new BlockPos(x, y - 1, z);
for (int dx = 0; dx < 22; dx++) for (int dz = 0; dz < 22; dz++)
  level.setBlock(origin.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
for (int dx = 6; dx <= 17; dx++) for (int dz = 0; dz < 22; dz++) for (int dy = 1; dy <= 5; dy++)
  level.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
for (int dx = 6; dx <= 10; dx++) for (int dy = 1; dy <= 4; dy++)
  level.setBlock(origin.offset(dx, dy, 10), Blocks.AIR.defaultBlockState(), 3);
for (int dz = 10; dz <= 15; dz++) for (int dy = 1; dy <= 4; dy++)
  level.setBlock(origin.offset(10, dy, dz), Blocks.AIR.defaultBlockState(), 3);
for (int dx = 10; dx <= 17; dx++) {
  int floor = dx < 15 ? 0 : dx == 15 ? 1 : 2;
  for (int dy = 1; dy <= 5; dy++) level.setBlock(origin.offset(dx, dy, 15),
    (dy <= floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
}
for (int dx = 18; dx < 22; dx++) for (int dz = 12; dz <= 18; dz++) for (int dy = 1; dy <= 2; dy++)
  level.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), 3);
var colony = Colonies.mint(level.getServer());
var bodies = new ArrayList<Body>();
for (int n = 0; n < 4; n++) {
  var body = PersonBody.RESIDENT.get().create(level);
  body.moveTo(x + 2.5, y, z + 7.5 + n * 2);
  body.pack().insert(new ItemStack(n % 2 == 0 ? Items.IRON_SWORD : Items.IRON_AXE));
  colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
  body.joinColony(colony.id()); level.addFreshEntity(body); bodies.add(body);
}
var group = UnitGroups.of(colony).create(bodies);
group.patrol(List.of(
  new Deployment(WorldPos.of(level, origin.offset(20, 3, 12)), WorldPos.of(level, origin.offset(20, 3, 18)),
    new Vec3(1, 0, 0), new Deployment.Formation(2)),
  new Deployment(WorldPos.of(level, origin.offset(2, 1, 7)), WorldPos.of(level, origin.offset(2, 1, 13)),
    new Vec3(-1, 0, 0), new Deployment.Formation(2))));
return Map.of("colony", colony.id().toString(), "group", group.id().toString(),
  "x", args.string("x"), "y", args.string("y"), "z", args.string("z"));
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.formation-terrain.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
var commands = ResidentCommands.of(colony);
var bodies = colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString()))
  .map(resident -> (Body)level.getEntity(resident.id())).toList();
if (bodies.size() != 4 || bodies.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("four loaded people required");
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var people = new ArrayList<Map<String, Object>>();
for (var body : bodies) {
  var order = commands.current(body.id());
  var row = new LinkedHashMap<String, Object>();
  row.put("id", body.id().toString()); row.put("x", body.mob().getX()); row.put("y", body.mob().getY()); row.put("z", body.mob().getZ());
  row.put("health", body.mob().getHealth());
  row.put("firstBend", Reach.standingAt(body.mob().position(), new BlockPos(x + 10, y, z + 10)));
  row.put("secondBend", Reach.standingAt(body.mob().position(), new BlockPos(x + 10, y, z + 15)));
  row.put("mode", order.map(value -> value.mode().name()).orElse("NONE"));
  row.put("status", order.map(value -> value.status().name()).orElse("NONE"));
  row.put("debug", "doing=" + body.doing() + " balk=" + body.balk() + " nav=" + body.mob().getNavigation().getPath());
  people.add(row);
}
return Map.of("phase", group.phase().name(), "stop", group.patrolStop(), "active",
  bodies.stream().filter(body -> commands.current(body.id()).isPresent()).count(), "members", group.members().size(),
  "inSlots", group.destinations().size() == 4 && bodies.stream().allMatch(body ->
    Reach.standingAt(body.mob().position(), group.destinations().get(body.id()).block(level))), "people", people);
` }),
});

const RELEASE = defineTask<Scene, { done: boolean }>({
  name: "folkways.formation-terrain.release", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var server = (MinecraftServer)ctx.server();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow().release();
return Map.of("done", true);
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.formation-terrain.cleanup", side: "server",
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
return Map.of("done", true);
` }),
});

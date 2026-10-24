import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";

type Scene = { colony: string; group: string; x: string; y: string; z: string };
type Reading = {
  phase: string; members: number; active: number; actor: string; actorX: number; actorZ: number;
  ingots: number[]; axes: number[]; toolState: boolean; inSlots: boolean;
};

test("resident commands and a four-person formation survive a real world restart safely", async ({}, testInfo) => {
  test.setTimeout(360_000);
  const run = newRunId();
  const options = {
    server: { instance: `commands-server-${run}`, reuseInstanceDir: true, keepInstanceDir: true },
    client: { instance: `commands-client-${run}`, reuseInstanceDir: true, keepInstanceDir: true },
    snapshot: { entityTypes: ["folkways:resident"] },
  };
  let pair: E2EPair | undefined;
  const evidence: Array<{ step: string; reading: Reading }> = [];
  try {
    pair = await launchPair(options);
    let { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, `fill ${x - 5} ${y - 1} ${z - 5} ${x + 30} ${y - 1} ${z + 25} minecraft:stone`);
    await tryCommand(server, `fill ${x - 5} ${y} ${z - 5} ${x + 30} ${y + 5} ${z + 25} minecraft:air`);
    let scene = await server.run(SETUP, { x: String(x), y: String(y), z: String(z) });

    async function wait(step: string, predicate: (reading: Reading) => boolean, budget = 500) {
      for (let elapsed = 0; elapsed < budget; elapsed += 8) {
        await tick.sprint(server, 8);
        const reading = await server.run(READ, scene);
        evidence.push({ step, reading });
        if (predicate(reading)) return reading;
      }
      throw new Error(`${step} timed out: ${JSON.stringify(evidence.at(-1))}`);
    }
    await server.run(CONTROL, { ...scene, op: "move" });
    await wait("move", reading => reading.actor === "NONE" && Math.abs(reading.actorX - x - 6.5) < 1);
    await server.run(CONTROL, { ...scene, op: "hold" });
    await wait("hold", reading => reading.actor === "HOLDING");
    await server.run(CONTROL, { ...scene, op: "retreat" });
    await wait("retreat", reading => reading.actor === "HOLDING" && Math.abs(reading.actorX - x - 2.5) < 1);
    await server.run(CONTROL, { ...scene, op: "release" });
    expect((await server.run(READ, scene)).actor).toBe("NONE");

    await mustCommand(server, `fill ${x + 9} ${y} ${z} ${x + 9} ${y + 3} ${z + 21} minecraft:stone`);
    await mustCommand(server, `fill ${x + 9} ${y} ${z + 10} ${x + 9} ${y + 2} ${z + 10} minecraft:air`);
    const { group } = await server.run(CONTROL, { ...scene, op: "group" });
    scene = { ...scene, group };
    const formed = await wait("column then line", reading => reading.phase === "HOLDING" && reading.inSlots, 800);
    expect(formed.members).toBe(4);
    expect(formed.active).toBe(4);
    expect(formed.ingots).toEqual([8, 8, 8, 8]);
    expect(formed.axes).toEqual([1, 1, 1, 1]);
    expect(formed.toolState).toBe(true);
    await mustCommand(server, "save-all flush");
    await pair.teardown();
    pair = undefined;

    // Reopen the same saved server and reconnect a new client JVM.
    pair = await launchPair(options);
    server = pair.server;
    await tick.sprint(server, 20);
    const reloaded = await server.run(READ, scene);
    evidence.push({ step: "world reloaded", reading: reloaded });
    expect(reloaded.members).toBe(4);
    expect(reloaded.phase).toBe("IDLE");
    expect(reloaded.active).toBe(0);
    expect(reloaded.ingots).toEqual([8, 8, 8, 8]);
    expect(reloaded.axes).toEqual([1, 1, 1, 1]);
    expect(reloaded.toolState).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("resident-command-readings.json", {
      body: JSON.stringify(evidence, null, 2), contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.phys.Vec3",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.core.api.terms.Reach", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.plugins.combat.group.UnitGroups", "io.github.izakyl.folkways.plugins.combat.group.Deployment",
];

const SETUP = defineTask<{ x: string; y: string; z: string }, Scene>({
  name: "folkways.commands.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var colony = Colonies.mint(level.getServer());
for (int n = 0; n < 4; n++) {
  var body = PersonBody.RESIDENT.get().create(level);
  body.moveTo(x + 2.5, y, z + 7.5 + n * 2);
  body.pack().insert(new ItemStack(Items.IRON_INGOT, 8));
  var axe = new ItemStack(Items.IRON_AXE); axe.setDamageValue(7); body.pack().insert(axe);
  colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
  body.joinColony(colony.id()); level.addFreshEntity(body);
}
return Map.of("colony", colony.id().toString(), "group", "", "x", args.string("x"), "y", args.string("y"), "z", args.string("z"));
` }),
});

const CONTROL = defineTask<Scene & { op: string }, { group: string }>({
  name: "folkways.commands.control", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var bodies = colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString()))
  .map(resident -> (Body)level.getEntity(resident.id())).toList();
var commands = ResidentCommands.of(colony);
var body = bodies.getFirst();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
String groupId = args.string("group");
switch (args.string("op")) {
  case "move" -> commands.move(body, WorldPos.of(level, new BlockPos(x + 6, y, z + 4)));
  case "hold" -> commands.hold(body, WorldPos.of(level, new BlockPos(x + 12, y, z + 4)));
  case "retreat" -> commands.retreat(body, WorldPos.of(level, new BlockPos(x + 2, y, z + 4)));
  case "release" -> commands.release(body.id());
  case "group" -> {
    var group = UnitGroups.of(colony).create(bodies);
    group.move(new Deployment(WorldPos.of(level, new BlockPos(x + 18, y, z + 7)),
      WorldPos.of(level, new BlockPos(x + 18, y, z + 13)), new Vec3(1, 0, 0), new Deployment.Formation(2)));
    groupId = group.id().toString();
  }
  default -> throw new IllegalArgumentException("unknown order");
}
return Map.of("group", groupId);
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.commands.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var bodies = colony.residents().stream().sorted(Comparator.comparing(resident -> resident.id().toString()))
  .map(resident -> (Body)level.getEntity(resident.id())).toList();
if (bodies.size() != 4 || bodies.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("four loaded residents required");
var commands = ResidentCommands.of(colony);
var group = args.string("group").isEmpty() ? null : UnitGroups.of(colony).group(UUID.fromString(args.string("group"))).orElseThrow();
var result = new LinkedHashMap<String, Object>();
result.put("phase", group == null ? "NONE" : group.phase().name());
result.put("members", group == null ? 0 : group.members().size());
result.put("active", bodies.stream().filter(body -> commands.current(body.id()).isPresent()).count());
result.put("actor", commands.current(bodies.getFirst().id()).map(order -> order.status().name()).orElse("NONE"));
result.put("actorX", bodies.getFirst().mob().getX()); result.put("actorZ", bodies.getFirst().mob().getZ());
result.put("ingots", bodies.stream().map(body -> body.pack().count(Items.IRON_INGOT)).toList());
result.put("axes", bodies.stream().map(body -> body.pack().count(Items.IRON_AXE)).toList());
result.put("toolState", bodies.stream().flatMap(body -> body.pack().contents().stream())
  .filter(stack -> stack.is(Items.IRON_AXE)).allMatch(stack -> stack.getDamageValue() == 7));
result.put("inSlots", group != null && group.destinations().size() == 4 && bodies.stream().allMatch(body ->
  Reach.standingAt(body.mob().position(), group.destinations().get(body.id()).block(level))));
return result;
` }),
});

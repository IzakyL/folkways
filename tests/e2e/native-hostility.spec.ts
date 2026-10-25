import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { tryCommand } from "../shared/harness/bw-helpers";

type Scene = { resident: string; monster: string; colony: string };
type Reading = { health: number; acquired: boolean; arrow: boolean; fleeing: boolean; distance: number; debug: string };

test("native monsters acquire residents, deal real damage, and an unarmed worker flees", async ({}, testInfo) => {
  test.setTimeout(240_000);
  let pair: E2EPair | undefined;
  const readings: Array<{ kind: string; sample: Reading }> = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `native-combat-server-${run}` },
      client: { instance: `native-combat-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:skeleton", "minecraft:arrow"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await mustCommand(server, `gamemode spectator ${player.name}`);
    await mustCommand(server, "difficulty normal");
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, `fill ${x - 15} ${y - 1} ${z - 15} ${x + 30} ${y - 1} ${z + 20} minecraft:stone`);
    await tryCommand(server, `fill ${x - 15} ${y} ${z - 15} ${x + 30} ${y + 5} ${z + 20} minecraft:air`);

    for (const kind of process.env.FOLKWAYS_COMBAT_CASE ? [process.env.FOLKWAYS_COMBAT_CASE] : ["zombie", "skeleton", "flee"]) {
      const scene = await server.run(SETUP, { kind, x: String(x), y: String(y), z: String(z) });
      let acquired = false;
      let hurt = false;
      let fled = false;
      try {
        for (let elapsed = 0; elapsed < 360; elapsed += 4) {
          await tick.sprint(server, 4);
          const sample = await server.run(READ, scene);
          readings.push({ kind, sample });
          if (elapsed % 80 === 0) console.log(kind, elapsed, sample);
          expect(sample.health, `${kind}: resident died; ${sample.debug}`).toBeGreaterThan(0);
          acquired ||= sample.acquired;
          hurt ||= sample.health < 20;
          fled ||= sample.fleeing;
          if (kind === "flee" ? hurt && fled && sample.distance > 8 : hurt && (kind !== "skeleton" || sample.arrow)) break;
        }
        expect(acquired, `${kind}: the native AI must acquire its own target`).toBe(true);
        expect(hurt, `${kind}: the resident must take actual damage`).toBe(true);
        if (kind === "skeleton") expect(readings.some(row => row.kind === kind && row.sample.arrow)).toBe(true);
        if (kind === "flee") {
          expect(fled, "the real labour runner must preempt work with fear").toBe(true);
          expect(readings.filter(row => row.kind === kind).at(-1)!.sample.distance).toBeGreaterThan(8);
        }
      } finally {
        await server.run(CLEANUP, scene);
        await tick.sprint(server, 4);
      }
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("native-combat-readings.json", {
      body: JSON.stringify(readings, null, 2), contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.resident.body.Body",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
];

const SETUP = defineTask<{ kind: string; x: string; y: string; z: string }, Scene>({
  name: "folkways.combat.native.setup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
var colony = Colonies.mint(level.getServer());
var resident = PersonBody.RESIDENT.get().create(level);
resident.setNoAi(!args.string("kind").equals("flee"));
resident.moveTo(x + 12.5, y, z + .5);
level.addFreshEntity(resident);
colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
resident.joinColony(colony.id());
Mob monster = args.string("kind").equals("skeleton") ? EntityType.SKELETON.create(level) : EntityType.ZOMBIE.create(level);
monster.moveTo(x + 2.5, y, z + .5);
monster.setPersistenceRequired();
monster.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
if (args.string("kind").equals("skeleton")) monster.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
level.addFreshEntity(monster);
return Map.of("resident", resident.getUUID().toString(), "monster", monster.getUUID().toString(), "colony", colony.id().toString());
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat.native.read", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var resident = (Body)level.getEntity(UUID.fromString(Args.of(ctx).string("resident")));
var monster = (Mob)level.getEntity(UUID.fromString(Args.of(ctx).string("monster")));
if (resident == null || monster == null) return Map.of("health", 0, "acquired", false, "arrow", false,
  "fleeing", false, "distance", 0, "debug", "missing resident or monster");
var hurt = resident.mob().getLastDamageSource();
var nav = resident.mob().getNavigation();
var path = nav.getPath();
return Map.of("health", resident.mob().getHealth(), "acquired", monster.getTarget() == resident.mob(),
  "arrow", hurt != null && hurt.getDirectEntity() instanceof net.minecraft.world.entity.projectile.AbstractArrow,
  "fleeing", resident.doing().map(doing -> doing.what().getPath().equals("fleeing")
    || doing.toward().filter(id -> id.getPath().equals("fleeing")).isPresent()).orElse(false),
  "distance", resident.mob().distanceTo(monster),
  "debug", "pos=" + resident.mob().position() + " doing=" + resident.doing() + " balk=" + resident.balk()
    + " speed=" + resident.mob().getSpeed() + " navDone=" + nav.isDone()
    + " path=" + (path == null ? "null" : path.getNextNodeIndex() + "/" + path.getNodeCount() + " to " + path.getTarget()));
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.native.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var level = ((MinecraftServer)ctx.server()).overworld();
for (var key : List.of("resident", "monster")) {
  var entity = level.getEntity(UUID.fromString(Args.of(ctx).string(key)));
  if (entity != null) entity.discard();
}
Colonies.of(level.getServer(), UUID.fromString(Args.of(ctx).string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});

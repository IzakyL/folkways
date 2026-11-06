import { expect, test } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";

type Scene = { resident: string; monster: string; colony: string };
type Reading = {
  monsterPresent: boolean;
  health: number;
  targeted: boolean;
  lastDamage: string;
  explosion: boolean;
  arrow: boolean;
  fuseStarted: boolean;
  fleeing: boolean;
  distance: number;
};
type TagState = { probe: boolean; silverfish: boolean; spiderIgnored: boolean };

const IMPORTS = [
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.world.entity.EntityType",
  "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.projectile.AbstractArrow",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.item.Items",
  "net.minecraft.tags.DamageTypeTags",
  "net.minecraft.world.entity.monster.Creeper",
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.resident.body.Body",
  "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
];

test("resident hostility follows native behavior and live data-pack policy", async ({}, testInfo) => {
  test.setTimeout(540_000);
  let pair: E2EPair | undefined;
  const scenes: Scene[] = [];
  try {
    const run = newRunId();
    const packName = `folkways-hostility-${run}`;
    pair = await launchPair({
      server: { instance: `hostility-policy-server-${run}` },
      client: { instance: `hostility-policy-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:skeleton", "minecraft:arrow",
        "minecraft:spider", "minecraft:silverfish", "minecraft:creeper"] },
    });
    const { server } = pair;
    const player = await waitForGroundedPlayer(server);
    const x = Math.round(player.pos.x) + 4;
    const y = Math.round(player.pos.y);
    const z = Math.round(player.pos.z) + 4;
    await world.command(server, `gamemode spectator ${player.name}`);
    await world.command(server, "difficulty normal");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "gamerule doDaylightCycle false");
    // Iron cannot be infested, so the silverfish remains a live target through the tag reload.
    await world.command(server, `fill ${x - 15} ${y - 1} ${z - 15} ${x + 30} ${y - 1} ${z + 20} minecraft:iron_block`);
    await world.command(server, `fill ${x - 15} ${y} ${z - 15} ${x + 30} ${y + 8} ${z + 20} minecraft:air`, { allowFailure: true });

    async function spawn(kind: string): Promise<Scene> {
      const scene = await server.run(SPAWN, { kind, x: String(x), y: String(y), z: String(z) });
      scenes.push(scene);
      return scene;
    }
    async function remove(scene: Scene) {
      await server.run(CLEANUP, scene);
      scenes.splice(scenes.indexOf(scene), 1);
    }
    async function waitForTags(step: string, predicate: (tags: TagState) => boolean) {
      for (let attempt = 0; attempt < 80; attempt++) {
        await tick.sprint(server, 4);
        const tags = await server.run(READ_TAGS, undefined);
        if (predicate(tags)) return tags;
      }
      throw new Error(`${step}: data-pack tags did not reach the expected state`);
    }
    async function waitFor(
      step: string,
      scene: Scene,
      predicate: (reading: Reading) => boolean,
      budget = 320,
      allowMissingMonster = false,
    ) {
      let last: Reading | undefined;
      for (let elapsed = 0; elapsed < budget; elapsed += 8) {
        await tick.sprint(server, 8);
        last = await server.run(READ, scene);
        if (!last.monsterPresent && !allowMissingMonster) {
          throw new Error(`${step}: fixture monster disappeared before the expected behavior`);
        }
        if (predicate(last)) return last;
      }
      throw new Error(`${step} did not occur; last reading: ${JSON.stringify(last)}`);
    }
    async function settle(scene: Scene, ticks = 48) {
      for (let elapsed = 0; elapsed < ticks; elapsed += 8) await tick.sprint(server, 8);
      return server.run(READ, scene);
    }

    // Under the default policy native monsters acquire residents and deal real damage, and an unarmed worker flees.
    for (const kind of ["zombie", "skeleton", "flee"]) {
      const scene = await spawn(kind);
      let acquired = false;
      let hurt = false;
      let fled = false;
      let shot = false;
      let last: Reading | undefined;
      for (let elapsed = 0; elapsed < 360; elapsed += 4) {
        await tick.sprint(server, 4);
        last = await server.run(READ, scene);
        expect(last.health, `${kind}: the resident must survive`).toBeGreaterThan(0);
        acquired ||= last.targeted;
        hurt ||= last.health < 20;
        fled ||= last.fleeing;
        shot ||= last.arrow;
        if (kind === "flee" ? hurt && fled && last.distance > 8 : hurt && (kind !== "skeleton" || last.arrow)) break;
      }
      expect(acquired, `${kind}: the native AI must acquire its own target`).toBe(true);
      expect(hurt, `${kind}: the resident must take actual damage`).toBe(true);
      if (kind === "skeleton") expect(shot, "the skeleton must hurt the resident with a real arrow").toBe(true);
      if (kind === "flee") {
        expect(fled, "the real labour runner must preempt work with fear").toBe(true);
        expect(last!.distance).toBeGreaterThan(8);
      }
      await remove(scene);
      await tick.sprint(server, 4);
    }

    const pack = await server.run(DATAPACK_DIR, undefined);
    if (typeof pack?.dir !== "string" || typeof pack.format !== "number") {
      throw new Error(`could not resolve the world's data-pack directory: ${JSON.stringify(pack)}`);
    }
    const packRoot = path.join(pack.dir, packName);
    const tagRoot = path.join(packRoot, "data", "folkways", "tags", "entity_type");
    mkdirSync(tagRoot, { recursive: true });
    writeFileSync(path.join(packRoot, "pack.mcmeta"), JSON.stringify({
      pack: { pack_format: pack.format, description: "Folkways hostility policy e2e" },
    }, null, 2) + "\n");
    const writeTag = (name: string, values: string[]) => writeFileSync(
      path.join(tagRoot, `${name}.json`), JSON.stringify({ replace: false, values }, null, 2) + "\n",
    );
    writeTag("resident_enemies", []);
    writeTag("ignored_resident_enemies", []);
    writeTag("policy_probe", ["minecraft:bat"]);
    const repository = await server.run(RESCAN_DATAPACKS, { packId: `file/${packName}` });
    expect(repository.available, `the server pack repository should discover file/${packName}`).toBe(true);
    await world.command(server, `datapack enable "file/${packName}"`);
    await world.command(server, "reload");
    await waitForTags("initial data-pack load", tags => tags.probe);

    // A covered midnight scene provides real low-light conditions for vanilla spider AI.
    await world.command(server, "time set midnight");
    await world.command(server, `fill ${x - 12} ${y + 6} ${z - 10} ${x + 20} ${y + 6} ${z + 12} minecraft:stone`);
    const silverfish = await spawn("silverfish");
    await tick.sprint(server, 64);
    const beforeInclude = await server.run(READ, silverfish);
    expect(beforeInclude.targeted, "an unlisted Enemy type must not acquire residents").toBe(false);
    expect(beforeInclude.health).toBe(20);

    // The tag changes while this same silverfish remains loaded; no entity restart is involved.
    writeTag("resident_enemies", ["minecraft:silverfish"]);
    await world.command(server, "reload");
    await waitForTags("include silverfish", tags => tags.silverfish);
    await waitFor("silverfish after tag include", silverfish, reading => reading.targeted && reading.health < 20);
    writeTag("resident_enemies", []);
    await world.command(server, "reload");
    await waitForTags("remove silverfish", tags => !tags.silverfish);
    const afterRemove = await settle(silverfish);
    expect(afterRemove.targeted, "removing a type from the tag must release its live target").toBe(false);
    await remove(silverfish);

    const spider = await spawn("spider");
    await waitFor("dark spider attack", spider, reading => reading.targeted && reading.health < 20);
    writeTag("ignored_resident_enemies", ["minecraft:spider"]);
    await world.command(server, "reload");
    await waitForTags("ignore spider", tags => tags.spiderIgnored);
    const excluded = await settle(spider);
    expect(excluded.targeted, "the ignored tag must stop a loaded spider's pursuit").toBe(false);
    expect(excluded.health, "an excluded spider must stop damaging the resident").toBeGreaterThan(0);
    writeTag("ignored_resident_enemies", []);
    await world.command(server, "reload");
    await waitForTags("unignore spider", tags => !tags.spiderIgnored);
    await waitFor("spider after exclusion removed", spider, reading => reading.targeted && reading.health < excluded.health);
    await remove(spider);

    // Remove the roof and put the spider in daylight; it must not start a new fight.
    await world.command(server, `fill ${x - 12} ${y + 6} ${z - 10} ${x + 20} ${y + 6} ${z + 12} minecraft:air`);
    await world.command(server, "time set noon");
    const brightSpider = await spawn("spider");
    const bright = await settle(brightSpider, 128);
    expect(bright.targeted, "a bright, unprovoked spider must remain neutral to residents").toBe(false);
    expect(bright.health, "daylight must prevent a new spider attack").toBe(20);
    await remove(brightSpider);

    // Creeper keeps its vanilla fuse and explosion; assert the target, ignition, and real blast damage.
    await world.command(server, "time set midnight");
    const creeper = await spawn("creeper");
    await waitFor("creeper target and vanilla fuse", creeper, reading => reading.targeted && reading.fuseStarted, 480);
    await waitFor("creeper explosion damage", creeper,
      reading => reading.explosion && reading.health < 20, 160, true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    for (const scene of scenes) {
      try {
        await pair?.server.run(CLEANUP, scene);
      } catch {
        // Preserve the original assertion failure; pair teardown also captures the world state.
      }
    }
    await pair?.teardown(testInfo);
  }
});

const DATAPACK_DIR = defineTask<void, { dir?: unknown; format?: unknown }>({
  name: "folkways.combat.datapack-dir",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.SharedConstants", "net.minecraft.server.packs.PackType", "net.minecraft.world.level.storage.LevelResource"],
    body: `
return Map.of(
  "dir", ctx.server().getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().toString(),
  "format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
`,
  }),
});

const RESCAN_DATAPACKS = defineTask<{ packId: string }, { available: boolean; selected: boolean }>({
  name: "folkways.combat.policy.rescan-datapacks",
  side: "server",
  source: javaTask({ imports: ["net.minecraft.server.MinecraftServer"], body: `
var repository = ((MinecraftServer)ctx.server()).getPackRepository();
repository.reload();
String packId = Args.of(ctx).string("packId");
return Map.of("available", repository.getAvailableIds().contains(packId), "selected", repository.getSelectedIds().contains(packId));
` }),
});

const READ_TAGS = defineTask<void, TagState>({
  name: "folkways.combat.policy.read-tags",
  side: "server",
  source: javaTask({
    imports: [
      "net.minecraft.core.registries.Registries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.tags.TagKey",
      "net.minecraft.world.entity.EntityType",
    ],
    body: `
var probe = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("folkways", "policy_probe"));
var residentEnemies = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("folkways", "resident_enemies"));
var ignored = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("folkways", "ignored_resident_enemies"));
return Map.of("probe", EntityType.BAT.is(probe), "silverfish", EntityType.SILVERFISH.is(residentEnemies),
  "spiderIgnored", EntityType.SPIDER.is(ignored));
`,
  }),
});

const SPAWN = defineTask<{ kind: string; x: string; y: string; z: string }, Scene>({
  name: "folkways.combat.policy.spawn",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
int x = Integer.parseInt(args.string("x")), y = Integer.parseInt(args.string("y")), z = Integer.parseInt(args.string("z"));
String kind = args.string("kind");
// The default-policy cases stand the resident a little further off, and only the worker that must flee thinks.
boolean byDefault = List.of("zombie", "skeleton", "flee").contains(kind);
var colony = Colonies.mint(level.getServer());
var resident = PersonBody.RESIDENT.get().create(level);
resident.setNoAi(!kind.equals("flee"));
resident.moveTo(x + (byDefault ? 12.5 : 10.5), y, z + .5);
level.addFreshEntity(resident);
colony.hold(PersonContent.ID, Held.Entity.of(resident)).orElseThrow();
Mob monster = switch (kind) {
  case "zombie", "flee" -> EntityType.ZOMBIE.create(level);
  case "skeleton" -> EntityType.SKELETON.create(level);
  case "spider" -> EntityType.SPIDER.create(level);
  case "silverfish" -> EntityType.SILVERFISH.create(level);
  case "creeper" -> EntityType.CREEPER.create(level);
  default -> throw new IllegalArgumentException("unknown hostility fixture: " + kind);
};
monster.moveTo(x + 2.5, y, z + .5);
monster.setPersistenceRequired();
if (byDefault) monster.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
if (kind.equals("skeleton")) monster.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
level.addFreshEntity(monster);
return Map.of("resident", resident.getUUID().toString(), "monster", monster.getUUID().toString(), "colony", colony.id().toString());
` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.combat.policy.read",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
var resident = (Body)level.getEntity(UUID.fromString(args.string("resident")));
var monster = (Mob)level.getEntity(UUID.fromString(args.string("monster")));
if (resident == null) return Map.of("monsterPresent", monster != null, "health", 0, "targeted", false, "lastDamage", "missing",
  "explosion", false, "arrow", false, "fuseStarted", false, "fleeing", false, "distance", 0);
var damage = resident.mob().getLastDamageSource();
return Map.of("monsterPresent", monster != null, "health", resident.mob().getHealth(), "targeted", monster != null && monster.getTarget() == resident.mob(),
  "lastDamage", damage == null ? "none" : damage.getMsgId(),
  "explosion", damage != null && damage.is(DamageTypeTags.IS_EXPLOSION),
  "arrow", damage != null && damage.getDirectEntity() instanceof AbstractArrow,
  "fuseStarted", monster instanceof Creeper creeper && creeper.getSwellDir() > 0,
  "fleeing", resident.doing().map(doing -> doing.what().getPath().equals("fleeing")
    || doing.toward().filter(id -> id.getPath().equals("fleeing")).isPresent()).orElse(false),
  "distance", monster == null ? -1 : resident.mob().distanceTo(monster));
` }),
});

const CLEANUP = defineTask<Scene, { done: boolean }>({
  name: "folkways.combat.policy.cleanup",
  side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var args = Args.of(ctx);
var level = ((MinecraftServer)ctx.server()).overworld();
for (var key : List.of("resident", "monster")) {
  var entity = level.getEntity(UUID.fromString(args.string(key)));
  if (entity != null) entity.discard();
}
Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).ifPresent(colony -> colony.raze());
return Map.of("done", true);
` }),
});

import { expect, test } from "@playwright/test";
import { lstatSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, media, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { REPO_ROOT } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

type Scene = Vec & { colony: string; resident: string; monster: string; token: string; expected: string; duty: string; profile: string };
type Gear = { name: string; item: string; count: number; damage: number; minimumDamage: number; place: string; matches: boolean; wearMatches: boolean };
type Reading = { pid: number; tick: number; patch: boolean; held: boolean; session: boolean; nativeSession: boolean;
  firing: boolean; attack: boolean; using: boolean; sprinting: boolean; handEmpty: boolean;
  order: string; profile: string; dutyCount: number; targetHealth: number; targetMax: number; attacker: boolean;
  gear: Gear[]; expectedCount: number; health: number; x: number; z: number };
type Evidence = Array<{ step: string; value: unknown }>;

test("Epic Fight saves a real borrowed weapon, fully exits and reloads the same world without equipment or action leaks", async ({}, info) => {
  test.setTimeout(600_000);
  const run = newRunId();
  const server = { instance: `epicfight-save-server-${run}`, keepInstanceDir: true };
  const client = { instance: `epicfight-save-client-${run}`, keepInstanceDir: true };
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  let verified = false;
  try {
    pair = await launchPair({ config: "blockwright.toml#epicfight", server, client,
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin);
    await mustCommand(pair.server, `gamemode spectator ${player.name}`);
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + .5} 0 10`);
    scene = await pair.server.run(SETUP, { ...origin, token: run });
    const active = await observe(pair, scene, evidence, "actual native session has borrowed and struck with the profile spear", row =>
      row.nativeSession && row.firing && row.attack && row.attacker && row.targetHealth < row.targetMax, 300, false);
    expect(active.gear.find(item => item.name === `${run}/mainhand`)?.place).toBe("mainhand");
    const beforePid = active.pid;

    // Freeze between ticks: remove the threat and run Minecraft's actual full-save command in one server task.
    // This does not serialize/restore the resident by hand or evict an in-memory colony cache.
    const saved = await pair.server.run(SAVE, scene);
    evidence.push({ step: "Minecraft save-all flush while the real weapon loan was active", value: saved });
    expect(saved.sessionBeforeSave).toBe(true);
    expect(saved.result).toBeGreaterThan(0);
    expect(saved.sessionAfterSave).toBe(false);
    scene.expected = saved.expected;
    const onDiskBoundary = await pair.server.run(READ, scene);
    evidence.push({ step: "physical gear immediately after the real save boundary", value: onDiskBoundary });
    physical(onDiskBoundary, scene);
    expect(onDiskBoundary.gear.find(item => item.name === `${run}/mainhand`)?.place).toMatch(/^pack:/);
    const stopped = await world.stop(pair.server, { timeoutMs: 60_000 });
    evidence.push({ step: "graceful server stop waited for actual JVM exit", value: stopped });
    expect(stopped.shutDown && stopped.exited).toBe(true);
    const closed = await pair.game.close();
    evidence.push({ step: "first process close result", value: closed });
    expect(closed.server?.success).toBe(true);
    expect(closed.server?.killed ?? false).toBe(false);
    pair = undefined;

    pair = await launchPair({ config: "blockwright.toml#epicfight",
      server: { ...server, reuseInstanceDir: true }, client: { ...client, reuseInstanceDir: true },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    await waitForGroundedPlayer(pair.server);
    const loaded = await pair.server.run(READ, scene);
    evidence.push({ step: "same resident UUID and physical components loaded from the same world on a new JVM", value: loaded });
    expect(loaded.pid).not.toBe(beforePid);
    physical(loaded, scene);
    expect(loaded.order, "the autonomous fixture has no manual RTS order").toBe("NONE");
    for (let elapsed = 0; elapsed < 40; elapsed++) {
      const row = await pair.server.run(READ, scene);
      evidence.push({ step: `reloaded resident has no old native action at tick ${elapsed}`, value: row });
      physical(row, scene);
      expect(row.session || row.attack || row.using).toBe(false);
      expect(row.handEmpty).toBe(true);
      expect(row.order).toBe("NONE");
      await tick.sprint(pair.server, 1); await frames(pair.client, 2);
    }
    const shot = info.outputPath("epicfight-real-world-reloaded.png");
    await media.screenshot(pair.client, { path: shot, hideGui: false });
    await info.attach("epicfight-real-world-reloaded.png", { path: shot, contentType: "image/png" });

    // A fresh hostile must let the persisted profile and staffing borrow the restored item and land a new native hit.
    scene.monster = await pair.server.run(NEW_THREAT, scene);
    const resumed = await observe(pair, scene, evidence, "a fresh threat after restart lands a real native hit", row =>
      row.nativeSession && row.attack && row.attacker && row.targetHealth < row.targetMax, 300, false);
    expect(resumed.gear.find(item => item.name === `${run}/mainhand`)!.damage)
      .toBeGreaterThan(loaded.gear.find(item => item.name === `${run}/mainhand`)!.damage);
    await pair.server.run(RELEASE, scene);
    const released = await observe(pair, scene, evidence, "staffing withdrawal after reload closes the new action and returns its unique real weapon", row =>
      !row.session && !row.using && row.order === "NONE", 100, false);
    expect(released.handEmpty).toBe(true);
    expect(released.gear.find(item => item.name === `${run}/mainhand`)!.place).toMatch(/^pack:/);
    verified = true;
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const evidencePath = info.outputPath("epicfight-save-restart-evidence.json");
    writeFileSync(evidencePath, JSON.stringify(evidence, null, 2), "utf8");
    await info.attach("epicfight-save-restart-evidence.json", { path: evidencePath, contentType: "application/json" });
    await pair?.teardown(info);
    // Failed fixtures stay intact for diagnosis. Only exact successful test directories are removed after both processes close.
    if (verified) for (const instance of [server.instance, client.instance]) removeTestInstance(instance);
  }
});

function physical(row: Reading, scene: Scene, exact = true) {
  expect(row.patch && row.held).toBe(true);
  expect(row.health).toBe(20);
  expect(row.profile).toBe(scene.profile);
  expect(row.gear).toHaveLength(7);
  if (scene.expected) expect(row.expectedCount).toBe(7);
  for (const item of row.gear) {
    const slot = item.name.slice(scene.token.length + 1);
    expect(item.count).toBe(slot === "ammo" ? 16 : 1);
    expect(item.damage, "physical wear cannot roll back during borrowing or reload").toBeGreaterThanOrEqual(item.minimumDamage);
    if (slot === "ammo" || slot === "supplyshield" || slot === "mainhand" && !row.session) expect(item.place).toMatch(/^pack:/);
    else expect(item.place).toBe(slot);
    if (scene.expected) expect(item.matches || !exact && slot === "mainhand" && item.wearMatches,
      `all components/counts except legitimate new weapon wear for ${item.name} at ${item.place}`).toBe(true);
  }
  expect(new Set(row.gear.map(item => item.name)).size).toBe(7);
}
async function observe(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (row: Reading) => boolean, budget: number, exact = true) {
  let last: Reading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed++) {
    last = await pair.server.run(READ, scene); evidence.push({ step, value: last }); physical(last, scene, exact && !!scene.expected);
    if (done(last)) return last;
    await tick.sprint(pair.server, 1); await frames(pair.client, 2);
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}
function removeTestInstance(instance: string) {
  const root = realpathSync(path.join(REPO_ROOT, ".blockwright", "instances"));
  const target = path.resolve(root, instance);
  if (!/^epicfight-save-(server|client)-[a-zA-Z0-9-]+$/.test(instance)
      || path.dirname(target) !== root || lstatSync(target).isSymbolicLink()
      || realpathSync(target).toLowerCase() !== target.toLowerCase()) throw new Error(`Unsafe test cleanup: ${target}`);
  rmSync(target, { recursive: true });
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal"); await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false"); await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 12} ${p.y - 1} ${p.z - 12} ${p.x + 12} ${p.y - 1} ${p.z + 12} minecraft:stone`);
  const clear = await world.command(pair.server, `fill ${p.x - 12} ${p.y} ${p.z - 12} ${p.x + 12} ${p.y + 7} ${p.z + 12} minecraft:air`, { allowFailure: true });
  if (!clear.success && !clear.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}

const IMPORTS = ["java.util.Set", "java.util.Optional", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile", "io.github.izakyl.folkways.core.api.terms.ItemSpec", "io.github.izakyl.folkways.plugins.patrol.PatrolContent", "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing", "io.github.izakyl.folkways.plugins.combat.DefenseControl", "java.util.UUID", "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.LinkedHashMap",
  "net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation", "net.minecraft.core.component.DataComponents",
  "net.minecraft.network.chat.Component", "net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.TagParser",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.item.ItemEntity", "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.phys.Vec3", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.epicfight.EpicFightWeaponSession",
  "yesman.epicfight.api.animation.types.AttackAnimation"];
const SETUP = defineTask<Vec & { token: string }, Scene>({ name: "folkways.epicfight.lifecycle.real-fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.mint(level.getServer());
var body=PersonBody.RESIDENT.get().create(level);body.moveTo(a.integer("x")+2.5,a.integer("y"),a.integer("z")+6.5,-90,0);
var slots=List.of(EquipmentSlot.MAINHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
var items=List.of(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse("epicfight:iron_spear")),Items.IRON_HELMET,Items.IRON_CHESTPLATE,Items.IRON_LEGGINGS,Items.IRON_BOOTS);
for(int n=0;n<slots.size();n++){var slot=slots.get(n);var stack=new ItemStack(items.get(n));stack.setDamageValue(9+n);stack.set(DataComponents.CUSTOM_NAME,Component.literal(a.string("token")+"/"+slot.getName()));if(n==0)body.pack().insert(stack);else body.setItemSlot(slot,stack);}
var shield=new ItemStack(Items.SHIELD);shield.setDamageValue(10);shield.set(DataComponents.CUSTOM_NAME,Component.literal(a.string("token")+"/supplyshield"));body.pack().insert(shield);
var ammo=new ItemStack(Items.ARROW,16);ammo.set(DataComponents.CUSTOM_NAME,Component.literal(a.string("token")+"/ammo"));body.pack().insert(ammo);body.pack().insert(new ItemStack(Items.BREAD,16));
if(!level.addFreshEntity(body))throw new IllegalStateException("resident spawn failed");colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));level.addFreshEntity(target);
var profiles=LoadoutProfiles.of(colony);var profile=profiles.create("Saved native spear patrol");profiles.add(profile.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(items.get(0))));
for(int n=1;n<slots.size();n++)profiles.add(profile.id(),LoadoutProfile.Entry.armor(slots.get(n),ItemSpec.of(items.get(n))));
profiles.add(profile.id(),LoadoutProfile.Entry.supply(ItemSpec.of(Items.SHIELD),1,1));profiles.add(profile.id(),LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW),16,16));profiles.add(profile.id(),LoadoutProfile.Entry.supply(ItemSpec.of(Items.BREAD),16,8));profiles.assign(List.of(body.id()),Optional.of(profile.id()));
var route=colony.hold(PatrolContent.BEAT,new Held.Line(List.of(WorldPos.of(level,body.blockPosition()),WorldPos.of(level,body.blockPosition().offset(5,0,0))))).orElseThrow();PatrolStaffing.of(colony).configure(level,route.id(),Set.of(profile.id()),1);
var out=new LinkedHashMap<String,Object>();out.put("colony",colony.id().toString());out.put("resident",body.id().toString());out.put("monster",target.getUUID().toString());out.put("token",a.string("token"));var initial=new CompoundTag();var all=new ArrayList<ItemStack>(body.pack().contents());for(var slot:EquipmentSlot.values())all.add(body.getItemBySlot(slot));for(var stack:all){var name=stack.get(DataComponents.CUSTOM_NAME);if(name!=null&&name.getString().startsWith(a.string("token")+"/"))initial.put(name.getString(),stack.save(level.registryAccess()));}out.put("expected",initial.toString());out.put("profile",profile.id().toString());out.put("duty",route.id().toString());for(var key:List.of("x","y","z"))out.put(key,a.integer(key));return out;` }) });
const SAVE = defineTask<Scene, { sessionBeforeSave: boolean; sessionAfterSave: boolean; result: number; expected: string }>({
  name: "folkways.epicfight.lifecycle.real-minecraft-save", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
boolean before=body.hasWeaponSession();if(!before)throw new IllegalStateException("must save an actual borrowed weapon");
var expected=new CompoundTag();var stacks=new ArrayList<ItemStack>(body.pack().contents());for(var slot:EquipmentSlot.values())stacks.add(body.getItemBySlot(slot));
for(var stack:stacks){var name=stack.get(DataComponents.CUSTOM_NAME);if(name!=null&&name.getString().startsWith(a.string("token")+"/"))expected.put(name.getString(),stack.save(level.registryAccess()));}
if(expected.getAllKeys().size()!=7)throw new IllegalStateException("exact physical gear snapshot required");
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();
var result=new java.util.concurrent.atomic.AtomicInteger(-1);var source=server.createCommandSourceStack().withCallback((success,value)->result.set(success?value:-1));
server.getCommands().performPrefixedCommand(source,"save-all flush");
return Map.of("sessionBeforeSave",before,"sessionAfterSave",body.hasWeaponSession(),"result",result.get(),"expected",expected.toString());` }) });
const READ = defineTask<Scene, Reading>({ name: "folkways.epicfight.lifecycle.read-actual-gear", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));if(body==null)throw new IllegalStateException("saved resident UUID is not loaded");
var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();var expected=a.string("expected").isEmpty()?new CompoundTag():TagParser.parseTag(a.string("expected"));
var stacks=new ArrayList<ItemStack>();var places=new ArrayList<String>();for(int n=0;n<body.pack().getContainerSize();n++){stacks.add(body.pack().getItem(n));places.add("pack:"+n);}
for(var slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){if(slot!=EquipmentSlot.MAINHAND||body.hasWeaponSession()){stacks.add(body.getItemBySlot(slot));places.add(slot.getName());}}
for(var drop:level.getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(48))){stacks.add(drop.getItem());places.add("drop:"+drop.getUUID());}
var gear=new ArrayList<Map<String,Object>>();for(int n=0;n<stacks.size();n++){var stack=stacks.get(n);var name=stack.get(DataComponents.CUSTOM_NAME);if(name==null||!name.getString().startsWith(a.string("token")+"/"))continue;
var original=ItemStack.parseOptional(level.registryAccess(),expected.getCompound(name.getString()));var normalized=stack.copy();if(!original.isEmpty())normalized.setDamageValue(original.getDamageValue());gear.add(Map.of("name",name.getString(),"item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),"count",stack.getCount(),"damage",stack.getDamageValue(),"minimumDamage",original.getDamageValue(),"place",places.get(n),"matches",ItemStack.matches(stack,original),"wearMatches",ItemStack.matches(normalized,original)));}
var patch=ResidentEpicFightPatch.of(body);var field=ResidentEntity.class.getDeclaredField("weaponSession");field.setAccessible(true);var session=field.get(body);var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));
var order=DefenseControl.of(colony).current(body.id()).orElse(null);var assigned=LoadoutProfiles.of(colony).assigned(body.id()).orElseThrow();
var out=new LinkedHashMap<String,Object>();out.put("pid",ProcessHandle.current().pid());out.put("tick",level.getGameTime());out.put("patch",patch!=null);out.put("held",body.colonyId().filter(colony.id()::equals).isPresent()&&colony.holdingOf(body.id()).isPresent());out.put("session",session!=null);out.put("nativeSession",session instanceof EpicFightWeaponSession);out.put("firing",session instanceof EpicFightWeaponSession nativeSession&&nativeSession.state().name().equals("FIRING"));
out.put("attack",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);out.put("using",body.isUsingItem());out.put("sprinting",body.isSprinting());out.put("handEmpty",body.getMainHandItem().isEmpty());out.put("order",order==null?"NONE":order.getClass().getName());
out.put("profile",assigned.id().toString());out.put("dutyCount",PatrolStaffing.of(colony).count(UUID.fromString(a.string("duty"))));out.put("targetHealth",target==null?-1:target.getHealth());out.put("targetMax",target==null?-1:target.getMaxHealth());out.put("attacker",target!=null&&target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);out.put("gear",gear);out.put("expectedCount",expected.getAllKeys().size());out.put("health",body.getHealth());out.put("x",body.getX());out.put("z",body.getZ());return out;` }) });
const NEW_THREAT = defineTask<Scene, string>({ name: "folkways.epicfight.lifecycle.fresh-autonomous-threat", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));if(!level.addFreshEntity(target))throw new IllegalStateException("fresh target spawn failed");
PatrolStaffing.of(colony).configure(level,UUID.fromString(a.string("duty")),Set.of(UUID.fromString(a.string("profile"))),1);return target.getUUID().toString();` }) });
const RELEASE = defineTask<Scene, boolean>({ name: "folkways.epicfight.lifecycle.withdraw-production-staffing", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();PatrolStaffing.of(colony).configure(level,UUID.fromString(a.string("duty")),Set.of(),0);return true;` }) });

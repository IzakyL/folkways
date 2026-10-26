import { expect, test, type TestInfo } from "@playwright/test";
import type { BlockwrightInstance } from "@izakyl/blockwright-client";
import { writeFileSync } from "node:fs";
import { defineTask, javaTask, media, minecraft, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

const GUEST_NAME = "EpicFightGuest";
const SKIN = "minecraft:textures/entity/player/wide/steve.png";
const MESH = "epicfight:entity/biped";
type Scene = { colony: string; resident: string; monster: string };
type Native = { tick: number; patch: boolean; epicfight: boolean; bridge: boolean; nativeVersion: string;
  nativeTarget: boolean; attackAnimation: boolean; sessionType: string;
  attacker: boolean; targetHealth: number; targetMaxHealth: number; count: number; components: boolean; damage: number; look: string; war: boolean };
type Draw = { present: boolean; patch: boolean; overlay: boolean; screen: string; epicfight: boolean; bridge: boolean; nativeVersion: string; war: boolean; look: string;
  animation: string; attackAnimation: boolean; draws: number; tick: number; mesh: string; texture: string;
  sourceRenderer: string; entryScale: number };
type Evidence = Array<{ step: string; value: unknown }>;

test("two real Epic Fight clients see one native guard, through guest disconnect and rejoin", async ({}, info) => {
  test.setTimeout(900_000);
  const evidence: Evidence = [];
  let pair: E2EPair | undefined, extraRaw: BlockwrightInstance | undefined, extra: MinecraftClient | undefined, scene: Scene | undefined;
  let primaryError: unknown;
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight",
      server: { instance: `epicfight-multi-server-${run}` }, client: { instance: `epicfight-multi-primary-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    const primary = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(primary.pos.x) + 4, y: Math.round(primary.pos.y), z: Math.round(primary.pos.z) + 4 };
    await flat(pair, origin);
    extraRaw = await pair.game.instances.driver.launchNeoForge("client", {
      config: "blockwright.toml#epicfight_guest", instance: `epicfight-multi-guest-first-${run}` });
    await extraRaw.waitForReady({ timeoutMs: 300_000 });
    extra = minecraft.client(extraRaw);
    const firstPlayers = await online(pair, 2, evidence, "two distinct live logins");
    const primaryPlayer = firstPlayers.find(player => player.name === primary.name);
    const guestPlayer = firstPlayers.find(player => player.name === GUEST_NAME);
    expect(primaryPlayer).toBeDefined();
    expect(guestPlayer, "the offline server must admit the actual second login").toBeDefined();
    expect(guestPlayer!.uuid).toMatch(/^[0-9a-f-]{36}$/i);
    expect(primaryPlayer!.uuid).not.toBe(guestPlayer!.uuid);
    expect(new Set(firstPlayers.map(player => player.uuid)).size).toBe(2);
    await positionViewers(pair, origin, primary.name);

    scene = await pair.server.run(SETUP, origin);
    const initial = await pair.server.run(NATIVE, scene);
    evidence.push({ step: "server pinned native spear and unique physical stack", value: initial });
    expect(initial.patch).toBe(true);
    expect(initial.war).toBe(false);
    expect(initial.look).toBe("folkways:steve_wide");
    expect(initial.count).toBe(1);
    expect(initial.components).toBe(true);
    expect(initial.damage).toBe(9);
    expect(initial.targetHealth).toBe(initial.targetMaxHealth);

    let sawServerAttack = false, sawPrimaryAttack = false, sawGuestAttack = false, firstHit: Native | undefined;
    let primaryDraw: Draw | undefined, guestDraw: Draw | undefined;
    for (let attempt = 0; attempt < 340; attempt++) {
      await frames(pair.client, 2); await frames(extra, 2);
      const server = await pair.server.run(NATIVE, scene);
      const a = await pair.client.run(DRAW, scene), b = await extra.run(DRAW, scene);
      sawServerAttack ||= server.sessionType.endsWith(".EpicFightWeaponSession") && server.nativeTarget && server.attackAnimation;
      sawPrimaryAttack ||= a.attackAnimation;
      sawGuestAttack ||= b.attackAnimation;
      if (attempt % 10 === 0) evidence.push({ step: `both clients native combat ${attempt}`, value: { server, primary: a, guest: b } });
      if (sawServerAttack && sawPrimaryAttack && sawGuestAttack && server.attacker
          && server.targetHealth < initial.targetHealth && server.damage > 9
          && a.draws > 0 && b.draws > 0 && !a.overlay && !b.overlay && !a.screen && !b.screen) {
        firstHit = server; primaryDraw = a; guestDraw = b;
        evidence.push({ step: "same resident hit and real patched draw on both clients", value: { server, primary: a, guest: b } });
        break;
      }
      await tick.sprint(pair.server, 1);
    }
    expect(sawServerAttack, "actual native Epic Fight spear attack must target the zombie").toBe(true);
    expect(sawPrimaryAttack && sawGuestAttack, "both clients must receive the native attack animation").toBe(true);
    expect(firstHit, "the spear must physically hit while both clients are connected").toBeDefined();
    physical(firstHit!); drawn(primaryDraw!); drawn(guestDraw!);
    await capture(pair.client, info, "epicfight-multi-primary-first.png");
    await capture(extra, info, "epicfight-multi-guest-first.png");

    // Stop only the second real game process; keep the same server and first client alive.
    evidence.push({ step: "first guest process close", value: await extraRaw.close() });
    extraRaw = undefined; extra = undefined;
    const alone = await online(pair, 1, evidence, "guest has really disconnected");
    expect(alone[0].uuid).toBe(primaryPlayer!.uuid);
    const offlineStart = await pair.server.run(NATIVE, scene);
    evidence.push({ step: "native guard at guest logout", value: offlineStart });
    let offlineHit: Native | undefined;
    for (let attempt = 0; attempt < 240; attempt++) {
      await tick.sprint(pair.server, 1); await frames(pair.client, 2);
      const state = await pair.server.run(NATIVE, scene);
      if (attempt % 10 === 0) evidence.push({ step: `guard while guest offline ${attempt}`, value: state });
      if (state.attacker && state.targetHealth < offlineStart.targetHealth && state.damage > offlineStart.damage) {
        offlineHit = state; break;
      }
    }
    expect(offlineHit, "native guard must keep landing real hits after the guest leaves").toBeDefined();
    physical(offlineHit!);
    const primaryWhileAlone = await freshDraw(pair.client, scene, primaryDraw!.draws, pair, evidence, "primary while guest offline");
    drawn(primaryWhileAlone);

    // A fresh instance uses the same configured account and reconnects to the original server.
    extraRaw = await pair.game.instances.driver.launchNeoForge("client", {
      config: "blockwright.toml#epicfight_guest", instance: `epicfight-multi-guest-rejoin-${run}` });
    await extraRaw.waitForReady({ timeoutMs: 300_000 });
    extra = minecraft.client(extraRaw);
    const rejoined = await online(pair, 2, evidence, "same guest account rejoined original server");
    // Offline servers derive UUIDs from the admitted name, independently of the client's configured profile UUID.
    expect(rejoined.find(player => player.name === GUEST_NAME)?.uuid).toBe(guestPlayer!.uuid);
    expect(rejoined.find(player => player.name === primary.name)?.uuid).toBe(primaryPlayer!.uuid);
    await positionViewers(pair, origin, primary.name);
    const primaryBaseline = await pair.client.run(DRAW, scene);
    const guestBaseline = await extra.run(DRAW, scene);
    const primaryAgain = await freshDraw(pair.client, scene, primaryBaseline.draws, pair, evidence, "primary after rejoin");
    const guestAgain = await freshDraw(extra, scene, guestBaseline.draws, pair, evidence, "new guest after rejoin");
    drawn(primaryAgain); drawn(guestAgain);
    const final = await pair.server.run(NATIVE, scene);
    evidence.push({ step: "same resident, two server players and one real spear after rejoin", value: { players: rejoined, server: final, primary: primaryAgain, guest: guestAgain } });
    physical(final);
    expect(final.damage).toBeGreaterThanOrEqual(offlineHit!.damage);
    expect(final.targetHealth).toBeLessThanOrEqual(offlineHit!.targetHealth);
    await capture(pair.client, info, "epicfight-multi-primary-rejoined.png");
    await capture(extra, info, "epicfight-multi-guest-rejoined.png");
  } catch (error) {
    primaryError = error;
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-multi-primary-failure.png"); } catch { /* preserve original error */ } }
    if (extra) { try { await capture(extra, info, "epicfight-multi-guest-failure.png"); } catch { /* preserve original error */ } }
    throw error;
  } finally {
    let cleanupError: unknown;
    const file = info.outputPath("epicfight-multiplayer-evidence.json");
    writeFileSync(file, JSON.stringify(evidence, null, 2), "utf8");
    try { await info.attach("epicfight-multiplayer-evidence.json", { path: file, contentType: "application/json" }); } catch (error) { cleanupError ??= error; }
    if (extraRaw) { try { await extraRaw.close(); } catch (error) { cleanupError ??= error; } }
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* preserve original error */ } }
    try { await pair?.teardown(info); } catch (error) { cleanupError ??= error; }
    if (primaryError === undefined && cleanupError !== undefined) throw cleanupError;
  }
});

function physical(state: Native) {
  expect(state.patch).toBe(true);
  expect(state.epicfight && state.bridge).toBe(true);
  expect(state.nativeVersion).toBe("21.17.3.1");
  expect(state.count, "pack, borrowed native hand and nearby drops together hold one spear").toBe(1);
  expect(state.components, "the real spear retains its components except legitimate durability").toBe(true);
  expect(state.damage).toBeGreaterThan(9);
}
function drawn(draw: Draw) {
  expect(draw.present && draw.patch && draw.epicfight && draw.bridge).toBe(true);
  expect(draw.overlay || !!draw.screen, "screenshots must expose the world rather than a loading/menu overlay").toBe(false);
  expect(draw.nativeVersion).toBe("21.17.3.1");
  expect(draw.war).toBe(false);
  expect(draw.look).toBe("folkways:steve_wide");
  expect(draw.draws).toBeGreaterThan(0);
  expect(draw.mesh).toBe(MESH);
  expect(draw.texture).toBe(SKIN);
  expect(draw.sourceRenderer).toBe("io.github.izakyl.folkways.plugins.person.ResidentSkinRenderer");
  expect(draw.entryScale).toBeGreaterThan(0);
}
async function online(pair: E2EPair, count: number, evidence: Evidence, step: string) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const players = await world.players(pair.server);
    if (players.length === count) { evidence.push({ step, value: players }); return players; }
    await tick.sprint(pair.server, 1);
  }
  throw new Error(`${step}: expected ${count} real players; got ${JSON.stringify(await world.players(pair.server))}`);
}
async function positionViewers(pair: E2EPair, p: Vec, primary: string) {
  await mustCommand(pair.server, `gamemode spectator ${primary}`);
  await mustCommand(pair.server, `gamemode spectator ${GUEST_NAME}`);
  await mustCommand(pair.server, `tp ${primary} ${p.x + 2.5} ${p.y + 1} ${p.z + .5} 0 10`);
  await mustCommand(pair.server, `tp ${GUEST_NAME} ${p.x + 3.5} ${p.y + 1} ${p.z + .5} 0 10`);
}
async function freshDraw(client: MinecraftClient, scene: Scene, prior: number, pair: E2EPair, evidence: Evidence, step: string) {
  let last: Draw | undefined;
  for (let attempt = 0; attempt < 50; attempt++) {
    await frames(client, 3);
    last = await client.run(DRAW, scene);
    evidence.push({ step, value: last });
    if (last.present && !last.overlay && !last.screen && last.draws > prior) return last;
    await tick.sprint(pair.server, 1);
  }
  throw new Error(`${step}: no fresh patched draw: ${JSON.stringify(last)}`);
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal");
  await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false");
  await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 20} ${p.y - 1} ${p.z - 20} ${p.x + 20} ${p.y - 1} ${p.z + 20} minecraft:stone`);
  const cleared = await world.command(pair.server,
    `fill ${p.x - 20} ${p.y} ${p.z - 20} ${p.x + 20} ${p.y + 7} ${p.z + 20} minecraft:air`, { allowFailure: true });
  if (!cleared.success && !cleared.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(cleared));
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const file = info.outputPath(name);
  await media.screenshot(client, { path: file, hideGui: false });
  await info.attach(name, { path: file, contentType: "image/png" });
}

const IMPORTS = ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.LinkedHashMap",
  "net.neoforged.fml.ModList", "net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.item.ItemEntity",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "net.minecraft.world.phys.Vec3",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "yesman.epicfight.api.animation.types.AttackAnimation"];
const SETUP = defineTask<Vec, Scene>({ name: "folkways.epicfight.multiplayer.real-guard", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.mint(level.getServer());
var body=PersonBody.RESIDENT.get().create(level);body.moveTo(a.integer("x")+2.5,a.integer("y"),a.integer("z")+6.5,-90,0);
body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,ResourceLocation.fromNamespaceAndPath("folkways","steve_wide")));
var weapon=new ItemStack(BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("epicfight:iron_spear")).orElseThrow());
weapon.setDamageValue(9);weapon.set(DataComponents.CUSTOM_NAME,Component.literal("Multiplayer native spear"));
body.getPersistentData().put("epicfight_multi_original",weapon.save(level.registryAccess()));body.pack().insert(weapon);
if(!level.addFreshEntity(body))throw new IllegalStateException("resident spawn refused");
colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2000);target.setHealth(2000);
target.getAttribute(Attributes.ARMOR).setBaseValue(0);if(!level.addFreshEntity(target))throw new IllegalStateException("target spawn refused");
ResidentCommands.of(colony).guard(body,WorldPos.of(level,body.blockPosition()),new Vec3(1,0,0));
return Map.of("colony",colony.id().toString(),"resident",body.id().toString(),"monster",target.getUUID().toString());` }) });
const NATIVE = defineTask<Scene, Native>({ name: "folkways.epicfight.multiplayer.native-physical-state", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));if(body==null||target==null)throw new IllegalStateException("resident or target unloaded");
var patch=ResidentEpicFightPatch.of(body);var original=ItemStack.parseOptional(level.registryAccess(),body.getPersistentData().getCompound("epicfight_multi_original"));
var stacks=new ArrayList<ItemStack>(body.pack().contents());if(body.hasWeaponSession())stacks.add(body.getMainHandItem());
var sessionField=ResidentEntity.class.getDeclaredField("weaponSession");sessionField.setAccessible(true);var session=sessionField.get(body);
for(var dropped:level.getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(48)))stacks.add(dropped.getItem());
int count=0,damage=-1;boolean components=true;for(var stack:stacks)if(stack.is(original.getItem())){
 count+=stack.getCount();damage=stack.getDamageValue();var normalized=stack.copy();normalized.setDamageValue(original.getDamageValue());
 components&=ItemStack.isSameItemSameComponents(normalized,original);}
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("patch",patch!=null);
out.put("epicfight",ModList.get().isLoaded("epicfight"));out.put("bridge",ModList.get().isLoaded("folkways_epicfight"));
out.put("nativeVersion",ModList.get().getModContainerById("epicfight").orElseThrow().getModInfo().getVersion().toString());
out.put("nativeTarget",patch!=null&&patch.getTarget()==target);
out.put("attackAnimation",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
out.put("sessionType",session==null?"":session.getClass().getName());
out.put("attacker",target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);
out.put("targetHealth",target.getHealth());out.put("targetMaxHealth",target.getMaxHealth());
out.put("count",count);out.put("components",components);out.put("damage",damage);
out.put("look",body.lookId().orElseThrow().location().toString());out.put("war",ModList.get().isLoaded("folkways_war"));return out;` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.multiplayer.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=level.getEntity(UUID.fromString(a.string("resident")));
if(body instanceof ResidentEntity resident){resident.stopWeaponSession();resident.discard();}
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();
Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });
const DRAW = defineTask<Scene, Draw>({ name: "folkways.epicfight.multiplayer.actual-client-draw", side: "client", source: javaTask({ imports: [
  "java.util.LinkedHashMap", "net.minecraft.client.Minecraft", "net.neoforged.fml.ModList",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity", "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence",
  "yesman.epicfight.api.animation.types.AttackAnimation"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var out=new LinkedHashMap<String,Object>();
out.put("present",false);out.put("patch",false);out.put("epicfight",ModList.get().isLoaded("epicfight"));
out.put("overlay",mc.getOverlay()!=null);out.put("screen",mc.screen==null?"":mc.screen.getClass().getSimpleName());
out.put("bridge",ModList.get().isLoaded("folkways_epicfight"));out.put("war",ModList.get().isLoaded("folkways_war"));
out.put("nativeVersion",ModList.get().getModContainerById("epicfight").orElseThrow().getModInfo().getVersion().toString());
out.put("look","");out.put("animation","");out.put("attackAnimation",false);out.put("draws",0L);out.put("tick",-1L);
out.put("mesh","");out.put("texture","");out.put("sourceRenderer","");out.put("entryScale",0);
for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){
 out.put("present",true);out.put("look",body.lookId().map(key->key.location().toString()).orElse(""));
 var patch=ResidentEpicFightPatch.of(body);out.put("patch",patch!=null);
 if(patch!=null){out.put("animation",patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());
  out.put("attackAnimation",patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);}
 var draw=ResidentRenderEvidence.last(body).orElse(null);
 if(draw!=null){out.put("draws",draw.sequence());out.put("tick",draw.gameTick());out.put("mesh",draw.mesh().toString());
  out.put("texture",draw.texture().toString());out.put("sourceRenderer",draw.sourceRenderer());out.put("entryScale",draw.entryScale());}break;
}return out;` }) });

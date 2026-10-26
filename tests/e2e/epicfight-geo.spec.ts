import { expect, test, type TestInfo } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, media, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { MODEL_ASSETS, placeServerModel, readServerLibrary, reloadServerModels, waitForModelsOnClient } from "../shared/ui/model-folder";
import { mustCommand } from "../shared/ui/world-ui";

const LOOK = "folkways:model/steve/tartaric_acid";
const GEOMETRY = "folkways:geo/resident_models/steve.geo.json";
const TEXTURE = "folkways:textures/entity/resident_models/steve/tartaric_acid.png";
const GEOMETRY_FILE = "assets/folkways/geo/resident_models/steve.geo.json";
type Scene = { colony: string; resident: string; monster: string };
type ServerState = { patch: boolean; nativeTarget: boolean; attackAnimation: boolean; hitByResident: boolean;
  targetHealth: number; targetMaxHealth: number; look: string; armor: string[]; session: boolean };
type Draw = { present: boolean; patch: boolean; overlay: boolean; screen: string; look: string; geometry: string; expectedTexture: string;
  mode: string; resource: boolean; textureResource: boolean; armor: string[]; sequence: number; gameTick: number;
  mesh: string; texture: string; sourceRenderer: string; entryScale: number; meshIdentity: number };
type ReloadState = { state: string; error: string };
type Evidence = Array<{ step: string; value: unknown }>;

test("Epic Fight converts a synced Steve GEO for native combat and rebuilds it after a real client reload", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight",
      server: { instance: `epicfight-geo-server-${run}` }, client: { instance: `epicfight-geo-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie"] } });
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin);
    await mustCommand(pair.server, `gamemode spectator ${player.name}`);
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + .5} 0 10`);

    const placed = await placeServerModel(pair.server, path.join(MODEL_ASSETS, "steve"), "steve", { weight: 400 });
    evidence.push({ step: "real server data pack", value: placed });
    await reloadServerModels(pair.server);
    const library = await readServerLibrary(pair.server);
    evidence.push({ step: "server library after reload", value: { resolved: library.resolved, entries: library.entryIds } });
    expect(library.resolved).toBe(true);
    expect(library.entryIds).toContain(LOOK);
    evidence.push({ step: "client received and settled model resource transfer", value: await waitForModelsOnClient(pair.client, GEOMETRY_FILE) });

    scene = await pair.server.run(SETUP, origin);
    const initial = await pair.server.run(SERVER_STATE, scene);
    evidence.push({ step: "server native patch, pinned look and physical armor", value: initial });
    expect(initial.patch).toBe(true);
    expect(initial.look).toBe(LOOK);
    expect(initial.armor).toEqual(ARMOR);
    expect(initial.targetHealth).toBe(initial.targetMaxHealth);

    let before: Draw | undefined, hit = false;
    for (let attempt = 0; attempt < 240; attempt++) {
      await frames(pair.client, 2);
      const state = await pair.server.run(SERVER_STATE, scene);
      hit ||= state.nativeTarget && state.attackAnimation;
      const draw = await pair.client.run(DRAW, scene);
      if (attempt % 10 === 0) evidence.push({ step: `native combat sample ${attempt}`, value: { state, draw } });
      if (hit && state.hitByResident && state.targetHealth < initial.targetHealth && draw.present && draw.sequence > 0
          && !draw.overlay && !draw.screen) {
        before = draw;
        evidence.push({ step: "post-hit production GEO draw", value: { state, draw } });
        break;
      }
      await tick.sprint(pair.server, 1);
    }
    expect(hit, "a real Epic Fight attack animation targeted the hostile").toBe(true);
    expect(before, "native collision and a production client draw must both occur").toBeDefined();
    assertGeo(before!);
    await capture(pair.client, info, "epicfight-geo-before-reload.png");

    // Minecraft's own reload future drives the production reload listener and its mesh disposal.
    // A unique system property records only that future's completion; it never changes renderer state.
    const token = `folkways.geo.reload.${run}`;
    const started = await pair.client.run(START_RELOAD, { token });
    evidence.push({ step: "Minecraft reloadResourcePacks invoked", value: started });
    expect(started.state).toBe("pending");
    let completion: ReloadState = started;
    for (let attempt = 0; attempt < 120 && completion.state === "pending"; attempt++) {
      await frames(pair.client, 2);
      completion = await pair.client.run(RELOAD_STATUS, { token });
    }
    evidence.push({ step: "real Minecraft reload future result", value: completion });
    expect(completion).toEqual({ state: "complete", error: "" });

    const postReloadBaseline = await pair.client.run(DRAW, scene);
    evidence.push({ step: "draw counter when reload future completed", value: postReloadBaseline });
    let after: Draw | undefined;
    for (let attempt = 0; attempt < 45; attempt++) {
      await frames(pair.client, 3);
      const draw = await pair.client.run(DRAW, scene);
      evidence.push({ step: `post-reload production draw ${attempt}`, value: draw });
      if (draw.present && !draw.overlay && !draw.screen && draw.sequence > Math.max(before!.sequence, postReloadBaseline.sequence)
          && draw.meshIdentity > 0) { after = draw; break; }
      await tick.sprint(pair.server, 1);
    }
    expect(after, "the resident must draw again after the reload future completes").toBeDefined();
    assertGeo(after!);
    expect(after!.mesh).toBe(before!.mesh);
    expect(after!.meshIdentity, "reload must rebuild the converted GEO mesh object").not.toBe(before!.meshIdentity);
    expect(after!.sequence).toBeGreaterThan(before!.sequence);
    await capture(pair.client, info, "epicfight-geo-after-reload.png");
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-geo-failure.png"); } catch { /* retain original failure */ } }
    throw error;
  } finally {
    const file = info.outputPath("epicfight-geo-evidence.json");
    writeFileSync(file, JSON.stringify(evidence, null, 2), "utf8");
    await info.attach("epicfight-geo-evidence.json", { path: file, contentType: "application/json" });
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* retain original failure */ } }
    await pair?.teardown(info);
  }
});

const ARMOR = ["minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots"];
function assertGeo(draw: Draw) {
  expect(draw.present && draw.patch).toBe(true);
  expect(draw.look).toBe(LOOK);
  expect(draw.geometry).toBe(GEOMETRY);
  expect(draw.expectedTexture).toBe(TEXTURE);
  expect(draw.resource && draw.textureResource).toBe(true);
  expect(draw.armor).toEqual(ARMOR);
  expect(draw.mode, "a fallback skin or vanilla biped cannot satisfy GEO acceptance").toBe("converted_geo");
  expect(draw.mesh).toMatch(/^folkways_epicfight:resident_geo\//);
  expect(draw.texture).toBe(TEXTURE);
  expect(draw.sourceRenderer).toBe("io.github.izakyl.folkways.plugins.person.ResidentGeoBackend");
  expect(draw.entryScale).toBeGreaterThan(0);
  expect(draw.meshIdentity).toBeGreaterThan(0);
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const file = info.outputPath(name);
  await media.screenshot(client, { path: file, hideGui: false });
  await info.attach(name, { path: file, contentType: "image/png" });
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal");
  await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false");
  await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 12} ${p.y - 1} ${p.z - 12} ${p.x + 12} ${p.y - 1} ${p.z + 12} minecraft:stone`);
  const clear = await world.command(pair.server,
    `fill ${p.x - 12} ${p.y} ${p.z - 12} ${p.x + 12} ${p.y + 7} ${p.z + 12} minecraft:air`, { allowFailure: true });
  if (!clear.success && !clear.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}

const SERVER_IMPORTS = ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.LinkedHashMap",
  "net.minecraft.server.MinecraftServer", "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.Mob",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3", "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent",
  "io.github.izakyl.folkways.plugins.person.ResidentEntity", "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands", "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch",
  "yesman.epicfight.api.animation.types.AttackAnimation"];
const SETUP = defineTask<Vec, Scene>({ name: "folkways.epicfight.geo.real-fixture", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.mint(level.getServer());
var look=ResourceLocation.parse("folkways:model/steve/tartaric_acid");
if(!ResidentLooks.pool(level.registryAccess()).containsKey(look))throw new IllegalStateException("synced Steve look absent from server pool: "+look);
var body=PersonBody.RESIDENT.get().create(level);body.moveTo(a.integer("x")+2.5,a.integer("y"),a.integer("z")+6.5,-90,0);
body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,look));
body.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.IRON_HELMET));
body.setItemSlot(EquipmentSlot.CHEST,new ItemStack(Items.IRON_CHESTPLATE));
body.setItemSlot(EquipmentSlot.LEGS,new ItemStack(Items.IRON_LEGGINGS));
body.setItemSlot(EquipmentSlot.FEET,new ItemStack(Items.IRON_BOOTS));
body.pack().insert(new ItemStack(Items.IRON_SWORD));
if(!level.addFreshEntity(body))throw new IllegalStateException("resident spawn refused");
colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);
if(!level.addFreshEntity(target))throw new IllegalStateException("target spawn refused");
ResidentCommands.of(colony).guard(body,WorldPos.of(level,body.blockPosition()),new Vec3(1,0,0));
return Map.of("colony",colony.id().toString(),"resident",body.id().toString(),"monster",target.getUUID().toString());` }) });
const SERVER_STATE = defineTask<Scene, ServerState>({ name: "folkways.epicfight.geo.native-state", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));if(body==null||target==null)throw new IllegalStateException("native fixture unloaded");
var patch=ResidentEpicFightPatch.of(body);var out=new LinkedHashMap<String,Object>();out.put("patch",patch!=null);
out.put("nativeTarget",patch!=null&&patch.getTarget()==target);
out.put("attackAnimation",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
out.put("hitByResident",target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);
out.put("targetHealth",target.getHealth());out.put("targetMaxHealth",target.getMaxHealth());
out.put("look",body.lookId().orElseThrow().location().toString());
out.put("armor",List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).stream()
 .map(slot->net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(body.getItemBySlot(slot).getItem()).toString()).toList());
out.put("session",body.hasWeaponSession());return out;` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.geo.cleanup", side: "server", source: javaTask({ imports: SERVER_IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=level.getEntity(UUID.fromString(a.string("resident")));
if(body instanceof ResidentEntity resident){resident.stopWeaponSession();resident.discard();}
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();
Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });
const DRAW = defineTask<Scene, Draw>({ name: "folkways.epicfight.geo.production-draw", side: "client", source: javaTask({ imports: [
  "java.util.List", "java.util.LinkedHashMap", "net.minecraft.client.Minecraft", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.core.registries.BuiltInRegistries", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLook", "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.epicfight.client.ResidentGeoMeshes",
  "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var out=new LinkedHashMap<String,Object>();out.put("present",false);out.put("overlay",mc.getOverlay()!=null);out.put("screen",mc.screen==null?"":mc.screen.getClass().getSimpleName());
for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){
 out.put("present",true);out.put("patch",ResidentEpicFightPatch.of(body)!=null);
 out.put("look",body.lookId().map(key->key.location().toString()).orElse(""));
 var appearance=ResidentLooks.of(body).appearance();if(!(appearance instanceof ResidentLook.Model model))throw new IllegalStateException("client did not receive the pinned model look");
 out.put("geometry",model.geometry().toString());out.put("expectedTexture",model.texture().toString());
 out.put("resource",mc.getResourceManager().getResource(model.geometry()).isPresent());
 out.put("textureResource",mc.getResourceManager().getResource(model.texture()).isPresent());
 out.put("mode",ResidentGeoMeshes.rendering(body));
 out.put("armor",List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).stream()
  .map(slot->BuiltInRegistries.ITEM.getKey(body.getItemBySlot(slot).getItem()).toString()).toList());
 var draw=ResidentRenderEvidence.last(body).orElse(null);
 out.put("sequence",draw==null?0L:draw.sequence());out.put("gameTick",draw==null?-1L:draw.gameTick());
 out.put("mesh",draw==null?"":draw.mesh().toString());out.put("texture",draw==null?"":draw.texture().toString());
 out.put("sourceRenderer",draw==null?"":draw.sourceRenderer());out.put("entryScale",draw==null?0:draw.entryScale());
 out.put("meshIdentity",draw==null||!ResidentGeoMeshes.mesh(body).isPresent()?0:System.identityHashCode(ResidentGeoMeshes.mesh(body).orElseThrow().get()));break;
}return out;` }) });
const START_RELOAD = defineTask<{ token: string }, ReloadState>({ name: "folkways.epicfight.geo.start-real-reload", side: "client", source: javaTask({ imports: ["java.util.Map", "net.minecraft.client.Minecraft"], body: `
var key=Args.of(ctx).string("token");System.setProperty(key,"pending");
Minecraft.getInstance().reloadResourcePacks().whenComplete((unused,error)->
 System.setProperty(key,error==null?"complete":"failed:"+error));
return Map.of("state","pending","error","");` }) });
const RELOAD_STATUS = defineTask<{ token: string }, ReloadState>({ name: "folkways.epicfight.geo.observe-reload-future", side: "client", source: javaTask({ imports: ["java.util.Map"], body: `
var state=System.getProperty(Args.of(ctx).string("token"),"missing");
return Map.of("state",state.startsWith("failed:")?"failed":state,"error",state.startsWith("failed:")?state.substring(7):"");` }) });

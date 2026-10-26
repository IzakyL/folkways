import { expect, test, type TestInfo } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, input, javaTask, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

type Scene = Vec & { colony: string; resident: string; monster: string; player: string; corner: boolean; guardId: string; revokedMotion?: string };
type Evidence = Array<{ step: string; value: unknown }>;
type Pixel = { x: number; y: number; width: number; height: number };
type InputState = { combat: boolean; roster: number; selected: string[]; selection: string[]; owned: boolean; reserved: boolean;
  routeRequest: number; accepted: boolean; intent: string; pending: boolean; points: Vec[]; groupKind: string; groupComplete: boolean;
  buttons: Array<{ kind: string; x: number; y: number; width: number; height: number }> };
type Reading = { tick: number; x: number; y: number; z: number; health: number; height: number; width: number; baseHeight: number; baseWidth: number;
  pose: string; sprinting: boolean; motionToken: string; speed: number; baseSpeed: number; motion: boolean; kind: string; dodge: boolean; animation: string; evasion: boolean;
  order: string; status: string; orderId: string; collisionFree: boolean; supported: boolean; wallSafe: boolean; grounded: boolean;
  weaponCount: number; components: boolean; session: boolean; using: boolean; navigating: boolean; danger: boolean; fuse: number; debug: string };
type Render = { present: boolean; dodge: boolean; animation: string; pose: string; height: number; x: number; y: number; z: number; draws: number; gameTick: number };

// Long-fuse ignition keeps the native animation observable while the server is frozen for pointer input.
// These scenarios verify the decision/action/terrain/command lifecycle, not ordinary 30-tick blast survival.
for (const corner of [false, true]) test(corner
  ? "Epic Fight rejects a wallward STEP and the real evasion runner goes around the corner before pointer replacement"
  : "Epic Fight real danger starts a rendered native STEP and pointer replacement reaches safety without action leaks", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight_combat", server: { instance: `epicfight-terrain-${corner ? "corner" : "plane"}-server-${run}` },
      client: { instance: `epicfight-terrain-${corner ? "corner" : "plane"}-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:creeper", "minecraft:item"] } });
    await pair.client.run(GUI_SCALE);
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin); await mustCommand(pair.server, `gamemode survival ${player.name}`);
    const founded = await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
    await openColonyPanelViaBook(pair.server, pair.client, player.name, founded.standBlock); await screen.dismiss(pair.client);
    scene = await pair.server.run(SETUP, { ...origin, colony: String(founded.evidence.colonyId), player: player.name, corner });
    evidence.push({ step: "real fixture with finite personal gear, standing dimensions and terrain", value: await pair.server.run(READ, scene) });
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + .5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(pair.server, 2); await frames(pair.client, 8);
    await input.key(pair.client, { keyCode: 86 }); await screen.waitFor(pair.client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(pair.client)).find(widget => /^(Combat|战斗|戰鬥)$/.test(widget.label?.trim() ?? ""));
    expect(combat).toBeTruthy(); await screen.clickWidget(pair.client, { label: combat!.label });
    await waitInput(pair, scene, evidence, "real V combat roster", row => row.combat && row.roster === 1);
    const person = await pair.client.run(PROJECT, { ...origin, entity: scene.resident }); visible(person);
    await input.click(pair.client, person, { button: "left" });
    await waitInput(pair, scene, evidence, "real pointer selection is server-owned", row => row.selected[0] === scene!.resident
      && row.selection.length === 1 && row.selection[0] === scene!.resident && row.owned && row.reserved);
    const guarded = await pair.server.run(PREPARE, { ...scene, stage: "guard" }); scene.guardId = guarded.guardId;
    await observe(pair, scene, evidence, "the real guard is stable before any threat", row => row.orderId === scene!.guardId && row.status === "HOLDING" && row.grounded, 160);
    const ignited = await pair.server.run(PREPARE, { ...scene, stage: "ignite" }); scene.monster = ignited.monster;
    evidence.push({ step: "actual ignited creeper with 30000-tick observation fuse; no synthetic motion request", value: ignited });
    const initialDraw = await pair.client.run(RENDER, scene);
    let oldMotion = "";
    if (!corner) {
      const stepping = await observe(pair, scene, evidence, "real CombatEvasion and the ordinary runner enter native STEP", row =>
        row.evasion && row.motion && row.kind === "DODGE" && row.dodge && Math.hypot(row.x - scene!.x - 2.5, row.z - scene!.z - 6.5) > .2, 160);
      expect(stepping.orderId).toBe(scene.guardId); expect(stepping.danger).toBe(true);
      let rendered: Render | undefined;
      for (let attempt = 0; attempt < 30; attempt++) {
        await frames(pair.client, 2); rendered = await pair.client.run(RENDER, scene);
        evidence.push({ step: "client really draws the synchronized native STEP and pose", value: rendered });
        if (rendered.present && rendered.dodge && rendered.draws > initialDraw.draws) break;
      }
      expect(rendered?.dodge, "the real remote client must observe DodgeAnimation, not a test-written motion flag").toBe(true);
      expect(rendered!.draws).toBeGreaterThan(initialDraw.draws);
      expect(Math.hypot(rendered!.x - scene.x - 2.5, rendered!.z - scene.z - 6.5)).toBeGreaterThan(.1);
      await capture(pair.client, info, "epicfight-terrain-native-step.png");
      const beforeClick = await pair.server.run(READ, scene); geometry(beforeClick, scene);
      expect(beforeClick.motion && beforeClick.dodge, "the server is frozen with the original STEP still live before pointer replacement").toBe(true);
      oldMotion = beforeClick.motionToken; expect(oldMotion).not.toBe("");
    } else {
      let sawCornerSide = false, sawFallbackNavigation = false;
      await observe(pair, scene, evidence, "blocked native direction falls back to the same real runner around the wall end", row => {
        if (row.x < scene!.x + 3 && row.z >= scene!.z + 3.3 && row.z <= scene!.z + 9.7)
          expect(row.dodge, "a wallward native trajectory must be rejected while the standing box faces the wall").toBe(false);
        expect(row.orderId).toBe(scene!.guardId);
        sawFallbackNavigation ||= row.evasion && row.navigating && !row.dodge
          && Math.hypot(row.x - scene!.x - 2.5, row.z - scene!.z - 6.5) > .3;
        sawCornerSide ||= row.z < scene!.z + 2.7 || row.z > scene!.z + 10.3;
        return row.evasion && sawFallbackNavigation && sawCornerSide && row.x > scene!.x + 4.35;
      }, 220);
      oldMotion = (await pair.server.run(READ, scene)).motionToken;
      await capture(pair.client, info, "epicfight-terrain-safe-corner-fallback.png");
    }
    const destination = { x: origin.x + 2, y: origin.y, z: origin.z + 16 };
    const ground = await pair.client.run(PROJECT, { ...destination, entity: "" }); visible(ground);
    const before = (await pair.client.run(INPUT_STATE)).routeRequest;
    scene.revokedMotion = oldMotion;
    await input.click(pair.client, ground, { button: "right" });
    await waitInput(pair, scene, evidence, "real ground RMB replaces evasion with the authoritative HOLD route", row => row.routeRequest > before
      && row.accepted && row.intent === "MOVE" && !row.pending && row.points.length === 1
      && row.points[0].x === destination.x && row.points[0].y === destination.y && row.points[0].z === destination.z);
    const replaced = await observe(pair, scene, evidence, "replacement revokes the old native action on the next frame", row =>
      row.orderId !== scene!.guardId && row.order === "HOLD" && (!oldMotion || row.motionToken !== oldMotion), 4);
    // A replacement HOLD may legitimately yield to the still-live danger again. Only the old lease is revoked.
    if (!replaced.motion) {
      expect(replaced.sprinting).toBe(false);
      expect(replaced.height).toBeCloseTo(replaced.baseHeight, 5); expect(replaced.width).toBeCloseTo(replaced.baseWidth, 5);
    }
    await observe(pair, scene, evidence, "the replacement physically reaches the clicked safe destination", row => {
      if (oldMotion) expect(row.motionToken, "the replaced original action cannot own another native frame").not.toBe(oldMotion);
      return Math.hypot(row.x - destination.x - .5, row.z - destination.z - .5) < 1.5 && !row.motion;
    }, 650);
    await waitInput(pair, scene, evidence, "arrival retains player control until explicit RELEASE", row => row.groupComplete && row.owned && row.reserved, 100);
    await capture(pair.client, info, "epicfight-terrain-replacement-arrived.png");
    const release = (await pair.client.run(INPUT_STATE)).buttons.find(button => button.kind === "RELEASE"); expect(release).toBeTruthy();
    await input.click(pair.client, { x: release!.x + release!.width / 2, y: release!.y + release!.height / 2 }, { button: "left" });
    await waitInput(pair, scene, evidence, "RELEASE clears selection and authoritative control", row => !row.owned && !row.reserved && row.selection.length === 0);
    for (let elapsed = 0; elapsed < 40; elapsed++) {
      const row = await sample(pair, scene, evidence, "all temporary action state remains released");
      expect(row.motion || row.dodge || row.sprinting || row.session || row.using).toBe(false);
      expect(row.height).toBeCloseTo(row.baseHeight, 5); expect(row.width).toBeCloseTo(row.baseWidth, 5);
      expect(row.speed).toBeCloseTo(row.baseSpeed, 7);
      await tick.sprint(pair.server, 1); await frames(pair.client, 2);
    }
  } catch (error) {
    pair?.failing(error);
    if (pair) {
      try { if (scene) evidence.push({ step: "failure native server state", value: await pair.server.run(READ, scene) }); } catch { /* Primary failure wins. */ }
      try { if (scene) evidence.push({ step: "failure client animation and draw", value: await pair.client.run(RENDER, scene) }); } catch { /* Primary failure wins. */ }
      try { await capture(pair.client, info, "epicfight-terrain-failure.png"); } catch { /* Client may have closed. */ }
    }
    throw error;
  } finally {
    const path = info.outputPath("epicfight-terrain-evidence.json"); writeFileSync(path, JSON.stringify(evidence, null, 2), "utf8");
    await info.attach("epicfight-terrain-evidence.json", { path, contentType: "application/json" });
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve evidence. */ } }
    await pair?.teardown(info);
  }
});

function geometry(row: Reading, scene: Scene) {
  expect(row.weaponCount, row.debug).toBe(1); expect(row.components, row.debug).toBe(true);
  expect(row.session, "motion never borrows the actual personal weapon").toBe(false);
  expect(row.collisionFree, row.debug).toBe(true); expect(row.supported, row.debug).toBe(true); expect(row.wallSafe, row.debug).toBe(true);
  expect(row.y).toBeGreaterThanOrEqual(scene.y - .08); expect(row.health).toBe(20);
  if (scene.revokedMotion && row.orderId !== scene.guardId)
    expect(row.motionToken, "the first and every later replacement-order sample must revoke the old native lease").not.toBe(scene.revokedMotion);
  if (row.orderId === scene.guardId) expect(Math.hypot(row.x - scene.x - 2.5, row.z - scene.z - 6.5)).toBeLessThan(12);
}
async function sample(pair: E2EPair, scene: Scene, evidence: Evidence, step: string) {
  const row = await pair.server.run(READ, scene); evidence.push({ step, value: row }); geometry(row, scene); return row;
}
async function observe(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (row: Reading) => boolean, budget: number) {
  let last: Reading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed++) {
    last = await sample(pair, scene, evidence, step); if (done(last)) return last;
    await tick.sprint(pair.server, 1); await frames(pair.client, 2);
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}
async function waitInput(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (row: InputState) => boolean, budget = 60) {
  let last: InputState | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed++) {
    await tick.sprint(pair.server, 1); await frames(pair.client, 3); await sample(pair, scene, evidence, step);
    last = { ...await pair.client.run(INPUT_STATE), ...await pair.server.run(CONTROL_STATE, scene) }; evidence.push({ step, value: last });
    if (done(last)) return last;
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}
function visible(point: Pixel) { expect(point.x).toBeGreaterThan(5); expect(point.x).toBeLessThan(point.width - 5); expect(point.y).toBeGreaterThan(40); expect(point.y).toBeLessThan(point.height - 65); }
async function capture(client: MinecraftClient, info: TestInfo, name: string) { const path = info.outputPath(name); await media.screenshot(client, { path }); await info.attach(name, { path, contentType: "image/png" }); }
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal"); await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false"); await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 20} ${p.y - 1} ${p.z - 20} ${p.x + 20} ${p.y - 1} ${p.z + 20} minecraft:stone`);
  const clear = await world.command(pair.server, `fill ${p.x - 20} ${p.y} ${p.z - 20} ${p.x + 20} ${p.y + 7} ${p.z + 20} minecraft:air`, { allowFailure: true });
  if (!clear.success && !(clear.output.length === 1 && clear.output[0].trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}

const IMPORTS = ["net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component", "net.minecraft.nbt.CompoundTag", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.ai.attributes.Attributes",
  "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB", "net.minecraft.world.level.block.Blocks",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
  "io.github.izakyl.folkways.core.api.resident.body.PersonalItems", "io.github.izakyl.folkways.core.api.resident.body.Equipment", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands", "io.github.izakyl.folkways.plugins.combat.motion.CombatEvasion", "io.github.izakyl.folkways.plugins.combat.CombatReadiness",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "yesman.epicfight.api.animation.types.DodgeAnimation"];
const SETUP = defineTask<Vec & { colony: string; player: string; corner: boolean }, Scene>({ name: "folkways.epicfight.terrain.fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
for(var original:List.copyOf(colony.residents())){var loaded=level.getEntity(original.id());if(loaded!=null)loaded.discard();}
int x=(int)a.integer("x"),y=(int)a.integer("y"),z=(int)a.integer("z");var body=PersonBody.RESIDENT.get().create(level);body.moveTo(x+2.5,y,z+6.5,-90,0);body.getRandom().setSeed(7901);
var weapon=new ItemStack(Items.IRON_SWORD);weapon.setDamageValue(7);weapon.set(DataComponents.CUSTOM_NAME,Component.literal("terrain motion keeps real gear"));PersonalItems.mark(body,weapon,"mainhand");body.pack().setItem(0,weapon);Equipment.lock(body,net.minecraft.world.entity.EquipmentSlot.MAINHAND,true);body.pack().insert(new ItemStack(Items.BREAD,16));
body.getPersistentData().put("terrain_original",weapon.save(level.registryAccess()));body.getPersistentData().putDouble("terrain_height",body.getBbHeight());body.getPersistentData().putDouble("terrain_width",body.getBbWidth());body.getPersistentData().putDouble("terrain_speed",body.getAttributeValue(Attributes.MOVEMENT_SPEED));
if(!level.addFreshEntity(body))throw new IllegalStateException("terrain resident must really load before admission");colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
for(var trade:io.github.izakyl.folkways.core.api.vocation.Vocations.all())body.licences().trade(trade).ifPresent(licence->body.licences().allow(licence,false));
if(a.flag("corner",false))for(int dz=-3;dz<=3;dz++)for(int dy=0;dy<3;dy++)level.setBlockAndUpdate(new BlockPos(x+3,y+dy,z+6+dz),Blocks.STONE.defaultBlockState());
return Map.of("x",x,"y",y,"z",z,"colony",colony.id().toString(),"resident",body.id().toString(),"monster","","player",a.string("player"),"corner",a.flag("corner",false),"guardId","");` }) });
const PREPARE = defineTask<Scene & { stage: string }, { monster: string; guardId: string }>({ name: "folkways.epicfight.terrain.actual-guard-and-danger", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
if(a.string("stage").equals("guard")){var guard=ResidentCommands.of(colony).guard(body,WorldSpaces.at(body),new Vec3(1,0,0)).deliberateGuard();return Map.of("monster","","guardId",guard.id().toString());}
var current=ResidentCommands.of(colony).current(body.id()).orElseThrow();if(!current.id().toString().equals(a.string("guardId"))||current.status()!=ResidentCommands.Status.HOLDING)throw new IllegalStateException("the same real guard must be stable before ignition");
var creeper=EntityType.CREEPER.create(level);creeper.setNoAi(true);creeper.setPersistenceRequired();creeper.moveTo(a.integer("x")+.5,a.integer("y"),a.integer("z")+6.5);
var saved=creeper.saveWithoutId(new CompoundTag());saved.putShort("Fuse",(short)30000);saved.putBoolean("ignited",true);creeper.load(saved);if(!level.addFreshEntity(creeper))throw new IllegalStateException("real creeper must load");
if(CombatReadiness.explosiveNear(body).orElse(null)!=creeper)throw new IllegalStateException("real danger query must find the actual ignited creeper");return Map.of("monster",creeper.getUUID().toString(),"guardId",current.id().toString());` }) });
const READ = defineTask<Scene, Reading>({ name: "folkways.epicfight.terrain.observe-physical-boundaries", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));if(body==null)throw new IllegalStateException("terrain resident remains loaded");
var patch=ResidentEpicFightPatch.of(body);if(patch==null)throw new IllegalStateException("native EF patch is required by this profile");var order=ResidentCommands.of(colony).current(body.id()).orElse(null);var data=body.getPersistentData();var box=new AABB(body.getX()-data.getDouble("terrain_width")/2,body.getY(),body.getZ()-data.getDouble("terrain_width")/2,body.getX()+data.getDouble("terrain_width")/2,body.getY()+data.getDouble("terrain_height"),body.getZ()+data.getDouble("terrain_width")/2).deflate(.001);
boolean supported=true;double radius=data.getDouble("terrain_width")*.4;for(double dx:new double[]{-radius,radius})for(double dz:new double[]{-radius,radius}){var floor=BlockPos.containing(body.getX()+dx,body.getY()-.05,body.getZ()+dz);supported&=level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)&&level.getFluidState(floor).isEmpty();}
var field=ResidentEpicFightPatch.class.getDeclaredField("motion");field.setAccessible(true);var motion=field.get(patch);String kind="NONE";if(motion!=null){var request=motion.getClass().getDeclaredField("request");request.setAccessible(true);kind=((io.github.izakyl.folkways.plugins.combat.motion.CombatMotions.Request)request.get(motion)).kind().name();}
var expected=ItemStack.parseOptional(level.registryAccess(),data.getCompound("terrain_original"));var stacks=new ArrayList<ItemStack>(body.pack().contents());if(body.hasWeaponSession())stacks.add(body.getMainHandItem());for(var drop:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,body.getBoundingBox().inflate(48)))stacks.add(drop.getItem());int count=0;boolean components=true;for(var stack:stacks)if(stack.is(Items.IRON_SWORD)){count+=stack.getCount();components&=ItemStack.matches(stack,expected);}
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("x",body.getX());out.put("y",body.getY());out.put("z",body.getZ());out.put("health",body.getHealth());out.put("height",body.getBbHeight());out.put("width",body.getBbWidth());out.put("baseHeight",data.getDouble("terrain_height"));out.put("baseWidth",data.getDouble("terrain_width"));out.put("speed",body.getAttributeValue(Attributes.MOVEMENT_SPEED));out.put("baseSpeed",data.getDouble("terrain_speed"));out.put("pose",body.getPose().name());out.put("sprinting",body.isSprinting());out.put("grounded",body.onGround());out.put("motion",motion!=null);out.put("motionToken",motion==null?"":Integer.toHexString(System.identityHashCode(motion)));out.put("kind",kind);out.put("dodge",patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation);out.put("animation",patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());out.put("evasion",CombatEvasion.active(body,order));out.put("order",order==null?"NONE":order.mode().name());out.put("status",order==null?"NONE":order.status().name());out.put("orderId",order==null?"":order.id().toString());out.put("collisionFree",level.noCollision(body,box));out.put("supported",supported);
boolean wallSafe=!a.flag("corner",false)||box.maxX<=a.integer("x")+3||box.minX>=a.integer("x")+4||box.maxZ<=a.integer("z")+3||box.minZ>=a.integer("z")+10;out.put("wallSafe",wallSafe);out.put("weaponCount",count);out.put("components",components);out.put("session",body.hasWeaponSession());out.put("using",body.isUsingItem());out.put("navigating",!body.getNavigation().isDone());out.put("danger",CombatReadiness.explosiveNear(body).isPresent());var dangerEntity=a.string("monster").isEmpty()?null:level.getEntity(UUID.fromString(a.string("monster")));out.put("fuse",dangerEntity==null?0:dangerEntity.saveWithoutId(new CompoundTag()).getShort("Fuse"));out.put("debug","doing="+body.doing()+",bbox="+box+",delta="+body.getDeltaMovement()+",order="+out.get("order")+"/"+out.get("status")+",motion="+kind+",pack="+body.pack().contents());return out;` }) });
const RENDER = defineTask<Scene, Render>({ name: "folkways.epicfight.terrain.observe-client-step", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft", "io.github.izakyl.folkways.plugins.person.ResidentEntity", "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence", "yesman.epicfight.api.animation.types.DodgeAnimation"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){var patch=ResidentEpicFightPatch.of(body);var draw=ResidentRenderEvidence.last(body).orElse(null);return Map.of("present",true,"dodge",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof DodgeAnimation,"animation",patch==null?"":patch.getAnimator().getPlayerFor(null).getRealAnimation().toString(),"pose",body.getPose().name(),"height",body.getBbHeight(),"x",body.getX(),"y",body.getY(),"z",body.getZ(),"draws",draw==null?0L:draw.sequence(),"gameTick",draw==null?-1L:draw.gameTick());}return Map.of("present",false,"dodge",false,"animation","","pose","","height",0,"x",0,"y",0,"z",0,"draws",0L,"gameTick",-1L);` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.terrain.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();if(!a.string("monster").isEmpty()){var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();}var body=level.getEntity(UUID.fromString(a.string("resident")));if(body instanceof ResidentEntity person){io.github.izakyl.folkways.plugins.combat.motion.CombatMotions.stop(person);person.stopWeaponSession();person.discard();}Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });

const GUI_SCALE = defineTask<void, boolean>({ name: "folkways.epicfight.terrain.gui-scale", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `var mc=Minecraft.getInstance();mc.options.guiScale().set(1);mc.resizeDisplay();return true;` }) });
type ClientInputState = Omit<InputState, "selection" | "owned" | "reserved" | "points" | "groupKind" | "groupComplete">;
const INPUT_STATE = defineTask<void, ClientInputState>({ name: "folkways.epicfight.terrain.observe-real-input-ack", side: "client", source: javaTask({ imports: [
  "java.util.Map", "java.util.List", "java.util.Set", "java.util.LinkedHashMap", "java.util.ArrayList",
  "io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"], body: `
var out=new LinkedHashMap<String,Object>();var handler=OverheadTools.selectedHandler().orElse(null);boolean combat=handler!=null&&handler.getClass().getName().endsWith(".CombatController");
out.put("combat",combat);out.put("roster",CombatPackets.roster().size());out.put("selected",List.of());out.put("buttons",List.of());var route=CombatPackets.route().orElse(null);
out.put("routeRequest",route==null?0L:route.request());out.put("accepted",route!=null&&route.accepted());out.put("intent",route==null?"":route.intent().name());out.put("pending",CombatPackets.routePending());
if(combat){
 var selected=handler.getClass().getDeclaredField("selected");selected.setAccessible(true);out.put("selected",((Set<?>)selected.get(handler)).stream().map(Object::toString).toList());
 var cardsField=handler.getClass().getDeclaredField("cards");cardsField.setAccessible(true);var cards=cardsField.get(handler);var hitsField=cards.getClass().getDeclaredField("hits");hitsField.setAccessible(true);
 var buttons=new ArrayList<Map<String,Object>>();for(var hit:(List<?>)hitsField.get(cards)){
  var actionMethod=hit.getClass().getDeclaredMethod("action");actionMethod.setAccessible(true);var action=actionMethod.invoke(hit);var kindMethod=action.getClass().getDeclaredMethod("kind");kindMethod.setAccessible(true);
  var row=new LinkedHashMap<String,Object>();row.put("kind",kindMethod.invoke(action).toString());for(var key:List.of("x","y","width","height")){var accessor=hit.getClass().getDeclaredMethod(key);accessor.setAccessible(true);row.put(key,accessor.invoke(hit));}buttons.add(row);
 }out.put("buttons",buttons);
}return out;` }) });
const CONTROL_STATE = defineTask<Scene, Pick<InputState, "selection" | "owned" | "reserved" | "points" | "groupKind" | "groupComplete">>({
  name: "folkways.epicfight.terrain.observe-authoritative-control", side: "server", source: javaTask({ imports: [...IMPORTS,
    "io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership", "io.github.izakyl.folkways.plugins.combat.group.UnitGroups"], body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var body=(ResidentEntity)player.serverLevel().getEntity(UUID.fromString(a.string("resident")));var ownership=CombatSelectionOwnership.of(colony);var snapshot=ownership.snapshot(player);var group=UnitGroups.of(colony).ofResident(body.id()).orElse(null);
return Map.of("selection",snapshot.selection().stream().map(Object::toString).toList(),"owned",ownership.owns(player,body),"reserved",ownership.reserved(body.id()),
 "points",group==null?List.of():group.pointRoute().stream().map(point->Map.of("x",point.cell().getX(),"y",point.cell().getY(),"z",point.cell().getZ())).toList(),
 "groupKind",group==null?"NONE":group.commandKind().name(),"groupComplete",group!=null&&group.routeComplete());` }) });
const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.epicfight.terrain.observe-world-projection", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB"],
    body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var type=Class.forName("io.github.izakyl.folkways.war.client.CombatProjection");
var constructor=type.getDeclaredConstructor(Minecraft.class);constructor.setAccessible(true);var projection=constructor.newInstance(mc);
double left,top,right,bottom;
if(!a.string("entity").isEmpty()){
  var id=UUID.fromString(a.string("entity"));net.minecraft.world.entity.Entity found=null;
  for(var entity:mc.level.entitiesForRendering()){if(entity.getUUID().equals(id)){found=entity;break;}}
  if(found==null)throw new IllegalStateException("fixture resident is not client-tracked: "+id);
  var bounds=type.getDeclaredMethod("bounds",AABB.class);bounds.setAccessible(true);var rect=((Optional<?>)bounds.invoke(projection,found.getBoundingBox())).orElseThrow();
  var l=rect.getClass().getDeclaredMethod("left");l.setAccessible(true);left=((Number)l.invoke(rect)).doubleValue();
  var t=rect.getClass().getDeclaredMethod("top");t.setAccessible(true);top=((Number)t.invoke(rect)).doubleValue();
  var r=rect.getClass().getDeclaredMethod("right");r.setAccessible(true);right=((Number)r.invoke(rect)).doubleValue();
  var b=rect.getClass().getDeclaredMethod("bottom");b.setAccessible(true);bottom=((Number)b.invoke(rect)).doubleValue();
}else{
  var point=type.getDeclaredMethod("point",Vec3.class);point.setAccessible(true);
  var pixel=((Optional<?>)point.invoke(projection,new Vec3(a.integer("x")+0.5,a.integer("y")+0.12,a.integer("z")+0.5))).orElseThrow();
  var x=pixel.getClass().getDeclaredMethod("x");x.setAccessible(true);left=right=((Number)x.invoke(pixel)).doubleValue();
  var y=pixel.getClass().getDeclaredMethod("y");y.setAccessible(true);top=bottom=((Number)y.invoke(pixel)).doubleValue();
}
return Map.of("x",(left+right)/2,"y",(top+bottom)/2,"left",left,"top",top,"right",right,"bottom",bottom,
  "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());`,
  }),
});

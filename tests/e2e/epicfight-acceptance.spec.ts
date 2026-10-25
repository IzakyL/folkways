import { expect, test, type TestInfo } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { defineTask, input, javaTask, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

type Weapon = "minecraft:iron_sword" | "epicfight:iron_spear";
type Scene = Vec & { colony: string; resident: string; monster: string; weapon: Weapon; player: string };
type Reading = {
  tick: number; patch: boolean; war: boolean; session: boolean; sessionType: string; sessionState: string;
  animation: string; attackAnimation: boolean; animationTime: number; preContact: boolean; attacking: boolean; inaction: boolean;
  authorized: boolean; nativeTarget: boolean; targetHealth: number; targetMaxHealth: number; attacker: boolean;
  targetHasAi: boolean; residentHealth: number; weaponCount: number; components: boolean; weaponDamage: number;
  hand: string; using: boolean; sprinting: boolean; livingMotion: string; order: string; orderStatus: string;
  x: number; y: number; z: number; debug: string;
};
type Render = { present: boolean; patch: boolean; draws: number; tick: number; mesh: string; texture: string;
  expectedTexture: string; sourceRenderer: string; entryScale: number; animation: string; attackAnimation: boolean; motion: string; war: boolean };
type Evidence = Array<{ step: string; value: unknown }>;
type Pixel = { x: number; y: number; width: number; height: number };
type InputState = { combat: boolean; roster: number; selected: string[]; selection: string[]; owned: boolean; reserved: boolean;
  routeRequest: number; accepted: boolean; intent: string; pending: boolean; points: Vec[]; groupKind: string; groupComplete: boolean;
  buttons: Array<{ kind: string; x: number; y: number; width: number; height: number }> };

// Java tasks arrange actual world entities and production orders, or read existing state.
// They never write a renderer observation, animation state, or a combat network payload.
test("Epic Fight without War renders real resident skins and lands native sword and spear collisions", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, active: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight", server: { instance: `epicfight-native-server-${run}` },
      client: { instance: `epicfight-native-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin);
    await mustCommand(pair.server, `gamemode spectator ${player.name}`);
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + .5} 0 10`);
    for (const weapon of ["minecraft:iron_sword", "epicfight:iron_spear"] as const) {
      active = await pair.server.run(SETUP, { ...origin, weapon, colony: "", player: player.name, guard: true });
      const initial = await pair.server.run(READ, active);
      evidence.push({ step: `${weapon} initial real stack`, value: initial });
      expect(initial.patch).toBe(true);
      expect(initial.war, "the actual server has no War dependency").toBe(false);
      expect(initial.weaponCount).toBe(1);
      expect(initial.weaponDamage).toBe(9);
      expect(initial.components).toBe(true);
      expect(initial.targetHealth).toBe(initial.targetMaxHealth);
      expect(initial.targetHasAi).toBe(false);
      let sawNativeAttack = false;
      const hit = await observe(pair, active, evidence, `${weapon} native collision`, row => {
        sawNativeAttack ||= row.sessionType.endsWith(".EpicFightWeaponSession") && row.attackAnimation && row.nativeTarget;
        return sawNativeAttack && row.attacker && row.targetHealth < initial.targetHealth && row.weaponDamage > 9;
      }, 450);
      expect(hit.residentHealth).toBe(20);
      const rendered = await render(pair, active, evidence);
      expect(rendered.war, "this profile must exercise the optional integration with no War module").toBe(false);
      expect(rendered.texture).toBe("minecraft:textures/entity/player/wide/steve.png");
      expect(rendered.sourceRenderer).toBe("io.github.izakyl.folkways.plugins.person.ResidentSkinRenderer");
      expect(rendered.mesh, "Steve's wide skin uses EF's real native biped mesh").toBe("epicfight:entity/biped");
      await capture(pair.client, info, `${weapon.replace(":", "-")}-native-hit-rendered.png`);
      // Replace the real runner-owned guard, then inspect its returned physical stack and every later tick.
      await pair.server.run(STOP_GUARD, active);
      const released = await observe(pair, active, evidence, `${weapon} release native hand`, row => !row.session && !row.authorized, 80);
      for (let elapsed = 0; elapsed < 80; elapsed += 2) {
        await tick.sprint(pair.server, 2);
        const row = await pair.server.run(READ, active);
        evidence.push({ step: `${weapon} after cancellation ${elapsed + 2}`, value: row });
        physical(row);
        expect(row.targetHealth, "no delayed native collision remains after the command was replaced").toBe(released.targetHealth);
        expect(row.weaponDamage).toBe(released.weaponDamage);
        expect(row.session).toBe(false);
        expect(row.authorized).toBe(false);
      }
      await pair.server.run(CLEANUP, active); active = undefined;
    }
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-native-failure.png"); } catch { /* Keep the first failure. */ } }
    throw error;
  }
  finally {
    await saveEvidence(info, "epicfight-native-evidence.json", evidence);
    if (pair && active) { try { await pair.server.run(CLEANUP, active); } catch { /* Keep the first failure. */ } }
    await pair?.teardown(info);
  }
});

test("Epic Fight V command pointer MOVE cancels a real pending native attack and physically arrives without ownership leaks", async ({}, info) => {
  test.setTimeout(420_000);
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#epicfight_combat", server: { instance: `epicfight-move-server-${run}` },
      client: { instance: `epicfight-move-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "minecraft:item"] } });
    await pair.client.run(GUI_SCALE);
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await flat(pair, origin); await mustCommand(pair.server, `gamemode survival ${player.name}`);
    const founded = await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
    await openColonyPanelViaBook(pair.server, pair.client, player.name, founded.standBlock);
    await screen.dismiss(pair.client);
    scene = await pair.server.run(SETUP, { ...origin, colony: String(founded.evidence.colonyId), player: player.name, weapon: "minecraft:iron_sword", guard: false });
    await mustCommand(pair.server, `tp ${player.name} ${origin.x + .5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(pair.server, 2); await frames(pair.client, 8);
    await input.key(pair.client, { keyCode: 86 });
    await screen.waitFor(pair.client, "OverheadScreen", { timeoutMs: 8_000 });
    const combat = (await screen.widgets(pair.client)).find(widget => /^(Combat|战斗|戰鬥)$/.test(widget.label?.trim() ?? ""));
    expect(combat, "the combined profile exposes the real War combat tab").toBeTruthy();
    await screen.clickWidget(pair.client, { label: combat!.label });
    await waitInput(pair, scene, evidence, "real V combat roster", value => value.combat && value.roster >= 1);
    const at = await pair.client.run(PROJECT, { ...origin, entity: scene.resident }); visible(at);
    await input.click(pair.client, at, { button: "left" });
    await waitInput(pair, scene, evidence, "real left-click selects exactly the native resident", value =>
      value.selected.length === 1 && value.selected[0] === scene!.resident && value.selection.length === 1
      && value.selection[0] === scene!.resident && value.owned && value.reserved);

    const enemy = await pair.client.run(PROJECT, { ...origin, entity: scene.monster }); visible(enemy);
    const beforeAttack = (await pair.client.run(INPUT_STATE)).routeRequest;
    await input.click(pair.client, enemy, { button: "right" });
    await waitInput(pair, scene, evidence, "right-click hostile receives authoritative ATTACK ACK", value =>
      value.routeRequest > beforeAttack && value.accepted && value.intent === "ATTACK" && !value.pending);
    const pending = await observe(pair, scene, evidence, "real selected sword enters native pre-contact windup", row =>
      row.order === "ATTACK" && row.sessionType.endsWith(".EpicFightWeaponSession") && row.sessionState === "FIRING"
      && row.attackAnimation && row.nativeTarget && row.authorized && row.preContact, 260);
    expect(pending.weaponDamage, "cancelled native windup has not spent its real item yet").toBe(9);
    expect(pending.targetHealth).toBe(pending.targetMaxHealth);
    // Project and click with the server frozen at the observed native pre-contact frame.
    const destination = { x: origin.x - 5, y: origin.y, z: origin.z + 13 };
    const ground = await pair.client.run(PROJECT, { ...destination, entity: "" }); visible(ground);
    const beforeMove = (await pair.client.run(INPUT_STATE)).routeRequest;
    await input.click(pair.client, ground, { button: "right" });
    const moveAck = await waitInput(pair, scene, evidence, "real right-click ground replaces native attack with MOVE", value =>
      value.routeRequest > beforeMove && value.accepted && value.intent === "MOVE" && !value.pending
      && value.groupKind === "DIRECT" && value.points.length === 1
      && value.points[0].x === destination.x && value.points[0].y === destination.y && value.points[0].z === destination.z);
    expect(moveAck.owned && moveAck.reserved).toBe(true);
    const moving = await pair.server.run(READ, scene); evidence.push({ step: "native state immediately after MOVE ACK", value: moving });
    expect(moving.targetHealth).toBe(pending.targetHealth);
    const leaving = await observe(pair, scene, evidence, "MOVE physically departs the native attack position", row => {
      expect(row.targetHealth).toBe(pending.targetHealth); expect(row.weaponDamage).toBe(9); expect(row.authorized).toBe(false);
      return Math.hypot(row.x - pending.x, row.z - pending.z) > .6 && !row.session;
    }, 80);
    const movingDraw = await render(pair, scene, evidence);
    evidence.push({ step: "real moving native renderer and living motion, without forced STEP/sprint", value: { server: leaving, client: movingDraw } });
    await capture(pair.client, info, "epicfight-native-replacement-moving.png");
    const arrived = await observe(pair, scene, evidence, "replacement MOVE physically reaches its clicked destination", row => {
      expect(row.targetHealth, "every observed tick after MOVE retains the old target's health").toBe(pending.targetHealth);
      expect(row.weaponDamage, "cancelled windup spends no durability").toBe(9);
      expect(row.authorized, "old target loses the real native collision permission").toBe(false);
      return Math.hypot(row.x - destination.x - .5, row.z - destination.z - .5) < 1.5 && !row.session;
    }, 500);
    expect(arrived.using).toBe(false);
    const rendered = await render(pair, scene, evidence); expect(rendered.war).toBe(true);
    await waitInput(pair, scene, evidence, "completed physical route retains control until RELEASE", value => value.groupComplete && value.owned && value.reserved, 100);
    await capture(pair.client, info, "epicfight-native-pending-cancel-move-arrived.png");
    // Observe a full additional native-animation interval; no delayed damage or hand ownership may reappear.
    for (let elapsed = 0; elapsed < 80; elapsed++) {
      await tick.sprint(pair.server, 1); await frames(pair.client, 2);
      const row = await pair.server.run(READ, scene); evidence.push({ step: "arrived command has no residual native strike", value: row }); physical(row);
      expect(row.targetHealth).toBe(pending.targetHealth); expect(row.weaponDamage).toBe(9);
      expect(row.session || row.authorized || row.using).toBe(false);
    }
    const release = (await pair.client.run(INPUT_STATE)).buttons.find(button => button.kind === "RELEASE");
    expect(release, "the real rendered cards expose RELEASE").toBeTruthy();
    await input.click(pair.client, { x: release!.x + release!.width / 2, y: release!.y + release!.height / 2 }, { button: "left" });
    await waitInput(pair, scene, evidence, "real RELEASE clears server selection and control ownership", value =>
      value.selected.length === 0 && value.selection.length === 0 && !value.owned && !value.reserved);
    const released = await pair.server.run(READ, scene); physical(released);
    expect(released.order).toBe("NONE"); expect(released.session || released.using || released.sprinting).toBe(false);
    evidence.push({ step: "final unique physical weapon after real RELEASE", value: released });
    // No synthetic STEP is invoked: the saved client render/animation/motion readings show what actually ran.
    await capture(pair.client, info, "epicfight-native-released-no-control-leak.png");
  } catch (error) {
    pair?.failing(error);
    if (pair) { try { await capture(pair.client, info, "epicfight-move-failure.png"); } catch { /* Keep the first failure. */ } }
    throw error;
  } finally {
    await saveEvidence(info, "epicfight-move-evidence.json", evidence);
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* Keep the first failure. */ } }
    await pair?.teardown(info);
  }
});

function visible(point: Pixel) {
  expect(point.x).toBeGreaterThan(5); expect(point.x).toBeLessThan(point.width - 5);
  expect(point.y).toBeGreaterThan(40); expect(point.y).toBeLessThan(point.height - 65);
}
async function waitInput(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (value: InputState) => boolean, attempts = 60) {
  let last: InputState | undefined;
  for (let attempt = 0; attempt < attempts; attempt++) {
    await tick.sprint(pair.server, 1); await frames(pair.client, 3);
    last = { ...await pair.client.run(INPUT_STATE), ...await pair.server.run(CONTROL_STATE, scene) };
    evidence.push({ step, value: last }); if (done(last)) return last;
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}

function physical(row: Reading) {
  expect(row.weaponCount, `actual pack, owned hand and nearby item entities: ${row.debug}`).toBe(1);
  expect(row.components, "the original real item's components survive borrowing, excluding legitimate durability wear").toBe(true);
  expect(row.residentHealth, "the stationary NoAI target never attacked this proactive guard").toBe(20);
}
async function observe(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (row: Reading) => boolean, budget = 300) {
  let last: Reading | undefined;
  for (let elapsed = 0; elapsed < budget; elapsed++) {
    last = await pair.server.run(READ, scene); evidence.push({ step, value: last }); physical(last);
    if (done(last)) return last;
    await tick.sprint(pair.server, 1); await frames(pair.client, 2);
  }
  throw new Error(`${step}: ${JSON.stringify(last)}`);
}
async function render(pair: E2EPair, scene: Scene, evidence: Evidence) {
  let last: Render | undefined;
  const before = await pair.client.run(RENDER, scene);
  for (let attempt = 0; attempt < 30; attempt++) {
    await frames(pair.client, 4); last = await pair.client.run(RENDER, scene);
    evidence.push({ step: "read actual post-draw renderer observation", value: last });
    if (last.present && last.patch && last.draws > before.draws && last.tick >= before.tick) {
      expect(last.texture).toBe(last.expectedTexture); expect(last.mesh).not.toBe("");
      expect(last.entryScale).toBeGreaterThan(0);
      return last;
    }
    await tick.sprint(pair.server, 1);
  }
  throw new Error(`no real patched resident draw: ${JSON.stringify(last)}`);
}
async function flat(pair: E2EPair, p: Vec) {
  await mustCommand(pair.server, "difficulty normal"); await mustCommand(pair.server, "gamerule doMobSpawning false");
  await mustCommand(pair.server, "gamerule doDaylightCycle false"); await mustCommand(pair.server, "time set noon");
  await mustCommand(pair.server, `fill ${p.x - 20} ${p.y - 1} ${p.z - 20} ${p.x + 20} ${p.y - 1} ${p.z + 20} minecraft:stone`);
  const clear = await world.command(pair.server, `fill ${p.x - 20} ${p.y} ${p.z - 20} ${p.x + 20} ${p.y + 7} ${p.z + 20} minecraft:air`, { allowFailure: true });
  if (!clear.success && !clear.output.every(line => line.trim() === "No blocks were filled")) throw new Error(JSON.stringify(clear));
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const path = info.outputPath(name); await media.screenshot(client, { path, hideGui: false }); await info.attach(name, { path, contentType: "image/png" });
}
async function saveEvidence(info: TestInfo, name: string, evidence: Evidence) {
  const path = info.outputPath(name); writeFileSync(path, JSON.stringify(evidence, null, 2), "utf8");
  await info.attach(name, { path, contentType: "application/json" });
}

const IMPORTS = ["java.util.UUID", "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.LinkedHashMap",
  "net.neoforged.fml.ModList",
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.item.ItemEntity",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack", "net.minecraft.world.phys.Vec3",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession",
  "yesman.epicfight.api.animation.types.AttackAnimation"];
const SETUP = defineTask<Vec & { colony: string; player: string; weapon: Weapon; guard: boolean }, Scene>({ name: "folkways.epicfight.acceptance.actual-fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();
var colony=a.string("colony").isEmpty()?Colonies.mint(level.getServer()):Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
if(!a.string("colony").isEmpty())for(var original:List.copyOf(colony.residents())){var loaded=level.getEntity(original.id());if(loaded!=null)loaded.discard();}
var body=PersonBody.RESIDENT.get().create(level);body.moveTo(a.integer("x")+2.5,a.integer("y"),a.integer("z")+6.5,-90,0);
body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,ResourceLocation.fromNamespaceAndPath("folkways","steve_wide")));
var item=BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(a.string("weapon"))).orElseThrow();var weapon=new ItemStack(item);
weapon.setDamageValue(9);weapon.set(DataComponents.CUSTOM_NAME,Component.literal("Native acceptance "+a.string("weapon")));
body.getPersistentData().put("epicfight_acceptance_original",weapon.save(level.registryAccess()));body.pack().insert(weapon);body.pack().insert(new ItemStack(Items.BREAD,16));
if(!level.addFreshEntity(body))throw new IllegalStateException("resident must load before colony admission");
colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+1.2,body.getY(),body.getZ());
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);target.getAttribute(Attributes.ARMOR).setBaseValue(0);
target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));if(!level.addFreshEntity(target))throw new IllegalStateException("target must load");
if(a.flag("guard",false))ResidentCommands.of(colony).guard(body,WorldPos.of(level,body.blockPosition()),new Vec3(1,0,0));
return Map.of("colony",colony.id().toString(),"resident",body.id().toString(),"monster",target.getUUID().toString(),"weapon",a.string("weapon"),"player",a.string("player"),"x",a.integer("x"),"y",a.integer("y"),"z",a.integer("z"));` }) });

const READ = defineTask<Scene, Reading>({ name: "folkways.epicfight.acceptance.observe-native-state", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));
if(body==null||target==null)throw new IllegalStateException("actual resident and hostile must remain loaded");
var patch=ResidentEpicFightPatch.of(body);var expected=ItemStack.parseOptional(level.registryAccess(),body.getPersistentData().getCompound("epicfight_acceptance_original"));
var stacks=new ArrayList<ItemStack>(body.pack().contents());if(body.hasWeaponSession())stacks.add(body.getMainHandItem());
for(var entity:level.getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(48)))stacks.add(entity.getItem());
int count=0;boolean components=true;int damage=-1;for(var stack:stacks)if(stack.is(expected.getItem())){count+=stack.getCount();damage=stack.getDamageValue();var normalized=stack.copy();normalized.setDamageValue(expected.getDamageValue());components&=ItemStack.isSameItemSameComponents(normalized,expected);}
var field=ResidentEntity.class.getDeclaredField("weaponSession");field.setAccessible(true);var session=(WeaponSession)field.get(body);
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("patch",patch!=null);out.put("war",ModList.get().isLoaded("folkways_war"));out.put("session",session!=null);out.put("sessionType",session==null?"":session.getClass().getName());out.put("sessionState",session==null?"NONE":session.state().name());
out.put("animation",patch==null?"":patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());out.put("attackAnimation",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
out.put("animationTime",patch==null?0:patch.getAnimator().getPlayerFor(null).getElapsedTime());out.put("attacking",patch!=null&&patch.getEntityState().attacking());out.put("inaction",patch!=null&&patch.getEntityState().inaction());
boolean preContact=false;if(patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation attack){float elapsed=patch.getAnimator().getPlayerFor(null).getElapsedTime();preContact=elapsed+.0001F<attack.getPhaseByTime(elapsed).preDelay;}out.put("preContact",preContact);
out.put("authorized",patch!=null&&!patch.isTargetInvulnerable(target));out.put("nativeTarget",patch!=null&&patch.getTarget()==target);out.put("targetHealth",target.getHealth());out.put("targetMaxHealth",target.getMaxHealth());
out.put("attacker",target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);out.put("targetHasAi",!target.isNoAi());out.put("residentHealth",body.getHealth());
out.put("weaponCount",count);out.put("components",components);out.put("weaponDamage",damage);out.put("hand",BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()).toString());out.put("using",body.isUsingItem());out.put("sprinting",body.isSprinting());out.put("livingMotion",patch==null?"":patch.currentLivingMotion.toString());
var order=ResidentCommands.of(colony).current(body.id());out.put("order",order.map(o->o.mode().name()).orElse("NONE"));out.put("orderStatus",order.map(o->o.status().name()).orElse("NONE"));
out.put("x",body.getX());out.put("y",body.getY());out.put("z",body.getZ());out.put("debug","doing="+body.doing()+",balk="+body.balk()+",target="+target.position()+",pack="+body.pack().contents()+",source="+target.getLastDamageSource());return out;` }) });

const RENDER = defineTask<Scene, Render>({ name: "folkways.epicfight.acceptance.observe-real-draw", side: "client", source: javaTask({ imports: [
  "net.minecraft.client.Minecraft", "net.neoforged.fml.ModList", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch",
  "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence", "yesman.epicfight.api.animation.types.AttackAnimation"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var out=new LinkedHashMap<String,Object>();out.put("present",false);out.put("patch",false);out.put("draws",0L);out.put("tick",-1L);out.put("mesh","");out.put("texture","");out.put("expectedTexture","");out.put("sourceRenderer","");out.put("entryScale",0);out.put("animation","");out.put("attackAnimation",false);out.put("motion","");out.put("war",ModList.get().isLoaded("folkways_war"));
for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){
 out.put("present",true);var patch=ResidentEpicFightPatch.of(body);out.put("patch",patch!=null);out.put("expectedTexture",ResidentLooks.of(body).appearance().skin().texture().toString());
 if(patch!=null){out.put("animation",patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());out.put("attackAnimation",patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);out.put("motion",patch.currentLivingMotion.toString());}
 var draw=ResidentRenderEvidence.last(body).orElse(null);if(draw!=null){out.put("draws",draw.sequence());out.put("tick",draw.gameTick());out.put("mesh",draw.mesh().toString());out.put("texture",draw.texture().toString());out.put("sourceRenderer",draw.sourceRenderer());out.put("entryScale",draw.entryScale());}break;
}return out;` }) });

const STOP_GUARD = defineTask<Scene, boolean>({ name: "folkways.epicfight.acceptance.replace-guard-fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
ResidentCommands.of(colony).move(body,WorldPos.of(level,new BlockPos((int)a.integer("x")-7,(int)a.integer("y"),(int)a.integer("z")+6)));return true;` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.acceptance.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=level.getEntity(UUID.fromString(a.string("resident")));if(body instanceof ResidentEntity resident){resident.stopWeaponSession();resident.discard();}
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });

const GUI_SCALE = defineTask<void, boolean>({ name: "folkways.epicfight.acceptance.gui-scale", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `var mc=Minecraft.getInstance();mc.options.guiScale().set(1);mc.resizeDisplay();return true;` }) });
type ClientInputState = Omit<InputState, "selection" | "owned" | "reserved" | "points" | "groupKind" | "groupComplete">;
const INPUT_STATE = defineTask<void, ClientInputState>({ name: "folkways.epicfight.acceptance.observe-real-input-ack", side: "client", source: javaTask({ imports: [
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
  name: "folkways.epicfight.acceptance.observe-authoritative-control", side: "server", source: javaTask({ imports: [...IMPORTS,
    "io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership", "io.github.izakyl.folkways.plugins.combat.group.UnitGroups"], body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var body=(ResidentEntity)player.serverLevel().getEntity(UUID.fromString(a.string("resident")));var ownership=CombatSelectionOwnership.of(colony);var snapshot=ownership.snapshot(player);var group=UnitGroups.of(colony).ofResident(body.id()).orElse(null);
return Map.of("selection",snapshot.selection().stream().map(Object::toString).toList(),"owned",ownership.owns(player,body),"reserved",ownership.reserved(body.id()),
 "points",group==null?List.of():group.pointRoute().stream().map(point->Map.of("x",point.cell().getX(),"y",point.cell().getY(),"z",point.cell().getZ())).toList(),
 "groupKind",group==null?"NONE":group.commandKind().name(),"groupComplete",group!=null&&group.routeComplete());` }) });
const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.epicfight.acceptance.observe-world-projection", side: "client",
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

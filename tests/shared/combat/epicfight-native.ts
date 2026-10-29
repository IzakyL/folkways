import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import type { Vec } from "./rts-fixtures";

export type Weapon = "minecraft:iron_sword" | "epicfight:iron_spear";
export type Scene = Vec & { colony: string; resident: string; monster: string; weapon: Weapon; player: string; duty: string; cancelled?: { orderId: string; health: number; damage: number } };
export type Reading = {
  tick: number; patch: boolean; rts: boolean; session: boolean; sessionType: string; sessionState: string;
  animation: string; attackAnimation: boolean; animationTime: number; preContact: boolean; attacking: boolean; inaction: boolean;
  authorized: boolean; nativeTarget: boolean; targetHealth: number; targetMaxHealth: number; attacker: boolean;
  targetHasAi: boolean; residentHealth: number; weaponCount: number; components: boolean; weaponDamage: number;
  hand: string; using: boolean; sprinting: boolean; livingMotion: string; order: string; orderStatus: string;
  x: number; y: number; z: number; orderId: string; weaponPlace: string; staffed: boolean; items: Array<{place: string; count: number; damage: number; serialized: string}>; debug: string;
};
export type Render = { present: boolean; patch: boolean; draws: number; tick: number; mesh: string; texture: string;
  expectedTexture: string; sourceRenderer: string; entryScale: number; animation: string; attackAnimation: boolean; motion: string; rts: boolean };
export type Evidence = Array<{ step: string; value: unknown }>;
export type Pixel = { x: number; y: number; width: number; height: number };
export type InputState = { combat: boolean; roster: number; selected: string[]; selection: string[]; owned: boolean; reserved: boolean;
  routeRequest: number; accepted: boolean; intent: string; pending: boolean; points: Vec[]; groupKind: string; groupComplete: boolean;
  buttons: Array<{ kind: string; x: number; y: number; width: number; height: number }> };

export const IMPORTS = ["java.util.Set", "java.util.Optional", "java.util.UUID", "java.util.List", "java.util.Map", "java.util.ArrayList", "java.util.LinkedHashMap",
  "net.neoforged.fml.ModList",
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component",
  "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.Mob", "net.minecraft.world.entity.item.ItemEntity",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack", "net.minecraft.world.phys.Vec3",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.combat.DefenseControl",
  "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch", "io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession",
  "yesman.epicfight.api.animation.types.AttackAnimation", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles", "io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile", "io.github.izakyl.folkways.core.api.terms.ItemSpec", "io.github.izakyl.folkways.plugins.patrol.PatrolContent", "io.github.izakyl.folkways.plugins.patrol.PatrolStaffing"];
export const SETUP = defineTask<Vec & { colony: string; player: string; weapon: Weapon; autonomous: boolean }, Scene>({ name: "folkways.epicfight.acceptance.actual-fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
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
var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setPersistenceRequired();target.moveTo(body.getX()+(a.flag("autonomous",false)?4.0:1.2),body.getY(),body.getZ());
target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(160);target.setHealth(160);target.getAttribute(Attributes.ARMOR).setBaseValue(0);
target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));if(!level.addFreshEntity(target))throw new IllegalStateException("target must load");
var profiles=LoadoutProfiles.of(colony);var profile=profiles.create("EF real melee patrol");
profiles.add(profile.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(item)));profiles.assign(List.of(body.id()),Optional.of(profile.id()));
String duty="";if(a.flag("autonomous",false)){
 var route=colony.hold(PatrolContent.BEAT,new Held.Line(List.of(WorldPos.of(level,body.blockPosition()),WorldPos.of(level,body.blockPosition().offset(5,0,0))))).orElseThrow();
 PatrolStaffing.of(colony).configure(level,route.id(),Set.of(profile.id()),1);duty=route.id().toString();
}
return Map.of("colony",colony.id().toString(),"resident",body.id().toString(),"monster",target.getUUID().toString(),"weapon",a.string("weapon"),"player",a.string("player"),"duty",duty,"x",a.integer("x"),"y",a.integer("y"),"z",a.integer("z"));` }) });

export const READ = defineTask<Scene, Reading>({ name: "folkways.epicfight.acceptance.observe-native-state", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));var target=(Mob)level.getEntity(UUID.fromString(a.string("monster")));
if(body==null||target==null)throw new IllegalStateException("actual resident and hostile must remain loaded");
var patch=ResidentEpicFightPatch.of(body);var expected=ItemStack.parseOptional(level.registryAccess(),body.getPersistentData().getCompound("epicfight_acceptance_original"));
var stacks=new ArrayList<ItemStack>(body.pack().contents());if(body.hasWeaponSession())stacks.add(body.getMainHandItem());
for(var entity:level.getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(48)))stacks.add(entity.getItem());
int count=0;boolean components=true;int damage=-1;for(var stack:stacks)if(stack.is(expected.getItem())){count+=stack.getCount();damage=stack.getDamageValue();var normalized=stack.copy();normalized.setDamageValue(expected.getDamageValue());components&=ItemStack.isSameItemSameComponents(normalized,expected);}
var field=ResidentEntity.class.getDeclaredField("weaponSession");field.setAccessible(true);var session=(WeaponSession)field.get(body);
var out=new LinkedHashMap<String,Object>();out.put("tick",level.getGameTime());out.put("patch",patch!=null);out.put("rts",ModList.get().isLoaded("folkways_rts"));out.put("session",session!=null);out.put("sessionType",session==null?"":session.getClass().getName());out.put("sessionState",session==null?"NONE":session.state().name());
out.put("animation",patch==null?"":patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());out.put("attackAnimation",patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);
out.put("animationTime",patch==null?0:patch.getAnimator().getPlayerFor(null).getElapsedTime());out.put("attacking",patch!=null&&patch.getEntityState().attacking());out.put("inaction",patch!=null&&patch.getEntityState().inaction());
boolean preContact=false;if(patch!=null&&patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation attack){float elapsed=patch.getAnimator().getPlayerFor(null).getElapsedTime();preContact=elapsed+.0001F<attack.getPhaseByTime(elapsed).preDelay;}out.put("preContact",preContact);
out.put("authorized",patch!=null&&!patch.isTargetInvulnerable(target));out.put("nativeTarget",patch!=null&&patch.getTarget()==target);out.put("targetHealth",target.getHealth());out.put("targetMaxHealth",target.getMaxHealth());
out.put("attacker",target.getLastDamageSource()!=null&&target.getLastDamageSource().getEntity()==body);out.put("targetHasAi",!target.isNoAi());out.put("residentHealth",body.getHealth());
out.put("weaponCount",count);out.put("components",components);out.put("weaponDamage",damage);out.put("hand",BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()).toString());out.put("using",body.isUsingItem());out.put("sprinting",body.isSprinting());out.put("livingMotion",patch==null?"":patch.currentLivingMotion.toString());
var order=DefenseControl.of(colony).current(body.id()).orElse(null);String mode="NONE",status="NONE",orderId="";
if(order!=null){mode=order.getClass().getMethod("mode").invoke(order).toString();status=order.getClass().getMethod("status").invoke(order).toString();orderId=order.getClass().getMethod("id").invoke(order).toString();}
out.put("order",mode);out.put("orderStatus",status);out.put("orderId",orderId);
String place="missing";if(body.hasWeaponSession()&&body.getMainHandItem().is(expected.getItem()))place="mainhand";
var inventory=new ArrayList<Map<String,Object>>();for(int i=0;i<body.pack().getContainerSize();i++){var stack=body.pack().getItem(i);if(stack.isEmpty())continue;if(stack.is(expected.getItem()))place="pack:"+i;inventory.add(Map.of("place","pack:"+i,"count",stack.getCount(),"damage",stack.getDamageValue(),"serialized",stack.save(level.registryAccess()).toString()));}
for(var slot:EquipmentSlot.values()){var stack=body.getItemBySlot(slot);if(!stack.isEmpty())inventory.add(Map.of("place",slot.getName(),"count",stack.getCount(),"damage",stack.getDamageValue(),"serialized",stack.save(level.registryAccess()).toString()));}
out.put("weaponPlace",place);out.put("items",inventory);out.put("staffed",!a.string("duty").isEmpty()&&PatrolStaffing.of(colony).members(UUID.fromString(a.string("duty"))).contains(body.id()));
out.put("x",body.getX());out.put("y",body.getY());out.put("z",body.getZ());out.put("debug","doing="+body.doing()+",balk="+body.balk()+",target="+target.position()+",pack="+body.pack().contents()+",source="+target.getLastDamageSource());return out;` }) });

export const RENDER = defineTask<Scene, Render>({ name: "folkways.epicfight.acceptance.observe-real-draw", side: "client", source: javaTask({ imports: [
  "net.minecraft.client.Minecraft", "net.neoforged.fml.ModList", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch",
  "io.github.izakyl.folkways.epicfight.client.ResidentRenderEvidence", "yesman.epicfight.api.animation.types.AttackAnimation"], body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var out=new LinkedHashMap<String,Object>();out.put("present",false);out.put("patch",false);out.put("draws",0L);out.put("tick",-1L);out.put("mesh","");out.put("texture","");out.put("expectedTexture","");out.put("sourceRenderer","");out.put("entryScale",0);out.put("animation","");out.put("attackAnimation",false);out.put("motion","");out.put("rts",ModList.get().isLoaded("folkways_rts"));
for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(a.string("resident"))&&entity instanceof ResidentEntity body){
 out.put("present",true);var patch=ResidentEpicFightPatch.of(body);out.put("patch",patch!=null);out.put("expectedTexture",ResidentLooks.of(body).appearance().skin().texture().toString());
 if(patch!=null){out.put("animation",patch.getAnimator().getPlayerFor(null).getRealAnimation().toString());out.put("attackAnimation",patch.getAnimator().getPlayerFor(null).getRealAnimation().get() instanceof AttackAnimation);out.put("motion",patch.currentLivingMotion.toString());}
 var draw=ResidentRenderEvidence.last(body).orElse(null);if(draw!=null){out.put("draws",draw.sequence());out.put("tick",draw.gameTick());out.put("mesh",draw.mesh().toString());out.put("texture",draw.texture().toString());out.put("sourceRenderer",draw.sourceRenderer());out.put("entryScale",draw.entryScale());}break;
}return out;` }) });

export const STOP_AUTONOMY = defineTask<Scene, boolean>({ name: "folkways.epicfight.acceptance.withdraw-real-staffing", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var colony=Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).orElseThrow();
if(!a.string("duty").isEmpty())PatrolStaffing.of(colony).configure(level,UUID.fromString(a.string("duty")),Set.of(),0);
// Move the actual NoAI hostile beyond the finite patrol footprint; do not close or fabricate a session.
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.moveTo(a.integer("x")+18.5,a.integer("y"),a.integer("z")+18.5);return true;` }) });

export const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.epicfight.acceptance.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=level.getEntity(UUID.fromString(a.string("resident")));if(body instanceof ResidentEntity resident){resident.stopWeaponSession();resident.discard();}
var target=level.getEntity(UUID.fromString(a.string("monster")));if(target!=null)target.discard();Colonies.of(level.getServer(),UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;` }) });

export const GUI_SCALE = defineTask<void, boolean>({ name: "folkways.epicfight.acceptance.gui-scale", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `var mc=Minecraft.getInstance();mc.options.guiScale().set(1);mc.resizeDisplay();return true;` }) });

/** Read the actual configured keyboard entry point; never invoke the controller action or change bindings. */
export const RELEASE_BINDING = defineTask<void, { active: boolean; code: number; type: string; name: string; label: string; hints: string[] }>({
  name: "folkways.epicfight.acceptance.observe-release-key", side: "client", source: javaTask({ imports: [
    "java.util.Map", "java.util.List", "io.github.izakyl.folkways.rts.client.CombatKeys",
    "io.github.izakyl.folkways.front.api.ui.OverheadTools"], body: `
var handler=OverheadTools.selectedHandler().orElseThrow();var mapping=CombatKeys.RELEASE;
var hints=handler.cues().stream().map(cue->cue.binding()==null?"":cue.binding()).toList();
return Map.of("active",handler.getClass().getName().endsWith(".CombatController"),
 "code",mapping.getKey().getValue(),"type",mapping.getKey().getType().name(),
 "name",mapping.getName(),"label",mapping.getTranslatedKeyMessage().getString(),"hints",hints);` }) });
type ClientInputState = Omit<InputState, "selection" | "owned" | "reserved" | "points" | "groupKind" | "groupComplete">;
export const INPUT_STATE = defineTask<void, ClientInputState>({ name: "folkways.epicfight.acceptance.observe-real-input-ack", side: "client", source: javaTask({ imports: [
  "java.util.Map", "java.util.List", "java.util.Set", "java.util.LinkedHashMap", "java.util.ArrayList",
  "io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.rts.combat.net.CombatPackets"], body: `
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
export const CONTROL_STATE = defineTask<Scene, Pick<InputState, "selection" | "owned" | "reserved" | "points" | "groupKind" | "groupComplete">>({
  name: "folkways.epicfight.acceptance.observe-authoritative-control", side: "server", source: javaTask({ imports: [...IMPORTS,
    "io.github.izakyl.folkways.rts.combat.control.CombatSelectionOwnership", "io.github.izakyl.folkways.rts.combat.group.UnitGroups"], body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var player=server.getPlayerList().getPlayerByName(a.string("player"));var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var body=(ResidentEntity)player.serverLevel().getEntity(UUID.fromString(a.string("resident")));var ownership=CombatSelectionOwnership.of(colony);var snapshot=ownership.snapshot(player);var group=UnitGroups.of(colony).ofResident(body.id()).orElse(null);
return Map.of("selection",snapshot.selection().stream().map(Object::toString).toList(),"owned",ownership.owns(player,body),"reserved",ownership.reserved(body.id()),
 "points",group==null?List.of():group.pointRoute().stream().map(point->Map.of("x",point.cell().getX(),"y",point.cell().getY(),"z",point.cell().getZ())).toList(),
 "groupKind",group==null?"NONE":group.commandKind().name(),"groupComplete",group!=null&&group.routeComplete());` }) });

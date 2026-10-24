import { expect, test } from "@playwright/test";
import path from "node:path";
import { defineTask, javaTask, media, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { MODEL_ASSETS, placeServerModel, reloadServerModels, waitForModelsOnClient } from "../shared/ui/model-folder";

type Scene = { residents: string[]; colony: string; player: string };

test("TACZ colony gunfire reaches a real target and skins, Geo and maid residents hold their actual guns", async ({}, testInfo) => {
  test.setTimeout(360_000);
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#tacz_models",
      server: { instance: `tacz-server-${run}`, startFrozen: false },
      client: { instance: `tacz-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie", "tacz:bullet"] },
    });
    const grounded = await waitForGroundedPlayer(pair.server);
    await world.command(pair.server, "gamerule doMobSpawning false");
    await world.command(pair.server, "time set noon");
    await placeServerModel(pair.server, path.join(MODEL_ASSETS, "default_boy"), "default_boy");
    await reloadServerModels(pair.server);
    await waitForModelsOnClient(pair.client, "assets/folkways/geo/resident_models/default_boy.geo.json");
    scene = await pair.server.run(SETUP, { player: grounded.name });
    await expect.poll(async () => pair!.server.run(READ_DAMAGE, scene!), { timeout: 30_000 }).toBeLessThan(1000);
    await expect.poll(async () => (await pair!.client.run(READ_POSES, scene!)).length, { timeout: 30_000 }).toBe(3);
    const poses = await pair.client.run(READ_POSES, scene);
    expect(poses.map(pose => pose.appearance).sort()).toEqual(["maid", "model", "skin"]);
    expect(poses.every(pose => pose.armed && pose.aiming)).toBe(true);
    const screenshot = testInfo.outputPath("tacz-resident-poses.png");
    await media.screenshot(pair.client, { path: screenshot });
    await testInfo.attach("tacz-resident-poses", { path: screenshot, contentType: "image/png" });
    await testInfo.attach("tacz-poses.json", { body: JSON.stringify(poses, null, 2), contentType: "application/json" });
  } catch (error) { pair?.failing(error); throw error; }
  finally {
    if (pair && scene) { await pair.server.run(CLEANUP, scene).catch(() => undefined); }
    await pair?.teardown(testInfo);
  }
});

const IMPORTS = [
  "net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.resources.ResourceKey", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.world.entity.ai.attributes.Attributes", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items",
  "net.minecraft.world.phys.Vec3", "net.minecraft.world.level.block.Blocks",
  "com.tacz.guns.api.item.IGun", "com.tacz.guns.api.item.IAmmo", "com.tacz.guns.api.item.gun.FireMode", "com.tacz.guns.init.ModItems",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
  "io.github.izakyl.folkways.core.api.terms.WorldSpaces", "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.person.look.MaidLooks",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands",
];
const SETUP = defineTask<{ player: string }, Scene>({
  name: "folkways.tacz.setup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();
var player=server.getPlayerList().getPlayerByName(a.string("player"));
BlockPos origin=player.blockPosition().offset(4,0,4);
for(int x=-4;x<15;x++)for(int z=-5;z<12;z++){
 level.setBlockAndUpdate(origin.offset(x,-1,z),Blocks.STONE.defaultBlockState());
 for(int y=0;y<4;y++)level.setBlockAndUpdate(origin.offset(x,y,z),Blocks.AIR.defaultBlockState());
}
var colony=Colonies.mint(server);var ids=new ArrayList<String>();
var maid=MaidLooks.entries().keySet().stream().sorted().findFirst().orElseThrow();
for(int n=0;n<3;n++){
 var body=PersonBody.RESIDENT.get().create(level);body.moveTo(origin.getX()+.5,origin.getY(),origin.getZ()+n*3+.5);
 var look=n==0?ResourceLocation.parse("folkways:test_skin_fallback"):n==1?ResourceLocation.parse("folkways:model/default_boy/blue"):maid;
 body.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,look));
 colony.hold(PersonContent.ID,Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());level.addFreshEntity(body);
 ItemStack actual=new ItemStack(ModItems.MODERN_KINETIC_GUN.get());IGun gun=(IGun)actual.getItem();
 gun.setGunId(actual,ResourceLocation.parse("tacz:m1911"));gun.setFireMode(actual,FireMode.SEMI);
 gun.setCurrentAmmoCount(actual,6);gun.setBulletInBarrel(actual,true);body.pack().insert(actual);
 ItemStack ammo=new ItemStack(ModItems.AMMO.get(),64);((IAmmo)ammo.getItem()).setAmmoId(ammo,ResourceLocation.parse("tacz:45acp"));body.pack().insert(ammo);
 ResidentCommands.of(colony).guard(body,WorldSpaces.at(body),new Vec3(1,0,0));ids.add(body.getUUID().toString());
 var target=EntityType.ZOMBIE.create(level);target.setNoAi(true);target.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));
 target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);target.setHealth(1000);
 target.moveTo(body.getX()+8,body.getY(),body.getZ());target.addTag("folkways_tacz_test");level.addFreshEntity(target);
}
player.teleportTo(origin.getX()+3,origin.getY(),origin.getZ()-5);player.setYRot(0);player.setXRot(10);
return Map.of("residents",ids,"colony",colony.id().toString(),"player",player.getGameProfile().getName());
` }) });
const READ_DAMAGE = defineTask<Scene, number>({ name: "folkways.tacz.damage", side: "server", source: javaTask({ imports: IMPORTS, body: `
var level=((MinecraftServer)ctx.server()).overworld();
return level.getEntities(EntityType.ZOMBIE,e->e.getTags().contains("folkways_tacz_test")).stream().mapToDouble(e->e.getHealth()).min().orElse(1000);
` }) });
const READ_POSES = defineTask<Scene, Array<{ appearance: string; armed: boolean; aiming: boolean }>>({
  name: "folkways.tacz.poses", side: "client", source: javaTask({ imports: [
    "net.minecraft.client.Minecraft", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
    "io.github.izakyl.folkways.plugins.person.ResidentWeaponPoses", "io.github.izakyl.folkways.plugins.person.look.ResidentLook",
    "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.person.look.MaidLooks",
    "com.tacz.guns.api.item.IGun",
  ], body: `
var client=(Minecraft)ctx.client();var ids=Args.of(ctx).strings("residents");var rows=new ArrayList<Map<String,Object>>();
for(var entity:client.level.entitiesForRendering()){
 if(!(entity instanceof ResidentEntity body)||!ids.contains(body.getUUID().toString()))continue;
 var pose=ResidentWeaponPoses.of(body);var look=ResidentLooks.of(body).appearance();
 rows.add(Map.of("appearance",look instanceof ResidentLook.Model?MaidLooks.isMaid(body.lookId().orElseThrow().location())?"maid":"model":"skin",
 "armed",body.getMainHandItem().getItem() instanceof IGun,"aiming",pose.isPresent()&&pose.get().right().pitch()<-.5));
}
return rows;
` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.tacz.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();
for(String id:a.strings("residents")){var entity=level.getEntity(UUID.fromString(id));if(entity!=null)entity.discard();}
level.getEntities(EntityType.ZOMBIE,e->e.getTags().contains("folkways_tacz_test")).forEach(e->e.discard());
Colonies.of(server,UUID.fromString(a.string("colony"))).ifPresent(c->c.raze());return true;
` }) });

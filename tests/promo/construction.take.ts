import { expect, test } from "@playwright/test";
import { camera, defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { cinematicWorld, launchPromoPair, sleepMs, take, type PromoPair } from "./promo-kit";
import { admitPromoSettlersFast, foundPromoColonyFast } from "./fast-setup";

type Cell = { x: number; y: number; z: number; block: string };
type Scenario = { name: string; cells: Cell[]; buried?: boolean };
const stone = "minecraft:stone_bricks";
function scenarios(): Scenario[] {
  const house: Cell[] = [], tower: Cell[] = [], bridge: Cell[] = [], excavation: Cell[] = [];
  const add = (cells: Cell[], x: number, y: number, z: number, block = stone) => cells.push({ x, y, z, block });
  // A sealed shell with interior partitions: access is intentionally lost as walls and roof close.
  for (let x = 0; x < 7; x++) for (let z = 0; z < 7; z++) for (let y = 0; y < 5; y++) {
    if (y === 0 || y === 4 || x === 0 || x === 6 || z === 0 || z === 6) add(house, x, y, z);
    else if (x === 3 && y < 3) add(house, x, y, z, "minecraft:oak_planks");
  }
  // A hollow tower with widely separated storeys, leaving no permanent staircase to its roof.
  for (let y = 0; y < 13; y++) for (let x = 0; x < 4; x++) for (let z = 0; z < 4; z++)
    if ((x === 0 || x === 3) && (z === 0 || z === 3) || y % 4 === 0) add(tower, x, y, z);
  // High deck and cantilever, no piers under the middle; forces work away from ground contact.
  for (let x = 0; x < 15; x++) for (let z = 0; z < 3; z++) {
    add(bridge, x, 6, z, "minecraft:oak_planks");
    if (x === 0 || x === 14) for (let y = 0; y < 6; y++) add(bridge, x, y, z);
  }
  for (let x = 0; x < 7; x++) for (let z = 0; z < 7; z++) for (let y = 0; y < 5; y++)
    add(excavation, x, y, z, x === 0 || x === 6 || z === 0 || z === 6 || y === 0 ? stone : "minecraft:air");
  return [{ name: "sealed-house", cells: house }, { name: "tower", cells: tower },
    { name: "bridge", cells: bridge }, { name: "excavation", cells: excavation, buried: true }];
}

test("residents build sealed interiors, a tower, a cantilever and an excavation on camera", async ({}, testInfo) => {
  test.setTimeout(60 * 60_000);
  const scope = process.env.FOLKWAYS_CONSTRUCTION_SCOPE ?? "construction";
  const run = take(scope);
  let pair: PromoPair | undefined;
  try {
    // Plain flat profile keeps the evidence readable and does not need downloaded shader assets.
    pair = await launchPromoPair(scope, { config: "blockwright.toml" });
    const { server, client } = pair;
    const player = (await waitForGroundedPlayer(server)).name;
    await world.command(server, `op ${player}`);
    await cinematicWorld(server);
    await world.command(server, `gamemode spectator ${player}`);
    await world.command(server, "forceload add -32 -32 96 32");
    const setup = await runColonyTask(server, HEIGHT, {});
    const y = Number(setup.y);
    await world.command(server, `tp ${player} -4 ${y} 0`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    const { colony_id } = await foundPromoColonyFast(server, player, setup.members);
    await admitPromoSettlersFast(server, player, colony_id, 3);
    const selectedCases = process.env.FOLKWAYS_CONSTRUCTION_CASES?.split(",");
    const firstCase = scenarios().findIndex(scenario => !selectedCases || selectedCases.includes(scenario.name));
    run.note("crew", await runColonyTask(server, CREW, { colony_id, y, stageX: Math.max(0, firstCase) * 22 }));
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    await camera.hud(client, false);
    const results: unknown[] = [];
    for (const [index, scenario] of scenarios().entries()) {
      const selected = process.env.FOLKWAYS_CONSTRUCTION_CASES?.split(",");
      if (selected && !selected.includes(scenario.name)) continue;
      const anchor = { x: index * 22, y: scenario.buried ? y - 4 : y, z: 5 };
      const chest = { x: anchor.x - 5, y, z: anchor.z - 3 };
      await world.command(server, `setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
      await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: [chest.x, chest.y, chest.z] });
      const args = { colony_id, anchor, chest, buried: false, ...scenario };
      run.note(`${scenario.name}-commission`, await runColonyTask(server, COMMISSION, args));
      const look = { x: anchor.x + 3, y: anchor.y + 3, z: anchor.z + 3 };
      const eye = { x: anchor.x - 13, y: anchor.y + 12, z: anchor.z - 14 };
      const camera = { position: eye, ...cameraLookingAt(eye, look), fov: 65 };
      await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z}`);
      const samples: any[] = [];
      const seconds = Number(process.env.FOLKWAYS_CONSTRUCTION_SECONDS ?? 40);
      await run.clip(client, server, `${index + 1}-${scenario.name}`, {
        subject: `Unstaged resident construction: ${scenario.name}; no target blocks are prebuilt`,
        worldState: args, camera, seconds, fps: 30,
        during: async () => {
          for (let sample = 0; sample < Math.ceil(seconds / 5); sample++) {
            samples.push(await runColonyTask(server, PROBE, args));
            await sleepMs(5_000);
          }
        },
      });
      let final = await runColonyTask(server, PROBE, args);
      // Finish under accelerated simulation after filming, preserving an exact tick/progress record.
      for (let round = 0; round < 100 && (Number(final.remaining) > 0 || Number(final.scaffolds) > 0 || (final.sites as unknown[]).length > 0); round++) {
        await tick.sprint(server, 200);
        final = await runColonyTask(server, PROBE, args);
        samples.push(final);
      }
      results.push({ name: scenario.name, samples, final });
      run.note("results", results);
      await run.shot(client, `${index + 1}-${scenario.name}-finished`, {
        subject: `Final state of ${scenario.name}`, worldState: final, camera, hard: true,
      });
      expect(Number(final.remaining), `${scenario.name} must complete, including its enclosed targets`).toBe(0);
      expect(Number(final.scaffolds), `${scenario.name} must recover its temporary scaffolding`).toBe(0);
      expect(final.sites, `${scenario.name} must retire its finished site`).toHaveLength(0);
    }
  } catch (error) { pair?.failing(error); throw error; }
  finally { await pair?.teardown(testInfo); }
});

const HEIGHT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.height", side: "server", source: javaTask({
    imports: ["net.minecraft.world.level.levelgen.Heightmap", "net.minecraft.core.BlockPos", "net.minecraft.core.Direction",
      "net.minecraft.world.level.block.*", "net.minecraft.world.level.block.state.properties.BedPart",
      "net.minecraft.world.Container", "net.minecraft.world.item.*"],
    body: `
var level=ctx.server().overworld();int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,0,0)+8;
// A deep level work yard lets the excavation extend below grade even in the bedrock-only test world.
for(int x=-32;x<=96;x++)for(int z=-24;z<=32;z++)for(int h=y-8;h<y;h++)
 level.setBlock(new BlockPos(x,h,z),(h==y-1?Blocks.GRASS_BLOCK:Blocks.DIRT).defaultBlockState(),2);
var members=new ArrayList<Object>();
for(int x:new int[]{-6,-8}){var at=new BlockPos(x,y,0);level.setBlock(at,Blocks.CHEST.defaultBlockState(),2);
 members.add(Map.of("x",x,"y",y,"z",0));
 if(x==-8){var chest=(Container)level.getBlockEntity(at);for(int slot=0;slot<27;slot++)chest.setItem(slot,new ItemStack(Items.BREAD,64));chest.setChanged();}}
for(int i=0;i<3;i++){var at=new BlockPos(-8+i*3,y,-5);
 level.setBlock(at,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.FOOT),3);
 level.setBlock(at.north(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH).setValue(BedBlock.PART,BedPart.HEAD),3);
 members.add(Map.of("x",at.getX(),"y",y,"z",at.getZ()));}
return Sync.stamp(Map.of("y",y,"members",members));`,
  }),
});

const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.crew", side: "server", source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.resident.api.Bodies", "io.github.izakyl.folkways.core.vocation.api.Vocations",
      "net.minecraft.world.item.*"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();int index=0;
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  for(var vocation:Vocations.all())body.licences().trade(vocation).ifPresent(trade->
    body.licences().allow(trade,Set.of("building","hauling").contains(vocation.id().getPath())));
  body.pack().insert(new ItemStack(Items.BREAD,32));
  body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));
  body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
  entity.teleportTo(args.number("stageX")-3+index++*2,args.number("y"),0.5);
}
return Sync.stamp(Map.of("residents",index));`,
  }),
});

const imports = ["io.github.izakyl.folkways.front.engine.colony.ColonyGround",
  "io.github.izakyl.folkways.plugins.build.*", "net.minecraft.core.BlockPos",
  "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.level.block.Blocks", "net.minecraft.world.Container",
  "io.github.izakyl.folkways.core.resident.api.Bodies", "io.github.izakyl.folkways.front.api.notice.Doings",
  "io.github.izakyl.folkways.core.work.api.Node"];
const members = `
static BlockPos pos(Map<?,?> raw){return new BlockPos(((Number)raw.get("x")).intValue(),((Number)raw.get("y")).intValue(),((Number)raw.get("z")).intValue());}
static Object field(Object target,String name) throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
static Object call(Object target,String name,Object... args) throws Exception {
  for(var method:target.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length){
    method.setAccessible(true);return method.invoke(target,args);}
  throw new NoSuchMethodException(name);
}`;
const COMMISSION = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.commission", side: "server", source: javaTask({ imports, members, body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var anchor=pos((Map<?,?>)args.value("anchor"));
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var blocks=new ArrayList<BlueprintBlock>();var materials=new LinkedHashMap<String,Integer>();
for(var raw:args.list("cells")){var cell=(Map<?,?>)raw;var offset=pos(cell);var id=ResourceLocation.parse(String.valueOf(cell.get("block")));
  var state=BuiltInRegistries.BLOCK.get(id).defaultBlockState();blocks.add(new BlueprintBlock(offset,state));
  if(!state.isAir())materials.merge(id.toString(),1,Integer::sum);
  level.setBlock(anchor.offset(offset),Boolean.TRUE.equals(args.value("buried"))?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
}
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var field=presence.getClass().getDeclaredField("book");field.setAccessible(true);var book=field.get(presence);
var blueprint=new Blueprint(UUID.randomUUID(),args.string("name"),blocks,BlueprintVolume.around(blocks.stream().map(BlueprintBlock::offset).toList()).orElseThrow(),level.getGameTime());
call(book,"add",level,blueprint);call(book,"place",level,blueprint.id(),blueprint.name(),anchor,Optional.empty(),"construction-take",level.getGameTime());
var chest=(Container)level.getBlockEntity(pos((Map<?,?>)args.value("chest")));
chest.clearContent();materials.put("minecraft:scaffolding",256);int slot=0;
for(var entry:materials.entrySet()){var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey()));int left=entry.getValue()+16;
 while(left>0){int count=Math.min(64,left);if(slot>=chest.getContainerSize())throw new IllegalStateException("Stock chest full");
 chest.setItem(slot++,new ItemStack(item,count));left-=count;}}
chest.setChanged();return Sync.stamp(Map.of("name",blueprint.name(),"blocks",blocks.size(),"materials",materials));
` }),
});
const PROBE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.probe", side: "server", source: javaTask({ imports, members, body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var anchor=pos((Map<?,?>)args.value("anchor"));int correct=0;
for(var raw:args.list("cells")){var cell=(Map<?,?>)raw;var state=level.getBlockState(anchor.offset(pos(cell)));
 if(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(String.valueOf(cell.get("block"))))correct++;}
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();var crew=new ArrayList<Object>();
for(var resident:colony.residents()){var entity=level.getEntity(resident.id());if(entity!=null){var body=Bodies.of(entity).orElseThrow();
 crew.add(Map.of("id",resident.id().toString(),"x",entity.getX(),"y",entity.getY(),"z",entity.getZ(),
 "doing",body.doing().map(one->Doings.name(one).getString()).orElse("idle"),
 "pack",body.pack().contents().stream().filter(stack->!stack.isEmpty()).map(Object::toString).toList()));}}
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var sites=new ArrayList<Object>();
for(var entry:((Map<?,?>)field(presence,"sites")).entrySet()){
 var site=entry.getValue();var live=(Map<?,?>)field(site,"live");var counts=new LinkedHashMap<String,Integer>();var pieces=new ArrayList<Object>();
 for(var piece:live.values()){var work=call(piece,"work");String kind=String.valueOf(call(work,"kind"));counts.merge(kind,1,Integer::sum);
 if(live.size()<=16)pieces.add(Map.of("kind",kind,"focus",String.valueOf(call(work,"focus")),"doing",String.valueOf(field(piece,"doing")),
 "restUntil",field(piece,"restUntil"),"starvedSince",field(piece,"starvedSince"),"tries",call(piece,"tries"),
 "before",call(site,"before",piece).toString(),"needs",((Node)call(piece,"node")).spec().needs().toString()));}
 sites.add(Map.of("order",entry.getKey().toString(),"live",live.size(),"held",((Map<?,?>)field(site,"held")).size(),
 "stays",((Map<?,?>)field(site,"stays")).size(),"raised",field(site,"raised").toString(),"counts",counts,"pieces",pieces));
}
int scaffolds=0;
for(int x=anchor.getX()-3;x<=anchor.getX()+18;x++)for(int z=anchor.getZ()-3;z<=anchor.getZ()+10;z++)for(int h=anchor.getY()-4;h<=anchor.getY()+15;h++)
 if(level.getBlockState(new BlockPos(x,h,z)).is(Blocks.SCAFFOLDING))scaffolds++;
return Map.of("tick",level.getGameTime(),"correct",correct,"remaining",args.list("cells").size()-correct,"crew",crew,"sites",sites,"scaffolds",scaffolds);
` }),
});

import { expect, test } from "@playwright/test";
import { createHash } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { instanceDirOf } from "@izakyl/blockwright-client";
import { defineTask, javaTask, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, scrollElementIntoView, type Element } from "../ldlib2";
import { commandPos, createColonyViaBook, tryCommand, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { aimAndLeftClick, openBookPage, setBookGesture } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeScreen, screenOpen, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { aboard, GROUND } from "../shared/harness/stage";

const OFFER = "folkways.zoneconfig.offer.folkways.sweep";
const KIND = "folkways.sweep.kind.";
const TAKE = "folkways.sweep.take";
const STRIDE = 6;
const MAX_SPAN = 768;

// The task makes/observes the fixture. It never invokes BlockSweep.found, BlockSweep.take, MemberToggle,
// ColonyGround.hold or Colony.hold: the player's actual drawing and Take click are the only enrollment route.
const ALL_BLOCKS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.block-sweep-all.fixture",
  side: "server",
  timeoutMs: 30_000,
  source: javaTask({
    imports: [
      "net.minecraft.SharedConstants", "net.minecraft.core.BlockPos", "net.minecraft.core.Direction",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.level.block.Block", "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.level.block.BedBlock", "net.minecraft.world.level.block.ChestBlock",
      "net.minecraft.world.level.block.state.BlockState", "net.minecraft.world.level.block.state.properties.Property",
      "net.minecraft.world.level.block.state.properties.BedPart", "net.minecraft.world.level.block.state.properties.ChestType",
      "net.minecraft.world.level.block.state.properties.DoubleBlockHalf", "net.neoforged.fml.ModList",
      "io.github.izakyl.folkways.front.api.Shape", "io.github.izakyl.folkways.front.engine.Enrollments",
      "io.github.izakyl.folkways.front.engine.colony.ColonySites",
      "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
      "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held",
      "io.github.izakyl.folkways.core.api.terms.WorldPos",
    ],
    members: `
private record Candidate(String id,BlockState state,String owner,String shape){}
private record Row(Candidate candidate,BlockPos primary,BlockPos anchor,List<BlockPos> parts){}
private static final int FLAGS=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE;
private static List<Block> registry=List.of();
private static List<BlockState> states=List.of();
private static final List<Candidate> selected=new ArrayList<>();
private static final List<Row> row=new ArrayList<>();
private static final List<BlockPos> scratchParts=new ArrayList<>();
private static final List<Object> normalizations=new ArrayList<>();
private static int blockIndex,stateIndex,tried;
private static boolean matched;
private static BlockPos scratch;
private static BlockPos min,max;
private static String colonyId;
private static BlockPos pos(Object value){var p=(Map<?,?>)value;return new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());}
private static String key(BlockPos p){return p.getX()+","+p.getY()+","+p.getZ();}
private static String shape(Shape shape){return shape instanceof Shape.Point?"point":"block";}
@SuppressWarnings({"rawtypes","unchecked"})
private static BlockState property(BlockState state,Property property,Comparable value){return state.setValue(property,value);}
private static LinkedHashMap<BlockPos,BlockState> parts(BlockState state,BlockPos base)throws Exception{
  var result=new LinkedHashMap<BlockPos,BlockState>();result.put(base.immutable(),state);
  if(state.getBlock() instanceof BedBlock){
    var part=state.getValue(BedBlock.PART);var facing=state.getValue(BedBlock.FACING);
    var other=base.relative(part==BedPart.FOOT?facing:facing.getOpposite());
    result.put(other.immutable(),state.setValue(BedBlock.PART,part==BedPart.FOOT?BedPart.HEAD:BedPart.FOOT));
  }
  if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE){
    result.put(base.relative(ChestBlock.getConnectedDirection(state)).immutable(),
      state.setValue(ChestBlock.TYPE,state.getValue(ChestBlock.TYPE)==ChestType.LEFT?ChestType.RIGHT:ChestType.LEFT));
  }
  for(Property<?> p:state.getProperties()){
    var value=state.getValue(p);
    if(value instanceof DoubleBlockHalf half){
      result.put((half==DoubleBlockHalf.LOWER?base.above():base.below()).immutable(),
        property(state,p,half==DoubleBlockHalf.LOWER?DoubleBlockHalf.UPPER:DoubleBlockHalf.LOWER));
    }
    if(!p.getName().equals("part"))continue;
    java.lang.reflect.Method dx,dz;
    try{dx=value.getClass().getMethod("getPosX");dz=value.getClass().getMethod("getPosY");}
    catch(NoSuchMethodException notPlanarParts){continue;}
    int originX=((Number)dx.invoke(value)).intValue(),originZ=((Number)dz.invoke(value)).intValue();
    for(var v:p.getPossibleValues()){
      int x=((Number)dx.invoke(v)).intValue()-originX,z=((Number)dz.invoke(v)).intValue()-originZ;
      result.put(base.offset(x,0,z).immutable(),property(state,p,(Comparable<?>)v));
    }
  }
  return result;
}
private static void support(ServerLevel level,BlockPos base){
  for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlock(base.offset(x,-1,z),Blocks.SMOOTH_STONE.defaultBlockState(),FLAGS);
  for(var d:Direction.values())level.setBlock(base.relative(d),Blocks.SMOOTH_STONE.defaultBlockState(),FLAGS);
}
private static List<BlockPos> put(ServerLevel level,BlockPos base,BlockState state,boolean exact)throws Exception{
  support(level,base);var fixture=parts(state,base);
  // Soul fire needs its native support even when it is only a negative registry probe.
  if(state.is(Blocks.SOUL_FIRE))level.setBlock(base.below(),Blocks.SOUL_SOIL.defaultBlockState(),FLAGS);
  for(var entry:fixture.entrySet()){
    level.getChunk(entry.getKey());
    level.setBlock(entry.getKey(),entry.getValue(),FLAGS);
    var actual=level.getBlockState(entry.getKey());
    if(!actual.equals(entry.getValue())){
      if(exact||actual.getBlock()!=entry.getValue().getBlock())throw new IllegalStateException("placement changed "+key(entry.getKey())+" from "+entry.getValue()+" to "+actual);
      normalizations.add(Map.of("requested",entry.getValue().toString(),"actual",actual.toString(),"key",key(entry.getKey())));
    }
  }
  return List.copyOf(fixture.keySet());
}
private static void clearScratch(ServerLevel level){
  for(var at:scratchParts)level.setBlock(at,Blocks.AIR.defaultBlockState(),FLAGS);
  scratchParts.clear();
}
private static Map<String,Object> stateAt(ServerLevel level,BlockPos at){
  var state=level.getBlockState(at);var be=level.getBlockEntity(at);var out=new LinkedHashMap<String,Object>();
  out.put("key",key(at));out.put("at",Map.of("x",at.getX(),"y",at.getY(),"z",at.getZ()));
  out.put("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());out.put("state",state.toString());
  out.put("blockEntity",be!=null);out.put("blockEntityClass",be==null?"":be.getClass().getName());
  out.put("survives",state.canSurvive(level,at));return out;
}
private static String current(ServerLevel level,String playerName){
  var player=level.getServer().getPlayerList().getPlayerByName(playerName);
  if(player==null)throw new IllegalStateException("no player "+playerName);
  return CurrentColony.id(player).map(UUID::toString).orElse("");
}
`,
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();String action=args.string("action");
if(action.equals("start")){
  registry=BuiltInRegistries.BLOCK.stream().sorted(Comparator.comparing(b->BuiltInRegistries.BLOCK.getKey(b).toString())).toList();
  selected.clear();row.clear();scratchParts.clear();normalizations.clear();states=List.of();blockIndex=stateIndex=tried=0;matched=false;
  scratch=pos(args.value("scratch"));level.getChunk(scratch);colonyId="";
  var declarations=new ArrayList<Object>();
  for(var d:Enrollments.delegations())declarations.add(Map.of("id",d.id().toString(),"shape",d.shape().getClass().getSimpleName(),
    "blockOrPoint",d.shape() instanceof Shape.Block||d.shape() instanceof Shape.Point,"fallback",d.shape() instanceof Shape.Block b&&b.fallback()));
  var mods=new ArrayList<Object>();for(var mod:ModList.get().getMods())mods.add(Map.of("id",mod.getModId(),"version",mod.getVersion().toString()));
  return Map.of("registryCount",registry.size(),"declarations",declarations,"loadedMods",mods,
    "minecraftVersion",SharedConstants.getCurrentVersion().getName(),"scope","one physical representative for every matched registered block ID; default state first, otherwise all states until a match; no untested state-variant claim");
}
if(action.equals("scan")){
  var completed=new ArrayList<Object>();int work=0;
  while(blockIndex<registry.size()&&work<64){
    var block=registry.get(blockIndex);String id=BuiltInRegistries.BLOCK.getKey(block).toString();
    if(states.isEmpty()){
      var ordered=new LinkedHashSet<BlockState>();ordered.add(block.defaultBlockState());ordered.addAll(block.getStateDefinition().getPossibleStates());
      states=List.copyOf(ordered);stateIndex=tried=0;matched=false;normalizations.clear();
    }
    BlockState state=states.get(stateIndex++);tried++;work++;
    String failure=null;Candidate candidate=null;
    try{
      clearScratch(level);scratchParts.addAll(put(level,scratch,state,false));
      var d=ColonySites.accepting(level,scratch).orElse(null);
      if(d!=null){
        matched=true;var anchor=ColonySites.anchor(d.shape()).of(level,scratch).immutable();
        if(level.isLoaded(anchor)&&ColonySites.accepting(level,anchor).isPresent()
          &&(!(d.shape() instanceof Shape.Block)||level.getBlockEntity(anchor)!=null))
          candidate=new Candidate(id,level.getBlockState(scratch),d.id().toString(),shape(d.shape()));
      }
    }catch(Exception exception){failure=exception.getClass().getName()+": "+exception.getMessage();}
    if(candidate!=null||failure!=null||stateIndex==states.size()){
      var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("possibleStates",states.size());result.put("statesTried",tried);
      result.put("matchedDeclaration",matched);result.put("accepted",candidate!=null);
      result.put("normalizations",List.copyOf(normalizations));
      if(candidate!=null){selected.add(candidate);result.put("requestedState",state.toString());result.put("state",candidate.state().toString());result.put("owner",candidate.owner());result.put("shape",candidate.shape());}
      if(failure!=null)result.put("error",failure);
      if(candidate==null&&matched&&failure==null)result.put("error","declaration matched but no canonical registerable state was physically constructed");
      completed.add(result);blockIndex++;states=List.of();
    }
  }
  clearScratch(level);
  return Map.of("done",blockIndex==registry.size(),"completed",completed,"blockIndex",blockIndex,"registryCount",registry.size(),"selectedCount",selected.size(),"work",work);
}
if(action.equals("place")){
  int index=(int)args.integer("index");var candidate=selected.get(index);var base=pos(args.value("at"));
  var cells=put(level,base,candidate.state(),true);var d=ColonySites.accepting(level,base).orElseThrow(()->new IllegalStateException("placed candidate is no longer accepted: "+candidate.id()));
  var anchor=ColonySites.anchor(d.shape()).of(level,base).immutable();
  if(!d.id().toString().equals(candidate.owner())||!shape(d.shape()).equals(candidate.shape()))throw new IllegalStateException("placement changed declaration "+candidate.id());
  if(d.shape() instanceof Shape.Block&&level.getBlockEntity(anchor)==null)throw new IllegalStateException("missing canonical block entity "+candidate.id());
  row.add(new Row(candidate,base,anchor,cells));
  return Map.of("id",candidate.id(),"owner",candidate.owner(),"shape",candidate.shape(),"anchor",key(anchor),"primary",stateAt(level,base),
    "parts",cells.stream().map(at->stateAt(level,at)).toList());
}
if(action.equals("bounds")){
  min=pos(args.value("min"));max=pos(args.value("max"));colonyId=current(level,args.string("player"));
  ctx.server().getPlayerList().setViewDistance((int)args.integer("distance"));
  return Map.of("colony",colonyId,"distance",args.integer("distance"));
}
if(action.equals("audit")||action.equals("ledger")){
  var errors=new ArrayList<Object>();var fixtures=new ArrayList<Object>();
  for(var placed:row){
    var d=ColonySites.accepting(level,placed.primary()).orElse(null);
    if(d==null||!d.id().toString().equals(placed.candidate().owner())||!shape(d.shape()).equals(placed.candidate().shape()))errors.add("candidate declaration lost: "+placed.candidate().id());
    if(!BuiltInRegistries.BLOCK.getKey(level.getBlockState(placed.primary()).getBlock()).toString().equals(placed.candidate().id()))errors.add("candidate block lost: "+placed.candidate().id());
    if(d!=null&&!ColonySites.anchor(d.shape()).of(level,placed.primary()).equals(placed.anchor()))errors.add("candidate anchor moved: "+placed.candidate().id());
    var nativeParts=new ArrayList<Object>();for(var at:placed.parts()){
      var actual=stateAt(level,at);nativeParts.add(actual);
      if(!actual.get("block").equals(placed.candidate().id()))errors.add("native part lost: "+placed.candidate().id()+" at "+key(at));
      if(!Boolean.TRUE.equals(actual.get("survives")))errors.add("unsupported native part: "+placed.candidate().id()+" at "+key(at));
    }
    fixtures.add(Map.of("id",placed.candidate().id(),"owner",placed.candidate().owner(),"shape",placed.candidate().shape(),"anchor",key(placed.anchor()),"parts",nativeParts));
  }
  var expected=new LinkedHashMap<String,Map<String,Object>>();
  for(var mutable:BlockPos.betweenClosed(min,max)){
    var cell=mutable.immutable();if(!level.isLoaded(cell)){errors.add("unloaded row cell "+key(cell));continue;}
    var d=ColonySites.accepting(level,cell).orElse(null);if(d==null)continue;
    var anchor=ColonySites.anchor(d.shape()).of(level,cell).immutable();
    if(d.shape() instanceof Shape.Block&&level.getBlockEntity(anchor)==null){errors.add("accepted Block without entity "+key(anchor));continue;}
    var value=Map.<String,Object>of("key",key(anchor),"owner",d.id().toString(),"shape",shape(d.shape()),"at",Map.of("x",anchor.getX(),"y",anchor.getY(),"z",anchor.getZ()));
    var old=expected.putIfAbsent(key(anchor),value);if(old!=null&&!old.equals(value))errors.add("conflicting canonical ownership "+key(anchor));
  }
  var ledger=new ArrayList<Object>();
  for(var entry:expected.entrySet()){
    var anchor=pos(entry.getValue().get("at"));var holders=new ArrayList<Object>();
    for(var colony:Colonies.all(ctx.server())){
      var holding=colony.holdingAt(WorldPos.of(level,anchor)).orElse(null);
      if(holding!=null)holders.add(Map.of("colony",colony.id().toString(),"id",holding.id().toString(),"owner",holding.owner().toString(),
        "shape",holding.what() instanceof Held.Point?"point":holding.what() instanceof Held.Block?"block":"other"));
    }
    ledger.add(Map.of("key",entry.getKey(),"holders",holders));
  }
  int holdings=Colonies.of(ctx.server(),UUID.fromString(colonyId)).orElseThrow().holdings().size();
  return Map.of("errors",errors,"fixtures",fixtures,"expected",List.copyOf(expected.values()),"ledger",ledger,"colony",colonyId,"totalHoldings",holdings,
    "corners",List.of(stateAt(level,min),stateAt(level,max)));
}
throw new IllegalArgumentException("unknown all-blocks action "+action);
`,
  }),
});

const CLIENT_ROW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.block-sweep-all.client-row", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries"],
    body: `var mc=Minecraft.getInstance();var args=Args.of(ctx);
if(args.value("distance")!=null){mc.options.renderDistance().set((int)args.integer("distance"));mc.options.broadcastOptions();mc.levelRenderer.needsUpdate();return Map.of("distance",mc.options.renderDistance().get());}
var missing=new ArrayList<Object>();var mismatch=new ArrayList<Object>();
for(var raw:args.list("parts")){
  var one=(Map<?,?>)raw;var p=(Map<?,?>)one.get("at");var at=new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
  if(mc.level==null||!mc.level.isLoaded(at)){missing.add(one.get("key"));continue;}
  var state=mc.level.getBlockState(at);
  if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(one.get("block"))
    ||!state.toString().equals(one.get("state"))||((mc.level.getBlockEntity(at)!=null)!=Boolean.TRUE.equals(one.get("blockEntity"))))mismatch.add(Map.of("key",one.get("key"),"state",state.toString(),"blockEntity",mc.level.getBlockEntity(at)!=null));
}
return Map.of("missing",missing,"mismatch",mismatch);`,
  }),
});

function rowTexts(elements: Element[], token: string) {
  const root = elements.find(element => element.id === token);
  if (!root) throw new Error(`missing preview kind ${token}`);
  const texts: string[] = [];
  for (let i = root.index + 1; i < elements.length && elements[i].depth > root.depth; i++) {
    if (elements[i].text) texts.push(elements[i].text!);
  }
  return texts;
}

function jarHashes(instance: string) {
  const mods = path.join(instanceDirOf(instance, process.cwd()), "mods");
  const files = readdirSync(mods).filter(name => name.endsWith(".jar")).sort();
  if (!files.length) throw new Error(`no runtime mod jars found at ${mods}`);
  return { directory: mods, files: files.map(name => {
    const file = path.join(mods, name);
    return { name, bytes: statSync(file).size, sha256: createHash("sha256").update(readFileSync(file)).digest("hex") };
  }) };
}

const SCENES = [
  { name: "base", stage: GROUND, config: "blockwright.toml", cookery: false, maid: false },
  { name: "cookery", stage: GROUND, config: "blockwright.toml#cookery", cookery: true, maid: false },
  { name: "all-integrations", stage: aboard(), config: "blockwright.toml#sweep-all", cookery: true, maid: true },
];

for (const scene of SCENES) test(scene.stage.title(`every loaded enrollable block ID stands in one line and is taken through the actual sweep UI (${scene.name})`), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const name = scene.stage.name(`block-sweep-all-${scene.name}-${runId}`);
  const serverInstance = seededServer(`${name}-server`);
  const clientInstance = `${name}-client`;
  const directory = path.join(process.env.FOLKWAYS_SWEEP_ARTIFACT_DIR ?? outAbs("e2e", "reports"), name);
  mkdirSync(directory, { recursive: true });
  const evidence: any = { status: "running", scene: scene.name, config: scene.config, stage: scene.stage.kind, scans: [], placed: [] };
  let pair: E2EPair | undefined;
  const save = () => writeFileSync(path.join(directory, "all-blocks-evidence.json"), JSON.stringify(evidence, null, 2));
  try {
    pair = await launchPair({ config: scene.config,
      server: { instance: serverInstance, trace: tracePath("e2e", `${name}-server`) },
      client: { instance: clientInstance, trace: tracePath("e2e", `${name}-client`) },
    });
    const { server, client } = pair;
    const stage = scene.stage;
    const grounded = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    evidence.runtimeJars = { server: jarHashes(serverInstance), client: jarHashes(clientInstance) };
    evidence.metadata = await server.run(ALL_BLOCKS, { action: "start", scratch: { x: origin.x - 20, y: origin.y + 2, z: origin.z - 20 } });
    const loaded = evidence.metadata.loadedMods.map((mod: any) => mod.id);
    for (const id of ["folkways_core", "folkways_vanilla", "folkways_create", "folkways_modulargolems", "create", "modulargolems"]) expect(loaded).toContain(id);
    if (scene.cookery) for (const id of ["folkways_cookery", "kaleidoscope_cookery"]) expect(loaded).toContain(id);
    if (scene.maid) for (const id of ["sable", "touhou_little_maid"]) expect(loaded).toContain(id);
    let done = false;
    while (!done) {
      const scan = await server.run(ALL_BLOCKS, { action: "scan" });
      evidence.scans.push(...scan.completed);
      evidence.scanProgress = { blockIndex: scan.blockIndex, registryCount: scan.registryCount, selectedCount: scan.selectedCount };
      save();
      done = scan.done;
    }
    expect(evidence.scans).toHaveLength(evidence.metadata.registryCount);
    expect(new Set(evidence.scans.map((entry: any) => entry.id)).size).toBe(evidence.metadata.registryCount);
    expect(evidence.scans.filter((entry: any) => entry.error), "no registry entry may be lost through placement errors or an unresolved declaration").toEqual([]);
    const candidates = evidence.scans.filter((entry: any) => entry.accepted);
    expect(candidates.length).toBeGreaterThan(0);
    for (const id of ["minecraft:composter", "minecraft:chest", "minecraft:crafting_table", "minecraft:red_bed"]) expect(candidates.map((entry: any) => entry.id)).toContain(id);
    if (scene.cookery) expect(candidates.map((entry: any) => entry.id)).toContain("kaleidoscope_cookery:stove");
    const span = (candidates.length - 1) * STRIDE;
    expect(span, "the complete line must fit a fully observable client view; never truncate the manifest").toBeLessThanOrEqual(MAX_SPAN);
    const startX = origin.x - Math.floor(span / 2), rowZ = origin.z + 6;
    const first = { x: startX - 3, y: origin.y - 1, z: rowZ - 2 };
    const last = { x: startX + span + 3, y: origin.y + 1, z: rowZ + 2 };
    const plot = { min: { x: first.x - 1, y: origin.y - 1, z: origin.z - 2 }, max: { x: last.x + 1, y: origin.y + 2, z: rowZ + 3 } };
    await tryCommand(server, `forceload add ${plot.min.x} ${plot.min.z} ${plot.max.x} ${plot.max.z}`);
    // Assemble only the flat deck, then clear the taller scene in single layers so even a long row stays
    // below Minecraft's per-command fill limit.
    await stage.lay(server, { min: { ...plot.min, y: origin.y }, max: { ...plot.max, y: origin.y } });
    const from = stage.at(plot.min), to = stage.at(plot.max);
    await tryCommand(server, `forceload add ${from.x} ${from.z} ${to.x} ${to.z}`);
    for (let y = origin.y; y <= plot.max.y; y++) {
      await tryCommand(server, `fill ${commandPos(stage.at({ ...plot.min, y }))} ${commandPos(stage.at({ ...plot.max, y }))} minecraft:air`);
    }
    await tryCommand(server, `fill ${commandPos(stage.at({ ...plot.min, y: origin.y - 1 }))} ${commandPos(stage.at({ ...plot.max, y: origin.y - 1 }))} minecraft:smooth_stone`);
    await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
    await tryCommand(server, `gamemode survival ${grounded.name}`);
    await tryCommand(server, `item replace entity ${grounded.name} weapon.mainhand with folkways:colony_book`);
    evidence.founded = await createColonyViaBook(server, client, grounded.name, origin, stage);
    const distance = Math.min(32, Math.ceil((span / 2 + 16) / 16) + 2);
    evidence.view = await server.run(ALL_BLOCKS, { action: "bounds", min: stage.at(first), max: stage.at(last), player: grounded.name, distance });
    evidence.clientView = await client.run(CLIENT_ROW, { distance });
    for (let i = 0; i < candidates.length; i++) {
      const at = { x: startX + i * STRIDE, y: origin.y, z: rowZ };
      const placed = await server.run(ALL_BLOCKS, { action: "place", index: i, at: stage.at(at) });
      expect(placed.id, "every discovered ID gets its own actual physical representative").toBe(candidates[i].id);
      evidence.placed.push(placed);save();
    }
    for (const corner of [first, last]) await tryCommand(server, `setblock ${commandPos(stage.at(corner))} minecraft:smooth_stone`);
    await tick.sprint(server, 30);
    const audit = evidence.before = await server.run(ALL_BLOCKS, { action: "audit" });
    expect(audit.errors, "no support, native part, declaration, anchor or chunk may disappear after real ticks").toEqual([]);
    expect(audit.fixtures.map((fixture: any) => fixture.id)).toEqual(candidates.map((candidate: any) => candidate.id));
    expect(audit.expected).toHaveLength(candidates.length);
    const orderedMapping = (items: any[]) => items.sort((a, b) => a.key.localeCompare(b.key));
    expect(orderedMapping(audit.expected.map((entry: any) => ({ key: entry.key, owner: entry.owner, shape: entry.shape }))),
      "the complete canonical key/owner/shape map must match every placed candidate, without compensating omissions or extra supports")
      .toEqual(orderedMapping(audit.fixtures.map((fixture: any) => ({ key: fixture.anchor, owner: fixture.owner, shape: fixture.shape }))));
    expect(audit.totalHoldings).toBe(0);
    expect(audit.ledger.every((entry: any) => entry.holders.length === 0)).toBe(true);
    const groups: Record<string, number> = {};
    for (const entry of audit.expected) groups[entry.owner] = (groups[entry.owner] ?? 0) + 1;
    evidence.expectedGroups = groups;

    const atCenter = stage.stand({ x: origin.x + 0.5, y: origin.y, z: origin.z + 1.5 });
    const parts = [...audit.fixtures.flatMap((fixture: any) => fixture.parts), ...audit.corners];
    const sync = async (wanted = parts) => {
      await expect.poll(async () => {
        await tick.sprint(server, 10);await frames(client, 10);
        return client.run(CLIENT_ROW, { parts: wanted });
      }, { timeout: 90_000, message: "the client receives each required chunk, state and block entity before interacting" }).toEqual({ missing: [], mismatch: [] });
    };
    await tryCommand(server, `tp ${grounded.name} ${atCenter.x.toFixed(3)} ${atCenter.y.toFixed(3)} ${atCenter.z.toFixed(3)}`);
    await sync();evidence.clientRowReadyBeforeMark = true;
    const cornerClick = async (corner: Vec, standing: Vec, actual: any) => {
      const at = stage.stand(standing);
      await tryCommand(server, `tp ${grounded.name} ${at.x.toFixed(3)} ${at.y.toFixed(3)} ${at.z.toFixed(3)}`);
      await sync([actual]);
      return aimAndLeftClick(server, client, grounded.name, standing, corner, stage);
    };

    const mark = async () => {
      await screen.dismiss(client);
      await setBookGesture(server, client, grounded.name, "box");
      await drainActionBar(client);
      const firstClick = await cornerClick(first,
        { x: first.x + 0.5, y: origin.y, z: first.z - 2.5 }, audit.corners[0]);
      await tick.sprint(server, 4);
      const firstReceipt = await readActionBar(client);
      const lastClick = await cornerClick(last,
        { x: last.x + 0.5, y: origin.y, z: last.z - 2.5 }, audit.corners[1]);
      await tick.sprint(server, 4);
      const lastReceipt = await readActionBar(client);
      expect(lastReceipt.text).toMatch(/marked$/);
      return { firstClick, firstReceipt, lastClick, lastReceipt };
    };
    evidence.mark = await mark();
    await tryCommand(server, `tp ${grounded.name} ${atCenter.x.toFixed(3)} ${atCenter.y.toFixed(3)} ${atCenter.z.toFixed(3)}`);
    await sync();
    evidence.clientRowSynchronized = true;
    const open = async () => {
      await openBookPage(server, client, grounded.name, origin, stage);
      await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
      await clickElement(client, { id: OFFER });await settlePanel(server, client);
    };
    await open();
    evidence.previewTree = await panelElements(client);
    expect(evidence.previewTree.filter((element: Element) => element.id?.startsWith(KIND)).map((element: Element) => element.id).sort())
      .toEqual(Object.keys(groups).map(owner => KIND + owner.replace(/[:/]/g, ".")).sort());
    evidence.previewGroups = {};
    for (const [owner, count] of Object.entries(groups)) {
      const token = KIND + owner.replace(/[:/]/g, ".");
      await scrollElementIntoView(client, { id: token });
      const texts = rowTexts(await panelElements(client), token);
      expect(texts, `client preview counts all canonical ${owner} members`).toContain(String(count));
      evidence.previewGroups[owner] = { count, texts };
    }
    await media.screenshot(client, { path: path.join(directory, "all-blocks-preview.png") });
    await scrollElementIntoView(client, { id: TAKE });await drainActionBar(client);
    evidence.take = await clickElement(client, { id: TAKE });
    await expect.poll(async () => { await tick.sprint(server, 3);return screenOpen(await safeScreen(client)); }, { timeout: 8_000 }).toBe(false);
    await expect.poll(async () => { await tick.sprint(server, 3);return (await readActionBar(client)).text; }, { timeout: 8_000 }).toBe(`Took in ${candidates.length} blocks`);
    evidence.receipt = await readActionBar(client);
    const after = evidence.after = await server.run(ALL_BLOCKS, { action: "ledger" });
    expect(after.errors).toEqual([]);expect(after.totalHoldings).toBe(candidates.length);
    const byKey = new Map(audit.expected.map((entry: any) => [entry.key, entry]));
    const identities: string[] = [];
    for (const entry of after.ledger) {
      const wanted: any = byKey.get(entry.key);
      expect(entry.holders, `exactly one real holding for ${entry.key}`).toHaveLength(1);
      expect(entry.holders[0]).toEqual(expect.objectContaining({ colony: audit.colony, owner: wanted.owner, shape: wanted.shape }));
      identities.push(entry.holders[0].id);
    }
    expect(new Set(identities).size).toBe(candidates.length);
    await frames(client, 30);await tick.sprint(server, 30);
    evidence.repeatMark = await mark();
    await tryCommand(server, `tp ${grounded.name} ${atCenter.x.toFixed(3)} ${atCenter.y.toFixed(3)} ${atCenter.z.toFixed(3)}`);
    await expect.poll(async () => { await tick.sprint(server, 5);await frames(client, 5);return client.run(CLIENT_ROW, { parts }); }, { timeout: 90_000 }).toEqual({ missing: [], mismatch: [] });
    await open();const repeatTree = evidence.repeatPreview = await panelElements(client);
    if (repeatTree.some(element => element.id === TAKE)) {
      await scrollElementIntoView(client, { id: TAKE });await drainActionBar(client);
      evidence.repeatClick = await clickElement(client, { id: TAKE });
      await expect.poll(async () => { await tick.sprint(server, 3);return (await readActionBar(client)).text; }, { timeout: 8_000 }).toBe("nothing in the box can be taken in");
    } else {
      expect(repeatTree.map(element => element.text).filter(Boolean).join(" ")).toContain("Nothing in the box can be taken in");
      evidence.repeatClick = "no Take action: all already held";
    }
    const repeated = evidence.repeated = await server.run(ALL_BLOCKS, { action: "ledger" });
    expect(repeated.totalHoldings).toBe(candidates.length);expect(repeated.ledger).toEqual(after.ledger);expect(repeated.errors).toEqual([]);
    evidence.status = "passed";save();
  } catch (error) {
    evidence.status = "failed";evidence.failure = String(error);save();pair?.failing(error);throw error;
  } finally {
    save();await testInfo.attach(`${name}-all-blocks.json`, { path: path.join(directory, "all-blocks-evidence.json"), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

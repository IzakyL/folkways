// What the Sable specs read off a colony on the server: the cells it holds and on which ship, its stock, how many
// ways on and off a ship its passage offers, and which of its residents stand aboard.
import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "./colony-tasks";

export type HeldCell = { owner: string; frame?: string; local?: string; unplaced?: boolean; x: number; y: number; z: number };

const COLONY_IMPORTS = [
  "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
  "io.github.izakyl.folkways.core.api.terms.Realm",
  "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
  "net.minecraft.core.registries.BuiltInRegistries",
  "net.minecraft.resources.ResourceLocation",
  "net.minecraft.world.Container",
];

/** Every cell the colony holds, where the level stores it, and how much of `item` its stores hold. */
export async function readColony(server: MinecraftServer, colonyId: string, item: string): Promise<{ cells: HeldCell[]; stock: number }> {
  const read = await runColonyTask(server, READ_COLONY, { colony_id: colonyId, item });
  return { cells: read.cells as HeldCell[], stock: Number(read.stock) };
}

/** One line per held cell, sorted: what two reads of a colony compare by. */
export function cellLines(cells: HeldCell[]): string[] {
  return cells.map((cell) => `${cell.owner} ${cell.frame ?? "-"} ${cell.x} ${cell.y} ${cell.z}`).sort();
}

/** The ways on and off a ship the Sable passage finds for the colony right now. */
export async function sableHops(server: MinecraftServer, colonyId: string): Promise<number> {
  return Number((await runColonyTask(server, HOPS, { colony_id: colonyId })).hops);
}

/** Each of the colony's residents, with the structure it stands aboard, if any. */
export async function residentsAboard(server: MinecraftServer, colonyId: string): Promise<Array<{ id: string; frame?: string; at?: string }>> {
  return (await runColonyTask(server, ABOARD, { colony_id: colonyId })).residents as any[];
}

const READ_COLONY = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-read-colony",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: COLONY_IMPORTS,
    body: `
var server=ctx.server();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("item")));
var cells=new ArrayList<Object>();int stock=0;
for(var view:colony.views(server)){
  for(var holding:view.holdings())for(var cell:holding.what().cells()){
    var one=new LinkedHashMap<String,Object>();
    one.put("owner",holding.owner().toString());
    // A cell aboard is kept relative to its ship; read back where the level stores it, to compare with the plot.
    var at=cell.cell();
    if(cell.realm() instanceof Realm.Frame frame){
      one.put("frame",frame.structure().toString());one.put("local",at.toShortString());
      at=WorldSpaces.storage(view.level(),cell).orElse(null);
      if(at==null){one.put("unplaced",true);one.put("x",0);one.put("y",0);one.put("z",0);cells.add(one);continue;}
    }
    one.put("x",at.getX());one.put("y",at.getY());one.put("z",at.getZ());
    cells.add(one);
  }
  for(var pos:view.blocks(ResourceLocation.parse("folkways:store")))
    if(view.level().getBlockEntity(pos) instanceof Container chest)
      for(int i=0;i<chest.getContainerSize();i++){var stack=chest.getItem(i);if(stack.is(item))stock+=stack.getCount();}
}
return Map.of("cells",cells,"stock",stock);
`,
  }),
});

const HOPS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-hops",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.sable.SablePassage",
    ],
    body: `
var colony=ColonyGround.of(ctx.server(),UUID.fromString(Args.of(ctx).string("colony_id"))).orElseThrow();
// A fresh passage each read: the one the colony uses keeps what it found for ten ticks.
return Map.of("hops",new SablePassage().hopsIn(colony).size());
`,
  }),
});

const ABOARD = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-aboard",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: COLONY_IMPORTS,
    body: `
var server=ctx.server();var level=server.overworld();
var colony=ColonyGround.of(server,UUID.fromString(Args.of(ctx).string("colony_id"))).orElseThrow();
var out=new ArrayList<Object>();
for(var resident:colony.residents()){
  var one=new LinkedHashMap<String,Object>();one.put("id",resident.id().toString());
  var entity=level.getEntity(resident.id());
  if(entity!=null){
    var at=WorldSpaces.at(entity);
    one.put("at",entity.position().toString());
    if(at.realm() instanceof Realm.Frame frame)one.put("frame",frame.structure().toString());
  }
  out.add(one);
}
return Map.of("residents",out);
`,
  }),
});

/** Where a ship's pose puts it now, and how it is turned. */
export async function shipPose(server: MinecraftServer, structure: string): Promise<{ x: number; y: number; z: number; w: number }> {
  return (await runColonyTask(server, POSE, { structure })) as any;
}

/**
 * Flies a ship by `by` (blocks) under Sable's own physics, the way a moving ship carries what stands on it, then
 * pauses physics again with the ship held where it came to. Answers the pose it ended at.
 */
export async function driveShip(server: MinecraftServer, structure: string, by: { x: number; y: number; z: number },
  tickSprint: (ticks: number) => Promise<unknown>, budgetTicks = 600) {
  const start = await shipPose(server, structure);
  const target = { x: start.x + by.x, y: start.y + by.y, z: start.z + by.z };
  await runColonyTask(server, DRIVE, { structure, target, driving: true });
  let now = start;
  for (let ticks = 0; ticks < budgetTicks; ticks += 10) {
    await tickSprint(10);
    now = await shipPose(server, structure);
    if (Math.hypot(now.x - target.x, now.y - target.y, now.z - target.z) < 0.05) break;
  }
  await runColonyTask(server, DRIVE, { structure, target, driving: false });
  return { start, target, end: await shipPose(server, structure) };
}

const POSE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-pose",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["dev.ryanhcode.sable.api.sublevel.SubLevelContainer"],
    body: `
var ship=SubLevelContainer.getContainer(ctx.server().overworld()).getSubLevel(UUID.fromString(Args.of(ctx).string("structure")));
if(ship==null)throw Fail.notFound("no Sable structure "+Args.of(ctx).string("structure"));
var pose=ship.logicalPose();var p=pose.position();var q=pose.orientation();
return Map.of("x",p.x(),"y",p.y(),"z",p.z(),"w",q.w());
`,
  }),
});

// One listener steers every ship whose user data names a drive target: each physics step sets its velocity toward
// the target, and keeps it from turning. Driving stops by clearing the target, and physics is paused again.
const DRIVE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-drive",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
      "dev.ryanhcode.sable.sublevel.ServerSubLevel",
      "dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem",
      "dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent",
      "net.minecraft.nbt.CompoundTag",
      "net.neoforged.neoforge.common.NeoForge",
      "org.joml.Vector3d",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var ship=(ServerSubLevel)SubLevelContainer.getContainer(level).getSubLevel(UUID.fromString(args.string("structure")));
if(ship==null)throw Fail.notFound("no Sable structure "+args.string("structure"));
if(ship.getUserDataTag()==null)ship.setUserDataTag(new CompoundTag());
var tag=ship.getUserDataTag();
if(System.getProperty("folkways.e2e.sableDrive")==null){
  System.setProperty("folkways.e2e.sableDrive","on");
  NeoForge.EVENT_BUS.addListener((ForgeSablePrePhysicsTickEvent event)->{
    for(var any:SubLevelContainer.getContainer(event.getPhysicsSystem().getLevel()).getAllSubLevels()){
      if(!(any instanceof ServerSubLevel one)||one.isRemoved()||one.getUserDataTag()==null||!one.getUserDataTag().getBoolean("folkwaysDrive"))continue;
      var handle=event.getPhysicsSystem().getPhysicsHandle(one);if(handle==null||!handle.isValid())continue;
      var t=one.getUserDataTag();var p=one.logicalPose().position();
      var want=new Vector3d(Math.clamp((t.getDouble("tx")-p.x())*2,-4,4),Math.clamp((t.getDouble("ty")-p.y())*2,-4,4),Math.clamp((t.getDouble("tz")-p.z())*2,-4,4));
      want.sub(handle.getLinearVelocity());
      handle.addLinearAndAngularVelocity(want,new Vector3d(handle.getAngularVelocity()).negate());
    }
  });
}
var target=(Map<?,?>)args.value("target");
tag.putDouble("tx",((Number)target.get("x")).doubleValue());tag.putDouble("ty",((Number)target.get("y")).doubleValue());tag.putDouble("tz",((Number)target.get("z")).doubleValue());
boolean driving=Boolean.TRUE.equals(args.value("driving"));
tag.putBoolean("folkwaysDrive",driving);
if(!driving){
  var handle=SubLevelPhysicsSystem.require(level).getPhysicsHandle(ship);
  if(handle!=null&&handle.isValid())handle.addLinearAndAngularVelocity(new Vector3d(handle.getLinearVelocity()).negate(),new Vector3d(handle.getAngularVelocity()).negate());
}
SubLevelPhysicsSystem.require(level).setPaused(!driving);
return Map.of("driving",driving);
`,
  }),
});

/**
 * For the block stored at `storage` aboard a ship: the address the colony would give it, whether that address leads
 * back to the same stored block, and how far the world point it names lies from where Sable's pose shows the block.
 */
export async function addressRoundTrip(server: MinecraftServer, storage: { x: number; y: number; z: number }) {
  return runColonyTask(server, ROUND_TRIP, { storage });
}

const ROUND_TRIP = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.sable-round-trip",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "dev.ryanhcode.sable.Sable",
      "io.github.izakyl.folkways.core.api.terms.Realm",
      "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
      "net.minecraft.core.BlockPos",
      "net.minecraft.world.phys.Vec3",
    ],
    body: `
var level=ctx.server().overworld();var m=(Map<?,?>)Args.of(ctx).value("storage");
var storage=new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());
var address=WorldSpaces.at(level,storage);
var back=WorldSpaces.storage(level,address).orElse(null);
var seen=WorldSpaces.world(level,address).orElse(null);
var ship=Sable.HELPER.getContaining(level,storage);
var shown=ship==null?null:ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(storage));
var out=new LinkedHashMap<String,Object>();
out.put("aboard",address.realm() instanceof Realm.Frame);
out.put("address",address.toString());
out.put("back_to_storage",storage.equals(back));
out.put("world",String.valueOf(seen));out.put("pose_shows",String.valueOf(shown));
out.put("world_off_by",seen==null||shown==null?-1.0:seen.distanceTo(shown));
return out;
`,
  }),
});

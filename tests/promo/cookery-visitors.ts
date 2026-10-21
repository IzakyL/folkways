import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { licenseCrewFast } from "./fast-setup";

const OWNER = "folkways:promo_visitors";

function layout(origin: Vec, count: number) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return Array.from({ length: count }, (_, i) => ({
    // Alternate roads and floors. Separate waiting positions keep the pavement clear.
    start: at(i % 2 === 0 ? -37 - Math.floor(i / 2) * 2 : 27 + Math.floor(i / 2) * 2, -16),
    entrance: i % 2 === 0 ? at(-5, -5, 6) : at(-3, -10),
    area: i % 2 === 0 ? "balcony" : "courtyard",
    release: i * 8,
  }));
}

/** Frozen-time blocking only. Visitors carry no cargo and have no delivery jobs. */
export async function stageCookeryVisitors(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (!workersUUIDs.length || new Set(workersUUIDs).size !== workersUUIDs.length) {
    throw new Error("Cookery visitors require distinct newly admitted residents");
  }
  const licensed = await licenseCrewFast(server, workersUUIDs.map(uuid => ({ uuid, trades: [] })));
  const plans = layout(origin, workersUUIDs.length);
  const prepared = await runColonyTask(server, STAGE, { colony_id, origin, workers: workersUUIDs, plans });
  return { visitors: workersUUIDs, workers: workersUUIDs, plans, licensed, prepared,
    initial: await readCookeryVisitors(server, colony_id, origin, workersUUIDs),
    via: "native-walking-and-dining" };
}

/** Direct the next entrance/exit; every visible journey runs through the native BodyRunner/Travel.
 * Appetite is created only after the entrance walk finishes, so balcony visitors take the elevator
 * before selecting a table. Dining completion is taken from the real living-system history. */
export async function stepCookeryVisitors(
  server: MinecraftServer, colony_id: string, origin: Vec, visitorUUIDs: string[], seconds: number,
) {
  await runColonyTask(server, STEP, {
    colony_id, origin, workers: visitorUUIDs, plans: layout(origin, visitorUUIDs.length), seconds,
  });
  return readCookeryVisitors(server, colony_id, origin, visitorUUIDs);
}

/** Read-only evidence: positions, actual food level, dined history and native journey endings. */
export async function readCookeryVisitors(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, origin, workers: workersUUIDs });
}

export function summarizeCookeryVisitors(samples: Array<{ seconds: number; [key: string]: any }>, workersUUIDs: string[]) {
  return workersUUIDs.map(uuid => {
    let outside: number | undefined, entered: number | undefined, ate: number | undefined, departed: number | undefined;
    let baseline = -24000, visits = 0;
    let last: any;
    let diningSamples = 0, upperFloorSamples = 0;
    const phases = new Set<string>();
    for (const sample of samples) {
      const person = sample.people?.find((one: any) => one.uuid === uuid);
      if (!person) continue;
      last = person;
      phases.add(person.phase);
      if (person.doing === "dining_eating") diningSamples++;
      if (person.local?.[1] >= 5) upperFloorSamples++;
      if (outside === undefined && person.offscreen) { outside = sample.seconds; baseline = person.dined; }
      if (outside !== undefined && entered === undefined && sample.seconds > outside && person.insideInn) entered = sample.seconds;
      if (entered !== undefined && ate === undefined && person.dined > baseline && person.dined >= sample.started) ate = sample.seconds;
      if (ate !== undefined && sample.seconds > ate && person.offscreen && person.departures > 0) departed ??= sample.seconds;
      visits = Math.max(visits, person.departures ?? 0);
    }
    const incompleteReason = outside === undefined ? "never observed off camera"
      : entered === undefined ? "entrance walk not observed"
      : ate === undefined ? "no completed table meal observed"
      : departed === undefined ? "exit walk not completed off camera" : undefined;
    return { uuid, outside, entered, ate, departed, visits, diningSamples, upperFloorSamples,
      phases: [...phases], lastPhase: last?.phase, lastDoing: last?.doing, lastLocal: last?.local,
      lastFood: last?.food, lastDined: last?.dined, walkEnding: last?.walkEnding,
      failure: last?.failure || undefined, lastFailure: last?.lastFailure || undefined,
      walkAttempts: last?.walkAttempts ?? 0, walkFailures: last?.walkFailures ?? 0,
      walkRetries: last?.walkRetries ?? 0, retryAt: last?.retryAt, incompleteReason,
      complete: outside !== undefined && entered !== undefined && ate !== undefined && departed !== undefined };
  });
}

const imports = [
  "io.github.izakyl.folkways.core.api.colony.Colonies",
  "io.github.izakyl.folkways.core.api.resident.body.Bodies",
  "io.github.izakyl.folkways.core.api.work.*",
  "io.github.izakyl.folkways.plugins.person.living.LivingContent",
  "net.minecraft.core.BlockPos", "net.minecraft.nbt.CompoundTag", "net.minecraft.resources.ResourceLocation",
  "io.github.izakyl.folkways.core.api.colony.Colony", "net.minecraft.server.level.ServerLevel",
];

const walkMembers = `
static void walk(Colony colony,ServerLevel level,ResourceLocation owner,UUID resident,String uuid,BlockPos destination,String after){
 walk(colony,level,owner,resident,uuid,destination,after,1);
}
static void walk(Colony colony,ServerLevel level,ResourceLocation owner,UUID resident,String uuid,BlockPos destination,String after,int ticks){
 var spec=NodeSpec.of(UUID.randomUUID(),owner,WorkSite.at(level,destination),Stances.at(level,destination),Workload.Once.of(ticks))
  .effort(WorkEffort.NONE).doing(ResourceLocation.parse("folkways:walking")).done();
 // Persist the exact native leg before submitting it. Callback tokens prevent a late
 // ending from an older attempt from overwriting a newer attempt's phase.
 var history=colony.kept(owner).copy();var pending=history.getCompound(uuid);
 pending.putLong("walkDestination",destination.asLong());pending.putString("walkAfter",after);
 pending.putInt("walkTicks",ticks);pending.putString("walkPhase",pending.getString("phase"));
 pending.putUUID("activeWalk",spec.id());pending.putInt("walkAttempts",pending.getInt("walkAttempts")+1);
 history.put(uuid,pending);colony.keep(owner,history);
 var node=new Node(){
  public NodeSpec spec(){return spec;}
  public Optional<UUID> worker(){return Optional.of(resident);}
  public Outcome commit(ServerLevel world,Worker who){return Outcome.done();}
 };
 colony.submit(owner,level.dimension(),Grown.of(node),(id,ending)->level.getServer().execute(()->{
  var saved=colony.kept(owner).copy();var state=saved.getCompound(uuid);
  if(!state.hasUUID("activeWalk")||!state.getUUID("activeWalk").equals(id))return;
  state.putString("walkEnding",ending.toString());
  if(ending instanceof Ending.Done){
   state.putString("phase",after);state.remove("failure");state.remove("retryAt");
  }else{
   state.putString("phase","walk_failed");state.putString("failure",ending.toString());
   state.putString("lastFailure",ending.toString());state.putInt("walkFailures",state.getInt("walkFailures")+1);
   state.putLong("retryAt",level.getGameTime()+100);
  }
  saved.put(uuid,state);colony.keep(owner,saved);
 }));
}
`;

const STAGE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-stage", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [...imports, "io.github.izakyl.folkways.core.api.terms.Footing"], body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
if(!level.tickRateManager().isFrozen())throw new IllegalStateException("Visitors must be staged while ticks are frozen");
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var ids=args.strings("workers");var saved=new CompoundTag();saved.putLong("started",level.getGameTime());
for(int i=0;i<ids.size();i++){
 var body=Bodies.of(level.getEntity(UUID.fromString(ids.get(i)))).orElseThrow();
 if(!colony.residents().contains(body.resident()))throw new IllegalStateException("Visitor is not a colony resident: "+ids.get(i));
 var plan=Args.of((Map)args.list("plans").get(i),"visitor");var post=plan.blockPos("start");
 if(!Footing.withFeetAt(level,post))throw new IllegalStateException("Visitor road footing is obstructed: "+post);
 if(!Footing.withFeetAt(level,plan.blockPos("entrance")))throw new IllegalStateException("Visitor entrance is obstructed: "+plan.blockPos("entrance"));
 body.pack().clearContent();body.mob().teleportTo(post.getX()+0.5,post.getY(),post.getZ()+0.5);body.mob().setInvulnerable(true);
 var state=new CompoundTag();state.putString("phase","holding");state.putDouble("release",plan.number("release"));
 state.putString("area",plan.string("area"));state.putInt("departures",0);saved.put(ids.get(i),state);
}
var owner=ResourceLocation.parse("${OWNER}");colony.keep(owner,saved);
for(int i=0;i<ids.size();i++){
 var uuid=ids.get(i);var body=Bodies.of(level.getEntity(UUID.fromString(uuid))).orElseThrow();
 var plan=Args.of((Map)args.list("plans").get(i),"visitor");
 walk(colony,level,owner,body.resident().id(),uuid,plan.blockPos("start"),"waiting",Math.max(1,(int)(plan.number("release")*20)));
}
return Sync.stamp(Map.of("started",level.getGameTime(),"workers",ids,"stagedOnlyWhileFrozen",true));
`, members: walkMembers }),
});

const STEP = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-step", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [...imports,
    "io.github.izakyl.folkways.effect.Stats",
  ], body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var owner=ResourceLocation.parse("${OWNER}");var saved=colony.kept(owner).copy();
var ids=args.strings("workers");double seconds=args.number("seconds");var life=colony.kept(LivingContent.ID);
var walks=new ArrayList<Runnable>();
for(int i=0;i<ids.size();i++){
 var uuid=ids.get(i);var entity=level.getEntity(UUID.fromString(uuid));if(entity==null)continue;
 var body=Bodies.of(entity).orElseThrow();var state=saved.getCompound(uuid);var phase=state.getString("phase");
 var plan=Args.of((Map)args.list("plans").get(i),"visitor");
 long dined=-24000;for(var raw:life.getList("moods",10)){var mood=(CompoundTag)raw;
  if(mood.hasUUID("resident")&&mood.getUUID("resident").equals(body.resident().id()))dined=mood.getCompound("times").getLong("dined");}
 int food=-1;for(var raw:life.getList("hunger",10)){var hunger=(CompoundTag)raw;
  if(hunger.hasUUID("resident")&&hunger.getUUID("resident").equals(body.resident().id()))food=hunger.getInt("FoodLevel");}
 if(phase.equals("walk_failed")&&level.getGameTime()>=state.getLong("retryAt")&&state.contains("walkDestination")){
  var retryDestination=BlockPos.of(state.getLong("walkDestination"));
  var retryAfter=state.getString("walkAfter");int retryTicks=Math.max(1,state.getInt("walkTicks"));
  state.putString("phase",state.getString("walkPhase"));state.putInt("walkRetries",state.getInt("walkRetries")+1);
  walks.add(()->walk(colony,level,owner,body.resident().id(),uuid,retryDestination,retryAfter,retryTicks));
 }else if(phase.equals("waiting")&&seconds>=state.getDouble("release")){
  state.putString("phase","arriving");state.putLong("baseline",dined);
  walks.add(()->walk(colony,level,owner,body.resident().id(),uuid,plan.blockPos("entrance"),"inside"));
 }else if(phase.equals("inside")){
  // Actual survival exertion creates hunger; no food, eating progress or mood is fabricated.
  WorkExertion.performed(body.mob(),4000/Math.max(0.1,body.effects().value(Stats.APPETITE)),0,0);
  state.putString("phase","dining");state.putLong("baseline",dined);
 }else if(phase.equals("dining")&&dined>state.getLong("baseline")&&food>=18){
  // Living's hunger watermark stays engaged until FoodLevel reaches 18, including after
  // a small meal. Let real repeat meals finish before asking this diner to walk out.
  // ate()/dined() synchronously write the hunger and mood snapshot used above.
  state.putString("phase","departing");state.putLong("ateAt",dined);
  walks.add(()->walk(colony,level,owner,body.resident().id(),uuid,plan.blockPos("start"),"outside"));
 }else if(phase.equals("outside")){
  var start=plan.blockPos("start");
  if(entity.distanceToSqr(start.getX()+0.5,start.getY(),start.getZ()+0.5)<=9){
   state.putInt("departures",state.getInt("departures")+1);
   state.putDouble("release",seconds+8+(i%4)*2);state.putString("phase","holding");
   int waitingTicks=(8+(i%4)*2)*20;
   walks.add(()->walk(colony,level,owner,body.resident().id(),uuid,start,"waiting",waitingTicks));
  }
 }
 saved.put(uuid,state);
}
colony.keep(owner,saved);for(var walking:walks)walking.run();
return Sync.stamp(Map.of("seconds",seconds));
`, members: `
${walkMembers}
` }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-visitors-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports, body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var life=colony.kept(LivingContent.ID);var history=colony.kept(ResourceLocation.parse("${OWNER}"));
var origin=args.blockPos("origin");var people=new ArrayList<Object>();
for(var uuid:args.strings("workers")){
 var entity=level.getEntity(UUID.fromString(uuid));if(entity==null)continue;
 var body=Bodies.of(entity).orElseThrow();double x=entity.getX()-origin.getX(),z=entity.getZ()-origin.getZ(),y=entity.getY()-origin.getY();
 long dined=-24000;int food=-1;
 for(var raw:life.getList("moods",10)){var mood=(CompoundTag)raw;
  if(mood.hasUUID("resident")&&mood.getUUID("resident").equals(entity.getUUID()))dined=mood.getCompound("times").getLong("dined");}
 for(var raw:life.getList("hunger",10)){var hunger=(CompoundTag)raw;
  if(hunger.hasUUID("resident")&&hunger.getUUID("resident").equals(entity.getUUID()))food=hunger.getInt("FoodLevel");}
 var state=history.getCompound(uuid);var one=new LinkedHashMap<String,Object>();
 one.put("uuid",uuid);one.put("role","visitor");one.put("position",List.of(entity.getX(),entity.getY(),entity.getZ()));one.put("local",List.of(x,y,z));
 one.put("doing",body.doing().map(doing->doing.toward().orElse(doing.what()).getPath()).orElse("idle"));
 one.put("food",food);one.put("dined",dined);one.put("phase",state.getString("phase"));one.put("area",state.getString("area"));
 one.put("walkAttempts",state.getInt("walkAttempts"));one.put("walkFailures",state.getInt("walkFailures"));
 one.put("walkRetries",state.getInt("walkRetries"));one.put("retryAt",state.getLong("retryAt"));
 one.put("lastFailure",state.getString("lastFailure"));
 one.put("departures",state.getInt("departures"));one.put("walkEnding",state.getString("walkEnding"));one.put("failure",state.getString("failure"));
 one.put("insideInn",x>=-20&&x<=-2&&z>=-13&&z<=3&&y>=-0.5&&y<=8);
 one.put("offscreen",x<=-34||x>=24);one.put("outboundOnRoad",z>=-19&&z<=-13&&(x<=-25||x>=17));
 people.add(one);
}
return Map.of("time",level.getGameTime(),"started",history.getLong("started"),"people",people);
` }),
});

import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { stageElevatorConstruction, readElevatorConstruction } from "./elevator-construction";
import { stageElevatorRural } from "./elevator-rural";
import { stageElevatorWorkshop, refreshElevatorWorkshop } from "./elevator-workshop";

type Worker = { uuid: string; floor: number };
const POSTS = [
  { role: "builder", floor: 2 }, { role: "builder", floor: 2 }, { role: "farmer", floor: 0 },
  { role: "forester", floor: 0 }, { role: "forester", floor: 0 },
  { role: "farmer", floor: 0 }, { role: "farmer", floor: 0 },
  { role: "herder", floor: 0 }, { role: "fisher", floor: 0 },
  { role: "cook", floor: 1 }, { role: "farmer", floor: 0 },
  { role: "hauler", floor: 0 }, { role: "hauler", floor: 1 },
  { role: "hauler", floor: 2 },
] as const;

/** A working colony: visible construction, land work, timber, crops, animals, fishing and cooking.
 * Three carriers supply these real workplaces through the lift, with no scripted passenger movement. */
export async function stageElevatorWork(server: MinecraftServer, player: string, colony_id: string, origin: Vec, crew: Worker[]) {
  if (crew.length !== POSTS.length) throw new Error(`Expected ${POSTS.length} actors, got ${crew.length}`);
  const roster = crew.map((one, index) => ({ ...one, ...POSTS[index] }));
  const rural = await stageElevatorRural(server, player, colony_id, origin,
    roster.filter(one => ["forester", "farmer", "herder", "fisher"].includes(one.role)) as any);
  const construction = await stageElevatorConstruction(server, player, colony_id, origin,
    roster.filter(one => ["builder", "paver"].includes(one.role)));
  const workshop = await stageElevatorWorkshop(server, player, colony_id, origin,
    roster.filter(one => ["cook", "crafter", "hauler"].includes(one.role)) as any);
  return { roster, rural, construction, workshop };
}
export { readElevatorConstruction, refreshElevatorWorkshop };
export async function readElevatorWork(server: MinecraftServer, colony_id: string, origin: Vec) {
  return runColonyTask(server, READ_WORK, { colony_id, origin });
}

const READ_WORK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-work-read", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies"],
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();var o=args.requireObject("origin").raw();double oy=((Number)o.get("y")).doubleValue();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var residents=new ArrayList<Object>();var activities=new LinkedHashMap<String,Integer>();
for(var resident:colony.residents()){
 var entity=level.getEntity(resident.id());if(entity==null)continue;
 var doing=Bodies.of(entity).flatMap(b->b.doing()).map(Object::toString).orElse("idle");
 activities.merge(doing,1,Integer::sum);
 residents.add(Map.of("uuid",entity.getUUID().toString(),"floor",Math.max(0,Math.min(2,(int)Math.round((entity.getY()-oy-1)/10))),
  "doing",doing,"seated",entity.isPassenger(),"x",entity.getX(),"y",entity.getY(),"z",entity.getZ()));
}
return Sync.stamp(Map.of("residents",residents,"activities",activities));`,
  }),
});

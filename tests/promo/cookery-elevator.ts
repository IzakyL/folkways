import { defineTask, javaTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos } from "../shared/harness/bw-helpers";

const RISE = 6;

/** Origin is the garden's ground-floor feet height; the cabin deck is one block below it.
 * Static landings belong to the garden set. Only this five-by-five shaft is assembled here.
 * After delegation, ordinary colony errands decide every call, passenger and destination.
 */
export async function buildCookeryElevator(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec,
) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  const deck = at(-2, -1, -6);
  const cabin = (x: number, y: number, z: number): Vec => ({ x: deck.x + x, y: deck.y + y, z: deck.z + z });
  const pulley = cabin(2, 15, 2);
  const contacts = [0, RISE].map(rise => cabin(5, 3 + rise, 2));
  const commands = [
    `fill ${commandPos(cabin(0, 0, 0))} ${commandPos(cabin(4, 0, 4))} minecraft:spruce_planks`,
    // Copper covers the rear and side frame. The front-centre, including both seats, stays open to the camera.
    `fill ${commandPos(cabin(0, 4, 3))} ${commandPos(cabin(4, 4, 4))} minecraft:cut_copper`,
    `fill ${commandPos(cabin(0, 4, 0))} ${commandPos(cabin(0, 4, 2))} minecraft:cut_copper`,
    `fill ${commandPos(cabin(4, 4, 0))} ${commandPos(cabin(4, 4, 2))} minecraft:cut_copper`,
    `setblock ${commandPos(cabin(1, 1, 2))} create:yellow_seat`,
    `setblock ${commandPos(cabin(3, 1, 2))} create:yellow_seat`,
    `setblock ${commandPos(cabin(2, 3, 4))} minecraft:lantern[hanging=true]`,
    `setblock ${commandPos(cabin(4, 1, 2))} minecraft:stripped_spruce_log[axis=y]`,
    `setblock ${commandPos(cabin(4, 2, 2))} minecraft:stripped_spruce_log[axis=y]`,
    `setblock ${commandPos(cabin(4, 3, 2))} create:redstone_contact[facing=east]`,
    `setblock ${commandPos(pulley)} create:elevator_pulley[facing=north]`,
    `setblock ${commandPos(cabin(1, 15, 2))} create:creative_motor[facing=east]`,
  ];
  for (const x of [0, 4]) for (const z of [0, 4]) {
    commands.push(`fill ${commandPos(cabin(x, 1, z))} ${commandPos(cabin(x, 3, z))} minecraft:stripped_spruce_log[axis=y]`);
  }
  for (const contact of contacts) commands.push(`setblock ${commandPos(contact)} create:elevator_contact[facing=west]`);
  for (const command of commands) await world.command(server, command);
  const glue = await runColonyTask(server, ASSEMBLE, { origin });
  await tick.sprint(server, 60);
  // Configure the initialized motor through Create's own scroll behaviour and kinetic propagation.
  const power = await runColonyTask(server, POWER, { origin });
  await tick.sprint(server, 20);
  const assembly = await runColonyTask(server, ASSEMBLE, { origin, click: true });
  await tick.sprint(server, 60);
  const delegated = await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: [pulley.x, pulley.y, pulley.z] });
  let state = await readCookeryElevator(server, colony_id, origin);
  for (let attempt = 0; attempt < 6 && (state.floors.length !== 2 || state.capacity !== 2 || !state.controller); attempt++) {
    await tick.sprint(server, 20);
    state = await readCookeryElevator(server, colony_id, origin);
  }
  const expectedFloors = contacts.map(contact => contact.y);
  // Create deliberately reports getSpeed()==0 while Minecraft ticks are frozen. Sprint restores
  // that freeze, so verify the real kinetic network's stored speed without advancing the scene.
  const powered = state.powered || state.frozen && state.pulleyTheoreticalSpeed !== 0 && !state.overstressed;
  if (state.floors.length !== 2 || state.capacity !== 2 || !powered || !state.controller
      || !expectedFloors.every(y => state.floors.some((floor: any) => floor.y === y))) {
    throw new Error(`Cookery elevator did not expose two powered, delegated landings and two seats: ${JSON.stringify(state)}`);
  }
  return {
    deck, pulley, motor: cabin(1, 15, 2), contacts, rise: RISE,
    feetHeights: [origin.y, origin.y + RISE],
    shaft: { min: deck, max: cabin(4, 14, 4) },
    glue, power, assembly, delegated, state,
  };
}

/** Read the actual Create contraption and seat mapping; dining-chair passengers never count as lift riders. */
export async function readCookeryElevator(server: MinecraftServer, colony_id: string, origin: Vec) {
  return runColonyTask(server, STATUS, { colony_id, origin });
}

const ASSEMBLE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-elevator-assemble", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["com.simibubi.create.content.contraptions.glue.SuperGlueEntity",
      "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity", "net.minecraft.core.BlockPos"],
    body: `
var args=Args.of(ctx);var o=args.requireObject("origin").raw();var level=ctx.server().overworld();
var deck=new BlockPos(((Number)o.get("x")).intValue()-2,((Number)o.get("y")).intValue()-1,((Number)o.get("z")).intValue()-6);
if(args.flag("click",false)){
 if(!(level.getBlockEntity(deck.offset(2,15,2)) instanceof ElevatorPulleyBlockEntity pulley))throw new IllegalStateException("Cookery elevator pulley is missing");
 pulley.clicked();
 return Sync.stamp(Map.of("clicked",true,"speed",pulley.getSpeed(),"assemblyError",String.valueOf(pulley.getLastAssemblyException())));
}
if(!level.addFreshEntity(new SuperGlueEntity(level,SuperGlueEntity.span(deck,deck.offset(4,4,4)))))throw new IllegalStateException("Cookery elevator glue could not be installed");
return Sync.stamp(Map.of("glued",true));`,
  }),
});

const POWER = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-elevator-power", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity",
      "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity", "net.minecraft.core.BlockPos"],
    body: `
var o=Args.of(ctx).requireObject("origin").raw();var level=ctx.server().overworld();
var pulleyPos=new BlockPos(((Number)o.get("x")).intValue(),((Number)o.get("y")).intValue()+14,((Number)o.get("z")).intValue()-4);
if(!(level.getBlockEntity(pulleyPos.west()) instanceof CreativeMotorBlockEntity motor)||motor.generatedSpeed==null)
 throw new IllegalStateException("Cookery elevator motor has not initialized");
motor.generatedSpeed.setValue(64);
motor.updateGeneratedRotation();
var pulley=(ElevatorPulleyBlockEntity)level.getBlockEntity(pulleyPos);
return Sync.stamp(Map.of("motor",motor.getBlockState().toString(),"generatedSpeed",motor.getGeneratedSpeed(),
 "motorSpeed",motor.getSpeed(),"pulleySpeed",pulley.getSpeed(),"overstressed",pulley.isOverStressed(),
 "motorTheoreticalSpeed",motor.getTheoreticalSpeed(),"pulleyTheoreticalSpeed",pulley.getTheoreticalSpeed(),
 "frozen",level.tickRateManager().isFrozen()));`,
  }),
});

const STATUS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.cookery-elevator-state", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.create.elevator.CreateElevatorNetwork",
      "io.github.izakyl.folkways.create.elevator.ElevatorRef", "io.github.izakyl.folkways.create.elevator.ElevatorDispatch",
      "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity", "net.minecraft.core.BlockPos"],
    body: `
var args=Args.of(ctx);var o=args.requireObject("origin").raw();var level=ctx.server().overworld();
var pos=new BlockPos(((Number)o.get("x")).intValue(),((Number)o.get("y")).intValue()+14,((Number)o.get("z")).intValue()-4);
var ref=new ElevatorRef(level.dimension(),pos);var network=new CreateElevatorNetwork();var found=network.inspect(ctx.server(),ref);
var out=new LinkedHashMap<String,Object>();out.put("pulley",point(pos));out.put("frozen",level.tickRateManager().isFrozen());
if(level.getBlockEntity(pos) instanceof ElevatorPulleyBlockEntity pulley){
 out.put("speed",pulley.getSpeed());out.put("overstressed",pulley.isOverStressed());out.put("pulleyTheoreticalSpeed",pulley.getTheoreticalSpeed());
 out.put("pulleyState",pulley.getBlockState().toString());
}
if(level.getBlockEntity(pos.west()) instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor){
 out.put("motorSpeed",motor.getSpeed());out.put("motorTheoreticalSpeed",motor.getTheoreticalSpeed());out.put("generatedSpeed",motor.getGeneratedSpeed());out.put("motorState",motor.getBlockState().toString());
}
if(found.isEmpty()){
 out.put("error","no assembled elevator");out.put("powered",false);out.put("controller",false);
 if(level.getBlockEntity(pos) instanceof ElevatorPulleyBlockEntity pulley){
  out.put("speed",pulley.getSpeed());out.put("assemblyError",String.valueOf(pulley.getLastAssemblyException()));
 }
 out.put("floors",List.of());out.put("capacity",0);out.put("passengers",List.of());out.put("seatOccupants",List.of());out.put("people",List.of());out.put("contactY",0);
 return Sync.stamp(out);
}
var v=found.get();var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var controller=ElevatorDispatch.controller(ctx.server(),colony.id(),ref);
out.put("vehicle",v.vehicle().toString());out.put("powered",v.powered());out.put("contactY",v.contactY());
out.put("stopped",v.stoppedAt().orElse(Integer.MIN_VALUE));out.put("capacity",v.seats().size());out.put("free",v.freeSeats().size());out.put("controller",controller.isPresent());
out.put("floors",v.floors().stream().map(floor->Map.of("y",floor.y(),"cells",floor.platform().cells().stream().map(cell->point(cell.cell())).toList())).toList());
var occupied=new LinkedHashMap<UUID,Integer>();var seatOccupants=new ArrayList<Object>();
for(int seat:v.seats()){
 var occupant=network.occupant(ctx.server(),ref,seat);
 if(occupant.isPresent()){occupied.put(occupant.get(),seat);seatOccupants.add(Map.of("seat",seat,"uuid",occupant.get().toString()));}
}
out.put("seatOccupants",seatOccupants);
var passengers=new ArrayList<Object>();var people=new ArrayList<Object>();
for(var resident:colony.residents()){
 var entity=level.getEntity(resident.id());if(entity==null)continue;
 boolean sameVehicle=entity.getVehicle()!=null&&entity.getVehicle().getUUID().equals(v.vehicle());
 boolean riding=sameVehicle&&occupied.containsKey(entity.getUUID());
 var one=new LinkedHashMap<String,Object>();one.put("uuid",entity.getUUID().toString());
 one.put("x",entity.getX());one.put("y",entity.getY());one.put("z",entity.getZ());
 one.put("inLift",riding);one.put("seated",entity.isPassenger());one.put("seat",riding?occupied.get(entity.getUUID()):-1);
 one.put("vehicle",entity.getVehicle()==null?"none":entity.getVehicle().getUUID().toString());
 one.put("doing",Bodies.of(entity).flatMap(body->body.doing()).map(Object::toString).orElse("idle"));
 one.put("request",controller.map(control->control.status(entity.getUUID()).toString()).orElse("unclaimed"));
 people.add(one);if(riding)passengers.add(one);
}
out.put("people",people);out.put("passengers",passengers);
return Sync.stamp(out);`,
    members: `static Map<String,Integer> point(BlockPos p){return Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ());}`,
  }),
});

import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { frames, safeAction, commandPos } from "../shared/harness/bw-helpers";
import { FILM_LOOK, shaderEvidence } from "../shared/capture/shaders";
import { buildElevatorVillage } from "./elevator-scenery";
import { prepareElevatorModels, WINE_FOX_MODELS, applyElevatorLooks } from "./elevator-cast";
import { stageElevatorWork, readElevatorWork, readElevatorConstruction, refreshElevatorWorkshop } from "./elevator-work";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, setFov, sleepMs, take, writeLook, type PromoPair,
} from "./promo-kit";

const RESIDENTS = 14;

function working(person: any) {
  return person.doing !== "idle" && !person.doing.includes("what=folkways:waiting,");
}

// The staged goods and standing orders produce real colony errands. Nothing on camera calls the pulley,
// assigns a seat, or moves a passenger: the planner and each lift's controller do that themselves.
test("residents ride the elevator between the colony's sunlit terraces", async ({}, testInfo) => {
  const run = take("elevator");
  let pair: PromoPair | undefined;
  try {
    prepareElevatorModels();
    pair = await launchPromoPair("elevator", { look: { options: FILM_LOOK }, models: WINE_FOX_MODELS });
    const { server, client, clientInstance } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    await world.command(server, `op ${player}`);
    await cinematicWorld(server);
    await world.command(server, "time set 8500");
    const origin: Vec = { x: Math.floor(grounded.pos.x) + 5, y: Math.floor(grounded.pos.y) - 1, z: Math.floor(grounded.pos.z) + 5 };
    const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
    const founded = await foundColonyFast(server, client, player, {
      origin: at(-24, 1, 28), residentCount: RESIDENTS,
    });
    const colony_id = founded.evidence.colonyId;
    run.note("colony", { colony_id, origin, residents: founded.residents });
    // The founding helper fences its temporary admission yard; remove that staging wall from the background.
    await world.tryCommand(server, `fill ${commandPos(at(-44, 1, 8))} ${commandPos(at(-4, 7, 48))} minecraft:air replace minecraft:smooth_stone`);
    const scenery = await buildElevatorVillage(server, origin);
    run.note("scenery", scenery);
    await cinematicWorld(server);
    await world.command(server, "time set 8500");
    // Open-front timber cage, two ochre seats and a copper canopy, all glued into one real contraption.
    const commands: string[] = [
      `fill ${commandPos(at(0, 0, 0))} ${commandPos(at(4, 0, 4))} minecraft:spruce_planks`,
      `fill ${commandPos(at(0, 4, 0))} ${commandPos(at(4, 4, 4))} minecraft:cut_copper`,
      `setblock ${commandPos(at(1, 1, 2))} create:yellow_seat`,
      `setblock ${commandPos(at(3, 1, 2))} create:yellow_seat`,
      `setblock ${commandPos(at(2, 3, 3))} minecraft:lantern[hanging=true]`,
      `setblock ${commandPos(at(4, 1, 2))} minecraft:stripped_spruce_log[axis=y]`,
      `setblock ${commandPos(at(4, 2, 2))} minecraft:stripped_spruce_log[axis=y]`,
      `setblock ${commandPos(at(4, 3, 2))} create:redstone_contact[facing=east]`,
      `setblock ${commandPos(at(2, 27, 2))} create:elevator_pulley[facing=north]`,
      `setblock ${commandPos(at(1, 27, 2))} create:creative_motor[facing=east]{ScrollValue:64}`,
    ];
    for (const x of [0, 4]) for (const z of [0, 4]) {
      commands.push(`fill ${commandPos(at(x, 1, z))} ${commandPos(at(x, 3, z))} minecraft:stripped_spruce_log[axis=y]`);
    }
    for (const y of [3, 13, 23]) commands.push(`setblock ${commandPos(at(5, y, 2))} create:elevator_contact[facing=west]`);
    for (const command of commands) await world.command(server, command);
    run.note("glue", await runColonyTask(server, ASSEMBLE, { origin }));
    await tick.sprint(server, 60);
    run.note("assembly", await runColonyTask(server, ASSEMBLE, { origin, click: true }));
    await tick.sprint(server, 60);
    const assembly = await runColonyTask(server, STATUS, { origin, colony_id });
    run.note("elevator", assembly);
    expect(assembly.floors.length, JSON.stringify(assembly)).toBe(3);
    expect(assembly.capacity).toBe(2);

    run.note("delegated", await runColonyTask(server, MARK_MEMBERS, {
      player, colony_id, cells: [at(2, 27, 2)].flatMap(p => [p.x, p.y, p.z]),
    }));
    const stagedCrew = await runColonyTask(server, STAGE_RESIDENTS, { colony_id, origin });
    const work = await stageElevatorWork(server, player, colony_id, origin, stagedCrew.residents);
    const crew = { residents: work.roster };
    run.note("crew", crew);
    run.note("work", work);
    await world.command(server, `tp ${player} ${commandPos(at(0, 12, -12))}`);
    run.note("cast", await applyElevatorLooks(server, client, crew.residents.map((resident: any) => resident.uuid)));
    await world.command(server, `gamemode spectator ${player}`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    await world.command(server, `item replace entity ${player} weapon.offhand with minecraft:air`);
    const eye = at(9, 18, -46);
    const aim = at(-1, 13, -1);
    const look = cameraLookingAt(eye, aim);
    await world.command(server, `tp ${player} ${eye.x} ${eye.y - 1.62} ${eye.z} ${look.yaw} ${look.pitch}`);
    await safeAction(() => screen.dismiss(client));
    await hideHud(client);
    await setFov(client, 53);
    await writeLook(client, look);
    await frames(client, 50);
    run.note("shaders", shaderEvidence(clientInstance));
    // Film from opening work: the building, crop and timber tasks must not be exhausted in rehearsal.
    const constructionBefore = await readElevatorConstruction(server, colony_id, origin);
    run.note("constructionBeforeFilm", constructionBefore);
    run.note("workBeforeFilm", await readElevatorWork(server, colony_id, origin));
    await tick.freeze(server, true);
    await run.shot(client, "00-terraces", {
      subject: "A working colony's timber elevator joins three copper-roofed terraces in warm afternoon light",
      worldState: { origin, eye, aim },
    });
    console.log(`ELEVATOR_PREVIEW ${run.dir}/00-terraces.png`);
    await sleepMs(Number(process.env.FOLKWAYS_ELEVATOR_REVIEW_PAUSE_MS ?? 0));
    const filmed: any[] = [];
    const shots = [
      { name: "01-elevator", seconds: 12, eye, aim, fov: 53, subject: "The working colony and its elevator" },
      { name: "03-fields-and-timber", seconds: 12, eye: at(-13, 11, -31), aim: at(-20, 3, -14), fov: 56, subject: "Foresters fell and replant beside the working crop gardens" },
      { name: "04-upper-construction", seconds: 11, eye: at(16, 27, -18), aim: at(6, 22, 1), fov: 52, subject: "Builders extend the upper canopy with materials brought by elevator" },
      { name: "05-pasture-and-pond", seconds: 10, eye: at(31, 9, -29), aim: at(15, 2, -10), fov: 60, subject: "Sheep care and rod fishing beside the colony" },
    ];
    const gates: any[] = [];
    const kitchenTrail: any[] = [];
    run.note("kitchenBeforeFilm", await refreshElevatorWorkshop(server, player, colony_id));
    let elapsed = 0;
    let refreshedAt = 0;
    for (const shot of shots) {
      const shotLook = cameraLookingAt(shot.eye, shot.aim);
      await world.command(server, `tp ${player} ${shot.eye.x} ${shot.eye.y - 1.62} ${shot.eye.z} ${shotLook.yaw} ${shotLook.pitch}`);
      await setFov(client, shot.fov);
      await writeLook(client, shotLook);
      await frames(client, 30);
      gates.push(await promoClip(run, client, server, shot.name, {
        seconds: shot.seconds, subject: shot.subject,
        worldState: { origin, eye: shot.eye, aim: shot.aim, colony_id, crew: crew.residents },
        during: async () => {
          const started = Date.now();
          while (Date.now() - started < shot.seconds * 1000) {
            const state = await runColonyTask(server, STATUS, { origin, colony_id });
            filmed.push({ second: elapsed + (Date.now() - started) / 1000, shot: shot.name, ...state });
            if (Date.now() - refreshedAt > 3000) {
              const kitchen = { second: elapsed + (Date.now() - started) / 1000, ...await refreshElevatorWorkshop(server, player, colony_id) };
              kitchenTrail.push(kitchen);
              run.note("kitchenProgress", kitchen);
              refreshedAt = Date.now();
            }
            await sleepMs(400);
          }
        },
      }));
      elapsed += shot.seconds;
    }
    run.note("segments", shots.map(shot => ({ name: shot.name, seconds: shot.seconds })));
    const constructionAfter = await readElevatorConstruction(server, colony_id, origin);
    run.note("constructionAfterFilm", constructionAfter);
    run.note("kitchen", kitchenTrail);
    run.note("journeys", filmed);
    expect.soft(constructionAfter.copper + constructionAfter.timber, "residents must visibly build the upper canopy").toBeGreaterThan(constructionBefore.copper + constructionBefore.timber + 4);
    run.note("workAfterFilm", await readElevatorWork(server, colony_id, origin));
    run.note("shot", gates);
    const activity = crew.residents.map((resident: any) => {
      const samples = filmed.map(state => state.people.find((person: any) => person.uuid === resident.uuid));
      return { uuid: resident.uuid, active: samples.filter(working).length, idle: samples.filter(person => person.doing === "idle").length };
    });
    run.note("activity", activity);
    for (const person of activity) {
      expect.soft(person.active, `resident ${person.uuid} needs sustained work in the film`).toBeGreaterThan(4);
      const role = crew.residents.find(one => one.uuid === person.uuid)?.role;
      // A cook waits for food to cook; evaluate their completed meals as well as their own gestures.
      if (role !== "cook") expect.soft(person.idle / filmed.length, `resident ${person.uuid} must not spend the film idle`).toBeLessThan(0.35);
    }
    expect(kitchenTrail.some(one => one.count > 0 || one.kitchen?.slots?.some((slot: string) => /^\d+ minecraft:dried_kelp$/.test(slot) && Number(slot.split(" ")[0]) > 0)), "the kitchen must produce meals, including finished food still in the smoker").toBeTruthy();
    const occupied = filmed.filter(state => state.passengers.length > 0);
    expect(occupied.length, "the film must contain actual seated residents").toBeGreaterThan(4);
    const heights = occupied.map(state => Number(state.contactY));
    expect(Math.max(...heights) - Math.min(...heights), "passengers must visibly move between floors").toBeGreaterThan(5);
    // Inspect a genuine passenger after the uninterrupted take, close enough to see bent knees.
    await tick.freeze(server, false);
    let rider: any;
    const poseDeadline = Date.now() + 45_000;
    while (Date.now() < poseDeadline) {
      const state = await runColonyTask(server, STATUS, { origin, colony_id });
      if (state.passengers.length) { rider = state.passengers[0]; break; }
      await sleepMs(350);
    }
    expect(rider, "a real passenger must be available for the seated-pose inspection").toBeTruthy();
    await tick.freeze(server, true);
    const closeEye = { x: rider.x + 2, y: rider.y + 1.8, z: rider.z - 7 };
    const closeAim = { x: rider.x, y: rider.y + 0.7, z: rider.z };
    const closeLook = cameraLookingAt(closeEye, closeAim);
    await world.command(server, `tp ${player} ${closeEye.x} ${closeEye.y - 1.62} ${closeEye.z} ${closeLook.yaw} ${closeLook.pitch}`);
    await setFov(client, 48);
    await writeLook(client, closeLook);
    await frames(client, 50);
    run.note("seatedPose", await client.run(SEATED_POSE, { uuid: rider.uuid }));
    await run.shot(client, "02-seated-pose", { subject: "A Wine Fox passenger uses the model's authored chair-riding pose", worldState: { rider, closeEye, closeAim } });
    run.expectTaken(["00-terraces", "01-elevator", "03-fields-and-timber", "04-upper-construction", "05-pasture-and-pond", "02-seated-pose"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const ASSEMBLE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-assemble", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["com.simibubi.create.content.contraptions.glue.SuperGlueEntity",
      "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity", "net.minecraft.core.BlockPos"],
    body: `
var args=Args.of(ctx);var p=args.requireObject("origin").raw();
var deck=new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
var level=ctx.server().overworld();
if(args.flag("click",false)){
 var pulley=(ElevatorPulleyBlockEntity)level.getBlockEntity(deck.offset(2,27,2));pulley.clicked();
 return Sync.stamp(Map.of("clicked",true,"speed",pulley.getSpeed()));
}
level.addFreshEntity(new SuperGlueEntity(level,SuperGlueEntity.span(deck,deck.offset(4,4,4))));
return Sync.stamp(Map.of("glued",true));`,
  }),
});

const STATUS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-state", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.create.elevator.CreateElevatorNetwork", "io.github.izakyl.folkways.create.elevator.ElevatorRef",
      "io.github.izakyl.folkways.create.elevator.ElevatorDispatch", "io.github.izakyl.folkways.core.api.colony.Colonies",
      "io.github.izakyl.folkways.core.api.resident.body.Bodies", "net.minecraft.core.BlockPos",
      "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity"],
    body: `
var args=Args.of(ctx);var p=args.requireObject("origin").raw();var level=ctx.server().overworld();
var pos=new BlockPos(((Number)p.get("x")).intValue()+2,((Number)p.get("y")).intValue()+27,((Number)p.get("z")).intValue()+2);
var ref=new ElevatorRef(level.dimension(),pos);var view=new CreateElevatorNetwork().inspect(ctx.server(),ref);
var out=new LinkedHashMap<String,Object>();
if(view.isEmpty()){
 var pulley=(ElevatorPulleyBlockEntity)level.getBlockEntity(pos);
 out.put("error","no elevator");out.put("speed",pulley.getSpeed());out.put("assembly",String.valueOf(pulley.getLastAssemblyException()));
 out.put("floors",List.of());out.put("capacity",0);out.put("passengers",List.of());out.put("contactY",0);return Sync.stamp(out);
}
var v=view.get();out.put("contactY",v.contactY());out.put("stopped",v.stoppedAt().orElse(Integer.MIN_VALUE));
out.put("capacity",v.seats().size());out.put("free",v.freeSeats().size());
out.put("floors",v.floors().stream().map(f->Map.of("y",f.y(),"cells",f.platform().cells().stream().map(Object::toString).toList())).toList());
var passengers=new ArrayList<Object>();var people=new ArrayList<Object>();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var controller=ElevatorDispatch.controller(ctx.server(),colony.id(),ref);
for(var resident:colony.residents()){
 var entity=level.getEntity(resident.id());if(entity==null)continue;
 var entry=new LinkedHashMap<String,Object>();entry.put("uuid",entity.getUUID().toString());entry.put("y",entity.getY());
 entry.put("x",entity.getX());entry.put("z",entity.getZ());entry.put("seated",entity.isPassenger());
 entry.put("doing",Bodies.of(entity).flatMap(b->b.doing()).map(Object::toString).orElse("idle"));
 entry.put("request",controller.map(c->c.status(entity.getUUID()).toString()).orElse("unclaimed"));people.add(entry);
 if(entity.isPassenger())passengers.add(entry);
}
out.put("people",people);out.put("passengers",passengers);return Sync.stamp(out);`,
  }),
});

const STAGE_RESIDENTS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.elevator-crew", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.resident.body.Bodies",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent", "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value"],
    body: `
var args=Args.of(ctx);var p=args.requireObject("origin").raw();var level=ctx.server().overworld();
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
ColonyFront.of(colony).setSetting(LivingContent.ID,LivingContent.FOOD.key(),new Value.Items(List.of()));
int ox=((Number)p.get("x")).intValue(),oy=((Number)p.get("y")).intValue(),oz=((Number)p.get("z")).intValue();
for(var pos:net.minecraft.core.BlockPos.betweenClosed(new net.minecraft.core.BlockPos(ox-46,oy+1,oz+6),new net.minecraft.core.BlockPos(ox-2,oy+7,oz+50)))
 if(level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE))level.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
var residents=new ArrayList<Object>();int index=0;
for(var resident:colony.residents()){
 var entity=level.getEntity(resident.id());if(entity==null)continue;
 int floor=index%3;entity.teleportTo(((Number)p.get("x")).doubleValue()-7.5,
  ((Number)p.get("y")).doubleValue()+floor*10+1,((Number)p.get("z")).doubleValue()-3.5);
 residents.add(Map.of("uuid",entity.getUUID().toString(),"floor",floor));index++;
}
return Sync.stamp(Map.of("residents",residents));`,
  }),
});

const SEATED_POSE = defineTask<{ uuid: string }, Record<string, any>>({
  name: "folkways.promo.elevator-client-seated-pose", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks", "io.github.izakyl.folkways.plugins.person.look.ResidentLook"],
    body: `
var wanted=UUID.fromString(Args.of(ctx).string("uuid"));
var level=Minecraft.getInstance().level;
for(var entity:level.entitiesForRendering())if(entity instanceof ResidentEntity resident&&resident.getUUID().equals(wanted)){
 if(!resident.isPassenger()||resident.getVehicle()==null||!resident.getVehicle().shouldRiderSit())throw new IllegalStateException("Passenger is not sitting on the client");
 if(!(ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model model))throw new IllegalStateException("Passenger has no custom model");
 return Map.of("uuid",wanted.toString(),"model",model.geometry().toString(),"seatedAnimation",model.seatedAnimation().orElseThrow(),"vehicle",resident.getVehicle().getType().toString());
}
throw new IllegalStateException("Passenger missing from client");`,
  }),
});

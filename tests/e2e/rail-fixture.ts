import { buildTrackLoop, type LoopBounds } from "./rail-loop";
import { commandPos, tryCommand } from "./colony-founding";
import { drainActionBar, readActionBar } from "./zone-receipt";
import { uuidToIntArray } from "./colony-nbt";
import { runColonyTask } from "./colony-tasks";
import {
  MinecraftTimeoutError,
  defineTask,
  javaTask,
  input,
  player,
  reflect,
  screen,
  tick,
  world,
  type MinecraftClient,
  type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { reflectVec3 } from "../shared/bw-helpers";
import { carEndOf, offsetBox, platformOf, railSite, trackEnd, type Box } from "./rail-footprint";
import { clearRailSite, releaseRailSite, type SiteClearing } from "./rail-site";

export type { Box } from "./rail-footprint";
export type Vec = { x: number; y: number; z: number };

/** The error of a line whose train Create assembled (the advancement fires, the car's blocks are taken up) with no
 * carriage entity to show for it. It is rare and has not been caught in the act, so a caller may build again. */
export const LOST_CARRIAGE = "no carriage_contraption entity after assemble";

export type RailResult = {
  ok: boolean;
  stationA: Vec;
  stationB: Vec;
  farPlatform: Vec;
  conductorSeat: Vec;
  carriageUuid?: string;
  assembled?: boolean;
  scheduled?: boolean;
  handedOver?: boolean;
  conductorSeatWorld?: Vec;
  stations?: Array<{ name: string; position: Vec }>;
  passengerSeats?: number;
  loopBounds?: LoopBounds;
  steps: any;
  error?: string;
};

const STATION_A = "Alpha";
const STATION_B = "Beta";

export async function buildRailLine(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  playerUuid: string,
  railOrigin: Vec,
  lineLength: number,
  bookSlot: string,
  options: {
    decorate?: (server: MinecraftServer, origin: Vec) => Promise<void>;
    decorateBounds?: Box;
    anchor?: Vec;
    cyclic?: boolean;
    loop?: LoopBounds;
    passengerSeats?: number;
    dwell?: Partial<Record<string, number>>;
    /** Hand the train over by putting the click straight to the carriage, for a car whose doors shut the seat off
     * from the platform. */
    clickThrough?: boolean;
  } = {},
): Promise<RailResult> {
  const steps: any = {};
  const x = railOrigin.x;
  const gy = railOrigin.y;
  const z0 = railOrigin.z;
  const track = (z: number): Vec => ({ x, y: gy, z });
  const passengerCount = options.passengerSeats ?? 1;
  const carEnd = carEndOf(passengerCount);
  // A line that runs back and forth faces Alpha out past the car's far end: the double-ended train backs out of it to
  // Beta and drives forward back into it, so each stop names one station, as a colony timetable needs.
  const turnback = Boolean(options.cyclic && !options.loop);
  const stationA: Vec = { x: x + 1, y: gy, z: turnback ? z0 + carEnd + 1 : z0 + 2 };
  const stationB: Vec = { x: x + 1, y: gy, z: z0 - lineLength };
  const stations = [{ name: STATION_A, position: stationA, reverse: turnback },
    { name: STATION_B, position: stationB, reverse: false },
    ...(options.loop ? [
      { name: "Gamma", position: { x: options.loop.minX + 1, y: gy, z: z0 - lineLength + 30 }, reverse: true },
      { name: "Delta", position: { x: options.loop.minX + 1, y: gy, z: z0 + 30 }, reverse: true },
    ] : [])];
  const car = { xMin: x - 2, xMax: x + 2, zMin: z0 + 3, zMax: z0 + carEnd, floorY: gy + 1, blockY: gy + 2, roofY: gy + 4 };
  const bogey: Vec = { x, y: car.floorY, z: z0 + 3 };
  const casing: Vec = { x, y: car.blockY, z: z0 + 3 };
  const controls: Vec = { x, y: car.blockY, z: z0 + 6 };
  const conductorSeat: Vec = { x, y: car.blockY, z: z0 + 7 };
  const passengerSeat: Vec = { x: x - 1, y: car.blockY, z: z0 + 4 };
  const schedulerStand = { x: x + 3.5, y: car.blockY, z: conductorSeat.z + 0.5 };
  const farPlatform: Vec = { x: x + 3, y: gy, z: stationB.z + 2 };
  const site = railSite({
    origin: railOrigin, lineLength, passengerCount, stations, loop: options.loop,
    carTop: car.roofY + (options.decorate ? 2 : 0),
    decoration: options.decorate && options.decorateBounds ? offsetBox(railOrigin, options.decorateBounds) : undefined,
  });
  let clearing: SiteClearing | undefined;
  let released = false;
  const release = async () => {
    if (!clearing || released) return undefined;
    released = true;
    return await releaseRailSite(server, site, clearing);
  };

  const result: RailResult = { ok: false, stationA, stationB, farPlatform, conductorSeat, steps, stations, passengerSeats: passengerCount, loopBounds: options.loop };

  try {
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    await tryCommand(server, `gamemode creative ${playerName}`);
    await tryCommand(server, `op ${playerName}`);
    await tryCommand(server, `forceload add ${x - 8} ${z0 - lineLength - 10} ${x + 10} ${z0 + 10}`);
    const farEnd = track(z0 - lineLength - 8);
    const pregen = await world.waitFor(server, `if loaded ${commandPos(farEnd)}`, { maxTicks: 3000, stepTicks: 10 })
      .then((held) => ({ matched: true, ticks: held.ticks, reason: "matched" }))
      .catch((error: unknown) => {
        if (!(error instanceof MinecraftTimeoutError)) throw error;
        return { matched: false, ticks: error.ticks, reason: (error.detail as { reason?: string } | undefined)?.reason ?? "maxTicks" };
      });
    steps.chunks_pregen = { loaded: pregen.matched, ticks: pregen.ticks, reason: pregen.reason };

    const anchor = options.anchor ?? await playerCell(server, playerName);
    if (!anchor) {
      result.error = `could not read where ${playerName} stands, so there is no colony side to move residents to`;
      return result;
    }
    clearing = await clearRailSite(server, site, anchor);
    steps.site = { ...clearing, held: clearing.held.length };
    if (!clearing.ok) {
      result.error = `rail site at ${commandPos(railOrigin)} is unsuitable: ${clearing.error} ${JSON.stringify(clearing.obstructions ?? [])}`;
      return result;
    }

    if (options.loop) steps.loop = await buildTrackLoop(server, options.loop, gy);
    else await tryCommand(server, `fill ${commandPos(track(z0 - lineLength - 8))} ${commandPos(track(trackEnd(railOrigin, carEnd, stations)))} create:track[shape=zo,turn=false,waterlogged=false]`);
    await tick.sprint(server, 20);
    steps.track = (await world.block(server, track(z0 + 3)))?.id;

    const placed = stations.map((stop) => ({ ...stop, suffix: "" }));
    for (const { name, position: station, reverse, suffix } of placed) {
      const target = { ...station, x: station.x - 1 };
      await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with create:track_station`);
      await tryCommand(server, `tp ${playerName} ${station.x + 1.5} ${gy} ${station.z + 0.5} ${reverse ? 0 : 180} 0`);
      await tick.sprint(server, 10);
      await player.useBlock(client, target, { face: "up" }).catch(() => null);
      await tick.sprint(server, 5);
      await player.useBlock(client, { ...station, y: station.y - 1 }, { face: "up" }).catch(() => null);
      await tick.sprint(server, 10);
      steps[`station_${name}${suffix}`] = (await world.block(server, station))?.id;
    }
    await tryCommand(server, `clear ${playerName} create:track_station`);
    await tick.sprint(server, 20);

    for (const { min, max } of site.platforms) {
      await tryCommand(server, `fill ${min.x} ${gy} ${min.z} ${min.x} ${gy + 1} ${max.z} minecraft:stone_bricks`);
      await tryCommand(server, `fill ${max.x} ${gy} ${min.z} ${max.x} ${gy} ${max.z} minecraft:stone_bricks`);
    }
    await tick.sprint(server, 10);

    for (const { position: station, name, suffix } of placed) {
      const renamed: any = await reflect
        .setField(server, { target: { kind: "blockentity", position: station }, path: "edgePoint.getEdgePoint()", field: "name", value: name })
        .catch((e: any) => ({ error: String(e) }));
      steps[`rename_${name}${suffix}`] = { found: renamed?.found, ownerType: renamed?.ownerType, error: renamed?.error };
    }

    await tryCommand(server, `fill ${x - 2} ${car.floorY} ${car.zMin} ${x + 2} ${car.floorY} ${car.zMax} minecraft:oak_planks`);
    await tryCommand(server, `setblock ${commandPos(bogey)} create:small_bogey[axis=z]`);
    await tryCommand(server, `setblock ${commandPos(casing)} create:railway_casing`);
    await tryCommand(server, `setblock ${commandPos(controls)} create:controls[facing=south]`);
    await tryCommand(server, `setblock ${commandPos(conductorSeat)} create:red_seat`);
    for (let i = 0; i < passengerCount; i++) {
      const seat = passengerCount === 1 ? passengerSeat : { x: x + (i % 2 === 0 ? -1 : 1), y: car.blockY, z: z0 + 9 + Math.floor(i / 2) };
      await tryCommand(server, `setblock ${commandPos(seat)} create:red_seat`);
    }
    await tick.sprint(server, 5);
    if (options.decorate) await options.decorate(server, railOrigin);
    if (turnback) {
      await tryCommand(server, `setblock ${x} ${car.blockY} ${conductorSeat.z + 1} create:controls[facing=north]`);
      // Create assembles from the bogey at the station, and a turnback line's Alpha stands past the car's far end.
      await tryCommand(server, `setblock ${x} ${car.floorY} ${car.zMax} create:small_bogey[axis=z]`);
    }
    steps.glue = (await tryCommand(server, `create glue ${x - 2} ${car.floorY} ${car.zMin} ${x + 2} ${car.roofY + (options.decorate ? 2 : 0)} ${car.zMax}`))?.success;
    await tick.sprint(server, 20);
    steps.placed = {
      controls: (await world.block(server, controls))?.id,
      conductorSeat: (await world.block(server, conductorSeat))?.id,
    };

    await reflect.invoke(server, { target: { kind: "blockentity", position: stationA }, method: "tryEnterAssemblyMode", returnType: "boolean" }).catch(() => null);
    await tick.sprint(server, 20);
    await reflect.invoke(server, { target: { kind: "blockentity", position: stationA }, method: "refreshAssemblyInfo", returnType: "void" }).catch(() => null);
    const assembled: any = await reflect
      .invoke(server, { target: { kind: "blockentity", position: stationA }, method: "assemble", args: [playerUuid], argTypes: ["java.util.UUID"], returnType: "void" })
      .catch((e: any) => ({ error: String(e) }));
    await reflect.invoke(server, { target: { kind: "blockentity", position: stationA }, method: "exitAssemblyMode", returnType: "boolean" }).catch(() => null);
    await tick.sprint(server, 40);
    steps.assemble = { found: assembled?.found, signature: assembled?.signature, error: assembled?.error };
    const after = await release();
    steps.site_after = after;

    const carriages = (await world
      .entities(server, { typePatterns: ["*carriage_contraption*"] })
      .catch(() => ({ entities: [] })))?.entities ?? [];
    steps.carriage_count = carriages.length;
    if (!carriages.length) {
      result.error = LOST_CARRIAGE;
      return result;
    }
    const carriageUuid = carriages[0].uuid;
    result.carriageUuid = carriageUuid;
    result.assembled = true;

    const conductorSeats = await world
      .entity(server, { uuid: carriageUuid }, { nbtPath: "Contraption.ConductorSeats" })
      .catch(() => null);
    steps.conductor_seats = JSON.stringify(conductorSeats?.nbt ?? null).slice(0, 200);

    const dwell = (s: number) => `Conditions:[[{Id:"create:delay",Data:{Value:${s},TimeUnit:1}}]]`;
    const entries = stations.map(({ name }) => `{Instruction:{Id:"create:destination",Data:{Text:"${name}"}},${dwell(options.dwell?.[name] ?? (options.loop || name === STATION_A ? 20 : 10))}}`);
    const scheduleTag = `Entries:[${entries.join(",")}],Cyclic:${options.cyclic ? 1 : 0}b,Progress:0`;
    steps.give_schedule = (await tryCommand(server, `give ${playerName} create:schedule[create:train_schedule={${scheduleTag}}] 1`))?.success;
    steps.book_offhand = (await tryCommand(
      server, `item replace entity ${playerName} weapon.offhand from entity ${playerName} ${bookSlot}`))?.success;

    const handedOver = await handOverAtConductorSeat(server, client, playerName, carriageUuid, schedulerStand, {
      clickThrough: options.clickThrough,
    });
    result.conductorSeatWorld = handedOver.seat;
    result.scheduled = handedOver.scheduled;
    result.handedOver = handedOver.roster;
    steps.hand_over = handedOver.attempts;
    result.ok = Boolean(result.assembled && result.scheduled && result.handedOver && after?.ok);
    if (after && !after.ok) result.error = `building the train left someone stuck: ${JSON.stringify(after.stuck)}`;
    return result;
  } catch (error) {
    result.error = String(error).slice(0, 400);
    return result;
  } finally {
    const late = await release().catch((e: any) => ({ error: String(e) }));
    if (late) steps.site_after = late;
  }
}

async function playerCell(server: MinecraftServer, playerName: string): Promise<Vec | undefined> {
  const at = (await world.players(server)).find((p) => p.name === playerName)?.position;
  return at ? { x: Math.floor(at.x), y: Math.floor(at.y + 0.01), z: Math.floor(at.z) } : undefined;
}

export const TAKEN_ON = "The colony has taken this train on";

export const GIVEN_BACK = "The train is no longer this colony's";

export const NO_BOOK = "Hold this colony's book in your other hand";

export type HandOver = {
  seat?: Vec;
  scheduled: boolean;
  roster: boolean;
  attempts: any[];
};

export async function handOverAtConductorSeat(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  carriageUuid: string,
  stand: { x: number; y: number; z: number },
  how: { clickThrough?: boolean } = {},
): Promise<HandOver> {
  const out: HandOver = { scheduled: false, roster: false, attempts: [] };
  for (let attempt = 0; attempt < 3 && !out.roster; attempt++) {
    const click = await clickConductorSeat(server, client, playerName, carriageUuid, stand, how);
    out.seat = click.seat;
    if (!click.seat) {
      return out;
    }
    out.scheduled = await trainHasSchedule(server, carriageUuid);
    out.roster = out.roster || click.actionBar.text.includes(TAKEN_ON);
    out.attempts.push({ attempt, action_bar: click.actionBar, scheduled: out.scheduled, roster: out.roster });
  }
  return out;
}

export async function clickConductorSeat(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  carriageUuid: string,
  stand: { x: number; y: number; z: number },
  how: { clickThrough?: boolean } = {},
): Promise<{ seat?: Vec; actionBar: { text: string; remaining_ticks: number } }> {
  await screen.dismiss(client).catch(() => null);
  const seat = await conductorSeatWorldPos(server, carriageUuid);
  if (!seat || !Number.isFinite(seat.x)) {
    return { seat: undefined, actionBar: { text: "", remaining_ticks: 0 } };
  }
  await tryCommand(server, `tp ${playerName} ${stand.x} ${stand.y} ${stand.z}`);
  await tick.sprint(server, 10);
  if (how.clickThrough) {
    // What Create does with a right click it has traced to the seat, without the trace: a shut door is in the way.
    await drainActionBar(client).catch(() => null);
    const local = await conductorSeatLocalPos(server, carriageUuid);
    if (local) {
      await runColonyTask(server, CLICK_CARRIAGE_BLOCK, { player: playerName, carriage: carriageUuid, ...local });
    }
    await tick.sprint(server, 10);
    const bar = await readActionBar(client).catch(() => ({ text: "", remaining_ticks: 0 }));
    return { seat, actionBar: bar.remaining_ticks > 0 ? bar : { text: "", remaining_ticks: 0 } };
  }
  const here = (await world.players(server)).find((q) => q.name === playerName);
  if (here?.position) {
    const eye = { x: here.position.x, y: here.position.y + 1.62, z: here.position.z };
    const dx = seat.x - eye.x, dy = seat.y - eye.y, dz = seat.z - eye.z;
    await player.place(client, {
      yaw: (Math.atan2(-dx, dz) * 180) / Math.PI,
      pitch: (-Math.atan2(dy, Math.hypot(dx, dz)) * 180) / Math.PI,
    }).catch(() => null);
    await tick.sprint(server, 5);
  }
  await drainActionBar(client).catch(() => null);
  await input.button(client, { button: "right", action: "press" }).catch(() => null);
  await tick.sprint(server, 3);
  await input.button(client, { button: "right", action: "release" }).catch(() => null);
  await tick.sprint(server, 10);
  const bar = await readActionBar(client).catch(() => ({ text: "", remaining_ticks: 0 }));
  await screen.dismiss(client).catch(() => null);
  return { seat, actionBar: bar.remaining_ticks > 0 ? bar : { text: "", remaining_ticks: 0 } };
}

export async function seatedAt(server: MinecraftServer, carriageUuid: string, uuid: string): Promise<number | undefined> {
  const seats = await world
    .entity(server, { uuid: carriageUuid }, { nbtPath: "Contraption.Passengers" })
    .catch(() => null);
  const rows: any[] = Array.isArray(seats?.nbt) ? seats.nbt : [];
  const want = uuidToIntArray(uuid).join(",");
  const row = rows.find((entry) => Array.isArray(entry?.Id) && entry.Id.join(",") === want);
  return row === undefined ? undefined : Number(row.Seat);
}

export async function trainHasSchedule(server: MinecraftServer, carriageUuid: string): Promise<boolean> {
  const sched = await reflect
    .invoke(server, { target: { kind: "entity", uuid: carriageUuid }, path: "getCarriage().train.runtime", method: "getSchedule", returnType: "com.simibubi.create.content.trains.schedule.Schedule", limits: { maxDepth: 1 } })
    .catch(() => null);
  return Boolean(sched?.returned);
}

const CLICK_CARRIAGE_BLOCK = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.rail-click-carriage-block",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.content.contraptions.AbstractContraptionEntity",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.world.InteractionHand",
    ],
    body: `
var args=Args.of(ctx);
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));
if(player==null)throw new TaskException("no-player","no player named "+args.string("player"));
var entity=ctx.server().overworld().getEntity(UUID.fromString(args.string("carriage")));
if(!(entity instanceof AbstractContraptionEntity carriage))throw new TaskException("no-carriage","no carriage "+args.string("carriage"));
var local=new BlockPos((int)args.integer("x"),(int)args.integer("y"),(int)args.integer("z"));
var handled=carriage.handlePlayerInteraction(player,local,Direction.EAST,InteractionHand.MAIN_HAND);
return Sync.stamp(Map.of("handled",handled));
`,
  }),
});

async function conductorSeatLocalPos(server: MinecraftServer, carriageUuid: string): Promise<Vec | undefined> {
  const seats = await world
    .entity(server, { uuid: carriageUuid }, { nbtPath: "Contraption.ConductorSeats" })
    .catch(() => null);
  return conductorSeatPos(seats?.nbt) ?? undefined;
}

export async function conductorSeatWorldPos(server: MinecraftServer, carriageUuid: string): Promise<Vec | undefined> {
  const local = await conductorSeatLocalPos(server, carriageUuid);
  if (!local) return undefined;
  const centre = { x: local.x + 0.5, y: local.y + 0.5, z: local.z + 0.5 };
  const global = await reflect
    .invoke(server, { target: { kind: "entity", uuid: carriageUuid }, method: "toGlobalVector", args: [centre, 1.0], argTypes: ["net.minecraft.world.phys.Vec3", "float"], returnType: "net.minecraft.world.phys.Vec3", limits: { maxDepth: 1 } })
    .catch(() => null);
  return global ? reflectVec3(global) : undefined;
}

export async function conductorAboard(server: MinecraftServer, carriageUuid: string): Promise<boolean> {
  const r = await reflect
    .invoke(server, { target: { kind: "entity", uuid: carriageUuid }, path: "getCarriage().train", method: "hasForwardConductor", returnType: "boolean" })
    .catch(() => null);
  return r ? reflect.boolean(r) : false;
}

function conductorSeatPos(nbt: any): Vec | null {
  const stack: any[] = [nbt];
  while (stack.length > 0) {
    const node = stack.pop();
    if (node == null) continue;
    if (Array.isArray(node)) {
      if (node.length === 3 && node.every((n) => typeof n === "number")) {
        return { x: node[0], y: node[1], z: node[2] };
      }
      stack.push(...node);
      continue;
    }
    if (typeof node === "object") {
      if (Array.isArray(node.Pos) && node.Pos.length === 3) {
        return { x: node.Pos[0], y: node.Pos[1], z: node.Pos[2] };
      }
      stack.push(...Object.values(node));
    }
  }
  return null;
}

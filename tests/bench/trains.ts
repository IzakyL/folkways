import { tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commandPos, tryCommand } from "../e2e/colony-founding";
import { carEndOf, headingOf, trackOf, type CarFrame, type Stop } from "../e2e/rail-footprint";
import { intArrayOf } from "../e2e/rail-loop";
import { SEATS, TRAINS, type Line } from "./network";

type Vec = { x: number; y: number; z: number };

export { TRAINS };

/** Each line's colour, in the band round its cars and the lamps of its cabs. */
const LINE_COLOURS = ["red", "blue", "green", "orange"];

/**
 * A line's cars, dressed as a commuter car: a casing underframe, white sides with a band of framed windows and the
 * line's colour above them, two pairs of sliding doors a side, a cab at the nose with headlights, windscreen and a lit
 * destination board, a rounded roof with its units. Riders board from the platform beside the car, so the walls keep no
 * one out; the conductor's seat is clicked through the doors.
 */
export function dressCar(lineIndex: number) {
  const colour = LINE_COLOURS[lineIndex % LINE_COLOURS.length];
  const end = carEndOf(SEATS) - 2;
  const doors = [8, 9, end - 4, end - 3];
  return async (server: MinecraftServer, _origin: Vec, f: CarFrame) => {
    // Cells are given across, up, along: [u, h, v].
    const fill = (a: [number, number, number], b: [number, number, number], state: string) =>
      world.command(server, `fill ${commandPos(f.at(a[0], a[2], a[1]))} ${commandPos(f.at(b[0], b[2], b[1]))} ${state}`);
    const block = (u: number, h: number, v: number, state: string) =>
      world.command(server, `setblock ${commandPos(f.at(u, v, h))} ${state}`);

    await fill([-2, 1, 1], [2, 1, end], "create:railway_casing replace minecraft:oak_planks");
    for (const side of [-2, 2]) {
      await fill([side, 2, 1], [side, 2, end], "minecraft:white_concrete");
      await fill([side, 3, 1], [side, 3, end], "create:framed_glass");
      await fill([side, 4, 1], [side, 4, end], `minecraft:${colour}_concrete`);
      const inward = f.outward(-side);
      for (const [i, v] of doors.entries()) {
        const hinge = (i % 2 === 0) === (side < 0) ? "left" : "right";
        await block(side, 2, v, `create:train_door[facing=${inward},half=lower,hinge=${hinge},open=false]`);
        await block(side, 3, v, `create:train_door[facing=${inward},half=upper,hinge=${hinge},open=false]`);
      }
    }
    // The cab at the nose: headlights either side of the coupling, a dark windscreen, the lit destination board.
    for (const u of [-1, 1]) await block(u, 2, 1, "minecraft:sea_lantern");
    await fill([-1, 3, 1], [1, 3, 1], "minecraft:black_stained_glass");
    // The tail: the back row of seats sits against it, so only its middle is walled.
    await block(0, 2, end, "minecraft:white_concrete");
    await block(0, 3, end, "minecraft:black_stained_glass");
    for (const v of [1, end]) {
      await fill([-1, 4, v], [1, 4, v], "minecraft:black_concrete");
      await block(0, 4, v, "minecraft:redstone_lamp[lit=true]");
    }
    // The roof, rounded at its edges and ends, with the units along its crown.
    await fill([-1, 5, 1], [1, 5, end], "minecraft:light_gray_concrete");
    await fill([-2, 5, 1], [-2, 5, end], `minecraft:smooth_quartz_stairs[facing=${f.outward(1)}]`);
    await fill([2, 5, 1], [2, 5, end], `minecraft:smooth_quartz_stairs[facing=${f.outward(-1)}]`);
    await fill([-1, 5, 1], [1, 5, 1], `minecraft:smooth_quartz_stairs[facing=${f.back}]`);
    await fill([-1, 5, end], [1, 5, end], `minecraft:smooth_quartz_stairs[facing=${f.ahead}]`);
    for (const v of [5, Math.round(end / 2) + 1, end - 2]) await fill([-1, 6, v], [1, 6, v + 1], "minecraft:polished_andesite_slab[type=bottom]");
  };
}

/**
 * Where each further train of a line is built and first calls: the stops spread round the line by the way the track
 * runs between them, each train starting at the stop nearest its share of the way round from the first. The central
 * station keeps no halt for drivers to start on, so it is never chosen.
 */
export function furtherStarts(line: Line): Stop[] {
  const stops = line.stops;
  const at = (s: Stop) => s.position;
  const legs = stops.map((s, i) => {
    const next = at(stops[(i + 1) % stops.length]);
    return Math.hypot(next.x - at(s).x, next.z - at(s).z);
  });
  const total = legs.reduce((a, b) => a + b, 0);
  const halted = new Set(line.halts.map((h) => h.stop));
  const starts: Stop[] = [];
  for (let train = 1; train < TRAINS; train++) {
    const want = (total * train) / TRAINS;
    let best: Stop | undefined, bestOff = Infinity, run = 0;
    for (let i = 0; i < stops.length; i++) {
      if (i > 0 && halted.has(stops[i].name) && !starts.includes(stops[i]) && Math.abs(run - want) < bestOff) {
        best = stops[i];
        bestOff = Math.abs(run - want);
      }
      run += legs[i];
    }
    if (best) starts.push(best);
  }
  return starts;
}

/** The line's stops in the order a train first calls at them, starting from `first`. */
export function callingFrom(line: Line, first: Stop): Stop[] {
  const k = line.stops.indexOf(first);
  return [...line.stops.slice(k), ...line.stops.slice(0, k)];
}

/**
 * Signals a few cells past each station and a car's length before it, for the way trains run through it: each station
 * is a block section of its own and so is each stretch between two, so a train waits before a station while another
 * stands in it, and at a station while the one ahead is still in the stretch beyond, rather than running into it.
 * With the stations alone as boundaries a line has as many sections as stations, and as many trains as that each
 * wait for ever on the section the next one stands in. One signal stands either side of the track, each pointing at
 * the track cell it guards.
 */
export async function signalStations(server: MinecraftServer, stops: Stop[], past = 3, before = carEndOf(SEATS) + 6) {
  const placed: Array<{ at: Vec; block?: string }> = [];
  for (const stop of stops) for (const along of [past, -before]) {
    const t = trackOf(stop), z = (stop.axis ?? "z") === "z", ahead = headingOf(stop) * along;
    const track = z ? { ...t, z: t.z + ahead } : { ...t, x: t.x + ahead };
    for (const side of [-1, 1]) {
      const at = z ? { ...track, x: track.x + side } : { ...track, z: track.z + side };
      const rel = `[I;${track.x - at.x},0,${track.z - at.z}]`;
      const nbt = `{TargetTrack:${rel},TargetDirection:${side > 0 ? 1 : 0}b,Ortho:0b,Id:${intArrayOf(crypto.randomUUID())}}`;
      await tryCommand(server, `setblock ${commandPos(at)} create:track_signal${nbt}`);
      placed.push({ at });
    }
  }
  await tick.sprint(server, 20);
  for (const p of placed) p.block = (await world.block(server, p.at))?.id;
  return { signals: placed.length, standing: placed.filter((p) => p.block === "create:track_signal").length,
    missing: placed.filter((p) => p.block !== "create:track_signal").map((p) => p.at) };
}

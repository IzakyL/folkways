import { defineTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../colony/colony-tasks";
import { commandPos, tryCommand } from "../colony/colony-founding";
import { crossingsOf, straightsOf } from "./rail-footprint";
export { crossingsOf, straightsOf } from "./rail-footprint";

type Vec = { x: number; y: number; z: number };
export type LoopBounds = { minX: number; maxX: number; minZ: number; maxZ: number };

export async function buildTrackLoop(server: MinecraftServer, bounds: LoopBounds, y: number) {
  const { minX: l, maxX: r, minZ: t, maxZ: b } = bounds;
  const radius = 16;
  if (r - l < 64 || b - t < 80) throw new Error("Rail loop needs at least 64 x 80 blocks");
  const p = (x: number, z: number): Vec => ({ x, y, z });
  const lines = [
    [p(r, t + radius), p(r, b - radius), "zo"],
    [p(l, t + radius), p(l, b - radius), "zo"],
    [p(l + radius, t), p(r - radius, t), "xo"],
    [p(l + radius, b), p(r - radius, b), "xo"],
  ] as const;
  const curves = [
    { a: p(r, t + radius), b: p(r - radius, t), ax: 0, az: -1, bx: 1, bz: 0 },
    { a: p(l + radius, t), b: p(l, t + radius), ax: -1, az: 0, bx: 0, bz: -1 },
    { a: p(l, b - radius), b: p(l + radius, b), ax: 0, az: 1, bx: -1, bz: 0 },
    { a: p(r - radius, b), b: p(r, b - radius), ax: 1, az: 0, bx: 0, bz: 1 },
  ];
  // Chunk-aligned strips, so no strip falls wholly inside chunks the one before already loaded (forceload refuses those).
  for (let x = Math.floor((l - 8) / 16) * 16; x <= r + 8; x += 64) {
    // A strip already loaded by someone else (another line, the town) marks nothing new; that is no failure.
    await tryCommand(server, `forceload add ${x} ${t - 8} ${Math.min(x + 63, r + 8)} ${b + 8}`);
  }
  await tick.sprint(server, 40);
  for (const [a, b, shape] of lines) {
    await world.command(server, `fill ${commandPos(a)} ${commandPos(b)} create:track[shape=${shape},turn=false,waterlogged=false]`);
  }
  const result = await runColonyTask(server, CURVES, { curves });
  await tick.sprint(server, 40);
  return { ...result, bounds, radius, curves: curves.length };
}

/** Joins the four corner tracks of the loop with Create bezier curves. */
const CURVES = defineTask<{ curves: unknown[] }, Record<string, any>>({
  name: "folkways.rail.loop-curves",
  side: "server",
  timeoutMs: 20_000,
  source: `
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import dev.blockwright.api.TaskException;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.createmod.catnip.data.Couple;
import com.simibubi.create.content.trains.track.*;
public final class Task {
  static BlockPos pos(Object raw) {
    var p = (Map<?,?>) raw;
    return new BlockPos(((Number)p.get("x")).intValue(), ((Number)p.get("y")).intValue(), ((Number)p.get("z")).intValue());
  }
  static Vec3 axis(Map<?,?> c, String prefix) {
    return new Vec3(((Number)c.get(prefix+"x")).doubleValue(), 0, ((Number)c.get(prefix+"z")).doubleValue());
  }
  public static Object run(Context ctx) {
    var level = ctx.server().overworld();
    var curves = Args.of(ctx).list("curves");
    if (curves == null) throw new TaskException("invalid", "args.curves is required (an array)");
    int connected = 0;
    for (var raw : curves) {
      var c = (Map<?,?>)raw;
      var a = pos(c.get("a")); var b = pos(c.get("b"));
      for (var p : List.of(a,b)) level.setBlockAndUpdate(p, level.getBlockState(p).setValue(TrackBlock.HAS_BE, true));
      var aa = axis(c,"a"); var bb = axis(c,"b");
      var curve = new BezierConnection(Couple.create(a,b),
        Couple.create(((ITrackBlock)level.getBlockState(a).getBlock()).getCurveStart(level,a,level.getBlockState(a),aa),
          ((ITrackBlock)level.getBlockState(b).getBlock()).getCurveStart(level,b,level.getBlockState(b),bb)),
        Couple.create(aa,bb), Couple.create(new Vec3(0,1,0),new Vec3(0,1,0)), true, false, TrackMaterial.ANDESITE);
      var abe = (TrackBlockEntity)level.getBlockEntity(a);
      var bbe = (TrackBlockEntity)level.getBlockEntity(b);
      abe.addConnection(curve); bbe.addConnection(curve.secondary());
      if (abe.getConnections().containsKey(b) && bbe.getConnections().containsKey(a)) connected++;
    }
    if (connected != 4) {
      throw new TaskException("refused", "only " + connected + " of 4 curves connected", Map.of("connected_curves", connected));
    }
    return Sync.stamp(Map.of("ok", true, "connected_curves", connected));
  }
}`,
});

/**
 * Lays level crossings where a new loop meets the loops already down: clearing the new loop's corridor took the old
 * track up where they meet, so the old straights are laid back wherever they are missing, and each meeting cell
 * becomes a crossing piece, which carries both lines through.
 */
export async function crossLoops(server: MinecraftServer, loop: LoopBounds, earlier: LoopBounds[], y: number) {
  const crossings = crossingsOf(loop, earlier);
  for (const old of earlier) for (const s of straightsOf(old)) {
    const a = s.axis === "z" ? { x: s.at, z: s.from } : { x: s.from, z: s.at };
    const b = s.axis === "z" ? { x: s.at, z: s.to } : { x: s.to, z: s.at };
    // Most straights were never cut, so their refill places nothing; that is no failure.
    await tryCommand(server, `fill ${a.x} ${y} ${a.z} ${b.x} ${y} ${b.z} create:track[shape=${s.axis}o,turn=false,waterlogged=false] keep`);
  }
  for (const c of crossings) await world.command(server, `setblock ${c.x} ${y} ${c.z} create:track[shape=cr_o,turn=false,waterlogged=false]`);
  await tick.sprint(server, 20);
  const placed = [];
  for (const c of crossings) placed.push({ ...c, block: (await world.block(server, { x: c.x, y, z: c.z }))?.id });
  return { crossings: placed };
}

export const intArrayOf = (uuid: string) => {
  const hex = uuid.replace(/-/g, "");
  return `[I;${[0, 8, 16, 24].map((i) => (parseInt(hex.slice(i, i + 8), 16) | 0)).join(",")}]`;
};

/**
 * Signals on both lines either side of each crossing, `gap` blocks out, so the crossing is a block section of its own:
 * a train waits at the signal while another crosses, rather than meeting it there. Each point gets a signal for either
 * direction of travel, one on each side of the track, each pointing at the track cell it guards.
 */
export async function signalCrossings(server: MinecraftServer, crossings: Array<{ x: number; z: number }>, y: number, gap = 6) {
  const placed: Array<{ at: Vec; block?: string }> = [];
  for (const c of crossings) {
    for (const along of ["x", "z"] as const) for (const out of [-gap, gap]) {
      const track = along === "z" ? { x: c.x, y, z: c.z + out } : { x: c.x + out, y, z: c.z };
      for (const side of [-1, 1]) {
        const at = along === "z" ? { x: track.x + side, y, z: track.z } : { x: track.x, y, z: track.z + side };
        const rel = `[I;${track.x - at.x},0,${track.z - at.z}]`;
        const nbt = `{TargetTrack:${rel},TargetDirection:${side > 0 ? 1 : 0}b,Ortho:0b,Id:${intArrayOf(crypto.randomUUID())}}`;
        await tryCommand(server, `setblock ${commandPos(at)} create:track_signal${nbt}`);
        placed.push({ at });
      }
    }
  }
  await tick.sprint(server, 20);
  for (const p of placed) p.block = (await world.block(server, p.at))?.id;
  return { signals: placed.length, standing: placed.filter((p) => p.block === "create:track_signal").length };
}

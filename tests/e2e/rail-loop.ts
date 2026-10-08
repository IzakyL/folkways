import { defineTask, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "./colony-tasks";
import { commandPos } from "./colony-founding";

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
  for (let x = l - 8; x <= r + 8; x += 64) {
    await world.command(server, `forceload add ${x} ${t - 8} ${Math.min(x + 63, r + 8)} ${b + 8}`);
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

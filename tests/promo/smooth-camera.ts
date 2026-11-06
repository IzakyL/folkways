import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import type { Vec } from "../shared/colony/colony-founding";
import type { MountedScene, Pose } from "./mounted-stage";
import { sleepMs } from "./promo-kit";

type Frame = { at: number; x: number; y: number; z: number; yaw: number; pitch: number; acceleration: number[] };

/** FreeCamera.Path is piecewise linear, even with many keys. Keep spring velocity on the render thread instead. */
export async function followCamera(scene: MountedScene, options: {
  from: Pose; until: () => boolean; sample: () => Promise<{ eye: Vec; target: Vec } | null>;
}) {
  await scene.client.run(FOLLOW, { action: "start", pose: options.from });
  let frames: Frame[] = [];
  try {
    while (!options.until()) {
      const next = await options.sample();
      if (next) {
        await scene.client.run(FOLLOW, { action: "target", ...next });
      }
      await sleepMs(270);
    }
    await scene.client.run(FOLLOW, { action: "brake" });
    await sleepMs(700);
  } finally {
    frames = await scene.client.run(FOLLOW, { action: "stop" });
  }
  // Secant velocities belong at interval midpoints. This works for irregular rendered frame times too,
  // including a slow frame; dividing by a nominal 30fps interval would invent acceleration spikes.
  let maxAcceleration = 0;
  let maxJerk = 0;
  for (let i = 2; i < frames.length; i++) {
    const [a, b, c] = frames.slice(i - 2, i + 1);
    const dt0 = b.at - a.at;
    const dt1 = c.at - b.at;
    const acc = ["x", "y", "z"].map((key) => {
      const k = key as "x" | "y" | "z";
      return 2 * ((c[k] - b[k]) / dt1 - (b[k] - a[k]) / dt0) / (dt0 + dt1);
    });
    maxAcceleration = Math.max(maxAcceleration, Math.hypot(...acc));
    maxJerk = Math.max(maxJerk, Math.hypot(...c.acceleration.map((v, k) => (v - b.acceleration[k]) / dt1)));
  }
  const metrics = { frames: frames.length, maxAcceleration, maxJerk, accelerationLimit: 8.1, jerkLimit: 24.1,
    units: "blocks/s² and blocks/s³; actual rendered poses, midpoint secant differences",
    method: "Per-render-frame critically damped position/shortest-angle springs; "
      + "persistent velocity, bounded acceleration and jerk; coasts to rest at the clip's tail" };
  if (frames.length < 30 || !Number.isFinite(maxAcceleration) || !Number.isFinite(maxJerk)
    || maxAcceleration > metrics.accelerationLimit || maxJerk > metrics.jerkLimit) {
    throw new Error(`follow camera continuity failed: ${JSON.stringify(metrics)}`);
  }
  return { metrics, frames };
}

const FOLLOW = defineTask<Record<string, any>, Frame[]>({
  name: "folkways.promo.smooth-follow",
  side: "client",
  limits: { maxNodes: 2_000_000, maxItems: 100_000 },
  source: javaTask({
    imports: ["blockwright.minecraft.core.client.View", "dev.blockwright.api.CameraPose"],
    body: `
var a = Args.of(ctx);
if (a.string("action").equals("start")) {
    var p = (Map<?, ?>) a.value("pose");
    var xyz = (Map<?, ?>) p.get("position");
    track = new Track();
    for (int i = 0; i < 3; i++) {
        track.p[i] = ((Number) xyz.get(List.of("x", "y", "z").get(i))).doubleValue();
    }
    track.p[3] = ((Number) p.get("yaw")).doubleValue();
    track.p[4] = ((Number) p.get("pitch")).doubleValue();
    System.arraycopy(track.p, 0, track.want, 0, 5);
    Track active = track;
    View.holdCamera(partial -> active.frame());
} else if (a.string("action").equals("target")) {
    var eye = (Map<?, ?>) a.value("eye");
    var target = (Map<?, ?>) a.value("target");
    for (int i = 0; i < 3; i++) {
        track.want[i] = ((Number) eye.get(List.of("x", "y", "z").get(i))).doubleValue();
        track.look[i] = ((Number) target.get(List.of("x", "y", "z").get(i))).doubleValue();
    }
    track.aim = true;
} else if (a.string("action").equals("brake")) {
    track.braking = true;
} else {
    // Keep coasting to rest through the clip's tail. Freezing this pose would introduce a velocity jump.
    track.recording = false;
    var frames = track.frames;
    track = null;
    return frames;
}
return List.of();`,
    members: `
private static Track track;

private static final class Track {
    final double[] p = new double[5], v = new double[5], acc = new double[5], want = new double[5];
    final double[] look = new double[3];
    final List<Object> frames = new ArrayList<>();
    final long started = System.nanoTime();
    long previous = started;
    boolean aim;
    boolean braking;
    boolean recording = true;

    CameraPose pose() {
        return new CameraPose(true, p[0], p[1], p[2], true, (float) p[3], (float) p[4]);
    }

    CameraPose frame() {
        long now = System.nanoTime();
        double elapsed = (now - previous) / 1.0e9;
        if (elapsed < 1.0e-5) {
            return pose();
        }
        previous = now;
        int steps = Math.max(1, (int) Math.ceil(elapsed * 120));
        double dt = elapsed / steps;
        for (int step = 0; step < steps; step++) {
            if (aim && !braking) {
                double dx = look[0] - p[0], dy = look[1] - p[1], dz = look[2] - p[2];
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                want[3] = p[3] + ((yaw - p[3]) % 360 + 540) % 360 - 180;
                want[4] = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            }
            double[] next = new double[5];
            for (int i = 0; i < 5; i++) {
                // omega=2.5: critical damping without a frame-rate-dependent lerp coefficient.
                next[i] = (braking ? 0 : 6.25 * (want[i] - p[i])) - 5 * v[i];
            }
            limit(next, 8);
            double[] change = {next[0] - acc[0], next[1] - acc[1], next[2] - acc[2]};
            limit(change, 24 * dt);
            for (int i = 0; i < 5; i++) {
                double old = acc[i];
                acc[i] = i < 3 ? old + change[i]
                    : old + Math.clamp(Math.clamp(next[i], -90, 90) - old, -270 * dt, 270 * dt);
                // Exact integration for acceleration varying linearly across this substep: C2 position,
                // rather than Euler steps whose velocity changes at render frame boundaries.
                double jerk = (acc[i] - old) / dt;
                p[i] += v[i] * dt + old * dt * dt / 2 + jerk * dt * dt * dt / 6;
                v[i] += old * dt + jerk * dt * dt / 2;
            }
        }
        if (recording) {
            frames.add(Map.of("at", (now - started) / 1.0e9, "x", p[0], "y", p[1], "z", p[2],
                "yaw", p[3], "pitch", p[4], "acceleration", List.of(acc[0], acc[1], acc[2])));
        }
        return pose();
    }

    private static void limit(double[] vector, double maximum) {
        double size = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        if (size > maximum) {
            for (int i = 0; i < 3; i++) {
                vector[i] *= maximum / size;
            }
        }
    }
}`,
  }),
});

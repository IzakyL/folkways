import { camera, defineTask, input, javaTask, media, player as players, screen, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { mkdirSync, statSync } from "node:fs";
import { spawn, execFileSync } from "node:child_process";
import { outAbs, outPath } from "../out-paths";
import type { Vec } from "./fixture";
import { frames, safeAction, tryCommand } from "../shared/bw-helpers";

// The overview recording in flight, between startOverview and finishOverview.
let recording: media.Recording | undefined;

export async function startOverview(client: MinecraftClient, server: MinecraftServer, player: string, extent: {
  minX: number; maxX: number; minZ: number; maxZ: number;
}, groundY: number) {
  mkdirSync(outAbs("bench", "film"), { recursive: true });
  const { eye, yaw, pitch } = framing(extent, groundY, OVERVIEW_PITCH);
  await world.command(server, `gamemode spectator ${player}`);
  await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z} ${yaw} ${pitch}`);
  await safeAction(() => screen.dismiss(client));
  await tryCommand(server, `clear ${player}`);
  await hideHud(client);
  const rendering = await prepareCamera(client);
  await safeAction(() => players.place(client, { yaw, pitch }));
  await safeAction(() => camera.set(client, { position: eye, yaw, pitch }));
  await safeAction(() => frames(client, 100));
  const preview = outPath("bench", "film", "overview.png");
  await media.screenshot(client, { path: preview });
  const pixels = execFileSync("ffmpeg", ["-v", "error", "-i", outAbs("bench", "film", "overview.png"),
    "-vf", "scale=160:90", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]);
  let groundPixels = 0;
  for (let i = 0; i < pixels.length; i += 3)
    if (pixels[i + 1] > pixels[i] * 1.08 && pixels[i + 1] > pixels[i + 2] * 1.12) groundPixels++;
  const groundFraction = groundPixels / (pixels.length / 3);
  if (groundFraction < 0.03) throw new Error(`Overhead preview does not show the grass district (${groundFraction}); check camera and render distance`);
  const path = outPath("bench", "film", "large-colony-realtime.mp4");
  if (recording) await recording.discard().catch(() => undefined);
  recording = undefined;
  try {
    recording = await media.record(client, { path, fps: 20, audio: false });
  } catch (error) {
    throw new Error(`Overview recording did not start: ${String(error)}`);
  }
  const started = { status: "recording", id: recording.id, path: recording.path, width: recording.width,
    height: recording.height, fps: recording.fps };
  return { path, preview, groundFraction, rendering, eye, yaw, pitch, extent, fps: 20, started, wall_start_ms: Date.now() };
}

const OVERVIEW_PITCH = 62;
const HALF_FOV = 35 * Math.PI / 180;
const ASPECT = 16 / 9;

// Where a camera facing north and tilted down by pitch must stand to fit the whole box in frame.
function framing(extent: Box, groundY: number, pitchDeg: number) {
  const p = pitchDeg * Math.PI / 180;
  const cx = (extent.minX + extent.maxX) / 2, cz = (extent.minZ + extent.maxZ) / 2;
  const forward = { x: 0, y: -Math.sin(p), z: -Math.cos(p) };
  const up = { x: 0, y: Math.cos(p), z: -Math.sin(p) };
  const tanV = Math.tan(HALF_FOV) * 0.94, tanH = Math.tan(HALF_FOV) * ASPECT * 0.94;
  const corners = [[extent.minX, extent.minZ], [extent.maxX, extent.minZ], [extent.minX, extent.maxZ], [extent.maxX, extent.maxZ]];
  const fits = (d: number, shift: number) => {
    const eye = { x: cx, y: groundY + d * Math.sin(p), z: cz + shift + d * Math.cos(p) };
    return corners.every(([x, z]) => {
      const v = { x: x - eye.x, y: groundY - eye.y, z: z - eye.z };
      const depth = v.x * forward.x + v.y * forward.y + v.z * forward.z;
      const right = v.x, high = v.x * up.x + v.y * up.y + v.z * up.z;
      return depth > 0 && Math.abs(right / depth) <= tanH && Math.abs(high / depth) <= tanV;
    });
  };
  let best = { d: Infinity, shift: 0 };
  for (let shift = -80; shift <= 80; shift += 4) {
    let lo = 1, hi = 2000;
    if (!fits(hi, shift)) continue;
    for (let i = 0; i < 40; i++) { const mid = (lo + hi) / 2; if (fits(mid, shift)) hi = mid; else lo = mid; }
    if (hi < best.d) best = { d: hi, shift };
  }
  const eye: Vec = { x: cx, y: groundY + best.d * Math.sin(p), z: cz + best.shift + best.d * Math.cos(p) };
  return { eye, yaw: 180, pitch: pitchDeg };
}

type Box = { minX: number; maxX: number; minZ: number; maxZ: number };
export type Shot = { name: string; eye: Vec; target: Vec; alternatives?: Vec[]; subject?: Box };

let hudHidden = false;

async function hideHud(client: MinecraftClient) {
  if (hudHidden) return;
  await safeAction(() => input.key(client, { keyCode: 290 }));
  hudHidden = true;
}

async function prepareCamera(client: MinecraftClient) {
  const rendering = await client.run(OVERHEAD_RENDER, {}, { timeoutMs: 20_000 })
    .catch((error: unknown) => { throw new Error(`Camera rendering setup failed: ${String(error)}`); });
  if (!rendering?.ok) throw new Error(`Camera rendering setup failed: ${JSON.stringify(rendering)}`);
  return rendering;
}

// Stills of the working colony after the measured run. Pick an unobstructed angle in
// the finished world: a neighbouring roof can invalidate an otherwise sensible pose.
export async function heroShots(client: MinecraftClient, server: MinecraftServer, player: string, shots: Shot[]) {
  mkdirSync(outAbs("bench", "film"), { recursive: true });
  await world.command(server, `gamemode spectator ${player}`);
  await tryCommand(server, `clear ${player}`);
  await safeAction(() => screen.dismiss(client));
  await hideHud(client);
  await prepareCamera(client);
  const taken: any[] = [];
  for (const shot of shots) {
    let eye = shot.eye;
    let sight: any;
    if (shot.subject && shot.alternatives?.length) {
      const candidates = [shot.eye, ...shot.alternatives];
      sight = await server.run(HERO_VIEW, { candidates, box: shot.subject, target: shot.target });
      if (sight.visible < 5 || !candidates[sight.index]) throw new Error(`No clear view of ${shot.name}: ${JSON.stringify(sight)}`);
      eye = candidates[sight.index];
    }
    const rotation = cameraLookingAt(eye, shot.target);
    await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z} ${rotation.yaw} ${rotation.pitch}`);
    await safeAction(() => camera.set(client, { position: eye, yaw: rotation.yaw, pitch: rotation.pitch }));
    await safeAction(() => frames(client, 120));
    const path = outPath("bench", "film", `hero-${shot.name}.png`);
    const saved = await media.screenshot(client, { path }).catch((error: unknown) => ({ error: String(error) }));
    taken.push({ name: shot.name, path, eye, target: shot.target, sight, saved: !(saved as any)?.error });
  }
  return taken;
}

const HERO_VIEW = defineTask<Record<string, any>, { index: number; visible: number }>({
  name: "folkways.bench.hero-view",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.level.ClipContext",
      "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.HitResult"],
    body: `
var args=Args.of(ctx);var level=ctx.server().overworld();
var box=(Map<?,?>)args.value("box");var target=(Map<?,?>)args.value("target");
double minX=((Number)box.get("minX")).doubleValue(),maxX=((Number)box.get("maxX")).doubleValue();
double minZ=((Number)box.get("minZ")).doubleValue(),maxZ=((Number)box.get("maxZ")).doubleValue();
double y=((Number)target.get("y")).doubleValue();int best=-1,most=-1,index=0;
for(var raw:args.list("candidates")){
  var c=(Map<?,?>)raw;var eye=new Vec3(((Number)c.get("x")).doubleValue(),((Number)c.get("y")).doubleValue(),((Number)c.get("z")).doubleValue());
  var at=BlockPos.containing(eye);int visible=0;
  if(level.getBlockState(at).getCollisionShape(level,at).isEmpty()){
    for(double u:new double[]{0.25,0.5,0.75})for(double v:new double[]{0.25,0.5,0.75}){
      var end=new Vec3(minX+(maxX-minX)*u,y,minZ+(maxZ-minZ)*v);
      var hit=level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
      var cell=hit.getBlockPos();
      if(hit.getType()==HitResult.Type.MISS||(cell.getX()>=minX&&cell.getX()<=maxX&&cell.getZ()>=minZ&&cell.getZ()<=maxZ))visible++;
    }
  }
  if(visible>most){most=visible;best=index;}index++;
}
return Map.of("index",best,"visible",most);
`,
  }),
});

export async function finishOverview(client: MinecraftClient, video: any) {
  const active = recording;
  recording = undefined;
  if (!active) throw new Error(`Overview recording failed: no recording in flight for ${client.instanceId}`);
  const stopped = await active.stop().catch((error: unknown) => {
    throw new Error(`Overview recording failed: ${String(error)}`);
  });
  const abs = outAbs("bench", "film", "large-colony-realtime.mp4");
  if (statSync(abs).size < 32768)
    throw new Error(`Overview recording failed: ${JSON.stringify(stopped)}`);
  const probe = JSON.parse(execFileSync("ffprobe", ["-v", "error", "-show_streams", "-show_format", "-of", "json", abs], { encoding: "utf8" }));
  const quick = outAbs("bench", "film", "large-colony-overview.mp4");
  const wallSeconds = (Date.now() - video.wall_start_ms) / 1000;
  const duration = Number(probe.format.duration);
  const speed = 8;
  await new Promise<void>((resolve, reject) => {
    const p = spawn("ffmpeg", ["-v", "error", "-y", "-i", abs, "-an", "-vf",
      `setpts=${wallSeconds / speed / duration}*PTS,fps=30`, "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-movflags", "+faststart", quick]);
    let error = "";
    p.stderr.on("data", b => error += b);
    p.on("error", reject);
    p.on("close", code => code === 0 ? resolve() : reject(new Error(error)));
  });
  return { ...video, stopped, bytes: statSync(abs).size, duration_s: duration, wall_s: wallSeconds,
    width: probe.streams[0]?.width, height: probe.streams[0]?.height,
    overview: outPath("bench", "film", "large-colony-overview.mp4"), overview_speed: speed,
    note: "Same measured run, no shaders. Recording/rendering overhead is included. Overview is 8x wall time." };
}

const OVERHEAD_RENDER = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.bench.overhead-render",
  side: "client",
  timeoutMs: 20_000,
  source: `
import dev.blockwright.api.Context;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
public final class Task {
    public static Object run(Context ctx) throws Exception {
        var mc = (Minecraft) ctx.client();
        mc.options.cloudStatus().set(CloudStatus.OFF);
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        var field = BlockEntityRenderDispatcher.class.getDeclaredField("renderers");
        field.setAccessible(true);
        var renderers = new HashMap<BlockEntityType<?>, BlockEntityRenderer<?>>((Map<BlockEntityType<?>, BlockEntityRenderer<?>>) field.get(dispatcher));
        for (var type : List.of(BlockEntityType.BED, BlockEntityType.CHEST)) {
            var original = (BlockEntityRenderer<BlockEntity>) renderers.get(type);
            if (original == null) throw new IllegalStateException("No renderer for " + type);
            renderers.put(type, new BlockEntityRenderer<BlockEntity>() {
                public int getViewDistance() { return 512; }
                public void render(BlockEntity entity, float tick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
                    original.render(entity, tick, pose, buffers, light, overlay);
                }
            });
        }
        field.set(dispatcher, renderers);
        mc.levelRenderer.allChanged();
        return Map.of("ok", true, "block_entity_view_distance", 512, "clouds", "off",
            "effective_render_chunks", mc.options.getEffectiveRenderDistance());
    }
}
`,
});

import { camera, defineTask, media, player as players, screen, world,
  type Game, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import type { BlockwrightInstance } from "@izakyl/blockwright-client";
import * as cinema from "@izakyl/blockwright-cinema";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { mkdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { spawn, execFileSync } from "node:child_process";
import nodePath from "node:path";
import { outAbs, outPath, tracePath } from "../out-paths";
import type { Vec } from "./fixture";
import { frames, safeAction, tryCommand } from "../shared/bw-helpers";
import { seedShaders, shaderEvidence } from "../promo/shaders";
import type { Cut } from "./director";

// The run is filmed through the promo's shaders unless FOLKWAYS_LC_SHADERS=0. The film is sped up (FILM.speed), so
// each camera takes few frames a second (FILM.fps) and its client draws at most maxFps (blockwright.toml#bench).
export const FILMING = process.env.FOLKWAYS_LC_VIDEO !== "0";
export const SHADERS = FILMING && process.env.FOLKWAYS_LC_SHADERS !== "0";
// A shot of the cut film lasts FILM.shot seconds on screen (FOLKWAYS_LC_SHOT_S), so FILM.shot * FILM.speed of the run.
export const FILM = { fps: Number(process.env.FOLKWAYS_LC_FPS ?? 10), speed: Number(process.env.FOLKWAYS_LC_FILM_SPEED ?? 8),
  shot: Number(process.env.FOLKWAYS_LC_SHOT_S ?? 3) };

// The roaming cameras (FOLKWAYS_LC_CAMERAS of them, none at 0): each is a client of its own, joined to the bench
// server as profile bench-cam-N of blockwright.toml, so there are at most that many. The director (director.ts) sends
// them in turn to where the colony is busiest, and the cut film is made of what they and the overview took.
export const MOST_CAMERAS = 2;
export const ROAMERS = Number(process.env.FOLKWAYS_LC_CAMERAS ?? MOST_CAMERAS);
if (!Number.isInteger(ROAMERS) || ROAMERS < 0 || ROAMERS > MOST_CAMERAS)
  throw new Error(`FOLKWAYS_LC_CAMERAS must be a count from 0 to ${MOST_CAMERAS} (blockwright.toml's bench-cam profiles), not ${process.env.FOLKWAYS_LC_CAMERAS}`);

// The films look like a model town shot close up (unless FOLKWAYS_LC_MINIATURE=0): a thin band of the picture is
// sharp and the rest falls out of focus, and the colours are pushed toward painted ones.
export const MINIATURE = SHADERS && process.env.FOLKWAYS_LC_MINIATURE !== "0";

/** Seeds a client instance with the shader pack (on or off as SHADERS says) before it launches. */
export function seedFilmClient(instance: string) {
  seedShaders(instance, SHADERS, MINIATURE ? { ...FILM_LOOK, ...MINIATURE_LOOK } : FILM_LOOK, MINIATURE ? MINIATURE_LENS : []);
}

// The cameras look down on the town from high up: the pack's own clouds (which the game's cloud setting does not
// reach) would cover it, and its haze and the fog at the edge of the loaded land would wash it out.
const FILM_LOOK = { CLOUD_STYLE_DEFINE: 0, ATM_FOG_MULT: "0.50", ATM_FOG_DISTANCE: 300, BORDER_FOG: "false" };

// The pack's depth of field, focused on what stands at the middle of the picture, and its tonemap's colour.
const MINIATURE_LOOK = { WORLD_BLUR: 2, WB_DOF_FOCUS: 0, T_SATURATION: "1.15", T_VIBRANCE: "1.10", TM_CONTRAST: "1.10" };

// The pack blurs by the gap in the depth buffer, which is next to nothing between things hundreds of blocks off.
// A lens close to a model blurs by how far off a thing is against the distance in focus, and a tilted one blurs the
// top and the bottom of the picture besides: both here, with a wider blur than the pack's own.
const DOF = "shaders/program/composite3.glsl";
const MINIATURE_LENS = [
  { file: DOF, find: "float coc = max(abs(z1 - centerDepthSmooth) * 0.125 * WB_DOF_I - 0.0001, 0.0);",
    replace: `vec4 focusPos = gbufferProjectionInverse * vec4(0.0, 0.0, centerDepthSmooth * 2.0 - 1.0, 1.0);
                float focus = abs(focusPos.z / focusPos.w);
                float coc = max(abs(focus / max(lViewPos0, 0.1) - 1.0) - 0.12, 0.0) * 0.3;
                coc = max(coc, smoothstep(0.22, 0.5, abs(texCoord.y - 0.5)) * 0.2);` },
  { file: DOF, find: "vec2 dofScale = vec2(1.0, aspectRatio);", replace: "vec2 dofScale = vec2(1.0, aspectRatio) * 1.5;" },
  { file: DOF, find: "coc * 0.75 / 320.0", replace: "coc * 0.75 / 215.0" },
];

/** A client that films: the bench's own, or a further camera. */
export type Camera = { name: string; client: MinecraftClient; player: string; instanceId: string; instance?: BlockwrightInstance };

/**
 * Launches the further cameras, side by side, each into the server the bench's driver already runs. A camera that
 * does not join is left out and said why: the run is measured whether or not it is filmed from everywhere.
 */
export async function launchCameras(game: Game, runId: string, count = ROAMERS) {
  const specs = Array.from({ length: count }, (_, i) => {
    const instanceId = `large-colony-cam${i + 1}-${runId}`;
    seedFilmClient(instanceId);
    return { name: `roam-${i + 1}`, player: `BenchCam${i + 1}`, instanceId,
      launch: { config: `blockwright.toml#bench-cam-${i + 1}`, instance: instanceId, reuseInstanceDir: true,
        trace: tracePath("bench", `large-colony-cam${i + 1}`) } };
  });
  const joined = await cinema.launchCameras(game.instances.driver, specs);
  return {
    cameras: joined.cameras.map((c): Camera => ({ ...c, instanceId: specs.find((spec) => spec.name === c.name)!.instanceId })),
    failed: joined.failed.map((f) => `camera ${f.name} (${specs.find((spec) => spec.name === f.name)!.launch.config.split("#")[1]}) did not join: ${f.reason.slice(0, 400)}`),
  };
}

/** Closes the further cameras; never throws. */
export async function closeCameras(cameras: Camera[]) {
  await cinema.closeCameras(cameras);
}

/** Whether each camera's client filmed through the shader pack, from its log. */
export function filmShaders(cameras: Camera[]) {
  return Object.fromEntries(cameras.map((c) => {
    try { return [c.name, { ok: true, pack: shaderEvidence(c.instanceId, SHADERS).pack }]; }
    catch (error) { return [c.name, { ok: false, error: String(error).slice(0, 600) }]; }
  }));
}

// The recordings in flight, between startFilm and finishFilm.
type Take = { camera: string; recording: media.Recording; path: string; eye: Vec; yaw: number; pitch: number; rolledAt: number };
let takes: Take[] = [];

async function standCamera(c: Camera, server: MinecraftServer, eye: Vec, yaw: number, pitch: number, underground = false) {
  const rendering = await prepareCamera(c.client);
  await cinema.standCamera(c, server, { position: eye, yaw, pitch, nightVision: underground }, { settleFrames: SETTLE_FRAMES });
  await safeAction(() => players.place(c.client, { yaw, pitch }));
  return rendering;
}

async function roll(c: Camera, eye: Vec, yaw: number, pitch: number) {
  const path = outPath("bench", "film", `large-colony-${c.name}-realtime.mp4`);
  try {
    const recording = await media.record(c.client, { path, fps: FILM.fps, audio: false });
    takes.push({ camera: c.name, recording, path, eye, yaw, pitch, rolledAt: Date.now() });
    return { status: "recording", id: recording.id, path: recording.path, width: recording.width, height: recording.height, fps: recording.fps };
  } catch (error) {
    throw new Error(`The ${c.name} recording did not start: ${String(error)}`);
  }
}

/** Where a camera stands and looks. */
export type Pose = { eye: Vec; yaw: number; pitch: number; underground?: boolean };

/**
 * Sends a camera that already films to another pose, and answers once it has drawn from there. A move across country
 * (`far`) waits for the land to arrive and be built as well.
 */
export async function moveCamera(c: Camera, server: MinecraftServer, to: Pose, from: { wasUnderground?: boolean; far?: boolean } = {}) {
  await cinema.moveCamera(c, server, { position: to.eye, yaw: to.yaw, pitch: to.pitch, nightVision: to.underground },
    { settleFrames: SETTLE_FRAMES, arriveMs: from.far ? FAR_SETTLE_MS : 0 });
}

const FAR_SETTLE_MS = 8_000;

/**
 * Starts filming: the bench's own client from overhead, taking in the whole extent, and each roaming camera on the
 * pose it starts from. Every camera starts before the run and stops after it (finishFilm).
 */
export async function startFilm(main: Camera, server: MinecraftServer, extent: Box, groundY: number,
  further: Camera[] = [], stands: Array<Pose & { name: string }> = []) {
  mkdirSync(outAbs("bench", "film"), { recursive: true });
  for (const t of takes) await t.recording.discard().catch(() => undefined);
  takes = [];
  const { eye, yaw, pitch } = framing(extent, groundY, OVERVIEW_PITCH, OVERVIEW_HEADING);
  const rendering = await standCamera(main, server, eye, yaw, pitch);
  const preview = outPath("bench", "film", "overview.png");
  await media.screenshot(main.client, { path: preview });
  const pixels = execFileSync("ffmpeg", ["-v", "error", "-i", outAbs("bench", "film", "overview.png"),
    "-vf", "scale=160:90", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]);
  let groundPixels = 0;
  for (let i = 0; i < pixels.length; i += 3)
    if (pixels[i + 1] > pixels[i] * 1.08 && pixels[i + 1] > pixels[i + 2] * 1.12) groundPixels++;
  const groundFraction = groundPixels / (pixels.length / 3);
  // Green against the sky: the shaders shift grass toward yellow, so a low share only warns (the preview shows why).
  const warning = groundFraction < 0.03
    ? `the overhead preview shows little green ground (${groundFraction}); look at ${preview} for the camera and render distance`
    : undefined;
  const views: any[] = [];
  for (const [i, c] of further.entries()) {
    const at = stands[i];
    if (!at) throw new Error(`The ${c.name} camera has nowhere to start: ${stands.length} spots for ${further.length} cameras`);
    await standCamera(c, server, at.eye, at.yaw, at.pitch, at.underground);
    const still = outPath("bench", "film", `${c.name}.png`);
    await media.screenshot(c.client, { path: still });
    views.push({ name: c.name, player: c.player, eye: at.eye, yaw: at.yaw, pitch: at.pitch, starts_on: at.name, preview: still });
  }
  // The cameras roll together, as near the run's start as they can.
  const started = [await roll(main, eye, yaw, pitch)];
  for (const c of further) {
    const v = views.find((x) => x.name === c.name);
    started.push(await roll(c, v.eye, v.yaw, v.pitch));
  }
  return { preview, groundFraction, warning, rendering, eye, yaw, pitch, extent, fps: FILM.fps, speed: FILM.speed, shaders: SHADERS,
    cameras: views, started, wall_start_ms: Date.now() };
}

// Frames a camera draws before it is filmed: two seconds at the bench client's 20 fps, inside a settle's 5 s.
const SETTLE_FRAMES = 40;

const OVERVIEW_PITCH = 62;
// The way the overview looks along the ground: west.
const OVERVIEW_HEADING = { x: -1, z: 0 };
const HALF_FOV = 35 * Math.PI / 180;
const ASPECT = 16 / 9;

// Where a camera facing along `heading` (a unit step on the ground) and tilted down by pitch must stand to fit the
// whole box in frame.
function framing(extent: Box, groundY: number, pitchDeg: number, heading: { x: number; z: number }) {
  const p = pitchDeg * Math.PI / 180;
  const cx = (extent.minX + extent.maxX) / 2, cz = (extent.minZ + extent.maxZ) / 2;
  const forward = { x: heading.x * Math.cos(p), y: -Math.sin(p), z: heading.z * Math.cos(p) };
  const up = { x: heading.x * Math.sin(p), y: Math.cos(p), z: heading.z * Math.sin(p) };
  const right = { x: -heading.z, z: heading.x };
  const back = (d: number, shift: number) => shift + d * Math.cos(p);
  const tanV = Math.tan(HALF_FOV) * 0.94, tanH = Math.tan(HALF_FOV) * ASPECT * 0.94;
  const corners = [[extent.minX, extent.minZ], [extent.maxX, extent.minZ], [extent.minX, extent.maxZ], [extent.maxX, extent.maxZ]];
  const fits = (d: number, shift: number) => {
    const eye = { x: cx - heading.x * back(d, shift), y: groundY + d * Math.sin(p), z: cz - heading.z * back(d, shift) };
    return corners.every(([x, z]) => {
      const v = { x: x - eye.x, y: groundY - eye.y, z: z - eye.z };
      const depth = v.x * forward.x + v.y * forward.y + v.z * forward.z;
      const aside = v.x * right.x + v.z * right.z, high = v.x * up.x + v.y * up.y + v.z * up.z;
      return depth > 0 && Math.abs(aside / depth) <= tanH && Math.abs(high / depth) <= tanV;
    });
  };
  let best = { d: Infinity, shift: 0 };
  for (let shift = -80; shift <= 80; shift += 4) {
    let lo = 1, hi = 2000;
    if (!fits(hi, shift)) continue;
    for (let i = 0; i < 40; i++) { const mid = (lo + hi) / 2; if (fits(mid, shift)) hi = mid; else lo = mid; }
    if (hi < best.d) best = { d: hi, shift };
  }
  const eye: Vec = { x: cx - heading.x * back(best.d, best.shift), y: groundY + best.d * Math.sin(p), z: cz - heading.z * back(best.d, best.shift) };
  return { eye, yaw: Math.atan2(-heading.x, heading.z) * 180 / Math.PI, pitch: pitchDeg };
}

type Box = { minX: number; maxX: number; minZ: number; maxZ: number };
/** A shot underground is lit by night vision on its camera: the spectator sees the dug rock from inside the hill. */
export type Shot = { name: string; eye: Vec; target: Vec; alternatives?: Vec[]; subject?: Box; underground?: boolean;
  /** The railway stop this view is of, for the cameras that go where a train is due. */
  station?: string };

async function hideHud(client: MinecraftClient) {
  await safeAction(() => camera.hud(client, false));
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
    const { eye, yaw, pitch, sight } = await aim(server, shot);
    await tryCommand(server, shot.underground ? `effect give ${player} night_vision infinite 0 true` : `effect clear ${player} night_vision`);
    await world.command(server, `tp ${player} ${eye.x} ${eye.y} ${eye.z} ${yaw} ${pitch}`);
    await safeAction(() => camera.set(client, { position: eye, yaw, pitch }));
    await safeAction(() => frames(client, SETTLE_FRAMES));
    const path = outPath("bench", "film", `hero-${shot.name}.png`);
    const saved = await media.screenshot(client, { path }).catch((error: unknown) => ({ error: String(error) }));
    taken.push({ name: shot.name, path, eye, target: shot.target, sight, saved: !(saved as any)?.error });
  }
  return taken;
}

// Where a shot is taken from: its own eye, or of its alternatives the one that sees most of its subject.
export async function aim(server: MinecraftServer, shot: Shot) {
  let eye = shot.eye;
  let sight: any;
  if (shot.subject && shot.alternatives?.length) {
    const candidates = [shot.eye, ...shot.alternatives];
    sight = await cinema.clearestView(server, { candidates, subject: shot.subject, y: shot.target.y });
    if (sight.visible < 13 || !candidates[sight.index]) throw new Error(`No clear view of ${shot.name}: ${JSON.stringify(sight)}`);
    eye = candidates[sight.index];
  }
  return { eye, ...cameraLookingAt(eye, shot.target), sight };
}

/**
 * Stops every camera, makes the overview's sped-up film beside its real-time one, and, given the director's cuts,
 * the cut film: each cut's stretch of its camera's recording, sped up, one after another. A train nobody got on or
 * off is left out of it.
 */
export async function finishFilm(video: any, cuts?: Cut[]) {
  const active = takes;
  takes = [];
  if (!active.length) throw new Error("Film failed: no recording in flight");
  const wallSeconds = (Date.now() - video.wall_start_ms) / 1000;
  const films: any[] = [];
  const failures: string[] = [];
  for (const t of active) {
    try { films.push(await finishTake(t, wallSeconds, t.camera === "overview")); }
    catch (error) { failures.push(String(error)); films.push({ camera: t.camera, error: String(error).slice(0, 600) }); }
  }
  let cut: any;
  if (cuts?.length) {
    try { cut = await cutFilm(cuts, active.filter((t) => films.some((f) => f.camera === t.camera && !f.error))); }
    catch (error) { failures.push(`the cut film: ${String(error)}`); cut = { error: String(error).slice(0, 600), cuts }; }
  }
  if (failures.length) throw Object.assign(new Error(`Film failed: ${failures.join("; ")}`), { films, cut });
  return { ...video, wall_s: wallSeconds, films, cut,
    note: `Same measured run, filmed live (${video.shaders ? "shaders on" : "no shaders"}, ${FILM.fps} fps); recording and `
      + `rendering overhead is included, the roaming cameras' clients and their teleports too. Each film is ${FILM.speed}x wall time.` };
}

const run = (command: string, args: string[]) => new Promise<void>((resolve, reject) => {
  const p = spawn(command, args);
  let error = "";
  p.stderr.on("data", (b) => error += b);
  p.on("error", reject);
  p.on("close", (code) => code === 0 ? resolve() : reject(new Error(error.slice(-2000))));
});

// A cut shorter than this on the wall clock is a frame or two of film: it is left out.
const SHORTEST_CUT_MS = 2_000;

async function cutFilm(cuts: Cut[], filmed: Take[]) {
  const dir = outAbs("bench", "film", "cut");
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  const parts = cuts.filter((c) => !c.train || c.alighted || c.boarded).flatMap((c) => {
    const take = filmed.find((t) => t.camera === c.camera);
    const from = Math.max(c.from_ms, take?.rolledAt ?? Infinity), length = (c.to_ms ?? from) - from;
    return take && length >= SHORTEST_CUT_MS ? [{ ...c, source: outAbs("bench", "film", `large-colony-${take.camera}-realtime.mp4`),
      at_s: (from - take.rolledAt) / 1000, length_s: length / 1000 }] : [];
  });
  if (!parts.length) throw new Error("no cut is long enough to show");
  const files = parts.map((_, i) => nodePath.join(dir, `${String(i).padStart(4, "0")}.mp4`));
  for (let i = 0; i < parts.length; i += 4)
    await Promise.all(parts.slice(i, i + 4).map((p, k) => run("ffmpeg", ["-v", "error", "-y", "-ss", String(p.at_s), "-t", String(p.length_s),
      "-i", p.source, "-an", "-vf", `setpts=PTS/${FILM.speed},fps=30`, "-c:v", "libx264", "-preset", "fast", "-crf", "20", files[i + k]])));
  const list = nodePath.join(dir, "list.txt");
  writeFileSync(list, files.map((f) => `file '${f}'\n`).join(""));
  const film = outAbs("bench", "film", "large-colony-cut.mp4");
  await run("ffmpeg", ["-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", list, "-c", "copy", "-movflags", "+faststart", film]);
  rmSync(dir, { recursive: true, force: true });
  const seconds: Record<string, number> = {};
  for (const p of parts) seconds[p.camera] = Math.round(((seconds[p.camera] ?? 0) + p.length_s / FILM.speed) * 10) / 10;
  return { film: outPath("bench", "film", "large-colony-cut.mp4"), bytes: statSync(film).size, shots: parts.length,
    film_seconds_by_camera: seconds, speed: FILM.speed,
    cuts: parts.map(({ source: _, ...p }) => p) };
}

async function finishTake(t: Take, wallSeconds: number, whole: boolean) {
  const stopped = await t.recording.stop().catch((error: unknown) => {
    throw new Error(`The ${t.camera} recording failed: ${String(error)}`);
  });
  const abs = outAbs("bench", "film", `large-colony-${t.camera}-realtime.mp4`);
  if (statSync(abs).size < 32768)
    throw new Error(`The ${t.camera} recording failed: ${JSON.stringify(stopped)}`);
  const probe = JSON.parse(execFileSync("ffprobe", ["-v", "error", "-show_streams", "-show_format", "-of", "json", abs], { encoding: "utf8" }));
  const duration = Number(probe.format.duration);
  const taken = { camera: t.camera, eye: t.eye, yaw: t.yaw, pitch: t.pitch, stopped, path: t.path, bytes: statSync(abs).size,
    duration_s: duration, width: probe.streams[0]?.width, height: probe.streams[0]?.height };
  // A roaming camera's recording jumps from spot to spot: only the cut film shows it sped up.
  if (!whole) return taken;
  await run("ffmpeg", ["-v", "error", "-y", "-i", abs, "-an", "-vf",
    `setpts=${wallSeconds / FILM.speed / duration}*PTS,fps=30`, "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-movflags", "+faststart",
    outAbs("bench", "film", `large-colony-${t.camera}.mp4`)]);
  return { ...taken, film: outPath("bench", "film", `large-colony-${t.camera}.mp4`), speed: FILM.speed };
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

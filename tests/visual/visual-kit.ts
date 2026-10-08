import { expect } from "@playwright/test";
import { panelElements, ancestorsOf, visibleBox } from "../ldlib2";
import { camera as cameras, input, media, screen as screens, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT, outAbs, outPath } from "../out-paths";
import { formatReport, type LayoutProbe, type LintOptions, type LintReport } from "./layout-lint";
import { frames, safeScreen, screenName } from "../shared/bw-helpers";

export const CONFIG_PATH = "blockwright.toml";
export const VISUAL_ROOT = outPath("visual", "takes");
export const VISUAL_DIR = outAbs("visual", "takes");
export { REPO_ROOT };

export const MIN_CLIP_BYTES = 32 * 1024;
export const MAX_BLACK_FRACTION = 0.5;

export type CameraPose = {
  dimension?: string;
  position: { x: number; y: number; z: number };
  yaw: number;
  pitch: number;
  fov?: number;
};

export type ClipOptions = {
  subject: string;
  worldState: Record<string, unknown>;
  camera?: CameraPose | null;
  seconds: number;
  fps?: number;
  /** Mux the client's audio mix into the clip; off by default, so a clip has no audio track. */
  audio?: boolean;
  during?: () => Promise<void>;
};

export type VisualEntry = {
  kind: "clip" | "shot";
  name: string;
  subject: string;
  worldState: Record<string, unknown>;
  path: string;
  bytes: number;
  gates: Record<string, unknown>;
};

export class VisualRun {
  readonly scope: string;
  readonly root: string;
  readonly dir: string;
  readonly entries: VisualEntry[] = [];
  readonly context: Record<string, unknown> = {};
  readonly lints: LintReport[] = [];

  constructor(scope: string, root = VISUAL_ROOT) {
    this.scope = scope;
    this.root = root;
    this.dir = path.join(REPO_ROOT, ...root.split("/"), scope);
    rmSync(this.dir, { recursive: true, force: true });
    mkdirSync(this.dir, { recursive: true });
  }

  rel(name: string) {
    return path.posix.join(this.root, this.scope, name);
  }

  async clip(client: MinecraftClient, server: MinecraftServer, name: string, options: ClipOptions) {
    if (process.env.FOLKWAYS_VISUAL_IMAGES_ONLY === "1") {
      try {
        await tick.freeze(server, false);
        if (options.during) await options.during();
        await this.shot(client, `${name}-start`, { ...options, hard: true });
        await holdRealSeconds(options.seconds / 2);
        await this.shot(client, `${name}-middle`, { ...options, hard: true });
        await holdRealSeconds(options.seconds / 2);
        await this.shot(client, `${name}-end`, { ...options, hard: true });
      } finally {
        await tick.freeze(server, true);
      }
      return;
    }
    const rel = this.rel(`${name}.mp4`);
    const abs = path.join(REPO_ROOT, rel);
    const fps = options.fps ?? 12;
    const audio = options.audio ?? false;
    const camera = options.camera ?? null;
    if (camera) {
      await safe(() =>
        cameras.set(client, {
          position: camera.position,
          yaw: camera.yaw,
          pitch: camera.pitch,
        }),
      );
    }

    const tickBefore = await gameTime(server);
    const startedAt = Date.now();
    let recording: media.Recording | null = null;
    let start: any = { status: "error" };
    let stop: any = { status: "error" };
    try {
      start = await safe(async () => {
        recording = await media.record(client, { path: abs, fps, audio });
        return { status: "recording", width: recording.width, height: recording.height };
      });
      await safe(() => tick.freeze(server, false));
      if (options.during) await options.during();
      await holdRealSeconds(options.seconds - (Date.now() - startedAt) / 1000);
    } finally {
      if (recording) {
        const started: media.Recording = recording;
        stop = await safe(async () => ({ status: "saved", ...(await started.stop()) }));
      }
      await safe(() => tick.freeze(server, true));
      if (camera) await safe(() => cameras.reset(client));
    }

    const bytes = fileSize(abs);
    const probe = probeVideo(abs);
    const gates = {
      start_status: start?.status ?? null,
      stop_status: stop?.status ?? null,
      backend: null,
      capture_reason: start?.reason ?? null,
      capture_width: start?.width ?? null,
      capture_height: start?.height ?? null,
      fps,
      frames: stop?.frames ?? null,
      dropped: stop?.dropped ?? null,
      audio,
      audio_muxed: stop?.audio?.muxed ?? null,
      audio_reason: stop?.audio?.reason ?? null,
      audio_offset_s: stop?.audio?.offsetSeconds ?? null,
      audio_silenced_s: stop?.audio ? round(stop.audio.silenced / stop.audio.sampleRate) : null,
      requested_s: options.seconds,
      wall_s: round((Date.now() - startedAt) / 1000),
      ticks_advanced: (await gameTime(server)) - tickBefore,
      exists: existsSync(abs),
      bytes,
      duration_s: probe.durationS,
      black_fraction: probe.blackFraction,
      black_check: probe.blackCheck,
      camera,
    };
    this.entries.push({ kind: "clip", name, subject: options.subject, worldState: options.worldState, path: rel, bytes, gates });
    this.write();

    expect(stop?.status, `Clip ${rel} was not recorded: start=${start?.status} stop=${stop?.status} ${stop?.reason ?? ""}`).toBe("saved");
    if (audio) {
      expect(stop?.audio?.muxed, `Clip ${rel} has no audio track: ${stop?.audio?.reason ?? "no audio arrived"}`).toBe(true);
    }
    expect(bytes, `Clip ${rel} is only ${bytes} bytes, an empty shell`).toBeGreaterThan(MIN_CLIP_BYTES);
    if (probe.blackCheck === "ok") {
      expect(
        probe.blackFraction,
        `Clip ${rel} is ${(probe.blackFraction * 100).toFixed(0)}% black frames: the render surface drew nothing`,
      ).toBeLessThan(MAX_BLACK_FRACTION);
    }
    return gates;
  }

  async shot(
    client: MinecraftClient,
    name: string,
    meta: {
      subject: string;
      worldState: Record<string, unknown>;
      camera?: CameraPose | null;
      hard?: boolean;
      verify?: () => Promise<boolean>;
    },
  ) {
    const rel = this.rel(`${name}.png`);
    const abs = path.join(REPO_ROOT, rel);
    rmSync(abs, { force: true });
    let result: any;
    try {
      await frames(client, 3);
      const shot = await media.screenshot(client, {
        path: abs,
        ...(meta.camera ? { camera: meta.camera } : {}),
      });
      result = { status: "captured", width: shot.width, height: shot.height, sha256: shot.sha256, terrain: shot.terrain };
    } catch (error) {
      result = { status: "error", reason: error instanceof Error ? error.message : String(error) };
    }
    if (meta.verify && !(await meta.verify())) {
      rmSync(abs, { force: true });
      return null;
    }
    const open = screenName(await safeScreen(client));
    const elements = /ModularUI|SelectorWindow|LonePanel/.test(open) ? await panelElements(client) : [];
    const visibleWindows = elements.filter(element =>
      [element, ...ancestorsOf(elements, element)].every(one => one.displayed !== false && one.visible !== false) && visibleBox(elements, element)
      && element.id?.startsWith("folkways.window.close.")).map(element => element.id);
    const bytes = fileSize(abs);
    const gates = { windows: visibleWindows, status: result.status, reason: result.reason ?? null, exists: existsSync(abs), bytes, camera: meta.camera ?? null };
    this.entries.push({ kind: "shot", name, subject: meta.subject, worldState: meta.worldState, path: rel, bytes, gates });
    this.write();
    if (meta.hard !== false) {
      expect(result.status, `Screenshot ${rel} was not captured: ${result.reason ?? ""}`).toBe("captured");
      expect(bytes, `Screenshot ${rel} is an empty file`).toBeGreaterThan(0);
    }
    return gates;
  }

  expectTaken(names: string[]) {
    const took = new Set(this.entries.filter(entry => entry.bytes > 0
      && existsSync(path.join(REPO_ROOT, entry.path))
      && (entry.kind !== "shot" || entry.gates.status === "captured")).map(entry => entry.name));
    if (process.env.FOLKWAYS_VISUAL_IMAGES_ONLY === "1") {
      for (const name of names) {
        if (["start", "middle", "end"].every(part => took.has(`${name}-${part}`))) took.add(name);
      }
    }
    const missing = names.filter((name) => !took.has(name));
    this.note("expected_takes", { want: names, missing });
    expect(
      missing,
      `${this.scope}: these shots were expected but never taken. Most likely an entry token no longer matches, `
        + `and skip-if-missing turned that into "one PNG fewer" instead of a failure. `
        + `Actually taken: ${JSON.stringify([...took])}`,
    ).toEqual([]);
  }

  expectWindows(ids: string[]) {
    const seen = new Set(this.entries.filter(entry => entry.gates.status === "captured")
      .flatMap(entry => (entry.gates.windows ?? []) as string[]));
    const missing = ids.filter(id => !seen.has(id));
    this.note("window_coverage", { expected: ids, missing });
    expect(missing, "Registered windows need an opening scenario before they can be photographed").toEqual([]);
  }

  async lint(probe: LayoutProbe, scope: string, options: LintOptions = {}): Promise<LintReport | undefined> {
    if (process.env.FOLKWAYS_VISUAL_IMAGES_ONLY === "1") return;

    const report = await probe.lint(scope, options);
    this.lints.push(report);
    this.write();
    return report;
  }

  expectLintClean() {
    if (process.env.FOLKWAYS_VISUAL_IMAGES_ONLY === "1") return;
    const dirty = this.lints.filter((report) => report.errors > 0);
    expect(
      dirty.map((report) => report.scope),
      `${this.scope}: these pages have broken layout:\n${dirty.map(formatReport).join("\n")}`,
    ).toEqual([]);
  }

  expectLinted(scopes: string[]) {
    if (process.env.FOLKWAYS_VISUAL_IMAGES_ONLY === "1") return;
    const seen = new Set(this.lints.map((report) => report.scope));
    const missing = scopes.filter((scope) => !seen.has(scope));
    expect(
      missing,
      `${this.scope}: these pages were never linted. "There is a screenshot" does not mean "someone will notice it is broken". `
        + `Actually checked: ${JSON.stringify([...seen])}`,
    ).toEqual([]);
  }

  async hover(client: MinecraftClient, token: string, frameCount = 4) {
    const rect = await tokenRect(client, token);
    if (!rect) {
      return null;
    }
    await input.move(client, {
      x: Math.round(rect.x + rect.width / 2),
      y: Math.round(rect.y + rect.height / 2),
    });
    await frames(client, frameCount);
    return rect;
  }

  note(key: string, value: unknown) {
    this.context[key] = value;
    this.write();
  }

  write() {
    const doc = {
      scope: this.scope,
      root: this.root,
      generated_at: new Date().toISOString(),
      context: this.context,
      entries: this.entries,
      lints: this.lints,
    };
    writeFileSync(path.join(this.dir, "index.json"), JSON.stringify(doc, null, 2) + "\n");
  }
}

async function tokenRect(client: MinecraftClient, token: string) {
  const reads = [
    async () => (await panelElements(client)).find((element) => element.id === token),
    async () => (await screens.widgets(client)).find((widget) => widget.id === token),
  ];
  for (const read of reads) {
    const hit = await read().catch(() => undefined);
    if (hit) return { x: hit.x, y: hit.y, width: hit.width, height: hit.height };
  }
  return null;
}

function holdRealSeconds(seconds: number) {
  if (!(seconds > 0)) return Promise.resolve();
  return new Promise((resolve) => setTimeout(resolve, Math.round(seconds * 1000)));
}

async function safe(fn: () => Promise<any>) {
  try {
    return await fn();
  } catch (error) {
    return { status: "error", reason: error instanceof Error ? error.message : String(error) };
  }
}

export async function gameTime(server: MinecraftServer): Promise<number> {
  const result: Partial<world.Time> = await world.time(server, { dimension: "minecraft:overworld" }).catch(() => ({}));
  return typeof result?.gameTime === "number" ? result.gameTime : 0;
}

export function fileSize(abs: string) {
  try {
    return statSync(abs).size;
  } catch {
    return 0;
  }
}

export function probeVideo(abs: string): { durationS: number | null; blackFraction: number; blackCheck: string } {
  if (!existsSync(abs)) return { durationS: null, blackFraction: 0, blackCheck: "missing" };
  let durationS: number | null = null;
  try {
    const out = execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", abs], {
      encoding: "utf8",
      timeout: 60_000,
    });
    const parsed = Number.parseFloat(out.trim());
    durationS = Number.isFinite(parsed) ? parsed : null;
  } catch {
    durationS = null;
  }
  const run = spawnSync(
    "ffmpeg",
    ["-hide_banner", "-nostats", "-i", abs, "-vf", "blackdetect=d=0.2:pix_th=0.10", "-an", "-f", "null", "-"],
    { encoding: "utf8", timeout: 120_000 },
  );
  if (run.error) return { durationS, blackFraction: 0, blackCheck: "unavailable" };
  let black = 0;
  for (const match of String(run.stderr ?? "").matchAll(/black_duration:\s*([0-9.]+)/g)) {
    black += Number.parseFloat(match[1]);
  }
  if (durationS == null || durationS <= 0) return { durationS, blackFraction: 0, blackCheck: "no-duration" };
  return { durationS, blackFraction: Math.min(1, black / durationS), blackCheck: "ok" };
}

function round(value: number) {
  return Math.round(value * 100) / 100;
}

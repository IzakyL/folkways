import { BlockwrightDriver, type BlockwrightInstance } from "@izakyl/blockwright-client";
import { defineTask, javaTask, minecraft, tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import type { TestInfo } from "@playwright/test";
import { appendFileSync, writeFileSync } from "node:fs";
import { REPO_ROOT, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { newRunId } from "../shared/run-id";
import { campaignMs, campaignSeed, fuzzShards, keep, readCorpus } from "./campaign";

// A live fuzz target runs on a server of its own (no client: nothing here is looked at). Each case lays a real
// colony out on one of the server's plots and the server ticks it as it would in play; tests/fuzz/java
// (io.github.izakyl.folkways.fuzz.Live) lays the cases, lands their disturbances and judges them. This side picks
// seeds, keeps up to PLOTS cases running side by side on each of a target's servers (FUZZ_SHARDS of them, one
// tick thread apiece), advances their ticks, shrinks what broke and keeps it.

const CONFIG = "blockwright.toml#fuzz";
/** Plots one server runs side by side (Plot.RING). */
const PLOTS = 8;
/** Ticks between two looks at the running cases: their disturbances land on these boundaries. */
const SEGMENT = 20;
/** Wall time a failing case may spend being shrunk. */
const SHRINK_MS = 4 * 60_000;
/** Shrink candidates tried at most, per failing case. */
const SHRINK_TRIES = 48;
/** Wall time a server with nothing to run waits before it looks for work again. */
const IDLE_MS = 250;

export type Target = "build" | "economy" | "paths";

type Case = Record<string, unknown>;

interface Violation {
  kind: string;
  detail: string;
}

const LIVE_IMPORTS = ["io.github.izakyl.folkways.fuzz.Live"];

const liveTask = <A, R>(name: string, call: string, timeoutMs: number) => defineTask<A, R>({
  name: `folkways.fuzz.live.${name}`,
  side: "server",
  timeoutMs,
  limits: { maxDepth: 32, maxNodes: 1_000_000, maxItems: 100_000, maxString: 1_000_000 },
  source: javaTask({ imports: LIVE_IMPORTS, body: `return ${call};` }),
});

const OPEN = liveTask<Record<string, unknown>, { id: string; case: Case; opened: boolean; budget?: number;
  violations?: Violation[] }>("open", "Live.open(ctx.server(), (Map<String, Object>) ctx.args())", 60_000);
const STEP = liveTask<Record<string, unknown>, Record<string, { state: string }>>(
  "step", "Live.step(ctx.server(), (Map<String, Object>) ctx.args())", 60_000);
const CLOSE = liveTask<Record<string, unknown>, { violations: Violation[]; summary?: unknown }>(
  "close", "Live.close(ctx.server(), (Map<String, Object>) ctx.args())", 60_000);
const CANDIDATES = liveTask<Record<string, unknown>, { candidates: Case[] }>(
  "candidates", "Live.candidates((Map<String, Object>) ctx.args())", 60_000);

export interface FuzzServer {
  server: MinecraftServer;
  close(): Promise<void>;
}

export interface FuzzServers {
  servers: MinecraftServer[];
  close(): Promise<void>;
}

/** Launches the target's FUZZ_SHARDS servers side by side; if one fails to come up, the rest are closed again. */
export async function launchFuzzServers(target: Target, shards = fuzzShards()): Promise<FuzzServers> {
  const launched = await Promise.allSettled(Array.from({ length: shards }, (_, shard) =>
    launchFuzzServer(target, shards > 1 ? `${target}-server-${shard}` : `${target}-server`)));
  const up = launched.flatMap((one) => (one.status === "fulfilled" ? [one.value] : []));
  const close = async () => {
    await Promise.allSettled(up.map((one) => one.close()));
  };
  const failed = launched.find((one): one is PromiseRejectedResult => one.status === "rejected");
  if (failed) {
    await close();
    throw failed.reason;
  }
  return { servers: up.map((one) => one.server), close };
}

export async function launchFuzzServer(target: Target, trace = `${target}-server`): Promise<FuzzServer> {
  const driver = new BlockwrightDriver({ cwd: REPO_ROOT });
  let instance: BlockwrightInstance | undefined;
  try {
    // Fuzz servers sprint, and sprinting has no tick deadline to budget against, so the engine runs single-threaded
    // throughout: every tick does its whole work, and a case replays the same way on any machine.
    instance = await driver.launchNeoForge("server", {
      config: CONFIG,
      instance: seededServer(`fuzz-${target}-${newRunId("FOLKWAYS_FUZZ_RUN_ID")}`),
      reuseInstanceDir: true,
      trace: tracePath("fuzz", trace),
    });
    await instance.waitForReady({ timeoutMs: 240_000 });
  } catch (error) {
    await instance?.close().catch(() => undefined);
    driver.close();
    throw error;
  }
  const launched = instance;
  return {
    server: minecraft.server(launched),
    async close() {
      try {
        await launched.close();
      } finally {
        driver.close();
      }
    },
  };
}

/** What a lane is running: a corpus case replayed, a fresh case drawn, or a candidate for shrinking one. */
type Job =
  | { kind: "replay"; name: string; case: Case }
  | { kind: "fresh"; index: number }
  | { kind: "shrink"; shrink: Shrinking; case: Case };

interface Shrinking {
  kind: string;
  best: Case;
  detail: string;
  found: { seed: string; index: number };
  queue: Case[];
  tries: number;
  taken: number;
  started: number;
  /** Bumped each time a smaller failing case is taken: candidates of an older generation are moot. */
  generation: number;
  running: number;
  done: boolean;
}

interface Lane {
  server: MinecraftServer;
  plot: number;
  job: Job;
  generation?: number;
  id: string;
  case: Case;
  budget: number;
  elapsed: number;
  state: string;
}

export interface CampaignReport {
  target: Target;
  seed: string;
  servers: number;
  budgetMs: number;
  replayed: number;
  ran: number;
  ticks: number;
  seen: Record<string, number>;
  stillBroken: Array<{ name: string; kind: string; detail: string }>;
  mended: string[];
  found: Array<{ name: string; kind: string; seed: string; index: number; detail: string;
    shrink: { tries: number; taken: number } }>;
}

/**
 * Replays the target's corpus, then draws fresh cases for FUZZ_MINUTES, PLOTS at a time on each server. Every
 * server runs a loop of its own over one shared queue of jobs, so the servers tick side by side. A case
 * that breaks a property for the first time is shrunk on the free plots, kept in the corpus and fails the test;
 * a corpus case that no longer breaks is reported mended (or flaky: these runs are real, so not every one repeats).
 */
export async function campaign(servers: MinecraftServer[], target: Target, testInfo: TestInfo): Promise<CampaignReport> {
  const seed = campaignSeed();
  const budgetMs = campaignMs();
  const report: CampaignReport = {
    target, seed, servers: servers.length, budgetMs, replayed: 0, ran: 0, ticks: 0, seen: {}, stillBroken: [],
    mended: [], found: [],
  };
  const replays: Job[] = readCorpus(target).map((entry) => ({ kind: "replay", name: entry.name, case: entry.case as Case }));
  const known = new Set<string>();
  const shrinking: Shrinking[] = [];
  const started = Date.now();
  let next = 0;
  // Every case judged, one JSON line apiece: what it was, what broke and how it went (its run's summary).
  const cases = outAbs("fuzz", "reports", `${target}-cases.ndjson`);
  writeFileSync(cases, "");

  const nextJob = (): Job | undefined => {
    const replay = replays.shift();
    if (replay) return replay;
    for (const one of shrinking) {
      if (one.done) continue;
      const out = Date.now() - one.started > SHRINK_MS || one.tries >= SHRINK_TRIES;
      if (out) {
        if (one.running === 0) finishShrink(one);
        continue;
      }
      const candidate = one.queue.shift();
      if (candidate) {
        one.tries++;
        one.running++;
        return { kind: "shrink", shrink: one, case: candidate };
      }
      if (one.running === 0) finishShrink(one);
    }
    if (Date.now() - started < budgetMs) return { kind: "fresh", index: next++ };
    return undefined;
  };

  const finishShrink = (one: Shrinking) => {
    one.done = true;
    const name = keep({
      target, kind: one.kind, detail: one.detail, case: one.best,
      found: { seed: one.found.seed, index: one.found.index, on: new Date().toISOString() },
    });
    report.found.push({ name, kind: one.kind, seed: one.found.seed, index: one.found.index, detail: one.detail,
      shrink: { tries: one.tries, taken: one.taken } });
  };

  const open = async (server: MinecraftServer, plot: number, job: Job): Promise<Lane | undefined> => {
    const args: Record<string, unknown> = { target, plot, id: `${target}@${plot}` };
    if (job.kind === "fresh") Object.assign(args, { seed, index: job.index });
    else args.case = job.case;
    const opened = await server.run(OPEN, args, { timeoutMs: OPEN.timeoutMs });
    const lane: Lane = { server, plot, job, id: opened.id, case: opened.case, budget: opened.budget ?? 0, elapsed: 0,
      state: "running", generation: job.kind === "shrink" ? job.shrink.generation : undefined };
    if (!opened.opened) {
      await judged(lane, opened.violations ?? []);
      return undefined;
    }
    return lane;
  };

  const judged = async (lane: Lane, violations: Violation[], summary?: unknown) => {
    report.ran++;
    appendFileSync(cases, JSON.stringify({ job: lane.job.kind, name: lane.job.kind === "replay" ? lane.job.name : undefined,
      index: lane.job.kind === "fresh" ? lane.job.index : undefined, at: Date.now() - started, elapsed: lane.elapsed, case: lane.case,
      violations, summary }) + "\n");
    for (const violation of violations) report.seen[violation.kind] = (report.seen[violation.kind] ?? 0) + 1;
    const job = lane.job;
    if (job.kind === "replay") {
      report.replayed++;
      if (violations.length === 0) report.mended.push(job.name);
      for (const violation of violations) {
        known.add(violation.kind);
        report.stillBroken.push({ name: job.name, kind: violation.kind, detail: violation.detail });
      }
      return;
    }
    if (job.kind === "shrink") {
      const one = job.shrink;
      one.running--;
      const same = violations.find((violation) => violation.kind === one.kind);
      if (same && !one.done && lane.generation === one.generation) {
        one.best = lane.case;
        one.detail = same.detail;
        one.taken++;
        one.generation++;
        one.queue = await candidates(lane.server, one.best);
      }
      return;
    }
    for (const violation of violations) {
      if (known.has(violation.kind)) continue;
      known.add(violation.kind);
      const fresh: Shrinking = {
        kind: violation.kind, best: lane.case, detail: violation.detail, found: { seed, index: job.index },
        queue: await candidates(lane.server, lane.case), tries: 0, taken: 0, started: Date.now(), generation: 0, running: 0,
        done: false,
      };
      shrinking.push(fresh);
    }
  };

  const candidates = async (server: MinecraftServer, kase: Case): Promise<Case[]> =>
    (await server.run(CANDIDATES, { case: kase, limit: 64 }, { timeoutMs: CANDIDATES.timeoutMs })).candidates;

  const drive = async (server: MinecraftServer) => {
    const lanes: (Lane | undefined)[] = Array.from({ length: PLOTS }, () => undefined);
    for (;;) {
      for (let plot = 0; plot < PLOTS; plot++) {
        if (lanes[plot]) continue;
        const job = nextJob();
        if (!job) break;
        lanes[plot] = await open(server, plot, job);
      }
      const running = lanes.filter((lane): lane is Lane => lane !== undefined);
      if (running.length === 0) {
        if (!nextJobPending(shrinking, replays, started, budgetMs)) break;
        // Nothing to run here yet, but another server's shrinking may still queue candidates: wait for them.
        await new Promise((resolve) => setTimeout(resolve, IDLE_MS));
        continue;
      }
      await tick.sprint(server, SEGMENT);
      report.ticks += SEGMENT;
      for (const lane of running) lane.elapsed += SEGMENT;
      const states = await server.run(STEP, {
        ids: running.map((lane) => lane.id),
        elapsed: Object.fromEntries(running.map((lane) => [lane.id, lane.elapsed])),
      }, { timeoutMs: STEP.timeoutMs });
      for (const lane of running) {
        const state = states[lane.id]?.state ?? "gone";
        if (state === "running" && lane.elapsed < lane.budget) continue;
        const closed = await server.run(CLOSE, { id: lane.id, settled: state === "settled", elapsed: lane.elapsed },
          { timeoutMs: CLOSE.timeoutMs });
        lanes[lane.plot] = undefined;
        await judged(lane, closed.violations, closed.summary);
      }
    }
  };
  await Promise.all(servers.map(drive));
  for (const one of shrinking) if (!one.done) finishShrink(one);

  const file = outAbs("fuzz", "reports", `${target}.json`);
  writeFileSync(file, JSON.stringify(report, null, 2) + "\n");
  await testInfo.attach(`${target}-report.json`, { path: file, contentType: "application/json" });
  return report;
}

function nextJobPending(shrinking: Shrinking[], replays: Job[], started: number, budgetMs: number): boolean {
  return replays.length > 0 || Date.now() - started < budgetMs || shrinking.some((one) => !one.done);
}

/** The failure message a broken campaign fails its test with: what broke, and how to see it again. */
export function verdict(report: CampaignReport): string | null {
  if (report.stillBroken.length === 0 && report.found.length === 0) return null;
  const lines = [`fuzz ${report.target}: ${report.found.length} new and ${report.stillBroken.length} known failures`
    + ` (seed ${report.seed}, ${report.ran} cases over ${report.ticks} ticks on ${report.servers} servers,`
    + ` ${report.replayed} replayed)`];
  for (const found of report.found) {
    lines.push(`\n[new] ${found.kind}  -> tests/fuzz/corpus/${report.target}/${found.name}.json`
      + `  (FUZZ_SEED=${found.seed}, case #${found.index}, shrunk ${found.shrink.taken}/${found.shrink.tries})\n${indent(found.detail)}`);
  }
  for (const broken of report.stillBroken) {
    lines.push(`\n[corpus] ${broken.kind}  -> tests/fuzz/corpus/${report.target}/${broken.name}.json\n${indent(broken.detail)}`);
  }
  return lines.join("\n");
}

function indent(text: string): string {
  return text.split("\n").slice(0, 30).map((line) => `    ${line}`).join("\n");
}

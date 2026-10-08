import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { REPO_ROOT } from "../out-paths";

// The live fuzzers: each target lays real colonies out on a server's plots and lets the server tick them as in
// play (tests/fuzz/java, io.github.izakyl.folkways.fuzz.Live), up to eight cases side by side. A case that broke a
// property is shrunk, kept in tests/fuzz/corpus/<target>/ and replayed first on every later run, so a fixed bug
// stays fixed. `npm run fuzz:run -- --grep build` runs one target.
//
// How a run is steered, all from the environment so `npm run fuzz:run` needs no arguments:
//   FUZZ_MINUTES  minutes of drawing fresh cases per target (default 2; 0 = replay the corpus only); cases already
//                 running when the time is up finish, and a failure found is shrunk for up to four minutes more
//   FUZZ_SEED     the run's seed, a signed 64-bit decimal (default random); a failure names the seed and index
//                 that drew it, and FUZZ_SEED=<seed> with the same target draws the same cases again. The cases
//                 run in a real world, so the same case need not go the same way twice.
//   FUZZ_SHARDS   servers (JVMs) each target runs its cases on, eight plots apiece (default: what the machine
//                 holds, see fuzzShards); a server ticks on one thread, so more of them is what uses more cores

export function campaignMs(): number {
  const minutes = Number(process.env.FUZZ_MINUTES ?? "2");
  return Math.max(0, Number.isFinite(minutes) ? minutes : 2) * 60_000;
}

/** GiB of heap one fuzz server is given (blockwright.toml, profile.fuzz) plus what its JVM takes besides. */
const SERVER_GIB = 4;
/** Targets run at once (fuzz.config.ts workers). */
const TARGETS_AT_ONCE = 3;

/**
 * Servers per target: FUZZ_SHARDS, or as many as the machine holds with every target running at once, each
 * server wanting about two cores (its tick thread, and the colony solver and GC beside it) and SERVER_GIB.
 */
export function fuzzShards(): number {
  const given = process.env.FUZZ_SHARDS?.trim();
  if (given) {
    const shards = Number(given);
    if (!Number.isInteger(shards) || shards < 1) throw new Error(`FUZZ_SHARDS must be a positive integer, not ${given}`);
    return shards;
  }
  const byCores = Math.floor(os.availableParallelism() / (2 * TARGETS_AT_ONCE));
  const byMemory = Math.floor(os.totalmem() / 2 ** 30 / (SERVER_GIB * TARGETS_AT_ONCE));
  return Math.max(1, Math.min(byCores, byMemory));
}

export function campaignSeed(): string {
  const given = process.env.FUZZ_SEED?.trim();
  if (given) {
    if (!/^-?\d+$/.test(given)) throw new Error(`FUZZ_SEED must be a 64-bit decimal integer, not ${given}`);
    return BigInt.asIntN(64, BigInt(given)).toString();
  }
  return randomBytes(8).readBigInt64BE().toString();
}

/** A case that once broke a property, kept under tests/fuzz/corpus/<target>/ and replayed before every run. */
export interface CorpusEntry {
  name: string;
  target: string;
  kind: string;
  found: { seed?: string; index?: number; on: string };
  detail: string;
  case: unknown;
}

export function corpusDir(target: string): string {
  return path.join(REPO_ROOT, "tests", "fuzz", "corpus", target);
}

export function readCorpus(target: string): CorpusEntry[] {
  let files: string[];
  try {
    files = readdirSync(corpusDir(target)).filter((file) => file.endsWith(".json")).sort();
  } catch {
    return [];
  }
  return files.map((file) => {
    const entry = JSON.parse(readFileSync(path.join(corpusDir(target), file), "utf8")) as CorpusEntry;
    return { ...entry, name: file.replace(/\.json$/, "") };
  });
}

/** Keeps a newly found failure; the file is named after the property and the case, so a rerun overwrites it. */
export function keep(entry: Omit<CorpusEntry, "name">): string {
  const slug = entry.kind.replace(/[^0-9A-Za-z]+/g, "-").replace(/^-|-$/g, "").toLowerCase().slice(0, 60);
  const hash = createHash("sha256").update(JSON.stringify(entry.case)).digest("hex").slice(0, 10);
  const name = `${slug}-${hash}`;
  mkdirSync(corpusDir(entry.target), { recursive: true });
  writeFileSync(path.join(corpusDir(entry.target), `${name}.json`), JSON.stringify({ name, ...entry }, null, 2) + "\n");
  return name;
}

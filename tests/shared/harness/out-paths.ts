import { mkdirSync } from "node:fs";
import path from "node:path";

export const REPO_ROOT = process.cwd();
export const OUT_ROOT = "tests/out";

export type Suite = "e2e" | "visual" | "promo" | "bench" | "fuzz";
export type Bucket = "traces" | "shots" | "reports" | "takes" | "film" | "keep" | "playwright";

const BUCKETS: Record<Suite, Bucket[]> = {
  e2e: ["traces", "shots", "reports"],
  visual: ["traces", "takes"],
  promo: ["traces", "takes", "film"],
  bench: ["traces", "reports"],
  fuzz: ["traces", "reports"],
};

export function outPath(suite: Suite, bucket: Bucket, ...rest: string[]): string {
  return path.posix.join(OUT_ROOT, suite, bucket, ...rest);
}

export function outAbs(suite: Suite, bucket: Bucket, ...rest: string[]): string {
  return path.join(REPO_ROOT, OUT_ROOT, suite, bucket, ...rest);
}

export function ensureOut(suite: Suite): void {
  for (const bucket of BUCKETS[suite]) {
    mkdirSync(outAbs(suite, bucket), { recursive: true });
  }
}

export function tracePath(suite: Suite, name: string): string {
  return outPath(suite, "traces", `${name}.ndjson`);
}

export function shotPath(suite: Suite, name: string): string {
  return outPath(suite, "shots", `${name}.png`);
}

export function reportPath(suite: Suite, name: string): string {
  return outPath(suite, "reports", `${name}.json`);
}

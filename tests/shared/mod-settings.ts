import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT } from "../out-paths";

export type EngineMode = "DEFAULT" | "SINGLE_THREAD";

export type ModSettings = {
  mode: EngineMode;
  zones?: Record<string, number>;
};

const seeded = new Set<string>();

// Blockwright clears an instance dir at launch unless asked to keep it, which would drop what was seeded.
export function markSeeded(instance: string): void {
  seeded.add(instance);
}

export function isSeeded(instance: string | undefined): boolean {
  return instance !== undefined && seeded.has(instance);
}

export function instanceConfigDir(instance: string): string {
  return path.join(REPO_ROOT, ".blockwright", "instances", instance, "config");
}

export function seedModSettings(instance: string, settings: ModSettings): string {
  const dir = instanceConfigDir(instance);
  mkdirSync(dir, { recursive: true });
  markSeeded(instance);
  const file = path.join(dir, "folkways-server.toml");
  const zones = Object.entries(settings.zones ?? {}).map(([key, value]) => `\t${key} = ${value}\n`).join("");
  writeFileSync(file, `[planning]\n\tmode = "${settings.mode}"\n` + (zones ? `[zones]\n${zones}` : ""));
  return file;
}

export function seededServer(instance: string, zones?: Record<string, number>): string {
  seedModSettings(instance, { mode: "SINGLE_THREAD", zones });
  return instance;
}

export function seededOffThreadServer(instance: string, zones?: Record<string, number>): string {
  seedModSettings(instance, { mode: "DEFAULT", zones });
  return instance;
}

import { instanceDirOf, seedInstance } from "@izakyl/blockwright-client";
import path from "node:path";
import { REPO_ROOT } from "./out-paths";

export type EngineMode = "DEFAULT" | "SINGLE_THREAD";

export type ModSettings = {
  mode: EngineMode;
  zones?: Record<string, number>;
  /** Recipe ids residents never use (crafting.refusedRecipes). */
  refusedRecipes?: string[];
  /** Settings of the core's labor section, by key (planHorizonTicks, planHorizonDepth...). */
  labor?: Record<string, number>;
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
  return path.join(instanceDirOf(instance, REPO_ROOT), "config");
}

export function seedModSettings(instance: string, settings: ModSettings): string {
  const seed = seedInstance(instance, { cwd: REPO_ROOT });
  markSeeded(instance);
  const zones = Object.entries(settings.zones ?? {}).map(([key, value]) => `\t${key} = ${value}\n`).join("");
  const refused = settings.refusedRecipes?.length
    ? `[crafting]\n\trefusedRecipes = [${settings.refusedRecipes.map((id) => `"${id}"`).join(", ")}]\n` : "";
  const labor = Object.entries(settings.labor ?? {}).map(([key, value]) => `\t${key} = ${value}\n`).join("");
  // Planning and labour are the core's settings; zones and crafting are Folkways Vanilla's.
  seed.write("config/folkways_vanilla-server.toml", (zones ? `[zones]\n${zones}` : "") + refused);
  return seed.write("config/folkways_core-server.toml", `[planning]\n\tmode = "${settings.mode}"\n`
    + (labor ? `[labor]\n${labor}` : ""));
}

export function seededServer(instance: string, zones?: Record<string, number>): string {
  seedModSettings(instance, { mode: "SINGLE_THREAD", zones });
  return instance;
}

export function seededOffThreadServer(instance: string, zones?: Record<string, number>,
  refusedRecipes?: string[], labor?: Record<string, number>): string {
  seedModSettings(instance, { mode: "DEFAULT", zones, refusedRecipes, labor });
  return instance;
}

import { instanceDirOf } from "@izakyl/blockwright-client";
import { seedShaderPack, shaderEvidence as irisEvidence, type ShaderPatch } from "@izakyl/blockwright-cinema";
import { existsSync, readFileSync, rmSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT } from "../out-paths";
import { markSeeded } from "../shared/mod-settings";

const CONFIG = path.join(REPO_ROOT, "blockwright.toml");
const MODS = path.join(REPO_ROOT, ".blockwright", "mods.json");

function modFilename(name: string): string {
  const url = new RegExp(`^\\[mods\\.${name}\\]\\n(?:.*\\n)*?url *= *"(.*)"$`, "m").exec(readFileSync(CONFIG, "utf8"))?.[1];
  if (!url) throw new Error(`Could not read the url of mods.${name} from ${CONFIG}`);
  return decodeURIComponent(url.slice(url.lastIndexOf("/") + 1));
}

const SHADER_PACK = modFilename("shaderpack");

const PACK_DIR = SHADER_PACK.replace(/\.zip$/, "") + "-promo";

export type Patch = ShaderPatch;

// The pack's translucent pass keeps only the texture's alpha, so faded build ghosts film as solid blocks.
const PATCHES: Patch[] = [
  {
    file: "shaders/program/gbuffers_water.glsl",
    find: "vec4 color = colorP * vec4(glColor.rgb, 1.0);",
    replace: "vec4 color = colorP * glColor;",
  },
];

function instanceDir(instance: string): string {
  return instanceDirOf(instance, REPO_ROOT);
}

// options are the pack's own settings, as its Shader Options screen would save them (shaderpacks/<pack>.txt);
// patches are further edits to its sources, after the ones every film takes.
export function seedShaders(instance: string, on = process.env.FOLKWAYS_PROMO_SHADERS !== "0", options: Record<string, string | number> = {},
  patches: Patch[] = []): void {
  const mods = existsSync(MODS) ? (JSON.parse(readFileSync(MODS, "utf8")) as Record<string, string[]>) : {};
  const zip = mods.shaderpack?.[0];
  if (!zip || !existsSync(zip)) {
    throw new Error(`Shader pack is not in ${MODS}: run  npm run setup  in the repo root first`);
  }
  const dir = instanceDir(instance);
  if (existsSync(path.join(dir, "blockwright-instance.json"))) rmSync(dir, { recursive: true, force: true });

  markSeeded(instance);
  seedShaderPack(dir, { zip, name: PACK_DIR, options, patches: [...PATCHES, ...patches], enabled: on });
}

// A pack that fails to compile falls back to vanilla without stopping the game: this throws unless the client's log
// says Iris loaded the pack and built its pipeline.
export function shaderEvidence(instance: string, on = process.env.FOLKWAYS_PROMO_SHADERS !== "0"): { pack: string; iris: string[] } {
  if (!on) return { pack: "off", iris: [] };
  return { pack: SHADER_PACK, iris: irisEvidence(instanceDir(instance), PACK_DIR).iris };
}

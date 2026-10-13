import { instanceDirOf } from "@izakyl/blockwright-client";
import { seedShaderPack, shaderEvidence as irisEvidence, type ShaderPatch } from "@izakyl/blockwright-cinema";
import { existsSync, readFileSync, rmSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT } from "../harness/out-paths";
import { markSeeded } from "../harness/mod-settings";

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

// The cameras look down on the land from high up: the pack's own clouds (which the game's cloud setting does not
// reach) would cover it, and its haze and the fog at the edge of the loaded land would wash it out.
export const FILM_LOOK = { CLOUD_STYLE_DEFINE: 0, ATM_FOG_MULT: "0.50", ATM_FOG_DISTANCE: 300, BORDER_FOG: "false" };

// The pack's depth of field, focused on what stands at the middle of the picture, and its tonemap's colour.
export const MINIATURE_LOOK = { WORLD_BLUR: 2, WB_DOF_FOCUS: 0, T_SATURATION: "1.15", T_VIBRANCE: "1.10", TM_CONTRAST: "1.10" };

// The pack blurs by the gap in the depth buffer, which is next to nothing between things hundreds of blocks off.
// A lens close to a model blurs by how far off a thing is against the distance in focus, and a tilted one blurs the
// top and the bottom of the picture besides: both here, with a wider blur than the pack's own.
const DOF = "shaders/program/composite3.glsl";
export const MINIATURE_LENS: Patch[] = [
  { file: DOF, find: "float coc = max(abs(z1 - centerDepthSmooth) * 0.125 * WB_DOF_I - 0.0001, 0.0);",
    replace: `vec4 focusPos = gbufferProjectionInverse * vec4(0.0, 0.0, centerDepthSmooth * 2.0 - 1.0, 1.0);
                float focus = abs(focusPos.z / focusPos.w);
                float coc = max(abs(focus / max(lViewPos0, 0.1) - 1.0) - 0.12, 0.0) * 0.3;
                coc = max(coc, smoothstep(0.22, 0.5, abs(texCoord.y - 0.5)) * 0.2);` },
  { file: DOF, find: "vec2 dofScale = vec2(1.0, aspectRatio);", replace: "vec2 dofScale = vec2(1.0, aspectRatio) * 1.5;" },
  { file: DOF, find: "coc * 0.75 / 320.0", replace: "coc * 0.75 / 215.0" },
];

// The same lens, but only on the view from above: out of the player's own eyes the picture stays sharp throughout. Iris
// tells a pack nothing of whose eyes the camera is in, so a take says it through the player's most health, which
// the pack is told and nothing in the picture shows: LENS_ON on going up, LENS_OFF on coming down. A player in
// creative, who has no health to tell, is always under the lens.
export const OVERHEAD_LENS: Patch[] = [
  ...MINIATURE_LENS,
  { file: DOF, find: "coc = max(coc, smoothstep(0.22, 0.5, abs(texCoord.y - 0.5)) * 0.2);",
    replace: `coc = max(coc, smoothstep(0.22, 0.5, abs(texCoord.y - 0.5)) * 0.2);
                coc *= float(maxPlayerHealth < 0.0 || maxPlayerHealth > 20.5);` },
  { file: DOF, find: "            uniform float centerDepthSmooth;",
    replace: "            uniform float centerDepthSmooth;\n            uniform float maxPlayerHealth;" },
];
export const LENS_ON = "minecraft:generic.max_health base set 21";
export const LENS_OFF = "minecraft:generic.max_health base set 20";

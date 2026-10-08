import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { gunzipSync } from "node:zlib";
import { REPO_ROOT } from "../out-paths";
import { markSeeded } from "../shared/mod-settings";

const CONFIG = path.join(REPO_ROOT, "blockwright.toml");
const MODS = path.join(REPO_ROOT, ".blockwright", "mods.json");

const INSTANCES = path.join(REPO_ROOT, ".blockwright", "instances");

function modFilename(name: string): string {
  const url = new RegExp(`^\\[mods\\.${name}\\]\\n(?:.*\\n)*?url *= *"(.*)"$`, "m").exec(readFileSync(CONFIG, "utf8"))?.[1];
  if (!url) throw new Error(`Could not read the url of mods.${name} from ${CONFIG}`);
  return decodeURIComponent(url.slice(url.lastIndexOf("/") + 1));
}

const SHADER_PACK = modFilename("shaderpack");

const PACK_DIR = SHADER_PACK.replace(/\.zip$/, "") + "-promo";

// The pack's translucent pass keeps only the texture's alpha, so faded build ghosts film as solid blocks.
const PATCHES = [
  {
    file: "shaders/program/gbuffers_water.glsl",
    find: "vec4 color = colorP * vec4(glColor.rgb, 1.0);",
    replace: "vec4 color = colorP * glColor;",
  },
];

function instanceDir(instance: string): string {
  return path.join(INSTANCES, instance);
}

export function seedShaders(instance: string): void {
  const mods = existsSync(MODS) ? (JSON.parse(readFileSync(MODS, "utf8")) as Record<string, string[]>) : {};
  const zip = mods.shaderpack?.[0];
  if (!zip || !existsSync(zip)) {
    throw new Error(`Shader pack is not in ${MODS}: run  npm run setup  in the repo root first`);
  }
  const dir = instanceDir(instance);
  if (existsSync(path.join(dir, "blockwright-instance.json"))) rmSync(dir, { recursive: true, force: true });

  markSeeded(instance);
  const pack = path.join(dir, "shaderpacks", PACK_DIR);
  mkdirSync(pack, { recursive: true });
  execFileSync("unzip", ["-oq", zip, "-d", pack]);
  for (const patch of PATCHES) {
    const file = path.join(pack, patch.file);
    const source = readFileSync(file, "utf8");
    if (!source.includes(patch.find)) throw new Error(`${SHADER_PACK} changed: ${patch.file} no longer has ${patch.find}`);
    writeFileSync(file, source.replace(patch.find, patch.replace));
  }

  mkdirSync(path.join(dir, "config"), { recursive: true });
  writeFileSync(
    path.join(dir, "config", "iris.properties"),
    [`enableShaders=${process.env.FOLKWAYS_PROMO_SHADERS !== "0"}`, `shaderPack=${PACK_DIR}`, "disableUpdateMessage=true", ""].join("\n"),
  );
}

const SAID = "[Iris/]:";
const USING = new RegExp(`^Using shaderpack: ${PACK_DIR.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`);
const PIPELINE = /^Creating pipeline for dimension/;
const BROKE = /^Failed to load|falling back to (?:internal|vanilla)|^Disabling shaders/i;

export function shaderEvidence(instance: string): { pack: string; iris: string[] } {
  if (process.env.FOLKWAYS_PROMO_SHADERS === "0") return { pack: "off", iris: [] };
  const logs = path.join(instanceDir(instance), "logs");
  const log = path.join(logs, "latest.log");
  if (!existsSync(log)) throw new Error(`Client log not found at ${log}, so there is no way to confirm the shaders compiled`);

  // latest.log rolls into <date>-<n>.log.gz at midnight, taking the startup lines with it.
  const rolled = readdirSync(logs)
    .filter((name) => /^\d{4}-\d{2}-\d{2}-\d+\.log\.gz$/.test(name))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .map((name) => gunzipSync(readFileSync(path.join(logs, name))).toString("utf8"));
  const iris = [...rolled, readFileSync(log, "utf8")]
    .join("\n")
    .split("\n")
    .filter((line) => line.includes(SAID))
    .map((line) => line.slice(line.indexOf(SAID) + SAID.length).trim());

  const broke = iris.filter((line) => BROKE.test(line));
  if (broke.length > 0) throw new Error(`Iris fell back to vanilla shaders; ${SHADER_PACK} did not compile:\n${broke.join("\n")}`);
  const using = iris.some((line) => USING.test(line));
  const piped = iris.some((line) => PIPELINE.test(line));
  if (!using || !piped) {
    throw new Error(
      `This run filmed without shaders: ` +
        `Iris ${using ? "loaded" : "did not load"} ${SHADER_PACK} and ${piped ? "built" : "did not build"} the render pipeline.\n` +
        (iris.length > 0 ? `Iris said:\n${iris.join("\n")}` : "Iris logged nothing. Is the jar installed?"),
    );
  }
  return { pack: SHADER_PACK, iris };
}

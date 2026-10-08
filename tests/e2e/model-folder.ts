import { cpSync, existsSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, reflect, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";

export const MODEL_ASSETS = path.join(process.cwd(), "tests/e2e/assets/resident-models");

export type ModelManifest = {
  weight?: number;
  idle_animation?: string;
  walk_animation?: string;
  work_animation?: string;
};

const MODEL_PACK = "folkways-e2e-models";

/** Where the server world keeps datapacks, and the data pack format it reads. */
const DATAPACK_DIR = defineTask<void, { dir?: unknown; format?: unknown }>({
  name: "folkways.models.datapack-dir",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.SharedConstants",
      "net.minecraft.server.packs.PackType",
      "net.minecraft.world.level.storage.LevelResource",
    ],
    body: `
return Map.of(
    "dir", ctx.server().getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().toString(),
    "format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
`,
  }),
});

async function modelPackRoot(server: MinecraftServer) {
  const value = await server.run(DATAPACK_DIR, undefined, { timeoutMs: 20_000 });
  if (typeof value?.dir !== "string") {
    throw new Error(`cannot read the server world's datapacks folder: ${JSON.stringify(value)}`);
  }
  const pack = path.join(value.dir, MODEL_PACK);
  mkdirSync(pack, { recursive: true });
  writeFileSync(path.join(pack, "pack.mcmeta"), JSON.stringify({
    pack: { pack_format: value.format, description: "Folkways e2e resident models" },
  }, null, 2) + "\n");
  return path.join(pack, "data", "folkways", "folkways", "resident_model");
}

export async function placeServerModels(
  server: MinecraftServer, models: Array<{ source: string; name: string; manifest?: ModelManifest }>,
) {
  const root = await modelPackRoot(server);
  mkdirSync(root, { recursive: true });
  return models.map(({ source, name, manifest }) => {
    const dir = path.join(root, name);
    cpSync(source, dir, { recursive: true });
    if (manifest) {
      writeFileSync(path.join(dir, "folkways.json"), JSON.stringify(manifest, null, 2) + "\n");
    }
    return dir;
  });
}

export async function placeServerModel(server: MinecraftServer, source: string, name: string, manifest?: ModelManifest) {
  const [dir] = await placeServerModels(server, [{ source, name, manifest }]);
  return { dir, root: path.dirname(dir) };
}

export async function reloadServerModels(server: MinecraftServer) {
  await world.command(server, "reload");
}

export async function readServerLibrary(server: MinecraftServer) {
  const held = await reflect.invoke(server, {
    target: { kind: "static", className: "io.github.izakyl.folkways.plugins.person.look.ResidentModels" },
    method: "current",
    limits: { maxDepth: 4 },
  });
  const text = JSON.stringify(held ?? {});
  const entryIds = [...new Set([...text.matchAll(/folkways:model\/[a-z0-9_./-]+/g)].map((match) => match[0]))];
  entryIds.sort();
  return {
    resolved: held?.found === true,
    entryIds,
    raw: held,
  };
}

export function clientModelFile(client: MinecraftClient, packPath: string) {
  return path.join(client.instance.instanceDir, "folkways", "resident-models", packPath);
}

export function clientHasModelFile(client: MinecraftClient, packPath: string) {
  return existsSync(clientModelFile(client, packPath));
}

export async function waitForModelsOnClient(client: MinecraftClient, packPath: string, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && !clientHasModelFile(client, packPath)) {
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  const arrived = clientHasModelFile(client, packPath);
  if (!arrived) throw new Error(`model resources did not sync to the client within ${timeoutMs}ms: ${packPath}`);
  await new Promise((resolve) => setTimeout(resolve, RELOAD_SETTLE_MS));
  // settle throws (MinecraftTimeoutError) when the client does not catch up in time.
  const settled = await client.settle({ timeoutMs: 60_000 });
  return { arrived, settled };
}

const RELOAD_SETTLE_MS = 8_000;

export async function readResidentLooks(server: MinecraftServer): Promise<string[]> {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 200 })
    .catch(() => ({ entities: [] }));
  const looks: string[] = [];
  for (const entity of listed.entities ?? []) {
    if (!entity?.uuid) continue;
    const held = await reflect
      .invoke(server, { target: { kind: "entity", uuid: entity.uuid }, method: "lookId", returnType: "java.util.Optional", limits: { maxDepth: 4 } })
      .catch(() => null);
    const ids = [...JSON.stringify(held?.returned ?? {}).matchAll(/folkways:([a-z0-9_./-]+)/g)].map((match) => match[1]);
    looks.push(ids.find((id) => id !== "resident_look") ?? "");
  }
  return looks;
}

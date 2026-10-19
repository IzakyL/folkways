import { expect, test } from "@playwright/test";
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, world } from "@izakyl/blockwright-minecraft";
import { MODEL_ASSETS, waitForModelsOnClient } from "../shared/ui/model-folder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_JSON = reportPath("e2e", "library-folder-report");

const SKIN = path.join(MODEL_ASSETS, "steve", "textures", "tartaric_acid.png");
const BLUEPRINT = path.join(REPO_ROOT, "mods/vanilla/src/gametest/resources/data/folkways/folkways/blueprint/test_hut.nbt");
const PATTERN = `accepts = ["zone"]

def draw(site):
    return [part("slab", site.zone, ["minecraft:stone"])]
`;

const SKIN_PACK_PATH = "assets/local/textures/entity/resident_skins/villagers/wren.png";

test("files dropped in the server's folkways folder load as one more data pack", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = { schema_version: "0.1.0" };
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "library-folder-server"), instance: seededServer(`library-folder-server-${runId}`) },
      client: { trace: tracePath("e2e", "library-folder-client"), instance: `library-folder-client-${runId}` },
    });
    const { server, client } = pair;

    const before = await server.run(READ_LIBRARY, undefined, { timeoutMs: 20_000 });
    const root = String(before?.root);
    evidence.root = root;
    expect(before?.shelves, "every shelf has its folder made on start").toEqual(
      ["blueprint", "piece", "pattern", "resident_model", "resident_skin"].sort());

    put(path.join(root, "pattern", "folder_slab.star"), PATTERN);
    put(path.join(root, "pattern", "Bad Name.star"), PATTERN);
    copy(SKIN, path.join(root, "resident_skin", "villagers", "wren.png"));
    put(path.join(root, "resident_skin", "villagers", "wren.json"), JSON.stringify({ arms: "slim" }));
    copy(BLUEPRINT, path.join(root, "blueprint", "hut.nbt"));
    await world.command(server, "reload");

    const after = await server.run(READ_LIBRARY, undefined, { timeoutMs: 20_000 });
    evidence.after = after;
    expect(after?.pattern, "a pattern in the folder loads under local:").toBe(true);
    expect(after?.blueprint, "a blueprint in the folder is on the shelf, and no pattern").toEqual([true, false]);
    expect(after?.skin, "a skin in the folder joins the pool of looks").toBe("slim");
    expect(after?.misnamed, "a file a resource location cannot name is reported, not lost").toEqual(["Bad Name.star"]);
    expect(after?.patternPacks, "the folder's pattern is listed as the folder pack's").toContain("folkways/library");

    evidence.arrival = await waitForModelsOnClient(client, SKIN_PACK_PATH);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(path.join(REPO_ROOT, REPORT_JSON), JSON.stringify(evidence, null, 2) + "\n");
    await pair?.teardown(testInfo);
  }
});

function put(file: string, content: string) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, content);
}

function copy(from: string, to: string) {
  mkdirSync(path.dirname(to), { recursive: true });
  copyFileSync(from, to);
}

/** The folder, its shelves, and whether what was dropped in it is loaded. */
const READ_LIBRARY = defineTask<void, {
  root?: unknown; shelves?: unknown; pattern?: unknown; blueprint?: unknown; skin?: unknown;
  misnamed?: unknown; patternPacks?: unknown;
}>({
  name: "folkways.library.read",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.library.LooseFolder",
      "io.github.izakyl.folkways.library.Shelves",
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "io.github.izakyl.folkways.plugins.build.draft.Templates",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLook",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
      "java.nio.file.Files",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var shelves = new java.util.ArrayList<String>();
var misnamed = new java.util.ArrayList<String>();
var patternPacks = new java.util.ArrayList<String>();
for (Shelves.Shelf shelf : Shelves.all()) {
    if (Files.isDirectory(shelf.path())) {
        shelves.add(shelf.name());
    }
    misnamed.addAll(LooseFolder.misnamed(shelf));
    if (shelf.name().equals("pattern")) {
        patternPacks.addAll(Shelves.stock(shelf).entries().values());
    }
}
java.util.Collections.sort(shelves);
ResidentLook look = ResidentLooks.pool(ctx.server().registryAccess())
    .get(ResourceLocation.fromNamespaceAndPath("local", "skin/villagers/wren"));
return Map.of(
    "root", Shelves.root().toAbsolutePath().toString(),
    "shelves", shelves,
    "pattern", Patterns.find(ResourceLocation.fromNamespaceAndPath("local", "folder_slab")).isPresent(),
    "blueprint", java.util.List.of(
        Templates.blueprint(ResourceLocation.fromNamespaceAndPath("local", "blueprint/hut")).isPresent(),
        Patterns.find(ResourceLocation.fromNamespaceAndPath("local", "blueprint/hut")).isPresent()),
    "skin", look == null ? "missing" : ((ResidentLook.Skin) look.appearance()).arms().getSerializedName(),
    "misnamed", misnamed,
    "patternPacks", patternPacks);
`,
  }),
});

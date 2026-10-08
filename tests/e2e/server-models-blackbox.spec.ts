import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, tryCommand, waitForGroundedPlayer, type Vec } from "./colony-founding";
import {
  MODEL_ASSETS,
  clientHasModelFile,
  placeServerModel,
  readResidentLooks,
  waitForModelsOnClient,
  readServerLibrary,
  reloadServerModels,
} from "./model-folder";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_JSON = reportPath("e2e", "server-models-report");

const MODEL_NAME = "default_boy";
const MODEL_WEIGHT = 400;
const RESIDENTS = 3;

const GEOMETRY_PACK_PATH = "assets/folkways/geo/resident_models/default_boy.geo.json";
const ANIMATION_PACK_PATH = "assets/folkways/animations/resident_models/default_boy.animation.json";
const TEXTURE_PACK_PATH = "assets/folkways/textures/entity/resident_models/default_boy/blue.png";

test("a model in a server data pack dresses residents on the client", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: Record<string, unknown> = { schema_version: "0.1.0", model: MODEL_NAME };
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "server-models-server"), instance: seededServer(`server-models-server-${runId}`) },
      client: { trace: tracePath("e2e", "server-models-client"), instance: `server-models-client-${runId}` },
      snapshot: () => ({ entityTypes: ["folkways:resident"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);

    const placed = await placeServerModel(server, path.join(MODEL_ASSETS, MODEL_NAME), MODEL_NAME, { weight: MODEL_WEIGHT });
    evidence.placed = placed.dir;
    await reloadServerModels(server);

    const library = await readServerLibrary(server);
    evidence.library = { entryIds: library.entryIds, resolved: library.resolved };
    expect(library.resolved, "the static read must resolve").toBe(true);
    expect(library.entryIds, "one entry per texture, and nothing refused").toEqual([
      "folkways:model/default_boy/blue",
      "folkways:model/default_boy/red",
    ]);

    evidence.arrival = await waitForModelsOnClient(client, GEOMETRY_PACK_PATH);
    // run throws when the task does not compile or run, so reaching the read means it ran.
    const fontCache = await client.run(STALE_FONT_CACHE, undefined, { timeoutMs: 20_000 });
    evidence.font_cache = fontCache;
    expect(fontCache?.current, "font lookup must reject a stale font set replaced by a resource reload").toBe(true);
    const onDisk = {
      geometry: clientHasModelFile(client, GEOMETRY_PACK_PATH),
      animations: clientHasModelFile(client, ANIMATION_PACK_PATH),
      texture: clientHasModelFile(client, TEXTURE_PACK_PATH),
    };
    evidence.onDisk = onDisk;
    expect(onDisk, "every file the entries name has to reach the client's pack directory").toEqual({
      geometry: true,
      animations: true,
      texture: true,
    });

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENTS });
    expect(founding.residents).toBeGreaterThanOrEqual(RESIDENTS);
    await tick.sprint(server, 40);

    const looks = await readResidentLooks(server);
    evidence.looks = looks;
    expect(looks.length, "there must be residents to read").toBeGreaterThanOrEqual(RESIDENTS);
    expect(looks.filter((look) => look.startsWith("model/default_boy/")).length,
      `residents drew ${JSON.stringify(looks)}; a weight of ${MODEL_WEIGHT} per skin should dress nearly all of them`)
      .toBeGreaterThanOrEqual(RESIDENTS - 1);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(path.join(REPO_ROOT, REPORT_JSON), JSON.stringify(evidence, null, 2) + "\n");
    await pair?.teardown(testInfo);
  }
});

/** Whether font lookup rejects a stale cached font set (one a resource reload replaced) and answers the current one. */
const STALE_FONT_CACHE = defineTask<void, { current?: boolean }>({
  name: "folkways.models.stale-font-cache",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.client.Minecraft",
      "net.minecraft.client.gui.font.FontManager",
      "net.minecraft.client.gui.font.FontSet",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
Minecraft minecraft = (Minecraft) ctx.client();
var managerField = Minecraft.class.getDeclaredField("fontManager");
managerField.setAccessible(true);
FontManager manager = (FontManager) managerField.get(minecraft);
var cache = FontManager.class.getDeclaredField("lastFontSetCache");
cache.setAccessible(true);
var raw = FontManager.class.getDeclaredMethod("getFontSetRaw", ResourceLocation.class);
var cached = FontManager.class.getDeclaredMethod("getFontSetCached", ResourceLocation.class);
raw.setAccessible(true);
cached.setAccessible(true);
Object current = raw.invoke(manager, Minecraft.DEFAULT_FONT);
FontSet obsolete = new FontSet(minecraft.getTextureManager(), Minecraft.DEFAULT_FONT);
obsolete.close();
try {
    cache.set(manager, obsolete);
    return Map.of("current", cached.invoke(manager, Minecraft.DEFAULT_FONT) == current);
} finally {
    cache.set(manager, null);
}
`,
  }),
});

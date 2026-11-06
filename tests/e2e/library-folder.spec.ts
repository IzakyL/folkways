import { expect, test } from "@playwright/test";
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { defineTask, javaTask, tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, tryCommand, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import {
  MODEL_ASSETS,
  clientHasModelFile,
  placeServerModel,
  readResidentLooks,
  readServerLibrary,
  reloadServerModels,
  waitForModelsOnClient,
} from "../shared/ui/model-folder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { REPO_ROOT, ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";

const SKIN = path.join(MODEL_ASSETS, "steve", "textures", "tartaric_acid.png");
const BLUEPRINT = path.join(REPO_ROOT, "mods/vanilla/src/gametest/resources/data/folkways/folkways/blueprint/test_hut.nbt");
const PATTERN = `accepts = ["zone"]

def draw(site):
    return [part("slab", site.zone, ["minecraft:stone"])]
`;

const SKIN_PACK_PATH = "assets/local/textures/entity/resident_skins/villagers/wren.png";

const MODEL_NAME = "default_boy";
const MODEL_WEIGHT = 400;
const RESIDENTS = 3;

const GEOMETRY_PACK_PATH = "assets/folkways/geo/resident_models/default_boy.geo.json";
const ANIMATION_PACK_PATH = "assets/folkways/animations/resident_models/default_boy.animation.json";
const TEXTURE_PACK_PATH = "assets/folkways/textures/entity/resident_models/default_boy/blue.png";

test("server data loads from the folkways folder and data packs: files, resident models and pattern translations", async ({}, testInfo) => {
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
      snapshot: () => ({ entityTypes: ["folkways:resident"] }),
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

    // A model in a server data pack is registered, its files reach the client, and residents wear it.
    const placed = await placeServerModel(server, path.join(MODEL_ASSETS, MODEL_NAME), MODEL_NAME, { weight: MODEL_WEIGHT });
    evidence.placed = placed.dir;
    await reloadServerModels(server);
    const models = await readServerLibrary(server);
    evidence.models = { entryIds: models.entryIds, resolved: models.resolved };
    expect(models.resolved, "the static read must resolve").toBe(true);
    expect(models.entryIds, "one entry per texture, and nothing refused").toEqual([
      "folkways:model/default_boy/blue",
      "folkways:model/default_boy/red",
    ]);
    evidence.model_arrival = await waitForModelsOnClient(client, GEOMETRY_PACK_PATH);
    expect({
      geometry: clientHasModelFile(client, GEOMETRY_PACK_PATH),
      animations: clientHasModelFile(client, ANIMATION_PACK_PATH),
      texture: clientHasModelFile(client, TEXTURE_PACK_PATH),
    }, "every file the entries name has to reach the client's pack directory").toEqual({
      geometry: true,
      animations: true,
      texture: true,
    });

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
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

    // A pattern's notices keep their own translations after component sync and client language changes.
    const translated = await client.run(TRANSLATE, {
      first: patternSource("First needs %s", "甲需要%s", "first stone", "甲石材"),
      second: patternSource("Second needs %s", "乙需要%s", "second stone", "乙石材"),
    });
    evidence.translated = translated;
    expect(translated.isolated, "matching local keys must have separate dictionary scopes").toBe(true);
    expect(translated.registered, "ClientLanguage.has must recognize pattern dictionary entries").toBe(true);
    expect(translated.english).toEqual(["First needs first stone", "Second needs second stone"]);
    expect(translated.chinese).toEqual(["甲需要甲石材", "乙需要乙石材"]);
    expect(translated.englishAgain, "already decoded components must follow the current client language")
      .toEqual(translated.english);
    expect(translated.globalEnglish, "local gui.done must leave the global translation intact").toBe("Done");
    expect(translated.globalChinese, "the global catalog must still use the selected language").toBe("完成");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach("library-folder", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
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

function patternSource(english: string, chinese: string, material: string, chineseMaterial: string) {
  return `lang = ${JSON.stringify({
    en_us: { "gui.done": english, "item.minecraft.stone": material },
    zh_cn: { "gui.done": chinese, "item.minecraft.stone": chineseMaterial },
  })}\ndef draw(site):\n    return []\n`;
}

const TRANSLATE = defineTask<{ first: string; second: string }, {
  english: string[]; chinese: string[]; englishAgain: string[];
  globalEnglish: string; globalChinese: string; isolated: boolean; registered: boolean;
}>({
  name: "folkways.pattern-i18n.client",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.mojang.serialization.JsonOps",
      "io.github.izakyl.folkways.front.api.notice.Notice",
      "io.github.izakyl.folkways.plugins.build.draft.Pattern",
      "io.github.izakyl.folkways.plugins.build.draft.PatternText",
      "net.minecraft.client.Minecraft",
      "net.minecraft.client.resources.language.ClientLanguage",
      "net.minecraft.locale.Language",
      "net.minecraft.network.chat.Component",
      "net.minecraft.network.chat.ComponentSerialization",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var mc = Minecraft.getInstance();
var manager = mc.getLanguageManager();
String previousCode = manager.getSelected();
Language previousLanguage = Language.getInstance();
try {
    var args = Args.of(ctx);
    var first = Pattern.parse(ResourceLocation.parse("local:i18n_first"), args.string("first")).orElseThrow();
    var second = Pattern.parse(ResourceLocation.parse("local:i18n_second"), args.string("second")).orElseThrow();
    var components = new java.util.ArrayList<Component>();
    var keys = new java.util.ArrayList<String>();
    for (var pattern : java.util.List.of(first, second)) {
        String key = PatternText.key(pattern.face(), "gui.done");
        String material = PatternText.key(pattern.face(), "item.minecraft.stone");
        keys.add(key);
        var notice = new Notice(key, java.util.List.of(Notice.named(material)));
        var encoded = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, PatternText.component(notice))
            .getOrThrow();
        components.add(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
    manager.setSelected("en_us");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us"), false));
    var english = components.stream().map(Component::getString).toList();
    String globalEnglish = Component.translatable("gui.done").getString();
    boolean registered = keys.stream().allMatch(Language.getInstance()::has);
    manager.setSelected("zh_cn");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us", "zh_cn"), false));
    var chinese = components.stream().map(Component::getString).toList();
    String globalChinese = Component.translatable("gui.done").getString();
    manager.setSelected("en_us");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us"), false));
    var englishAgain = components.stream().map(Component::getString).toList();
    return Map.of("english", english, "chinese", chinese, "englishAgain", englishAgain,
        "globalEnglish", globalEnglish, "globalChinese", globalChinese,
        "isolated", !keys.get(0).equals(keys.get(1)), "registered", registered);
} finally {
    manager.setSelected(previousCode);
    Language.inject(previousLanguage);
}
`,
  }),
});

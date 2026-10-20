package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaidAddonGameTests {
    private static final String MODEL = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{
          "identifier":"geometry.sample","texture_width":16,"texture_height":16},"bones":[]}]}
        """;
    private static final String MANIFEST = """
        {"model_list":[{"model_id":"addon:sample","is_gecko":true,
          "animation":["addon:animation/sample.json"]}]}
        """;

    private MaidAddonGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MaidAddonGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void installedZipAndDirectoryResourcesRemainReadable(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-resources");
        try {
            Map<String, String> files = Map.of(
                "assets/addon/maid_model.json", MANIFEST,
                "assets/addon/models/entity/sample.json", MODEL,
                "assets/addon/textures/entity/sample.png", "texture bytes",
                "assets/addon/animation/sample.json", "{\"animations\":{\"idle\":{}}}",
                "assets/addon/lang/en_us.lang", "{\"model.addon.sample\":\"Sample\"}",
                "assets/addon/lang/zh_cn.json", "{\"model.addon.sample\":\"示例\"}");
            for (boolean archive : List.of(false, true)) {
                Path pack = root.resolve(archive ? "model.zip" : "model");
                if (archive) {
                    zip(pack, files);
                } else {
                    for (var entry : files.entrySet()) {
                        write(pack.resolve(entry.getKey()), entry.getValue());
                    }
                }
                List<MaidLooks.Maid> maids = MaidLooks.readInstalled(root);
                helper.assertValueEqual(maids.size(), 1, "the installed pack exposes its model");
                MaidLooks.Maid maid = maids.getFirst();
                helper.assertTrue(maid.gecko(), "the Gecko animation mode survives discovery");
                helper.assertValueEqual(maid.geometry(), "assets/addon/models/entity/sample.json",
                    "external paths start at the installed pack root");
                helper.assertValueEqual(maid.texture().toString(),
                    "folkways:textures/entity/resident_models/maid/addon/sample.png",
                    "external textures use Folkways' generated resource namespace");
                helper.assertValueEqual(maid.source().json(maid.geometry()).get("format_version").getAsString(),
                    "1.12.0", "the geometry can actually be opened");
                helper.assertTrue(maid.source().json(maid.animations().getFirst()).has("animations"),
                    "the declared animation can actually be opened");
                helper.assertValueEqual(new String(maid.source().bytes("assets/addon/textures/entity/sample.png"),
                    StandardCharsets.UTF_8), "texture bytes", "the texture bytes survive both storage formats");
                helper.assertValueEqual(maid.source().languages().get("en_us").get("model.addon.sample").getAsString(),
                    "Sample", "JSON in a legacy .lang file is imported");
                helper.assertValueEqual(maid.source().languages().get("zh_cn").get("model.addon.sample").getAsString(),
                    "示例", "modern .json translations are imported");
                delete(pack);
                helper.assertTrue(MaidLooks.readInstalled(root).isEmpty(), "rescanning forgets a removed pack");
            }
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void badPacksAndBadEntriesDoNotHideValidModels(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-errors");
        try {
            write(root.resolve("broken.zip"), "not a zip");
            zip(root.resolve("voice.zip"), Map.of("assets/voice/maid_sound.json", "{}"));
            zip(root.resolve("broken-manifest.zip"), Map.of("assets/broken/maid_model.json", "{"));
            zip(root.resolve("good.zip"), Map.of(
                "assets/addon/maid_model.json", """
                    {"model_list":[null,{"model_id":"INVALID"},{"model_id":"addon:missing"},
                      {"model_id":"addon:sample"}]}
                    """,
                "assets/addon/models/entity/sample.json", MODEL,
                "assets/addon/textures/entity/sample.png", "texture"));
            helper.assertValueEqual(MaidLooks.readInstalled(root).stream().map(maid -> maid.model().toString()).toList(),
                List.of("addon:sample"), "invalid packs, voice-only packs and individual bad entries are skipped");
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void laterInstalledPacksOverrideDuplicateModelIds(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-overrides");
        try {
            for (String name : List.of("a.zip", "z.zip")) {
                zip(root.resolve(name), Map.of(
                    "assets/addon/maid_model.json", MANIFEST,
                    "assets/addon/models/entity/sample.json", MODEL,
                    "assets/addon/textures/entity/sample.png", name));
            }
            List<MaidLooks.Maid> maids = MaidLooks.readInstalled(root);
            helper.assertValueEqual(maids.size(), 1, "a duplicate model ID appears once");
            helper.assertValueEqual(new String(maids.getFirst().source().bytes(
                "assets/addon/textures/entity/sample.png"), StandardCharsets.UTF_8), "z.zip",
                "the later pack supplies the model's resources");
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void installedResourcesCannotEscapeTheirPack(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-paths");
        try {
            Path pack = root.resolve("pack");
            write(root.resolve("outside.json"), "{}");
            Files.createDirectories(pack);
            for (boolean archive : List.of(false, true)) {
                Path location = archive ? root.resolve("pack.zip") : pack;
                if (archive) {
                    zip(location, Map.of("../outside.json", "{}", "assets/good.json", "{}"));
                }
                MaidResources resources = new MaidResources(location, archive);
                helper.assertFalse(resources.contains("../outside.json"), "parent traversal is inaccessible");
                helper.assertFalse(resources.contains(root.resolve("outside.json").toString()),
                    "absolute paths are inaccessible");
            }
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void generatedModelFilesAndNamesSurviveABrokenNeighbour(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-generation");
        try {
            zip(root.resolve("models.zip"), Map.of(
                "assets/addon/maid_model.json", """
                    {"model_list":[{"model_id":"addon:broken","is_gecko":true},
                      {"model_id":"addon:sample","is_gecko":true,
                       "animation":["addon:animation/sample.json"]}]}
                    """,
                "assets/addon/models/entity/broken.json", "{",
                "assets/addon/textures/entity/broken.png", "broken texture",
                "assets/addon/models/entity/sample.json", MODEL,
                "assets/addon/textures/entity/sample.png", "texture bytes",
                "assets/addon/animation/sample.json", "{\"animations\":{\"idle\":{\"loop\":true,\"bones\":{}}}}",
                "assets/addon/lang/en_us.json", "{\"model.addon.sample\":\"Sample\"}"));
            List<MaidLooks.Maid> maids = MaidLooks.readInstalled(root);
            helper.assertValueEqual(maids.size(), 2, "discovery leaves geometry validation to resource conversion");
            MaidLooks.Maid sample = maids.stream().filter(maid -> maid.model().getPath().equals("sample"))
                .findFirst().orElseThrow();
            MaidLooks.Maid broken = maids.stream().filter(maid -> maid.model().getPath().equals("broken"))
                .findFirst().orElseThrow();
            ResourceLocation language = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "lang/en_us.json");
            Map<ResourceLocation, byte[]> rendered = MaidModelFiles.make(maids, new JsonObject(), true);
            helper.assertTrue(rendered.containsKey(sample.geometryAt()), "valid geometry is generated after a broken model");
            helper.assertTrue(json(rendered.get(sample.geometryAt())).has("minecraft:geometry"),
                "the output geometry has the GeckoLib schema");
            helper.assertTrue(json(rendered.get(sample.animationsAt())).getAsJsonObject("animations")
                .has("maid.addon.sample.idle"), "the external idle animation is converted under its resident name");
            helper.assertValueEqual(new String(rendered.get(sample.texture()), StandardCharsets.UTF_8),
                "texture bytes", "the generated resource pack exposes the actual external texture");
            helper.assertFalse(rendered.containsKey(broken.geometryAt()), "broken geometry contributes no model resource");
            helper.assertValueEqual(json(rendered.get(language)).get("model.addon.sample").getAsString(),
                "Sample", "the generated resource pack exposes model names");
            Map<ResourceLocation, byte[]> namesOnly = MaidModelFiles.make(maids, new JsonObject(), false);
            helper.assertValueEqual(namesOnly.keySet(), java.util.Set.of(language),
                "without GeckoLib only translated names are generated");
            helper.assertValueEqual(json(namesOnly.get(language)).get("model.addon.sample").getAsString(),
                "Sample", "the disabled model still has its translated name");
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void translationOverridesFollowPackOrderRatherThanModelOrder(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-addon-language-order");
        try {
            for (boolean later : List.of(false, true)) {
                String namespace = later ? "a_namespace" : "z_namespace";
                String word = later ? "later" : "earlier";
                zip(root.resolve(later ? "z.zip" : "a.zip"), Map.of(
                    "assets/" + namespace + "/maid_model.json",
                    "{\"model_list\":[{\"model_id\":\"" + namespace + ":model\"}]}",
                    "assets/" + namespace + "/models/entity/model.json", MODEL,
                    "assets/" + namespace + "/textures/entity/model.png", "texture",
                    "assets/" + namespace + "/lang/en_us.json", "{\"shared.model.name\":\"" + word + "\"}"));
            }
            List<MaidLooks.Maid> maids = MaidLooks.readInstalled(root);
            helper.assertValueEqual(maids.getFirst().model().getNamespace(), "a_namespace",
                "the catalog sorts model IDs independently of installation order");
            Map<ResourceLocation, byte[]> files = MaidModelFiles.make(maids, new JsonObject(), false);
            ResourceLocation language = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "lang/en_us.json");
            helper.assertValueEqual(json(files.get(language)).get("shared.model.name").getAsString(), "later",
                "the later pack overrides translations even when its model sorts first");
        } finally {
            delete(root);
        }
        helper.succeed();
    }

    private static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void zip(Path path, Map<String, String> entries) throws IOException {
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                // Real model packs need not include explicit directory entries.
                stream.putNextEntry(new ZipEntry(entry.getKey()));
                stream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}

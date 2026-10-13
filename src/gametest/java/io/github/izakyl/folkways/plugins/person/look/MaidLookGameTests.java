package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaidLookGameTests {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation REIMU =
        ResourceLocation.fromNamespaceAndPath(MaidLooks.MOD, "maid/touhou_little_maid/hakurei_reimu");
    private static final ResourceLocation STEVE =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "steve");
    private static final String LEGACY = """
        {"format_version":"1.10.0","geometry.model":{"texturewidth":128,"textureheight":64,"bones":[
          {"name":"head","pivot":[0,8,0]},
          {"name":"blink","parent":"head","pivot":[0,8,0]},
          {"name":"begShow","parent":"head","pivot":[0,8,0]},
          {"name":"heart","parent":"begShow","pivot":[0,9,0]},
          {"name":"helmet","parent":"head","pivot":[0,8,0]},
          {"name":"farmShow","pivot":[0,0,0]}]}}
        """;
    private static final String CURRENT = """
        {"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.fox"},"bones":[
          {"name":"Head","pivot":[0,24,0]},
          {"name":"Umbrella","parent":"Head","pivot":[0,24,0]},
          {"name":"Tank","pivot":[0,0,0]},
          {"name":"molang1","pivot":[0,0,0]}]}]}
        """;

    private MaidLookGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MaidLookGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aLegacyMaidBecomesCurrentGeometryWithoutItsOccasionalBones(GameTestHelper helper) {
        JsonObject model = json(LEGACY);
        List<String> bones = MaidAssets.boneNames(model);
        JsonObject written = MaidAssets.geometry(model, MaidAssets.hiddenByScripts(List.of(), bones));
        JsonObject geometry = written.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        helper.assertValueEqual(written.get("format_version").getAsString(), "1.12.0", "the version GeckoLib reads");
        helper.assertValueEqual(geometry.getAsJsonObject("description").get("texture_width").getAsInt(), 128,
            "the texture size moves into the description");
        helper.assertValueEqual(names(geometry), List.of("head", "blink", "farmShow"),
            "the default scripts hide what begging and armour show, with whatever hangs from it; the blink"
                + " stays for the animation to hide, and a task bone stays when its script is not run");
        helper.assertValueEqual(
            names(geometryOf(model, List.of("touhou_little_maid:animation/maid/default/task/farm.js"), bones)),
            List.of("head", "blink", "begShow", "heart", "helmet"),
            "a maid that names its scripts is held to those alone");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aGeckoMaidLosesTheBonesItsStandingLayersScaleAway(GameTestHelper helper) {
        JsonObject model = json(CURRENT);
        JsonObject animations = MaidAssets.merged(List.of(
            json("""
                {"animations":{"idle":{"loop":true,"bones":{"Head":{"rotation":[0,0,0]}}},
                 "parallel0":{"bones":{"Tank":{"scale":1}}}}}
                """),
            json("""
                {"animations":{"idle":{"loop":true,"bones":{"Head":{
                   "rotation":{"0.0":{"post":["q.is_sneaking ? 5", "math.sin(q.anim_time * 90)", "ysm.head_yaw"],
                               "lerp_mode":"catmullrom"}}}}},
                 "pre_parallel1":{"bones":{"Umbrella":{"scale":1}}},
                 "parallel1":{"bones":{"Umbrella":{"scale":"(query.is_in_water_or_rain && !q.is_item_name_any('Mainhand', 'bow')) ? 1 : 0"},
                                       "Tank":{"scale":{"0.0":[0,0,0],"1.0":[1,1,1]}},
                                       "Head":{"scale":"math.random(0, 1)"}}}}}
                """)));
        Set<String> hidden = MaidAssets.hiddenByLayers(animations, MaidAssets.boneNames(model));
        helper.assertValueEqual(hidden, Set.of("Umbrella", "Tank", "molang1"),
            "a later layer decides, a scale that cannot be worked out keeps its bone, and helper bones go");
        helper.assertValueEqual(names(MaidAssets.geometry(model, hidden).getAsJsonArray("minecraft:geometry")
            .get(0).getAsJsonObject()), List.of("Head"), "what is left to draw");

        MaidLooks.Maid maid = new MaidLooks.Maid(ResourceLocation.fromNamespaceAndPath("geckolib", "winefox"), true,
            "pack/assets/geckolib/models/entity/winefox.json", List.of(), STEVE, 0.65F);
        JsonObject played = MaidAssets.animations(maid, animations).getAsJsonObject("animations");
        helper.assertValueEqual(played.keySet(),
            Set.of("maid.geckolib.winefox.idle", "maid.geckolib.winefox.walk", "maid.geckolib.winefox.swing_hand"),
            "only what a resident plays, each under the maid's own name");
        JsonObject frame = played.getAsJsonObject("maid.geckolib.winefox.idle").getAsJsonObject("bones")
            .getAsJsonObject("Head").getAsJsonObject("rotation").getAsJsonObject("0.0");
        helper.assertValueEqual(frame.get("lerp_mode").getAsString(), "catmullrom", "an easing's name is no expression");
        helper.assertValueEqual(texts(frame.getAsJsonArray("post")),
            List.of("0 ? 5 : 0", "math.sin(query.anim_time * 90)", "0"),
            "the later file's idle wins, rewritten into what GeckoLib takes");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void molangIsReadAtRestAndRewrittenForGeckoLib(GameTestHelper helper) {
        helper.assertValueEqual(Molang.atRest("v.gun ?? 1"), OptionalDouble.of(1.0), "an unset variable falls back");
        helper.assertValueEqual(Molang.atRest("(!ysm.has_helmet && 2 > 1) ? 0.5 : 0;"), OptionalDouble.of(0.5),
            "queries are 0 at rest");
        helper.assertValueEqual(Molang.atRest("math.clamp(v.x - 1, 0, 1)"), OptionalDouble.of(0.0), "math is worked out");
        helper.assertValueEqual(Molang.atRest("v.x = 1; return v.x;"), OptionalDouble.empty(),
            "a script is past working out");
        helper.assertValueEqual(Molang.atRest("math.random(0, 1) > 2"), OptionalDouble.empty(),
            "and so is a function whose value is not known");
        helper.assertValueEqual(
            Molang.portable("(!v.leftbow&&!v.rightbow?(0.2)) + (v.leftbow?(0));"),
            "(!v.leftbow && !v.rightbow ? (0.2) : 0) + (v.leftbow ? (0) : 0)", "the short conditional is completed");
        helper.assertValueEqual(Molang.portable("q.life_time * 2 + query.ground_speed + tlm.is_sitting"),
            "query.life_time * 2 + 0 + 0", "only the queries GeckoLib answers for a stand-in are kept");
        helper.assertValueEqual(Molang.portable("v.t = v.t + ysm.second_order(1, 2); return v.t ?? 3;"),
            "v.t = v.t + 0; return 3;", "statement by statement");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void maidsStayOutOfAPoolUntilSwitchedOn(GameTestHelper helper) {
        helper.assertTrue(MaidLooks.isMaid(REIMU) && !MaidLooks.isMaid(STEVE), "a maid is told by its id");
        LookPool pool = LookPool.UNTOUCHED;
        helper.assertTrue(pool.allows(STEVE) && !pool.allows(REIMU), "an untouched pool draws no maid");
        LookPool chosen = LookPool.from(pool.with(REIMU, true).save());
        helper.assertTrue(chosen.allows(REIMU) && chosen.allows(STEVE), "one switched on is kept, and saved");
        helper.assertFalse(chosen.with(REIMU, false).allows(REIMU), "and can be switched off again");
        helper.assertFalse(chosen.with(STEVE, false).allows(STEVE), "any other look is struck off as before");
        LookPool replaced = new LookPool(Optional.of(Set.of(REIMU)), Set.of(), Set.of());
        helper.assertTrue(replaced.allows(REIMU) && !replaced.allows(STEVE), "a set of maids is all maids");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aPackFolderIsReadIntoMaids(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("folkways-maids");
        Path packs = root.resolve("assets").resolve(MaidLooks.MOD).resolve("tlm_custom_pack");
        Path assets = packs.resolve("pack-1.0.0").resolve("assets");
        write(assets.resolve("touhou/maid_model.json"), """
            {"model_list":[
              {"model_id":"touhou:reimu"},
              {"model_id":"touhou:reimu_2","model":"touhou:models/entity/reimu.json","render_entity_scale":0.8,
               "animation":["touhou_little_maid:animation/maid/default/head/blink.js"]},
              {"model_id":"touhou:lost"}]}
            """);
        write(assets.resolve("touhou/models/entity/reimu.json"), LEGACY);
        write(assets.resolve("touhou/textures/entity/reimu.png"), "");
        write(assets.resolve("touhou/textures/entity/reimu_2.png"), "");
        write(assets.resolve("fox/maid_model.json"), """
            {"model_list":[{"model_id":"fox:wine","is_gecko":true,"animation":["fox:animation/wine.animation.json"]}]}
            """);
        write(assets.resolve("fox/models/entity/wine.json"), CURRENT);
        write(assets.resolve("fox/textures/entity/wine.png"), "");

        List<MaidLooks.Maid> maids = MaidLooks.read(packs);
        helper.assertValueEqual(maids.stream().map(maid -> maid.look().toString()).toList(),
            List.of("touhou_little_maid:maid/fox/wine", "touhou_little_maid:maid/touhou/reimu",
                "touhou_little_maid:maid/touhou/reimu_2"),
            "one look a model, and none for a model whose files are not there");
        MaidLooks.Maid wine = maids.get(0);
        MaidLooks.Maid second = maids.get(2);
        helper.assertValueEqual(wine.animations(), List.of("pack-1.0.0/assets/fox/animation/wine.animation.json"),
            "a GeckoLib maid's animations are files in the pack");
        helper.assertValueEqual(wine.texture().toString(),
            "touhou_little_maid:tlm_custom_pack/pack-1.0.0/assets/fox/textures/entity/wine.png",
            "the texture is read where Touhou Little Maid keeps it");
        helper.assertValueEqual(second.geometry(), "pack-1.0.0/assets/touhou/models/entity/reimu.json",
            "a maid may borrow another's model");
        helper.assertValueEqual(second.scale(), 0.8F, "and is drawn at the size its pack asks");
        ResidentLook.Model look = (ResidentLook.Model) second.asLook().appearance();
        helper.assertValueEqual(look.animations(), MaidLooks.SHARED_ANIMATIONS, "scripted maids share one animation file");
        helper.assertValueEqual(look.idleAnimation(), "maid.idle", "and its names");
        helper.assertValueEqual(((ResidentLook.Model) wine.asLook().appearance()).walkAnimation(), "maid.fox.wine.walk",
            "a GeckoLib maid plays its own");
        helper.succeed();
    }

    private static JsonObject geometryOf(JsonObject model, List<String> scripts, List<String> bones) {
        return MaidAssets.geometry(model, MaidAssets.hiddenByScripts(scripts, bones))
            .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    private static List<String> names(JsonObject geometry) {
        List<String> names = new ArrayList<>();
        for (JsonElement bone : geometry.getAsJsonArray("bones")) {
            names.add(bone.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    private static List<String> texts(JsonArray array) {
        List<String> texts = new ArrayList<>();
        array.forEach(element -> texts.add(element.getAsString()));
        return texts;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}

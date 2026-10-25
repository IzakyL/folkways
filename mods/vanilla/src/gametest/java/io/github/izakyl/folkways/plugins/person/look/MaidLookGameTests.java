package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.GsonBuilder;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.ui.FolderPicker;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.loading.json.typeadapter.BakedAnimationsAdapter;
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter;
import software.bernie.geckolib.loading.math.MathParser;
import software.bernie.geckolib.loading.object.BakedAnimations;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
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
    public static void maidMealsKeepAuthoredSeatedLegsAndUseTheItemGesture(GameTestHelper helper) {
        MaidLooks.Maid maid = new MaidLooks.Maid(ResourceLocation.fromNamespaceAndPath("geckolib", "winefox"), true,
            "model.json", List.of(), STEVE, 1.0F);
        JsonObject source = json("""
            {"chair":{"loop":true,"animation_length":1,"bones":{
                "LeftLeg":{"rotation":[-80,0,0]},"RightArm":{"position":[1,0,0],"rotation":[0,0,0]}}},
             "use_mainhand:eat":{"animation_length":0.5,"bones":{"RightArm":{"rotation":[-70,0,0]}}},
             "use_mainhand:drink":{"animation_length":0.75,"bones":{"RightArm":{"rotation":[-100,0,0]}}}}
            """);
        JsonObject played = MaidAssets.animations(maid, source).getAsJsonObject("animations");
        JsonObject eating = played.getAsJsonObject(maid.animation("dining_seated")).getAsJsonObject("bones");
        helper.assertTrue(eating.has("LeftLeg"), "a meal retains the chair's authored leg pose");
        helper.assertTrue(eating.getAsJsonObject("RightArm").has("position"), "the chair's arm position survives");
        helper.assertValueEqual(eating.getAsJsonObject("RightArm").getAsJsonArray("rotation").get(0).getAsInt(), -70,
            "the eating gesture overrides the chair's arm rotation");
        helper.assertTrue(played.has(maid.animation("drinking_seated")), "drinking has its own seated gesture");
        ResidentLook.Model look = (ResidentLook.Model) maid.asLook().appearance();
        helper.assertValueEqual(look.seatedAnimation(), Optional.of(maid.animation("chair")), "the chair clip is mapped");
        helper.assertValueEqual(look.leisureAnimations().get("drinking"), maid.animation("use_mainhand:drink"),
            "the core's drinking state selects the drink clip");
        helper.succeed();
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
    public static void aGeckoMaidKeepsItsLiveLayersAndExpressionBones(GameTestHelper helper) {
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
        helper.assertValueEqual(names(MaidAssets.geometry(model, Set.of()).getAsJsonArray("minecraft:geometry")
            .get(0).getAsJsonObject()), List.of("Head", "Umbrella", "Tank", "molang1"),
            "temporarily hidden and helper bones remain available to the live layers");

        MaidLooks.Maid maid = new MaidLooks.Maid(ResourceLocation.fromNamespaceAndPath("geckolib", "winefox"), true,
            "pack/assets/geckolib/models/entity/winefox.json", List.of(), STEVE, 0.65F);
        JsonObject played = MaidAssets.animations(maid, animations).getAsJsonObject("animations");
        helper.assertValueEqual(played.keySet(),
            Set.of("maid.geckolib.winefox.idle", "maid.geckolib.winefox.walk", "maid.geckolib.winefox.swing_hand",
                "maid.geckolib.winefox.parallel0", "maid.geckolib.winefox.pre_parallel1", "maid.geckolib.winefox.parallel1"),
            "main clips and TLM's independent layers are kept under the maid's own name");
        JsonObject frame = played.getAsJsonObject("maid.geckolib.winefox.idle").getAsJsonObject("bones")
            .getAsJsonObject("Head").getAsJsonObject("rotation").getAsJsonObject("0.0");
        helper.assertValueEqual(frame.get("lerp_mode").getAsString(), "catmullrom", "an easing's name is no expression");
        helper.assertValueEqual(texts(frame.getAsJsonArray("post")),
            List.of("variable.folkways_sneaking ? 5 : 0", "math.sin(query.anim_time * 90)", ResidentMolang.HEAD_YAW.variable()),
            "the later file's idle wins, rewritten into what GeckoLib takes");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void wineFoxQueriesFollowTheResidentInsteadOfTheSharedRenderer(GameTestHelper helper) {
        var healthy = helper.spawn(PersonBody.RESIDENT.get(), new BlockPos(1, 1, 1));
        var hurt = helper.spawn(PersonBody.RESIDENT.get(), new BlockPos(3, 1, 1));
        healthy.setHealth(healthy.getMaxHealth());
        hurt.setHealth(hurt.getMaxHealth() * 0.1F);
        hurt.hurtTime = 7;
        helper.assertValueEqual(ResidentMolang.HEALTH.value(healthy, 0, 0, 0)
            / ResidentMolang.MAX_HEALTH.value(healthy, 0, 0, 0), 1.0, "a healthy resident has full health");
        helper.assertTrue(ResidentMolang.HEALTH.value(hurt, 0, 0, 0)
            / ResidentMolang.MAX_HEALTH.value(hurt, 0, 0, 0) < 0.2, "wine fox's low-health form can activate");
        helper.assertValueEqual(ResidentMolang.HURT_TIME.value(hurt, 0, 0, 0), 7.0, "the hurt timer is live");
        helper.assertValueEqual(ResidentMolang.HURT_TIME.value(healthy, 0, 0, 0), 0.0,
            "one resident's hurt state does not affect another");
        double phase = Math.abs(healthy.getUUID().getLeastSignificantBits()) % 10L;
        helper.assertValueEqual(ResidentMolang.CLOSE_EYES.value(healthy, 0, 0, 86 - phase), 1.0,
            "the blink uses TLM's tick window and UUID phase");
        helper.assertValueEqual(ResidentMolang.CLOSE_EYES.value(healthy, 0, 0, 0), 0.0, "eyes are normally open");
        healthy.startSleeping(helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.assertValueEqual(ResidentMolang.SLEEPING.value(healthy, 0, 0, 0), 1.0, "sleeping is live");
        helper.assertValueEqual(ResidentMolang.CLOSE_EYES.value(healthy, 0, 0, 0), 1.0,
            "sleep closes the eyes outside the blink window");
        healthy.stopSleeping();
        helper.assertValueEqual(ResidentMolang.CLOSE_EYES.value(healthy, 0, 0, 0), 0.0,
            "waking restores the open-eye expression");
        helper.assertValueEqual(Molang.atRest("query.health / query.max_health"), OptionalDouble.of(1.0),
            "rest means healthy, rather than 0 / 0");
        helper.assertValueEqual(Molang.portable("(query.health/query.max_health)<0.2?0:1"),
            "(variable.folkways_health / variable.folkways_max_health) < 0.2 ? 0 : 1",
            "wine fox's real body scale condition reads the resident's health");
        helper.assertValueEqual(Molang.portable("ysm.is_close_eyes ? 0 : 1"),
            "variable.folkways_close_eyes ? 0 : 1", "its eyelids read the resident's blink and sleep state");
        helper.assertValueEqual(Molang.portable("q.health ?? 5"), ResidentMolang.HEALTH.variable(),
            "a supported query is defined and must not be replaced by its fallback");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void magicalWineFoxIdleMouthMatchesTlmAtFullFood(GameTestHelper helper) {
        // Exact idle mouth channels from TLM 1.5.3's winefox_magical.main.animation.json.
        // Unlike our atRest reader, GeckoLib 4 ranks <= ABOVE +: missing grouping exposed kongju at food 20.
        JsonObject authored = json("""
            {"animations":{"idle":{"loop":true,"animation_length":30,"bones":{
              "kongju":{"position":[0,0,"0.8+ysm.food_level<=6?-0.9:0"]},
              "weixiao":{"position":[0,0,"ysm.food_level<=6?0:-0.9"]}
            }}}}
            """);
        var maid = new MaidLooks.Maid(ResourceLocation.fromNamespaceAndPath("geckolib", "winefox_magical"), true,
            "pack/assets/geckolib/models/entity/winefox_magical.json", List.of(), STEVE, 0.65F);
        JsonObject converted = MaidAssets.animations(maid, MaidAssets.merged(List.of(authored)));
        var gson = new GsonBuilder()
            .registerTypeAdapter(BakedAnimations.class, new BakedAnimationsAdapter())
            .registerTypeAdapter(Animation.Keyframes.class, new KeyFramesAdapter()).create();
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.assertTrue(body != null, "the resident can be constructed without ticking");
        // Native TLM results, including each threshold and recovery after hunger. The 0.8 offset
        // makes kongju's threshold different from weixiao's; do not simplify either condition.
        int[] foodLevels = {20, 7, 6, 5, 0, 20};
        double[] kongju = {0, 0, 0, -0.9, -0.9, 0};
        double[] weixiao = {-0.9, -0.9, 0, 0, 0, -0.9};
        for (int i = 0; i < foodLevels.length; i++) {
            body.foodLevel(foodLevels[i]);
            JsonObject clips = converted.getAsJsonObject("animations").deepCopy();
            JsonObject bones = clips.getAsJsonObject(maid.animation("idle")).getAsJsonObject("bones");
            for (var bone : bones.entrySet()) {
                JsonArray position = bone.getValue().getAsJsonObject().getAsJsonArray("position");
                String expression = position.get(2).getAsString();
                helper.assertTrue(expression.contains(ResidentMolang.FOOD_LEVEL.variable()),
                    "the converted mouth channel must read the resident's food binding");
                // GeckoLib's variable registry links ClientLevel even without an active renderer. Bind
                // the resident's real value as a literal so its actual baker/parser can run on a server.
                position.set(2, new JsonPrimitive(expression.replace(ResidentMolang.FOOD_LEVEL.variable(),
                    Double.toString(ResidentMolang.FOOD_LEVEL.value(body, 0, 0, 40)))));
            }
            Animation idle = gson.fromJson(clips, BakedAnimations.class).getAnimation(maid.animation("idle"));
            helper.assertTrue(idle != null, "GeckoLib must bake the converted magical idle clip");
            helper.assertValueEqual(idle.boneAnimations().length, 2, "both mouth channels were baked");
            for (var bone : idle.boneAnimations()) {
                double expected = bone.boneName().equals("kongju") ? kongju[i] : weixiao[i];
                var frame = bone.positionKeyFrames().zKeyframes().getFirst();
                helper.assertValueEqual(frame.startValue().get(), expected,
                    bone.boneName() + " starts at TLM's mouth depth for food " + foodLevels[i]);
                helper.assertValueEqual(frame.endValue().get(), expected,
                    bone.boneName() + " ends at TLM's mouth depth for food " + foodLevels[i]);
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void translatedMolangKeepsTlmOperatorGrouping(GameTestHelper helper) {
        for (String expression : List.of(
                "0.8 + 20 <= 6 ? -0.9 : 0", "20 / 20 < 0.2 ? 0 : 1",
                "1 + 2 * 3 > 6", "1 || 0 && 0", "0 && 1 < 2", "1 && 2 == 2",
                "v.unset ?? 0.8 + 20 <= 6", "1 ? 0 : 1 ? 2 : 3", "1 ? 0 ? 2 : 3 : 4")) {
            helper.assertValueEqual(MathParser.compileMolang(Molang.portable(expression)).get(),
                Molang.atRest(expression).orElseThrow(), "GeckoLib must retain TLM's grouping: " + expression);
        }
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
            "query.life_time * 2 + variable.folkways_ground_speed + variable.folkways_sitting",
            "shared queries stay with GeckoLib and resident queries use the bridge");
        helper.assertValueEqual(Molang.portable("v.t = v.t + ysm.second_order(1, 2); return v.t ?? 3;"),
            "v.t = v.t + (2); return 3;", "statement by statement, with a filter at its steady-state input");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void everyBundledMaidQueryHasAnExplicitBridgeOrClock(GameTestHelper helper) throws IOException {
        // The independent inventory comes from all 62 animation JSON files in TLM 1.5.3, not from our enum.
        try (var stream = MaidLookGameTests.class.getResourceAsStream("/assets/folkways/maid_molang_queries.json")) {
            helper.assertTrue(stream != null, "the audited query inventory is available");
            JsonObject audit = json(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            JsonObject names = audit.getAsJsonObject("names");
            helper.assertValueEqual(names.size(), 37, "all distinct spellings, including q aliases, are audited");
            for (var name : names.entrySet()) {
                String portable = Molang.portable(name.getValue().getAsString());
                boolean clock = Set.of("q.anim_time", "query.anim_time", "query.life_time").contains(name.getKey());
                helper.assertTrue(clock ? portable.startsWith("query.")
                        : name.getKey().equals("ysm.second_order") ? portable.equals("(5)")
                        : portable.startsWith("variable.folkways_"),
                    name.getKey() + " must have an explicit live binding or documented fallback, got " + portable);
            }
        }
        helper.assertValueEqual(Molang.atRest("ysm.food_level <= 6 ? 0.7 : 1"), OptionalDouble.of(1),
            "a normal fed maid keeps full-sized eyelids");
        helper.assertValueEqual(Molang.portable("ysm.food_level <= 6 ? -25 : 0"),
            "variable.folkways_food_level <= 6 ? -25 : 0", "the hungry arm pose reads live hunger");
        helper.assertValueEqual(Molang.portable("ysm.food_level ?? 0"), ResidentMolang.FOOD_LEVEL.variable(),
            "food is defined; it must not fall through to the starving default");
        helper.assertValueEqual(Molang.portable("query.position(0) - ysm.second_order('DroneX', query.position(0), 1, 1, 1)"),
            "variable.folkways_position_x - (variable.folkways_position_x)",
            "a resting follower stays at its input, even far from the world origin");
        helper.assertValueEqual(Molang.portable("-ysm.bone_rot('LeftM1').x"),
            "-" + ResidentMolang.boneRotation("LeftM1", "x"), "bone struct members become live private variables");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void maidMotionAndEquipmentQueriesReadTheResident(GameTestHelper helper) {
        // These queries need an entity's state, not a world participant that can tick or collide.
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.assertTrue(body != null, "the resident can be constructed");
        body.setPos(helper.absolutePos(new BlockPos(1, 1, 1)).getCenter());
        body.setDeltaMovement(0.3, 0, 0.4);
        helper.assertTrue(Math.abs(ResidentMolang.GROUND_SPEED.value(body, 0, 0, 0) - 10) < 0.0001,
            "TLM speed is blocks per second");
        body.xo = body.getX() - 2;
        body.yo = body.getY() - 0.5;
        body.zo = body.getZ();
        helper.assertValueEqual(ResidentMolang.VERTICAL_SPEED.value(body, 0, 0, 0), 10.0,
            "vertical speed follows displacement, as TLM's binding does");
        helper.assertValueEqual(ResidentMolang.POSITION_DELTA_X.value(body, 0, 0, 0, 0.25F), 0.5,
            "position_delta is interpolated displacement, not velocity or world position");
        helper.assertValueEqual(ResidentMolang.POSITION_X.value(body, 0, 0, 0, 0.25F), body.xo + 0.5,
            "position is interpolated in world space");
        body.setYRot(0);
        body.yRotO = 0;
        // Like TLM, input() uses getViewYRot: LivingEntity interpolates head yaw, not body yaw.
        // The constructor randomizes yHeadRot, so setYRot alone leaves this assertion nondeterministic.
        body.setYHeadRot(0);
        body.yHeadRotO = 0;
        helper.assertTrue(Math.abs(ResidentMolang.INPUT_HORIZONTAL.value(body, 0, 0, 0) + 1) < 0.0001,
            "sideways motion is measured relative to facing, not a player keyboard");
        body.setYHeadRot(90);
        double diagonal = Math.sqrt(0.5);
        helper.assertTrue(Math.abs(ResidentMolang.INPUT_HORIZONTAL.value(body, 0, 0, 0, 0.5F) + diagonal) < 0.0001,
            "horizontal input follows interpolated head yaw even when body yaw stays zero");
        helper.assertTrue(Math.abs(ResidentMolang.INPUT_VERTICAL.value(body, 0, 0, 0, 0.5F) + diagonal) < 0.0001,
            "vertical input uses the same interpolated facing");
        helper.assertValueEqual(ResidentMolang.INPUT_HORIZONTAL.value(body, 0, 0, 0, 0), 0.0,
            "at partial tick zero, interpolated displacement is zero as in TLM");
        body.setNoAi(true);
        body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        body.travel(net.minecraft.world.phys.Vec3.ZERO);
        helper.assertValueEqual(ResidentMolang.JUMPING.value(body, 0, 0, 0), 1.0,
            "NoAI skips travel physics and leaves a newly staged portrait airborne: TLM would select jump");
        body.setOnGround(true);
        body.travel(net.minecraft.world.phys.Vec3.ZERO);
        helper.assertValueEqual(ResidentMolang.JUMPING.value(body, 0, 0, 0), 0.0,
            "an explicitly grounded NoAI portrait can select the same idle state in both renderers");
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA));
        helper.assertValueEqual(ResidentMolang.HAS_HELMET.value(body, 0, 0, 0), 1.0, "the resident wears a helmet");
        helper.assertValueEqual(ResidentMolang.HAS_MAINHAND.value(body, 0, 0, 0), 1.0, "the resident holds an item");
        helper.assertValueEqual(ResidentMolang.HAS_OFFHAND.value(body, 0, 0, 0), 0.0, "the other hand stays empty");
        helper.assertValueEqual(ResidentMolang.HAS_ELYTRA.value(body, 0, 0, 0), 1.0, "elytra comes from equipment");
        helper.assertValueEqual(ResidentMolang.MAINHAND_BOW.value(body, 0, 0, 0), 1.0, "the audited item query matches bow");
        helper.assertValueEqual(ResidentMolang.HEAD_PITCH.value(body, 17, 29, 0), 17.0, "head pitch uses render data");
        helper.assertValueEqual(ResidentMolang.named("query.head_x_rotation"), Optional.of(ResidentMolang.HEAD_YAW),
            "retain TLM's head-axis naming, which differs from GeckoLib's default entity queries");
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
        write(assets.resolve("fox/animation/wine.animation.json"), """
            {"animations":{"pre_parallel0":{"loop":true,"bones":{
                "Umbrella":{"scale":"ysm.is_close_eyes ? 1 : 0"},
                "Tank":{"scale":{"0.0":[0,0,0],"1.0":[1,1,1]}}}}}}
            """);

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
        var files = MaidModelFiles.make(List.of(wine), new JsonObject(), true);
        JsonObject made = json(new String(files.get(wine.geometryAt()), java.nio.charset.StandardCharsets.UTF_8));
        helper.assertValueEqual(names(made.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()),
            List.of("Head", "Umbrella", "Tank", "molang1"),
            "the actual resource pipeline retains bones that can appear after the first frame");
        JsonObject clips = json(new String(files.get(wine.animationsAt()), java.nio.charset.StandardCharsets.UTF_8));
        helper.assertTrue(clips.getAsJsonObject("animations").has(wine.animation("pre_parallel0")),
            "the resource pipeline emits the layer that controls those bones");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void unavailableModelsCannotBeSelected(GameTestHelper helper) {
        Component reason = Component.translatable("folkways.looks.requires_geckolib");
        for (boolean ticked : List.of(false, true)) {
            List<String> said = new ArrayList<>();
            FolderPicker picker = new FolderPicker("test", ticked, (word, key) -> said.add(word + ":" + key));
            picker.fill(List.of(
                new FolderPicker.Leaf("model", "test:model", Component.literal("Model"), "model",
                    Optional.of(reason)),
                new FolderPicker.Leaf("skin", "test:skin", Component.literal("Skin"), "skin")));
            UIElement model = element(picker.tree(), "model");
            UIElement skin = element(picker.tree(), "skin");
            helper.assertTrue(!model.isActive() && skin.isActive(),
                "only the model requiring a missing dependency is disabled");
            if (ticked) {
                Toggle folder = (Toggle) element(picker.tree(), "test.tick.test");
                helper.assertTrue(!folder.isActive(), "bulk selection cannot bypass a disabled model");
                ((Toggle) model).setOn(true, true);
                folder.setOn(true, true);
                helper.assertTrue(said.isEmpty(), "disabled individual and bulk toggles send no choice");
                ((Toggle) skin).setOn(true, true);
                helper.assertValueEqual(said, List.of("on:skin"), "a skin remains selectable");
            }
        }
        helper.succeed();
    }

    private static UIElement element(UIElement root, String id) {
        if (id.equals(root.getId())) {
            return root;
        }
        for (UIElement child : root.getChildren()) {
            UIElement found = element(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
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

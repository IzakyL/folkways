package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class ChosenGameTests {

    private static final ResourceLocation STONE = ResourceLocation.withDefaultNamespace("stone");
    private static final ResourceLocation WOOD = ResourceLocation.withDefaultNamespace("oak_planks");

    private ChosenGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ChosenGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void currentSettingsRequireEveryFixedKnobAndValidValues(GameTestHelper helper) {
        Schema schema = schema(9, List.of(STONE, WOOD), STONE);
        CompoundTag saved = Chosen.of(schema, ColonySettings.byDefault(schema)).save();
        helper.assertValueEqual(Chosen.load(schema, saved).save(), saved, "a current payload round trips intact");
        helper.assertTrue(Chosen.load(schema, saved).items("materials").isEmpty(),
            "an empty item selection may be omitted by the writer");
        for (String key : List.of("enabled", "width", "material")) {
            CompoundTag missing = saved.copy();
            missing.remove(key);
            rejects(helper, schema, missing, "current settings cannot omit " + key);
        }
        CompoundTag invalid = saved.copy();
        invalid.putString("enabled", "true");
        rejects(helper, schema, invalid, "a flag must keep its current type");
        invalid = saved.copy();
        invalid.putInt("width", 10);
        rejects(helper, schema, invalid, "current counts cannot be silently bounded");
        invalid = saved.copy();
        invalid.putString("material", "minecraft:dirt");
        rejects(helper, schema, invalid, "current choices must belong to the schema");
        invalid = saved.copy();
        invalid.putString("materials", "minecraft:stone");
        rejects(helper, schema, invalid, "a present item selection must have the writer's shape");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rememberedAndPreviewSettingsAreExplicitlyNormalizedAfterSchemaChanges(GameTestHelper helper) {
        Schema before = schema(9, List.of(STONE, WOOD), STONE);
        CompoundTag old = Chosen.of(before, ColonySettings.byDefault(before)).save();
        old.putInt("width", 8);
        old.putBoolean("enabled", false);
        Schema after = new Schema(List.of(
            new Schema.Setting.Flag("enabled", "", STONE, true),
            new Schema.Setting.Count("width", "", STONE, 1, 4, 2),
            new Schema.Setting.Choice("material", "", STONE, List.of(WOOD), WOOD),
            new Schema.Setting.Flag("roof", "", STONE, true),
            new Schema.Setting.Count("height", "", STONE, 1, 5, 3),
            new Schema.Setting.Choice("trim", "", STONE, List.of(STONE), STONE)));
        Chosen restored = Chosen.normalize(after, old);
        helper.assertFalse(restored.flag("enabled"), "an unchanged knob retains the user's setting");
        helper.assertValueEqual(restored.count("width"), 4, "the changed range bounds the old value");
        helper.assertValueEqual(restored.choice("material"), WOOD, "a removed option uses the new default");
        helper.assertTrue(restored.flag("roof"), "a new flag uses the schema default");
        helper.assertValueEqual(restored.count("height"), 3, "a new count uses the schema default");
        helper.assertValueEqual(restored.choice("trim"), STONE, "a new choice uses the schema default");
        helper.assertValueEqual(Chosen.load(after, restored.save()).save(), restored.save(),
            "normalization produces a complete current payload");
        helper.assertValueEqual(old.getInt("width"), 8, "normalization does not rewrite its source");
        CompoundTag cleared = old.copy();
        cleared.put("materials", new CompoundTag());
        helper.assertTrue(Chosen.normalize(before, cleared).items("materials").isEmpty(),
            "the remembered-settings writer's empty list marker remains explicitly cleared");
        rejects(helper, before, cleared, "the current payload writer omits empty lists instead of writing a marker");
        rejects(helper, after, old, "the strict path never normalizes an older schema implicitly");
        helper.succeed();
    }

    private static Schema schema(int max, List<ResourceLocation> options, ResourceLocation byDefault) {
        return new Schema(List.of(
            new Schema.Setting.Flag("enabled", "", STONE, true),
            new Schema.Setting.Count("width", "", STONE, 1, max, 2),
            new Schema.Setting.Choice("material", "", STONE, options, byDefault),
            Schema.Setting.Items.one("materials", "", STONE)));
    }

    private static void rejects(GameTestHelper helper, Schema schema, CompoundTag saved, String message) {
        boolean rejected = false;
        try {
            Chosen.load(schema, saved);
        } catch (IllegalArgumentException | java.util.NoSuchElementException invalid) {
            rejected = true;
        }
        helper.assertTrue(rejected, message);
    }
}

package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonySettingsGameTests {
    private static final ResourceLocation OWNER = id("settings_test");
    private static final ResourceLocation ZONE = id("settings_zone");
    private static final ResourceLocation PATH = id("settings_path");
    private static final ResourceLocation ICON = ResourceLocation.withDefaultNamespace("paper");
    private static final Schema CURRENT = new Schema(List.of(
        new Schema.Setting.Count("count", "", ICON, 1, 10, 3)));

    @SubscribeEvent
    public static void enroll(Registering event) {
        event.enrollment(OWNER, new Enrollment(List.of(
            new Delegation(ZONE, new Shape.Volume(16), CURRENT),
            new Delegation(PATH, new Shape.Path(16), CURRENT)), CURRENT));
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonySettingsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void drawingSettersConformBeforeSavingAndReloadWithoutChanges(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        try {
            var front = ColonyFront.of(colony);
            var at = helper.absolutePos(BlockPos.ZERO);
            var invalid = ColonySettings.empty().with("count", new ColonySettings.Value.Count(200));
            var zone = front.addZone(level, ZONE, Set.of(at), invalid).orElseThrow();
            var path = front.addPath(level, PATH, List.of(at, at.east()), invalid).orElseThrow();
            helper.assertValueEqual(zone.settings().count("count"), 3, "zone writes use the current schema");
            helper.assertValueEqual(path.settings().count("count"), 3, "path writes use the same schema boundary");
            helper.assertFalse(front.setDrawn(path.id(), invalid), "equivalent normalized settings are a no-op");
            helper.assertTrue(front.setDrawn(path.id(), invalid.with("count", new ColonySettings.Value.Count(7))),
                "a valid change is applied");
            helper.assertTrue(front.setDrawn(path.id(), invalid), "invalid values become defaults before saving");
            CompoundTag saved = colony.kept(ColonyFront.OWNER).copy();
            ColonyFront.forget(colony.id());
            var restored = ColonyFront.of(colony);
            helper.assertValueEqual(restored.path(path.id()).orElseThrow().settings(), path.settings(),
                "path settings need no repair after reload");
            helper.assertValueEqual(restored.zone(zone.id()).orElseThrow().settings(), zone.settings(),
                "zone settings need no repair after reload");
            helper.assertValueEqual(colony.kept(ColonyFront.OWNER), saved, "read leaves the saved settings intact");
        } finally {
            ColonyFront.forget(colony.id());
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void schemaChangesNormalizeWellFormedValuesAndPreserveInheritance(GameTestHelper helper) {
        var oldSchema = new Schema(List.of(
            new Schema.Setting.Count("count", "", ICON, 0, 200, 100),
            new Schema.Setting.Flag("changed_type", "", ICON, true),
            new Schema.Setting.Choice("choice", "", ICON, List.of(id("old")), id("old"))));
        var newSchema = new Schema(List.of(
            CURRENT.settings().getFirst(),
            new Schema.Setting.Count("changed_type", "", ICON, 1, 5, 2),
            new Schema.Setting.Choice("choice", "", ICON, List.of(id("new")), id("new")),
            new Schema.Setting.Flag("added", "", ICON, true),
            new Schema.Setting.Items("tool", "", ICON, List.of(ItemFilter.item(ICON)))));
        var old = ColonySettings.byDefault(oldSchema).with("absent_plugin", new ColonySettings.Value.Flag(true));
        var loaded = ColonySettings.load(Reader.of(old.save()));
        var current = loaded.conformedTo(newSchema, Set.of("tool"));
        helper.assertValueEqual(current.count("count"), 3, "a narrowed external range uses its new default");
        helper.assertValueEqual(current.count("changed_type"), 2, "an external type change uses its new default");
        helper.assertValueEqual(current.choice("choice"), id("new"), "removed choices use the new default");
        helper.assertTrue(current.flag("added") && current.flag("absent_plugin"),
            "new schema fields gain defaults and absent plugins keep their keys");
        helper.assertFalse(current.keys().contains("tool"), "an omitted override keeps inheriting the current kit");
        helper.assertValueEqual(loaded, old, "format loading preserves well-formed values before normalization");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedSettingsAreRejectedBeforeSchemaNormalization(GameTestHelper helper) {
        CompoundTag valid = ColonySettings.empty().with("count", new ColonySettings.Value.Count(3)).save();
        for (int fault = 0; fault < 6; fault++) {
            CompoundTag broken = valid.copy();
            switch (fault) {
                case 0 -> broken.put("count", new CompoundTag());
                case 1 -> broken.putString("count", "not a value compound");
                case 2 -> broken.getCompound("count").putString("count", "wrong numeric type");
                case 3 -> broken.getCompound("count").putBoolean("flag", true);
                case 4 -> broken.put("count", Writer.of().string("items", "not a list").tag());
                case 5 -> {
                    ListTag items = new ListTag();
                    items.add(Writer.of().id("item", ICON).tag());
                    items.add(new CompoundTag());
                    CompoundTag value = new CompoundTag();
                    value.put("items", items);
                    broken.put("count", value);
                }
            }
            boolean rejected = false;
            try {
                ColonySettings.load(Reader.of(broken)).conformedTo(CURRENT);
            } catch (RuntimeException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "malformed values cannot be discarded or repaired: " + fault);
        }
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            colony.keep(ColonyFront.OWNER, Writer.of().blob("drawn", new CompoundTag())
                .blob("settings", Writer.of().string(OWNER.toString(), "not a settings compound").tag()).tag());
            boolean rejected = false;
            try {
                ColonyFront.of(colony);
            } catch (RuntimeException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "a malformed scope rejects the whole front instead of keeping fresh defaults");
        } finally {
            ColonyFront.forget(colony.id());
            colony.raze();
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_test", path);
    }
}

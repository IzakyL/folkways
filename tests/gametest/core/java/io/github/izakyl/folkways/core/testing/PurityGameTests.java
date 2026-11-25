package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.resident.internal.FolkwaysAttachments;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySites;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PurityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PurityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void observerDetectsLootAndAttachmentMaterialization(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(at);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 7L);
        assertDetected(helper, "loot generation", () -> chest.getItem(0));
        var person = PersonBody.RESIDENT.get().create(level);
        person.setPos(at.getCenter());
        level.addFreshEntity(person);
        person.removeData(FolkwaysAttachments.LICENCES);
        try {
            assertDetected(helper, "attachment creation", () -> person.getData(
                FolkwaysAttachments.LICENCES));
        } finally {
            person.discard();
        }
        helper.succeed();
    }

    private static void assertDetected(GameTestHelper helper, String label, Runnable write) {
        boolean detected = false;
        try {
            Purity.assertPure(helper, label, write);
        } catch (GameTestAssertException expected) {
            detected = true;
        }
        helper.assertTrue(detected, "observer must detect " + label);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedAndMissingContainersStayUnmaterialized(GameTestHelper helper) {
        var level = helper.getLevel();
        var absent = new BlockPos(28000000, 64, 28000000);
        helper.assertTrue(!level.isLoaded(absent), "fixture uses an unloaded chunk");
        Purity.assertPure(helper, "unloaded container", () -> {
            helper.assertTrue(Containers.at(level, absent).isEmpty(), "unloaded container is unknown");
            Containers.anchor(level, absent);
            helper.assertTrue(ColonySites.accepting(level, absent).isEmpty(), "unloaded site is unknown");
        });
        var at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        level.removeBlockEntity(at);
        Purity.assertPure(helper, "missing block entity", () -> {
            helper.assertTrue(Containers.at(level, at).isEmpty(), "lookup does not create the missing chest entity");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void observerDetectsWrites(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            Purity.assertPure(helper, "observer", () -> {
            });
            boolean detected = false;
            try {
                Purity.assertPure(helper, "deliberate write", () -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putBoolean("written", true);
                    colony.keep(ResourceLocation.fromNamespaceAndPath("folkways_test", "purity"), tag);
                });
            } catch (GameTestAssertException expected) {
                detected = expected.getMessage().contains("colonies");
            }
            helper.assertTrue(detected, "observer must detect a deliberate keep write");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}

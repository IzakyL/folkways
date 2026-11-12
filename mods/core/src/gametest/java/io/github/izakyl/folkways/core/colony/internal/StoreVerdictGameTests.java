package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoreVerdictGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StoreVerdictGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void lastingVerdictSurvivesSaveAndClearsOnRegistration(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var owner = ResourceLocation.fromNamespaceAndPath("folkways_test", "verdict");
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        try {
            var claim = colony.hold(owner, new Claimed.Block(at)).orElseThrow();
            colony.stores(owner, level.dimension(), List.of(at));
            Component reason = Component.translatable("folkways.store.unusable", Component.literal("probe"));
            StorePorts.condemn(level, at, reason);
            helper.assertTrue(StorePorts.condemned(at)
                .map(found -> found.getString().equals(reason.getString())).orElse(false),
                "the verdict is queryable: " + StorePorts.condemned(at));
            helper.assertTrue(StorePorts.at(level, cell).orElseThrow().usability()
                instanceof Usability.Unusable unusable && unusable.lasting(), "resolution applies the lasting verdict");
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            ColonyData loaded = ColonyData.load(Reader.of(data.save(new CompoundTag()))).orElseThrow();
            var bag = ResourceLocation.fromNamespaceAndPath("folkways_core", "store_verdicts");
            helper.assertTrue(loaded.works().kept(bag).equals(colony.kept(bag))
                && !loaded.works().kept(bag).isEmpty(),
                "the colony writer and reader preserve the Component and address");
            InventoryObservations.publish(colony, level);
            helper.assertTrue(colony.inventory().snapshot().store(at).orElseThrow().usability()
                instanceof Usability.Unusable unusable && unusable.lasting(), "observation keeps the verdict");
            colony.release(claim.id(), ClaimRelease.LET_GO);
            colony.hold(owner, new Claimed.Block(at)).orElseThrow();
            helper.assertTrue(StorePorts.condemned(at).isEmpty()
                && StorePorts.at(level, cell).orElseThrow().usability().usable(), "registration clears the verdict");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}

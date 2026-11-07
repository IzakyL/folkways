package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.core.colony.internal.ColonyWorks;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StationInventoryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StationInventoryGameTests.class); }

    @GameTest(template = "empty")
    public static void rackPublicationUsesObservationsAndKeepsRulesDuringIncompleteMembership(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rackCell = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos barrelCell = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(rackCell, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
        level.setBlockAndUpdate(barrelCell, Blocks.BARREL.defaultBlockState());
        CountingBarrel barrel = new CountingBarrel(barrelCell, level.getBlockState(barrelCell));
        level.removeBlockEntity(barrelCell);
        level.setBlockEntity(barrel);
        KitchenRacks.register();
        var data = ColonyRegistry.get(level.getServer()).mint(level.getServer());
        var colony = data.works();
        var body = PersonBody.RESIDENT.get().create(level);
        body.mob().setNoAi(true);
        body.mob().moveTo(barrelCell.getCenter());
        level.addFreshEntity(body.mob());
        CountingPack pack = new CountingPack();
        try {
            var field = body.pack().getClass().getDeclaredField("container");
            field.setAccessible(true);
            field.set(body.pack(), pack);
            colony.hold(body.kind().id(), Claimed.Entity.of(body.mob())).orElseThrow();
            WorldPos rack = WorldPos.of(level, rackCell);
            WorldPos store = WorldPos.of(level, barrelCell);
            colony.stores(CookeryContent.ID, level.dimension(), List.of(rack, store));
            InventoryObservations.publish(data, level);
            CookeryPresence presence = colony.service(CookeryContent.ID, CookeryPresence.class).orElseThrow();
            presence.refresh(level.getServer());
            helper.assertTrue(((ColonyWorks) colony).rulesIn(level.dimension()).containsKey(rack),
                "the observed rack receives its preference");
            int containerReads = barrel.reads;
            int packReads = pack.reads;
            var version = colony.inventory().snapshot().version();
            for (int pass = 0; pass < 50; pass++) {
                presence.refresh(level.getServer());
            }
            helper.assertValueEqual(barrel.reads, containerReads, "unchanged refreshes do not read store slots");
            helper.assertValueEqual(pack.reads, packReads, "unchanged refreshes do not read resident packs");
            helper.assertValueEqual(colony.inventory().snapshot().version(), version,
                "refreshing kitchen rules does not invalidate inventory observations");

            barrel.setItem(0, new ItemStack(Items.APPLE, 3));
            body.pack().setItem(0, new ItemStack(Items.BREAD, 2));
            colony.inventoryFeedback().changed(Stash.at(store));
            colony.inventoryFeedback().changedResident(body.id());
            InventoryObservations.publish(data, level);
            var snapshot = colony.inventory().snapshot();
            helper.assertTrue(snapshot.store(store).orElseThrow().slots().getFirst().stackCopy().is(Items.APPLE),
                "store mutation feedback publishes the new item");
            helper.assertTrue(snapshot.residents().get(body.id()).pack().getFirst().stackCopy().is(Items.BREAD),
                "resident mutation feedback publishes the new item");

            List<WorldPos> next = new ArrayList<>();
            next.add(store);
            for (int index = 0; index < 130; index++) {
                next.add(WorldPos.of(level.dimension(), new BlockPos(21_000_000 + index, 80, 21_000_000)));
            }
            colony.stores(CookeryContent.ID, level.dimension(), next);
            helper.assertTrue(colony.inventory().snapshot().coverage().membershipPending(),
                "membership change is pending before discovery");
            presence.refresh(level.getServer());
            helper.assertTrue(((ColonyWorks) colony).rulesIn(level.dimension()).containsKey(rack),
                "pending membership cannot erase the previously observed rack rule");
            InventoryObservations.publish(data, level);
            presence.refresh(level.getServer());
            helper.assertFalse(((ColonyWorks) colony).rulesIn(level.dimension()).containsKey(rack),
                "completed removal withdraws the rack rule");
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        } finally {
            colony.raze();
            body.mob().discard();
        }
        helper.succeed();
    }

    private static final class CountingBarrel extends BarrelBlockEntity {
        private int reads;

        private CountingBarrel(BlockPos pos, BlockState state) { super(pos, state); }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }
    }

    private static final class CountingPack extends SimpleContainer {
        private int reads;

        private CountingPack() { super(18); }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }
    }
}

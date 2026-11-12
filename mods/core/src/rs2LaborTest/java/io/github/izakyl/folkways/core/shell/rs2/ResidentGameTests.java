package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ResidentGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (Boolean.getBoolean("folkways.rs2.laborTests")) {
            event.register(ResidentGameTests.class);
        }
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void residentsDeliverThroughTwoDistantTerminals(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        BlockPos far = new BlockPos(4, 2, 25);
        for (int z = 2; z <= 25; z++) {
            helper.setBlock(new BlockPos(7, 2, z),
                com.refinedmods.refinedstorage.common.content.Blocks.INSTANCE.getCable().getDefault());
        }
        for (int x = 5; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 2, 25),
                com.refinedmods.refinedstorage.common.content.Blocks.INSTANCE.getCable().getDefault());
        }
        helper.setBlock(far, com.refinedmods.refinedstorage.common.content.Blocks.INSTANCE.getGrid().getDefault());
        helper.startSequence().thenIdle(120).thenExecute(() ->
            io.github.izakyl.folkways.core.testing.StorageTrunkChecks.deliver(helper, NetworkRig.TERMINAL,
                far, () -> rig.count(Items.WHEAT)));
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void residentHaulsNetworkStockToRegisteredBarrel(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            rig.stock(Items.WHEAT, 128);
            deliver(helper, Items.WHEAT, 32);
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 4000)
    public static void residentOrdersCraftingAndDeliversItsOutput(GameTestHelper helper) {
        NetworkRig rig = CraftingGameTests.craftingRig(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            rig.stock(Items.OAK_LOG, 8);
            deliver(helper, Items.OAK_PLANKS, 16);
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void residentReturnsGoodsToRsNetwork(GameTestHelper helper) {
        new NetworkRig(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> deliver(helper, Items.WHEAT, 32, true));
    }

    private static void deliver(GameTestHelper helper, Item item, long count) {
        deliver(helper, item, count, false);
    }

    private static void deliver(GameTestHelper helper, Item item, long count, boolean intoNetwork) {
        var level = helper.getLevel();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos destination = helper.absolutePos(intoNetwork ? NetworkRig.TERMINAL : new BlockPos(7, 2, 2));
        if (!intoNetwork) {
            level.setBlockAndUpdate(destination, Blocks.BARREL.defaultBlockState());
        }
        Colony colony = Colonies.mint(level.getServer());
        for (BlockPos pos : List.of(helper.absolutePos(NetworkRig.TERMINAL),
                helper.absolutePos(NetworkRig.OTHER), destination).stream().distinct().toList()) {
            colony.hold(WaresContent.STORE, new Claimed.Block(WorldPos.of(level, pos))).orElseThrow();
        }
        ResidentEntity person = (ResidentEntity) BuiltInRegistries.ENTITY_TYPE.get(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "resident")).create(level);
        person.moveTo(helper.absolutePos(new BlockPos(6, 2, 4)).getBottomCenter());
        level.addFreshEntity(person);
        colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
        person.joinColony(colony.id());
        colony.entered(person);
        person.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        if (intoNetwork) {
            person.pack().setItem(1, new ItemStack(item, (int) count));
        }
        WorkSite site = WorkSite.at(level, destination);
        Delivery delivery = Delivery.to(UUID.randomUUID(), Rs2Integration.OWNER, site,
            Stances.at(level, destination.south()), ItemSpec.of(item), count, arrived -> { });
        AtomicReference<Ending> finished = new AtomicReference<>();
        colony.submit(Rs2Integration.OWNER, level.dimension(), Grown.of(delivery),
            (id, ending) -> finished.set(ending));
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean closed = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            colony.raze();
            person.discard();
        });
        helper.onEachTick(() -> {
            if (!closed.get()) {
                labor.tick();
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(intoNetwork
                ? Devices.at(level, destination).orElseThrow().storage().get(new ItemResource(item)) == count
                : Goods.countIn((Container) level.getBlockEntity(destination), ItemSpec.of(item)) == count,
            "the resident completes the whole delivery; diagnosis=" + labor.diagnosis()
                + "; stalls=" + colony.stallsIn(level.dimension()) + "; route=" + labor.route(person)
                + "; pack=" + person.pack().count(item) + "; at=" + person.blockPosition()
                + "; ending=" + finished.get()
                + "; destination=" + (intoNetwork ? -1
                    : Goods.countIn((Container) level.getBlockEntity(destination), ItemSpec.of(item)))));
    }
}

package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class StorageTrunkChecks {

    private StorageTrunkChecks() {
    }

    public static void deliver(GameTestHelper helper, BlockPos near, BlockPos far, LongSupplier networkCount) {
        var level = helper.getLevel();
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 29; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos firstStand = near.north();
        BlockPos lastStand = far.north();
        BlockPos source = firstStand.east();
        BlockPos destination = lastStand.west();
        helper.setBlock(source, Blocks.BARREL);
        helper.setBlock(destination, Blocks.BARREL);
        Container from = (Container) level.getBlockEntity(helper.absolutePos(source));
        Container into = (Container) level.getBlockEntity(helper.absolutePos(destination));
        from.setItem(0, new ItemStack(Items.WHEAT, 32));
        Colony colony = Colonies.mint(level.getServer());
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        for (BlockPos pos : List.of(near, far, source, destination)) {
            colony.hold(WaresContent.STORE,
                new Claimed.Block(WorldPos.of(level, helper.absolutePos(pos)))).orElseThrow();
        }
        ResidentEntity first = resident(helper, colony, firstStand);
        ResidentEntity last = resident(helper, colony, lastStand);
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "storage_trunk_test");
        Delivery delivery = Delivery.to(UUID.randomUUID(), owner, WorkSite.at(level, helper.absolutePos(destination)),
            Stances.at(level, helper.absolutePos(lastStand)), ItemSpec.of(Items.WHEAT), 32, count -> { });
        colony.submit(owner, level.dimension(), Grown.of(delivery), (id, ending) -> { });
        LaborFixture labor = new LaborFixture(colony, level);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean deposited = new AtomicBoolean();
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            first.discard();
            last.discard();
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (!closed.get()) {
                labor.tick();
                if (networkCount.getAsLong() > 0) {
                    deposited.set(true);
                }
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(deposited.get()
                && Goods.countIn(into, ItemSpec.of(Items.WHEAT)) == 32
                && Goods.countIn(from, ItemSpec.of(Items.WHEAT)) == 0 && networkCount.getAsLong() == 0
                && first.pack().count(Items.WHEAT) == 0 && last.pack().count(Items.WHEAT) == 0,
            "residents deliver the real batch through both native terminals; deposited=" + deposited.get()
                + "; diagnosis=" + labor.diagnosis() + "; first=" + labor.route(first)
                + "; last=" + labor.route(last)));
    }

    private static ResidentEntity resident(GameTestHelper helper, Colony colony, BlockPos stand) {
        var level = helper.getLevel();
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(helper.absolutePos(stand).getBottomCenter());
        body.setNoAi(true);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        body.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
        return body;
    }
}

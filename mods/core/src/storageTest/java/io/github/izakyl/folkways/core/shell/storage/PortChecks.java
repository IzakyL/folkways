package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class PortChecks {

    private PortChecks() {
    }

    public static BlockEntity place(GameTestHelper helper, BlockPos relative, String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(key), "native block exists: " + id);
        helper.setBlock(relative, BuiltInRegistries.BLOCK.get(key));
        return helper.getLevel().getBlockEntity(helper.absolutePos(relative));
    }

    public static ItemStack item(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }

    public static StorePort at(GameTestHelper helper, BlockPos relative) {
        return StorePorts.at(helper.getLevel(), helper.absolutePos(relative)).orElseThrow();
    }

    public static PortReader.PortReading read(GameTestHelper helper, StorePort port) {
        PortReader reader = port.reader(helper.getLevel(), List.of());
        int work = 0;
        while (!reader.step(1)) {
            helper.assertTrue(work++ < 10000, "bounded reader completes");
        }
        return reader.result();
    }

    public static long count(GameTestHelper helper, StorePort port) {
        return read(helper, port).entries().stream().mapToLong(PortReader.Entry::count).sum();
    }

    public static void roundTrip(GameTestHelper helper, BlockPos pos, boolean keyed) {
        StorePort port = at(helper, pos);
        helper.assertTrue(port.usability().usable(), "plain native storage usable");
        PortReader.PortReading empty = read(helper, port);
        helper.assertTrue(empty.entries().isEmpty(), "starts empty");
        if (keyed) {
            helper.assertTrue(empty.room() instanceof Room.Slots room && room.open() > 0
                && room.cap().limit(Items.DIAMOND_PICKAXE.getDefaultInstance()) > 0,
                "native drawers expose open keyed cells and capacity");
        } else {
            helper.assertTrue(empty.slots().isPresent() && empty.room() instanceof Room.Slots room
                && room.open() > 0 && room.cap().limit(Items.STONE.getDefaultInstance()) >= 64,
                "native main slots and capacity");
        }
        long revision = port.revision().orElseThrow();
        List<ItemStack> goods = List.of(new ItemStack(Items.STONE, 40), new ItemStack(Items.STONE, 40));
        helper.assertTrue(port.put(helper.getLevel(), goods, List.of(), false, true).done(),
            "batch insertion simulates");
        helper.assertTrue(count(helper, port) == 0, "simulation does not insert");
        helper.assertTrue(port.put(helper.getLevel(), goods, List.of(), false, false).done(), "batch inserts");
        helper.assertTrue(count(helper, port) == 80, "entries sum physical stock");
        helper.assertTrue(port.revision().orElseThrow() != revision, "native changes alter the revision");
        List<Pull> tooMuch = List.of(new Pull.Exact(new ItemStack(Items.STONE, 60)),
            new Pull.Exact(new ItemStack(Items.STONE, 60)));
        helper.assertTrue(!port.take(helper.getLevel(), tooMuch, List.of(), true).done(), "aggregate take refuses");
        helper.assertTrue(!port.take(helper.getLevel(), tooMuch, List.of(), false).done(),
            "real aggregate take refuses");
        helper.assertTrue(count(helper, port) == 80, "failed take leaves every cell intact");
        List<ItemStack> overflow = List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT, Integer.MAX_VALUE));
        helper.assertTrue(!port.put(helper.getLevel(), overflow, List.of(), false, false).done(),
            "batch overflow refuses");
        helper.assertTrue(count(helper, port) == 80, "failed put leaves every cell intact");
        WorldPos where = WorldPos.of(helper.getLevel(), helper.absolutePos(pos));
        List<StoreRule> rules = List.of(new StoreRule.Admit(where, true, List.of()));
        helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE)), rules, false, false).done(),
            "ordinary admission rules hold");
        helper.assertTrue(port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE)), rules, true, false).done(),
            "directed orders bypass colony admission");
        List<Pull> all = List.of(new Pull.Matching(ItemSpec.of(Items.STONE), 81));
        helper.assertTrue(port.take(helper.getLevel(), all, List.of(), true).done(), "take simulates");
        helper.assertTrue(count(helper, port) == 81, "simulation does not extract");
        TransferResult result = port.take(helper.getLevel(), all, List.of(), false);
        helper.assertTrue(result instanceof TransferResult.Done done
            && done.moved().stream().mapToInt(ItemStack::getCount).sum() == 81, "take returns all goods");
        helper.assertTrue(count(helper, port) == 0, "complete round trip");
    }

    public static void unusable(GameTestHelper helper, BlockPos pos) {
        StorePort port = at(helper, pos);
        helper.assertTrue(port.usability() instanceof Usability.Unusable verdict && !verdict.lasting(),
            "configuration refusal automatically recoverable");
        helper.assertTrue(read(helper, port).room().equals(Room.NONE), "unusable storage has no room");
        helper.assertTrue(read(helper, port).entries().isEmpty(), "unusable stock is not counted");
        helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE)), List.of(),
            true, false).done(), "even directed insertion refuses unusable storage");
        helper.assertTrue(!port.take(helper.getLevel(), List.of(new Pull.Exact(new ItemStack(Items.STONE))),
            List.of(), false).done(), "unusable storage cannot be extracted");
    }
}

package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

public final class KitchenDelivery {

    private KitchenDelivery() {
    }

    public record Result(boolean success, List<ItemStack> outputs) {
        public Result {
            outputs = List.copyOf(outputs);
        }
    }

    public static Result run(ServerLevel level, BlockPos at, Worker who, ItemStack hand,
                             Supplier<Boolean> action) {
        Mob body = who.body();
        ItemStack previous = body.getMainHandItem();
        List<AABB> bounds = new ArrayList<>(List.of(new AABB(at).inflate(1.0),
            body.getBoundingBox().inflate(1.0)));
        WorldSpaces.world(level, WorldPos.of(level, at))
            .ifPresent(world -> bounds.add(AABB.ofSize(world, 3.0, 3.0, 3.0)));
        body.setItemInHand(InteractionHand.MAIN_HAND, hand);
        try {
            ItemDrops.Captured<Boolean> captured = ItemDrops.capture(level, bounds, action);
            List<ItemStack> outputs = new ArrayList<>(captured.items());
            ItemStack returned = body.getMainHandItem();
            if (returned != hand && !returned.isEmpty()) {
                outputs.add(returned.copy());
            }
            return new Result(captured.result(), outputs);
        } finally {
            body.setItemInHand(InteractionHand.MAIN_HAND, previous);
        }
    }
}
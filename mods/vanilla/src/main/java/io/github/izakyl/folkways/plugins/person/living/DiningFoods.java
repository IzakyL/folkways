package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Placed meals whose native block interaction can safely be performed by a resident. */
public final class DiningFoods {
    public interface Food {
        boolean supports(ItemStack stack);
        Optional<Serving> serving(ServerLevel level, BlockPos at);
        /** Consumes the actual supplied stack only when placement succeeds. */
        boolean place(ServerLevel level, BlockPos at, ItemStack actual);
        /** Returns real leftovers; throws if no portion could be consumed. */
        List<ItemStack> eat(ServerLevel level, BlockPos at, Worker who);
    }

    public record Serving(ItemStack item, FoodProperties food) {
        public Serving { item = item.copyWithCount(1); }
        @Override public ItemStack item() { return item.copy(); }
    }

    private static final Map<ResourceLocation, Food> REGISTERED = new LinkedHashMap<>();
    private static volatile List<Food> foods = List.of(new Cake());

    private DiningFoods() { }

    public static synchronized void register(ResourceLocation id, Food food) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(food, "food");
        if (REGISTERED.putIfAbsent(id, food) == null) {
            var all = new java.util.ArrayList<Food>();
            all.add(new Cake());
            all.addAll(REGISTERED.values());
            foods = List.copyOf(all);
        }
    }

    public static boolean placeable(ItemStack stack) {
        return !stack.isEmpty() && foods.stream().anyMatch(food -> food.supports(stack));
    }

    public static Optional<Serving> serving(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Optional.empty();
        for (Food food : foods) {
            Optional<Serving> serving = food.serving(level, at);
            if (serving.isPresent()) return serving;
        }
        return Optional.empty();
    }

    public static boolean place(ServerLevel level, BlockPos at, ItemStack actual) {
        if (actual.isEmpty() || !level.isLoaded(at)) return false;
        for (Food food : foods) {
            if (food.supports(actual)) return food.place(level, at, actual);
        }
        return false;
    }

    public static List<ItemStack> eat(ServerLevel level, BlockPos at, Worker who) {
        if (level.isLoaded(at)) {
            for (Food food : foods) {
                if (food.serving(level, at).isPresent()) return food.eat(level, at, who);
            }
        }
        throw new IllegalStateException("the placed meal is no longer available");
    }

    /** Native placement preserves the item's own state-for-placement and placement hooks. */
    public static boolean placeBlock(ServerLevel level, BlockPos at, ItemStack actual) {
        if (!(actual.getItem() instanceof BlockItem item) || !level.isLoaded(at)
                || !level.getBlockState(at).isAir()) return false;
        var hit = new BlockHitResult(Vec3.atBottomCenterOf(at), Direction.UP, at.below(), false);
        return item.place(new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, actual, hit)).consumesAction();
    }

    public static void eaten(ServerLevel level, BlockPos at, Worker who) {
        level.playSound(null, at, SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.5F,
            0.9F + level.random.nextFloat() * 0.1F);
        level.gameEvent(who.body(), GameEvent.EAT, at);
    }

    private static final class Cake implements Food {
        private static final FoodProperties BITE = new FoodProperties.Builder().nutrition(2).saturationModifier(0.1F).build();
        @Override public boolean supports(ItemStack stack) { return stack.is(Items.CAKE); }
        @Override public Optional<Serving> serving(ServerLevel level, BlockPos at) {
            return level.getBlockState(at).getBlock() instanceof CakeBlock
                ? Optional.of(new Serving(new ItemStack(Items.CAKE), BITE)) : Optional.empty();
        }
        @Override public boolean place(ServerLevel level, BlockPos at, ItemStack actual) {
            return placeBlock(level, at, actual);
        }
        @Override public List<ItemStack> eat(ServerLevel level, BlockPos at, Worker who) {
            var state = level.getBlockState(at);
            if (!(state.getBlock() instanceof CakeBlock)) throw new IllegalStateException("cake was removed");
            int bites = state.getValue(CakeBlock.BITES);
            if (!level.setBlockAndUpdate(at, bites < CakeBlock.MAX_BITES
                    ? state.setValue(CakeBlock.BITES, bites + 1) : Blocks.AIR.defaultBlockState())) {
                throw new IllegalStateException("cake could not be eaten");
            }
            eaten(level, at, who);
            return List.of();
        }
    }
}

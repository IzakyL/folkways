package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteOneByTwoBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteThreeByThreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.living.DiningFoods;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The native FoodBite interaction, with its Player-only hunger update owned by living instead. */
public final class CookeryDining {
    private CookeryDining() { }

    public static void register() {
        DiningFoods.register(ResourceLocation.fromNamespaceAndPath("folkways", "cookery_dining"), new Meal());
    }

    private static boolean supported(Block block) {
        // Large dishes place additional blocks outside the requested tabletop cell. Real table slots still
        // accept their item form; only single-cell dishes participate in placed-block dining here.
        return block instanceof FoodBiteBlock && !(block instanceof FoodBiteOneByTwoBlock)
            && !(block instanceof FoodBiteThreeByThreeBlock)
            && FoodBiteRegistry.FOOD_DATA_MAP.containsKey(BuiltInRegistries.BLOCK.getKey(block));
    }

    private static final class Meal implements DiningFoods.Food {
        @Override public boolean supports(ItemStack stack) {
            return stack.getItem() instanceof BlockItem item && supported(item.getBlock());
        }

        @Override public Optional<DiningFoods.Serving> serving(ServerLevel level, BlockPos at) {
            BlockState state = level.getBlockState(at);
            if (!supported(state.getBlock())) return Optional.empty();
            FoodBiteBlock block = (FoodBiteBlock) state.getBlock();
            if (state.getValue(block.getBites()) >= block.getMaxBites()) return Optional.empty();
            ItemStack identity = new ItemStack(block);
            int quality = state.getValue(FoodBiteBlock.QUALITY);
            double ratio = 1.0D;
            if (quality != FoodBiteBlock.DEFAULT_QUALITY) {
                Quality actual = Quality.BY_ID.apply(quality);
                QualityUtils.setQuality(identity, actual);
                ratio = actual.getRatio();
            }
            FoodProperties nativeBite = FoodBiteRegistry.FOOD_DATA_MAP.get(
                BuiltInRegistries.BLOCK.getKey(block)).blockFood();
            double scale = ratio;
            List<FoodProperties.PossibleEffect> effects = nativeBite.effects().stream()
                .map(effect -> new FoodProperties.PossibleEffect(() -> new MobEffectInstance(effect.effect().getEffect(),
                    (int) Math.round(effect.effect().getDuration() * scale), effect.effect().getAmplifier()),
                    effect.probability())).toList();
            FoodProperties bite = new FoodProperties((int) Math.round(nativeBite.nutrition() * ratio),
                (float) (nativeBite.saturation() * ratio), nativeBite.canAlwaysEat(), nativeBite.eatSeconds(),
                Optional.empty(), effects);
            return Optional.of(new DiningFoods.Serving(identity, bite));
        }

        @Override public boolean place(ServerLevel level, BlockPos at, ItemStack actual) {
            return DiningFoods.placeBlock(level, at, actual);
        }

        @Override public List<ItemStack> eat(ServerLevel level, BlockPos at, Worker who) {
            DiningFoods.Serving serving = serving(level, at).orElseThrow(
                () -> new IllegalStateException("the dish has no remaining bites"));
            BlockState state = level.getBlockState(at);
            FoodBiteBlock block = (FoodBiteBlock) state.getBlock();
            int bites = state.getValue(block.getBites()) + 1;
            BlockState after = state.setValue(block.getBites(), bites);
            // Native right-click destroys the empty dish on the following click. Settle that empty dish
            // together with the last bite, taking its actual final-state loot exactly once.
            List<ItemStack> leftovers = bites == block.getMaxBites()
                ? Block.getDrops(after, level, at, level.getBlockEntity(at), who.body(), ItemStack.EMPTY) : List.of();
            if (!level.setBlockAndUpdate(at,
                    bites == block.getMaxBites() ? Blocks.AIR.defaultBlockState() : after)) {
                throw new IllegalStateException("the dish could not be eaten");
            }
            for (var effect : serving.food().effects()) {
                if (level.random.nextFloat() < effect.probability()) {
                    who.body().addEffect(new MobEffectInstance(effect.effect()));
                }
            }
            DiningFoods.eaten(level, at, who);
            return leftovers;
        }
    }
}

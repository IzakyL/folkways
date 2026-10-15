package io.github.izakyl.folkways.plugins.cookery.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.TeaTreeBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModEnchantments;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import com.github.ysbbbbbb.kaleidoscopecookery.item.SickleItem;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.plugins.farming.FarmRefusal;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.farming.HarvestMethod;
import io.github.izakyl.folkways.plugins.farming.HarvestMethods;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.NeoForge;

/** Safe single-root fallback: a sweep never touches a cell outside this job's existing farm claims. */
public final class CookerySickle implements HarvestMethod {
    public static final ResourceLocation MODE = ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "sickle");
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("folkways", "cookery_sickles"));
    public static final ToolNeed SICKLE = new ToolNeed.Tagged(TAG);
    private static final String READY_AT = "folkways:sickle_ready_at";
    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        HarvestMethods.register(MODE, new CookerySickle());
        Tools.shared(SICKLE, ItemSpec.of(TAG));
        registered = true;
    }

    @Override
    public boolean supports(Block crop) {
        return crop instanceof CropBlock || crop instanceof TeaTreeBlock;
    }

    @Override
    public List<ToolUse> tools() {
        // The native 1/2/4/8 cost is applied once per successful invocation, including breakage.
        return List.of(new ToolUse(SICKLE, 0));
    }

    @Override
    public Outcome harvest(ServerLevel level, Worker who, Block crop, List<BlockPos> approved) {
        ItemStack sickle = who.held(SICKLE);
        if (!(sickle.getItem() instanceof SickleItem)) return Outcome.failed(FarmRefusal.NO_SICKLE);
        if (who.body().getPersistentData().getLong(READY_AT) > level.getGameTime()) {
            return Outcome.failed(FarmRefusal.SICKLE_COOLDOWN);
        }
        int sweep = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
            .getHolder(ModEnchantments.SWEEP).map(sickle::getEnchantmentLevel).orElse(0);
        int radius = 2 + sweep;
        BlockPos center = approved.isEmpty() ? who.body().blockPosition() : approved.getFirst();
        List<ItemStack> made = new ArrayList<>();
        List<WorkNoise> heard = new ArrayList<>();
        var roots = new LinkedHashSet<BlockPos>();
        boolean cost = false;
        int worked = 0;
        for (BlockPos requested : approved) {
            if (!level.isLoaded(requested) || Math.abs(requested.getX() - center.getX()) > radius
                    || Math.abs(requested.getZ() - center.getZ()) > radius
                    || requested.getY() < center.getY() || requested.getY() > center.getY() + 1) continue;
            BlockState state = level.getBlockState(requested);
            BlockPos root = state.getBlock() instanceof RiceCropBlock
                ? requested.below(state.getValue(RiceCropBlock.LOCATION)) : requested;
            if (!approved.contains(root) || !roots.add(root) || !level.isLoaded(root)) continue;
            state = level.getBlockState(root);
            if (!state.is(crop) || state.is(TagMod.SICKLE_HARVEST_BLACKLIST)) continue;
            ResidentSickleHarvestEvent event = new ResidentSickleHarvestEvent(who.body(), sickle, root, state);
            if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
                cost |= event.isCostDurability();
                continue;
            }
            if (!level.getBlockState(root).equals(state)) continue;
            BlockState next;
            if (crop instanceof TeaTreeBlock tea) {
                if (!tea.isMaxAge(state)) continue;
                next = tea.getStateForAge(0);
            } else if (crop instanceof CropBlock growing) {
                if (!growing.isMaxAge(state)) continue;
                next = growing.getStateForAge(0);
                if (next.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    next = next.setValue(BlockStateProperties.WATERLOGGED, state.getValue(BlockStateProperties.WATERLOGGED));
                }
            } else continue;
            // The upstream sickle passes an empty loot tool, so Fortune and Silk Touch do not gain a
            // new meaning here. Real THIS_ENTITY still permits the worn straw-hat loot condition.
            List<ItemStack> drops = Block.getDrops(state, level, root, level.getBlockEntity(root), who.body(), ItemStack.EMPTY);
            try {
                if (!level.setBlock(root, next, Block.UPDATE_ALL)) continue;
            } catch (RuntimeException | LinkageError broken) {
                if (!level.getBlockState(root).equals(next)) throw broken;
                org.slf4j.LoggerFactory.getLogger("folkways-cookery")
                    .warn("sickle reset {} before its notification failed", root, broken);
            }
            made.addAll(drops);
            heard.add(WorkNoise.broke(root, state));
            cost = true;
            worked++;
        }
        if (cost) {
            int wear = switch (sweep) { case 1 -> 2; case 2 -> 4; case 3 -> 8; default -> 1; };
            sickle.hurtAndBreak(wear, level, who.body(), ignored -> { });
            who.pack().setChanged();
            who.body().getPersistentData().putLong(READY_AT, level.getGameTime() + 10L);
            heard.add(WorkNoise.voice(SoundEvents.PLAYER_ATTACK_SWEEP));
        }
        return new Outcome.Done(heard, made, worked == 0 ? Optional.empty()
            : Optional.of(new Xp(FarmingContent.trade(), worked)));
    }
}

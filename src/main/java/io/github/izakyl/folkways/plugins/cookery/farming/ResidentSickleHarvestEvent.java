package io.github.izakyl.folkways.plugins.cookery.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** The resident counterpart to Cookery's PlayerEvent; it never impersonates a player. */
public final class ResidentSickleHarvestEvent extends Event implements ICancellableEvent {
    private final LivingEntity resident;
    private final ItemStack sickle;
    private final BlockPos pos;
    private final BlockState state;
    private boolean costDurability;

    public ResidentSickleHarvestEvent(LivingEntity resident, ItemStack sickle, BlockPos pos, BlockState state) {
        this.resident = resident;
        this.sickle = sickle;
        this.pos = pos.immutable();
        this.state = state;
    }

    public LivingEntity getEntity() { return resident; }
    public ItemStack getSickle() { return sickle; }
    public BlockPos getHarvestPos() { return pos; }
    public BlockState getHarvestState() { return state; }
    public boolean isCostDurability() { return costDurability; }
    public void setCostDurability(boolean costDurability) { this.costDurability = costDurability; }
}

package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.perk.Perks;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

public interface Body {

    Mob mob();

    ResidentKind kind();

    default UUID id() {
        return mob().getUUID();
    }

    default Resident resident() {
        return new Resident(id(), kind().id());
    }

    private BodyState state() {
        return mob().getData(FolkwaysAttachments.BODY_STATE);
    }

    default Pack pack() {
        return state().pack();
    }

    default Licences licences() {
        return mob().getData(FolkwaysAttachments.LICENCES);
    }

    default Perks perks() {
        return Bodies.perksOf(kind(), state());
    }

    default Optional<Doing> doing() {
        return state().doing();
    }

    default void setDoing(Doing doing) {
        state().nowDoing(doing);
    }

    default void clearDoing() {
        state().nowDoing(null);
    }

    /** The work this body last gave up on, while it is still news; kept only in memory. */
    default Optional<Balk> balk() {
        long now = mob().level().getGameTime();
        return state().balk().filter(balk -> balk.fresh(now));
    }

    default void balked(Balk balk) {
        state().balked(balk);
    }

    default void clearBalk() {
        state().balked(null);
    }

    default Optional<UUID> colonyId() {
        return state().colony();
    }

    default void joinColony(UUID colony) {
        state().bindTo(colony);
        enrolled();
    }

    default void leaveColony() {
        state().unbind();
        dismissed();
    }

    default void enrolled() {
    }

    default void dismissed() {
    }

    default void refreshPerkEffects() {
    }

    default void presentHeldItem(ItemStack copy) {
    }

    default void clearHandPresentation() {
    }

    default void presentCastLine(Optional<BlockPos> cell) {
    }
}

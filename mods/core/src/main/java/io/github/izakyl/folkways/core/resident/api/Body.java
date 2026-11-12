package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.effect.perk.Perks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

public interface Body {

    Mob mob();

    ResidentKind kind();

    default Locomotion locomotion() {
        return Locomotions.current(this);
    }

    default ResourceLocation routeKind() {
        return Locomotions.routeKind(this);
    }

    default UUID id() {
        return mob().getUUID();
    }

    default Resident resident() {
        return new Resident(id(), kind().id(), routeKind());
    }

    private BodyStateAccess state() {
        return Bodies.state(this);
    }

    default Pack pack() {
        Pack pack = state().pack();
        pack.bind(this);
        return pack;
    }


    default Licences licences() {
        return Bodies.licences(this);
    }

    default Perks perks() {
        return state().perks(kind());
    }

    // Everything bearing on this body now.
    default Effects effects() {
        return perks().effects();
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
        state().leaveColony(this);
        dismissed();
    }

    default void enrolled() {
    }

    default void dismissed() {
    }

    // Carries its effects over to what the body is outside them: its attributes, its pack.
    default void refreshEffects() {
        Bodies.carry(this);
    }

    default void presentHeldItem(ItemStack copy) {
    }

    default void clearHandPresentation() {
    }

    default void presentCastLine(Optional<BlockPos> cell) {
    }
}

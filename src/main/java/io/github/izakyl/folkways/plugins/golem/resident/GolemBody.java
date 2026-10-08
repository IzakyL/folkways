package io.github.izakyl.folkways.plugins.golem.resident;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

final class GolemBody implements Body {

    private final AbstractGolemEntity<?, ?> golem;
    private final ResidentKind kind;

    private GolemBody(AbstractGolemEntity<?, ?> golem, ResidentKind kind) {
        this.golem = golem;
        this.kind = kind;
    }

    static Optional<Body> of(AbstractGolemEntity<?, ?> golem, ResidentKind kind) {
        return Optional.of(new GolemBody(golem, kind));
    }

    @Override
    public Mob mob() {
        return golem;
    }

    @Override
    public ResidentKind kind() {
        return kind;
    }

    @Override
    public void enrolled() {
        GolemAi.takeOver(golem);
    }

    @Override
    public void dismissed() {
        GolemAi.release(golem);
    }

    @Override
    public void presentHeldItem(ItemStack copy) {
        golem.setItemInHand(InteractionHand.MAIN_HAND, copy);
    }

    @Override
    public void clearHandPresentation() {
        golem.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    boolean endorsableBy(Player player) {
        UUID owner = golem.getOwnerUUID();
        return owner != null && owner.equals(player.getUUID());
    }
}

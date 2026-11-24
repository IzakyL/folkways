package io.github.izakyl.folkways.core.shell.carts.mixin;

import com.jusipat.astikorcartsredux.entity.PlowEntity;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.shell.carts.CartIntegration;
import io.github.izakyl.folkways.core.shell.carts.CartPlowing;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlowEntity.class, remap = false)
public abstract class PlowMixin implements CartPlowing.State {

    @Shadow @Final
    private static EntityDataAccessor<Boolean> PLOWING;

    @Unique
    private static final GameProfile FOLKWAYS_PLOWER =
        new GameProfile(UUID.fromString("30b51d8c-6191-44ba-95d9-8615b81e0ec5"), "[Folkways plow]");

    @Shadow
    private void plow(ServerPlayer player) {
        throw new AssertionError();
    }

    @Override
    public void folkways$setPlowing(boolean enabled) {
        ((PlowEntity) (Object) this).getEntityData().set(PLOWING, enabled);
    }

    @Inject(method = "pulledTick", at = @At("TAIL"))
    private void folkways$plowWithColonyPuller(CallbackInfo callback) {
        PlowEntity cart = (PlowEntity) (Object) this;
        if (!(cart.level() instanceof ServerLevel level) || !cart.getPlowing()
                || !(cart.getPulling() instanceof Mob puller) || puller.getControllingPassenger() instanceof Player
                || (cart.xo == cart.getX() && cart.zo == cart.getZ())
                || Colonies.all(level.getServer()).stream().noneMatch(colony -> CartIntegration.belongsTo(colony, cart))) {
            return;
        }
        // Native plowing needs a player context even when a colony mob supplies the motion.
        var hands = FakePlayerFactory.get(level, FOLKWAYS_PLOWER);
        hands.moveTo(puller.getX(), puller.getY(), puller.getZ(), puller.getYRot(), puller.getXRot());
        plow(hands);
    }
}

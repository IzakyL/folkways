package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import io.github.izakyl.folkways.core.combat.api.ColonyDamage;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

final class WarfareExplosions {

    private WarfareExplosions() {
    }

    static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof ProjectileEntity projectile) {
            var owner = projectile.getOwner();
            if (owner != null) {
                ColonyDamage.party(owner.getVehicle()).or(() -> ColonyDamage.party(owner))
                    .ifPresent(id -> ColonyDamage.mark(projectile, id));
            }
        }
    }
}

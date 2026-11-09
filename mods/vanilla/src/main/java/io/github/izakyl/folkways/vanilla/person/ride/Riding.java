package io.github.izakyl.folkways.vanilla.person.ride;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Search;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.vanilla.person.walk.Walking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.phys.Vec3;

public final class Riding implements Locomotion {
    public static final Riding INSTANCE = new Riding();
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "riding");

    private Riding() {
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public NodeEvaluator newEvaluator(Search search) {
        // A journey includes its walking last leg. Horse clearance is checked separately on the ridden leg.
        return Walking.INSTANCE.newEvaluator(search);
    }

    @Override
    public void fit(Mob body) {
        Walking.INSTANCE.fit(body);
    }

    @Override
    public void stand(Mob body) {
        ResidentMounts.dismount(body);
    }

    @Override
    public void stand(Mob body, boolean mountedWork) {
        if (mountedWork) {
            ResidentMounts.horse(body).filter(horse -> body.getVehicle() == horse)
                .ifPresent(horse -> horse.getNavigation().stop());
        } else {
            stand(body);
        }
    }

    @Override
    public Vec3 feet(Mob body) {
        return ResidentMounts.horse(body).filter(horse -> body.getVehicle() == horse)
            .map(horse -> horse.position()).orElse(body.position());
    }

    @Override
    public Conveyance along(double speed, RefusalKind lost) {
        return new OnHorse(speed, lost);
    }
}

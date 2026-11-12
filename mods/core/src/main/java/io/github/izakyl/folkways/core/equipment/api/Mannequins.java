package io.github.izakyl.folkways.core.equipment.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

/**
 * Client only: the figure the equipment page dresses in a profile. A resident module offers one of its residents;
 * without one the page shows an armour stand.
 */
public final class Mannequins {
    private static volatile Function<Level, LivingEntity> figure;

    private Mannequins() {
    }

    public static void register(Function<Level, LivingEntity> made) {
        figure = Objects.requireNonNull(made);
    }

    public static Optional<LivingEntity> make(Level level) {
        Function<Level, LivingEntity> made = figure;
        if (made != null) {
            LivingEntity body = made.apply(level);
            if (body != null) {
                return Optional.of(body);
            }
        }
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        if (stand != null) {
            stand.setShowArms(true);
            stand.setNoBasePlate(true);
        }
        return Optional.ofNullable(stand);
    }
}

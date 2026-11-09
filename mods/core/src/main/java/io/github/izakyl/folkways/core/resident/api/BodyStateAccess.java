package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.effect.perk.Perks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

public interface BodyStateAccess {

    Pack pack();

    Perks perks(ResidentKind kind);

    void credit(ResourceLocation vocation, int amount, RandomSource random);

    void worked(ResourceLocation vocation, int ticks, RandomSource random);

    Optional<UUID> colony();

    void bindTo(UUID colony);

    void leaveColony(Body body);

    Optional<Doing> doing();

    void nowDoing(Doing doing);

    Optional<Balk> balk();

    void balked(Balk balk);
}

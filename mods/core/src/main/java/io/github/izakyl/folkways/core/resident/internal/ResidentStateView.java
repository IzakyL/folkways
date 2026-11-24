package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.BodyStateAccess;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.effect.perk.Perks;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

public final class ResidentStateView implements BodyStateAccess {
    private final Supplier<BodyState> existing;
    private final Supplier<BodyState> assign;
    private final BodyState empty = new BodyState();
    private final Pack pack;

    public ResidentStateView(Supplier<BodyState> existing, Supplier<BodyState> assign) {
        this.existing = existing;
        this.assign = assign;
        this.pack = new ResidentPack(() -> {
            BodyState state = existing.get();
            return state == null ? null : state.pack();
        }, () -> assign.get().pack());
    }

    private BodyState reading() {
        BodyState state = existing.get();
        return state == null ? empty : state;
    }

    public Pack pack() {
        return pack;
    }

    public Perks perks(ResidentKind kind) {
        return reading().perks(kind);
    }

    public void credit(ResourceLocation vocation, int amount, RandomSource random) {
        assign.get().credit(vocation, amount, random);
    }

    public void worked(ResourceLocation vocation, int ticks, RandomSource random) {
        assign.get().worked(vocation, ticks, random);
    }

    public Optional<UUID> colony() {
        return reading().colony();
    }

    public void bindTo(UUID colony) {
        assign.get().bindTo(colony);
    }

    public void leaveColony(Body body) {
        assign.get().leaveColony(body);
    }

    public Optional<Doing> doing() {
        return reading().doing();
    }

    public void nowDoing(Doing doing) {
        assign.get().nowDoing(doing);
    }

    public Optional<Balk> balk() {
        return reading().balk();
    }

    public void balked(Balk balk) {
        assign.get().balked(balk);
    }
}

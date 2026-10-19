package io.github.izakyl.folkways.core.engine.travel.graph;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

public record Bulk(int width, int height) {

    public static Bulk of(Entity body) {
        return new Bulk(Mth.floor(body.getBbWidth() + 1.0F), Mth.floor(body.getBbHeight() + 1.0F));
    }
}

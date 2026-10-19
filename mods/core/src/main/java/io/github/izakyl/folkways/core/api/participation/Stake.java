package io.github.izakyl.folkways.core.api.participation;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record Stake(ResourceLocation kind, ResourceLocation id) {

    public static final ResourceLocation URGE = kind("urge");

    public static final ResourceLocation VOCATION = kind("vocation");

    public static final ResourceLocation PASSAGE = kind("passage");

    public Stake {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public static Stake urge(ResourceLocation id) {
        return new Stake(URGE, id);
    }

    public static Stake vocation(ResourceLocation id) {
        return new Stake(VOCATION, id);
    }

    public static Stake passage(ResourceLocation id) {
        return new Stake(PASSAGE, id);
    }

    @Override
    public String toString() {
        return id + " as " + saying();
    }

    private String saying() {
        if (URGE.equals(kind)) {
            return "an urge source";
        }
        if (VOCATION.equals(kind)) {
            return "a trade";
        }
        if (PASSAGE.equals(kind)) {
            return "a way across";
        }
        return "a " + kind.getPath().replace('_', ' ');
    }

    private static ResourceLocation kind(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }
}

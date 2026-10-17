package io.github.izakyl.folkways.core.api.work;

import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public record Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take) {

    public static final double IDLE = 0.05;

    public static final double SPARE = 0.5;

    public static final double NOW = 1.0;

    public static final double DIRE = 2.0;
}

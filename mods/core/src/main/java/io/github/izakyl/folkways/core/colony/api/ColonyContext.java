package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public interface ColonyContext {
    ResourceLocation owner();
    Colony colony();
    MinecraftServer server();
    void urges(BiFunction<Worker, ColonyView, List<Urge>> source);
    void tick(Consumer<MinecraftServer> callback);
    void closed(Consumer<Closing> callback);

    // Hears what the colony takes up and lets go under `held`, starting with what it already holds.
    void watch(ResourceLocation held, Claim.Watch watch);

    void share(Object state);
}

package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
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
    void watch(ResourceLocation held, Holding.Watch watch);

    void share(Object state);
}

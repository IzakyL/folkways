package io.github.izakyl.folkways.assistant.sandbox;

import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.create.control.Terminal;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.assistant.AssistantMod;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The assistant's sandbox: a void dimension of its own where a copy of a mechanism is worked and watched, so the real
 * one never moves. It is opened on the first probe after the server starts and never saved: it is not a datapack
 * dimension, so no world remembers it and a world without the assistant loses nothing. Blocks are copied at the
 * coordinates they hold in the world, which keeps every position stored in block entities true. One probe runs at a
 * time; its chunks are held by a ticket that is never saved and are emptied before the copy and after the probe.
 * Nothing else in the sandbox may appear: entities that join it outside a probe's own copies and its contraptions are
 * refused, and explosions there never happen. Moving structures a probe copies (Sable sub-levels) are deleted with it,
 * whether it finishes, fails or the server stops, and the sandbox is never saved, not even by a forced save.
 */
@EventBusSubscriber(modid = AssistantMod.ID)
public final class Sandbox {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceKey<Level> LEVEL = ResourceKey.create(Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath(AssistantMod.ID, "sandbox"));

    private static final ResourceKey<DimensionType> TYPE = ResourceKey.create(Registries.DIMENSION_TYPE,
        LEVEL.location());

    private static final ChunkProgressListener QUIET = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {
        }

        @Override
        public void onStatusChange(ChunkPos chunk, @Nullable ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    };

    static final TicketType<ChunkPos> TICKET = TicketType.create(AssistantMod.ID + ":sandbox",
        Comparator.comparingLong(ChunkPos::toLong));

    private static Probe active;

    private Sandbox() {
    }

    public static Optional<ServerLevel> level(MinecraftServer server) {
        return Optional.ofNullable(server.getLevel(LEVEL));
    }

    public static boolean is(Level level) {
        return level.dimension().equals(LEVEL);
    }

    static Signal.Write write(ServerLevel sandbox, BlockPos target, Signal signal, Value value) {
        if (sandbox.getBlockEntity(target) instanceof Terminal terminal && value instanceof Value.Real level
            && signal.type().check(value).isEmpty()) {
            return terminal.probeOutput(sandbox, (int) level.value()) ? Signal.Write.WRITTEN : Signal.Write.REFUSED;
        }
        return signal.write(sandbox, null, value);
    }

    /** Starts a probe and answers once it has run and the sandbox is empty again. Server thread only. */
    public static CompletableFuture<JsonObject> probe(ServerPlayer player, Probe.Request request) {
        return probe(player, request, pos -> true);
    }

    public static CompletableFuture<JsonObject> probe(ServerPlayer player, Probe.Request request,
            Predicate<BlockPos> permitted) {
        MinecraftServer server = player.getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("Probes start on the server thread");
        }
        ServerLevel sandbox = open(server);
        if (sandbox == null) {
            return CompletableFuture.completedFuture(Probe.error("sandbox_unavailable"));
        }
        if (active != null) {
            return CompletableFuture.completedFuture(Probe.error("sandbox_busy"));
        }
        if (!sandbox.players().isEmpty()) {
            return CompletableFuture.completedFuture(Probe.error("sandbox_occupied"));
        }
        Probe probe;
        try {
            probe = Probe.start(sandbox, request, permitted);
        } catch (IllegalArgumentException refused) {
            return CompletableFuture.completedFuture(Probe.error(refused.getMessage()));
        }
        active = probe;
        probe.result().whenComplete((result, failure) -> {
            if (active == probe) {
                active = null;
            }
        });
        // A caller that gives up (a script past its time) cancels only its copy: the probe still runs out and cleans up.
        return probe.result().copy();
    }

    /** The sandbox, opened now if this server has not opened it yet; null if its dimension type is missing. */
    @Nullable
    private static ServerLevel open(MinecraftServer server) {
        ServerLevel existing = server.getLevel(LEVEL);
        if (existing != null) {
            return existing;
        }
        RegistryAccess registries = server.registryAccess();
        Optional<Holder.Reference<DimensionType>> type = registries.registryOrThrow(Registries.DIMENSION_TYPE)
            .getHolder(TYPE);
        if (type.isEmpty()) {
            return null;
        }
        Holder<Biome> nothing = registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
        FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.empty(), nothing, List.of());
        flat.updateLayers();
        LevelStorageSource.LevelStorageAccess storage = ObfuscationReflectionHelper.getPrivateValue(
            MinecraftServer.class, server, "storageSource");
        WorldData data = server.getWorldData();
        ServerLevel level = new ServerLevel(server, Util.backgroundExecutor(), storage,
            new DerivedLevelData(data, data.overworldData()), LEVEL, new LevelStem(type.get(), new FlatLevelSource(flat)),
            QUIET, false, BiomeManager.obfuscateSeed(data.worldGenOptions().seed()), List.of(), false,
            server.overworld().getRandomSequences());
        level.noSave = true;
        server.forgeGetWorldMap().put(LEVEL, level);
        server.markWorldsDirty();
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        return level;
    }

    public static boolean busy() {
        return active != null;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        Probe probe = active;
        if (probe == null) {
            return;
        }
        try {
            probe.tick();
        } catch (RuntimeException failure) {
            LOGGER.warn("Sandbox probe failed", failure);
            probe.fail("probe_failed");
        }
    }

    // Lowest priority, so a copy refused here has already been seen by everyone else's listeners.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joining(EntityJoinLevelEvent event) {
        if (!is(event.getLevel()) || event.getEntity() instanceof ServerPlayer) {
            return;
        }
        Probe probe = active;
        if (probe == null || !probe.admits(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void exploding(ExplosionEvent.Start event) {
        if (is(event.getLevel())) {
            event.setCanceled(true);
            Probe probe = active;
            if (probe != null) {
                probe.suppressed("explosion");
            }
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        Probe probe = active;
        if (probe != null) {
            probe.fail("server_stopping");
        }
        active = null;
    }
}

package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentSuppliers;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.PlayDiscPayload;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.ServerStorageSoundHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StopDiscPlaybackPayload;

/** Runtime playlists read from the discs actually installed in worn backpacks. */
final class ResidentJukebox {
    private static final Map<UUID, Playback> PLAYING = new HashMap<>();

    private static final class Playback {
        final ServerLevel level;
        final UUID sound = UUID.randomUUID();
        final EquipmentProfile profile;
        final List<KitProvider.Held> held;
        final List<Holder<JukeboxSong>> songs;
        final Set<UUID> audience = new HashSet<>();
        int track;
        long until;
        Vec3 position;

        Playback(ServerLevel level, EquipmentProfile profile, List<KitProvider.Held> held,
                 List<Holder<JukeboxSong>> songs) {
            this.level = level;
            this.profile = profile;
            this.held = held;
            this.songs = List.copyOf(songs);
        }
    }

    private ResidentJukebox() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(ResidentJukebox::tick);
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide()) {
                stop(event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(LivingDeathEvent.class, event -> {
            if (!event.getEntity().level().isClientSide()) {
                stop(event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.StartTracking.class, event -> {
            Playback playing = PLAYING.get(event.getTarget().getUUID());
            if (playing != null) {
                // Send on the following tick, after the entity spawn packet.
                playing.audience.remove(event.getEntity().getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> PLAYING.clear());
        EquipmentSuppliers.listen((colony, resident) -> stop(resident));
    }

    static boolean playing(UUID resident) {
        return PLAYING.containsKey(resident);
    }

    static int track(UUID resident) {
        Playback playing = PLAYING.get(resident);
        return playing == null ? -1 : playing.track;
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<UUID> loaded = new HashSet<>();
        for (var entity : level.getAllEntities()) {
            Bodies.of(entity).ifPresent(body -> {
                loaded.add(body.id());
                update(body);
            });
        }
        for (var id : List.copyOf(PLAYING.keySet())) {
            if (PLAYING.get(id).level == level && !loaded.contains(id)) {
                stop(id);
            }
        }
    }

    static void update(Body body) {
        if (!(body.mob().level() instanceof ServerLevel level)) {
            return;
        }
        var profile = EquipmentKits.profile(body);
        var held = KitProviders.held(body);
        List<Holder<JukeboxSong>> songs = songs(level, body);
        if (!body.mob().isAlive() || body.mob().isRemoved() || profile.isEmpty() || songs.isEmpty()) {
            stop(body.id());
            return;
        }
        Playback playing = PLAYING.get(body.id());
        if (playing != null && (playing.level != level || !playing.profile.equals(profile.get())
            || !playing.held.equals(held) || !playing.songs.equals(songs))) {
            stop(body.id());
            playing = null;
        }
        if (playing == null) {
            playing = new Playback(level, profile.get(), held, songs);
            PLAYING.put(body.id(), playing);
            start(body, playing);
        } else if (level.getGameTime() >= playing.until) {
            // Native defaults: shuffle=false, slot order. Restart the list to keep residents playing forever.
            playing.track = (playing.track + 1) % playing.songs.size();
            start(body, playing);
        }
        playing.position = body.mob().position();
        ServerStorageSoundHandler.updateKeepAlive(playing.sound, level, playing.position, () -> { });
        sync(body, playing);
    }

    private static List<Holder<JukeboxSong>> songs(ServerLevel level, Body body) {
        for (var stack : KitProviders.activeRoots(body)) {
            if (!BackpackKits.backpack(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                continue;
            }
            if (!ResidentBackpacks.ticking(body, stack)) {
                continue;
            }
            var wrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper.fromStack(stack);
            for (var upgrade : wrapper.getUpgradeHandler().getSlotWrappers().values()) {
                if (!(upgrade instanceof net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper jukebox)
                    || !jukebox.isEnabled()) {
                    continue;
                }
                List<Holder<JukeboxSong>> songs = new ArrayList<>();
                var discs = jukebox.getDiscInventory();
                for (int slot = 0; slot < discs.getSlots(); slot++) {
                    var playable = discs.getStackInSlot(slot).get(DataComponents.JUKEBOX_PLAYABLE);
                    if (playable != null) {
                        playable.song().unwrap(level.registryAccess()).ifPresent(songs::add);
                    }
                }
                if (!songs.isEmpty()) {
                    return songs;
                }
            }
        }
        return List.of();
    }

    private static void start(Body body, Playback playing) {
        var song = playing.songs.get(playing.track);
        playing.position = body.mob().position();
        playing.until = playing.level.getGameTime() + song.value().lengthInTicks();
        ServerStorageSoundHandler.startPlayingDisc(playing.level, playing.position, playing.sound,
            body.mob().getId(), song, () -> { });
        playing.audience.clear();
        nearby(body).forEach(player -> playing.audience.add(player.getUUID()));
    }

    private static List<ServerPlayer> nearby(Body body) {
        return ((ServerLevel) body.mob().level()).players().stream()
            .filter(player -> player.distanceToSqr(body.mob()) <= 128 * 128).toList();
    }

    private static void sync(Body body, Playback playing) {
        Set<UUID> near = new HashSet<>();
        for (var player : nearby(body)) {
            near.add(player.getUUID());
            if (playing.audience.add(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, new PlayDiscPayload(playing.sound,
                    playing.songs.get(playing.track), body.mob().getId()));
            }
        }
        for (var id : Set.copyOf(playing.audience)) {
            if (!near.contains(id)) {
                var player = playing.level.getServer().getPlayerList().getPlayer(id);
                if (player != null) {
                    PacketDistributor.sendToPlayer(player, new StopDiscPlaybackPayload(playing.sound));
                }
                playing.audience.remove(id);
            }
        }
    }

    private static void stop(UUID resident) {
        Playback playing = PLAYING.remove(resident);
        if (playing == null) {
            return;
        }
        ServerStorageSoundHandler.stopPlayingDisc(playing.level, playing.position, playing.sound);
        for (var id : playing.audience) {
            var player = playing.level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, new StopDiscPlaybackPayload(playing.sound));
            }
        }
    }
}

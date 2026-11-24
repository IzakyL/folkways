package io.github.izakyl.folkways.core.shell.create.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class HoseSessions {
    private static final Map<UUID, NativeHoseSession> ACTIVE = new LinkedHashMap<>();

    private HoseSessions() {
    }

    static boolean add(NativeHoseSession session) {
        return ACTIVE.putIfAbsent(session.actor().getUUID(), session) == null;
    }

    static void remove(NativeHoseSession session) {
        ACTIVE.remove(session.actor().getUUID(), session);
    }

    public static boolean owns(UUID actor) {
        return ACTIVE.containsKey(actor);
    }

    public static int size() {
        return ACTIVE.size();
    }

    public static void disconnect(FireHydrantCabinetBlockEntity cabinet) {
        if (!(cabinet.getLevel() instanceof ServerLevel)) {
            return;
        }
        for (NativeHoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.cabinet() == cabinet) {
                session.close();
            }
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        for (NativeHoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (!session.valid()) {
                session.close();
            } else if (session.actor().level().getGameTime() % 5 == 0) {
                ResidentSprayPacket.connection(session.actor(), session.cabinet());
            }
        }
    }

    public static void died(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            closeActor(event.getEntity().getUUID());
        }
    }

    public static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            closeActor(event.getEntity().getUUID());
        }
    }

    private static void closeActor(UUID actor) {
        NativeHoseSession session = ACTIVE.get(actor);
        if (session != null) {
            session.close();
        }
    }

    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Hydrants.unload((ServerLevel) event.getLevel(), event.getChunk().getPos());
        for (NativeHoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.actor().level() == event.getLevel()
                && (new ChunkPos(session.cabinet().getBlockPos()).equals(event.getChunk().getPos())
                    || session.actor().chunkPosition().equals(event.getChunk().getPos()))) {
                session.close(false);
            }
        }
    }

    public static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Hydrants.unload((ServerLevel) event.getLevel());
        for (NativeHoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.actor().level() == event.getLevel()) {
                session.close(false);
            }
        }
    }

    public static void closeColony(UUID colony) {
        for (NativeHoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.colony().equals(colony)) {
                session.close();
            }
        }
    }
}

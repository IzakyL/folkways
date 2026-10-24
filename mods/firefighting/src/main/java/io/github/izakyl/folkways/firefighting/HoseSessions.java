package io.github.izakyl.folkways.firefighting;

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
    private static final Map<UUID, HoseSession> ACTIVE = new LinkedHashMap<>();

    private HoseSessions() {
    }

    static boolean add(HoseSession session) {
        return ACTIVE.putIfAbsent(session.actor().getUUID(), session) == null;
    }

    static void remove(HoseSession session) {
        ACTIVE.remove(session.actor().getUUID(), session);
    }

    static int size() { return ACTIVE.size(); }

    public static void disconnect(FireHydrantCabinetBlockEntity cabinet) {
        if (!(cabinet.getLevel() instanceof ServerLevel)) {
            return;
        }
        for (HoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.cabinet() == cabinet) {
                session.close();
            }
        }
    }

    static void tick(ServerTickEvent.Post event) {
        for (HoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (!session.valid()) {
                session.close();
            }
        }
    }

    static void died(LivingDeathEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            closeActor(event.getEntity().getUUID());
        }
    }
    static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            closeActor(event.getEntity().getUUID());
        }
    }

    private static void closeActor(UUID actor) {
        HoseSession session = ACTIVE.get(actor);
        if (session != null) {
            session.close();
        }
    }

    static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (HoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.actor().level() == event.getLevel()
                    && (new ChunkPos(session.cabinet().getBlockPos()).equals(event.getChunk().getPos())
                        || session.actor().chunkPosition().equals(event.getChunk().getPos()))) {
                session.close(false);
            }
        }
    }

    static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        for (HoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.actor().level() == event.getLevel()) {
                session.close(false);
            }
        }
    }

    static void closeColony(UUID colony) {
        for (HoseSession session : new ArrayList<>(ACTIVE.values())) {
            if (session.colony().equals(colony)) {
                session.close();
            }
        }
    }
}

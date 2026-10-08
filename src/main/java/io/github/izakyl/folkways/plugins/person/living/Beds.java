package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

final class Beds {

    private final Map<UUID, WorldPos> bedsByResident = new LinkedHashMap<>();
    private final Map<WorldPos, UUID> residentsByBed = new LinkedHashMap<>();

    boolean claim(UUID resident, WorldPos bed) {
        UUID heldBy = residentsByBed.get(bed);
        WorldPos held = bedsByResident.get(resident);
        if (heldBy != null && !heldBy.equals(resident)) {
            return false;
        }
        if (held != null && !held.equals(bed)) {
            return false;
        }
        bedsByResident.put(resident, bed);
        residentsByBed.put(bed, resident);
        return true;
    }

    Optional<WorldPos> bedFor(UUID resident) {
        return Optional.ofNullable(bedsByResident.get(resident));
    }

    boolean sweep(ColonyView colony, Set<UUID> belonging) {
        ServerLevel level = colony.level();
        Set<BlockPos> members = colony.blocks(LivingContent.BED);
        boolean moved = false;
        for (WorldPos cell : List.copyOf(residentsByBed.keySet())) {
            if (!belonging.contains(residentsByBed.get(cell))) {
                moved |= forget(cell);
                continue;
            }
            if (!cell.in(level) || !level.isLoaded(cell.block(level))) {
                continue;
            }
            boolean stands = members.contains(cell.block(level))
                && Housing.isBed(level.getBlockState(cell.block(level)));
            if (!stands) {
                moved |= forget(cell);
            }
        }
        return house(colony, sleepers(colony)) || moved;
    }

    boolean house(ColonyView colony) {
        return house(colony, sleepers(colony));
    }

    private static Set<UUID> sleepers(ColonyView colony) {
        Set<UUID> sleeping = new LinkedHashSet<>();
        for (Resident resident : colony.residents()) {
            if (LivingContent.looksAfter(resident)) {
                sleeping.add(resident.id());
            }
        }
        return sleeping;
    }

    private boolean house(ColonyView colony, Set<UUID> residents) {
        List<WorldPos> free = null;
        boolean moved = false;
        for (UUID resident : residents) {
            if (bedsByResident.containsKey(resident)) {
                continue;
            }
            if (free == null) {
                free = new ArrayList<>(freeMemberBeds(colony));
            }
            if (free.isEmpty()) {
                break;
            }
            moved |= claim(resident, free.remove(0));
        }
        return moved;
    }

    private boolean forget(WorldPos cell) {
        UUID resident = residentsByBed.remove(cell);
        if (resident == null) {
            return false;
        }
        bedsByResident.remove(resident);
        return true;
    }

    List<WorldPos> freeMemberBeds(ColonyView colony) {
        ServerLevel level = colony.level();
        List<WorldPos> free = new ArrayList<>();
        for (BlockPos pos : colony.blocks(LivingContent.BED)) {
            WorldPos cell = WorldPos.of(level, pos);
            if (residentsByBed.containsKey(cell) || !level.isLoaded(pos)
                    || !Housing.isBed(level.getBlockState(pos))) {
                continue;
            }
            free.add(cell);
        }
        return List.copyOf(free);
    }

    void clear() {
        bedsByResident.clear();
        residentsByBed.clear();
    }

    CompoundTag save() {
        return Writer.of().children("claims", bedsByResident.entrySet(), Beds::saveClaim).tag();
    }

    private static CompoundTag saveClaim(Map.Entry<UUID, WorldPos> claim) {
        return Writer.of()
            .uuid("resident", claim.getKey())
            .blob("bed", claim.getValue().save())
            .tag();
    }

    void load(Reader reader) {
        for (Claim claim : Entries.of(reader, "claims", Beds::claimOf)) {
            claim(claim.resident(), claim.bed());
        }
    }

    private record Claim(UUID resident, WorldPos bed) {
    }

    private static Optional<Claim> claimOf(Reader reader) {
        return reader.uuid("resident")
            .flatMap(resident -> reader.child("bed").flatMap(WorldPos::load)
                .map(bed -> new Claim(resident, bed)));
    }
}

package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Footing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

// Visitors remain ordinary, untamed vanilla animals. Their saved UUIDs also count while unloaded.
final class ColonyPets {
    private static final String COLONY = "folkways_colony_pet";
    private static final long INTERVAL = 12_000;
    private final Colony colony;
    private final HousingRooms rooms;
    private final Set<UUID> pets = new HashSet<>();
    private long nextAttempt = -1;

    private record Neighborhood(ServerLevel level, List<BlockPos> beds, int housed) { }

    ColonyPets(Colony colony, HousingRooms rooms) {
        this.colony = colony;
        this.rooms = rooms;
    }

    boolean tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (nextAttempt < 0 || nextAttempt > now + INTERVAL) {
            nextAttempt = now + INTERVAL;
            return true;
        }
        if (now < nextAttempt) { return false; }
        // Failed attempts also wait a full half-day, including across saves and restarts.
        nextAttempt = now + INTERVAL;
        List<Neighborhood> neighborhoods = new ArrayList<>();
        int housed = 0;
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            int residents = (int) view.residents().stream().filter(LivingContent::looksAfter)
                .filter(resident -> level.getEntity(resident.id()) instanceof LivingEntity living && living.isAlive())
                .count();
            if (residents == 0) { continue; }
            List<BlockPos> beds = view.blocks(LivingContent.BED).stream()
                .filter(level::isLoaded)
                .filter(at -> Housing.isBed(level.getBlockState(at)))
                .filter(at -> rooms.get(level, at).grade().ordinal() >= HousingRooms.Grade.DORMITORY.ordinal())
                .toList();
            int local = Math.min(residents, beds.size());
            housed += local;
            if (local >= 3 && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                neighborhoods.add(new Neighborhood(level, beds, local));
            }
        }
        if (pets.size() >= housed / 3 || neighborhoods.isEmpty()
                || !server.overworld().random.nextBoolean()) { return true; }
        Neighborhood neighborhood = neighborhoods.get(server.overworld().random.nextInt(neighborhoods.size()));
        spawn(neighborhood);
        return true;
    }

    private void spawn(Neighborhood neighborhood) {
        ServerLevel level = neighborhood.level();
        List<EntityType<? extends Mob>> types = new ArrayList<>();
        types.add(EntityType.CAT);
        if (neighborhood.housed() >= 6) { types.add(EntityType.WOLF); }
        if (neighborhood.housed() >= 10) { types.add(EntityType.PARROT); }
        Mob pet = types.get(level.random.nextInt(types.size())).create(level);
        if (pet == null) { return; }
        for (int attempt = 0; attempt < 16; attempt++) {
            BlockPos bed = neighborhood.beds().get(level.random.nextInt(neighborhood.beds().size()));
            double angle = level.random.nextDouble() * Math.PI * 2;
            double radius = 8 + level.random.nextDouble() * 8;
            int x = bed.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = bed.getZ() + (int) Math.round(Math.sin(angle) * radius);
            // Search only a small vertical band; never load terrain to find a visitor's position.
            for (int y = bed.getY() + 8; y >= bed.getY() - 8; y--) {
                BlockPos at = new BlockPos(x, y, z);
                if (!safe(level, at, pet, attempt < 12)) { continue; }
                pet.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360, 0);
                pet.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
                pet.setPersistenceRequired();
                pet.getPersistentData().putUUID(COLONY, colony.id());
                if (level.addFreshEntity(pet)) { pets.add(pet.getUUID()); }
                return;
            }
        }
    }

    private boolean safe(ServerLevel level, BlockPos at, Mob pet, boolean outdoors) {
        if (level.isOutsideBuildHeight(at) || level.isOutsideBuildHeight(at.above())
                || !level.hasChunksAt(at.offset(-1, -1, -1), at.offset(1, 2, 1))
                || !level.getWorldBorder().isWithinBounds(at)
                || !Footing.withFeetAt(level, at) || !Footing.holdsAbove(level, at.below())
                || !level.getFluidState(at).isEmpty() || !level.getFluidState(at.above()).isEmpty()
                || !level.getFluidState(at.below()).isEmpty()
                || level.getMaxLocalRawBrightness(at) < 8 || outdoors && !level.canSeeSky(at)) { return false; }
        for (BlockPos near : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
            var state = level.getBlockState(near);
            if (state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                    || state.is(Blocks.POWDER_SNOW)) { return false; }
        }
        pet.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (!level.noCollision(pet) || level.containsAnyLiquid(pet.getBoundingBox())) { return false; }
        AABB nearby = pet.getBoundingBox().inflate(12);
        if (!level.getEntitiesOfClass(Mob.class, nearby, mob -> mob instanceof Enemy && mob.isAlive()).isEmpty()
                || level.hasNearbyAlivePlayer(pet.getX(), pet.getY(), pet.getZ(), 8)) { return false; }
        return colony.view(level).residents().stream().filter(LivingContent::looksAfter)
            .map(resident -> level.getEntity(resident.id()))
            .noneMatch(resident -> resident != null && resident.distanceToSqr(pet) < 16);
    }

    static boolean belongsTo(Entity entity, Colony colony) {
        CompoundTag data = entity.getPersistentData();
        return data.hasUUID(COLONY) && data.getUUID(COLONY).equals(colony.id());
    }

    CompoundTag save() {
        return Writer.of().longValue("next_attempt", nextAttempt).uuids("pets", pets).tag();
    }

    void load(Reader reader) {
        pets.clear();
        pets.addAll(reader.uuids("pets"));
        nextAttempt = reader.longValue("next_attempt").orElse(-1L);
    }

    boolean removed(UUID id) { return pets.remove(id); }

    void clear() {
        pets.clear();
        nextAttempt = -1;
    }
}

package io.github.izakyl.folkways.raid;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.MobTravel;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

final class RaidObjectives {

    private RaidObjectives() {
    }

    static Optional<WorldPos> centre(Colony colony, MinecraftServer server) {
        // Routes and isolated outposts do not enlarge the settlement's attack area.
        List<WorldPos> sites =
            colony.holdings().stream()
            .filter(holding -> !(holding.what() instanceof Held.Line))
            .flatMap(holding -> holding.what().cells().stream())
            .limit(4096)
            .toList();
        Optional<WorldPos> site =
            sites.stream()
            .filter(at -> at.realm() instanceof Realm.Dimension)
            .filter(at -> at.level(server).filter(level -> level.isPositionEntityTicking(at.cell())).isPresent())
            .findFirst();
        if (site.isPresent()) {
            return site;
        }
        for (var level : server.getAllLevels()) {
            for (var resident : colony.residents()) {
                var mob = level.getEntity(resident.id());
                if (mob != null && mob.isAlive() && Bodies.of(mob).isPresent()) {
                    WorldPos at = WorldPos.of(level, mob.blockPosition());
                    if (at.realm() instanceof Realm.Dimension) {
                        return Optional.of(at);
                    }
                }
            }
        }
        return Optional.empty();
    }

    static Optional<WorldPos> spawn(Colony colony, MinecraftServer server, WorldPos target, long seed) {
        ServerLevel level = target.level(server).orElse(null);
        if (level == null || !(target.realm() instanceof Realm.Dimension)) {
            return Optional.empty();
        }
        BlockPos centre = target.block(level);
        int radius =
            colony.holdings().stream()
            .filter(holding -> !(holding.what() instanceof Held.Line))
            .flatMap(holding -> holding.what().cells().stream())
            .filter(target::sameRealm)
            .filter(at -> at.cell().distSqr(centre) <= 48 * 48)
            .mapToInt(at -> (int) Math.ceil(Math.sqrt(at.cell().distSqr(centre))))
            .max()
            .orElse(8);
        RandomSource random = RandomSource.create(seed);
        double direction = random.nextDouble() * Math.PI * 2;
        Mob probe = EntityType.ZOMBIE.create(level);
        if (probe == null) {
            return Optional.empty();
        }
        try {
            for (int attempt = 0; attempt < 48; attempt++) {
                double angle = direction + (random.nextDouble() - .5) * .8;
                int distance = radius + 24 + random.nextInt(41);
                int x = centre.getX() + (int) (Math.cos(angle) * distance);
                int z = centre.getZ() + (int) (Math.sin(angle) * distance);
                if (!level.isPositionEntityTicking(new BlockPos(x, centre.getY(), z))) {
                    continue;
                }
                BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (!safe(level, probe, at)
                    || level.players().stream()
                    .anyMatch(player -> player.distanceToSqr(Vec3.atCenterOf(at)) < 16 * 16)) {
                    continue;
                }
                boolean foreign =
                    Colonies.all(server).stream()
                    .filter(other -> !other.id().equals(colony.id()))
                    .map(other -> centre(other, server))
                    .flatMap(Optional::stream)
                    .anyMatch(other -> other.sameRealm(target) && other.cell().distSqr(at) < 32 * 32);
                if (foreign) {
                    continue;
                }
                probe.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                // Reach the outer approach; a closed wall need not already have an open entrance.
                BlockPos approach =
                    new BlockPos(
                    centre.getX() + (int) (Math.cos(angle) * (radius + 3)),
                    at.getY(),
                    centre.getZ() + (int) (Math.sin(angle) * (radius + 3)));
                if (MobTravel.reaches(probe, approach)) {
                    return Optional.of(WorldPos.of(level, at));
                }
            }
        } finally {
            probe.discard();
        }
        return Optional.empty();
    }

    static boolean safe(ServerLevel level, Mob mob, BlockPos at) {
        if (!level.isPositionEntityTicking(at)
            || !level.getWorldBorder().isWithinBounds(at)
            || !level.getFluidState(at).isEmpty()
            || !level.getFluidState(at.below()).isEmpty()) {
            return false;
        }
        var floor = level.getBlockState(at.below());
        if (!floor.isFaceSturdy(level, at.below(), Direction.UP)
            || floor.is(Blocks.MAGMA_BLOCK)
            || floor.is(Blocks.CAMPFIRE)
            || floor.is(Blocks.CACTUS)) {
            return false;
        }
        mob.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
        return level.noCollision(mob) && level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir();
    }
}

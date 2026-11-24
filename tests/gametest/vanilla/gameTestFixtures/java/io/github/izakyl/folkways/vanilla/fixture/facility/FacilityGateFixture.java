package io.github.izakyl.folkways.vanilla.fixture.facility;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

public final class FacilityGateFixture implements AutoCloseable {

    public final ServerLevel level;
    public final Colony colony;
    public final WorldPos door;
    public final ControlRef lever;
    public final Body body;
    public final Set<WorldPos> goals;
    private final Roaming roaming;

    public FacilityGateFixture(GameTestHelper helper) {
        level = helper.getLevel();
        BlockPos door = helper.absolutePos(new BlockPos(3, 2, 3));
        for (BlockPos floor : BlockPos.betweenClosed(door.offset(-3, -1, -2), door.offset(3, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        for (BlockPos wall : BlockPos.betweenClosed(door.offset(0, 0, -2), door.offset(0, 2, 2))) {
            if (wall.getZ() != door.getZ()) {
                level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            }
        }
        var lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        level.setBlock(door, lower, 2);
        level.setBlockAndUpdate(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(door.west(), Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.FLOOR));
        this.door = WorldPos.of(level, door);
        lever = new ControlRef(this.door.at(door.west()), "lever");
        var person = PersonBody.RESIDENT.get().create(level);
        person.setPos(Vec3.atBottomCenterOf(door.west(3)));
        person.setOnGround(true);
        level.addFreshEntity(person);
        body = Bodies.of(person).orElseThrow();
        body.locomotion().fit(person);
        goals = Set.of(WorldPos.of(level, door.east(3)));
        roaming = new Roaming(level);
        colony = Colonies.mint(level.getServer());
    }

    public void install() {
        install(colony, door, lever.at().cell(), Direction.Axis.X);
    }

    public static void install(Colony colony, WorldPos door, BlockPos lever, Direction.Axis axis) {
        String name = "Test gate";
        BlockPos cell = door.cell();
        var bounds = BoundingBox.fromCorners(cell, lever);
        var extent = new FacilityExtent(door.realm(), new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
            new BlockPos(bounds.maxX(), bounds.maxY() + 1, bounds.maxZ()));
        var facilities = DefinedFacilities.of(colony).orElseThrow();
        var data = Map.of("name", name, "extent", DefinitionCodec.extent(extent), "anchor", pos(cell),
            "controls", Map.of("lever", Map.of("part", "lever", "offset", pos(lever.subtract(cell)))),
            "projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(cell), pos(cell.above())),
                "axes", List.of(axis.getName()), "open_when", Map.of("lever", true)))));
        var problems = facilities.apply(facilities.preview(name, Optional.of(data)));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Test gate installation failed: " + problems);
        }
    }

    private static List<Integer> pos(BlockPos cell) {
        return List.of(cell.getX(), cell.getY(), cell.getZ());
    }

    public void powerLever() {
        BlockPos cell = lever.at().cell();
        level.setBlockAndUpdate(cell, level.getBlockState(cell).setValue(LeverBlock.POWERED, true));
    }

    public boolean open() {
        return level.getBlockState(door.cell()).getValue(DoorBlock.OPEN);
    }

    public Faring route() {
        WorldPos start = WorldPos.of(level, body.mob().blockPosition());
        return roaming.answered(ways -> ways.journey(body.resident(), start, goals),
            List.of(new Roamer(body.routeKind(), body.locomotion(), body.mob())),
            Passages.published(level, colony.id()), level.getGameTime());
    }

    public Ways ways() {
        return roaming.ways();
    }

    @Override
    public void close() {
        body.mob().discard();
        colony.raze();
    }
}

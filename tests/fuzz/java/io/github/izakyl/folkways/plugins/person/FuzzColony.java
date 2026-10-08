package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.engine.colony.ColonySites;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * A colony founded with nobody holding a book: what binding a book, opting beds and chests in and admitting
 * settlers do, through the same calls, minus the player. Settlers arrive as {@link Immigration} brings them,
 * without its checks on whether the colony may take more; a fuzz case decides how many it has.
 */
public final class FuzzColony {

    private FuzzColony() {
    }

    public record Founded(Colony colony, List<BlockPos> beds, List<ResidentEntity> residents) {
    }

    /** Beds laid in a row eastward from {@code firstBed}, each joined to the colony, as are {@code chests}. */
    public static Founded found(ServerLevel level, BlockPos firstBed, List<BlockPos> chests, int residents, BlockPos arrival) {
        Colony colony = ColonyGround.found(level);
        List<BlockPos> beds = new ArrayList<>();
        for (int i = 0; i < residents; i++) {
            BlockPos foot = firstBed.offset(0, 0, 2 * i);
            level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD), 3);
            hold(level, colony, foot);
            beds.add(foot);
        }
        for (BlockPos chest : chests) {
            hold(level, colony, chest);
        }
        List<ResidentEntity> people = new ArrayList<>();
        for (int i = 0; i < residents; i++) {
            ResidentEntity person = PersonBody.RESIDENT.get().create(level);
            if (person == null) {
                throw new IllegalStateException("no resident could be made");
            }
            person.moveTo(arrival.getX() + 0.5D + i, arrival.getY(), arrival.getZ() + 0.5D, 0.0F, 0.0F);
            if (!level.addFreshEntity(person)) {
                throw new IllegalStateException("the level would not take a resident at " + arrival);
            }
            colony.hold(PersonContent.ID, Held.Entity.of(person));
            people.add(person);
        }
        return new Founded(colony, List.copyOf(beds), List.copyOf(people));
    }

    // Held under whichever delegation takes the block, as the book's toggle holds it.
    private static void hold(ServerLevel level, Colony colony, BlockPos pos) {
        Delegation taking = ColonySites.accepting(level, pos)
            .orElseThrow(() -> new IllegalStateException("nothing takes the block at " + pos));
        ColonyGround.hold(level, colony, taking.id(), pos);
    }

    /**
     * Sets one of the colony's item lists, as its settings page would; answers whether the colony took it (it
     * does not when the list already reads so, or the colony has no such setting).
     */
    public static boolean setItems(Colony colony, ResourceLocation scope, String key, List<ItemFilter> items) {
        return ColonyFront.of(colony).setSetting(scope, key, new ColonySettings.Value.Items(items));
    }

    /** Razes the colony as the book's raze does: its residents sent off and its members let go. */
    public static void raze(ServerLevel level, Colony colony) {
        ColonyGround.raze(level, colony);
    }
}

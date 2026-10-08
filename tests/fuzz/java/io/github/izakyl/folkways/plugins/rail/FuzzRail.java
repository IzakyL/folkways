package io.github.izakyl.folkways.plugins.rail;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.track.ITrackBlock;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import io.github.izakyl.folkways.plugins.rail.domain.RailPresence;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A Create line laid on the server the way a player lays one, minus the player, as tests/e2e/rail-fixture.ts lays
 * it through a client: track running north from the car, two stations bound to it as the station item binds them
 * (track and facing written into the station's block entity), a plank car on two bogeys with controls either way, a
 * conductor's seat and passenger seats, glued and assembled at the first station. Then the train is given a cyclic
 * timetable of timed stops and handed to a colony, as the conductor seat's hand-over does. The line is built over
 * a few ticks: {@link #advance} moves it on and says when it runs.
 *
 * <p>Laid out from {@code origin} (the track under the car's south end, less three): the track runs from
 * {@code length + 8} north of it to the car's far end and three past; the first station stands east of the track
 * just past the car, facing north along it, the second {@code length} north of {@code origin} facing south; each has a
 * platform east of it, a two-high edge and a step behind.
 */
public final class FuzzRail {

    public enum Stage { LAID, NAMED, ASSEMBLED, RUNNING, FAILED }

    private static final int SETTLE = 10;

    private final ServerLevel level;
    private final BlockPos origin;
    private final int length;
    private final int seats;
    private final String[] names;
    private final int[] dwell;
    private final UUID owner = UUID.randomUUID();
    private Stage stage = Stage.LAID;
    private long since;
    private UUID train;
    private String trouble = "";

    public FuzzRail(ServerLevel level, BlockPos origin, int length, int seats, String first, String second,
            int dwellFirst, int dwellSecond) {
        this.level = level;
        this.origin = origin;
        this.length = length;
        this.seats = Math.clamp(seats, 1, 4);
        this.names = new String[] {first, second};
        this.dwell = new int[] {dwellFirst, dwellSecond};
    }

    /** How far south of {@code origin} the car reaches. */
    public static int carEnd(int seats) {
        return seats > 1 ? 8 + (seats + 1) / 2 : 8;
    }

    public int carEnd() {
        return carEnd(seats);
    }

    public int x() {
        return origin.getX();
    }

    public int y() {
        return origin.getY();
    }

    public int north() {
        return origin.getZ() - length - 8;
    }

    public int south() {
        return origin.getZ() + carEnd() + 3;
    }

    /** The first station (where the train is made), then the second. */
    public BlockPos station(int which) {
        return which == 0 ? origin.offset(1, 0, carEnd() + 1) : origin.offset(1, 0, -length);
    }

    /** The cells of the platform beside {@code which}: north to south along the train's berth, inclusive. */
    public int[] berth(int which) {
        int z = station(which).getZ();
        return which == 0 ? new int[] {z - carEnd() - 2, z + 2} : new int[] {z - 2, z + carEnd() + 2};
    }

    /** Where a train certainly stands beside {@code which}, whichever way it came in. */
    public int[] alongside(int which) {
        int z = station(which).getZ();
        return which == 0 ? new int[] {z - 3, z - 1} : new int[] {z + 1, z + 3};
    }

    public BlockPos conductorSeat() {
        return origin.offset(0, 2, 7);
    }

    public Stage stage() {
        return stage;
    }

    public Optional<UUID> trainId() {
        return Optional.ofNullable(train);
    }

    public String trouble() {
        return trouble;
    }

    /** Lays track, stations, platforms and the car; {@code put} sets each block, as the case sets its ground. */
    public void lay(BiConsumer<BlockPos, BlockState> put, long now) {
        BlockState track = block("create:track[shape=zo,turn=false,waterlogged=false]");
        for (int z = north(); z <= south(); z++) {
            put.accept(new BlockPos(x(), y(), z), track);
        }
        BlockState bricks = block("minecraft:stone_bricks");
        for (int which = 0; which < 2; which++) {
            int[] span = berth(which);
            for (int z = span[0]; z <= span[1]; z++) {
                put.accept(new BlockPos(x() + 3, y(), z), bricks);
                put.accept(new BlockPos(x() + 3, y() + 1, z), bricks);
                put.accept(new BlockPos(x() + 4, y(), z), bricks);
            }
        }
        int end = carEnd();
        BlockState planks = block("minecraft:oak_planks");
        for (int dx = -2; dx <= 2; dx++) {
            for (int z = 3; z <= end; z++) {
                put.accept(origin.offset(dx, 1, z), planks);
            }
        }
        put.accept(origin.offset(0, 1, 3), block("create:small_bogey[axis=z]"));
        put.accept(origin.offset(0, 1, end), block("create:small_bogey[axis=z]"));
        put.accept(origin.offset(0, 2, 3), block("create:railway_casing"));
        put.accept(origin.offset(0, 2, 6), block("create:controls[facing=south]"));
        put.accept(conductorSeat(), block("create:red_seat"));
        put.accept(origin.offset(0, 2, 8), block("create:controls[facing=north]"));
        for (int i = 0; i < seats; i++) {
            put.accept(seats == 1 ? origin.offset(-1, 2, 4) : origin.offset(i % 2 == 0 ? -1 : 1, 2, 9 + i / 2),
                block("create:red_seat"));
        }
        for (int which = 0; which < 2; which++) {
            BlockPos at = station(which);
            put.accept(at, block("create:track_station"));
            bind(at, new BlockPos(x(), y(), at.getZ()), which == 0 ? -1 : 1);
        }
        level.addFreshEntity(new SuperGlueEntity(level,
            SuperGlueEntity.span(origin.offset(-2, 1, 3), origin.offset(2, 4, end))));
        since = now;
    }

    /** Points the station at {@code at} onto the track at {@code track}, assembling towards {@code step} along z. */
    private void bind(BlockPos at, BlockPos track, int step) {
        if (!(level.getBlockEntity(at) instanceof StationBlockEntity station)
                || !(level.getBlockState(track).getBlock() instanceof ITrackBlock rails)) {
            fail("no station or no track at " + at.toShortString());
            return;
        }
        Vec3 axis = rails.getTrackAxes(level, track, level.getBlockState(track)).getFirst();
        CompoundTag tag = station.saveWithFullMetadata(level.registryAccess());
        tag.put("TargetTrack", NbtUtils.writeBlockPos(track.subtract(at)));
        tag.putBoolean("TargetDirection", axis.z * step > 0.0D);
        station.loadWithComponents(tag, level.registryAccess());
        station.setChanged();
    }

    /** Moves the line on once its last stage has taken: names it, assembles it, then timetables and hands it over. */
    public Stage advance(long now, Colony colony) {
        if (now - since < SETTLE || stage == Stage.RUNNING || stage == Stage.FAILED) {
            return stage;
        }
        switch (stage) {
            case LAID -> {
                StationBlockEntity first = station(level, station(0));
                StationBlockEntity second = station(level, station(1));
                if (first != null && second != null && first.getStation() != null && second.getStation() != null) {
                    first.updateName(names[0]);
                    second.updateName(names[1]);
                    next(Stage.NAMED, now);
                } else if (now - since > 200) {
                    fail("the stations never joined the track graph");
                }
            }
            case NAMED -> {
                StationBlockEntity first = station(level, station(0));
                if (first == null || first.getStation() == null) {
                    fail("the first station is gone");
                    return stage;
                }
                first.refreshAssemblyInfo();
                first.assemble(owner);
                Train made = first.getStation().getPresentTrain();
                if (made == null) {
                    fail("assembly failed: " + first.saveWithoutMetadata(level.registryAccess()).get("LastException"));
                    return stage;
                }
                train = made.id;
                next(Stage.ASSEMBLED, now);
            }
            case ASSEMBLED -> {
                Train made = train();
                Optional<RailPresence> presence = presence(colony);
                if (made == null || presence.isEmpty()) {
                    fail(made == null ? "the train vanished after assembly" : "the colony keeps no trains");
                    return stage;
                }
                made.runtime.setSchedule(timetable(names, dwell, true), false);
                presence.get().take(made.id);
                next(Stage.RUNNING, now);
            }
            default -> { }
        }
        return stage;
    }

    private void next(Stage to, long now) {
        stage = to;
        since = now;
    }

    private void fail(String why) {
        stage = Stage.FAILED;
        trouble = why;
    }

    /** A schedule calling at {@code stops} in turn, each held {@code dwell} seconds: what a colony can timetable. */
    public Schedule timetable(String[] stops, int[] holds, boolean cyclic) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < stops.length; i++) {
            entries.add("{Instruction:{Id:\"create:destination\",Data:{Text:\"" + stops[i] + "\"}},Conditions:[[{Id:"
                + "\"create:delay\",Data:{Value:" + holds[i % holds.length] + ",TimeUnit:1}}]]}");
        }
        try {
            return Schedule.fromTag(level.registryAccess(),
                TagParser.parseTag("{Entries:[" + String.join(",", entries) + "],Cyclic:" + (cyclic ? 1 : 0) + "b}"));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public String[] names() {
        return names.clone();
    }

    public int[] dwell() {
        return dwell.clone();
    }

    public Train train() {
        return train == null ? null : Create.RAILWAYS.trains.get(train);
    }

    public boolean conducted() {
        Train made = train();
        return made != null && (made.hasForwardConductor() || made.hasBackwardConductor());
    }

    /** Whether the train is there to ride: on its track, timetabled, and still the colony's. */
    public boolean serves(Colony colony) {
        Train made = train();
        return stage == Stage.RUNNING && made != null && !made.derailed && made.graph != null
            && made.runtime.getSchedule() != null
            && presence(colony).map(trains -> trains.holds(made.id)).orElse(false);
    }

    public List<CarriageContraptionEntity> carriages() {
        List<CarriageContraptionEntity> out = new ArrayList<>();
        Train made = train();
        if (made != null) {
            for (Carriage carriage : made.carriages) {
                carriage.forEachPresentEntity(out::add);
            }
        }
        return out;
    }

    /** Takes the train off the network and out of the world, as Create's own train removal does. */
    public void scrap() {
        Train made = train();
        if (made == null) {
            return;
        }
        made.invalid = true;
        List<CarriageContraptionEntity> cars = carriages();
        Create.RAILWAYS.removeTrain(made.id);
        cars.forEach(car -> {
            car.ejectPassengers();
            car.discard();
        });
    }

    /** Takes the train apart into blocks at the station it stands at; answers whether it stood at one. */
    public boolean disassemble() {
        Train made = train();
        GlobalStation at = made == null ? null : made.getCurrentStation();
        if (at == null || !(level.getBlockEntity(at.getBlockEntityPos()) instanceof StationBlockEntity station)) {
            return false;
        }
        return station.tryDisassembleTrain(null);
    }

    public void release(Colony colony) {
        Train made = train();
        if (made != null) {
            presence(colony).ifPresent(trains -> trains.release(made.id));
        }
    }

    public static Optional<RailPresence> presence(Colony colony) {
        return RailContent.presenceIn(colony.service(RailContent.ID, Object.class));
    }

    private static StationBlockEntity station(ServerLevel level, BlockPos at) {
        return level.getBlockEntity(at) instanceof StationBlockEntity station ? station : null;
    }

    private static BlockState block(String written) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written, false).blockState();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException(written, e);
        }
    }
}

import dev.blockwright.api.Context;
import blockwright.minecraft.core.Args;
import com.atsuishio.superbwarfare.block.AircraftCatapultBlock;
import com.atsuishio.superbwarfare.block.CatapultControllerBlock;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import com.atsuishio.superbwarfare.init.ModBlocks;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.izakyl.folkways.airline.AirlineContent;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaterniond;
import org.joml.Vector3d;

// The promo carrier: a straight-deck flight deck on a haze-gray hull with a pointed, raked bow and a deck that
// overhangs the hull, the island on the starboard side, and a keel of levitite. Local axes: x from the stern (0) to
// the bow (+x), z across with starboard +z, y up from the flight deck (0). The flight deck's runway is the Airline
// runway the tower surveys (gray concrete, white threshold rows and dashed centreline), two
// helipads stand on the port side, the SW catapult is set into the runway's centreline short of the bow, and the
// airport tower with its controller's seat crowns the island's aft end, facing aft and scanning forward along the deck:
// its rear 120 degree sector takes in the whole runway and both pads from there.
//
// op=build   lays the carrier out in the world at `x y z` (the stern end of the deck's centreline) and pauses physics;
//            the take then runs `sable assemble area` over the returned box.
// op=float   finds the assembled structure, turns it to `turn` degrees about the vertical, puts the deck's middle at
//            `at`, holds it there with a velocity controller and resumes physics. Answers the tower's storage cell.
// op=where   world positions and yaws for local deck points (`points`: [{x, z, y?}]).
// op=spawn   parks vehicles at local deck points (`vehicles`: [{key, type, x, z, heading}]), heading in local degrees
//            from the bow (0 = toward the bow).
// op=shuttle puts the catapult's shuttle on its first rail.
// op=place   moves a vehicle already in the world, crew and all, onto a local deck point ({id, x, z, heading}):
//            residents board a hull only from footing in the world, and the deck's blocks live in the structure's
//            plot, so the deck's Ju-87 is crewed on the grass below and then set on the deck.
// op=seat    sits a resident in the tower's chair, as the ground tower's controller is seated.
// op=sites   the carrier airport's id and its landing sites, once its survey offers them.
// op=track   where vehicles are in deck cells (x from the stern, z across), with their speed and footing.
public final class Task {
    // Deck plan, in local cells.
    static final int LENGTH = 198;
    static final int HALF = 20;
    // The runway sits a little to port of the centreline, as a carrier's does, with four cells of deck between its
    // edge and the island.
    static final int RUNWAY_FROM = 36;
    static final int RUNWAY_TO = 185;
    static final int RUNWAY_Z = -3;
    static final int RUNWAY_HALF = 7;
    static final int[] ISLAND = {18, 44, 9, 16};
    static final int TOP = 17;
    static final int[] TOWER = {22, TOP, 10};
    static final int[][] PADS = {{46, -16}, {68, -16}};
    static final int PAD_HALF = 4;
    static final int CATAPULT = 120;
    static final int RAILS = 12;
    static final int DEPTH = 13;
    static final int[] BASE = {18, 16, 15, 15, 15, 15, 15, 15, 15, 14, 13, 12, 10, 6};

    static ServerLevel level;
    static BlockPos base;

    public static Object run(Context ctx) throws Exception {
        var a = Args.of(ctx);
        level = ((MinecraftServer) ctx.server()).overworld();
        return switch (a.string("op")) {
            case "build" -> build(new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z")));
            case "float" -> hold(a);
            case "where" -> where(a);
            case "spawn" -> spawn(a);
            case "shuttle" -> shuttle();
            case "seat" -> seat(a.string("resident"));
            case "place" -> place(a);
            case "sites" -> sites();
            case "track" -> track(a.strings("ids"));
            default -> throw new IllegalArgumentException(a.string("op"));
        };
    }

    // ------------------------------------------------------------------------------------------------- the hull

    static int deckHalf(int x) {
        if (x < 0 || x >= LENGTH) return -1;
        if (x == 0) return HALF - 2;
        if (x == 1) return HALF - 1;
        if (x <= 172) return HALF;
        double t = (x - 172) / 26.0;
        return (int) Math.floor(HALF * (1 - t * t));
    }

    static int hullHalf(int x, int d) {
        if (d == 0) return deckHalf(x);
        if (d > DEPTH) return -1;
        int stern = 3 + Math.max(0, d - 7) * 3;
        int tip = 194 - 2 * d;
        if (x < stern || x > tip) return -1;
        double w = BASE[d];
        if (x > tip - 40) {
            double t = (x - (tip - 40)) / 40.0;
            w *= Math.sqrt(Math.max(0, 1 - t * t));
        }
        return (int) Math.round(w);
    }

    static boolean hull(int x, int d, int z) {
        int w = hullHalf(x, d);
        return w >= 0 && Math.abs(z) <= w;
    }

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).orElse(Blocks.AMETHYST_BLOCK)
            .defaultBlockState();
    }

    static final List<BlockPos> placed = new ArrayList<>();

    static void put(int x, int y, int z, BlockState state) {
        BlockPos pos = base.offset(x, y, z);
        level.setBlock(pos, state, 2);
        placed.add(pos);
    }

    static Object build(BlockPos at) {
        base = at;
        placed.clear();
        SubLevelPhysicsSystem.require(level).setPaused(true);
        for (int cx = (at.getX() - 8) >> 4; cx <= (at.getX() + LENGTH + 8) >> 4; cx++) {
            for (int cz = (at.getZ() - 30) >> 4; cz <= (at.getZ() + 30) >> 4; cz++) {
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        var deck = block("minecraft:deepslate_tiles");
        var edge = block("minecraft:calcite");
        var gray = Blocks.GRAY_CONCRETE.defaultBlockState();
        var white = Blocks.WHITE_CONCRETE.defaultBlockState();
        var haze = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        var bay = Blocks.BLACK_STAINED_GLASS.defaultBlockState();
        var boot = Blocks.BLACK_CONCRETE.defaultBlockState();
        var red = Blocks.RED_TERRACOTTA.defaultBlockState();
        var keel = block("aeronautics:levitite");
        // The hull: only its shell, each layer meeting the next face to face.
        for (int d = 0; d <= DEPTH; d++) {
            for (int x = -1; x <= LENGTH; x++) {
                int w = hullHalf(x, d);
                if (w < 0) continue;
                for (int z = -w; z <= w; z++) {
                    boolean shell = d == 0 || d == DEPTH || !hull(x + 1, d, z) || !hull(x - 1, d, z)
                        || !hull(x, d, z + 1) || !hull(x, d, z - 1) || !hull(x, d + 1, z) || !hull(x, d - 1, z);
                    if (!shell) continue;
                    BlockState state;
                    if (d == 0) state = deck;
                    else if (d >= DEPTH - 1) state = keel;
                    else if (d >= 10) state = red;
                    else if (d == 9) state = boot;
                    else if (d == 3 && Math.abs(z) == w && x > 24 && x < 168 && Math.floorMod(x, 8) < 5) state = bay;
                    else state = haze;
                    put(x, -d, z, state);
                }
            }
        }
        // The runway: white threshold rows at both ends, a dashed white centreline, gray elsewhere, and a calcite
        // edge line on the deck beside it, interrupted by the shared port-side apron.
        for (int x = RUNWAY_FROM; x <= RUNWAY_TO; x++) {
            for (int z = -RUNWAY_HALF; z <= RUNWAY_HALF; z++) {
                boolean threshold = (x >= RUNWAY_FROM + 2 && x < RUNWAY_FROM + 10
                    || x > RUNWAY_TO - 10 && x <= RUNWAY_TO - 2)
                    && Math.abs(z) < RUNWAY_HALF - 1 && z % 2 == 0;
                boolean sideline = Math.abs(z) == RUNWAY_HALF;
                boolean dash = z == 0 && x > RUNWAY_FROM + 2 && x < RUNWAY_TO - 2 && Math.floorMod(x - RUNWAY_FROM, 8) < 4;
                put(x, 0, RUNWAY_Z + z, threshold || sideline || dash ? white : gray);
            }
            put(x, 0, RUNWAY_Z - RUNWAY_HALF - 1, edge);
            put(x, 0, RUNWAY_Z + RUNWAY_HALF + 1, edge);
        }
        // Shared pavement joins both pads to the runway across its port-side calcite trim. Keep a gray row
        // between the white pad borders and runway sideline so their marking components remain separate.
        // The broad apron gives GroundAreas positive clearance throughout, rather than a one-cell neck.
        for (int x = PADS[0][0] - PAD_HALF - 1; x <= PADS[1][0] + PAD_HALF + 1; x++) {
            for (int z = PADS[0][1] - PAD_HALF; z < RUNWAY_Z - RUNWAY_HALF; z++) {
                put(x, 0, z, gray);
            }
        }
        // Helipads on the port side: white border and slender white H, gray between.
        for (int[] pad : PADS) {
            for (int dx = -PAD_HALF; dx <= PAD_HALF; dx++) {
                for (int dz = -PAD_HALF; dz <= PAD_HALF; dz++) {
                    int along = Math.max(2, PAD_HALF - 2);
                    int across = Math.max(1, (int) ((along * 2 + 1) * 0.6) / 2);
                    boolean border = Math.abs(dx) == PAD_HALF || Math.abs(dz) == PAD_HALF;
                    boolean h = Math.abs(dx) <= across && Math.abs(dz) <= along
                        && (Math.abs(dx) >= across || dz == 0);
                    put(pad[0] + dx, 0, pad[1] + dz, border || h ? white : gray);
                }
            }
        }
        // The catapult: controller at full launch power, lever underneath the taxi lane, twelve centreline rails.
        put(CATAPULT - 1, 0, RUNWAY_Z, ModBlocks.CATAPULT_CONTROLLER.get().defaultBlockState()
            .setValue(CatapultControllerBlock.FACING, Direction.EAST).setValue(CatapultControllerBlock.LAUNCH_POWER, 15));
        // Keep the lever below deck level so the approaching aircraft cannot collide with it.
        put(CATAPULT - 1, -1, RUNWAY_Z, Blocks.LEVER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.CEILING));
        for (int n = 0; n < RAILS; n++) {
            put(CATAPULT + n, 0, RUNWAY_Z, ModBlocks.AIRCRAFT_CATAPULT.get().defaultBlockState()
                .setValue(AircraftCatapultBlock.FACING, Direction.EAST));
        }
        island(haze, bay);
        // The box `sable assemble area` takes in: the hull, the island and its mast.
        BlockPos min = base.offset(-2, -DEPTH - 1, -HALF - 1);
        BlockPos max = base.offset(LENGTH + 1, TOP + 10, HALF + 1);
        return Map.of("blocks", placed.size(), "min", List.of(min.getX(), min.getY(), min.getZ()),
            "max", List.of(max.getX(), max.getY(), max.getZ()));
    }

    /**
     * The island: a long lower superstructure with bands of windows, the tower section over its aft end with the
     * airport tower on its forward edge and the controller's chair just aft of it inside a glass cab, and a lattice
     * mast forward. Nothing full and opaque stands ahead of the tower above the lower block's roof, and that roof is
     * low enough for the tower's sight lines to the near threshold to pass over it.
     */
    static void island(BlockState haze, BlockState glass) {
        int x0 = ISLAND[0], x1 = ISLAND[1], z0 = ISLAND[2], z1 = ISLAND[3];
        var dark = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        var roof = block("minecraft:smooth_quartz_slab");
        var deckSlab = block("minecraft:polished_andesite_slab").setValue(SlabBlock.TYPE, SlabType.TOP);
        var pane = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        var bars = Blocks.IRON_BARS.defaultBlockState();
        var rod = Blocks.LIGHTNING_ROD.defaultBlockState();
        // The lower block, its forward corners cut back.
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == x1 && (z == z0 || z == z1)) continue;
                for (int y = 1; y <= 8; y++) {
                    boolean side = x == x0 || x == x1 || z == z0 || z == z1 || x == x1 - 1 && (z == z0 + 1 || z == z1 - 1);
                    boolean top = y == 8;
                    if (!side && !top) continue;
                    boolean window = side && !top && (y == 6 || y == 3 && Math.floorMod(x, 3) != 0);
                    put(x, y, z, top ? dark : window ? glass : haze);
                }
            }
        }
        // The tower section: from the island's aft end up to the tower's floor, a window band two below it.
        int tx = TOWER[0];
        for (int x = x0; x <= tx; x++) {
            for (int z = z0; z <= z0 + 6; z++) {
                boolean side = x == x0 || x == tx || z == z0 || z == z0 + 6;
                for (int y = 9; y < TOP; y++) {
                    if (side || y == TOP - 1) put(x, y, z, side && y == TOP - 3 ? glass : haze);
                }
            }
        }
        // The cab: a floor aft of the tower for the chair, glass walls round both, a slab roof above.
        for (int x = x0; x <= tx + 2; x++) {
            for (int z = z0; z <= z0 + 6; z++) {
                if (x > tx) put(x, TOP - 1, z, deckSlab);
                boolean ring = x == x0 || x == tx + 2 || z == z0 || z == z0 + 6;
                if (ring) {
                    put(x, TOP, z, pane);
                    put(x, TOP + 1, z, pane);
                }
                put(x, TOP + 2, z, roof);
            }
        }
        put(TOWER[0], TOWER[1], TOWER[2], AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        put(TOWER[0] - 1, TOWER[1], TOWER[2], block("create:white_seat"));
        put(x0 + 2, TOP + 3, z0 + 3, rod);
        put(x0 + 2, TOP + 4, z0 + 3, rod);
        // The lattice mast forward, with yards and its radar.
        int mx = x0 + 15, mz = (z0 + z1) / 2;
        for (int y = 9; y <= TOP + 5; y++) put(mx, y, mz, bars);
        for (int z = mz - 3; z <= mz + 3; z++) put(mx, TOP, z, bars);
        for (int z = mz - 2; z <= mz + 2; z++) put(mx, TOP + 3, z, bars);
        put(mx, TOP + 6, mz, block("minecraft:daylight_detector"));
        put(mx, TOP + 7, mz, rod);
        put(mx + 8, 9, mz, block("minecraft:daylight_detector"));
        put(mx - 6, 9, mz, bars);
        put(mx - 6, 10, mz, rod);
    }

    // ------------------------------------------------------------------------------------------- the structure

    static ServerSubLevel carrier() {
        for (var candidate : SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if (candidate instanceof ServerSubLevel ship && ship.getUserDataTag() != null
                    && ship.getUserDataTag().getBoolean("carrier")) {
                return ship;
            }
        }
        return null;
    }

    /** The plot cell the deck's local origin landed on. */
    static BlockPos origin(ServerSubLevel ship) {
        var tag = ship.getUserDataTag();
        return BlockPos.of(tag.getLong("origin"));
    }

    static Object hold(Args a) {
        ServerSubLevel ship = carrier();
        if (ship == null) {
            for (var candidate : SubLevelContainer.getContainer(level).getAllSubLevels()) {
                if (!(candidate instanceof ServerSubLevel one)) continue;
                var box = one.getPlot().getBoundingBox();
                for (int x = box.minX(); x <= box.maxX() && ship == null; x++) {
                    for (int y = box.minY(); y <= box.maxY() && ship == null; y++) {
                        for (int z = box.minZ(); z <= box.maxZ() && ship == null; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (level.getBlockState(pos).is(AirlineContent.AIRPORT_TOWER.get())) {
                                ship = one;
                                if (one.getUserDataTag() == null) one.setUserDataTag(new CompoundTag());
                                one.getUserDataTag().putBoolean("carrier", true);
                                one.getUserDataTag().putLong("origin",
                                    pos.offset(-TOWER[0], -TOWER[1], -TOWER[2]).asLong());
                            }
                        }
                    }
                }
            }
        }
        if (ship == null) throw new IllegalStateException("no assembled carrier");
        final ServerSubLevel held = ship;
        BlockPos origin = origin(ship);
        var handle = RigidBodyHandle.of(ship);
        Quaterniond turn = new Quaterniond().rotateY(Math.toRadians(a.number("turn")));
        var target = a.object("at");
        Vec3 want = new Vec3(target.number("x"), target.number("y"), target.number("z"));
        Vec3 middle = Vec3.atBottomCenterOf(origin.offset(LENGTH / 2, 0, 0));
        Vector3d position = new Vector3d(ship.logicalPose().position());
        for (int pass = 0; pass < 2; pass++) {
            handle.teleport(position, turn);
            SubLevelPhysicsSystem.require(level).updatePose(ship);
            Vec3 now = ship.logicalPose().transformPosition(middle);
            position.add(want.x - now.x, want.y - now.y, want.z - now.z);
        }
        handle.teleport(position, turn);
        SubLevelPhysicsSystem.require(level).updatePose(ship);
        final Vector3d hold = new Vector3d(position);
        final Quaterniond attitude = new Quaterniond(turn);
        if (!ship.getUserDataTag().getBoolean("held")) {
            ship.getUserDataTag().putBoolean("held", true);
            NeoForge.EVENT_BUS.addListener((ForgeSablePrePhysicsTickEvent event) -> {
                if (event.getPhysicsSystem().getLevel() != level || held.isRemoved()) return;
                var body = event.getPhysicsSystem().getPhysicsHandle(held);
                if (body == null || !body.isValid()) return;
                var pose = held.logicalPose();
                Vector3d velocity = new Vector3d(hold).sub(pose.position()).mul(4.0);
                velocity.set(Math.clamp(velocity.x, -5, 5), Math.clamp(velocity.y, -5, 5), Math.clamp(velocity.z, -5, 5));
                velocity.sub(body.getLinearVelocity());
                // Turn back toward the held attitude: the error's rotation vector, scaled.
                Quaterniond error = new Quaterniond(attitude).mul(new Quaterniond(pose.orientation()).conjugate());
                if (error.w < 0) error.scale(-1);
                Vector3d spin = new Vector3d(error.x, error.y, error.z).mul(4.0);
                spin.sub(body.getAngularVelocity());
                body.addLinearAndAngularVelocity(velocity, spin);
            });
        }
        SubLevelPhysicsSystem.require(level).setPaused(false);
        BlockPos tower = origin.offset(TOWER[0], TOWER[1], TOWER[2]);
        Vec3 bow = ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(origin.offset(LENGTH, 0, 0)))
            .subtract(ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(origin)));
        return Map.of("structure", ship.getUniqueId().toString(), "origin", List.of(origin.getX(), origin.getY(),
            origin.getZ()), "tower", Map.of("x", tower.getX(), "y", tower.getY(), "z", tower.getZ()),
            "heading", Math.toDegrees(Math.atan2(bow.x, -bow.z)), "position", List.of(hold.x, hold.y, hold.z));
    }

    static float yaw(Vec3 forward) {
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    static Map<String, Object> point(ServerSubLevel ship, double x, double y, double z, double heading) {
        BlockPos origin = origin(ship);
        var pose = ship.logicalPose();
        Vec3 local = new Vec3(origin.getX() + x + .5, origin.getY() + 1 + y, origin.getZ() + z + .5);
        Vec3 world = pose.transformPosition(local);
        double rad = Math.toRadians(heading);
        Vec3 ahead = pose.transformPosition(local.add(Math.cos(rad), 0, Math.sin(rad))).subtract(world);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", world.x);
        out.put("y", world.y);
        out.put("z", world.z);
        out.put("yaw", yaw(ahead));
        out.put("storage", List.of(origin.getX() + (int) Math.floor(x), origin.getY() + (int) Math.floor(y),
            origin.getZ() + (int) Math.floor(z)));
        return out;
    }

    static Object where(Args a) {
        ServerSubLevel ship = carrier();
        List<Object> out = new ArrayList<>();
        for (Object raw : a.list("points")) {
            var p = Args.of(raw, "point");
            out.add(point(ship, p.number("x"), p.has("y") ? p.number("y") : 0, p.number("z"),
                p.has("heading") ? p.number("heading") : 0));
        }
        return out;
    }

    static Object spawn(Args a) {
        ServerSubLevel ship = carrier();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object raw : a.list("vehicles")) {
            var v = Args.of(raw, "vehicle");
            var at = point(ship, v.number("x"), 0.2, v.number("z"), v.number("heading"));
            var vehicle = (com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity) BuiltInRegistries.ENTITY_TYPE
                .get(ResourceLocation.parse(v.string("type"))).create(level);
            float yaw = ((Number) at.get("yaw")).floatValue();
            vehicle.moveTo((double) at.get("x"), (double) at.get("y"), (double) at.get("z"), yaw, 0);
            vehicle.setYHeadRot(yaw);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("folkways_promo");
            level.addFreshEntity(vehicle);
            out.put(v.string("key"), Map.of("uuid", vehicle.getUUID().toString(), "at", at));
        }
        return out;
    }

    static Object shuttle() {
        ServerSubLevel ship = carrier();
        var at = point(ship, CATAPULT, -1, RUNWAY_Z, 0);
        var shuttle = new CatapultShuttleEntity(level);
        shuttle.moveTo((double) at.get("x"), (double) at.get("y"), (double) at.get("z"));
        shuttle.setYRot(((Number) at.get("yaw")).floatValue());
        shuttle.addTag("folkways_promo");
        level.addFreshEntity(shuttle);
        return Map.of("shuttle", shuttle.getUUID().toString(), "at", at);
    }

    static Object seat(String resident) {
        ServerSubLevel ship = carrier();
        BlockPos origin = origin(ship);
        BlockPos chair = origin.offset(TOWER[0] - 1, TOWER[1], TOWER[2]);
        var at = point(ship, TOWER[0] - 1, TOWER[1], TOWER[2], 0);
        var body = level.getEntity(UUID.fromString(resident));
        body.moveTo((double) at.get("x"), (double) at.get("y"), (double) at.get("z"), (float) at.get("yaw"), 0);
        com.simibubi.create.content.contraptions.actors.seat.SeatBlock.sitDown(level, chair, body);
        return Map.of("seat", chair.toShortString(), "riding", body.isPassenger(),
            "vehicle", String.valueOf(body.getVehicle()), "block", level.getBlockState(chair).toString());
    }

    static Object sites() {
        ServerSubLevel ship = carrier();
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (var site : LandingSites.in(level)) {
            if (site.position().realm() instanceof Realm.Frame frame && frame.structure().equals(ship.getUniqueId())) {
                out.put("airport", site.airport().id());
                out.put("name", site.airport().name());
                ids.add(site.id() + " " + site.kind() + " len=" + site.length() + " w=" + site.width());
            }
        }
        out.put("sites", ids);
        return out;
    }

    static Object track(List<String> ids) {
        ServerSubLevel ship = carrier();
        BlockPos origin = origin(ship);
        var frame = WorldSpaces.frame(level, ship.getUniqueId()).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : ids) {
            var e = level.getEntity(UUID.fromString(id));
            if (e == null) {
                out.put(id, Map.of("gone", true));
                continue;
            }
            Vec3 storage = frame.toLocal(e.position()).add(Vec3.atLowerCornerOf(frame.storageOrigin()));
            Vec3 local = storage.subtract(Vec3.atLowerCornerOf(origin));
            out.put(id, Map.of("x", local.x, "y", local.y - 1, "z", local.z, "ground", e.onGround(),
                "speed", e.getDeltaMovement().length(), "world", List.of(e.getX(), e.getY(), e.getZ()),
                "chunk", e.chunkPosition().toString(), "block", e.blockPosition().toString()));
        }
        return out;
    }

    static Object place(Args a) {
        ServerSubLevel ship = carrier();
        var at = point(ship, a.number("x"), 0, a.number("z"), a.number("heading"));
        var e = level.getEntity(UUID.fromString(a.string("id")));
        float yaw = ((Number) at.get("yaw")).floatValue();
        e.moveTo((double) at.get("x"), (double) at.get("y"), (double) at.get("z"), yaw, 0);
        e.setYHeadRot(yaw);
        e.setDeltaMovement(Vec3.ZERO);
        return Map.of("at", at, "aboard", e.getPassengers().size());
    }
}

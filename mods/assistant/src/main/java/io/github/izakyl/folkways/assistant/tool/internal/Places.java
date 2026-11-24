package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a pending change stands in the world, so the client can mark it before the player decides: a resident, a
 * line of points, a box of cells, or a facility's extent before and after the change, in one dimension. A change with
 * no place in the world, such as a colony script, has none.
 */
final class Places {

    private static final int MAX_BOXES = 32;

    private Places() {
    }

    static Optional<JsonObject> of(ToolContext context, ActionBatch.Intent intent, ToolReply preview) {
        try {
            return find(context, intent.action(), intent.arguments(), preview);
        } catch (RuntimeException unplaced) {
            // A place only helps the player look; a change without one is still decided on its words.
            return Optional.empty();
        }
    }

    private static Optional<JsonObject> find(ToolContext context, String action, JsonObject args, ToolReply preview) {
        String here = context.player().serverLevel().dimension().location().toString();
        return switch (action) {
            case "rename_resident", "set_work" -> Residents.of(context.colony(), context.player().getServer(),
                    UUID.fromString(args.get("resident_id").getAsString())).map(body -> {
                        JsonObject place = place(body.mob().level().dimension().location().toString());
                        place.addProperty("entity", body.mob().getUUID().toString());
                        return place;
                    });
            case "create_path", "request_survey" -> {
                JsonArray points = new JsonArray();
                args.getAsJsonArray("points").forEach(point -> points.add(cell(at(point.getAsJsonObject()))));
                JsonObject place = place(args.get("dimension").getAsString());
                place.add("points", points);
                yield Optional.of(place);
            }
            case "create_zone" -> Optional.of(box(args.get("dimension").getAsString(),
                    at(args.getAsJsonObject("from")), at(args.getAsJsonObject("to"))));
            case "commission_build" -> bounds(preview.ghosts().stream().map(Ghost::pos).toList())
                    .map(corners -> box(here, corners[0], corners[1]));
            case "erase_zone_or_path" -> claimed(context, UUID.fromString(args.get("id").getAsString()));
            case "set_setting" -> args.has("zone_or_path")
                    ? claimed(context, UUID.fromString(args.get("zone_or_path").getAsString())) : Optional.empty();
            case "set_facility" -> extents(context, FacilityTools.previousExtent(preview),
                    FacilityTools.previewExtent(preview)).or(() -> FacilityTools.previewExtent(preview).firstBlock()
                    .flatMap(site -> point(context, site)));
            case "run", "cancel", "set_facility_parameter", "delete_facility" -> FacilityTools.find(context,
                    args.get(switch (action) {
                        case "delete_facility" -> "name";
                        case "set_facility_parameter" -> "id";
                        default -> "facility";
                    }).getAsString())
                    .map(operating -> operating.facility().site()).flatMap(site -> point(context, site));
            default -> Optional.empty();
        };
    }

    private static Optional<JsonObject> point(ToolContext context, WorldPos site) {
        return WorldSpaces.resolve(context.player().getServer(), site).map(where -> {
            JsonObject place = place(where.level().dimension().location().toString());
            JsonArray points = new JsonArray();
            points.add(cell(where.cell()));
            place.add("points", points);
            return place;
        });
    }

    // The extent as boxes in the player's own dimension, each [minX, minY, minZ, maxX, maxY, maxZ] round whole cells;
    // a structure's box is the world box round its turned corners.
    private static Optional<JsonObject> extents(ToolContext context, FacilityExtent before, FacilityExtent after) {
        ServerLevel level = context.player().serverLevel();
        JsonArray was = boxes(level, before);
        JsonArray will = boxes(level, after);
        if (will.isEmpty()) {
            return Optional.empty();
        }
        JsonObject extent = new JsonObject();
        extent.add("before", was);
        extent.add("after", will);
        JsonObject place = place(level.dimension().location().toString());
        place.add("extent", extent);
        return Optional.of(place);
    }

    private static JsonArray boxes(ServerLevel level, FacilityExtent extent) {
        JsonArray boxes = new JsonArray();
        for (FacilityExtent.Element element : extent.elements()) {
            if (boxes.size() >= MAX_BOXES) {
                break;
            }
            Optional<AABB> box = switch (element) {
                case FacilityExtent.Block block -> world(level, block.pos().realm(), block.pos().cell(),
                        block.pos().cell());
                case FacilityExtent.Box bounds -> world(level, bounds.realm(), bounds.min(), bounds.max());
                case FacilityExtent.Entity ignored -> Optional.empty();
            };
            box.ifPresent(found -> {
                JsonArray row = new JsonArray();
                for (double value : new double[] {found.minX, found.minY, found.minZ, found.maxX, found.maxY,
                        found.maxZ}) {
                    row.add(Math.round(value * 100) / 100.0);
                }
                boxes.add(row);
            });
        }
        return boxes;
    }

    private static Optional<AABB> world(ServerLevel level, Realm realm, BlockPos min, BlockPos max) {
        if (realm instanceof Realm.Dimension dimension) {
            return dimension.id().equals(level.dimension())
                    ? Optional.of(new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max.offset(1, 1, 1))))
                    : Optional.empty();
        }
        return WorldSpaces.frame(level, ((Realm.Frame) realm).structure()).map(frame -> {
            AABB around = null;
            for (int corner = 0; corner < 8; corner++) {
                Vec3 at = frame.toWorld(new Vec3((corner & 1) == 0 ? min.getX() : max.getX() + 1,
                        (corner & 2) == 0 ? min.getY() : max.getY() + 1, (corner & 4) == 0 ? min.getZ() : max.getZ() + 1));
                around = around == null ? new AABB(at, at) : around.minmax(new AABB(at, at));
            }
            return around;
        });
    }

    private static Optional<JsonObject> claimed(ToolContext context, UUID id) {
        var server = context.player().getServer();
        return context.colony().claim(id).flatMap(claim -> {
            List<WorldPos> cells = claim.what().cells();
            return cells.stream().findFirst().flatMap(first -> first.level(server)).flatMap(level ->
                    bounds(cells.stream().filter(cell -> cell.in(level.dimension())).map(WorldPos::cell).toList())
                            .map(corners -> box(level.dimension().location().toString(), corners[0], corners[1])));
        });
    }

    private static Optional<BlockPos[]> bounds(Collection<BlockPos> cells) {
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        BlockPos min = cells.iterator().next();
        BlockPos max = min;
        for (BlockPos cell : cells) {
            min = BlockPos.min(min, cell);
            max = BlockPos.max(max, cell);
        }
        return Optional.of(new BlockPos[] {min, max});
    }

    private static JsonObject box(String dimension, BlockPos from, BlockPos to) {
        JsonObject place = place(dimension);
        JsonArray corners = new JsonArray();
        corners.add(cell(BlockPos.min(from, to)));
        corners.add(cell(BlockPos.max(from, to)));
        place.add("box", corners);
        return place;
    }

    private static JsonObject place(String dimension) {
        JsonObject place = new JsonObject();
        place.addProperty("dimension", dimension);
        return place;
    }

    private static BlockPos at(JsonObject point) {
        return new BlockPos(point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("z").getAsInt());
    }

    private static JsonArray cell(BlockPos pos) {
        JsonArray cell = new JsonArray();
        cell.add(pos.getX());
        cell.add(pos.getY());
        cell.add(pos.getZ());
        return cell;
    }
}

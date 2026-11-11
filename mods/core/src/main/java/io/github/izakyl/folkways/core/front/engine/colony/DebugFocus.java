package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket.Kind;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket.Link;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket.StoreMark;
import io.github.izakyl.folkways.core.front.engine.net.DebugTarget;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.runtime.DebugWork;
import io.github.izakyl.folkways.core.runtime.Labor;
import io.github.izakyl.folkways.core.runtime.StoreDebug;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class DebugFocus {
    private DebugFocus() {
    }

    public static DebugFocusPacket read(ServerPlayer player, Colony colony, DebugTarget requested) {
        ServerLevel level = player.serverLevel();
        ColonyLabor labor = Labor.site(colony.id(), level.dimension()).orElse(null);
        List<Line> card = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        DebugTarget target = DebugTarget.NONE;
        var view = colony.view(level);
        if (requested instanceof DebugTarget.Resident resident) {
            var entity = level.getEntity(resident.entityId());
            var body = entity == null ? Optional.<Body>empty() : Bodies.of(entity);
            if (body.isPresent() && colony.claimOf(body.get().id()).isPresent()
                    && body.get().colonyId().filter(colony.id()::equals).isPresent()) {
                target = requested;
                card.add(Line.text(entity.getDisplayName()));
                if (labor != null) {
                    var debug = labor.debugResident(level, entity.getUUID());
                    card.addAll(debug.lines());
                    DebugTarget previous = target;
                    for (DebugWork work : debug.route()) {
                        Optional<DebugTarget> site = endpoint(level, work.spec().site().where());
                        if (site.isEmpty()) {
                            continue;
                        }
                        Component label = label(work);
                        links.add(new Link(previous, site.get(), Kind.ROUTE, work.index(), label));
                        previous = site.get();
                        if (work.index() <= 2) {
                            var from = new LinkedHashSet<>(work.placement().from());
                            work.placement().draws().forEach(draw -> draw.from().ifPresent(from::add));
                            for (var stash : from) {
                                endpoint(level, stash.pos()).ifPresent(at -> links.add(new Link(site.get(), at,
                                    Kind.DRAW, work.index(), label)));
                            }
                            for (var stash : work.placement().into()) {
                                endpoint(level, stash.pos()).ifPresent(at -> links.add(new Link(site.get(), at,
                                    Kind.INTO, work.index(), label)));
                            }
                        }
                        if (links.size() >= DebugFocusPacket.MAX_LINKS) {
                            break;
                        }
                    }
                }
                for (var owner : Enrollments.facingOwners()) {
                    Facing.of(colony, owner).ifPresent(facing ->
                        extension(card, owner, facing.debugResidentLines(entity.getUUID(), view)));
                }
            }
        } else if (requested instanceof DebugTarget.Block block && level.isLoaded(block.pos())) {
            WorldPos at = WorldPos.of(level, block.pos());
            var debug = labor == null ? null : labor.debugSite(level, at);
            boolean member = colony.claimAt(at).isPresent();
            boolean store = colony.inventory().snapshot().store(at).isPresent();
            if (member || store || debug != null && !debug.work().isEmpty()) {
                target = requested;
                card.add(Line.text(Component.translatable("folkways.debug.overlay.facility",
                    DebugText.where(block.pos().toShortString()))));
                if (debug != null) {
                    card.addAll(debug.lines());
                    for (DebugWork work : debug.work()) {
                        work.resident().map(level::getEntity).ifPresent(entity -> links.add(new Link(block,
                            new DebugTarget.Resident(entity.getId()), Kind.ASSIGNEE, work.index(),
                            Component.translatable("folkways.debug.overlay.assignee",
                                entity.getDisplayName(), label(work)))));
                    }
                }
                if (labor == null) {
                    colony.inventory().snapshot().store(at).ifPresent(one ->
                        card.addAll(StoreDebug.lines(level, one, colony.inventory())));
                }
                for (var owner : Enrollments.facingOwners()) {
                    Facing.of(colony, owner).ifPresent(facing ->
                        extension(card, owner, facing.debugBlockLines(block.pos(), view)));
                }
            }
        }
        List<StoreMark> stores = new ArrayList<>();
        colony.inventory().snapshot().stores().values().stream()
            .filter(store -> store.dimension().equals(level.dimension())
                && near(level, player, store.stash().pos(), 48))
            .sorted(Comparator.comparingDouble(store -> WorldSpaces.world(level, store.stash().pos())
                .map(pos -> pos.distanceToSqr(player.position())).orElse(Double.MAX_VALUE)))
            .limit(DebugFocusPacket.MAX_STORES).forEach(store -> {
                boolean differs = !StoreDebug.read(level, store, colony.inventory()).differences().isEmpty();
                WorldSpaces.storage(level, store.stash().pos()).ifPresent(pos ->
                    stores.add(new StoreMark(pos, store.state(), differs)));
                store.aliases().stream().filter(alias -> !alias.equals(store.stash().pos()))
                    .forEach(alias -> WorldSpaces.storage(level, alias).ifPresent(pos -> {
                        if (stores.size() < DebugFocusPacket.MAX_STORES) {
                            stores.add(new StoreMark(pos, store.state(), differs));
                        }
                    }));
            });
        return new DebugFocusPacket(target, card, links, stores);
    }

    private static void extension(List<Line> card, ResourceLocation owner, List<Line> lines) {
        if (!lines.isEmpty()) {
            card.add(DebugText.section("extension", Component.literal(owner.toString())));
            card.addAll(lines);
        }
    }

    private static Component label(DebugWork work) {
        return Component.translatable("folkways.debug.overlay.link", work.index(), ColonyLabor.job(work.spec()));
    }

    private static Optional<DebugTarget> endpoint(ServerLevel level, WorldPos at) {
        return at.in(level) ? WorldSpaces.storage(level, at).map(DebugTarget.Block::new) : Optional.empty();
    }

    private static boolean near(ServerLevel level, ServerPlayer player, WorldPos at, int range) {
        return WorldSpaces.world(level, at).map(pos -> pos.distanceToSqr(player.position()) <= range * range)
            .orElse(false);
    }
}

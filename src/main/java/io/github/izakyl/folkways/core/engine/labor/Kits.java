package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class Kits {

    private static final int KIT_RANK = -1;

    private final Map<UUID, Set<UUID>> asked = new HashMap<>();

    void read(Colony colony, Body body, Consumer<Message> to) {
        Set<UUID> mine = asked.computeIfAbsent(body.id(), ignored -> new LinkedHashSet<>());
        WorkSite at = new WorkSite.AtEntity(body.id(), WorldSpaces.at(body.mob()));
        Set<UUID> still = new HashSet<>();
        for (ItemSpec tool : Kit.of(colony, body).specs()) {
            UUID id = idOf(body.id(), tool);
            boolean carried = Kit.carried(body, tool) > 0;
            if (!carried) {
                still.add(id);
            }
            if (!carried && mine.add(id)) {
                to.accept(new Message.Submitted(Haul.DOMAIN, Grown.of(
                    Delivery.to(id, Haul.DOMAIN, at, Stances.WHEREVER, tool, 1, count -> { })).ranked(KIT_RANK)));
            }
        }
        // Asked for, but now carried or no longer in the kit - the colony may have narrowed its tools.
        for (UUID id : List.copyOf(mine)) {
            if (!still.contains(id)) {
                mine.remove(id);
                to.accept(new Message.Withdrawn(Haul.DOMAIN, id));
            }
        }
    }

    void ended(UUID node) {
        asked.values().forEach(mine -> mine.remove(node));
    }

    void left(UUID resident, Consumer<Message> to) {
        Set<UUID> mine = asked.remove(resident);
        if (mine != null) {
            mine.forEach(id -> to.accept(new Message.Withdrawn(Haul.DOMAIN, id)));
        }
    }

    private static UUID idOf(UUID resident, ItemSpec tool) {
        return UUID.nameUUIDFromBytes((resident + "|" + tool.describe()).getBytes(StandardCharsets.UTF_8));
    }
}

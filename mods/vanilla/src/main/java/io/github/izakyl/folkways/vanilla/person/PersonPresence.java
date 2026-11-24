package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.front.api.Endorsement;
import io.github.izakyl.folkways.core.front.engine.colony.Endorsements;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class PersonPresence implements ColonyPanel {

    private static final int SWEEP_TICKS = 100;

    private final Colony colony;

    private final SettlerQueue settlers;

    // When the settlers are counted within each period, spread against every other cron; see Cadence.
    private final Cadence.Cron cron;
    private boolean swept;
    private Endorsement.Called pendingAdmission;

    PersonPresence(Colony colony) {
        this.colony = colony;
        this.settlers = SettlerQueue.from(colony.kept(PersonContent.ID));
        this.cron = Cadence.claim(colony, SWEEP_TICKS);
    }

    public void tick(MinecraftServer server) {
        if (pendingAdmission != null) {
            colony.inventory().ensureObserved();
            if (colony.inventory().snapshot().coverage().complete()) {
                Endorsement.Called retry = pendingAdmission;
                pendingAdmission = null;
                var result = Endorsements.called(retry.view().level(), colony, retry.by(), retry.kind());
                if (retry.by() instanceof ServerPlayer player) {
                    result.refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
                }
            }
        }
        if (!swept || cron.due(server)) {
            swept = true;
            refresh(server);
        }
    }

    void retry(Endorsement.Called called) {
        pendingAdmission = called;
    }

    private void refresh(MinecraftServer server) {
        if (settlers.gather(server.overworld().getDayTime(), people(server))) {
            keep();
        }
    }

    private int people(MinecraftServer server) {
        int counted = 0;
        for (ColonyView view : colony.views(server)) {
            counted += heads(view);
        }
        return counted;
    }

    static int heads(ColonyView view) {
        int counted = 0;
        for (Resident resident : view.residents()) {
            if (resident.kind().equals(PersonBody.ID)) {
                counted++;
            }
        }
        return counted;
    }

    int waiting() {
        return settlers.waiting();
    }

    boolean take() {
        if (!settlers.take()) {
            return false;
        }
        keep();
        return true;
    }

    private void keep() {
        colony.keep(PersonContent.ID, settlers.save());
    }

    @Override
    public List<Card.Row> rows(Page page, ColonyView view) {
        if (page != Page.RESIDENTS) {
            return List.of();
        }
        return List.of(Card.Row.of(new ItemStack(Items.BELL),
            Line.text(Component.translatable("folkways.settlers.waiting", settlers.waiting(), settlers.cap())),
            List.of(Line.text(nextSettler(view))))
            .with(new Card.Act.Run("admit", Map.of(), new Card.Label("folkways.settlers.admit", true),
                Optional.empty())));
    }

    @Override
    public Optional<List<DefinedFacilities.Problem>> act(ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        if (!routine.equals("admit")) {
            return Optional.empty();
        }
        var result = Endorsements.called(by.serverLevel(), colony, by, PersonBody.ID);
        return Optional.of(result.refusal()
            .map(why -> List.of(new DefinedFacilities.Problem("routine", why)))
            .orElse(List.of()));
    }

    private Component nextSettler(ColonyView view) {
        if (settlers.waiting() >= settlers.cap()) {
            return Component.translatable("folkways.settlers.next.full");
        }
        return Component.translatable("folkways.settlers.next",
            Component.literal(Long.toString(settlers.ticksToNext(heads(view)) / 20L)));
    }
}

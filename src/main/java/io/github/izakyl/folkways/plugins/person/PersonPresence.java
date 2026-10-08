package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.person.look.LookPool;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import io.github.izakyl.folkways.plugins.person.name.ColonyNames;
import io.github.izakyl.folkways.plugins.person.name.NamePool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class PersonPresence implements Facing {

    private static final int SWEEP_TICKS = 100;

    private final Colony colony;

    private final SettlerQueue settlers;

    PersonPresence(Colony colony) {
        this.colony = colony;
        this.settlers = SettlerQueue.from(colony.kept(PersonContent.ID));
    }

    private int sinceSweep = SWEEP_TICKS;

    public void tick(MinecraftServer server) {
        if (++sinceSweep >= SWEEP_TICKS) {
            sinceSweep = 0;
            refresh(server);
        }
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
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(PersonContent.ID)) {
            return Optional.empty();
        }
        List<Board.Row> rows = new ArrayList<>();
        rows.add(Board.Row.heading(Component.translatable("folkways.page.person.joining")));
        rows.add(new Board.Row(new ItemStack(Items.BELL),
            Component.translatable("folkways.settlers.waiting", settlers.waiting(), settlers.cap()),
            nextSettler(view),
            List.of(new Board.Act.Admit("folkways.settlers.admit", PersonBody.ID))));
        rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(PersonContent.NAMES.icon())),
            Component.translatable(PersonContent.NAMES.nameKey()),
            names(view),
            List.of(new Board.Act.Open(PersonContent.NAMES.id()))));
        rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(PersonContent.LOOKS.icon())),
            Component.translatable(PersonContent.LOOKS.nameKey()),
            looks(view),
            List.of(new Board.Act.Open(PersonContent.LOOKS.id()))));
        return Optional.of(new Board(List.of(), rows,
            Optional.of(Component.translatable("folkways.page.person.empty"))));
    }

    private Component names(ColonyView view) {
        NamePool pool = ColonyNames.of(colony, view.level().registryAccess());
        return Component.translatable("folkways.pool.names.short", pool.names().size(), pool.surnames().size());
    }

    private Component looks(ColonyView view) {
        LookPool pool = LookPool.of(colony);
        long admitted = ResidentLooks.keysInPool(view.level().registryAccess()).stream()
            .filter(key -> pool.allows(key.location()))
            .count();
        return Component.translatable("folkways.pool.looks.holds", admitted);
    }

    private Component nextSettler(ColonyView view) {
        if (settlers.waiting() >= settlers.cap()) {
            return Component.translatable("folkways.settlers.next.full");
        }
        return Component.translatable("folkways.settlers.next",
            Component.literal(Long.toString(settlers.ticksToNext(heads(view)) / 20L)));
    }
}

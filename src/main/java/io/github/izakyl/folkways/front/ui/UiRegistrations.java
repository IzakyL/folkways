package io.github.izakyl.folkways.front.ui;

import io.github.izakyl.folkways.front.api.ui.Bay;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Outline;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.front.ui.panel.Inlays;
import io.github.izakyl.folkways.front.ui.screen.PageKinds;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModLoader;

public final class UiRegistrations {

    private static final Map<ResourceLocation, Supplier<? extends Draw>> PAGES = new LinkedHashMap<>();
    private static final List<Outline> OUTLINES = new ArrayList<>();

    private UiRegistrations() {
    }

    public static void gather() {
        RegisteringUi event = new RegisteringUi(new RegisteringUi.Registry() {
            public void page(ResourceLocation page, Supplier<? extends Draw> factory) {
                if (PAGES.putIfAbsent(page, factory) != null) {
                    throw new IllegalStateException(page + " already has a UI factory");
                }
            }
            public void inlay(Bay bay, ResourceLocation owner, Supplier<? extends Inlay> factory) {
                Inlays.register(bay, owner, factory);
            }
            public void outline(Outline outline) {
                OUTLINES.add(outline);
            }
        });
        try {
            ModLoader.postEventWrapContainerInModOrder(event);
        } finally {
            event.close();
        }
        PageKinds.verify();
        var declared = PageKinds.every().stream().map(page -> page.id()).collect(Collectors.toSet());
        for (ResourceLocation page : PAGES.keySet()) {
            if (!declared.contains(page)) {
                throw new IllegalStateException(page + " has a UI factory but no declared page");
            }
        }
    }

    public static Optional<BlockPos> partner(BlockGetter level, BlockPos pos, BlockState state) {
        for (Outline outline : OUTLINES) {
            Optional<BlockPos> partner = outline.partner(level, pos, state);
            if (partner.isPresent()) {
                return partner;
            }
        }
        return Optional.empty();
    }

    public static Optional<Draw> draw(ResourceLocation page) {
        return Optional.ofNullable(PAGES.get(page)).map(Supplier::get);
    }
}

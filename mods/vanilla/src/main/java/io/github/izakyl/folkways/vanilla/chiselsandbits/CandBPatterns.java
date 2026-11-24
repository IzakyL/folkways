package io.github.izakyl.folkways.vanilla.chiselsandbits;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.event.AddPackFindersEvent;

public final class CandBPatterns {

    // The built-in data pack carrying the Chisels & Bits patterns that Vanilla ships.
    private static final String PACK_ID = "mod/folkways_vanilla:chiselsandbits";

    private CandBPatterns() {
    }

    public static void install(IEventBus bus, ModContainer container) {
        bus.addListener(AddPackFindersEvent.class, event -> register(event, container));
    }

    private static void register(AddPackFindersEvent event, ModContainer container) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        var mod = container.getModInfo();
        var path = mod.getOwningFile().getFile().findResource("chiselsandbits");
        var location = new PackLocationInfo(PACK_ID, Component.literal("Folkways Chisels & Bits patterns"),
            PackSource.BUILT_IN, Optional.of(new KnownPack("neoforge", PACK_ID, mod.getVersion().toString())));
        Pack pack = Pack.readMetaAndCreate(location,
            BuiltInPackSource.fromName(info -> new PathPackResources(info, path)),
            PackType.SERVER_DATA, new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }
}

package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Configuration materials, quoted from the same decoded data used by construction. */
final class BlueprintMaterials {
    private BlueprintMaterials() {
    }

    static List<Component> describe(CompoundTag file, HolderLookup.Provider registries) {
        Blueprint blueprint = StructureNbt.toBlueprint(file, registries, UUID.randomUUID(), "preview", 0,
            StructureNbt.AirMeaning.UNCONSTRAINED, BlueprintBook.maxCaptureBlocks()).orElseThrow(StructureCompat::invalid);
        blueprint.compatible();
        Map<ItemSpec, Long> counted = new LinkedHashMap<>();
        Map<ResourceLocation, Component> integrations = new LinkedHashMap<>();
        for (BlueprintBlock block : blueprint.blocks()) {
            var handler = StructureCompat.of(block.state());
            if (handler.isEmpty()) continue;
            integrations.put(handler.get().id(), handler.get().name());
            var entity = handler.get().detached(registries, block.offset(), block.state(), new StructurePayload(block.data().tag()));
            handler.get().materials(entity).forEach(need -> counted.merge(need.spec(), need.count(), Long::sum));
        }
        List<Component> lines = new ArrayList<>();
        integrations.values().forEach(name -> lines.add(Component.translatable("folkways.blueprint.requires", name)));
        counted.entrySet().stream().limit(8).forEach(entry -> lines.add(Component.translatable(
            "folkways.blueprint.micro.material", Goods.display(entry.getKey()).getHoverName(), entry.getValue())));
        if (counted.size() > 8) lines.add(Component.translatable("folkways.blueprint.micro.more", counted.size() - 8));
        if (!counted.isEmpty()) lines.add(Component.translatable("folkways.blueprint.micro.same_state"));
        return List.copyOf(lines);
    }
}

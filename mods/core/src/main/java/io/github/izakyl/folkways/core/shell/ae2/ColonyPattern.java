package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

final class ColonyPattern implements IPatternDetails {

    static final IPatternDetailsDecoder DECODER = new IPatternDetailsDecoder() {
        @Override
        public boolean isEncodedPattern(ItemStack stack) {
            return stack.is(FolkwaysItems.NETWORK_PATTERN.get());
        }

        @Override
        public IPatternDetails decodePattern(AEItemKey key, Level level) {
            return key.is(FolkwaysItems.NETWORK_PATTERN.get()) ? new ColonyPattern(key, level) : null;
        }
    };

    private final AEItemKey definition;
    private final List<IInput> inputs;
    private final List<GenericStack> outputs;

    ColonyPattern(String reference, WorkshopRecipes.Recipe recipe, ServerLevel level) {
        CompoundTag data = new CompoundTag();
        data.putString("recipe", reference);
        ListTag inputs = new ListTag();
        recipe.inputs().forEach(need -> inputs.add(amount(need.spec(), need.count(), level)));
        data.put("inputs", inputs);
        ListTag outputs = new ListTag();
        recipe.outputs().forEach(output -> outputs.add(amount(output.spec(), output.count(), level)));
        data.put("outputs", outputs);
        ItemStack stack = new ItemStack(FolkwaysItems.NETWORK_PATTERN.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        definition = AEItemKey.of(stack);
        this.inputs = readInputs(data, level);
        this.outputs = readOutputs(data, level);
    }

    private ColonyPattern(AEItemKey definition, Level level) {
        this.definition = definition;
        var data = definition.get(DataComponents.CUSTOM_DATA).copyTag();
        inputs = readInputs(data, level);
        outputs = readOutputs(data, level);
    }

    private static CompoundTag amount(ItemSpec spec, long count, Level level) {
        CompoundTag amount = new CompoundTag();
        amount.put("spec", spec.save(level.registryAccess()));
        amount.putLong("count", count);
        return amount;
    }

    private static List<IInput> readInputs(CompoundTag data, Level level) {
        List<IInput> inputs = new ArrayList<>();
        for (var raw : data.getList("inputs", Tag.TAG_COMPOUND)) {
            CompoundTag amount = (CompoundTag) raw;
            ItemSpec spec = ItemSpec.load(Reader.of(amount.getCompound("spec")), level.registryAccess()).orElseThrow();
            long count = amount.getLong("count");
            List<GenericStack> possibilities = new ArrayList<>();
            choices(spec, possibilities);
            inputs.add(new Input(spec, count, List.copyOf(possibilities)));
        }
        return List.copyOf(inputs);
    }

    private static void choices(ItemSpec spec, List<GenericStack> choices) {
        if (!spec.anyOf().isEmpty()) {
            spec.anyOf().forEach(alternative -> choices(alternative, choices));
            return;
        }
        for (var item : Goods.members(spec)) {
            ItemStack stack = Goods.display(new ItemSpec(
                Optional.of(BuiltInRegistries.ITEM.getKey(item)),
                Optional.empty(), Set.of(), spec.components()));
            if (Goods.matches(stack, spec)) {
                choices.add(new GenericStack(AEItemKey.of(stack), 1));
            }
        }
    }

    private static List<GenericStack> readOutputs(CompoundTag data, Level level) {
        List<GenericStack> outputs = new ArrayList<>();
        for (var raw : data.getList("outputs", Tag.TAG_COMPOUND)) {
            CompoundTag amount = (CompoundTag) raw;
            ItemSpec spec = ItemSpec.load(Reader.of(amount.getCompound("spec")), level.registryAccess()).orElseThrow();
            outputs.add(new GenericStack(AEItemKey.of(Goods.display(spec)), amount.getLong("count")));
        }
        return List.copyOf(outputs);
    }

    String reference() {
        return definition.get(DataComponents.CUSTOM_DATA).copyTag().getString("recipe");
    }

    static boolean isColonyPattern(IPatternDetails pattern) {
        return pattern.getDefinition().is(FolkwaysItems.NETWORK_PATTERN.get());
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs.toArray(IInput[]::new);
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    private record Input(ItemSpec spec, long getMultiplier, List<GenericStack> choices) implements IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return choices.toArray(GenericStack[]::new);
        }

        @Override
        public boolean isValid(AEKey key, Level level) {
            return key instanceof AEItemKey item && Goods.matches(item.toStack(), spec);
        }

        @Override
        public AEKey getRemainingKey(AEKey key) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColonyPattern pattern && definition.equals(pattern.definition);
    }

    @Override
    public int hashCode() {
        return definition.hashCode();
    }
}

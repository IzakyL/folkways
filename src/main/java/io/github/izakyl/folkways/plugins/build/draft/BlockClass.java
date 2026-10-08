package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

record BlockClass(String written, Predicate<BlockState> test) {

    static BlockClass parse(String written) throws EvalException {
        List<Predicate<BlockState>> any = new ArrayList<>();
        for (String alternative : written.split("\\|")) {
            any.add(one(alternative.trim()));
        }
        return new BlockClass(written, state -> {
            for (Predicate<BlockState> each : any) {
                if (each.test(state)) {
                    return true;
                }
            }
            return false;
        });
    }

    boolean matches(BlockState state) {
        return test.test(state);
    }

    private static Predicate<BlockState> one(String written) throws EvalException {
        if (written.startsWith("!")) {
            return one(written.substring(1).trim()).negate();
        }
        String name = written.toLowerCase(Locale.ROOT);
        switch (name) {
            case "air":
                return BlockState::isAir;
            case "fluid":
                return BlockClass::fluid;
            case "foliage":
                return BlockClass::foliage;
            case "replaceable":
                return BlockClass::replaceable;
            case "solid":
                return BlockClass::solid;
            case "ground":
                return state -> solid(state) && !foliage(state);
            default:
                break;
        }
        if (name.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(name.substring(1));
            if (id == null) {
                throw Starlark.errorf("'%s' is not a block tag", written);
            }
            TagKey<net.minecraft.world.level.block.Block> tag = TagKey.create(Registries.BLOCK, id);
            return state -> state.is(tag);
        }
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !name.contains(":") || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw Starlark.errorf("'%s' is not air, fluid, foliage, replaceable, solid, ground, a #tag or a"
                + " block id", written);
        }
        net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(id);
        return state -> state.is(block);
    }

    private static boolean fluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    private static boolean foliage(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
    }

    private static boolean replaceable(BlockState state) {
        return !state.isAir() && !fluid(state) && state.canBeReplaced();
    }

    private static boolean solid(BlockState state) {
        return !state.isAir() && !fluid(state) && !state.canBeReplaced();
    }
}

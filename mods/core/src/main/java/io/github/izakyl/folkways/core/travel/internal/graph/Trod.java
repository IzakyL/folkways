package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.world.api.Realm;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.resources.ResourceLocation;

record Trod(ResourceLocation kind, Realm realm, LongList along) {
}

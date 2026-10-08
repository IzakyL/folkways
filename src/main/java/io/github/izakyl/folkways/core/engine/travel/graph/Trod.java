package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Realm;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.resources.ResourceLocation;

record Trod(ResourceLocation kind, Realm realm, LongList along) {
}

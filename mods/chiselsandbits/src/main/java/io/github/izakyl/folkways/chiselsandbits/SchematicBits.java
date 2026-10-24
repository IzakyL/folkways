package io.github.izakyl.folkways.chiselsandbits;

import java.util.Map;
import mod.chiselsandbits.api.blockinformation.BlockInformation;

/** Implemented on detached schematic entities by the integration's loading bridge. */
public interface SchematicBits {
    boolean folkways$valid();

    Map<BlockInformation, Integer> folkways$counts();
}

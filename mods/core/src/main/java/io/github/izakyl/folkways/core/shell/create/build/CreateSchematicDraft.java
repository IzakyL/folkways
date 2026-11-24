package io.github.izakyl.folkways.core.shell.create.build;

import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import com.simibubi.create.content.schematics.client.SchematicTransformation;
import com.simibubi.create.foundation.utility.CreatePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

public record CreateSchematicDraft(String name, byte[] bytes, BlockPos anchor, StructurePlaceSettings settings) {
    public static Optional<CreateSchematicDraft> read(Predicate<byte[]> fits) {
        SchematicHandler handler = CreateClient.SCHEMATIC_HANDLER;
        String name = handler.getCurrentSchematicName();
        Optional<byte[]> file = read(CreatePaths.SCHEMATICS_DIR.resolve(name), fits);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        SchematicTransformation transformation = handler.getTransformation();
        StructurePlaceSettings settings = transformation.toSettings();
        var bounds = handler.getBounds();
        BlockPos size = BlockPos.containing(bounds.getXsize(), bounds.getYsize(), bounds.getZsize());
        BlockPos anchor = CreateSchematicPlacement.corner(transformation.getAnchor(), size, settings);
        return Optional.of(new CreateSchematicDraft(name, file.get(), anchor, settings));
    }

    private static Optional<byte[]> read(Path path, Predicate<byte[]> fits) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return fits.test(bytes) ? Optional.of(bytes) : Optional.empty();
        } catch (IOException | RuntimeException missing) {
            return Optional.empty();
        }
    }
}

package io.github.izakyl.folkways.tacz.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EntityKineticBullet.class, remap = false)
public interface KineticBulletAccessor {
    @Accessor("explosion") boolean folkways$explosive();
    @Accessor("igniteEntity") boolean folkways$ignitesEntities();
    @Accessor("igniteBlock") boolean folkways$ignitesBlocks();
}

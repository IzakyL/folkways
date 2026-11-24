package io.github.izakyl.folkways.core.shell.create.actuator.mixin;

import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LinearActuatorBlockEntity.class)
public interface LinearActuatorRead {

    @Invoker("getExtensionRange")
    int folkways$extensionRange();
}

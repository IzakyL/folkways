package io.github.izakyl.folkways.core.shell.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import com.jusipat.astikorcartsredux.entity.AbstractDrawnEntity;
import com.jusipat.astikorcartsredux.entity.PlowEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.RoutineCards;
import io.github.izakyl.folkways.core.facility.api.RuntimeFacilities;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.shell.carts.api.CartCargo;
import io.github.izakyl.folkways.core.shell.carts.api.CartContents;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class CartIntegration {
    private CartIntegration() {
    }

    public static void install() {
        RuntimeFacilities.register(CartIntegration::facility);
        Controls.register(ResourceLocation.parse("folkways_core:carts/drive"), Controls.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(AbstractDrawnEntity.class, new AABB(cell.block(level)),
                    cart -> WorldSpaces.at(cart).equals(cell)).stream().<Signal>map(CartDrive::new).toList());
        Ports.register(ResourceKind.ITEM, ResourceLocation.parse("folkways_core:carts/cargo"), Ports.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(AbstractCargoCart.class, new AABB(cell.block(level)),
                    cart -> WorldSpaces.at(cart).equals(cell)).stream()
                    .<Port<ItemStack>>map(CartCargo::new).toList());
        Controls.register(ResourceLocation.parse("folkways_core:carts/plowing"), Controls.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(PlowEntity.class, new AABB(cell.block(level)),
                    plow -> WorldSpaces.at(plow).equals(cell)).stream().<Signal>map(CartPlowing::new).toList());
        Ports.register(ResourceKind.ITEM, ResourceLocation.parse("folkways_core:carts/tools"), Ports.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(PlowEntity.class, new AABB(cell.block(level)),
                    plow -> WorldSpaces.at(plow).equals(cell)).stream().<Port<ItemStack>>map(CartTools::new).toList());
        NeoForge.EVENT_BUS.addListener(CartIntegration::stockTick);
    }

    private static Optional<RuntimeFacilities.RuntimeFacility> facility(Colony colony, Entity entity) {
        if (!(entity instanceof AbstractDrawnEntity cart)) {
            return Optional.empty();
        }
        if (!belongsTo(colony, cart)) {
            return Optional.empty();
        }
        var puller = cart.getPulling();
        CartDrive drive = new CartDrive(cart);
        Map<String, Control> controls = new LinkedHashMap<>();
        controls.put("destination", drive);
        if (cart instanceof AbstractCargoCart cargo) {
            controls.put("cargo", new CartCargo(cargo));
        } else if (cart instanceof PlowEntity plow) {
            controls.put("plowing", new CartPlowing(plow));
            controls.put("tools", new CartTools(plow));
        }
        List<String> cargoPorts = controls.containsKey("cargo") ? List.of("cargo") : List.of();
        List<Stances.Seat> seats = puller instanceof AbstractHorse
            ? List.of(new Stances.Seat(puller.getUUID(), 0), new Stances.Seat(cart.getUUID(), 0))
            : List.of(new Stances.Seat(cart.getUUID(), 0));
        return Optional.of(new RuntimeFacilities.RuntimeFacility(
            RoutineCards.destinationAndDeposit(cart.getUUID(), "cart_" + cart.getUUID(), drive.anchor(), controls,
                "destination", cargoPorts, List.of(cart.getUUID())), cart.getDisplayName(), true, seats, Optional.empty()));
    }

    public static boolean belongsTo(Colony colony, AbstractDrawnEntity cart) {
        var puller = cart.getPulling();
        return colony.claimOf(cart.getUUID()).isPresent() || puller != null
            && (Bodies.of(puller).flatMap(body -> body.colonyId()).filter(colony.id()::equals).isPresent()
                || colony.claimOf(puller.getUUID()).isPresent());
    }

    public static void stockTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof AbstractCargoCart cart && !cart.level().isClientSide()
                && cart.tickCount % 5 == 0) {
            // Some container callers grow a slot stack in place and only call setChanged afterwards.
            CartContents.synchronize(cart);
        }
    }
}

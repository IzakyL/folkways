package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Controllers supply access to member stores, never a second inventory of their goods. */
public final class ProxyPort extends CellPort {

    private final List<WorldPos> members;
    private final Set<Operation> operations;

    public ProxyPort(WorldPos home, List<WorldPos> members, boolean connected) {
        this(home, members, connected, Set.of(Operation.TAKE, Operation.PUT));
    }

    public ProxyPort(WorldPos home, List<WorldPos> members, boolean connected, Set<Operation> operations) {
        super(PortIdentity.block(home), List.of(),
            () -> connected ? Usability.USABLE : Verdicts.because("disconnected"), false);
        this.members = members.stream().distinct().toList();
        this.operations = Set.copyOf(operations);
    }

    @Override
    public Set<Operation> operations() {
        return operations;
    }

    @Override
    public Optional<List<WorldPos>> proxyMembers() {
        return Optional.of(members);
    }
}

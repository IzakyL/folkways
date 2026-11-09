
public final class Task {

    public static Object run(Context ctx) throws Exception {
        var level = ctx.server().overworld();
        var report = new ArrayList<Object>();
        int x = 0;
        for (String name : new String[] {"torii", "rotunda"}) {
            var pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath("folkways_carving", name))
                .orElseThrow();
            var defaults = new CompoundTag();
            for (var setting : pattern.knobs().settings()) {
                if (setting instanceof Schema.Setting.Items items) {
                    defaults.put(items.key(), ItemSpec.of(items.byDefault().getFirst().id()).save());
                }
            }
            var anchor = new BlockPos(x, 1, 0);
            var commission = new Commission(new Hint.Zone(anchor, anchor.offset(name.equals("torii") ? 8 : 7, 15,
                name.equals("torii") ? 2 : 7)),
                Chosen.load(pattern.knobs(), defaults), World.NONE, Direction.NORTH, 7);
            var drawn = Drafting.draw(pattern, commission).join();
            if (!(drawn instanceof Drawn.Ready ready)) {
                throw new IllegalStateException(name + ": " + drawn);
            }
            for (Draft.Cell cell : ready.draft().cells()) {
                var pos = ready.corner().offset(cell.offset());
                level.setBlock(pos, cell.state(), 2);
                if (cell.payload().present()) {
                    var entity = StructureCompat.of(cell.state()).orElseThrow()
                        .detached(level.registryAccess(), pos, cell.state(), cell.payload());
                    level.setBlockEntity(entity);
                    entity.setChanged();
                    level.sendBlockUpdated(pos, cell.state(), cell.state(), 3);
                }
            }
            report.add(Map.of("name", name, "corner", ready.corner().toShortString(),
                "extent", ready.draft().extent().size().toString(), "cells", ready.draft().cells().size()));
            x += 24;
        }
        return Sync.stamp(Map.of("ok", true, "patterns", report));
    }
}

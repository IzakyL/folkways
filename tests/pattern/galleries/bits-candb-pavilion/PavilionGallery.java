
public final class Task {
    public static Object run(Context ctx) throws Exception {
        return Sync.stamp(build(ctx));
    }

    private static Object build(Context ctx) throws Exception {
        var level = ctx.server().overworld();
        var id = ResourceLocation.parse("folkways_carving:pavilion");
        var pattern = Pattern.parse(id, Args.of(ctx).string("source")).orElseThrow();
        if (Patterns.find(id).isEmpty()) {
            throw new AssertionError("integration namespace was not loaded");
        }
        var hint = new Hint.Zone(new BlockPos(-4, 1, -4), new BlockPos(4, 1, 4));
        CompoundTag defaults = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                defaults.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
        var commission = new Commission(hint, Chosen.load(pattern.knobs(), defaults),
            World.NONE, Direction.NORTH, 73);
        long began = System.nanoTime();
        var drawn = Drafting.draw(pattern, commission).join();
        long millis = (System.nanoTime() - began) / 1_000_000;
        if (!(drawn instanceof Drawn.Ready ready)) {
            throw new AssertionError("pavilion refused: " + drawn);
        }
        var draft = ready.draft();
        long partial = 0;
        long payloadBytes = 0;
        long voxels = 0;
        var unique = new HashSet<CompoundTag>();
        int resolution = Voxelizer.installed().orElseThrow().resolution();
        try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
            for (var cell : draft.cells()) {
                var pos = ready.corner().offset(cell.offset());
                level.setBlock(pos, cell.state(), 3);
                if (cell.payload().present()) {
                    partial++;
                    payloadBytes += cell.payload().bytes();
                    unique.add(cell.payload().tag());
                    voxels += CandBStructureCodec.validated(cell.payload().tag()).counts().values().stream()
                        .mapToLong(Integer::longValue).sum();
                    var entity = StructureCompat.of(cell.state()).orElseThrow()
                        .detached(level.registryAccess(), pos, cell.state(), cell.payload());
                    level.setBlockEntity(entity);
                    entity.setChanged();
                    level.sendBlockUpdated(pos, cell.state(), cell.state(), 3);
                } else {
                    voxels += (long) resolution * resolution * resolution;
                }
            }
        }
        var compressed = new ByteArrayOutputStream();
        NbtIo.writeCompressed(DraftTemplate.of(draft, 3955), compressed);
        var report = new LinkedHashMap<String, Object>();
        report.put("ok", true);
        report.put("extent", draft.extent().toString());
        report.put("partial", partial);
        report.put("whole", draft.cells().size() - partial);
        report.put("voxels", voxels);
        report.put("equivalentBlocks", (double) voxels / (resolution * resolution * resolution));
        report.put("drawMillis", millis);
        report.put("payloadBytes", payloadBytes);
        report.put("gzipBytes", compressed.size());
        report.put("uniquePayloads", unique.size());
        report.put("uniqueRatio", (double) unique.size() / partial);
        return report;
    }
}

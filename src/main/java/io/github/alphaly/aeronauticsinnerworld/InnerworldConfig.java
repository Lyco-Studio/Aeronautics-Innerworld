package io.github.alphaly.aeronauticsinnerworld;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class InnerworldConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue OVERWORLD_BOUNDARY;
    public static final ModConfigSpec.IntValue MAX_TRANSFER_CHUNKS;

    static {
        var builder = new ModConfigSpec.Builder();
        OVERWORLD_BOUNDARY = builder.comment("Lowest occupied drill-head block Y that opens the mantle connection.")
                .defineInRange("overworldBoundaryY", -58, -2032, 2032);
        MAX_TRANSFER_CHUNKS = builder.comment("Maximum destination chunks occupied by a head during layer transfer.")
                .defineInRange("maxTransferChunks", 9, 1, 64);
        SPEC = builder.build();
    }

    private InnerworldConfig() {
    }
}

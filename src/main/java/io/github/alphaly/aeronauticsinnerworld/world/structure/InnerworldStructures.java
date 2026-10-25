package io.github.alphaly.aeronauticsinnerworld.world.structure;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class InnerworldStructures {
    private static final DeferredRegister<StructureType<?>> TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE,
            AeronauticsInnerworld.MOD_ID);
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE,
            AeronauticsInnerworld.MOD_ID);
    public static final DeferredHolder<StructureType<?>, StructureType<MantleRelayStructure>> MANTLE_RELAY =
            TYPES.register("mantle_relay", () -> () -> MantleRelayStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> RELAY_PIECE = PIECES.register("mantle_relay",
            () -> (StructurePieceType.ContextlessType) MantleRelayPiece::new);
    public static final DeferredHolder<StructureType<?>, StructureType<DeepWorksStructure>> DEEP_WORKS =
            TYPES.register("deep_works", () -> () -> DeepWorksStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> DEEP_WORKS_PIECE = PIECES.register("deep_works",
            () -> (StructurePieceType.ContextlessType) DeepWorksPiece::new);
    public static final DeferredHolder<StructureType<?>, StructureType<CoreMazeStructure>> CORE_MAZE =
            TYPES.register("core_maze", () -> () -> CoreMazeStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> CORE_MAZE_PIECE = PIECES.register("core_maze",
            () -> (StructurePieceType.ContextlessType) CoreMazePiece::new);

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        PIECES.register(bus);
    }

    private InnerworldStructures() {
    }
}

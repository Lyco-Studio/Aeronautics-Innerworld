package io.github.alphaly.aeronauticsinnerworld.integration;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

final class CoreDiskFixture {
    private static final BlockPos ORIGIN = new BlockPos(2048, 32, 2048);
    private static final TicketType<BlockPos> TICKET = TicketType.create("innerworld_core_lifecycle",
            Comparator.comparingLong(BlockPos::asLong), 40);
    private static State state;
    private static int mode;
    private static boolean ready;
    private static boolean built;

    static void start(MinecraftServer server, int stage) {
        mode = stage;ready = false;built = false;
        state = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(State::new, State::load),
                "innerworld_core_lifecycle_check");
    }

    static boolean ready() {return ready;}

    static void tick(MinecraftServer server) {
        var level = server.getLevel(Core.LEVEL);
        require(level != null, "Core lifecycle dimension missing");
        level.resetEmptyTime();
        var lease = state.console == null ? ORIGIN : state.console;
        level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(lease), 4, ORIGIN);
        level.getChunkAt(lease);
        if (ready) return;
        if (mode == 0 && !built && level.isPositionEntityTicking(ORIGIN)) {
            var piece = new CoreMazePiece(ORIGIN, Direction.NORTH, 4271251);
            var box = piece.getBoundingBox();var generator = level.getChunkSource().getGenerator();
            piece.postProcess(level, level.structureManager(), generator, RandomSource.create(31), box, new ChunkPos(ORIGIN), ORIGIN);
            for (var pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.minY() + 1, box.maxZ())) {
                if (level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity) state.console = pos.immutable();
            }
            require(state.console != null, "Disk fixture has no maintenance console");
            built = true;
        }
        if (state.console == null || !(level.getBlockEntity(state.console) instanceof CoreConsoleBlockEntity console)) return;
        if (mode == 0) {
            if (console.getBossId() == null) {
                var config = console.saveWithFullMetadata(level.registryAccess());
                for (long packed : config.getLongArray("Nodes")) {
                    var pos = BlockPos.of(packed);level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(LeverBlock.POWERED, true));
                }
                console.interact(FakePlayerFactory.getMinecraft(level));
            }
            if (console.getBossId() != null && level.getEntity(console.getBossId()) instanceof CoreWarden warden) {
                warden.setNoAi(true);warden.setHealth(123);warden.exposeCore(120);
                state.boss = warden.getUUID();state.setDirty();ready = true;
            }
        } else if (mode == 10) {
            if (level.getEntity(state.boss) instanceof CoreWarden warden) {
                require(warden.getHealth() == 123 && warden.isNoAi(), "Disk restart changed Warden combat state");
                require(state.boss.equals(console.getBossId()) && !console.hasVictory(), "Disk restart changed active console encounter");
                var data = console.saveWithFullMetadata(level.registryAccess());
                for (long packed : data.getLongArray("Nodes")) {
                    var pos = BlockPos.of(packed);require(level.getBlockState(pos).getValue(LeverBlock.POWERED), "Disk restart lost a maintenance node");
                }
                var center = warden.getBoundingBox().inflate(24);
                require(level.getEntitiesOfClass(CoreWarden.class, center).stream().filter(CoreWarden::isAlive).count() == 1,
                        "Disk restart duplicated the Warden");
                warden.hurt(level.damageSources().genericKill(), 10000);
                require(console.hasVictory() && console.getBossId() == null, "Disk encounter did not finish");ready = true;
            }
        } else if (mode == 20) {
            require(console.hasVictory() && console.getBossId() == null, "Second disk restart lost victory state");
            require(level.getEntity(state.boss) == null || !level.getEntity(state.boss).isAlive(), "Victory restart restored a living Warden");
            ready = true;
        }
    }

    private static void require(boolean condition, String message) {if (!condition) throw new IllegalStateException(message);}

    private static final class State extends SavedData {
        private BlockPos console;
        private UUID boss;

        private static State load(CompoundTag tag, HolderLookup.Provider registries) {
            var state = new State();state.console = tag.contains("Console") ? BlockPos.of(tag.getLong("Console")) : null;
            state.boss = tag.hasUUID("Boss") ? tag.getUUID("Boss") : null;return state;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (console != null) tag.putLong("Console", console.asLong());if (boss != null) tag.putUUID("Boss", boss);return tag;
        }
    }
}

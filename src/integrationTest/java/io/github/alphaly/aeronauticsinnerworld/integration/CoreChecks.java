package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import io.github.alphaly.aeronauticsinnerworld.world.structure.InnerworldStructures;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreChecks {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void connectsMazeNodesAndPersistsWardenVictoryAndRematch(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Core.LEVEL);
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(
                ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_maze"));
        var source = level.getChunkSource();var generator = source.getGenerator();var chunk = new ChunkPos(144, 144);
        var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), source.randomState(),
                level.getServer().getStructureManager(), level.getSeed(), chunk, 0, level, biome -> true);
        helper.assertTrue(start.isValid(), "Core labyrinth failed native generation");
        var box = start.getBoundingBox();
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
            level.getChunk(x, z);level.setChunkForced(x, z, true);
        }
        start.placeInChunk(level, level.structureManager(), generator, RandomSource.create(77), box, chunk);
        var piece = (CoreMazePiece) start.getPieces().getFirst();
        CoreConsoleBlockEntity control = null;
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY() + 1, box.minZ(), box.maxX(), box.minY() + 1, box.maxZ())) {
            if (level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity console) control = console;
        }
        helper.assertTrue(control != null, "Labyrinth has no maintenance console");
        final var console = control;
        var config = console.saveWithFullMetadata(level.registryAccess());
        var nodes = config.getLongArray("Nodes");var gates = config.getLongArray("Gates");var valves = config.getLongArray("Valves");
        var reached = new HashSet<BlockPos>();var queue = new ArrayDeque<BlockPos>();
        queue.add(piece.getEntrance());reached.add(piece.getEntrance());
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            for (var direction : Direction.Plane.HORIZONTAL) {
                var next = pos.relative(direction);
                if (box.isInside(next) && !reached.contains(next) && level.getBlockState(next).getCollisionShape(level, next).isEmpty()
                        && level.getBlockState(next.above()).getCollisionShape(level, next.above()).isEmpty()
                        && level.getBlockState(next.below()).isFaceSturdy(level, next.below(), Direction.UP)) {
                    reached.add(next);queue.add(next);
                }
            }
        }
        helper.assertTrue(nodes.length == 3 && gates.length == 9 && valves.length == 2, "Missing labyrinth controls");
        for (long packed : nodes) helper.assertTrue(reached.contains(BlockPos.of(packed)), "A maintenance node is unreachable on foot");
        helper.assertTrue(Direction.Plane.HORIZONTAL.stream().anyMatch(direction -> reached.contains(console.getBlockPos().relative(direction))),
                "Console has no walkable approach");
        var serialization = StructurePieceSerializationContext.fromLevel(level);
        var savedPiece = piece.createTag(serialization);
        var loadedPiece = InnerworldStructures.CORE_MAZE_PIECE.get().load(serialization, savedPiece);
        helper.assertTrue(loadedPiece.createTag(serialization).getLong("LayoutSeed") == savedPiece.getLong("LayoutSeed")
                && loadedPiece.getBoundingBox().equals(box), "Labyrinth seed or orientation changed on reload");
        var message = new String[1];
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("196eea49-e158-4798-abbb-d06853498c4f"), "CoreEngineer")) {
            @Override
            public void displayClientMessage(net.minecraft.network.chat.Component text, boolean actionBar) {message[0] = text.toString();}
        };
        player.getAbilities().instabuild = false;
        console.interact(player);
        helper.assertTrue(console.getBossId() == null, "Console started the encounter before maintenance");
        for (long packed : nodes) {
            var pos = BlockPos.of(packed);level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(LeverBlock.POWERED, true));
        }
        console.interact(player);
        helper.assertTrue(console.getBossId() != null,
                "Restored circuit failed to spawn its Warden: " + message[0] + "; arena=" + BlockPos.of(config.getLong("Arena")));
        for (long packed : gates) helper.assertTrue(!level.getBlockState(BlockPos.of(packed)).is(AeronauticsInnerworld.CORE_SEAL.get()),
                "Restored circuit left the core gate sealed");
        var active = new CoreWarden[1];
        var armored = new float[1];
        var valve = BlockPos.of(valves[0]);
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(level.getEntity(console.getBossId()) instanceof CoreWarden,
                "Waiting for the arena entity section to load"))
                .thenExecute(() -> {
                    active[0] = (CoreWarden) level.getEntity(console.getBossId());
                    float health = active[0].getHealth();active[0].hurt(level.damageSources().playerAttack(player), 10);
                    armored[0] = health - active[0].getHealth();
                    level.setBlockAndUpdate(valve, level.getBlockState(valve).setValue(LeverBlock.POWERED, true));
                }).thenExecuteAfter(5, () -> {
            var warden = active[0];
            helper.assertTrue(warden.isCoreExposed() && !level.getBlockState(valve).getValue(LeverBlock.POWERED),
                    "Arena cooling did not expose the core and reset its valve");
            warden.invulnerableTime = 0;healthCheck(helper, warden, player, armored[0]);
            var tag = new CompoundTag();warden.save(tag);
            var copy = (CoreWarden) EntityType.loadEntityRecursive(tag, level, entity -> entity);
            helper.assertTrue(copy != null && copy.getHealth() == warden.getHealth() && copy.getAttackType() == 0,
                    "Warden reload lost combat state or resumed an attack without warning");
            copy.discard();
            warden.invulnerableTime = 0;warden.hurt(level.damageSources().genericKill(), 10000);
            helper.assertTrue(console.hasVictory() && console.getBossId() == null, "Victory did not persist in the console");
            var saved = console.saveWithFullMetadata(level.registryAccess());console.loadWithComponents(saved, level.registryAccess());
            helper.assertTrue(console.hasVictory(), "Reload lost the cleared story circuit");
            console.interact(player);helper.assertTrue(console.getBossId() == null, "Ordinary interaction started a rematch");
            player.setShiftKeyDown(true);console.interact(player);
            helper.assertTrue(console.getBossId() == null, "Unpaid rematch started");
            player.getInventory().setItem(0, new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get(), 4));
            console.interact(player);
            helper.assertTrue(console.getBossId() != null && player.getInventory().getItem(0).isEmpty(),
                    "Rematch failed or consumed an incorrect crystal count");
            var rematch = level.getEntity(console.getBossId());if (rematch != null) rematch.discard();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) level.setChunkForced(x, z, false);
        }).thenSucceed();
    }

    private static void healthCheck(GameTestHelper helper, CoreWarden warden, FakePlayer player, float armored) {
        float health = warden.getHealth();warden.hurt(warden.level().damageSources().playerAttack(player), 10);
        helper.assertTrue(health - warden.getHealth() > armored * 1.5, "Exposed core did not open a meaningful damage window");
    }
}

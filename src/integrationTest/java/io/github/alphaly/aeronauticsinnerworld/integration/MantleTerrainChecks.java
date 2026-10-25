package io.github.alphaly.aeronauticsinnerworld.integration;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Optional;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MantleTerrainChecks {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void generatesSpireWithoutOverwritingFacilitiesOrNeighborChunks(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var chunk = new ChunkPos(-81, -79);
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        var boundary = new HashMap<BlockPos, BlockState>();
        for (var pos : BlockPos.betweenClosed(x - 1, 0, z - 1, x + 16, 200, z + 16)) {
            boolean neighbor = pos.getX() < x || pos.getX() > x + 15 || pos.getZ() < z || pos.getZ() > z + 15;
            if (neighbor || pos.getY() == 0 || pos.getY() == 200) {
                boundary.put(pos.immutable(), level.getBlockState(pos));
            } else {
                level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 2);
            }
        }
        var chestPos = new BlockPos(x + 8, 8, z + 8);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 2);
        ((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        var air = chestPos.above();
        var wall = chestPos.above(2);
        var station = chestPos.below(6);
        level.setBlock(air, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(wall, Blocks.OBSIDIAN.defaultBlockState(), 2);
        level.setBlock(station, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState(), 2);
        var context = new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
                RandomSource.create(713), new BlockPos(x + 15, 1, z + 15), NoneFeatureConfiguration.INSTANCE);
        helper.assertTrue(AeronauticsInnerworld.MANTLE_SPIRE.get().place(context), "Mantle spire placed no terrain");
        int basalt = 0, ore = 0;
        for (var pos : BlockPos.betweenClosed(x, 1, z, x + 15, 199, z + 15)) {
            var state = level.getBlockState(pos);
            if (state.is(Blocks.BASALT)) basalt++;
            if (state.is(AeronauticsInnerworld.HARMONIC_ORE.get())) ore++;
        }
        helper.assertTrue(basalt > 500 && ore > 0, "Spire lacks a navigable rock column or buried mineral");
        boundary.forEach((pos, state) -> helper.assertTrue(level.getBlockState(pos).equals(state),
                "Spire changed its roof, floor or neighboring chunk at " + pos));
        helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
                "Spire overwrote a container or its contents");
        helper.assertTrue(level.getBlockState(air).isAir() && level.getBlockState(wall).is(Blocks.OBSIDIAN)
                        && level.getBlockState(station).is(AeronauticsInnerworld.BORE_STATION.get()),
                "Spire replaced dry space, a solid wall or a bore station");
        helper.succeed();
    }
}

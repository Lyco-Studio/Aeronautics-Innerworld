package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NaturalSpawnChecks {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void naturallySpawnsLavaEcologyAndRejectsSolidObstructions(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var center = new BlockPos(1160, 100, 136);
        var chunk = new ChunkPos(center);
        boolean wasForced = level.getForcedChunks().contains(chunk.toLong());
        level.setChunkForced(chunk.x, chunk.z, true);
        var original = new HashMap<BlockPos, BlockState>();
        for (var pos : BlockPos.betweenClosed(center.offset(-8, -1, -8), center.offset(8, 2, 8))) {
            original.put(pos.immutable(), level.getBlockState(pos));
            level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 2);
        }
        var observer = new FakePlayer(level, new GameProfile(UUID.fromString("a13a12c8-5ed4-46cb-b563-f2d1d6004f24"), "EcologyObserver"));
        observer.setGameMode(GameType.CREATIVE);
        observer.setPos(center.getX() + 64, center.getY(), center.getZ());
        level.addNewPlayer(observer);
        var spawned = new ArrayList<Mob>();
        var cleaned = new boolean[1];
        Runnable cleanUp = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            spawned.forEach(Mob::discard);
            observer.discard();
            original.forEach(level::setBlockAndUpdate);
            if (!wasForced) level.setChunkForced(chunk.x, chunk.z, false);
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanUp.run(); }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenExecuteAfter(2, () -> {
            helper.assertTrue(level.players().contains(observer), "Native spawner has no nearby observer");
            for (var type : List.of(AeronauticsInnerworld.LAVA_STALKER.get(), AeronauticsInnerworld.MANTLE_GRAZER.get())) {
                var creature = type.create(level);
                creature.moveTo(center.getX() + .5, center.getY(), center.getZ() + .5, 0, 0);
                helper.assertTrue(creature.checkSpawnObstruction(level), "Lava habitat rejects " + type);
                level.setBlock(center.east(), Blocks.BASALT.defaultBlockState(), 2);
                helper.assertTrue(!creature.checkSpawnObstruction(level), "Spawn bounds intersect a solid obstacle");
                level.setBlock(center.east(), Blocks.LAVA.defaultBlockState(), 2);
                level.setBlock(center, Blocks.AIR.defaultBlockState(), 2);
                helper.assertTrue(!creature.checkSpawnObstruction(level), "Lava ecology accepts a dry spawn");
                level.setBlock(center, Blocks.LAVA.defaultBlockState(), 2);
                creature.discard();
            }
            level.random.setSeed(7041);
            for (var category : List.of(MobCategory.MONSTER, MobCategory.CREATURE)) {
                int before = spawned.size();
                var eligible = new int[1];
                for (int attempt = 0; attempt < 64 && spawned.size() == before; attempt++) {
                    NaturalSpawner.spawnCategoryForPosition(category, level, level.getChunkAt(center), center,
                            (type, pos, source) -> { eligible[0]++; return true; }, (mob, source) -> spawned.add(mob));
                }
                helper.assertTrue(spawned.size() > before && spawned.subList(before, spawned.size()).stream()
                                .allMatch(mob -> mob instanceof LavaStalker && mob.getType().getCategory() == category),
                        "Native natural-spawn pipeline produced no mantle " + category + "; eligible=" + eligible[0]
                                + ", difficulty=" + level.getDifficulty() + ", observer="
                                + level.getNearestPlayer(center.getX(), center.getY(), center.getZ(), -1, false)
                                + ", biome=" + level.getBiome(center).unwrapKey() + ", candidates="
                                + level.getBiome(center).value().getMobSettings().getMobs(category).unwrap());
            }
        }).thenSucceed();
    }
}

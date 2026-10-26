package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreHeatChecks {
    // Register the observer after the concurrent physics fixtures have finished.
    @GameTest(template = "empty", batch = "core_heat", timeoutTicks = 500)
    public static void warnsBeforeHeatAndAllowsAvoidanceProtectionAndCooling(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Core.LEVEL);
        var origin = new BlockPos(1536, 32, 1536);
        var forced = new HashMap<ChunkPos, Boolean>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var chunk = new ChunkPos((origin.getX() >> 4) + x, (origin.getZ() >> 4) + z);
            forced.put(chunk, level.getForcedChunks().contains(chunk.toLong()));
            level.setChunkForced(chunk.x, chunk.z, true);level.getChunk(chunk.x, chunk.z);
        }
        var hits = new ArrayList<Float>();
        var tagged = new boolean[] {true};
        var heatType = ResourceKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_heat"));
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("0510435e-eeac-46ab-9d08-3f407b6ed63d"), "CoreHeatEngineer")) {
            @Override
            public boolean hurt(DamageSource source, float amount) {
                if (source.is(heatType)) {
                    hits.add(amount);
                    tagged[0] &= source.is(DamageTypeTags.BYPASSES_ARMOR) && source.is(DamageTypeTags.BYPASSES_SHIELD);
                }
                return false;
            }
        };
        var warden = AeronauticsInnerworld.CORE_WARDEN.get().create(level);
        player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(origin.east(4)));
        warden.setPos(Vec3.atBottomCenterOf(origin));warden.setHome(origin, origin);
        helper.assertTrue(level.noCollision(warden) && level.noCollision(player), "Core heat fixture intersects natural structures");
        level.addNewPlayer(player);level.addFreshEntity(warden);warden.setTarget(player);
        var zone = new BlockPos[1];var protectedDamage = new float[1];var hitCount = new int[1];
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanUp(); }
            public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanUp(); }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
            private void cleanUp() {
                warden.discard();player.discard();
                forced.forEach((chunk, wasForced) -> {if (!wasForced) level.setChunkForced(chunk.x, chunk.z, false);});
            }
        });
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenExecuteAfter(110, () -> {
            helper.assertTrue(warden.getHeatZone() == null && hits.isEmpty(), "First phase leaked heat");
            warden.setHealth(120);
        }).thenWaitUntil(() -> helper.assertTrue(warden.getHeatZone() != null, "Waiting for second-phase heat warning"))
                .thenExecute(() -> {
                    zone[0] = warden.getHeatZone();
                    helper.assertTrue(!warden.isHeatActive() && hits.isEmpty(), "Heat struck without its warning window");
                }).thenExecuteAfter(20, () -> {
                    helper.assertTrue(!warden.isHeatActive() && hits.isEmpty(), "Heat warning ended too early");
                    player.setPos(Vec3.atBottomCenterOf(zone[0].north(4)));
                }).thenExecuteAfter(15, () -> {
                    helper.assertTrue(warden.isHeatActive() && hits.isEmpty(), "Leaving the warning ring failed to avoid heat");
                    player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(AeronauticsInnerworld.HEATWEAVE_HELMET.get()));
                    player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.get()));
                    player.setPos(Vec3.atBottomCenterOf(zone[0]));
                }).thenWaitUntil(() -> helper.assertTrue(!hits.isEmpty(), "Waiting for heatweave-protected pulse"))
                .thenExecute(() -> {
                    protectedDamage[0] = hits.getLast();hitCount[0] = hits.size();
                    player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                }).thenWaitUntil(() -> helper.assertTrue(hits.size() > hitCount[0], "Waiting for unprotected pulse"))
                .thenExecute(() -> {
                    helper.assertTrue(protectedDamage[0] > 0 && hits.getLast() > protectedDamage[0] * 2 && tagged[0],
                            "Heatweave did not mitigate thermal damage or the damage tags permit armor/shield blocking");
                    var tag = new CompoundTag();warden.save(tag);
                    var copy = (CoreWarden) EntityType.loadEntityRecursive(tag, level, entity -> entity);
                    helper.assertTrue(copy != null && copy.getHeatZone() == null && !copy.isHeatActive(),
                            "Reload resumed a heat pulse without a new warning");
                    copy.discard();
                    warden.exposeCore(160);hitCount[0] = hits.size();
                    helper.assertTrue(warden.getHeatZone() == null, "Cooling failed to extinguish the heat zone");
                }).thenExecuteAfter(40, () -> helper.assertTrue(hits.size() == hitCount[0] && warden.getHeatZone() == null,
                        "Cooling did not suppress further heat pulses")).thenSucceed();
    }
}

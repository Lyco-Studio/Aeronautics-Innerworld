package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EcologyChecks {
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void samplesNeutralGrazersWithoutLosingTheirRecovery(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(640, 180, 128));
        rig.workEquipment();
        var grazer = new LavaStalker[1];
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) { }
            public void testFailed(GameTestInfo test, GameTestRunner runner) {
                if (grazer[0] != null) grazer[0].discard();
                rig.cleanUp();
            }
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            if (grazer[0] != null && grazer[0].isAlive()) {
                var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                double distance = level.getGameTime() % 16 < 8 ? 2 : 3;
                grazer[0].setPos(rig.world(ProbeTestRig.SAMPLER).add(direction.scale(distance)).add(0, -.5, 0));
                grazer[0].setDeltaMovement(Vec3.ZERO);
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for ecological sampling rig"))
                .thenExecute(() -> {
                    rig.assemble();
                    rig.power();
                    rig.motor(ProbeTestRig.SAMPLER, 32);
                    for (int slot = 0; slot < 9; slot++) {
                        rig.sampler().inventory.setStackInSlot(slot, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
                    }
                }).thenExecuteAfter(35, () -> {
                    var position = rig.creatureSamplingTarget();
                    grazer[0] = AeronauticsInnerworld.MANTLE_GRAZER.get().create(level);
                    grazer[0].setPos(position);
                    grazer[0].setNoGravity(true);
                    level.addFreshEntity(grazer[0]);
                    helper.assertTrue(grazer[0].isNeutral(), "Grazer was registered with hostile behavior");
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(grazer[0].getSampleCooldown() == 0 && grazer[0].getHealth() == 40,
                            "Full cargo consumed a living sample or injured its source");
                    helper.assertTrue(grazer[0].getTarget() == null, "Unprovoked grazing creature acquired an attack target");
                    rig.sampler().inventory.extractItem(0, 64, false);
                }).thenExecuteAfter(25, () -> {
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).is(AeronauticsInnerworld.RAW_HARMONIC.get())
                            && rig.sampler().inventory.getStackInSlot(0).getCount() == 1,
                            "Gentle sampling failed to preserve exactly one mineral sample: status=" + rig.sampler().getStatus()
                                    + ", specimen=" + grazer[0].position() + ", sampler=" + rig.world(ProbeTestRig.SAMPLER)
                                    + ", target=" + grazer[0].getTarget() + ", cooldown=" + grazer[0].getSampleCooldown());
                    helper.assertTrue(grazer[0].getHealth() == 40 && grazer[0].getTarget() == null
                            && grazer[0].getSampleCooldown() > 1100, "Gentle sampling harmed or depleted the creature incorrectly");
                    var saved = new CompoundTag();
                    grazer[0].save(saved);
                    var restored = EntityType.loadEntityRecursive(saved, level, entity -> entity);
                    helper.assertTrue(restored instanceof LavaStalker copy && copy.isNeutral()
                            && copy.getSampleCooldown() == grazer[0].getSampleCooldown() && copy.takeSample().isEmpty(),
                            "Entity save/reload bypassed the creature sampling cooldown");
                    if (restored != null) {
                        restored.discard();
                    }
                    saved.putInt("SampleCooldown", 1);
                    grazer[0].load(saved);
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).getCount() == 2,
                            "Sampling did not resume after the creature recovered");
                    var player = new FakePlayer(level, new GameProfile(UUID.fromString("5926a94f-b359-4a9e-bcee-a53967c395e0"), "Ecologist"));
                    player.getAbilities().instabuild = false;
                    helper.assertTrue(!grazer[0].isPreventingPlayerRest(player), "Calm grazer was treated as a hostile encounter");
                    grazer[0].hurt(level.damageSources().playerAttack(player), 1);
                    helper.assertTrue(grazer[0].getTarget() == player && grazer[0].isPreventingPlayerRest(player),
                            "Provoked neutral creature did not retaliate");
                    var recipe = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(
                            AeronauticsInnerworld.MOD_ID, "relic_alloy_recasting")).orElseThrow().value();
                    helper.assertTrue(recipe instanceof MixingRecipe, "Alloy recasting is not a native Create mixing recipe");
                    var output = recipe.getResultItem(level.registryAccess());
                    helper.assertTrue(output.is(AeronauticsInnerworld.RELIC_ALLOY.get()) && output.getCount() == 2,
                            "Heated recasting does not produce the intended alloy yield");
                    grazer[0].discard();
                    rig.cleanUp();
                }).thenSucceed();
    }
}

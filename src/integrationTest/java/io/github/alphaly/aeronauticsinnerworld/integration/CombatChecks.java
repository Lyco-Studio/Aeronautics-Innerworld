package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import io.github.alphaly.aeronauticsinnerworld.content.combat.PressureBoltEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CombatChecks {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void fightsAndRepairsMantlePredators(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(512, 180, 128));
        rig.combatEquipment();
        java.util.stream.StreamSupport.stream(level.getAllEntities().spliterator(), false)
                .filter(entity -> entity.getTags().contains("innerworld_combat_check")).toList()
                .forEach(net.minecraft.world.entity.Entity::discard);
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("5c0a876c-df54-46f9-9f33-d6fdf9cbb0d6"), "ProbeGunner"));
        player.getAbilities().instabuild = false;
        var predator = new LavaStalker[1];
        boolean[] telegraphed = {false};
        boolean[] trackShots = {false};
        int[] remainingAmmo = {0};
        var shots = new java.util.HashSet<UUID>();
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo test) {
            }

            @Override
            public void testPassed(GameTestInfo test, GameTestRunner runner) {
            }

            @Override
            public void testFailed(GameTestInfo test, GameTestRunner runner) {
                if (predator[0] != null) {
                    predator[0].discard();
                }
                rig.cleanUp();
            }

            @Override
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {
            }
        });
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            if (rig.subLevel != null) {
                var feet = rig.world(new BlockPos(2, 1, 2)).add(0, -.45, 0);
                player.setPos(feet);
                if (predator[0] != null && predator[0].isAlive() && predator[0].isNoAi()) {
                    var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                    predator[0].setPos(rig.world(ProbeTestRig.CANNON).add(direction.scale(8)).add(0, -.35, 0));
                    predator[0].setDeltaMovement(Vec3.ZERO);
                }
            }
            if (predator[0] != null && predator[0].isWindingUp()) {
                telegraphed[0] = true;
            }
            if (trackShots[0]) {
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof PressureBoltEntity) {
                        shots.add(entity.getUUID());
                    }
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for combat probe assembly"))
                .thenExecute(() -> {
                    int loose = rig.sourceDrops(AeronauticsInnerworld.PRESSURE_BOLT.get());
                    rig.assemble();
                    rig.power();
                    rig.motor(ProbeTestRig.CANNON, 0);
                    helper.assertTrue(rig.cannon().ammunition.getStackInSlot(0).getCount() == 9,
                            "Native assembly lost preloaded ammunition");
                    helper.assertTrue(rig.sourceDrops(AeronauticsInnerworld.PRESSURE_BOLT.get()) == loose,
                            "Native assembly duplicated ammunition at its source");
                    rig.cabin().addCoolant(240);
                }).thenExecuteAfter(35, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Combat probe has no sealed cabin");
                    use(player, new ItemStack(AeronauticsInnerworld.PRESSURE_BOLT.get(), 60), rig.local(ProbeTestRig.CANNON));
                    helper.assertTrue(rig.cannon().ammunition.getStackInSlot(0).getCount() == 64
                            && player.getMainHandItem().getCount() == 5, "Reload did not preserve ammunition overflow");
                    var muzzle = rig.world(ProbeTestRig.CANNON);
                    var direction = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, -1));
                    predator[0] = AeronauticsInnerworld.LAVA_STALKER.get().create(level);
                    predator[0].setPos(muzzle.add(direction.scale(8)).add(0, -.35, 0));
                    predator[0].setNoAi(true);
                    predator[0].setNoGravity(true);
                    predator[0].addTag("innerworld_combat_check");
                    level.addFreshEntity(predator[0]);
                    helper.assertTrue(LavaStalker.canSpawn(AeronauticsInnerworld.LAVA_STALKER.get(), level,
                            MobSpawnType.SPAWN_EGG, predator[0].blockPosition(), level.random), "Predator rejects deep mantle lava");
                    trackShots[0] = true;
                    rig.motor(ProbeTestRig.CANNON, 32);
                }).thenWaitUntil(() -> helper.assertTrue(predator[0].isDeadOrDying(),
                        "Cannon has not defeated its target: health=" + predator[0].getHealth()
                                + ", rounds=" + rig.cannon().ammunition.getStackInSlot(0).getCount()
                                + ", speed=" + rig.cannon().getSpeed() + ", target=" + predator[0].position()
                                + ", muzzle=" + rig.world(ProbeTestRig.CANNON)))
                .thenExecute(() -> {
                    rig.motor(ProbeTestRig.CANNON, 0);
                    remainingAmmo[0] = rig.cannon().ammunition.getStackInSlot(0).getCount();
                    helper.assertTrue(shots.size() >= 5 && 64 - remainingAmmo[0] == shots.size(),
                            "Ammunition differs from emitted projectiles: rounds=" + remainingAmmo[0] + ", shots=" + shots.size());
                    predator[0].discard();
                    predator[0] = AeronauticsInnerworld.LAVA_STALKER.get().create(level);
                    predator[0].setPos(rig.world(new BlockPos(0, 1, 2)).add(-1.5, -.35, 0));
                    predator[0].addTag("innerworld_combat_check");
                    level.addFreshEntity(predator[0]);
                    predator[0].setTarget(player);
                }).thenWaitUntil(() -> helper.assertTrue(rig.cabin().getDamagedPlate() != null,
                        "Predator has not damaged the exposed hull"))
                .thenExecute(() -> {
                    helper.assertTrue(telegraphed[0], "Predator damaged the hull without an attack warning");
                    var plate = rig.cabin().getDamagedPlate();
                    helper.assertTrue(level.getBlockState(plate).is(AeronauticsInnerworld.DAMAGED_HULL.get()),
                            "Predator did not produce a repairable local hull crack");
                    predator[0].discard();
                    use(player, new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), 2), plate);
                    helper.assertTrue(level.getBlockState(plate).is(AeronauticsInnerworld.REFRACTORY_HULL.get())
                            && player.getMainHandItem().getCount() == 1, "Enemy hull damage could not be repaired with one kit");
                    helper.assertTrue(rig.cabin().getDamagedPlate() == null, "Repaired hull retained a false damage location");
                    rig.unload();
                    helper.runAfterDelay(2, rig::restore);
                }).thenExecuteAfter(4, () -> {
                    helper.assertTrue(rig.cannon().ammunition.getStackInSlot(0).getCount() == remainingAmmo[0],
                            "Probe reload lost or duplicated its remaining ammunition");
                    int cargo = player.getInventory().countItem(AeronauticsInnerworld.PRESSURE_BOLT.get());
                    player.setShiftKeyDown(true);
                    use(player, ItemStack.EMPTY, rig.local(ProbeTestRig.CANNON));
                    helper.assertTrue(rig.cannon().ammunition.getStackInSlot(0).isEmpty()
                            && player.getInventory().countItem(AeronauticsInnerworld.PRESSURE_BOLT.get()) == cargo + remainingAmmo[0],
                            "Manual ammunition unloading lost or duplicated bolts");
                    rig.cleanUp();
                }).thenSucceed();
    }

    private static void use(FakePlayer player, ItemStack item, BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        player.gameMode.useItemOn(player, player.serverLevel(), item, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}

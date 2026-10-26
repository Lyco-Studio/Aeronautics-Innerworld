package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExpeditionChecks {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void samplesAndRepairsMantleProbe(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(256, 180, 256));
        rig.workEquipment();
        var player = new FakePlayer(level, new GameProfile(UUID.fromString("bdcf9827-a66b-444c-b6de-d9f226f9e50b"), "ProbeMechanic"));
        player.getAbilities().instabuild = false;
        var dockedForSampling = new boolean[1];
        helper.onEachTick(() -> {
            level.resetEmptyTime();
            if (dockedForSampling[0] && rig.subLevel != null) {
                var body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(rig.subLevel);
                if (body.isValid()) body.addLinearAndAngularVelocity(
                        body.getLinearVelocity().mul(-1, new org.joml.Vector3d()),
                        body.getAngularVelocity().mul(-1, new org.joml.Vector3d()));
            }
        });
        var ore = new BlockPos[1];
        var compass = new ItemStack[1];
        var waypoint = new BlockPos[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for probe entity chunks"))
                .thenExecute(() -> {
                    int copperDrops = rig.sourceCopperDrops();
                    rig.assemble();
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).is(Items.COPPER_INGOT)
                            && rig.sampler().inventory.getStackInSlot(0).getCount() == 17,
                            "Native assembly lost or duplicated preloaded sampler cargo");
                    helper.assertTrue(rig.sourceCopperDrops() == copperDrops, "Assembly duplicated cargo into loose source drops");
                    rig.power();
                    rig.motor(ProbeTestRig.SAMPLER, 64);
                    for (int i = 0; i < 9; i++) {
                        rig.sampler().inventory.setStackInSlot(i, new ItemStack(Items.COBBLED_DEEPSLATE, 64));
                    }
                }).thenExecuteAfter(35, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Expedition cabin is not sealed");
                    helper.assertTrue(new BlockPos(256, 180, 256).equals(rig.sounder().getHome()),
                            "Assembly lost home berth: " + rig.sounder().saveWithFullMetadata(level.registryAccess()));
                    helper.assertTrue(Math.abs(rig.sounder().getSpeed()) >= 16,
                            "Fueled Aeronautics portable engine did not power the sounder");
                    checkRecoveryNavigation(helper, rig, player);
                    dockedForSampling[0] = true;
                    ore[0] = rig.mineralTarget();
                }).thenExecuteAfter(15, () -> {
                    helper.assertTrue(level.getBlockState(ore[0]).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Full sampler destroyed terrain without cargo capacity");
                    helper.assertTrue(level.getCapability(Capabilities.ItemHandler.BLOCK, rig.local(ProbeTestRig.SAMPLER), Direction.SOUTH)
                            == rig.sampler().inventory, "Sampler cargo capability is missing");
                    rig.sounder().scan();
                    var target = rig.sounder().getMineral();
                    helper.assertTrue(target != null && level.getBlockState(target).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Sounder did not identify a mineral at its actual world location");
                    var stack = new ItemStack(Items.COMPASS, 3);
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                    var pos = rig.local(ProbeTestRig.SOUNDER);
                    player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                    compass[0] = player.getInventory().items.stream().filter(item -> item.is(Items.COMPASS)
                            && item.has(DataComponents.LODESTONE_TRACKER)).findFirst().orElseThrow().copy();
                    waypoint[0] = rig.sounder().getWaypoint();
                    helper.assertTrue(waypoint[0].equals(target) && stack.getCount() == 2
                                    && !stack.has(DataComponents.LODESTONE_TRACKER) && player.getInventory().countItem(Items.COMPASS) == 3,
                            "Recording a route lost compasses or changed the entire stack");
                    rig.sampler().inventory.extractItem(0, 64, false);
                }).thenExecuteAfter(25, () -> {
                    helper.assertTrue(!level.getBlockState(ore[0]).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                            "Sampler did not excavate target: speed=" + rig.sampler().getSpeed() + ", status="
                                    + rig.sampler().getStatus() + ", target=" + rig.sampler().getTarget()
                                    + ", position=" + rig.world(ProbeTestRig.SAMPLER) + ", ore=" + ore[0]);
                    helper.assertTrue(rig.sampler().inventory.getStackInSlot(0).is(AeronauticsInnerworld.RAW_HARMONIC.get())
                                    && rig.sampler().inventory.getStackInSlot(0).getCount() == 1,
                            "Sample output was lost or duplicated");
                    rig.motor(ProbeTestRig.SAMPLER, 0);
                    dockedForSampling[0] = false;
                    primeHeat(rig, 100, 9);
                }).thenExecuteAfter(25, () -> {
                    boolean faulted = false;
                    for (var actor : rig.subLevel.getPlot().getBlockEntityActors()) {
                        if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) {
                            faulted = true;
                        }
                    }
                    helper.assertTrue(faulted, "Overheat did not disable a drive");
                    helper.assertTrue(rig.cabin().getDamagedPlate() != null
                                    && level.getBlockState(rig.cabin().getDamagedPlate()).is(AeronauticsInnerworld.DAMAGED_HULL.get()),
                            "Overheat did not damage a local hull plate");
                    var uuid = rig.subLevel.getUniqueId();
                    rig.unload();
                    helper.runAfterDelay(2, () -> {
                        rig.restore();
                        helper.assertTrue(rig.subLevel.getUniqueId().equals(uuid), "Expedition reload changed probe identity");
                    });
                }).thenExecuteAfter(4, () -> {
                    helper.assertTrue(new BlockPos(256, 180, 256).equals(rig.sounder().getHome()),
                            "Reload lost home berth: " + rig.sounder().saveWithFullMetadata(level.registryAccess()));
                    helper.assertTrue(waypoint[0].equals(rig.sounder().getWaypoint()), "Reload lost the active navigation target");
                    compass[0].getItem().inventoryTick(compass[0], level, player, 0, true);
                    helper.assertTrue(compass[0].get(DataComponents.LODESTONE_TRACKER).target().orElseThrow().pos().equals(waypoint[0]),
                            "A mined deposit invalidated its route compass");
                    var wrongDimension = new ItemStack(Items.COMPASS);
                    wrongDimension.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(java.util.Optional.of(
                            GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO)), false));
                    rig.sounder().useCompass(player, wrongDimension);
                    helper.assertTrue(waypoint[0].equals(rig.sounder().getWaypoint()), "A foreign dimension replaced the active waypoint");
                    player.setShiftKeyDown(true);
                    rig.sounder().interact(player);
                    player.setShiftKeyDown(false);
                    helper.assertTrue(rig.sounder().getWaypoint() == null && new BlockPos(256, 180, 256).equals(rig.sounder().getHome()),
                            "Canceling navigation changed the return berth");
                    int loaded = level.getChunkSource().getLoadedChunksCount();
                    var distant = new BlockPos(12000, 96, 12000);
                    var distantCompass = new ItemStack(Items.COMPASS);
                    distantCompass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(java.util.Optional.of(
                            GlobalPos.of(Mantle.LEVEL, distant)), false));
                    rig.sounder().useCompass(player, distantCompass);
                    helper.assertTrue(distant.equals(rig.sounder().getWaypoint())
                                    && level.getChunkSource().getLoadedChunksCount() == loaded,
                            "Loading a saved route failed or loaded target chunks");
                    rig.sounder().useCompass(player, compass[0]);
                    var fault = findFault(rig);
                    helper.assertTrue(fault != null, "Reload lost the mechanical fault");
                    use(player, new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), 2), fault.getBlockPos(), Direction.UP);
                    helper.assertTrue(fault.isFaulted() && player.getMainHandItem().getCount() == 2,
                            "Repair bypassed heat lockout or consumed supplies on refusal");
                    use(player, new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(), 2), rig.local(ProbeTestRig.CABIN), Direction.UP);
                    helper.assertTrue(rig.cabin().getCoolant() == 120 && player.getMainHandItem().getCount() == 1,
                            "Coolant interaction consumed an incorrect quantity");
                }).thenExecuteAfter(25, () -> {
                    helper.assertTrue(rig.cabin().getHeat() < 100, "Loaded coolant did not reduce heat");
                    primeHeat(rig, 65, 0);
                    var fault = findFault(rig);
                    use(player, new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), 2), fault.getBlockPos(), Direction.UP);
                    helper.assertTrue(!fault.isFaulted() && player.getMainHandItem().getCount() == 1,
                            "Cooled drive repair failed or consumed an incorrect quantity");
                    var plate = rig.cabin().getDamagedPlate();
                    use(player, new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), 2), plate, Direction.UP);
                    helper.assertTrue(level.getBlockState(plate).is(AeronauticsInnerworld.REFRACTORY_HULL.get())
                            && player.getMainHandItem().getCount() == 1, "Hull repair did not restore the plate with one kit");
                    level.setBlockAndUpdate(rig.local(new BlockPos(0, 1, 2)), Blocks.AIR.defaultBlockState());
                }).thenExecuteAfter(3, () -> {
                    helper.assertTrue(!rig.cabin().isSealed(), "Hull breach retained cabin seal");
                    var below = rig.local(new BlockPos(0, 0, 2));
                    use(player, new ItemStack(AeronauticsInnerworld.EMERGENCY_PATCH.get(), 2), below, Direction.UP);
                    helper.assertTrue(level.getBlockState(below.above()).is(AeronauticsInnerworld.EMERGENCY_PATCH.get()),
                            "Emergency patch did not close the hull breach");
                    helper.assertTrue(player.getMainHandItem().getCount() == 1, "Emergency patch consumption was incorrect");
                }).thenExecuteAfter(15, () -> {
                    helper.assertTrue(rig.cabin().isSealed(), "Patched cabin did not reseal");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    rig.sampler().interact(player);
                    helper.assertTrue(player.getInventory().countItem(AeronauticsInnerworld.RAW_HARMONIC.get()) == 1
                            && rig.sampler().inventory.getStackInSlot(0).isEmpty(), "Cargo unloading lost or duplicated samples");
                    rig.cleanUp();
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void generatesHarmonicDeposits(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        int count = 0;
        for (var pos : BlockPos.betweenClosed(512, 164, 512, 543, 199, 543)) {
            if (level.getBlockState(pos).is(AeronauticsInnerworld.HARMONIC_ORE.get())) {
                count++;
            }
        }
        helper.assertTrue(count > 0, "Natural mantle chunks contain no harmonic deposits");
        var input = new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(AeronauticsInnerworld.RAW_HARMONIC.get()));
        var smelting = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, input, level);
        helper.assertTrue(smelting.isPresent() && smelting.get().value().assemble(input, level.registryAccess())
                        .is(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()), "Harmonic processing recipe is unavailable");
        var crafting = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, java.util.List.of(
                new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()), new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get()),
                new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get())));
        var upgrade = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, crafting, level);
        helper.assertTrue(upgrade.isPresent(), "Recovered crystal cannot improve expedition coolant");
        var result = upgrade.get().value().assemble(crafting, level.registryAccess());
        helper.assertTrue(result.is(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) && result.getCount() == 2,
                "Expedition coolant recipe produces incorrect supplies");
        helper.succeed();
    }

    private static void checkRecoveryNavigation(GameTestHelper helper, ProbeTestRig rig, FakePlayer player) {
        var destination = new BlockPos(290, 163, 264);
        var compass = new ItemStack(Items.RECOVERY_COMPASS);
        compass.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Rescue navigation"));
        var original = compass.copy();
        player.setLastDeathLocation(java.util.Optional.of(GlobalPos.of(Mantle.LEVEL, destination)));
        use(player, compass, rig.local(ProbeTestRig.SOUNDER), Direction.NORTH);
        helper.assertTrue(destination.equals(rig.sounder().getWaypoint())
                        && new BlockPos(256, 180, 256).equals(rig.sounder().getHome())
                        && ItemStack.isSameItemSameComponents(compass, original) && compass.getCount() == 1,
                "Recovery navigation did not use world death coordinates or modified the compass/home berth");
        player.setLastDeathLocation(java.util.Optional.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO)));
        use(player, compass, rig.local(ProbeTestRig.SOUNDER), Direction.NORTH);
        helper.assertTrue(destination.equals(rig.sounder().getWaypoint()), "A foreign death replaced the rescue route");
        player.setLastDeathLocation(java.util.Optional.empty());
        use(player, compass, rig.local(ProbeTestRig.SOUNDER), Direction.NORTH);
        helper.assertTrue(destination.equals(rig.sounder().getWaypoint()), "An empty death history replaced the rescue route");
        player.setLastDeathLocation(java.util.Optional.of(GlobalPos.of(Mantle.LEVEL, BlockPos.ZERO)));
        float speed = rig.sounder().getSpeed();
        rig.sounder().setSpeed(0);
        use(player, compass, rig.local(ProbeTestRig.SOUNDER), Direction.NORTH);
        helper.assertTrue(destination.equals(rig.sounder().getWaypoint()), "An unpowered sounder loaded a rescue route");
        rig.sounder().setSpeed(speed);
        player.setShiftKeyDown(true);rig.sounder().interact(player);player.setShiftKeyDown(false);
    }

    private static void primeHeat(ProbeTestRig rig, float heat, int hotSeconds) {
        var data = rig.cabin().saveWithFullMetadata(rig.level.registryAccess());
        data.putFloat("Heat", heat);
        data.putInt("HotSeconds", hotSeconds);
        rig.cabin().loadWithComponents(data, rig.level.registryAccess());
    }

    private static ProbeDriveBlockEntity findFault(ProbeTestRig rig) {
        for (var actor : rig.subLevel.getPlot().getBlockEntityActors()) {
            if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) {
                return drive;
            }
        }
        return null;
    }

    private static void use(FakePlayer player, ItemStack item, BlockPos pos, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        player.gameMode.useItemOn(player, player.serverLevel(), item, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
    }
}

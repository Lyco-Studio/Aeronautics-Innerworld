package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.MantleSamplerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.util.FakePlayer;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BaseExcavationChecks {
    @GameTest(template = "empty", timeoutTicks = 28000)
    public static void excavatesNaturalBayAndSailsToSeaWithCoal(GameTestHelper helper) {
        var rig = new Rig(helper);
        helper.onEachTick(rig::keepLoaded);
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo test) { }
            @Override public void testPassed(GameTestInfo test, GameTestRunner runner) {rig.cleanUp();}
            @Override public void testFailed(GameTestInfo test, GameTestRunner runner) {rig.cleanUp();}
            @Override public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.startSequence().thenWaitUntil(() -> {
                    helper.assertTrue(Math.abs(rig.controller().getSpeed()) == 32, "Coal engine has not powered the vertical gearbox");
                    helper.assertTrue(rig.source.getEntitiesOfClass(SuperGlueEntity.class,
                            new AABB(rig.origin).inflate(15)).containsAll(rig.glue), "Waiting for drill glue");
                }).thenExecute(() -> {
                    rig.controller().toggleWork();rig.identity = rig.head().getUUID();
                    helper.assertTrue(rig.head().getContraption().getBlocks().size() == 148,
                            "Bay head did not retain its 143 cutters, four barrels and station");
                }).thenWaitUntil(() -> helper.assertTrue(rig.head().level() == rig.mantle,
                        "Fueled head has not crossed at the normal layer boundary: " + rig.controller().getStatus()))
                .thenExecute(() -> {
                    rig.assertHead();
                    helper.assertTrue(rig.head().getY() == 256, "Arrival changed the lowest head height");
                    helper.assertTrue(rig.mantle.getBlockState(rig.origin.atY(255)).isSolid(), "Arrival cleared natural rock");
                    LogUtils.getLogger().info("Coal bay crossed the normal boundary after {} ticks", rig.elapsed());
                    rig.controller().toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(rig.head().getY() <= 202.5,
                        "Head has not entered the roof: " + rig.controller().getStatus() + ", " + rig.head().position()))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> {
                    rig.assertHead();rig.start = rig.head().blockPosition();
                    helper.assertTrue(rig.start.getY() == 202, "Head did not stop at the chosen excavation height");
                    LogUtils.getLogger().info("Coal bay vertical excavation paused after {} ticks", rig.elapsed());
                    helper.assertTrue(rig.controller().setMovementDirection(Direction.EAST), "Paused head could not turn east");
                    rig.controller().toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(rig.head().getX() >= rig.start.getX() + 23.5,
                        "Head has not excavated the full bay: " + rig.controller().getStatus() + ", " + rig.head().position()))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> rig.resume(Direction.DOWN))
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() <= 199.5, "Launch well has not reached the lava sea"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> rig.resume(Direction.EAST))
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getX() >= rig.start.getX() + 31.5, "Launch well is not long enough"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> rig.resume(Direction.UP))
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() >= 201.5, "Head has not returned to bay height"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> rig.resume(Direction.WEST))
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getX() <= rig.start.getX() + .5, "Head has not retreated to the supply end"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(rig::inspectAndPlace)
                .thenWaitUntil(() -> helper.assertTrue(rig.targetStation().getPartner() != null, "Excavated station has not connected"))
                .thenExecute(rig::prepareProbe)
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.ready(), "Waiting for probe assembly glue"))
                .thenExecute(() -> rig.probe.assemble())
                .thenExecuteAfter(40, () -> {
                    helper.assertTrue(rig.probe.cabin().isSealed(), "Probe did not retain a sealed cabin inside the excavated bay");
                    helper.assertTrue(Math.abs(rig.probe.sounder().getSpeed()) == 32, "Assembled probe lacks coal power");
                    helper.assertTrue(rig.floor.equals(rig.mantle.getBlockState(rig.floorPos))
                                    && rig.ceiling.equals(rig.mantle.getBlockState(rig.ceilingPos)),
                            "Excavation or assembly damaged the bay floor or ceiling");
                    LogUtils.getLogger().info("Coal-powered natural bay assembly ready: size=24x11x11, sea well=11x11, mined={}, fuel={}, ticks={}, probe={}",
                            rig.mined, rig.engine().inventory.getItem(0).getCount(), rig.elapsed(), rig.probe.subLevel.getName());
                    helper.assertTrue(rig.builder.getInventory().countItem(Items.COPPER_INGOT) == 17,
                            "Base builder could not recover the carried supplies");
                    rig.openDock();
                }).thenWaitUntil(() -> helper.assertTrue(rig.shutter.atRequestedPosition() && rig.portcullis.atRequestedPosition(),
                        "Waiting for natural bay gates: " + rig.shutter.status() + "; " + rig.portcullis.status()))
                .thenWaitUntil(() -> helper.assertTrue(((ProbeDriveBlockEntity)
                        rig.mantle.getBlockEntity(rig.probe.local(ProbeTestRig.LEFT_DRIVE))).isImmersed(), "Waiting for the high dock to flood"))
                .thenExecute(() -> DockChecks.trim(rig.probe, rig.builder, 1))
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().minY() > 202.2, "Waiting to clear the natural bay floor: " + rig.sailingStatus()))
                .thenExecute(() -> DockChecks.trim(rig.probe, rig.builder, 0))
                .thenExecuteAfter(30, () -> DockChecks.propulsion(rig.probe, rig.builder, true, false))
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().minX() > rig.start.getX() + 24.5,
                        "Waiting to leave the natural bay: " + rig.sailingStatus()))
                .thenExecute(() -> {DockChecks.propulsion(rig.probe, rig.builder, false, false);DockChecks.trim(rig.probe, rig.builder, -1);})
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().maxY() < 199.5,
                        "Waiting to descend below the roof: " + rig.sailingStatus()))
                .thenExecute(() -> {DockChecks.trim(rig.probe, rig.builder, 0);DockChecks.propulsion(rig.probe, rig.builder, true, false);})
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().minX() > rig.start.getX() + 35,
                        "Waiting to reach the natural lava sea: " + rig.sailingStatus()))
                .thenExecute(() -> {DockChecks.propulsion(rig.probe, rig.builder, false, false);
                    LogUtils.getLogger().info("Natural bay fueled probe reached open sea after {} ticks: {}", rig.elapsed(), rig.sailingStatus());})
                .thenExecuteAfter(30, () -> DockChecks.propulsion(rig.probe, rig.builder, true, true))
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().maxX() < rig.start.getX() + 34,
                        "Waiting to return to the launch well: " + rig.sailingStatus()))
                .thenExecute(() -> {DockChecks.propulsion(rig.probe, rig.builder, false, true);DockChecks.trim(rig.probe, rig.builder, 1);})
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().minY() > 202.2,
                        "Waiting to climb the flooded launch well: " + rig.sailingStatus()))
                .thenExecute(() -> DockChecks.trim(rig.probe, rig.builder, 0))
                .thenExecuteAfter(30, () -> DockChecks.propulsion(rig.probe, rig.builder, true, true))
                .thenWaitUntil(() -> helper.assertTrue(rig.probe.subLevel.boundingBox().maxX() < rig.start.getX() + 23.5,
                        "Waiting to return to the high dock: " + rig.sailingStatus()))
                .thenExecute(() -> {DockChecks.propulsion(rig.probe, rig.builder, false, true);rig.portcullis.command(true);rig.shutter.command(true);})
                .thenWaitUntil(() -> helper.assertTrue(rig.portcullis.atRequestedPosition() && rig.shutter.atRequestedPosition(), "Waiting for high dock gates to close"))
                .thenWaitUntil(() -> {
                    for (var pos : BlockPos.betweenClosed(rig.dockAt(new BlockPos(-2, -1, -1)), rig.dockAt(new BlockPos(6, 7, 7))))
                        helper.assertTrue(rig.mantle.getFluidState(pos).isEmpty(), "Waiting for the high dock to drain at " + pos);
                }).thenExecute(() -> {
                    helper.assertTrue(rig.probe.cabin().isSealed(), "Natural bay return lost the cabin seal");
                    helper.assertTrue(Math.abs(rig.controller().getSpeed()) == 32 && rig.engine().getCurrentBurnTime() > 0,
                            "Natural bay round trip exhausted the supply connection");
                    var samples = rig.probe.sampler().inventory;
                    int carriedCopper = rig.builder.getInventory().countItem(Items.COPPER_INGOT)
                            + java.util.stream.IntStream.range(0, samples.getSlots()).mapToObj(samples::getStackInSlot)
                                    .filter(stack -> stack.is(Items.COPPER_INGOT)).mapToInt(ItemStack::getCount).sum();
                    helper.assertTrue(carriedCopper == 17,
                            "Natural bay voyage changed supplies: copper=" + rig.builder.getInventory().countItem(Items.COPPER_INGOT)
                                    + ", samples=" + java.util.stream.IntStream.range(0, samples.getSlots()).mapToObj(samples::getStackInSlot).toList());
                    helper.assertTrue(rig.floor.equals(rig.mantle.getBlockState(rig.floorPos))
                                    && rig.ceiling.equals(rig.mantle.getBlockState(rig.ceilingPos)),
                            "Natural bay voyage damaged the fixed supply area's floor or ceiling");
                    LogUtils.getLogger().info("Natural bay coal round trip complete after {} ticks: {}", rig.elapsed(), rig.sailingStatus());
                }).thenSucceed();
    }

    private static final class Rig {
        private static final TicketType<BlockPos> TICKET = TicketType.create("innerworld_base_check",
                Comparator.comparingLong(BlockPos::asLong), 40);
        private static final List<BlockPos> BARRELS = List.of(new BlockPos(0, 1, -3), new BlockPos(1, 1, -3),
                new BlockPos(0, 1, -1), new BlockPos(1, 1, -1));
        private static final BlockPos STATION = new BlockPos(1, 1, 4);
        private final GameTestHelper helper;
        private final ServerLevel source, mantle;
        private final BlockPos controllerPos = new BlockPos(-512, -49, 0);
        private final BlockPos origin = controllerPos.below();
        private final BlockPos sourceStation = controllerPos.west(4);
        private final BlockPos enginePos = controllerPos.above().south();
        private final BlockPos floorPos = origin.offset(12, 0, 0).atY(201);
        private final BlockPos ceilingPos = floorPos.west(8).above(12);
        private final BlockState floor, ceiling;
        private final int originalRock;
        private final List<SuperGlueEntity> glue = new ArrayList<>();
        private UUID identity;
        private BlockPos start, placed;
        private int mined;
        private ProbeTestRig probe;
        private FakePlayer builder;
        private BlockPos dockOrigin;
        private FloodingShutter shutter;
        private DockPortcullis portcullis;
        private final java.util.Map<BlockPos, BlockState> dockOriginal = new java.util.LinkedHashMap<>();
        private int extraMined;
        private final long started;

        private Rig(GameTestHelper helper) {
            this.helper = helper;source = helper.getLevel();mantle = source.getServer().getLevel(Mantle.LEVEL);
            started = source.getGameTime();
            keepLoaded();source.getChunkAt(controllerPos);mantle.getChunkAt(floorPos);
            floor = mantle.getBlockState(floorPos);ceiling = mantle.getBlockState(ceilingPos);
            helper.assertTrue(floor.isSolid() && ceiling.isSolid(), "Bay input is not natural roof rock");
            originalRock = remainingRock();
            source.setBlockAndUpdate(controllerPos, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                    .setValue(DrillControllerBlock.FACING, Direction.DOWN));
            source.setBlockAndUpdate(controllerPos.above(), AllBlocks.GEARBOX.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            source.setBlockAndUpdate(enginePos, SimBlocks.PORTABLE_ENGINES.get(DyeColor.BROWN).getDefaultState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
            engine().inventory.setItem(0, new ItemStack(Items.COAL, 16));
            for (int x = 0; x <= 2; x++) for (int z = -5; z <= 5; z++) cutter(new BlockPos(x, 0, z));
            for (int y = 1; y <= 10; y++) for (int z = -5; z <= 5; z++) cutter(new BlockPos(2, y, z));
            glue(new BlockPos(0, 0, -5), new BlockPos(2, 0, 5));
            glue(new BlockPos(2, 0, -5), new BlockPos(2, 10, 5));
            for (var pos : BARRELS) {
                source.setBlockAndUpdate(origin.offset(pos), Blocks.BARREL.defaultBlockState());glue(pos.below(), pos);
            }
            ((BarrelBlockEntity) source.getBlockEntity(origin.offset(BARRELS.getFirst()))).setItem(0, new ItemStack(Items.COPPER_INGOT, 17));
            var owner = GlobalPos.of(source.dimension(), controllerPos);
            source.setBlockAndUpdate(sourceStation, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState());
            source.setBlockAndUpdate(sourceStation.north().below(), Blocks.IRON_BLOCK.defaultBlockState());
            ((BoreStationBlockEntity) source.getBlockEntity(sourceStation)).bind(owner);
            source.setBlockAndUpdate(origin.offset(STATION), AeronauticsInnerworld.BORE_STATION.get().defaultBlockState()
                    .setValue(BoreStationBlock.FACING, Direction.WEST));
            ((BoreStationBlockEntity) source.getBlockEntity(origin.offset(STATION))).bind(owner);glue(STATION.below(), STATION);
        }

        private void keepLoaded() {
            source.resetEmptyTime();mantle.resetEmptyTime();
            source.getChunkSource().addRegionTicket(TICKET, new ChunkPos(controllerPos), 2, controllerPos);
            if (shutter != null) shutter.tick();
            if (portcullis != null) portcullis.tick();
            if (portcullis != null) for (var pos : List.of(placed.offset(STATION).west(), start.offset(3, 1, 3), start.offset(12, 1, 3)))
                helper.assertTrue(mantle.getFluidState(pos).isEmpty(), "Fixed supply area flooded at " + pos);
        }

        private long elapsed() {return source.getGameTime() - started;}

        private void cutter(BlockPos relative) {source.setBlockAndUpdate(origin.offset(relative), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());}
        private void glue(BlockPos from, BlockPos to) {
            var entity = new SuperGlueEntity(source, SuperGlueEntity.span(origin.offset(from), origin.offset(to)));
            source.addFreshEntity(entity);glue.add(entity);
        }
        private DrillControllerBlockEntity controller() {return (DrillControllerBlockEntity) source.getBlockEntity(controllerPos);}
        private PortableEngineBlockEntity engine() {return (PortableEngineBlockEntity) source.getBlockEntity(enginePos);}
        private DrillHeadEntity head() {
            helper.assertTrue(controller().movedContraption instanceof DrillHeadEntity, "Bay head missing: " + controller().getStatus());
            return (DrillHeadEntity) controller().movedContraption;
        }
        private void assertHead() {
            helper.assertTrue(head().getUUID().equals(identity) && count(Items.COPPER_INGOT) == 17, "Bay excavation lost its original head or cargo");
            helper.assertTrue(Math.abs(controller().getSpeed()) == 32 && engine().inventory.getItem(0).getCount() < 16,
                    "Drill lacks real coal power");
        }
        private void assertPaused() {helper.assertTrue(controller().getStatus().equals("paused"), "Bay head has not paused");}
        private void resume(Direction direction) {
            helper.assertTrue(controller().setMovementDirection(direction), "Head could not select " + direction);
            controller().toggleWork();
        }
        private int count(Item item) {
            var storage = head().getContraption().getStorage().getAllItems();int total = 0;
            for (int slot = 0; slot < storage.getSlots(); slot++) if (storage.getStackInSlot(slot).is(item)) total += storage.getStackInSlot(slot).getCount();
            return total;
        }

        private int remainingRock() {
            int total = 0;
            for (var pos : BlockPos.betweenClosed(origin.offset(-1, 0, -5).atY(200), origin.offset(35, 0, 5).atY(255)))
                if (mantle.getBlockState(pos).is(Blocks.DEEPSLATE)) total++;
            return total;
        }

        private void inspectAndPlace() {
            assertHead();mined = count(Items.COBBLED_DEEPSLATE);
            LogUtils.getLogger().info("Coal bay horizontal excavation paused after {} ticks; collected={}", elapsed(), mined);
            helper.assertTrue(mined == originalRock - remainingRock(), "Excavated rock did not match the mounted inventory: " + mined);
            for (var pos : BlockPos.betweenClosed(start.offset(3, 0, -5), start.offset(23, 10, 5)))
                helper.assertTrue(mantle.getBlockState(pos).getCollisionShape(mantle, pos).isEmpty() && mantle.getFluidState(pos).isEmpty(),
                        "Bay is not clear and dry at " + pos);
            for (var pos : BlockPos.betweenClosed(start.offset(24, -2, -5), start.offset(34, 7, 5)))
                helper.assertTrue(mantle.getBlockState(pos).getCollisionShape(mantle, pos).isEmpty(), "Launch well is blocked at " + pos);
            helper.assertTrue(mantle.getFluidState(start.offset(28, -3, 0)).is(net.minecraft.tags.FluidTags.LAVA),
                    "Launch well does not connect to the natural lava sea");
            placed = head().blockPosition();
            helper.assertTrue(controller().tryPlaceHead(), "Bay head could not park at the end of its excavation");
            int rock = 0, copper = 0;
            for (var relative : BARRELS) {
                var barrel = (BarrelBlockEntity) mantle.getBlockEntity(placed.offset(relative));
                for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                    var stack = barrel.getItem(slot);
                    if (stack.is(Items.COPPER_INGOT)) copper += stack.getCount();
                    if (stack.is(Items.COBBLED_DEEPSLATE)) rock += stack.getCount();
                }
            }
            helper.assertTrue(copper == 17 && rock == mined, "Parking lost the excavation's supplies or collected rock");
        }

        private BoreStationBlockEntity targetStation() {return (BoreStationBlockEntity) mantle.getBlockEntity(placed.offset(STATION));}
        private void prepareProbe() {
            var feet = placed.offset(STATION).west();
            helper.assertTrue(mantle.getBlockState(feet).isAir() && mantle.getBlockState(feet.above()).isAir()
                            && mantle.getBlockState(feet.below()).isFaceSturdy(mantle, feet.below(), Direction.UP),
                    "Carried station does not have a dry, supported exit");
            builder = new FakePlayer(mantle, new GameProfile(UUID.randomUUID(), "base-builder"));
            builder.setGameMode(GameType.SURVIVAL);
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            builder.setPos(Vec3.atBottomCenterOf(feet));
            helper.assertTrue(mantle.noCollision(builder, builder.getBoundingBox()) && !mantle.containsAnyLiquid(builder.getBoundingBox()),
                    "Builder does not fit the station exit");
            var doorway = placed.offset(2, 1, 3);
            for (var pos : List.of(doorway, doorway.above()))
                helper.assertTrue(builder.gameMode.destroyBlock(pos), "Builder could not open the parked cutting face");
            helper.assertTrue(builder.getMainHandItem().getDamageValue() == 2, "Opening the cutting face bypassed tool durability");
            int recovered = mantle.getEntitiesOfClass(ItemEntity.class, new AABB(doorway).inflate(2)).stream()
                    .filter(item -> item.getItem().is(AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().asItem()))
                    .mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(recovered == 2 && mantle.getBlockState(doorway).isAir() && mantle.getBlockState(doorway.above()).isAir(),
                    "Parked head did not yield a two-block access passage and reusable cutters");
            for (var relative : BARRELS) {
                var barrel = (BarrelBlockEntity) mantle.getBlockEntity(placed.offset(relative));
                for (int slot = 0; slot < barrel.getContainerSize(); slot++)
                    if (barrel.getItem(slot).is(Items.COPPER_INGOT)) builder.getInventory().add(barrel.removeItem(slot, 64));
            }
            dockOrigin = start.offset(16, 1, 2);
            builder.getInventory().add(builder.getMainHandItem().copy());
            builder.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            excavate(new BlockPos(-7, 7, -5), new BlockPos(10, 10, 8));
            excavate(new BlockPos(-1, -1, 4), new BlockPos(9, 14, 8));
            excavate(new BlockPos(0, 7, 9), new BlockPos(4, 9, 17));
            excavate(new BlockPos(1, -2, -1), new BlockPos(3, -2, 7));
            helper.assertTrue(builder.getMainHandItem().getDamageValue() == extraMined,
                    "Dock expansion did not consume normal pickaxe durability: " + extraMined);
            builder.getInventory().add(builder.getMainHandItem().copy());builder.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            for (var pos : BlockPos.betweenClosed(new BlockPos(-3, -2, -2), new BlockPos(7, 8, 8))) {
                if (pos.getX() == -3 || pos.getX() == 7 || pos.getZ() == -2 || pos.getZ() == 8 || pos.getY() == 8)
                    dockSet(dockAt(pos), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
            for (int y : new int[] {1, 2}) dockSet(dockAt(new BlockPos(2, y, -2)), AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).rotate(Rotation.COUNTERCLOCKWISE_90));
            for (var pos : BlockPos.betweenClosed(new BlockPos(0, 9, 9), new BlockPos(4, 9, 17)))
                dockSet(dockAt(pos), Blocks.LAVA.defaultBlockState());
            for (var pos : BlockPos.betweenClosed(start.offset(25, 0, -2).atY(193), start.offset(43, 0, 2).atY(199)))
                helper.assertTrue(mantle.getBlockState(pos).getCollisionShape(mantle, pos).isEmpty(),
                        "Natural launch route is obstructed at " + pos + ": " + mantle.getBlockState(pos));
            probe = new ProbeTestRig(mantle, dockOrigin, false, Rotation.COUNTERCLOCKWISE_90);
            probe.workEquipment();probe.survivalPower();probe.landingSkids();
            ((MantleSamplerBlockEntity) mantle.getBlockEntity(probe.source(ProbeTestRig.SAMPLER))).clearContent();
            shutter = new FloodingShutter(mantle, dockOrigin, builder, Rotation.COUNTERCLOCKWISE_90, true);
            portcullis = new DockPortcullis(mantle, dockOrigin, builder, Rotation.COUNTERCLOCKWISE_90);
            LogUtils.getLogger().info("Natural bay dock prepared: extra rock={}, origin={}, heading=east", extraMined, dockOrigin);
        }
        private BlockPos dockAt(BlockPos relative) {return dockOrigin.offset(relative.rotate(Rotation.COUNTERCLOCKWISE_90));}

        private void excavate(BlockPos from, BlockPos to) {
            for (var pos : BlockPos.betweenClosed(dockAt(from), dockAt(to))) {
                if (!mantle.getBlockState(pos).is(Blocks.DEEPSLATE)) continue;
                helper.assertTrue(!builder.getMainHandItem().isEmpty() && builder.gameMode.destroyBlock(pos),
                        "Builder could not expand the dock at " + pos);
                extraMined++;
            }
        }

        private void dockSet(BlockPos pos, BlockState state) {
            dockOriginal.putIfAbsent(pos.immutable(), mantle.getBlockState(pos));mantle.setBlockAndUpdate(pos, state);
        }

        private void openDock() {
            probe.cabin().addCoolant(240);shutter.command(false);portcullis.command(false);
        }

        private String sailingStatus() {
            var bounds = probe.subLevel.boundingBox();
            return "bounds=(" + bounds.minX() + "," + bounds.minY() + "," + bounds.minZ() + ")..("
                    + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ() + "), drive="
                    + ((ProbeDriveBlockEntity) mantle.getBlockEntity(probe.local(ProbeTestRig.LEFT_DRIVE))).getSpeed()
                    + ", velocity=" + dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(probe.subLevel).getLinearVelocity();
        }

        private void cleanUp() {
            if (portcullis != null) portcullis.cleanUp();
            if (shutter != null) shutter.cleanUp();
            if (probe != null) probe.cleanUp();
            dockOriginal.forEach(mantle::setBlockAndUpdate);
            if (builder != null) builder.discard();
            if (source.getBlockEntity(enginePos) instanceof PortableEngineBlockEntity engine) engine.inventory.clearContent();
            if (source.getBlockEntity(controllerPos) instanceof DrillControllerBlockEntity controller) {
                var head = controller.movedContraption;controller.disassemble();if (head != null) head.discard();
                source.removeBlock(controllerPos, false);
            }
            glue.forEach(net.minecraft.world.entity.Entity::discard);
            source.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(controllerPos), 2, controllerPos);
        }
    }
}

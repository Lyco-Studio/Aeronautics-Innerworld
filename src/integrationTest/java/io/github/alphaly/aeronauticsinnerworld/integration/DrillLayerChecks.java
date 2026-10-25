package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DrillLayerChecks {
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void crossesExcavatesReloadsAndReturns(GameTestHelper helper) {
        var rig = new Rig(helper);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> ((CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(rig.controllerPos.above()))
                        .generatedSpeed.setValue(64))
                .thenIdle(3)
                .thenExecute(() -> {
                    rig.controller().toggleWork();
                    rig.identity = rig.head().getUUID();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().level().dimension().equals(Mantle.LEVEL),
                        "Head has not crossed into the mantle: " + rig.controller().getStatus()))
                .thenExecute(() -> {
                    rig.assertCargo();
                    helper.assertTrue(rig.controller().getStatus().equals("layer_arrived"), "Arrival did not pause");
                    helper.assertTrue(helper.getLevel().getEntity(rig.identity) == null, "Source head was duplicated");
                    var mantle = rig.mantle();
                    var column = BlockPos.containing(rig.head().position());
                    helper.assertTrue(mantle.getBlockState(column.atY(0)).is(Blocks.BEDROCK), "Mantle floor is missing");
                    helper.assertTrue(mantle.getFluidState(column.atY(199)).is(Fluids.LAVA), "Mantle lava layer is missing");
                    helper.assertTrue(mantle.getBlockState(column.atY(200)).isSolid(), "Mantle roof is too thin");
                    helper.assertTrue(mantle.getBlockState(column.atY(255)).isSolid(), "Arrival excavated the roof without drilling");
                    rig.remoteUse();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() <= 252,
                        "Head has not drilled into the mantle roof: " + rig.controller().getStatus()
                                + "; position " + rig.head().position() + "; speed " + rig.controller().getSpeed()
                                + "; stalled " + rig.head().isStalled()))
                .thenExecute(rig::remoteUse)
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> {
                    rig.assertCargo();
                    rig.checkpoint = rig.head().position();
                    rig.remoteDirection(Direction.EAST);
                    rig.remoteUse();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getX() >= rig.checkpoint.x + 1.4,
                        "Transferred head has not excavated horizontally"))
                .thenExecute(rig::remoteUse)
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> {
                    helper.assertTrue(rig.count(Items.COBBLED_DEEPSLATE) > 0,
                            "Mantle excavation did not collect resources; entity ticks " + rig.head().tickCount
                                    + "; actors " + rig.head().getContraption().getActors().stream()
                                    .map(actor -> actor.right.position + "/" + actor.right.relativeMotion).toList());
                    rig.reload();
                })
                .thenIdle(8)
                .thenExecute(() -> {
                    rig.assertCargo();
                    helper.assertTrue(rig.head().position().distanceTo(rig.checkpoint) < 0.001,
                            "Remote save/reload changed the drill position");
                    rig.returnObstruction = new BlockPos(rig.head().getBlockX(), rig.head().getReturnPosition().getY(), rig.head().getBlockZ());
                    helper.getLevel().setBlockAndUpdate(rig.returnObstruction, Blocks.OBSIDIAN.defaultBlockState());
                    rig.remoteDirection(Direction.UP);
                    rig.remoteUse();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.controller().getStatus().equals("layer_blocked"),
                        "Blocked return has not stopped safely: " + rig.controller().getStatus() + "; "
                                + rig.head().position() + "; speed " + rig.controller().getSpeed()
                                + "; stalled " + rig.head().isStalled() + "; direction " + rig.controller().getMovementDirection()
                                + "; bounds " + rig.head().getContraption().bounds + "; entity bounds " + rig.head().getBoundingBox()))
                .thenExecute(() -> {
                    rig.assertCargo();
                    helper.assertTrue(rig.head().level().dimension().equals(Mantle.LEVEL), "Blocked return moved the head");
                    helper.assertTrue(helper.getLevel().getBlockState(rig.returnObstruction).is(Blocks.OBSIDIAN),
                            "Return destroyed the obstruction");
                    helper.getLevel().removeBlock(rig.returnObstruction, false);
                    rig.remoteUse();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().level().dimension().equals(Level.OVERWORLD),
                        "Head has not returned to the overworld"))
                .thenExecute(() -> {
                    rig.assertCargo();
                    helper.assertTrue(rig.mantle().getEntity(rig.identity) == null, "Return duplicated the mantle head");
                    helper.assertTrue(rig.head().getBlockX() == rig.returnObstruction.getX(), "Return changed horizontal coordinates");
                    var chestPos = rig.head().blockPosition().east();
                    helper.assertTrue(rig.controller().tryPlaceHead(), "Returned head could not be placed");
                    var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(chestPos);
                    helper.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
                            "Return placement lost mounted cargo");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void pairsCarriedStationAndRejectsUnsafeTransit(GameTestHelper helper) {
        var rig = new Rig(helper, true);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> ((CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(rig.controllerPos.above()))
                        .generatedSpeed.setValue(64))
                .thenIdle(3)
                .thenExecute(() -> {
                    rig.traveller = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "bore-transit-check")) {
                        @Override
                        public void displayClientMessage(net.minecraft.network.chat.Component message, boolean actionBar) {
                            rig.stationMessage = message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                                    ? text.getKey() : message.getString();
                        }
                    };
                    rig.traveller.getInventory().setItem(5, new ItemStack(Items.COPPER_INGOT, 17));
                    helper.assertTrue(!rig.sourceStation().travel(rig.traveller), "Station opened before drilling");
                    rig.assertStationMessage("station_waiting");
                    rig.controller().toggleWork();
                    rig.identity = rig.head().getUUID();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().isRemote(), "Station assembly has not crossed"))
                .thenExecute(rig::remoteUse)
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() <= 253.25,
                        "Station assembly has not excavated its exit: " + rig.controller().getStatus()))
                .thenExecute(rig::remoteUse)
                .thenWaitUntil(rig::assertPaused)
                .thenExecute(() -> {
                    rig.assertCargo();
                    helper.assertTrue(rig.sourceStation().getPartner() == null, "Moving station connected before placement");
                    rig.destinationStation = rig.head().blockPosition().north();
                    helper.assertTrue(rig.controller().tryPlaceHead(), "Station assembly could not be placed");
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.sourceStation().getPartner() != null, "Stations have not paired: source=" + rig.sourceStation().getOwner()
                        + "; target=" + rig.targetStation().getOwner() + "; controller="
                        + rig.controller().saveWithFullMetadata(helper.getLevel().registryAccess())))
                .thenExecute(() -> {
                    rig.reloadStation(helper.getLevel(), rig.sourceStationPos);
                    rig.reloadStation(rig.mantle(), rig.destinationStation);
                    var source = helper.getLevel();
                    var controllerData = rig.controller().saveWithFullMetadata(source.registryAccess());
                    source.setBlockEntity(BlockEntity.loadStatic(rig.controllerPos, source.getBlockState(rig.controllerPos),
                            controllerData, source.registryAccess()));
                    var stationState = source.getBlockState(rig.sourceStationPos);
                    source.removeBlock(rig.sourceStationPos, false);
                    source.setBlockAndUpdate(rig.sourceStationPos, stationState);
                    rig.sourceStation().bind(net.minecraft.core.GlobalPos.of(source.dimension(), rig.controllerPos));
                    helper.assertTrue(rig.sourceStation().getPartner() != null, "Rebuilt source station lost its destination");
                    var feet = rig.destinationStation.east();
                    rig.mantle().setBlockAndUpdate(feet, Blocks.STONE.defaultBlockState());
                    helper.assertTrue(!rig.sourceStation().travel(rig.traveller), "Station overwrote an obstructed exit");
                    rig.assertStationMessage("station_exit_blocked");
                    helper.assertTrue(rig.mantle().getBlockState(feet).is(Blocks.STONE), "Station cleared destination terrain");
                    rig.mantle().setBlockAndUpdate(feet, Blocks.LAVA.defaultBlockState());
                    helper.assertTrue(!rig.sourceStation().travel(rig.traveller), "Station admitted the player into lava");
                    rig.assertStationMessage("station_exit_blocked");
                    rig.mantle().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                    ((CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(rig.controllerPos.above())).generatedSpeed.setValue(0);
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.controller().getSpeed() == 0, "Station power has not stopped"))
                .thenExecute(() -> {
                    helper.assertTrue(!rig.sourceStation().travel(rig.traveller), "Unpowered station transported a player");
                    rig.assertStationMessage("station_no_power");
                    ((CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(rig.controllerPos.above())).generatedSpeed.setValue(64);
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.controller().getSpeed() != 0, "Station power has not resumed"))
                .thenExecute(() -> {
                    rig.traveller.setPortalCooldown(40);
                    helper.assertTrue(!rig.sourceStation().travel(rig.traveller), "Station cooldown was bypassed");
                    rig.assertStationMessage("station_player_busy");
                    helper.assertTrue(rig.sourceStation().getPartner().pos().equals(rig.destinationStation),
                            "Station pairing changed during refused transit");
                    var cargo = rig.traveller.getInventory().getItem(5);
                    helper.assertTrue(cargo.is(Items.COPPER_INGOT) && cargo.getCount() == 17,
                            "Refused transit changed carried supplies");
                })
                .thenSucceed();
    }

    private static final class Rig {
        private final GameTestHelper helper;
        private final BlockPos controllerPos;
        private final ItemStack remote = new ItemStack(AeronauticsInnerworld.DRILL_REMOTE.get());
        private UUID identity;
        private Vec3 checkpoint;
        private BlockPos returnObstruction;
        private BlockPos sourceStationPos;
        private BlockPos destinationStation;
        private ServerPlayer traveller;
        private String stationMessage;
        private final boolean withStation;

        private Rig(GameTestHelper helper) {
            this(helper, false);
        }

        private Rig(GameTestHelper helper, boolean withStation) {
            this.helper = helper;
            this.withStation = withStation;
            var base = new BlockPos(5, 5, 6);
            controllerPos = helper.absolutePos(base);
            helper.setBlock(base, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                    .setValue(DrillControllerBlock.FACING, Direction.DOWN));
            helper.setBlock(base.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN));
            var root = base.below();
            helper.setBlock(root, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
            helper.setBlock(root.east(), Blocks.CHEST);
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(root.east()));
            chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
            for (var pos : new BlockPos[] {root.east().above(), root.east().below(), root.east(2)}) {
                helper.setBlock(pos, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
                glue(root.east(), pos);
            }
            glue(root, root.east());
            var player = helper.makeMockPlayer(GameType.CREATIVE);
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            remote.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(controllerPos), Direction.UP, controllerPos, false)));
            if (withStation) {
                var main = base.offset(-4, 0, -4);
                sourceStationPos = helper.absolutePos(main);
                helper.setBlock(main, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState()
                        .setValue(BoreStationBlock.FACING, Direction.SOUTH));
                helper.setBlock(main.south().below(), Blocks.IRON_BLOCK);
                helper.setBlock(root.north(), AeronauticsInnerworld.BORE_STATION.get().defaultBlockState()
                        .setValue(BoreStationBlock.FACING, Direction.EAST));
                helper.setBlock(root.north().below(), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
                helper.setBlock(root.east().north().below(), Blocks.IRON_BLOCK);
                helper.setBlock(root.east().north().below(2), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
                glue(root, root.north());
                glue(root.north(), root.north().below());
                glue(root.east().below(), root.east().north().below());
                glue(root.east().north().below(), root.east().north().below(2));
                var owner = net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), controllerPos);
                sourceStation().bind(owner);
                ((BoreStationBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(root.north()))).bind(owner);
            }
        }

        private void assertStationMessage(String key) {
            helper.assertTrue(("message.aeronautics_innerworld." + key).equals(stationMessage),
                    "Unexpected transit rejection: " + stationMessage);
        }

        private BoreStationBlockEntity sourceStation() {
            return (BoreStationBlockEntity) helper.getLevel().getBlockEntity(sourceStationPos);
        }

        private BoreStationBlockEntity targetStation() {
            return (BoreStationBlockEntity) mantle().getBlockEntity(destinationStation);
        }

        private void reloadStation(ServerLevel level, BlockPos pos) {
            var data = level.getBlockEntity(pos).saveWithFullMetadata(level.registryAccess());
            level.setBlockEntity(BlockEntity.loadStatic(pos, level.getBlockState(pos), data, level.registryAccess()));
        }

        private void glue(BlockPos from, BlockPos to) {
            helper.getLevel().addFreshEntity(new SuperGlueEntity(helper.getLevel(),
                    SuperGlueEntity.span(helper.absolutePos(from), helper.absolutePos(to))));
        }

        private DrillControllerBlockEntity controller() {
            return (DrillControllerBlockEntity) helper.getLevel().getBlockEntity(controllerPos);
        }

        private DrillHeadEntity head() {
            helper.assertTrue(controller().movedContraption instanceof DrillHeadEntity,
                    "Drill head is missing: " + controller().getStatus());
            return (DrillHeadEntity) controller().movedContraption;
        }

        private ServerLevel mantle() {
            var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
            helper.assertTrue(level != null, "Mantle dimension was not loaded");
            return level;
        }

        private void remoteUse() {
            var player = FakePlayerFactory.getMinecraft((ServerLevel) head().level());
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            remote.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
        }

        private void remoteDirection(Direction direction) {
            var player = FakePlayerFactory.getMinecraft((ServerLevel) head().level());
            player.setShiftKeyDown(true);
            player.setItemInHand(InteractionHand.MAIN_HAND, remote);
            for (int i = 0; i < 6 && controller().getMovementDirection() != direction; i++) {
                remote.getItem().use(player.level(), player, InteractionHand.MAIN_HAND);
            }
            helper.assertTrue(controller().getMovementDirection() == direction, "Remote direction selection failed");
        }

        private void assertPaused() {
            helper.assertTrue(controller().getStatus().equals("paused"), "Head has not paused: " + controller().getStatus());
        }

        private int count(net.minecraft.world.item.Item item) {
            int count = 0;
            var inventory = head().getContraption().getStorage().getAllItems();
            for (int i = 0; i < inventory.getSlots(); i++) {
                if (inventory.getStackInSlot(i).is(item)) {
                    count += inventory.getStackInSlot(i).getCount();
                }
            }
            return count;
        }

        private void assertCargo() {
            helper.assertTrue(head().getUUID().equals(identity), "Cross-layer head identity changed");
            helper.assertTrue(head().getContraption().getBlocks().size() == (withStation ? 9 : 5), "Cross-layer structure changed");
            helper.assertTrue(count(Items.DIAMOND) == 3, "Cross-layer cargo was lost or duplicated");
        }

        private void reload() {
            checkpoint = head().position();
            var source = helper.getLevel();
            var controllerData = controller().saveWithFullMetadata(source.registryAccess());
            var headData = new CompoundTag();
            helper.assertTrue(head().save(headData), "Remote head could not be saved");
            head().discard();
            source.setBlockEntity(BlockEntity.loadStatic(controllerPos, source.getBlockState(controllerPos),
                    controllerData, source.registryAccess()));
            var restored = EntityType.loadEntityRecursive(headData, mantle(), entity -> entity);
            helper.assertTrue(restored instanceof DrillHeadEntity && mantle().addFreshEntity(restored),
                    "Remote head could not be restored");
        }
    }
}

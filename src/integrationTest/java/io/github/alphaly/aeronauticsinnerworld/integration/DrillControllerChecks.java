package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DrillControllerChecks {
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void turnsSameHeadAndRestoresInventory(GameTestHelper helper) {
        var rig = new Rig(helper);
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> rig.motor().generatedSpeed.setValue(64))
                .thenIdle(3)
                .thenExecute(() -> {
                    rig.controller().toggleWork();
                    rig.identity = rig.head().getUUID();
                    helper.assertTrue(!rig.controller().setMovementDirection(Direction.EAST),
                            "Direction changed while the head was moving");
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() <= rig.origin.y - 1.4,
                        "Head has not completed vertical excavation"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(() -> rig.assertPaused())
                .thenExecute(() -> {
                    rig.assertIdentityAndCargo();
                    rig.checkpoint = rig.head().position();
                    helper.assertTrue(rig.controller().setMovementDirection(Direction.EAST),
                            "Paused head could not change to horizontal travel");
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    rig.assertAtCheckpoint();
                    rig.controller().toggleWork();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getX() >= rig.origin.x + 1.4,
                        "The same head has not excavated horizontally"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(() -> rig.assertPaused())
                .thenExecute(() -> {
                    rig.assertIdentityAndCargo();
                    helper.assertTrue(rig.count(Items.COBBLED_DEEPSLATE) >= 2,
                            "Excavated resources did not enter mounted storage");
                    helper.assertTrue(rig.controller().setMovementDirection(Direction.SOUTH), "Second turn failed");
                    rig.controller().toggleWork();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getZ() > rig.origin.z + 0.1, "Head has not resumed"))
                .thenExecute(() -> rig.motor().generatedSpeed.setValue(0))
                .thenWaitUntil(() -> helper.assertTrue(rig.controller().getSpeed() == 0, "Motor has not stopped"))
                .thenExecute(() -> rig.checkpoint = rig.head().position())
                .thenIdle(5)
                .thenExecute(() -> {
                    rig.assertAtCheckpoint();
                    helper.assertTrue(!rig.controller().canWork(), "Unpowered equipment remained active");
                    rig.motor().generatedSpeed.setValue(64);
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getZ() >= rig.origin.z + 1.4, "Power restoration did not resume travel"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(() -> rig.assertPaused())
                .thenExecute(rig::reload)
                .thenIdle(5)
                .thenExecute(() -> {
                    rig.assertAtCheckpoint();
                    rig.assertIdentityAndCargo();
                    helper.assertTrue(rig.controller().getMovementDirection() == Direction.SOUTH,
                            "Controller direction changed on reload");
                    helper.assertTrue(rig.controller().setMovementDirection(Direction.UP), "Restored controller could not turn");
                    rig.controller().toggleWork();
                })
                .thenWaitUntil(() -> helper.assertTrue(rig.head().getY() >= rig.checkpoint.y + 1.4,
                        "Restored head could not resume movement"))
                .thenExecute(() -> rig.controller().requestPause())
                .thenWaitUntil(() -> rig.assertPaused())
                .thenExecute(() -> {
                    rig.assertIdentityAndCargo();
                    var root = BlockPos.containing(rig.head().position());
                    helper.getLevel().setBlockAndUpdate(root, Blocks.OBSIDIAN.defaultBlockState());
                    helper.assertTrue(!rig.controller().tryPlaceHead(), "Head overwrote a blocked placement position");
                    helper.assertTrue(helper.getLevel().getBlockState(root).is(Blocks.OBSIDIAN), "Placement destroyed the obstruction");
                    rig.assertIdentityAndCargo();
                    helper.getLevel().removeBlock(root, false);
                    helper.assertTrue(rig.controller().tryPlaceHead(), "Paused head could not be placed");
                    helper.assertTrue(!rig.controller().running, "Controller retained an assembled head after placement");
                    helper.assertTrue(helper.getLevel().getBlockState(root).is(Blocks.SLIME_BLOCK), "Head structure was lost");
                    var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(root.north());
                    helper.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND)
                                    && chest.getItem(0).getCount() == 3,
                            "Inventory was not restored to the placed chest");
                    helper.assertTrue(helper.getLevel().getEntity(rig.identity) == null,
                            "Placed head left a duplicate entity");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void rejectedAssemblyPreservesBlocksAndInventory(GameTestHelper helper) {
        var rig = new Rig(helper);
        helper.setBlock(new BlockPos(5, 9, 6), Blocks.IRON_BLOCK);
        helper.setBlock(new BlockPos(6, 10, 6), Blocks.IRON_BLOCK);
        rig.controller().toggleWork();
        helper.assertTrue(!rig.controller().running && rig.controller().movedContraption == null,
                "A head without cutting equipment assembled");
        helper.assertTrue(rig.controller().getLastAssemblyException() != null, "Assembly failure was not reported");
        helper.assertBlockPresent(Blocks.SLIME_BLOCK, new BlockPos(5, 10, 6));
        helper.assertBlockPresent(Blocks.IRON_BLOCK, new BlockPos(5, 9, 6));
        helper.assertBlockPresent(Blocks.IRON_BLOCK, new BlockPos(6, 10, 6));
        var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 10, 5)));
        helper.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 3,
                "Rejected assembly changed the inventory");
        helper.succeed();
    }

    private static final class Rig {
        private final GameTestHelper helper;
        private final BlockPos controllerPos;
        private final BlockPos motorPos;
        private final Vec3 origin;
        private UUID identity;
        private Vec3 checkpoint;

        private Rig(GameTestHelper helper) {
            this.helper = helper;
            var base = new BlockPos(5, 11, 6);
            controllerPos = helper.absolutePos(base);
            motorPos = helper.absolutePos(base.above());
            origin = Vec3.atLowerCornerOf(helper.absolutePos(base.below()));
            helper.setBlock(base, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                    .setValue(DrillControllerBlock.FACING, Direction.DOWN));
            helper.setBlock(base.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN));
            helper.setBlock(base.below(), Blocks.SLIME_BLOCK);
            helper.setBlock(base.below(2), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
            helper.setBlock(base.below().east(), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
            for (var cutter : new BlockPos[] {base.below(2), base.below().east()}) {
                helper.getLevel().addFreshEntity(new SuperGlueEntity(helper.getLevel(),
                        SuperGlueEntity.span(helper.absolutePos(base.below()), helper.absolutePos(cutter))));
            }
            helper.setBlock(base.below().north(), Blocks.CHEST);
            ((ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(base.below().north())))
                    .setItem(0, new ItemStack(Items.DIAMOND, 3));
            helper.setBlock(base.below(3), Blocks.DEEPSLATE);
            helper.setBlock(base.below(3).east(2), Blocks.DEEPSLATE);
        }

        private DrillControllerBlockEntity controller() {
            return (DrillControllerBlockEntity) helper.getLevel().getBlockEntity(controllerPos);
        }

        private CreativeMotorBlockEntity motor() {
            return (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(motorPos);
        }

        private DrillHeadEntity head() {
            helper.assertTrue(controller().movedContraption instanceof DrillHeadEntity,
                    "Drill head is missing: " + controller().getStatus() + "; "
                            + (controller().getLastAssemblyException() == null ? "no assembly error"
                            : controller().getLastAssemblyException().component.getString()));
            return (DrillHeadEntity) controller().movedContraption;
        }

        private int count(Item item) {
            int total = 0;
            var inventory = head().getContraption().getStorage().getAllItems();
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        private void assertPaused() {
            helper.assertTrue(controller().getStatus().equals("paused"), "Head has not reached a paused grid position");
        }

        private void assertIdentityAndCargo() {
            helper.assertTrue(head().getUUID().equals(identity), "Direction change replaced the head entity");
            helper.assertTrue(head().getContraption().getBlocks().size() == 4, "Head structure changed");
            helper.assertTrue(count(Items.DIAMOND) == 3, "Mounted cargo was lost or duplicated");
        }

        private void assertAtCheckpoint() {
            helper.assertTrue(head().position().distanceTo(checkpoint) < 0.001, "Paused or restored head changed position");
        }

        private void reload() {
            var level = helper.getLevel();
            checkpoint = head().position();
            var controllerData = controller().saveWithFullMetadata(level.registryAccess());
            var entityData = new CompoundTag();
            helper.assertTrue(head().save(entityData), "Head could not be saved");
            head().discard();
            var restoredController = BlockEntity.loadStatic(controllerPos,
                    level.getBlockState(controllerPos), controllerData, level.registryAccess());
            helper.assertTrue(restoredController instanceof DrillControllerBlockEntity, "Controller could not be restored");
            level.setBlockEntity(restoredController);
            var restoredEntity = EntityType.loadEntityRecursive(entityData, level, entity -> entity);
            helper.assertTrue(restoredEntity instanceof DrillHeadEntity && level.addFreshEntity(restoredEntity),
                    "Saved head could not be restored");
        }
    }
}

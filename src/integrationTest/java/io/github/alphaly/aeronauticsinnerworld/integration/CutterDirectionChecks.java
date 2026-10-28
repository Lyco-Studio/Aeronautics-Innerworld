package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_cutter")
@PrefixGameTestTemplate(false)
public final class CutterDirectionChecks {
    @GameTest(template = "empty", timeoutTicks = 800)
    public static void downwardCutterPreservesControllerWithPositiveShaft(GameTestHelper helper) {run(helper, 32);}

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void downwardCutterPreservesControllerWithNegativeShaft(GameTestHelper helper) {run(helper, -32);}

    private static void run(GameTestHelper helper, int speed) {
        var base = new BlockPos(6, 10, 6);
        var root = base.below();
        helper.setBlock(base, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                .setValue(DrillControllerBlock.FACING, Direction.DOWN));
        helper.setBlock(base.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN));
        helper.setBlock(root, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
        helper.setBlock(root.west(), Blocks.CHEST);
        helper.getLevel().addFreshEntity(new SuperGlueEntity(helper.getLevel(),
                SuperGlueEntity.span(helper.absolutePos(root), helper.absolutePos(root.west()))));
        helper.setBlock(root.below(), Blocks.DEEPSLATE);
        helper.setBlock(root.below(2), Blocks.DEEPSLATE);
        var controller = (DrillControllerBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(base));
        var motor = (CreativeMotorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(base.above()));
        helper.startSequence().thenIdle(2).thenExecute(() -> motor.generatedSpeed.setValue(speed))
                .thenIdle(3).thenExecute(controller::toggleWork)
                .thenWaitUntil(() -> {
                    helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(base)) == controller,
                            "Cutter reversed its active area and destroyed its own controller");
                    helper.assertTrue(controller.movedContraption instanceof DrillHeadEntity head
                                    && head.getY() <= helper.absolutePos(root).getY() - 2,
                            "Cutter has not excavated both layers");
                }).thenExecute(controller::requestPause)
                .thenWaitUntil(() -> helper.assertTrue(controller.getStatus().equals("paused"), "Drill has not paused"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockState(root.below()).isAir()
                                    && helper.getBlockState(root.below(2)).isAir(), "Downward cutting missed the rock");
                    helper.assertTrue(controller.setMovementDirection(Direction.EAST), "Drill could not turn");
                    var head = controller.movedContraption;
                    var forward = BlockPos.containing(head.position()).east();
                    helper.getLevel().setBlockAndUpdate(forward, Blocks.DEEPSLATE.defaultBlockState());
                    controller.toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(controller.movedContraption.getX() >= helper.absolutePos(root).getX() + 1,
                        "Turned cutter did not excavate east: " + controller.getStatus() + "; "
                                + controller.movedContraption.position() + "; "
                                + controller.movedContraption.getContraption().getActors().getFirst().right.writeToNBT(new net.minecraft.nbt.CompoundTag())))
                .thenExecute(controller::requestPause)
                .thenWaitUntil(() -> helper.assertTrue(controller.getStatus().equals("paused"), "Turned drill has not paused"))
                .thenSucceed();
    }
}

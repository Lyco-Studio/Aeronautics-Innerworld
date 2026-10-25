package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@Mod("aeronautics_innerworld_checks")
@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuntimeChecks {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void excavatesDownward(GameTestHelper helper) {
        excavatesWithPiston(helper, Direction.DOWN);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void excavatesEastward(GameTestHelper helper) {
        excavatesWithPiston(helper, Direction.EAST);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void excavatesNorthward(GameTestHelper helper) {
        excavatesWithPiston(helper, Direction.NORTH);
    }

    private static void excavatesWithPiston(GameTestHelper helper, Direction direction) {
        var base = new BlockPos(8, 8, 8);
        var facing = BlockStateProperties.FACING;
        var pistonState = AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(facing, direction);
        var rock = base.relative(direction, 2);
        var barrier = base.relative(direction, 3);
        var shaftAxis = AllBlocks.MECHANICAL_PISTON.get().getRotationAxis(pistonState);
        var motorDirection = Direction.fromAxisAndDirection(shaftAxis, Direction.AxisDirection.POSITIVE);
        var motorPos = base.relative(motorDirection.getOpposite());
        helper.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(facing, motorDirection));
        helper.runAfterDelay(2, () -> {
            CreativeMotorBlockEntity motor = helper.getBlockEntity(motorPos);
            int speed = -64 * direction.getAxisDirection().getStep()
                    * (direction.getAxis() == Direction.Axis.Z ? -1 : 1);
            motor.generatedSpeed.setValue(speed);
        });
        helper.runAfterDelay(5, () -> {
            helper.setBlock(base, pistonState);
            for (int i = 1; i <= 4; i++) {
                helper.setBlock(base.relative(direction.getOpposite(), i),
                        AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(facing, direction));
            }
            helper.setBlock(base.relative(direction), AeronauticsInnerworld.MULTI_AXIS_CUTTER.get());
            helper.setBlock(rock, Blocks.DEEPSLATE);
            helper.setBlock(barrier, Blocks.BEDROCK);
        });
        helper.runAfterDelay(150, () -> {
            helper.assertBlockNotPresent(Blocks.DEEPSLATE, rock);
            helper.assertBlockPresent(Blocks.BEDROCK, barrier);
            helper.assertItemEntityPresent(Items.COBBLED_DEEPSLATE, rock, 16);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void physicsAssemblyAndRestore(GameTestHelper helper) {
        var level = helper.getLevel();
        var source = helper.absolutePos(new BlockPos(4, 8, 4));
        var chestPos = source.east();
        level.setBlockAndUpdate(source, Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        var bounds = new BoundingBox3i(source.getX(), source.getY(), source.getZ(),
                chestPos.getX(), chestPos.getY(), chestPos.getZ());
        var subLevel = SubLevelAssemblyHelper.assembleBlocks(level, source, List.of(source, chestPos), bounds);
        helper.assertTrue(subLevel != null, "Physical structure was not assembled");
        helper.assertTrue(level.getBlockState(source).isAir(), "Assembly left a duplicate source block");
        helper.assertTrue(level.getBlockState(chestPos).isAir(), "Assembly left a duplicate source inventory");
        var container = SubLevelContainer.getContainer(level);
        container.addForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);

        helper.runAfterDelay(5, () -> {
            var body = RigidBodyHandle.of(subLevel);
            helper.assertTrue(body.isValid(), "Native rigid body was not created");
            double startX = subLevel.logicalPose().position().x();
            body.applyLinearImpulse(new Vector3d(5000, 0, 0));
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(subLevel.logicalPose().position().x() > startX + 0.05,
                        "Physical structure did not respond to an impulse");
                var saved = SubLevelSerializer.toData(subLevel, List.of());
                var uuid = subLevel.getUniqueId();
                var position = new Vector3d(subLevel.logicalPose().position());
                container.removeForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                container.removeSubLevel(subLevel, SubLevelRemovalReason.UNLOADED);
                ServerSubLevel restored = SubLevelSerializer.fullyLoad(level, saved);
                helper.assertTrue(restored.getUniqueId().equals(uuid), "Structure identity changed on restore");
                helper.assertTrue(restored.logicalPose().position().distance(position) < 0.001,
                        "Structure position changed on restore");
                var plotBounds = restored.getPlot().getBoundingBox();
                var block = new BlockPos(plotBounds.minX(), plotBounds.minY(), plotBounds.minZ());
                helper.assertTrue(level.getBlockState(block).is(Blocks.IRON_BLOCK),
                        "Structure block was lost on restore");
                var chest = (ChestBlockEntity) level.getBlockEntity(block.east());
                helper.assertTrue(chest != null && chest.getItem(0).is(Items.DIAMOND)
                                && chest.getItem(0).getCount() == 3,
                        "Mounted inventory changed on restore");
                container.addForceLoadTicket(restored, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                helper.runAfterDelay(5, () -> {
                    helper.assertTrue(RigidBodyHandle.of(restored).isValid(),
                            "Restored structure did not recreate its native rigid body");
                    container.removeForceLoadTicket(restored, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    container.removeSubLevel(restored, SubLevelRemovalReason.REMOVED);
                    helper.succeed();
                });
            });
        });
    }
}

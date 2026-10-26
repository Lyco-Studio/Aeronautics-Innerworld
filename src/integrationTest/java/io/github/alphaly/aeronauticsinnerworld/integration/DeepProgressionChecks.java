package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.InnerworldStructures;
import io.github.alphaly.aeronauticsinnerworld.world.structure.DeepWorksPiece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepProgressionChecks {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void generatesDeepRecoveryAndDryWalkways(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(
                ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "deep_works"));
        var chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var chunk = new ChunkPos(80, 80);
        var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), chunks.randomState(),
                level.getServer().getStructureManager(), level.getSeed(), chunk, 0, level, biome -> true);
        helper.assertTrue(start.isValid(), "Deep works failed native structure generation");
        var box = start.getBoundingBox();
        start.placeInChunk(level, level.structureManager(), generator, RandomSource.create(19), box, chunk);
        level.getChunk(chunk.x, chunk.z).setStartForStructure(structure, start);
        int matrices = 0;
        int records = 0;
        int encounters = 0;
        var dryPositions = new java.util.ArrayList<BlockPos>();
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                helper.assertTrue(level.getBlockState(pos).getMenuProvider(level, pos) != null,
                        "Deep works chest cannot open its native container");
                dryPositions.add(pos.above().immutable());
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    var stack = chest.getItem(slot);
                    if (stack.is(AeronauticsInnerworld.PRESSURE_MATRIX.get())) matrices += stack.getCount();
                    if (stack.is(Items.WRITTEN_BOOK) && stack.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().size() == 2) records++;
                }
            }
            if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                helper.assertTrue(spawner.saveWithFullMetadata(level.registryAccess()).toString().contains("minecraft:magma_cube"),
                        "Deep works encounter has no valid magma cube source");
                encounters++;
            }
        }
        helper.assertTrue(matrices == 2 && records == 1 && encounters == 1, "Deep recovery rewards or encounter missing");
        var context = StructurePieceSerializationContext.fromLevel(level);
        var piece = start.getPieces().getFirst();
        var copy = InnerworldStructures.DEEP_WORKS_PIECE.get().load(context, piece.createTag(context));
        helper.assertTrue(copy.getBoundingBox().equals(box) && copy.getOrientation() == piece.getOrientation(),
                "Saved works lost their layout");
        var entrance = ((DeepWorksPiece) piece).getEntrance();
        helper.assertTrue(((DeepWorksPiece) copy).getEntrance().equals(entrance)
                        && level.getFluidState(entrance).is(net.minecraft.tags.FluidTags.LAVA)
                        && level.getBlockState(entrance.below()).is(Blocks.POLISHED_BASALT),
                "Deep works entrance does not match its submerged steps after reload");
        var sounderPos = box.getCenter().above(2);
        level.setBlockAndUpdate(sounderPos, AeronauticsInnerworld.ECHO_SOUNDER.get().defaultBlockState());
        var sounder = (EchoSounderBlockEntity) level.getBlockEntity(sounderPos);
        int loaded = chunks.getLoadedChunksCount();
        sounder.scan();
        helper.assertTrue(entrance.equals(sounder.getRelay()) && chunks.getLoadedChunksCount() == loaded,
                "Deep works scan missed the site or loaded new chunks");
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenExecuteAfter(60, () -> {
            for (var pos : dryPositions) helper.assertTrue(level.getFluidState(pos).isEmpty(), "Recovery gallery flooded");
            level.removeBlock(sounderPos, false);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void gatesCoreDrillingAndPreservesReturnAndSupplyConnection(GameTestHelper helper) {
        var mantle = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var core = helper.getLevel().getServer().getLevel(Core.LEVEL);
        helper.assertTrue(core != null && core.dimensionType().bedWorks(), "Core camp dimension is unavailable");
        var controllerPos = new BlockPos(1024, 7, 512);
        var root = controllerPos.below();
        for (int x = 63; x <= 65; x++) for (int z = 31; z <= 33; z++) mantle.setChunkForced(x, z, true);
        for (var pos : BlockPos.betweenClosed(root.offset(-5, -5, -5), root.offset(5, 3, 5))) {
            mantle.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        mantle.setBlockAndUpdate(controllerPos, AeronauticsInnerworld.DRILL_CONTROLLER.get().defaultBlockState()
                .setValue(DrillControllerBlock.FACING, Direction.DOWN));
        mantle.setBlockAndUpdate(controllerPos.above(), AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN));
        mantle.setBlockAndUpdate(root, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().defaultBlockState());
        mantle.setBlockAndUpdate(root.west(), Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) mantle.getBlockEntity(root.west())).setItem(0, new ItemStack(Items.DIAMOND, 3));
        mantle.addFreshEntity(new SuperGlueEntity(mantle, SuperGlueEntity.span(root, root.west())));
        var controller = (DrillControllerBlockEntity) mantle.getBlockEntity(controllerPos);
        var sourcePos = controllerPos.east(3);
        mantle.setBlockAndUpdate(sourcePos, AeronauticsInnerworld.BORE_STATION.get().defaultBlockState());
        var source = (BoreStationBlockEntity) mantle.getBlockEntity(sourcePos);
        source.bind(GlobalPos.of(Mantle.LEVEL, controllerPos));
        var stopped = new BlockPos[1];
        helper.onEachTick(() -> {mantle.resetEmptyTime(); core.resetEmptyTime();});
        helper.startSequence().thenIdle(3).thenExecute(() ->
                ((CreativeMotorBlockEntity) mantle.getBlockEntity(controllerPos.above())).generatedSpeed.setValue(128))
                .thenWaitUntil(() -> helper.assertTrue(mantle.getEntitiesOfClass(SuperGlueEntity.class,
                        new net.minecraft.world.phys.AABB(root).inflate(2)).size() == 1, "Waiting for source glue to load"))
                .thenExecute(controller::toggleWork)
                .thenWaitUntil(() -> helper.assertTrue(controller.getStatus().equals("layer_needs_coupler"),
                        "Unupgraded drill did not stop at the core boundary: " + controller.getStatus()))
                .thenExecute(() -> {
                    helper.assertTrue(controller.movedContraption.level() == mantle, "Unupgraded head crossed layers");
                    helper.assertTrue(controller.movedContraption.getContraption().getBlocks().values().stream()
                            .anyMatch(info -> info.state().is(Blocks.CHEST)), "Initial assembly omitted its glued cargo");
                    stopped[0] = controller.movedContraption.blockPosition();
                    helper.assertTrue(controller.tryPlaceHead(), "Stopped head could not be upgraded");
                }).thenIdle(3).thenExecute(() -> {
                    var newRoot = stopped[0];
                    mantle.setBlockAndUpdate(newRoot.east(), AeronauticsInnerworld.DEEP_BORE_COUPLER.get().defaultBlockState());
                    mantle.setBlockAndUpdate(newRoot.north(), AeronauticsInnerworld.BORE_STATION.get().defaultBlockState()
                            .setValue(BoreStationBlock.FACING, Direction.EAST));
                    ((BoreStationBlockEntity) mantle.getBlockEntity(newRoot.north())).bind(GlobalPos.of(Mantle.LEVEL, controllerPos));
                    for (var pos : new BlockPos[] {newRoot, newRoot.west(), newRoot.east()}) {
                        if (!pos.equals(newRoot)) mantle.addFreshEntity(new SuperGlueEntity(mantle, SuperGlueEntity.span(newRoot, pos)));
                    }
                    // Reassemble the same blocks beneath the controller after fitting the upgrade.
                    for (var pos : new BlockPos[] {newRoot, newRoot.west(), newRoot.east(), newRoot.north()}) {
                        var state = mantle.getBlockState(pos);
                        var data = mantle.getBlockEntity(pos) == null ? null : mantle.getBlockEntity(pos).saveWithFullMetadata(mantle.registryAccess());
                        var moved = pos.offset(root.subtract(newRoot));
                        mantle.setBlockAndUpdate(moved, state);
                        if (data != null) mantle.getBlockEntity(moved).loadWithComponents(data, mantle.registryAccess());
                        if (mantle.getBlockEntity(pos) instanceof ChestBlockEntity chest) chest.clearContent();
                        mantle.removeBlock(pos, false);
                    }
                    for (var pos : new BlockPos[] {root.west(), root.east(), root.north()})
                        mantle.addFreshEntity(new SuperGlueEntity(mantle, SuperGlueEntity.span(root, pos)));
                    mantle.getEntitiesOfClass(SuperGlueEntity.class, new net.minecraft.world.phys.AABB(newRoot).inflate(2))
                            .forEach(net.minecraft.world.entity.Entity::discard);
                }).thenWaitUntil(() -> helper.assertTrue(mantle.getEntitiesOfClass(SuperGlueEntity.class,
                        new net.minecraft.world.phys.AABB(root).inflate(2)).size() >= 3, "Waiting for upgraded head glue"))
                .thenExecute(controller::toggleWork)
                .thenWaitUntil(() -> helper.assertTrue(controller.movedContraption != null && controller.movedContraption.level() == core,
                        "Upgraded drill has not reached the core: " + controller.getStatus()))
                .thenExecute(() -> {
                    var head = (DrillHeadEntity) controller.movedContraption;
                    helper.assertTrue(head.getMantleReturnPosition() != null && head.getMantleReturnPosition().getY() <= 2,
                            "Core crossing lost its mantle return anchor");
                    var saved = new CompoundTag();head.save(saved);
                    var copy = (DrillHeadEntity) EntityType.loadEntityRecursive(saved, core, entity -> entity);
                    helper.assertTrue(copy.getMantleReturnPosition().equals(head.getMantleReturnPosition()), "Return anchor did not persist");
                    copy.discard();
                    mantle.setBlockAndUpdate(head.getMantleReturnPosition().east(), Blocks.OBSIDIAN.defaultBlockState());
                    helper.assertTrue(controller.setMovementDirection(Direction.UP), "Core head could not select its return direction");
                    controller.toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(controller.getStatus().equals("layer_blocked"), "Obstructed mantle return did not stop"))
                .thenExecute(() -> {
                    var head = (DrillHeadEntity) controller.movedContraption;
                    helper.assertTrue(head.level() == core, "Obstructed return moved the drill");
                    var obstacle = head.getMantleReturnPosition().east();
                    helper.assertTrue(mantle.getBlockState(obstacle).is(Blocks.OBSIDIAN), "Return destroyed existing terrain");
                    mantle.removeBlock(obstacle, false);
                    controller.toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(controller.movedContraption.level() == mantle,
                        "Core head has not returned: " + controller.getStatus() + "; " + controller.movedContraption.position()))
                .thenExecute(() -> {
                    helper.assertTrue(controller.setMovementDirection(Direction.DOWN), "Returned head could not select core drilling");
                    controller.toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(controller.movedContraption.level() == core, "Returned head could not repeat the core crossing"))
                .thenExecute(() -> {
                    controller.toggleWork();
                }).thenWaitUntil(() -> helper.assertTrue(controller.movedContraption.getY() <= 126, "Head has not descended into core camp space"))
                .thenExecute(controller::requestPause)
                .thenWaitUntil(() -> helper.assertTrue(controller.getStatus().equals("paused"), "Core drill has not paused"))
                .thenExecute(() -> {
                    var head = (DrillHeadEntity) controller.movedContraption;
                    var destination = head.blockPosition().north();
                    var cargo = head.getContraption().getBlocks().entrySet().stream()
                            .filter(entry -> entry.getValue().state().is(Blocks.CHEST))
                            .map(entry -> head.blockPosition().offset(entry.getKey())).findFirst().orElse(null);
                    helper.assertTrue(cargo != null, "Deep head has no cargo block: " + head.getContraption().getBlocks());
                    helper.assertTrue(controller.tryPlaceHead(), "Core camp assembly could not be placed");
                    helper.assertTrue(source.getPartner() != null && source.getPartner().equals(GlobalPos.of(Core.LEVEL, destination)),
                            "Mantle supply station did not pair with the placed core station");
                    helper.assertTrue(core.getBlockEntity(cargo) instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND)
                                    && chest.getItem(0).getCount() == 3,
                            "Deep drilling lost carried cargo");
                    var target = (BoreStationBlockEntity) core.getBlockEntity(destination);
                    helper.assertTrue(target.getPartner().equals(GlobalPos.of(Mantle.LEVEL, sourcePos)), "Core return station points to the wrong layer");
                    for (int x = 63; x <= 65; x++) for (int z = 31; z <= 33; z++) mantle.setChunkForced(x, z, false);
                }).thenSucceed();
    }
}

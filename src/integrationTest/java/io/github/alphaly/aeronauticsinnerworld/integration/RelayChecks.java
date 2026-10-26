package io.github.alphaly.aeronauticsinnerworld.integration;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.InnerworldStructures;
import io.github.alphaly.aeronauticsinnerworld.world.structure.MantleRelayPiece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(AeronauticsInnerworld.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RelayChecks {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void generatesDryRelayAndRecoverySupplies(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "mantle_relay"));
        helper.assertTrue(structure != null, "Relay structure is not registered");
        var chunks = level.getChunkSource();
        var generator = chunks.getGenerator();
        var startChunk = new ChunkPos(48, 48);
        var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), chunks.randomState(),
                level.getServer().getStructureManager(), level.getSeed(), startChunk, 0, level, holder -> true);
        helper.assertTrue(start.isValid(), "Native relay generation returned no structure");
        var box = start.getBoundingBox();
        start.placeInChunk(level, level.structureManager(), generator, RandomSource.create(42), box, startChunk);
        level.getChunk(startChunk.x, startChunk.z).setStartForStructure(structure, start);
        int alloy = 0;
        int archives = 0;
        int chests = 0;
        int beacons = 0;
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(AeronauticsInnerworld.RELAY_BEACON.get())) {
                beacons++;
            }
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                chests++;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    var item = chest.getItem(slot);
                    if (item.is(AeronauticsInnerworld.RELIC_ALLOY.get())) {
                        alloy += item.getCount();
                    }
                    if (item.is(Items.WRITTEN_BOOK)) {
                        var record = item.get(DataComponents.WRITTEN_BOOK_CONTENT);
                        helper.assertTrue(record != null && record.pages().size() == 2, "Relay archive has no readable record");
                        archives++;
                    }
                }
            }
        }
        helper.assertTrue(chests == 2 && beacons == 1 && archives == 1 && alloy >= 7,
                "Relay supplies are incomplete: chests=" + chests + ", beacons=" + beacons + ", records=" + archives + ", alloy=" + alloy);
        var context = StructurePieceSerializationContext.fromLevel(level);
        var original = start.getPieces().getFirst();
        var restored = InnerworldStructures.RELAY_PIECE.get().load(context, original.createTag(context));
        helper.assertTrue(restored.getBoundingBox().equals(original.getBoundingBox())
                && restored.getOrientation() == original.getOrientation(), "Relay piece did not retain its saved layout");
        var entrance = ((MantleRelayPiece) original).getEntrance();
        helper.assertTrue(((MantleRelayPiece) restored).getEntrance().equals(entrance)
                        && level.getFluidState(entrance).is(FluidTags.LAVA)
                        && level.getBlockState(entrance.below()).is(Blocks.POLISHED_BASALT),
                "Relay entrance does not match its submerged steps after reload");
        var sounderPos = box.getCenter().above(2);
        level.setBlockAndUpdate(sounderPos, AeronauticsInnerworld.ECHO_SOUNDER.get().defaultBlockState());
        var sounder = (EchoSounderBlockEntity) level.getBlockEntity(sounderPos);
        int loaded = chunks.getLoadedChunksCount();
        sounder.scan();
        helper.assertTrue(entrance.equals(sounder.getRelay()), "Sounder did not identify the relay intake");
        helper.assertTrue(chunks.getLoadedChunksCount() == loaded, "Sounder generated or loaded chunks during a scan");
        var crafting = CraftingInput.of(3, 1, java.util.List.of(new ItemStack(AeronauticsInnerworld.RELIC_ALLOY.get()),
                new ItemStack(com.simibubi.create.AllItems.IRON_SHEET.get()), new ItemStack(Items.REDSTONE)));
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, crafting, level).orElseThrow();
        var supplies = recipe.value().assemble(crafting, level.registryAccess());
        helper.assertTrue(supplies.is(AeronauticsInnerworld.REPAIR_KIT.get()) && supplies.getCount() == 6,
                "Recovered alloy cannot replenish expedition repair supplies");
        var dry = new BlockPos(box.getCenter().getX(), box.minY() + 7, box.getCenter().getZ());
        helper.onEachTick(level::resetEmptyTime);
        helper.startSequence().thenExecuteAfter(60, () -> {
            helper.assertTrue(level.getFluidState(dry).isEmpty(), "Relay dry gallery flooded after generation");
            helper.assertTrue(level.getFluidState(new BlockPos(box.getCenter().getX(), box.minY() + 3, box.getCenter().getZ()))
                    .is(FluidTags.LAVA), "Relay lower intake is not open to the lava sea");
            level.setBlockAndUpdate(sounderPos, Blocks.AIR.defaultBlockState());
        }).thenSucceed();
    }
}

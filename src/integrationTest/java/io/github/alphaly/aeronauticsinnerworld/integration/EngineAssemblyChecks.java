package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.SimItems;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_industry")
@PrefixGameTestTemplate(false)
public final class EngineAssemblyChecks {
    @GameTest(template = "empty", timeoutTicks = 40000)
    public static void assemblesPortableEngineFromHandPower(GameTestHelper helper) {
        var line = new Assembly(helper);
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) {line.cleanUp();}
            public void testFailed(GameTestInfo test, GameTestRunner runner) {line.cleanUp();}
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(line::tick);
    }

    private static final class Assembly {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final FakePlayer player;
        private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        private final BlockPos depot, pressCrank, saw, sawCrank, bench;
        private int ticks, stage, step, attempts;

        Assembly(GameTestHelper helper) {
            this.helper = helper;level = helper.getLevel();
            depot = at(5, 2, 5);pressCrank = depot.above(2).east();saw = at(9, 2, 5);sawCrank = saw.east();bench = at(7, 2, 8);
            for (var pos : BlockPos.betweenClosed(at(3, 1, 3), at(11, 1, 9))) set(pos, Blocks.STONE.defaultBlockState());
            set(depot, AllBlocks.DEPOT.getDefaultState());
            set(depot.above(2), AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            set(pressCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            set(saw, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP)
                    .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
            set(saw.north(), AllBlocks.DEPOT.getDefaultState());set(saw.south(), AllBlocks.DEPOT.getDefaultState());
            set(sawCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            set(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
            player = new FakePlayer(level, new GameProfile(UUID.fromString("7d32fbc4-ccae-4f20-97b8-4ca010a49128"), "EngineBuilder"));
            player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(at(7, 2, 6)));
            player.getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
            player.getInventory().add(new ItemStack(Items.BLAST_FURNACE));
            depot().setHeldItem(take(Items.IRON_INGOT));
        }

        private BlockPos at(int x, int y, int z) {return helper.absolutePos(new BlockPos(x, y, z));}
        private void set(BlockPos pos, BlockState state) {original.putIfAbsent(pos.immutable(), level.getBlockState(pos));level.setBlockAndUpdate(pos, state);}
        private DepotBlockEntity depot() {return (DepotBlockEntity) level.getBlockEntity(depot);}
        private SawBlockEntity saw() {return (SawBlockEntity) level.getBlockEntity(saw);}
        private int count(Item item) {return player.getInventory().countItem(item);}
        private ItemStack take(Item item) {
            int slot = -1;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                if (player.getInventory().getItem(i).is(item)) {slot = i;break;}
            }
            helper.assertTrue(slot >= 0, "Engine assembly exhausted its prepared ingredient: " + item);
            return player.getInventory().removeItem(slot, 1);
        }

        private void use(BlockPos pos, Direction face) {
            int empty = -1;
            for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).isEmpty()) {empty = slot;break;}
            helper.assertTrue(empty >= 0, "Engine builder has no empty hand slot");player.getInventory().selected = empty;
            player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.49)), face, pos, false));
        }

        private void cut(ItemStack input) {
            helper.assertTrue(saw().inventory.isEmpty() && saw().inventory.insertItem(0, input, false).isEmpty(),
                    "Saw rejected its next assembly input");stage = 1;
        }

        private void beginAttempt() {step = 0;attempts++;cut(take(AllItems.IRON_SHEET.get()));}

        void tick() {
            if (++ticks % 10 == 0) {use(pressCrank, Direction.EAST);use(sawCrank, Direction.EAST);}
            if (stage == 0 && depot().getHeldItem().is(AllItems.IRON_SHEET.get())) {
                use(depot, Direction.UP);
                if (count(AllItems.IRON_SHEET.get()) < 2) depot().setHeldItem(take(Items.IRON_INGOT));
                else beginAttempt();
            } else if (stage == 1) {
                var outputPos = saw.offset(BlockPos.containing(saw().getItemMovementVec()));
                var output = (DepotBlockEntity) level.getBlockEntity(outputPos);
                var result = output.getHeldItem();
                if (result.isEmpty()) return;
                checkStep(result, ++step);
                use(outputPos, Direction.UP);
                depot().setHeldItem(take(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()));stage = 2;
            } else if (stage == 2) {
                var result = depot().getHeldItem().copy();var progress = result.get(AllDataComponents.SEQUENCED_ASSEMBLY);
                if (progress != null && progress.step() == step) return;
                if (step < 15) {
                    if (result.isEmpty()) return;
                    checkStep(result, ++step);use(depot, Direction.UP);cut(take(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()));
                    LogUtils.getLogger().info("Native engine assembly attempt {} completed round {}", attempts, step / 2);
                } else if (!result.isEmpty()) {
                    helper.assertTrue(!result.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()), "Completed engine sequence retained an unfinished item");
                    use(depot, Direction.UP);
                    if (count(SimItems.ENGINE_ASSEMBLY.get()) > 0) {craftEngine();helper.succeed();}
                    else {
                        LogUtils.getLogger().info("Engine assembly attempt {} yielded {}; continuing normal production", attempts, result.getItem());
                        if (count(AllItems.IRON_SHEET.get()) >= 2) beginAttempt();
                        else {depot().setHeldItem(take(Items.IRON_INGOT));stage = 0;}
                    }
                }
            }
        }

        private void checkStep(ItemStack stack, int expected) {
            var progress = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            helper.assertTrue(stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) && stack.getCount() == 1
                    && progress != null && progress.step() == expected, "Native cutting/pressing lost engine sequence step " + expected + ": " + stack + ", " + progress);
        }

        private void craftEngine() {
            player.containerMenu = new CraftingMenu(1, player.getInventory(), ContainerLevelAccess.create(level, bench));
            var menu = player.containerMenu;
            Map.of(2, AllItems.IRON_SHEET.get(), 5, SimItems.ENGINE_ASSEMBLY.get(), 8, Items.BLAST_FURNACE).forEach((target, item) -> {
                int source = -1;
                for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).getItem().is(item)) {source = slot;break;}
                helper.assertTrue(source >= 0, "Engine workbench lost ingredient " + item);
                menu.clicked(source, 0, ClickType.PICKUP, player);menu.clicked(target, 1, ClickType.PICKUP, player);menu.clicked(source, 0, ClickType.PICKUP, player);
            });
            var engine = SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem();
            helper.assertTrue(menu.getSlot(0).getItem().is(engine) && menu.getSlot(0).getItem().getCount() == 1, "Native portable engine recipe did not assemble");
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);closeMenu();
            helper.assertTrue(count(engine) == 1 && count(SimItems.ENGINE_ASSEMBLY.get()) == 0
                    && count(Items.BLAST_FURNACE) == 0 && count(AllItems.IRON_SHEET.get()) == 0, "Engine crafting consumed the wrong ingredients");
            LogUtils.getLogger().info("First portable engine produced with native hand-powered cutting/pressing: rounds=8, attempts={}, ticks={}", attempts, ticks);
        }

        private void closeMenu() {
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.removed(player);
            player.containerMenu = player.inventoryMenu;
        }

        void cleanUp() {
            closeMenu();player.getInventory().clearContent();original.keySet().forEach(pos -> Clearable.tryClear(level.getBlockEntity(pos)));
            original.forEach(level::setBlockAndUpdate);
        }
    }
}

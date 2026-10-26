package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
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
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_industry")
@PrefixGameTestTemplate(false)
public final class IndustryChecks {
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void processesExpeditionSuppliesAndDeepUpgrade(GameTestHelper helper) {
        var line = new Production(helper);
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo test) { }
            public void testPassed(GameTestInfo test, GameTestRunner runner) {line.cleanUp();}
            public void testFailed(GameTestInfo test, GameTestRunner runner) {line.cleanUp();}
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) { }
        });
        helper.onEachTick(line::tick);
        helper.startSequence()
                .thenWaitUntil(line::checkPressedAndSmelted)
                .thenExecute(line::stockBasin)
                .thenExecuteAfter(60, line::checkColdAndFuel)
                .thenWaitUntil(line::checkRecast)
                .thenExecute(line::craftUpgrades)
                .thenSucceed();
    }

    private static final class Production {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final FakePlayer player;
        private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        private final BlockPos burner, basin, mixer, mixerCrank, depot, press, pressCrank, furnace, bench;
        private int ticks, plates;

        Production(GameTestHelper helper) {
            this.helper = helper;level = helper.getLevel();
            burner = at(4, 1, 4);basin = burner.above();mixer = basin.above(2);mixerCrank = at(5, 5, 5);
            depot = at(9, 2, 4);press = depot.above(2);pressCrank = press.east();furnace = at(9, 2, 8);bench = at(5, 2, 8);
            for (var pos : BlockPos.betweenClosed(at(3, 0, 3), at(11, 0, 9))) set(pos, Blocks.STONE.defaultBlockState());
            for (var pos : new BlockPos[] {depot.below(), furnace.below(), bench.below(), at(7, 1, 6)}) set(pos, Blocks.STONE.defaultBlockState());
            set(burner, AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
            set(basin, AllBlocks.BASIN.getDefaultState());set(mixer, AllBlocks.MECHANICAL_MIXER.getDefaultState());
            set(mixerCrank.below(), AllBlocks.LARGE_COGWHEEL.getDefaultState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
            set(mixerCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP));
            set(depot, AllBlocks.DEPOT.getDefaultState());
            set(press, AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            set(pressCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
            set(furnace, Blocks.FURNACE.defaultBlockState());set(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
            player = new FakePlayer(level, new GameProfile(UUID.fromString("d3b5a8e6-59a7-4640-91c1-201f9c677fb0"), "FoundryWorker"));
            player.setGameMode(GameType.SURVIVAL);player.setPos(Vec3.atBottomCenterOf(at(7, 2, 6)));
            ((DepotBlockEntity) level.getBlockEntity(depot)).setHeldItem(new ItemStack(Items.COPPER_INGOT));
            var oven = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnace);
            oven.setItem(0, new ItemStack(AeronauticsInnerworld.RAW_HARMONIC.get(), 4));oven.setItem(1, new ItemStack(Items.COAL));
            player.getInventory().add(new ItemStack(AeronauticsInnerworld.RELIC_ALLOY.get(), 3));
            player.getInventory().add(new ItemStack(AllItems.PRECISION_MECHANISM.get(), 2));
            player.getInventory().add(new ItemStack(AeronauticsInnerworld.PRESSURE_MATRIX.get()));
            player.getInventory().add(new ItemStack(Items.GLASS, 2));player.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        }

        private BlockPos at(int x, int y, int z) {return helper.absolutePos(new BlockPos(x, y, z));}
        private void set(BlockPos pos, BlockState state) {original.putIfAbsent(pos.immutable(), level.getBlockState(pos));level.setBlockAndUpdate(pos, state);}
        private BasinBlockEntity basin() {return (BasinBlockEntity) level.getBlockEntity(basin);}
        private int count(Item item) {return player.getInventory().countItem(item);}

        void tick() {
            if (++ticks % 10 == 0) {use(pressCrank, Direction.EAST);use(mixerCrank, Direction.UP);}
            var work = (DepotBlockEntity) level.getBlockEntity(depot);
            if (plates < 2 && work.getHeldItem().is(AllItems.COPPER_SHEET.get())) {
                helper.assertTrue(work.getHeldItem().getCount() == 1, "Press changed the copper sheet yield");
                use(depot, Direction.UP);plates++;
                if (plates == 1) work.setHeldItem(new ItemStack(Items.COPPER_INGOT));
            }
        }

        private void emptyHand() {
            for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).isEmpty()) {player.getInventory().selected = slot;return;}
            throw new IllegalStateException("Factory worker has no empty hotbar slot");
        }

        private void use(BlockPos pos, Direction face) {
            emptyHand();useHeld(pos, face);
        }

        private void useHeld(BlockPos pos, Direction face) {
            player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.49)), face, pos, false));
        }

        void checkPressedAndSmelted() {
            helper.assertTrue(plates == 2 && count(AllItems.COPPER_SHEET.get()) == 2, "Waiting for two hand-powered copper sheets");
            var oven = (AbstractFurnaceBlockEntity) level.getBlockEntity(furnace);
            helper.assertTrue(oven.getItem(0).isEmpty() && oven.getItem(1).isEmpty()
                    && oven.getItem(2).is(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) && oven.getItem(2).getCount() == 4,
                    "Waiting for four normally smelted harmonic crystals");
        }

        void stockBasin() {
            player.containerMenu = ((AbstractFurnaceBlockEntity) level.getBlockEntity(furnace)).createMenu(1, player.getInventory(), player);
            helper.assertTrue(player.containerMenu instanceof FurnaceMenu, "Furnace did not open its native menu");
            player.containerMenu.clicked(2, 0, ClickType.QUICK_MOVE, player);closeMenu();
            helper.assertTrue(count(AeronauticsInnerworld.HARMONIC_CRYSTAL.get()) == 4, "Furnace output was not recovered normally");
            var inventory = basin().getInputInventory();
            inventory.setStackInSlot(0, new ItemStack(AeronauticsInnerworld.RELIC_ALLOY.get()));
            inventory.setStackInSlot(1, player.getInventory().removeItem(player.getInventory().findSlotMatchingItem(new ItemStack(AeronauticsInnerworld.HARMONIC_CRYSTAL.get())), 2));
            inventory.setStackInSlot(2, new ItemStack(AeronauticsInnerworld.MOLTEN_SCALE.get(), 2));
            inventory.setStackInSlot(3, new ItemStack(Items.IRON_INGOT, 2));basin().notifyChangeOfContents();
        }

        void checkColdAndFuel() {
            var drive = (KineticBlockEntity) level.getBlockEntity(mixer);
            helper.assertTrue(Math.abs(drive.getSpeed()) >= 32, "Hand crank and large cog do not power the mixer: " + drive.getSpeed());
            helper.assertTrue(basin().getOutputInventory().isEmpty() && basin().getInputInventory().getStackInSlot(0).getCount() == 1
                    && basin().getInputInventory().getStackInSlot(1).getCount() == 2, "Unheated recasting consumed its inputs");
            emptyHand();player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COAL));useHeld(burner, Direction.SOUTH);
            helper.assertTrue(player.getMainHandItem().isEmpty(), "Blaze burner did not consume normal coal");
        }

        void checkRecast() {
            helper.assertTrue(basin().getInputInventory().isEmpty(), "Waiting for heated recasting to consume every ingredient");
            var output = basin().getOutputInventory();
            int alloy = 0;
            for (int slot = 0; slot < output.getSlots(); slot++) {
                var stack = output.getStackInSlot(slot);
                helper.assertTrue(stack.isEmpty() || stack.is(AeronauticsInnerworld.RELIC_ALLOY.get()), "Recasting produced unexpected output");
                alloy += stack.getCount();
            }
            helper.assertTrue(alloy == 2, "Waiting for two physically mixed alloy ingots");
        }

        private void craft(Item result, int amount, Map<Integer, Item> ingredients) {
            player.containerMenu = new CraftingMenu(2, player.getInventory(), ContainerLevelAccess.create(level, bench));
            var menu = player.containerMenu;
            ingredients.forEach((target, item) -> {
                int source = -1;
                for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).getItem().is(item)) {source = slot;break;}
                helper.assertTrue(source >= 0, "Missing processed ingredient " + item);
                menu.clicked(source, 0, ClickType.PICKUP, player);menu.clicked(target, 1, ClickType.PICKUP, player);menu.clicked(source, 0, ClickType.PICKUP, player);
            });
            helper.assertTrue(menu.getSlot(0).getItem().is(result) && menu.getSlot(0).getItem().getCount() == amount, "Native workbench output does not match " + result);
            menu.clicked(0, 0, ClickType.QUICK_MOVE, player);closeMenu();
        }

        void craftUpgrades() {
            use(basin, Direction.UP);
            helper.assertTrue(count(AeronauticsInnerworld.RELIC_ALLOY.get()) == 5 && basin().isEmpty(),
                    "Mixed alloy was not recovered with the basin's normal empty-hand interaction");
            craft(AeronauticsInnerworld.COOLANT_CELL.get(), 2, Map.of(2, AllItems.COPPER_SHEET.get(), 8, AllItems.COPPER_SHEET.get(),
                    4, Items.GLASS, 6, Items.GLASS, 5, Items.WATER_BUCKET));
            helper.assertTrue(count(Items.BUCKET) == 1, "Coolant crafting did not return its bucket");
            craft(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get(), 2, Map.of(1, AeronauticsInnerworld.RELIC_ALLOY.get(), 2, AeronauticsInnerworld.COOLANT_CELL.get()));
            var alloy = AeronauticsInnerworld.RELIC_ALLOY.get();var crystal = AeronauticsInnerworld.HARMONIC_CRYSTAL.get();
            craft(AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem(), 1, Map.of(1, alloy, 3, alloy, 7, alloy, 9, alloy,
                    2, crystal, 8, crystal, 4, AllItems.PRECISION_MECHANISM.get(), 6, AllItems.PRECISION_MECHANISM.get(), 5, AeronauticsInnerworld.PRESSURE_MATRIX.get()));
            helper.assertTrue(count(AeronauticsInnerworld.DEEP_BORE_COUPLER.get().asItem()) == 1
                    && count(AeronauticsInnerworld.STABILIZED_COOLANT_CELL.get()) == 2 && count(AeronauticsInnerworld.COOLANT_CELL.get()) == 1,
                    "Processed supplies or deep upgrade were lost");
            for (var item : new Item[] {alloy, crystal, AllItems.COPPER_SHEET.get(), AllItems.PRECISION_MECHANISM.get(),
                    AeronauticsInnerworld.PRESSURE_MATRIX.get(), Items.GLASS, Items.WATER_BUCKET})
                helper.assertTrue(count(item) == 0, "Workbench did not consume the exact processing ingredients: " + item);
            LogUtils.getLogger().info("Hand-powered industrial progression passed: two copper sheets, four smelted crystals, heated alloy, bucket return and deep coupler; ticks={}", ticks);
        }

        void cleanUp() {
            closeMenu();player.getInventory().clearContent();
            original.keySet().forEach(pos -> Clearable.tryClear(level.getBlockEntity(pos)));
            original.forEach(level::setBlockAndUpdate);
        }

        private void closeMenu() {
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.removed(player);
            player.containerMenu = player.inventoryMenu;
        }
    }
}

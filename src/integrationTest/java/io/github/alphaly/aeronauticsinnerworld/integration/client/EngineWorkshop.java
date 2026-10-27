package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import dev.simulated_team.simulated.index.SimBlocks;
import dev.simulated_team.simulated.index.SimItems;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class EngineWorkshop {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos depot, pressCrank, pressHopper, saw, sawCrank, hopper, bench, pressStand, sawStand;
    private final BoundingBox bounds;
    private final int goal;
    private final Map<BlockPos, BlockState> original = new LinkedHashMap<>();
    private final long started;
    private volatile CoreMazeWalk walk;
    private volatile BlockPos target, stand;
    private volatile Item input;
    private volatile int stage;
    private int observed = -1, frames, sequence, attempts;
    private long since;
    private long progressed;
    private int processed = -1;
    private boolean clicked, filled;
    private boolean viewsChecked;
    private int ironRemaining;
    private int produced, batchSize, inputAmount, platesNeeded, craftTarget, craftBatch;
    private final Map<Item, Integer> finalBefore = new LinkedHashMap<>();
    volatile boolean complete;

    EngineWorkshop(ServerPlayer player, BlockPos origin, BlockPos bench, BoundingBox bounds, int goal) {
        this.player = player;level = player.serverLevel();this.bench = bench;this.bounds = bounds;
        if (goal < 1 || goal > 7) throw new IllegalArgumentException("Engine count must be between one and seven");
        this.goal = goal;
        depot = origin;pressCrank = depot.above(2).east();pressHopper = depot.south();saw = origin.east(4);sawCrank = saw.east();hopper = saw.above();
        pressStand = depot.east(2).south();sawStand = goal > 1 ? saw.east().north() : saw.east(2).south();
        set(depot, AllBlocks.DEPOT.getDefaultState());
        set(depot.above(2), AllBlocks.MECHANICAL_PRESS.getDefaultState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        set(pressCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        set(saw, AllBlocks.MECHANICAL_SAW.getDefaultState().setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        set(saw.north(), AllBlocks.DEPOT.getDefaultState());set(saw.south(), AllBlocks.DEPOT.getDefaultState());
        set(sawCrank, AllBlocks.HAND_CRANK.getDefaultState().setValue(BlockStateProperties.FACING, Direction.EAST));
        set(hopper, Blocks.HOPPER.defaultBlockState());
        if (goal > 1) set(pressHopper, Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.NORTH));
        started = level.getGameTime();beginBatch();
    }

    private void set(BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) != null) throw new IllegalStateException("Engine workshop overlaps existing equipment");
        original.put(pos, level.getBlockState(pos));level.setBlockAndUpdate(pos, state);
    }
    private DepotBlockEntity depot() {return (DepotBlockEntity) level.getBlockEntity(depot);}
    private BlockPos output(Level world) {return saw.offset(BlockPos.containing(((SawBlockEntity) world.getBlockEntity(saw)).getItemMovementVec()));}
    private int count(Item item) {return player.getInventory().countItem(item);}
    private boolean streamedPress() {return goal > 1 && input == SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();}
    private int pendingInput() {return progressCount(sequence);}
    private int progressCount(int step) {
        int count = 0;
        for (var stack : player.getInventory().items) {
            var data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) && data != null && data.step() == step) count += stack.getCount();
        }
        return count;
    }
    int ironRemaining() {return ironRemaining;}
    private void next(int value, BlockPos stand, BlockPos block) {
        stage = value;since = level.getGameTime();progressed = since;processed = -1;target = block;
        this.stand = stand;
        walk = new CoreMazeWalk(level, bounds, player.blockPosition(), stand, false);
        LogUtils.getLogger().info("Client engine stage {}: sequence={}, attempt={}, target={}", value, sequence, attempts, block);
    }
    private void beginBatch() {
        batchSize = goal == 1 ? 1 : 2 * (goal - produced) + 2;platesNeeded = batchSize + goal - produced;
        preparePlates();
    }
    private void preparePlates() {
        if (count(AllItems.IRON_SHEET.get()) >= platesNeeded) {sequence = 0;attempts++;beginSaw();return;}
        input = Items.IRON_INGOT;inputAmount = goal == 1 ? 1 : platesNeeded - count(AllItems.IRON_SHEET.get());next(0, pressStand, depot);
    }
    private void beginSaw() {input = sequence == 0 ? AllItems.IRON_SHEET.get() : SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();inputAmount = batchSize;next(3, sawStand, hopper);}

    void tickClient(Minecraft client) {
        if (complete) return;
        client.options.keyUp.setDown(false);client.options.keyDown.setDown(false);client.options.keyJump.setDown(false);
        if (observed != stage) {
            observed = stage;frames = 0;clicked = false;filled = false;ProbeConstruction.release(client);
            if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
        }
        if (stage != 3 && stage != 6 && !(stage == 0 && streamedPress())
                && client.player.containerMenu != client.player.inventoryMenu) {client.player.closeContainer();return;}
        if (!walk.arrived()) {walk.tickClient(client);return;}
        if (client.player.position().distanceToSqr(Vec3.atBottomCenterOf(stand)) > .5) return;
        if (goal > 1 && client.player.containerMenu == client.player.inventoryMenu
                && (client.player.getFoodData().getFoodLevel() <= 16 || client.player.isUsingItem() && client.player.getMainHandItem().is(Items.BREAD))) {
            client.player.setXRot(-80);
            if (!client.player.getMainHandItem().is(Items.BREAD)) {
                for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(Items.BREAD)) {
                    if (slot < 9) client.player.getInventory().selected = slot;
                    else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}
                    break;
                }
            }
            if (!client.player.getMainHandItem().is(Items.BREAD)) throw new IllegalStateException("Engine workshop exhausted its prepared food");
            client.options.keyUse.setDown(true);if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);return;
        }
        ProbeConstruction.release(client);frames++;
        if (stage == 0) {
            if (streamedPress()) feed(client);
            else if (!clicked && frames == 5) {takeAmount(client, input, inputAmount);use(client, depot);clicked = true;}
        } else if (stage == 1 || stage == 4) {
            if (frames % 8 == 0) {DeepDrillConnection.empty(client);use(client, target);}
            if (stage == 1 && streamedPress() && frames % 8 == 4 && advanced(((DepotBlockEntity) client.level.getBlockEntity(depot)).getHeldItem(), sequence + 1)) {
                DeepDrillConnection.empty(client);use(client, depot);
            }
            if (stage == 4 && goal > 1 && frames % 8 == 4) {
                var pos = output(client.level);
                if (client.level.getBlockEntity(pos) instanceof DepotBlockEntity output && !output.getHeldItem().isEmpty()) {
                    DeepDrillConnection.empty(client);use(client, pos);
                }
            }
        } else if (stage == 2 || stage == 5) {
            if (!clicked && frames == 5) {DeepDrillConnection.empty(client);use(client, target);clicked = true;}
        } else if (stage == 3) feed(client);
        else if (stage == 6) craft(client);
    }

    static void takeAmount(Minecraft client, Item item, int amount) {
        DeepDrillConnection.empty(client);int hotbar = client.player.getInventory().selected;
        int source = -1;
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item) && client.player.getInventory().getItem(slot).getCount() >= amount) {source = slot;break;}
        if (source < 0) throw new IllegalStateException("Engine workshop exhausted " + item);
        if (client.player.getInventory().getItem(source).getCount() == amount && source < 9) {client.player.getInventory().selected = source;return;}
        int inventorySlot = source < 9 ? 36 + source : source;
        int destination = 36 + hotbar;
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, inventorySlot, 0, ClickType.PICKUP, client.player);
        for (int i = 0; i < amount; i++) client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, destination, 1, ClickType.PICKUP, client.player);
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, inventorySlot, 0, ClickType.PICKUP, client.player);
    }

    private void feed(Minecraft client) {
        if (!(client.player.containerMenu instanceof HopperMenu menu)) {
            if (frames % 10 == 0) {
                if (input == SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) DeepDrillConnection.empty(client);else takeAmount(client, input, inputAmount);
                use(client, target);
            }return;
        }
        if (clicked) return;
        for (int slot = 5; slot < menu.slots.size(); slot++) {
            var stack = menu.getSlot(slot).getItem();var data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            boolean unfinished = input == SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();
            if (!stack.is(input) || (unfinished ? data == null || data.step() != sequence : stack.getCount() != inputAmount)) continue;
            boolean space = false;for (int i = 0; i < 5; i++) space |= menu.getSlot(i).getItem().isEmpty();
            if (!space) break;
            client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);clicked = true;
            if (!unfinished) return;
        }
        if (!clicked) throw new IllegalStateException("Engine hopper lost its prepared workpieces: " + inputAmount + ", sequence=" + sequence);
    }

    private void craft(Minecraft client) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 == 0) {DeepDrillConnection.empty(client);use(client, bench);}return;
        }
        if (!filled) {
            Map.of(2, AllItems.IRON_SHEET.get(), 5, SimItems.ENGINE_ASSEMBLY.get(), 8, Items.BLAST_FURNACE).forEach((slot, item) -> {
                int source = -1;
                for (int i = 10; i < 46; i++) if (menu.getSlot(i).getItem().is(item)) {source = i;break;}
                if (source < 0) throw new IllegalStateException("Engine crafting lost " + item);
                client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
                for (int i = 0; i < craftBatch; i++) client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 1, ClickType.PICKUP, client.player);
                client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
            });filled = true;return;
        }
        if (!clicked && menu.getSlot(0).getItem().is(SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem())) {
            client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);clicked = true;
        }
    }

    private void use(Minecraft client, BlockPos pos) {
        var hit = hit(client.level, client.player, client.player.getEyePosition(), pos);
        var delta = hit.getLocation().subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private static BlockHitResult hit(Level level, Player player, Vec3 eye, BlockPos pos) {
        boolean onDepot = level.getBlockEntity(pos) instanceof DepotBlockEntity;
        var shape = level.getBlockState(pos).getShape(level, pos);
        var points = onDepot ? java.util.List.of(new Vec3(pos.getX() + .5, pos.getY() + shape.bounds().maxY - .01, pos.getZ() + .5))
                : shape.toAabbs().stream().map(box -> box.getCenter().add(Vec3.atLowerCornerOf(pos))).toList();
        for (var point : points) {
            var hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && (!onDepot || hit.getDirection() == Direction.UP)
                    && hit.getLocation().distanceTo(eye) <= 4.5) return hit;
        }
        throw new IllegalStateException("Engine workshop has no visible input face: target=" + pos + ", eye=" + eye);
    }

    boolean advance() {
        if (complete) return true;
        if (!viewsChecked) {
            Map.of(depot, pressStand, pressCrank, pressStand, sawCrank, sawStand, hopper, sawStand,
                    saw.north(), saw.north().east(), saw.south(), saw.south().east(), bench, bench.south())
                    .forEach((block, stand) -> hit(level, player, Vec3.atBottomCenterOf(stand).add(0, 1.62, 0), block));
            viewsChecked = true;
            if (goal > 1) hit(level, player, Vec3.atBottomCenterOf(pressStand).add(0, 1.62, 0), pressHopper);
            LogUtils.getLogger().info("Client engine workshop checked all native interaction approaches");
        }
        if (walk.arrived() && player.position().distanceToSqr(Vec3.atBottomCenterOf(stand)) > .5) {
            LogUtils.getLogger().info("Client engine returning to operation approach: player={}, stand={}", player.position(), stand);
            walk = new CoreMazeWalk(level, bounds, player.blockPosition(), stand, false);
        }
        walk.advance(player.position());
        if (!player.isAlive() || level.getGameTime() - started > 16000 + 2000L * (goal - 1)
                || level.getGameTime() - (stage == 1 || stage == 4 ? progressed : since) > (stage == 1 || stage == 4 ? 600 : 300))
            throw new IllegalStateException("Engine workshop stalled: stage=" + stage + ", sequence=" + sequence + ", attempts=" + attempts
                    + ", player=" + player.position() + ", depot=" + depot().getHeldItem() + ", arrived=" + walk.arrived());
        if (stage == 0 && depot().getHeldItem().is(input)) next(1, pressStand, pressCrank);
        else if (stage == 1) {
            int retrieved = streamedPress() ? sequence == 15 ? finalBefore.entrySet().stream().mapToInt(entry -> count(entry.getKey()) - entry.getValue()).sum()
                    : progressCount(sequence + 1) : 0;
            int ready = processedOnDepot() + retrieved;observeProgress(ready);
            if (ready == inputAmount) next(2, pressStand, depot);
            else if (streamedPress() && pendingInput() > 0 && depot().getHeldItem().isEmpty()
                    && ((net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(pressHopper)).isEmpty()) next(0, pressStand, pressHopper);
        } else if (stage == 2 && depot().getHeldItem().isEmpty()) {
            if (input == Items.IRON_INGOT) {
                preparePlates();
            } else {
                sequence++;
                if (sequence < 16) {LogUtils.getLogger().info("Client engine attempt {} completed round {}", attempts, sequence / 2);beginSaw();}
                else if (count(SimItems.ENGINE_ASSEMBLY.get()) > 0) {
                    if (count(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) != 0) throw new IllegalStateException("Native bulk pressing left unfinished workpieces");
                    craftBatch = Math.min(goal - produced, count(SimItems.ENGINE_ASSEMBLY.get()));craftTarget = produced + craftBatch;
                    next(6, bench.south(), bench);
                }
                else {
                    LogUtils.getLogger().info("Client engine attempt {} produced a native failure result; retrying", attempts);
                    beginBatch();
                }
            }
        } else if (stage == 3 && !((SawBlockEntity) level.getBlockEntity(saw)).inventory.isEmpty()) next(4, sawStand, sawCrank);
        else if (stage == 4) {
            var pos = output(level);var held = ((DepotBlockEntity) level.getBlockEntity(pos)).getHeldItem();
            int ready = (advanced(held, sequence + 1) ? held.getCount() : 0)
                    + (goal > 1 ? progressCount(sequence + 1) : 0);observeProgress(ready);
            if (ready == batchSize) next(5, pos.east(), pos);
            else if (goal > 1 && input == SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get() && pendingInput() > 0
                    && ((SawBlockEntity) level.getBlockEntity(saw)).inventory.isEmpty()
                    && ((net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(hopper)).isEmpty()) next(3, sawStand, hopper);
        } else if (stage == 5 && ((DepotBlockEntity) level.getBlockEntity(target)).getHeldItem().isEmpty()) {
            sequence++;input = SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get();
            if (sequence == 15) {
                finalBefore.clear();
                for (var item : java.util.List.of(SimItems.ENGINE_ASSEMBLY.get(), AllItems.IRON_SHEET.get(), Items.IRON_NUGGET,
                        AllBlocks.INDUSTRIAL_IRON_BLOCK.asItem(), Items.IRON_BARS, Items.IRON_HELMET)) finalBefore.put(item, count(item));
            }
            next(0, pressStand, streamedPress() ? pressHopper : depot);
        } else if (stage == 6 && count(SimBlocks.PORTABLE_ENGINES.get(DyeColor.RED).asItem()) == craftTarget) {
            if (count(Items.BLAST_FURNACE) != goal - craftTarget) throw new IllegalStateException("Native engine crafting consumed the wrong furnace count");
            produced = craftTarget;player.closeContainer();
            LogUtils.getLogger().info("Client engine production: engines={}/{}, batches={}", produced, goal, attempts);
            if (produced < goal) {beginBatch();return false;}
            ironRemaining = count(Items.IRON_INGOT);complete = true;player.closeContainer();
            LogUtils.getLogger().info("Client hand-powered engine production complete: engines={}, batches={}, iron remaining={}, bread remaining={}, ticks={}", produced, attempts, ironRemaining, count(Items.BREAD), level.getGameTime() - started);
            return true;
        }
        return false;
    }

    private void observeProgress(int value) {if (value != processed) {
        processed = value;progressed = level.getGameTime();
        if (goal > 1 && value % 4 == 0) LogUtils.getLogger().info("Client engine processing: stage={}, completed={}/{}, sequence={}", stage, value, inputAmount, sequence);
    }}
    private int processedOnDepot() {
        var behaviour = depot().getBehaviour(DepotBehaviour.TYPE);
        var stacks = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for (int slot = 0; slot < behaviour.itemHandler.getSlots(); slot++) stacks.add(behaviour.itemHandler.getStackInSlot(slot));
        int total = 0;
        for (var stack : stacks) if (input == Items.IRON_INGOT ? stack.is(AllItems.IRON_SHEET.get()) : advanced(stack, sequence + 1)) total += stack.getCount();
        return total;
    }

    private boolean advanced(net.minecraft.world.item.ItemStack stack, int expected) {
        if (stack.isEmpty()) return false;
        if (expected == 16) return !stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get());
        var data = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
        if (stack.is(SimItems.INCOMPLETE_ENGINE_ASSEMBLY.get()) && data != null && data.step() == expected) return true;
        if (stack.is(input)) return false;
        throw new IllegalStateException("Client engine sequence lost step " + expected + ": " + stack + ", data=" + data);
    }

    void cleanUp() {
        player.closeContainer();original.keySet().forEach(pos -> Clearable.tryClear(level.getBlockEntity(pos)));original.forEach(level::setBlockAndUpdate);
    }
}

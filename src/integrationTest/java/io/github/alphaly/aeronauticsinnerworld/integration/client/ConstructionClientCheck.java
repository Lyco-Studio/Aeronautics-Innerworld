package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class ConstructionClientCheck {
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> step, cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static int ticks, observed = -1, frames;
    private static boolean capturedPause, oldPause, finished, inputs;
    private static boolean engineProduction() {return Boolean.getBoolean("innerworld.constructionEngine");}
    private static int engineCount() {return Integer.getInteger("innerworld.engineCount", 1);}

    @SubscribeEvent
    public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.constructionClientCheck") || finished) return;
        var client = Minecraft.getInstance();
        if (!capturedPause) {oldPause = client.options.pauseOnLostFocus;capturedPause = true;client.options.pauseOnLostFocus = false;}
        try {
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                finished = true;
                try {cleanup.join();} finally {client.options.pauseOnLostFocus = oldPause;client.options.save();}
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve("construction-check.result"), engineProduction() ? engineCount() > 1 ? "engine-batch-verified" : "engine-verified" : "verified");
                LogUtils.getLogger().info("Native survival construction passed: workbench recipes, placement, fuel, glue, excavation and unloading");
                client.stop();return;
            }
            if (++ticks > (engineProduction() ? 21000 + 2000L * (engineCount() - 1) : 5000)) throw new IllegalStateException("Construction timed out at " + observed);
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {var id = client.player.getUUID();setup = server.submit(() -> new Scene(server.getPlayerList().getPlayer(id)));return;}
            if (!setup.isDone()) return;
            scene = setup.join();
            if (scene.stage == -1) {
                scene.workshop.tickClient(client);
                if (step != null) {if (!step.isDone()) return;step.join();step = null;}
                step = server.submit(() -> scene.advance(0));return;
            }
            if (observed != scene.stage) {
                release(client);observed = scene.stage;frames = 0;inputs = false;
                if (client.player.containerMenu != client.player.inventoryMenu) client.player.closeContainer();
            }
            frames++;
            if (frames > 1200) throw new IllegalStateException("Construction stage timed out: " + observed);
            if (observed == -2) scene.walk.tickClient(client);
            else if (observed == 0 || observed == 1) craft(client);
            else if (observed == 4 || observed == 8 || observed == 10 || observed == 12 || observed == 18) scene.walk.tickClient(client);
            else if (observed == 2) place(client, AeronauticsInnerworld.DRILL_CONTROLLER.get().asItem(), scene.controller.below(), Direction.UP, true);
            else if (observed == 3) place(client, scene.engineItem, scene.controller, Direction.NORTH, false);
            else if (observed == 5) place(client, AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().asItem(), scene.controller, Direction.SOUTH, true);
            else if (observed == 6) place(client, Items.CHEST, scene.root, Direction.EAST, true);
            else if (observed == 7) chest(client, false);
            else if (observed == 9 || observed == 11) {
                equip(client, AllItems.SUPER_GLUE.get());look(client, Vec3.atCenterOf(observed == 9 ? scene.root : scene.root.east()));
                if (frames == 10) KeyMapping.click(client.options.keyUse.getKey());
            } else if (observed == 13) {
                equip(client, Items.COAL);look(client, Vec3.atCenterOf(scene.engine));
                if (frames == 10) use(client, scene.engine, null);
            } else if (observed == 14 || observed == 16) {
                DeepDrillConnection.empty(client);look(client, Vec3.atCenterOf(scene.controller));
                if (frames == 10) use(client, scene.controller, null);
            } else if (observed == 17) {
                equip(client, AllItems.WRENCH.get());sneak(client, true);look(client, Vec3.atCenterOf(scene.controller));
                if (frames == 10) use(client, scene.controller, null);
            } else if (observed == 19) chest(client, true);
            else if (observed == 20) {
                if (frames == 10) Screenshot.grab(client.gameDirectory, "survival-construction.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Construction screenshot: {}", message.getString()));
                if (frames > 15) {cleanup = server.submit(scene::cleanUp);return;}
            }
            if (step != null) {if (!step.isDone()) return;step.join();step = null;}
            int frame = frames;step = server.submit(() -> scene.advance(frame));
        } catch (Exception error) {
            if (client.player != null) release(client);
            if (finished) throw new IllegalStateException("Construction check failed", error);
            Screenshot.grab(client.gameDirectory, "construction-failure.png", client.getMainRenderTarget(),
                    message -> LogUtils.getLogger().info("Construction failure screenshot: {}", message.getString()));
            failure = new IllegalStateException("Construction check failed", error);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void craft(Minecraft client) {
        if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
            if (frames % 10 == 0) use(client, scene.bench, null);return;
        }
        if (!inputs) {
            if (observed == 0) {
                ingredient(client, menu, Items.REDSTONE, 2);ingredient(client, menu, com.simibubi.create.AllBlocks.SHAFT.asItem(), 4, 6);
                ingredient(client, menu, com.simibubi.create.AllBlocks.ANDESITE_CASING.asItem(), 5);ingredient(client, menu, Items.IRON_INGOT, 8);
            } else {
                ingredient(client, menu, Items.IRON_INGOT, 2, 4, 6, 8);
                ingredient(client, menu, com.simibubi.create.AllBlocks.ANDESITE_CASING.asItem(), 5);
            }
            inputs = true;
        }
        var output = observed == 0 ? AeronauticsInnerworld.DRILL_CONTROLLER.get().asItem() : AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().asItem();
        if (frames % 5 == 0 && menu.getSlot(0).getItem().is(output))
            client.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, client.player);
    }

    private static void ingredient(Minecraft client, CraftingMenu menu, Item item, int... targets) {
        int source = -1;
        for (int slot = 10; slot < 46; slot++) if (menu.getSlot(slot).getItem().is(item)) {source = slot;break;}
        if (source < 0) throw new IllegalStateException("Missing construction ingredient " + item);
        client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
        for (int target : targets) client.gameMode.handleInventoryMouseClick(menu.containerId, target, 1, ClickType.PICKUP, client.player);
        if (!menu.getCarried().isEmpty()) client.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, client.player);
    }

    private static void equip(Minecraft client, Item item) {
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {
                client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);
                client.player.getInventory().selected = 0;
            }
            return;
        }
    }

    private static void place(Minecraft client, Item item, BlockPos against, Direction side, boolean crouch) {
        equip(client, item);sneak(client, crouch);
        look(client, Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(side.getNormal()).scale(.499)));
        if (frames == 10) use(client, against, side);
    }

    private static void chest(Minecraft client, boolean unloading) {
        DeepDrillConnection.empty(client);
        var pos = unloading ? scene.placedChest : scene.root.east();
        if (!(client.player.containerMenu instanceof ChestMenu menu)) {
            look(client, Vec3.atCenterOf(pos));if (frames % 10 == 0) use(client, pos, null);return;
        }
        if (frames % 5 != 0) return;
        for (int slot = unloading ? 0 : 27; slot < (unloading ? 27 : 63); slot++) {
            var stack = menu.getSlot(slot).getItem();
            if (stack.is(Items.COPPER_INGOT) || unloading && stack.is(Items.COBBLED_DEEPSLATE)) {
                client.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, client.player);break;
            }
        }
    }

    private static void use(Minecraft client, BlockPos pos, Direction face) {
        var point = Vec3.atCenterOf(pos);
        if (face != null) point = point.add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499));
        var hit = client.level.clip(new ClipContext(client.player.getEyePosition(), point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)
                || face != null && hit.getDirection() != face || client.player.getEyePosition().distanceTo(hit.getLocation()) > 4.5)
            throw new IllegalStateException("Construction target is not reachable: " + pos + ", hit=" + hit);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
    }

    private static void look(Minecraft client, Vec3 point) {
        var delta = point.subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, delta.horizontalDistance())));
    }
    private static void sneak(Minecraft client, boolean down) {
        client.options.keyShift.setDown(down);
        if (client.player.isShiftKeyDown() == down) return;
        client.player.setShiftKeyDown(down);
        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, down
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
    }
    private static void release(Minecraft client) {client.options.keyUp.setDown(false);client.options.keyUse.setDown(false);sneak(client, false);}

    private static final class Scene {
        private final ServerPlayer player;
        private final TeamPlayerState old;
        private final int oldSlot;
        private final ServerLevel level;
        private final BlockPos base, controller, root, engine, bench;
        private final Map<BlockPos, BlockState> terrain = new HashMap<>();
        private final Set<UUID> oldGlue = new HashSet<>();
        private final BoundingBox bounds;
        private final EngineWorkshop workshop;
        private final Item engineItem;
        private final long started;
        private volatile int stage;
        private volatile CoreMazeWalk walk;
        private volatile BlockPos placedChest;

        private Scene(ServerPlayer player) {
            this.player = player;old = TeamPlayerState.capture(player);oldSlot = player.getInventory().selected;level = player.serverLevel();
            var candidate = player.blockPosition().offset(24, 0, 0);
            while (true) {
                boolean clear = true;
                for (var pos : BlockPos.betweenClosed(candidate.offset(-3, -1, -2), candidate.offset(7, 4, 9)))
                    if (!level.isEmptyBlock(pos)) {clear = false;break;}
                if (clear) break;
                candidate = candidate.above(6);
                if (candidate.getY() > level.getMaxBuildHeight() - 6) throw new IllegalStateException("No empty construction site");
            }
            base = candidate;controller = base.offset(0, 1, 2);root = controller.south();engine = controller.north();bench = base.east(2);
            bounds = new BoundingBox(base.getX() - 3, base.getY(), base.getZ() - 2, base.getX() + 7, base.getY(), base.getZ() + 9);
            level.getEntitiesOfClass(SuperGlueEntity.class, new AABB(Vec3.atLowerCornerOf(base.offset(-3, -1, -2)), Vec3.atLowerCornerOf(base.offset(8, 4, 10)))).forEach(entity -> oldGlue.add(entity.getUUID()));
            for (var pos : BlockPos.betweenClosed(base.offset(-3, -1, -2), base.offset(7, 4, 9))) terrain.put(pos.immutable(), level.getBlockState(pos));
            for (int x = -3; x <= 7; x++) for (int z = -2; z <= 9; z++) {
                level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(base.offset(x, 4, z), (x == -1 || x == 5) && (z == 0 || z == 7)
                        ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState());
                if (x == -3 || x == 7 || z == -2 || z == 9) for (int y = 0; y < 4; y++)
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(controller.below(), Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(bench, Blocks.CRAFTING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(root.south(), Blocks.DEEPSLATE.defaultBlockState());level.setBlockAndUpdate(root.south(2), Blocks.DEEPSLATE.defaultBlockState());
            player.setGameMode(GameType.SURVIVAL);player.getInventory().clearContent();player.getInventory().selected = 0;
            player.getInventory().setItem(1, com.simibubi.create.AllBlocks.ANDESITE_CASING.asStack(2));
            player.getInventory().setItem(2, new ItemStack(Items.IRON_INGOT, 5));player.getInventory().setItem(3, com.simibubi.create.AllBlocks.SHAFT.asStack(2));
            player.getInventory().setItem(4, new ItemStack(Items.REDSTONE));
            engineItem = SimBlocks.PORTABLE_ENGINES.get(engineProduction() ? DyeColor.RED : DyeColor.BROWN).asItem();
            if (!engineProduction()) player.getInventory().setItem(5, new ItemStack(engineItem));
            else {player.getInventory().setItem(11, new ItemStack(Items.IRON_INGOT, 64));player.getInventory().setItem(12, new ItemStack(Items.BLAST_FURNACE, engineCount()));
                if (engineCount() > 1) player.getInventory().setItem(13, new ItemStack(Items.BREAD, 64));}
            player.getInventory().setItem(6, new ItemStack(Items.CHEST));player.getInventory().setItem(7, AllItems.SUPER_GLUE.asStack());
            player.getInventory().setItem(8, new ItemStack(Items.COAL, 4));player.getInventory().setItem(9, AllItems.WRENCH.asStack());
            player.getInventory().setItem(10, new ItemStack(Items.COPPER_INGOT, 17));
            player.teleportTo(level, base.getX() + .5, base.getY(), base.getZ() + .5, 0, 0);player.containerMenu.broadcastChanges();
            workshop = engineProduction() ? new EngineWorkshop(player, base.offset(-2, 0, 7), bench, bounds, engineCount()) : null;
            if (workshop != null) stage = -1;
            started = level.getGameTime();
        }

        private DrillControllerBlockEntity machine() {return (DrillControllerBlockEntity) level.getBlockEntity(controller);}
        private int minedRocks() {
            var inventory = machine().movedContraption.getContraption().getStorage().getAllItems();
            int rocks = 0;
            for (int slot = 0; slot < inventory.getSlots(); slot++) {
                var stack = inventory.getStackInSlot(slot);
                if (stack.is(Items.COBBLED_DEEPSLATE)) rocks += stack.getCount();
            }
            return rocks;
        }
        private void next(int value) {stage = value;LogUtils.getLogger().info("Native construction stage {}: player={}", value, player.position());}
        private void walking(int value, BlockPos destination) {walk = new CoreMazeWalk(level, bounds, player.blockPosition(), destination, false);next(value);}
        private void advance(int frame) {
            if (!player.isAlive() || level.getGameTime() - started > (engineProduction() ? 20000 + 2000L * (engineCount() - 1) : 4000)) throw new IllegalStateException("Construction failed at " + stage);
            if (stage == -1) {if (workshop.advance()) walking(-2, base);return;}
            if (stage == -2) {walk.advance(player.position());if (walk.arrived()) next(0);return;}
            if (stage == 0 && player.getInventory().countItem(AeronauticsInnerworld.DRILL_CONTROLLER.get().asItem()) == 1) next(1);
            else if (stage == 1 && player.getInventory().countItem(AeronauticsInnerworld.MULTI_AXIS_CUTTER.get().asItem()) == 1) {
                if (player.getInventory().countItem(Items.IRON_INGOT) != (workshop == null ? 0 : workshop.ironRemaining() - 5) || player.getInventory().countItem(com.simibubi.create.AllBlocks.ANDESITE_CASING.asItem()) != 0
                        || player.getInventory().countItem(com.simibubi.create.AllBlocks.SHAFT.asItem()) != 0 || player.getInventory().countItem(Items.REDSTONE) != 0)
                    throw new IllegalStateException("Native workbench did not consume the exact construction ingredients");
                next(2);
            } else if (stage == 2 && level.getBlockEntity(controller) instanceof DrillControllerBlockEntity) {
                if (level.getBlockState(controller).getValue(DrillControllerBlock.FACING) != Direction.SOUTH) throw new IllegalStateException("Crouched controller placement selected the wrong facing");next(3);
            } else if (stage == 3 && level.getBlockEntity(engine) instanceof PortableEngineBlockEntity) {
                if (level.getBlockState(engine).getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.SOUTH)
                    throw new IllegalStateException("Engine did not connect to the controller shaft: " + level.getBlockState(engine) + ", crouched=" + player.isShiftKeyDown());
                if (player.getInventory().countItem(engineItem) != (engineProduction() ? engineCount() - 1 : 0))
                    throw new IllegalStateException("Native engine placement changed the production batch");
                walking(4, base.offset(2, 0, 4));
            } else if (stage == 4 || stage == 8 || stage == 10 || stage == 12 || stage == 18) {
                walk.advance(player.position());
                if (walk.arrived()) next(switch (stage) {case 4 -> 5;case 8 -> 9;case 10 -> 11;case 12 -> 13;default -> 19;});
            } else if (stage == 5 && level.getBlockState(root).is(AeronauticsInnerworld.MULTI_AXIS_CUTTER.get())) next(6);
            else if (stage == 6 && level.getBlockEntity(root.east()) instanceof ChestBlockEntity) next(7);
            else if (stage == 7 && player.getInventory().countItem(Items.COPPER_INGOT) == 0
                    && ((ChestBlockEntity) level.getBlockEntity(root.east())).countItem(Items.COPPER_INGOT) == 17) walking(8, base.offset(-2, 0, 3));
            else if (stage == 9 && frame >= 20) walking(10, base.offset(2, 0, 4));
            else if (stage == 11 && !level.getEntitiesOfClass(SuperGlueEntity.class, SuperGlueEntity.span(root, root.east())).isEmpty()) {
                if (player.getInventory().getItem(7).getDamageValue() == 0) throw new IllegalStateException("Native glue selection did not consume durability");
                walking(12, base.offset(2, 0, 1));
            } else if (stage == 13 && Math.abs(machine().getSpeed()) == 32 && player.getInventory().countItem(Items.COAL) == 0) next(14);
            else if (stage == 14 && machine().movedContraption != null) {
                if (machine().movedContraption.getContraption().getBlocks().size() != 2) throw new IllegalStateException("Native glue failed to carry both construction blocks");next(15);
            } else if (stage == 15 && minedRocks() == 2) next(16);
            else if (stage == 16 && machine().getStatus().equals("paused")) next(17);
            else if (stage == 17 && !machine().running) {
                for (var pos : BlockPos.betweenClosed(root, root.offset(1, 0, 5))) if (level.getBlockEntity(pos) instanceof ChestBlockEntity) placedChest = pos.immutable();
                if (placedChest == null) throw new IllegalStateException("Construction head lost its placed chest");
                walking(18, new BlockPos(base.getX() + 2, base.getY(), placedChest.getZ()));
            } else if (stage == 19 && player.getInventory().countItem(Items.COPPER_INGOT) == 17 && player.getInventory().countItem(Items.COBBLED_DEEPSLATE) == 2) next(20);
        }

        private void cleanUp() {
            if (workshop != null) workshop.cleanUp();
            player.closeContainer();
            if (machine() != null) {var head = machine().movedContraption;machine().disassemble();if (head != null) head.discard();}
            terrain.forEach((pos, before) -> {
                var entity = level.getBlockEntity(pos);
                if (entity instanceof ChestBlockEntity chest) chest.clearContent();
                if (entity instanceof PortableEngineBlockEntity motor) motor.inventory.clearContent();
                level.setBlockAndUpdate(pos, before);
            });
            level.getEntitiesOfClass(SuperGlueEntity.class, new AABB(Vec3.atLowerCornerOf(base.offset(-3, -1, -2)), Vec3.atLowerCornerOf(base.offset(8, 4, 10))))
                    .stream().filter(entity -> !oldGlue.contains(entity.getUUID())).forEach(net.minecraft.world.entity.Entity::discard);
            old.restore();player.getInventory().selected = oldSlot;player.connection.send(new ClientboundSetCarriedItemPacket(oldSlot));
        }
    }
}

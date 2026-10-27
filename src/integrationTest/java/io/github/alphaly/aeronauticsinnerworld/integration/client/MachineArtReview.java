package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class MachineArtReview {
    private static CompletableFuture<Gallery> setup;
    private static CompletableFuture<Void> cleanup;
    private static Gallery gallery;
    private static int ticks, viewTicks;
    private static int oldFov;
    private static HumanoidArm oldArm;
    private static boolean oldPause, oldGui, finished;
    private static RuntimeException failure;

    static void tick(Minecraft client) {
        if (finished) return;
        if (++ticks == 1) {
            oldPause = client.options.pauseOnLostFocus;
            oldGui = client.options.hideGui;
            oldFov = client.options.fov().get();
            oldArm = client.options.mainHand().get();
            client.options.pauseOnLostFocus = false;
            client.options.hideGui = true;
            client.options.fov().set(50);
        }
        if (client.screen instanceof PauseScreen) client.setScreen(null);
        if (cleanup != null) {
            if (!cleanup.isDone()) return;
            try {cleanup.join();}
            finally {
                client.setScreen(null);
                client.options.pauseOnLostFocus = oldPause;
                client.options.hideGui = oldGui;
                client.options.fov().set(oldFov);
                client.options.mainHand().set(oldArm);
                client.options.save();
                finished = true;
            }
            if (failure != null) throw failure;
            try {Files.writeString(client.gameDirectory.toPath().resolve("probe-check.result"), "art-reviewed");}
            catch (java.io.IOException e) {throw new IllegalStateException(e);}
            LogUtils.getLogger().info("Art review captured equipment relief, hull tiling, hatch hardware, worn armor and native item icons");
            client.stop();
            return;
        }
        if (client.getSingleplayerServer() == null || client.level == null || client.player == null) return;
        try {
            if (ticks > 1200) throw new IllegalStateException("Machine art review timed out");
            if (ticks < 60) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {
                var identity = client.player.getUUID();
                setup = server.submit(() -> new Gallery(server.getPlayerList().getPlayer(identity)));
                return;
            }
            if (!setup.isDone()) return;
            if (gallery == null) gallery = setup.join();
            if (client.player.position().distanceTo(gallery.view) > 1) return;
            client.player.lookAt(Anchor.EYES, gallery.target);
            if (++viewTicks == 50) capture(client, "innerworld-machines-polished.png");
            if (viewTicks == 55) {
                server.submit(() -> gallery.viewMachines());
            }
            if (viewTicks == 105) capture(client, "innerworld-machines-detail.png");
            if (viewTicks == 110) server.submit(() -> gallery.viewInstruments());
            if (viewTicks == 160) capture(client, "innerworld-instruments-detail.png");
            if (viewTicks == 165) server.submit(() -> gallery.viewHull());
            if (viewTicks == 215) capture(client, "innerworld-hull-polished.png");
            if (viewTicks == 220) server.submit(() -> gallery.viewArmor());
            if (viewTicks == 270) capture(client, "innerworld-armor-detail.png");
            if (viewTicks == 275) {
                client.options.hideGui = false;
                client.options.fov().set(70);
                client.options.mainHand().set(HumanoidArm.RIGHT);
                server.submit(() -> gallery.viewHands());
            }
            if (viewTicks == 325) capture(client, "innerworld-tools-right.png");
            if (viewTicks == 330) client.options.mainHand().set(HumanoidArm.LEFT);
            if (viewTicks == 380) capture(client, "innerworld-tools-left.png");
            if (viewTicks == 385) {
                client.options.hideGui = true;
                client.options.mainHand().set(oldArm);
                client.setScreen(new ToolArtScreen());
            }
            if (viewTicks == 410) capture(client, "innerworld-tool-models.png");
            if (viewTicks == 415) client.setScreen(new ItemArtScreen());
            if (viewTicks == 440) capture(client, "innerworld-items-polished.png");
            if (viewTicks == 445) {
                client.setScreen(null);
                cleanup = server.submit(gallery::restore);
            }
        } catch (RuntimeException exception) {
            failure = exception;
            cleanup = client.getSingleplayerServer().submit(() -> {if (gallery != null) gallery.restore();});
        }
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(),
                message -> LogUtils.getLogger().info("Machine art review: {}", message.getString()));
    }

    private static final class ToolArtScreen extends Screen {
        ToolArtScreen() {super(Component.literal("Field instrument models"));}

        @Override
        public boolean isPauseScreen() {return false;}

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0,0,width,height,0xff2f363d);
            int cardWidth=Math.min(186,(width-30)/2), top=(height-212)/2;
            graphics.drawCenteredString(font,"INNERWORLD / FIELD INSTRUMENTS",width/2,top,0xffd0d7db);
            var tools=List.of(AeronauticsInnerworld.DRILL_REMOTE.toStack(),AeronauticsInnerworld.CORE_SURVEYOR.toStack());
            for (int i=0;i<tools.size();i++) {
                int x=width/2+(i-1)*cardWidth;
                graphics.fill(x+3,top+20,x+cardWidth-3,top+202,0xff353e46);
                graphics.drawCenteredString(font,tools.get(i).getHoverName(),x+cardWidth/2,top+29,0xffbcc5cb);
                graphics.flush();
                graphics.pose().pushPose();
                graphics.pose().translate(x+cardWidth/2,top+117,150);
                graphics.pose().scale(150,-150,150);
                graphics.pose().mulPose(Axis.XP.rotationDegrees(12));
                graphics.pose().mulPose(Axis.YP.rotationDegrees(25));
                Lighting.setupFor3DItems();
                minecraft.getItemRenderer().renderStatic(tools.get(i),ItemDisplayContext.FIXED,15728880,0,
                        graphics.pose(),graphics.bufferSource(),minecraft.level,0);
                graphics.flush();
                graphics.pose().popPose();
            }
        }
    }

    private static final class ItemArtScreen extends Screen {
        private final List<ItemStack> items = new ArrayList<>(List.of(
                AeronauticsInnerworld.COOLANT_CELL.toStack(), AeronauticsInnerworld.STABILIZED_COOLANT_CELL.toStack(),
                AeronauticsInnerworld.REPAIR_KIT.toStack(), AeronauticsInnerworld.DRILL_REMOTE.toStack(),
                AeronauticsInnerworld.CORE_SURVEYOR.toStack(), AeronauticsInnerworld.RAW_HARMONIC.toStack(),
                AeronauticsInnerworld.HARMONIC_CRYSTAL.toStack(), AeronauticsInnerworld.RELIC_ALLOY.toStack(),
                AeronauticsInnerworld.PRESSURE_MATRIX.toStack(), AeronauticsInnerworld.CORE_HEART.toStack(),
                AeronauticsInnerworld.HEATWEAVE_HELMET.toStack(), AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.toStack(),
                AeronauticsInnerworld.PRESSURE_BOLT.toStack(), AeronauticsInnerworld.MOLTEN_SCALE.toStack()));
        private final List<ItemStack> references = List.of(new ItemStack(Items.IRON_INGOT),
                AllItems.BRASS_INGOT.asStack(), AllItems.PRECISION_MECHANISM.asStack(),
                AllItems.GOGGLES.asStack(), AllItems.LINKED_CONTROLLER.asStack());

        ItemArtScreen() {
            super(Component.literal("Item art review"));
            var client = Minecraft.getInstance();
            client.level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID,"expedition_taskbook"))
                    .ifPresent(recipe -> items.add(recipe.value().getResultItem(client.level.registryAccess()).copy()));
            for (var stack : items) if (client.getItemRenderer().getModel(stack,client.level,client.player,0)
                    == client.getModelManager().getMissingModel()) throw new IllegalStateException("Missing item model: " + stack);
        }

        @Override
        public boolean isPauseScreen() {return false;}

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0,0,width,height,0xff2f363d);
            int cellWidth = Math.min(84,(width-20)/5), left = (width-5*cellWidth)/2;
            int top = (height-218)/2;
            graphics.drawCenteredString(font,"INNERWORLD  /  ITEM STUDIES",width/2,top,0xffd0d7db);
            graphics.drawCenteredString(font,"16 px + 32 px",width/2,top+12,0xff96a4ae);
            for (int i=0;i<items.size();i++) {
                int x=left+i%5*cellWidth, y=top+28+i/5*47;
                graphics.fill(x+1,y,x+cellWidth-1,y+45,0xff353e46);
                icon(graphics,items.get(i),x+5,y+10,1);
                icon(graphics,items.get(i),x+cellWidth-37,y+2,2);
                var label=font.plainSubstrByWidth(items.get(i).getHoverName().getString(),cellWidth-4);
                graphics.drawCenteredString(font,label,x+cellWidth/2,y+35,0xffbcc5cb);
            }
            graphics.drawCenteredString(font,"CREATE / MINECRAFT",width/2,top+174,0xff96a4ae);
            for (int i=0;i<references.size();i++) icon(graphics,references.get(i),left+i*cellWidth+(cellWidth-32)/2,top+187,2);
        }

        private static void icon(GuiGraphics graphics, ItemStack stack, int x, int y, int scale) {
            graphics.pose().pushPose();
            graphics.pose().translate(x,y,0);
            graphics.pose().scale(scale,scale,1);
            graphics.renderItem(stack,0,0);
            graphics.pose().popPose();
        }
    }

    private static final class Gallery {
        private final ServerPlayer player;
        private final Vec3 oldPosition;
        private final float oldYaw, oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final ItemStack oldMain, oldOff;
        private final List<BlockPos> placed = new ArrayList<>();
        private final BlockPos origin;
        private final long oldDayTime;
        private ArmorStand armor;
        private volatile Vec3 view, target;

        Gallery(ServerPlayer player) {
            this.player = player;
            oldPosition = player.position();
            oldYaw = player.getYRot();oldPitch = player.getXRot();
            oldMode = player.gameMode.getGameModeForPlayer();oldFlying = player.getAbilities().flying;
            oldMain = player.getMainHandItem().copy();oldOff = player.getOffhandItem().copy();
            var level = player.serverLevel();
            oldDayTime = level.getDayTime();
            origin = new BlockPos(player.getBlockX(), level.getMaxBuildHeight() - 30, player.getBlockZ());
            for (var pos : BlockPos.betweenClosed(origin.offset(-2,0,-2), origin.offset(27,10,3))) {
                if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Art review needs an empty sky area");
            }
            try {
                level.setDayTime(6000);
                for (int x = -2; x <= 27; x++) for (int z = -2; z <= 3; z++)
                    place(x,0,z, (z == -2 ? Blocks.SEA_LANTERN : Blocks.POLISHED_DEEPSLATE).defaultBlockState());
                for (int x = -1; x <= 14; x++) for (int y = 1; y <= 9; y++)
                    place(x,y,2, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                var devices = List.of(AeronauticsInnerworld.DRILL_CONTROLLER.get(), AeronauticsInnerworld.MOLTEN_THRUSTER.get(),
                        AeronauticsInnerworld.MANTLE_BALLAST.get(), AeronauticsInnerworld.CABIN_REGULATOR.get(),
                        AeronauticsInnerworld.ECHO_SOUNDER.get(), AeronauticsInnerworld.MANTLE_SAMPLER.get(),
                        AeronauticsInnerworld.PRESSURE_CANNON.get());
                for (int i = 0; i < devices.size(); i++) {
                    var block = devices.get(i);
                    place(i < 4 ? i * 4 : (i - 4) * 4 + 2, i < 4 ? 1 : 4, 0,
                            block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH));
                }
                var references = List.of(AllBlocks.ANDESITE_CASING.get(), AllBlocks.GEARBOX.get(),
                        AllBlocks.FLUID_TANK.get(), AllBlocks.COPPER_CASING.get());
                for (int i = 0; i < references.size(); i++)
                    place(i*4,7,0,references.get(i).defaultBlockState());
                for (int x = 19; x <= 25; x++) for (int y = 1; y <= 4; y++)
                    place(x,y,0,AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState());
                for (int x = 20; x <= 21; x++) for (int y = 2; y <= 3; y++)
                    place(x,y,0,AeronauticsInnerworld.MANTLE_VIEWPORT.get().defaultBlockState());
                for (int y = 1; y <= 2; y++)
                    place(24,y,0,AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState());
                place(24,3,0,AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState()
                        .setValue(BlockStateProperties.OPEN,true));
                place(22,1,0,AeronauticsInnerworld.DAMAGED_HULL.get().defaultBlockState());
                place(17,1,0,AllBlocks.ANDESITE_CASING.get().defaultBlockState());
                place(17,2,0,AllBlocks.COPPER_CASING.get().defaultBlockState());
                armor = new ArmorStand(level,origin.getX()+16.5,origin.getY()+1,origin.getZ()+.5);
                armor.setNoGravity(true);armor.setInvulnerable(true);armor.setShowArms(true);armor.setNoBasePlate(true);
                armor.setYRot(180);armor.setYBodyRot(180);armor.setYHeadRot(180);
                armor.setItemSlot(EquipmentSlot.HEAD,AeronauticsInnerworld.HEATWEAVE_HELMET.toStack());
                armor.setItemSlot(EquipmentSlot.CHEST,AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.toStack());
                armor.setItemSlot(EquipmentSlot.MAINHAND,AeronauticsInnerworld.DRILL_REMOTE.toStack());
                level.addFreshEntity(armor);
                view = Vec3.atLowerCornerOf(origin).add(10.5,3.3,-18);
                target = Vec3.atLowerCornerOf(origin).add(6.5,4,.5);
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(level, view.x,view.y,view.z,0,0);
            } catch (RuntimeException exception) {restore();throw exception;}
        }

        private void place(int x, int y, int z, BlockState state) {
            var pos = origin.offset(x,y,z);
            player.serverLevel().setBlockAndUpdate(pos,state);
            placed.add(pos);
        }

        private void viewHull() {
            target = Vec3.atLowerCornerOf(origin).add(22.5,2.1,.5);
            view = Vec3.atLowerCornerOf(origin).add(24.5,1.3,-8);
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void viewMachines() {
            target = Vec3.atLowerCornerOf(origin).add(6.5,1.6,.5);
            view = Vec3.atLowerCornerOf(origin).add(9.5,2.1,-7.8);
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void viewInstruments() {
            target = Vec3.atLowerCornerOf(origin).add(6.5,4.6,.5);
            view = Vec3.atLowerCornerOf(origin).add(8.5,4.8,-6.2);
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void viewArmor() {
            target = Vec3.atLowerCornerOf(origin).add(16.5,1.9,.5);
            view = Vec3.atLowerCornerOf(origin).add(18,1,-4.5);
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void viewHands() {
            target = Vec3.atLowerCornerOf(origin).add(8,4.62,.5);
            view = Vec3.atLowerCornerOf(origin).add(8,3,-10);
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;player.onUpdateAbilities();
            player.setItemSlot(EquipmentSlot.MAINHAND,AeronauticsInnerworld.DRILL_REMOTE.toStack());
            player.setItemSlot(EquipmentSlot.OFFHAND,AeronauticsInnerworld.CORE_SURVEYOR.toStack());
            player.containerMenu.broadcastChanges();
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void restore() {
            if (armor != null) armor.discard();
            for (var pos : placed) player.serverLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            player.serverLevel().setDayTime(oldDayTime);
            player.teleportTo(player.serverLevel(),oldPosition.x,oldPosition.y,oldPosition.z,oldYaw,oldPitch);
            player.setGameMode(oldMode);player.getAbilities().flying = oldFlying;player.onUpdateAbilities();
            player.setItemSlot(EquipmentSlot.MAINHAND,oldMain);player.setItemSlot(EquipmentSlot.OFFHAND,oldOff);
            player.containerMenu.broadcastChanges();
        }
    }
}

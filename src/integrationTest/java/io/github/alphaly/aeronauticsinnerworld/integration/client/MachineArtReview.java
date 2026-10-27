package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
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
    private static boolean oldPause, oldGui, finished;
    private static RuntimeException failure;

    static void tick(Minecraft client) {
        if (finished) return;
        if (++ticks == 1) {
            oldPause = client.options.pauseOnLostFocus;
            oldGui = client.options.hideGui;
            oldFov = client.options.fov().get();
            client.options.pauseOnLostFocus = false;
            client.options.hideGui = true;
            client.options.fov().set(50);
        }
        if (client.screen instanceof PauseScreen) client.setScreen(null);
        if (cleanup != null) {
            if (!cleanup.isDone()) return;
            try {cleanup.join();}
            finally {
                client.options.pauseOnLostFocus = oldPause;
                client.options.hideGui = oldGui;
                client.options.fov().set(oldFov);
                client.options.save();
                finished = true;
            }
            if (failure != null) throw failure;
            try {Files.writeString(client.gameDirectory.toPath().resolve("probe-check.result"), "art-reviewed");}
            catch (java.io.IOException e) {throw new IllegalStateException(e);}
            LogUtils.getLogger().info("Machine art review captured equipment relief, hull tiling and pressure hatch hardware");
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
            if (viewTicks == 110) server.submit(() -> gallery.viewHull());
            if (viewTicks == 160) capture(client, "innerworld-hull-polished.png");
            if (viewTicks == 165) cleanup = server.submit(gallery::restore);
        } catch (RuntimeException exception) {
            failure = exception;
            cleanup = client.getSingleplayerServer().submit(() -> {if (gallery != null) gallery.restore();});
        }
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(),
                message -> LogUtils.getLogger().info("Machine art review: {}", message.getString()));
    }

    private static final class Gallery {
        private final ServerPlayer player;
        private final Vec3 oldPosition;
        private final float oldYaw, oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final List<BlockPos> placed = new ArrayList<>();
        private final BlockPos origin;
        private volatile Vec3 view, target;

        Gallery(ServerPlayer player) {
            this.player = player;
            oldPosition = player.position();
            oldYaw = player.getYRot();oldPitch = player.getXRot();
            oldMode = player.gameMode.getGameModeForPlayer();oldFlying = player.getAbilities().flying;
            var level = player.serverLevel();
            origin = new BlockPos(player.getBlockX(), level.getMaxBuildHeight() - 30, player.getBlockZ());
            for (var pos : BlockPos.betweenClosed(origin.offset(-2,0,-2), origin.offset(27,10,3))) {
                if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Art review needs an empty sky area");
            }
            try {
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
                place(22,1,0,AeronauticsInnerworld.DAMAGED_HULL.get().defaultBlockState());
                place(17,1,0,AllBlocks.ANDESITE_CASING.get().defaultBlockState());
                place(17,2,0,AllBlocks.COPPER_CASING.get().defaultBlockState());
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
            view = Vec3.atLowerCornerOf(origin).add(9.5,.4,-6.5);
            player.teleportTo(player.serverLevel(),view.x,view.y,view.z,0,0);
        }

        private void restore() {
            for (var pos : placed) player.serverLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            player.teleportTo(player.serverLevel(),oldPosition.x,oldPosition.y,oldPosition.z,oldYaw,oldPitch);
            player.setGameMode(oldMode);player.getAbilities().flying = oldFlying;player.onUpdateAbilities();
        }
    }
}

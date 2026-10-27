package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

final class CoreMazeArtReview {
    private static final String[] VIEWS = {"entrance", "workshop", "archive", "junction", "relay", "cooling", "isolation", "arena"};
    private static final double[][] CAMERAS = {{3.5,1.1,-5.5}, {11.5,1.1,14}, {35.5,1.1,14}, {23.5,1.1,26.5},
            {39.5,1.1,7.5}, {38.5,1.1,43}, {7.5,1.1,38.5}, {23.5,1.1,49.5}};
    private static final double[][] TARGETS = {{3.5,3.4,2.5}, {10.5,2.3,9.2}, {34.5,2.5,9}, {23.5,4.5,21},
            {43.3,2.5,2.8}, {41.5,2.5,40.5}, {3.2,2.3,43.2}, {23.5,4,69.5}};
    private static CompletableFuture<Gallery> setup;
    private static CompletableFuture<Void> action;
    private static Gallery gallery;
    private static int ticks, frames, view, oldFov;
    private static boolean done, oldGui;

    static boolean finished() {return done;}

    static void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) return;
        if (++ticks == 1) {
            oldGui = client.options.hideGui;oldFov = client.options.fov().get();
            client.options.hideGui = true;client.options.fov().set(65);
            checkPlaques(client);
        }
        var server = client.getSingleplayerServer();
        if (setup == null) {
            var identity = client.player.getUUID();
            setup = server.submit(() -> new Gallery(server.getPlayerList().getPlayer(identity)));return;
        }
        if (!setup.isDone()) return;
        if (gallery == null) gallery = setup.join();
        if (action != null) {
            if (!action.isDone()) return;
            action.join();action = null;frames = 0;
            if (view == VIEWS.length) {
                restoreOptions(client);done = true;return;
            }
        }
        if (!client.level.dimension().equals(Core.LEVEL) || client.player.position().distanceTo(gallery.position) > 1) return;
        client.player.lookAt(Anchor.EYES, gallery.target);
        if (++frames == 30) Screenshot.grab(client.gameDirectory, "core-maze-" + VIEWS[view] + ".png", client.getMainRenderTarget(),
                text -> LogUtils.getLogger().info("Maze art review: {}", text.getString()));
        if (frames == 35) {
            view++;
            action = server.submit(() -> {if (view == VIEWS.length) gallery.restore();else gallery.move(view);});
        }
    }

    static void restoreOptions(Minecraft client) {
        if (ticks == 0) return;
        client.options.hideGui = oldGui;client.options.fov().set(oldFov);
    }

    static void restorePlayer() {
        if (gallery != null) gallery.restore();
    }

    private static void checkPlaques(Minecraft client) {
        for (var language : new String[] {"en_us", "zh_cn"}) {
            var resource = ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "lang/" + language + ".json");
            try (var reader = client.getResourceManager().openAsReader(resource)) {
                for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                    if (entry.getKey().startsWith("maze.aeronautics_innerworld.") && client.font.width(entry.getValue().getAsString()) > 90)
                        throw new IllegalStateException("Maze plaque exceeds sign width: " + language + " / " + entry.getKey());
                }
            } catch (java.io.IOException error) {throw new IllegalStateException(error);}
        }
    }

    private static final class Gallery {
        private final ServerPlayer player;
        private final net.minecraft.server.level.ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw, oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final CoreMazePiece maze;
        private volatile Vec3 position, target;

        Gallery(ServerPlayer player) {
            this.player = player;oldLevel = player.serverLevel();oldPosition = player.position();
            oldYaw = player.getYRot();oldPitch = player.getXRot();oldMode = player.gameMode.getGameModeForPlayer();oldFlying = player.getAbilities().flying;
            var level = player.server.getLevel(Core.LEVEL);
            var id = ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_mazes");
            var located = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id), new BlockPos(8192,33,8192),4,false);
            if (located == null) throw new IllegalStateException("No natural maze for art review");
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .get(ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_maze"));
            var start = level.getChunkAt(located).getStartForStructure(structure);
            maze = (CoreMazePiece) start.getPieces().getFirst();
            var box = maze.getBoundingBox();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) level.getChunk(x,z);
            player.setGameMode(GameType.SPECTATOR);move(0);
        }

        private Vec3 world(double[] local) {
            var forward = maze.getOrientation();
            var right = forward.getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
            return Vec3.atBottomCenterOf(maze.getEntrance()).add(right.getStepX() * (local[0] - 3.5) + forward.getStepX() * (local[2] - .5),
                    local[1] - 1, right.getStepZ() * (local[0] - 3.5) + forward.getStepZ() * (local[2] - .5));
        }

        void move(int index) {
            position = world(CAMERAS[index]);target = world(TARGETS[index]);
            player.teleportTo(player.server.getLevel(Core.LEVEL), position.x,position.y,position.z,0,0);
        }

        void restore() {
            player.teleportTo(oldLevel, oldPosition.x,oldPosition.y,oldPosition.z,oldYaw,oldPitch);
            player.setGameMode(oldMode);player.getAbilities().flying = oldFlying;player.onUpdateAbilities();
        }
    }
}

package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import io.github.alphaly.aeronauticsinnerworld.world.structure.DeepWorksPiece;
import io.github.alphaly.aeronauticsinnerworld.world.structure.MantleRelayPiece;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class RelayClientCheck {
    private static final boolean DEEP = Boolean.getBoolean("innerworld.deepWorksClientCheck");
    private static final String CHECK = DEEP ? "deep-works" : "relay";
    private static int ticks;
    private static int sceneTicks;
    private static int bookFrames;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> cleanup;
    private static CompletableFuture<Void> terrainView;
    private static int terrainFrames;
    private static Scene scene;
    private static boolean openedBook;
    private static boolean finished;
    private static RuntimeException failure;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if ((!Boolean.getBoolean("innerworld.relayClientCheck") && !DEEP) || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        try {
            if (++ticks > 1800 && cleanup == null) {
                throw new IllegalStateException("Relay client check timed out");
            }
            if (cleanup != null) {
                if (cleanup.isDone()) {
                    cleanup.join();
                    finished = true;
                    if (failure != null) {
                        throw failure;
                    }
                    Files.writeString(client.gameDirectory.toPath().resolve(CHECK + "-check.result"), "verified");
                    LogUtils.getLogger().info("Relay client check passed: natural world generation, structure echo, dry exploration, archive reading and original models");
                    client.stop();
                }
                return;
            }
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) {
                return;
            }
            if (setup == null) {
                var uuid = client.player.getUUID();
                setup = client.getSingleplayerServer().submit(() -> new Scene(client.getSingleplayerServer().getPlayerList().getPlayer(uuid)));
                return;
            }
            if (!setup.isDone()) {
                return;
            }
            scene = setup.join();
            if (terrainView != null) {
                if (terrainView.isDone()) {
                    terrainView.join();
                    if (++terrainFrames == 40) {
                        Screenshot.grab(client.gameDirectory, "mantle-spire-route.png", client.getMainRenderTarget(),
                                message -> LogUtils.getLogger().info("Natural mantle spire screenshot: {}", message.getString()));
                        cleanup = client.getSingleplayerServer().submit(scene::cleanUp);
                    }
                }
                return;
            }
            if (++sceneTicks == 25) {
                if (client.getBlockRenderer().getBlockModel(AeronauticsInnerworld.RELAY_BEACON.get().defaultBlockState())
                        == client.getModelManager().getMissingModel()
                        || client.getItemRenderer().getModel(new ItemStack(DEEP ? AeronauticsInnerworld.PRESSURE_MATRIX.get() : AeronauticsInnerworld.RELIC_ALLOY.get()),
                                client.level, client.player, 0) == client.getModelManager().getMissingModel()) {
                    throw new IllegalStateException("Relay beacon or relic alloy model is missing");
                }
                Screenshot.grab(client.gameDirectory, "mantle-" + CHECK + "-interior.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Relay interior screenshot: {}", message.getString()));
                client.getSingleplayerServer().execute(() -> {
                    scene.player.setItemInHand(InteractionHand.MAIN_HAND, scene.record.copy());
                    scene.player.containerMenu.broadcastChanges();
                });
            }
            if (sceneTicks > 30 && !openedBook && client.player.getMainHandItem().is(Items.WRITTEN_BOOK)) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                openedBook = true;
            }
            if (client.screen instanceof BookViewScreen book) {
                if (++bookFrames == 1) {
                    BookLayoutChecks.check(client, client.player.getMainHandItem());
                    if (DEEP) BookLayoutChecks.check(client, scene.manual);
                }
                if (bookFrames >= 6) {
                    Screenshot.grab(client.gameDirectory, "mantle-" + CHECK + "-record.png", client.getMainRenderTarget(),
                            message -> LogUtils.getLogger().info("Relay archive screenshot: {}", message.getString()));
                    book.onClose();
                    if (DEEP) {
                        terrainView = client.getSingleplayerServer().submit(() -> {
                            scene.verify();
                            scene.observeSpire();
                        });
                    } else {
                        cleanup = client.getSingleplayerServer().submit(() -> {
                            try {
                                scene.verify();
                            } finally {
                                scene.cleanUp();
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            if (finished || cleanup != null && cleanup.isDone()) {
                finished = true;
                throw new IllegalStateException("Relay client check failed", e);
            }
            failure = new IllegalStateException("Relay client check failed", e);
            client.setScreen(null);
            cleanup = client.getSingleplayerServer().submit(() -> {
                if (scene != null) {
                    scene.cleanUp();
                }
            });
        }
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final net.minecraft.server.level.ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw;
        private final float oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final ItemStack oldHand;
        private final float oldHealth;
        private final BlockPos dry;
        private final ItemStack record;
        private final ItemStack manual;

        private Scene(ServerPlayer player) {
            this.player = player;
            oldLevel = player.serverLevel();
            oldPosition = player.position();
            oldYaw = player.getYRot();
            oldPitch = player.getXRot();
            oldMode = player.gameMode.getGameModeForPlayer();
            oldFlying = player.getAbilities().flying;
            oldHand = player.getMainHandItem().copy();
            oldHealth = player.getHealth();
            var level = player.server.getLevel(Mantle.LEVEL);
            if (!player.server.getWorldData().worldGenOptions().generateStructures()) {
                throw new IllegalStateException("Select a development world with natural structures enabled");
            }
            var tag = TagKey.create(Registries.STRUCTURE,
                    ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, DEEP ? "deep_works" : "mantle_relays"));
            var located = level.findNearestMapStructure(tag, DEEP ? new BlockPos(8192, 40, 8192) : new BlockPos(1024, 175, 1024), 4, false);
            if (located == null) {
                throw new IllegalStateException("Native placement did not locate a mantle relay");
            }
            Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
                    .get(ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, DEEP ? "deep_works" : "mantle_relay"));
            var start = level.getChunkAt(located).getStartForStructure(structure);
            if (start == null || !start.isValid()) {
                throw new IllegalStateException("Naturally located relay has no structure start");
            }
            var box = start.getBoundingBox();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                    level.getChunk(x, z);
                }
            }
            BlockPos beacon = null;
            var spawners = new java.util.ArrayList<BlockPos>();
            ItemStack foundRecord = ItemStack.EMPTY;
            for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                if (level.getBlockState(pos).is(AeronauticsInnerworld.RELAY_BEACON.get())) {
                    beacon = pos.immutable();
                }
                if (level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SPAWNER)) spawners.add(pos.immutable());
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        if (chest.getItem(slot).is(Items.WRITTEN_BOOK)) {
                            foundRecord = chest.getItem(slot).copy();
                        }
                    }
                }
            }
            if (beacon == null || foundRecord.isEmpty()) {
                throw new IllegalStateException("Natural relay generated incomplete scenery or archive");
            }
            record = foundRecord;
            manual = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(
                    AeronauticsInnerworld.MOD_ID, "expedition_manual")).orElseThrow().value().getResultItem(level.registryAccess()).copy();
            BlockPos standing = null;
            for (var pos : BlockPos.betweenClosed(box.minX() + 1, box.minY() + 4, box.minZ() + 1,
                    box.maxX() - 1, box.minY() + 4, box.maxZ() - 1)) {
                double distance = pos.distSqr(beacon);
                if (distance < 16 || distance > (DEEP ? 900 : 50) || !level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                        || !level.getBlockState(pos.below()).isSolid() || level.getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                        || DEEP && spawners.stream().anyMatch(spawner -> spawner.distSqr(pos) < 17 * 17)) {
                    continue;
                }
                var eye = Vec3.atBottomCenterOf(pos).add(0, 1.62, 0);
                if (DEEP || level.clip(new ClipContext(eye, Vec3.atCenterOf(beacon), ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player)).getBlockPos().equals(beacon)) {
                    standing = pos.immutable();
                    break;
                }
            }
            if (standing == null) {
                throw new IllegalStateException("Natural relay has no dry observation space");
            }
            dry = standing;
            var original = level.getBlockState(dry);
            try {
                level.setBlockAndUpdate(dry, AeronauticsInnerworld.ECHO_SOUNDER.get().defaultBlockState());
                var sounder = (EchoSounderBlockEntity) level.getBlockEntity(dry);
                sounder.scan();
                var entrance = DEEP ? ((DeepWorksPiece) start.getPieces().getFirst()).getEntrance()
                        : ((MantleRelayPiece) start.getPieces().getFirst()).getEntrance();
                if (!entrance.equals(sounder.getRelay())) {
                    throw new IllegalStateException("Sounder missed the naturally generated relay");
                }
            } finally {
                level.setBlockAndUpdate(dry, original);
            }
            double dx = beacon.getX() - dry.getX();
            double dz = beacon.getZ() - dry.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            player.teleportTo(level, dry.getX() + .5, dry.getY(), dry.getZ() + .5, yaw, 0);
            player.setGameMode(GameType.SURVIVAL);
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AeronauticsInnerworld.RELIC_ALLOY.get()));
            player.containerMenu.broadcastChanges();
            LogUtils.getLogger().info("Natural mantle site verified at {} with dry gallery {}", box.getCenter(), dry);
        }

        private void verify() {
            if (!player.serverLevel().getFluidState(dry).isEmpty() || player.isInLava() || player.isOnFire()
                    || player.getHealth() < oldHealth) {
                throw new IllegalStateException("Natural dry gallery exposed its explorer to lava or heat");
            }
        }

        private void observeSpire() {
            var level = player.serverLevel();
            var center = new net.minecraft.world.level.ChunkPos(dry);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                var chunk = level.getChunk(center.x + dx, center.z + dz);
                var origin = chunk.getPos().getWorldPosition();
                for (var pos : BlockPos.betweenClosed(origin.offset(0, 64, 0), origin.offset(15, 64, 15))) {
                    if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BASALT)
                            || !level.getBlockState(pos.above()).is(net.minecraft.world.level.block.Blocks.BASALT)) continue;
                    player.setGameMode(GameType.SPECTATOR);
                    player.teleportTo(level, pos.getX() + 10.5, 65, pos.getZ() + .5, 90, 0);
                    LogUtils.getLogger().info("Natural mantle spire verified at {}", pos);
                    return;
                }
            }
            throw new IllegalStateException("New mantle region contains no natural spires");
        }

        private void cleanUp() {
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);
            player.setGameMode(oldMode);
            player.getAbilities().flying = oldFlying;
            player.onUpdateAbilities();
            player.setItemInHand(InteractionHand.MAIN_HAND, oldHand);
            player.containerMenu.broadcastChanges();
        }
    }
}

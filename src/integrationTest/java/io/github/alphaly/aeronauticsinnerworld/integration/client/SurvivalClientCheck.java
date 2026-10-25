package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class SurvivalClientCheck {
    private static int ticks;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Boolean> step;
    private static CompletableFuture<Void> cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean openedGuide;
    private static boolean readGuide;
    private static int guideFrames;
    private static boolean started;
    private static boolean reversed;
    private static boolean stopped;
    private static boolean openedHatches;
    private static boolean closedHatches;
    private static boolean finished;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.survivalClientCheck") || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        try {
            if (++ticks > 1800 && cleanup == null) {
                throw new IllegalStateException("Survival client check timed out");
            }
            if (cleanup != null) {
                if (cleanup.isDone()) {
                    cleanup.join();
                    if (failure != null) {
                        throw failure;
                    }
                    Files.writeString(client.gameDirectory.toPath().resolve("survival-check.result"), "verified");
                    LogUtils.getLogger().info("Survival client check passed: bilingual guide, fueled probe and mechanical controls over the client connection");
                    finished = true;
                    client.stop();
                }
                return;
            }
            if (client.player == null || client.level == null || client.getSingleplayerServer() == null || ticks < 100) {
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
            if (scene.ticks == 40 && !readGuide) {
                if (!openedGuide && client.player.getMainHandItem().is(Items.WRITTEN_BOOK)) {
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    openedGuide = true;
                }
                if (client.screen instanceof BookViewScreen book) {
                    if (++guideFrames == 1) {
                        BookLayoutChecks.check(client, client.player.getMainHandItem(), 15);
                        book.setPage(7);
                    }
                    if (guideFrames >= 6) {
                        Screenshot.grab(client.gameDirectory, "mantle-expedition-manual.png", client.getMainRenderTarget(),
                                message -> LogUtils.getLogger().info("Field guide screenshot: {}", message.getString()));
                        book.onClose();
                        readGuide = true;
                    }
                }
                return;
            }
            if (scene.ticks >= 45 && !openedHatches && client.player.getMainHandItem().isEmpty()) {
                toggle(client, ProbeTestRig.BOARDING_HATCH);
                openedHatches = true;
            }
            if (scene.ticks >= 56 && !closedHatches) {
                toggle(client, ProbeTestRig.BOARDING_HATCH.above());
                closedHatches = true;
            }
            if (scene.ticks >= 75 && !started && client.player.getMainHandItem().isEmpty()) {
                toggle(client, ProbeTestRig.LEFT_THROTTLE);
                toggle(client, ProbeTestRig.RIGHT_THROTTLE);
                started = true;
            }
            if (scene.ticks >= 130 && !reversed) {
                toggle(client, ProbeTestRig.LEFT_REVERSE);
                toggle(client, ProbeTestRig.RIGHT_REVERSE);
                reversed = true;
            }
            if (scene.ticks >= 210 && !stopped) {
                toggle(client, ProbeTestRig.LEFT_THROTTLE);
                toggle(client, ProbeTestRig.RIGHT_THROTTLE);
                stopped = true;
            }
            if (step == null) {
                step = client.getSingleplayerServer().submit(scene::tick);
            } else if (step.isDone()) {
                boolean done = step.join();
                step = null;
                if (done) {
                    cleanup = client.getSingleplayerServer().submit(scene::cleanUp);
                }
            }
        } catch (Exception e) {
            if (cleanup != null && cleanup.isDone()) {
                finished = true;
                throw new IllegalStateException("Survival client check failed", e);
            }
            failure = new IllegalStateException("Survival client check failed", e);
            client.setScreen(null);
            cleanup = client.getSingleplayerServer().submit(() -> {
                if (scene != null) {
                    scene.cleanUp();
                }
            });
        }
    }

    private static void toggle(Minecraft client, BlockPos relative) {
        var pos = scene.rig.local(relative);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static final class Scene {
        private final ServerPlayer player;
        private final net.minecraft.server.level.ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw;
        private final float oldPitch;
        private final GameType oldMode;
        private final boolean oldFlying;
        private final float oldHealth;
        private final List<ItemStack> oldInventory;
        private final List<MobEffectInstance> oldEffects;
        private final int oldExposure;
        private final ProbeTestRig rig;
        private int ticks;
        private double initialZ;

        private Scene(ServerPlayer player) {
            this.player = player;
            oldLevel = player.serverLevel();
            oldPosition = player.position();
            oldYaw = player.getYRot();
            oldPitch = player.getXRot();
            oldMode = player.gameMode.getGameModeForPlayer();
            oldFlying = player.getAbilities().flying;
            oldHealth = player.getHealth();
            oldInventory = java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                    .mapToObj(slot -> player.getInventory().getItem(slot).copy()).toList();
            oldEffects = player.getActiveEffects().stream().map(MobEffectInstance::new).toList();
            oldExposure = player.getPersistentData().getInt("InnerworldHeatExposure");
            rig = new ProbeTestRig(player.server.getLevel(Mantle.LEVEL), new BlockPos(384, 180, 128));
            rig.workEquipment();
            rig.survivalPower();
        }

        private boolean tick() {
            rig.level.resetEmptyTime();
            if (rig.subLevel == null && !rig.ready()) {
                return false;
            }
            if (++ticks == 1) {
                rig.assemble();
            }
            if (ticks == 40) {
                if (!rig.cabin().isSealed()) {
                    throw new IllegalStateException("Fueled probe has no sealed cabin");
                }
                var position = rig.subLevel.logicalPose().transformPosition(Vec3.atLowerCornerOf(rig.local(new BlockPos(2, 1, 2))).add(.5, .05, .5));
                player.teleportTo(rig.level, position.x, position.y, position.z, 0, 0);
                player.setGameMode(GameType.SURVIVAL);
                player.removeAllEffects();
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                player.setHealth(player.getMaxHealth());
                rig.cabin().addCoolant(240);
                var input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.BOOK), new ItemStack(Items.COPPER_INGOT)));
                var recipe = rig.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, rig.level).orElseThrow();
                player.setItemInHand(InteractionHand.MAIN_HAND, recipe.value().assemble(input, rig.level.registryAccess()));
                player.containerMenu.broadcastChanges();
                initialZ = rig.subLevel.logicalPose().position().z();
            }
            if (ticks == 41) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.containerMenu.broadcastChanges();
            }
            if (ticks == 55) {
                for (var pos : List.of(rig.local(ProbeTestRig.BOARDING_HATCH), rig.local(ProbeTestRig.BOARDING_HATCH.above()))) {
                    var state = rig.level.getBlockState(pos);
                    if (!state.getValue(PressureHatchBlock.OPEN) || !state.getCollisionShape(rig.level, pos).isEmpty()) {
                        throw new IllegalStateException("Paired hatches did not open a two-block passage");
                    }
                }
                if (rig.cabin().isSealed()) {
                    throw new IllegalStateException("Open boarding hatches retained their cabin seal");
                }
            }
            if (ticks == 73 && !rig.cabin().isSealed()) {
                throw new IllegalStateException("Closing the upper hatch did not seal both boarding cells");
            }
            if (ticks == 128 && (rig.subLevel.logicalPose().position().z() - initialZ < .1
                    || !started)) {
                throw new IllegalStateException("Client lever input did not engage fueled propulsion");
            }
            if (ticks == 208 && (RigidBodyHandle.of(rig.subLevel).getLinearVelocity().z() >= -.02 || !reversed)) {
                throw new IllegalStateException("Client lever input did not reverse fueled propulsion");
            }
            if (ticks > 75 && (player.isInLava() || player.isOnFire() || player.getHealth() < player.getMaxHealth())) {
                throw new IllegalStateException("Survival pilot was exposed inside mechanically powered cabin");
            }
            return ticks >= 230;
        }

        private void cleanUp() {
            rig.cleanUp();
            player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);
            player.setGameMode(oldMode);
            player.getAbilities().flying = oldFlying;
            player.onUpdateAbilities();
            player.removeAllEffects();
            oldEffects.forEach(player::addEffect);
            for (int slot = 0; slot < oldInventory.size(); slot++) {
                player.getInventory().setItem(slot, oldInventory.get(slot));
            }
            player.setHealth(oldHealth);
            player.clearFire();
            player.getPersistentData().putInt("InnerworldHeatExposure", oldExposure);
            player.containerMenu.broadcastChanges();
        }
    }
}

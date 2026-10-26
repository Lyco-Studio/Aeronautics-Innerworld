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
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.util.Mth;
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
    private static boolean recordedRoute;
    private static boolean reloadedRoute;
    private static boolean routeScreenshot;
    private static boolean ascending;
    private static boolean descending;
    private static boolean holdingDepth;
    private static boolean capturedOptions, oldPause, steeringScreenshot;

    private static boolean steering() {
        return Boolean.getBoolean("innerworld.survivalSteering");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("innerworld.survivalClientCheck") || finished) {
            return;
        }
        var client = Minecraft.getInstance();
        if (!capturedOptions) {oldPause = client.options.pauseOnLostFocus;capturedOptions = true;client.options.pauseOnLostFocus = false;}
        if (client.screen instanceof PauseScreen) client.setScreen(null);
        try {
            if (++ticks > (steering() ? 6000 : 1800) && cleanup == null) {
                throw new IllegalStateException("Survival client check timed out");
            }
            if (cleanup != null) {
                if (cleanup.isDone()) {
                    cleanup.join();
                    client.options.pauseOnLostFocus = oldPause;
                    if (failure != null) {
                        throw failure;
                    }
                    Files.writeString(client.gameDirectory.toPath().resolve("survival-check.result"), steering() ? "steering-verified" : "verified");
                    LogUtils.getLogger().info(steering() ? "Survival steering check passed: fueled quarter-turn, countersteer and native propulsion stop"
                            : "Survival client check passed: bilingual guide, fueled probe and mechanical controls over the client connection");
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
                        BookLayoutChecks.check(client, client.player.getMainHandItem());
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
            if (scene.ticks >= 217 && !recordedRoute && client.player.getMainHandItem().is(Items.COMPASS)) {
                toggle(client, ProbeTestRig.SOUNDER);
                recordedRoute = true;
            }
            if (scene.ticks >= 232 && !reloadedRoute && client.player.getMainHandItem().has(DataComponents.LODESTONE_TRACKER)) {
                toggle(client, ProbeTestRig.SOUNDER);
                reloadedRoute = true;
            }
            if (scene.ticks >= 248 && !routeScreenshot) {
                if (!client.player.getMainHandItem().has(DataComponents.LODESTONE_TRACKER)) {
                    throw new IllegalStateException("Route compass data did not reach the client");
                }
                Screenshot.grab(client.gameDirectory, "mantle-route-compass.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Route compass screenshot: {}", message.getString()));
                routeScreenshot = true;
            }
            if (scene.ticks >= 252 && !ascending && client.player.getMainHandItem().isEmpty()) {
                cycleTrim(client);
                ascending = true;
            }
            if (scene.ticks >= 285 && !descending) {
                cycleTrim(client);
                descending = true;
            }
            if (scene.ticks >= 325 && !holdingDepth) {
                cycleTrim(client);
                holdingDepth = true;
            }
            if (steering() && scene.steeringPhase > 0 && ticks % 6 == 0) {
                int direction = scene.steeringDirection;
                setLever(client, ProbeTestRig.LEFT_THROTTLE, direction == 0);
                setLever(client, ProbeTestRig.RIGHT_THROTTLE, direction == 0);
                setLever(client, ProbeTestRig.LEFT_REVERSE, direction < 0);
                setLever(client, ProbeTestRig.RIGHT_REVERSE, direction > 0);
            }
            if (scene.steeringPhase == 2 && !steeringScreenshot) {
                Screenshot.grab(client.gameDirectory, "mantle-native-steering.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Native steering screenshot: {}", message.getString()));
                steeringScreenshot = true;
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
                client.options.pauseOnLostFocus = oldPause;
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

    private static void setLever(Minecraft client, BlockPos relative, boolean powered) {
        var state = client.level.getBlockState(scene.rig.local(relative));
        if (state.hasProperty(LeverBlock.POWERED) && state.getValue(LeverBlock.POWERED) != powered) toggle(client, relative);
    }

    private static void cycleTrim(Minecraft client) {
        client.player.setShiftKeyDown(true);
        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
        toggle(client, new BlockPos(1, 0, 2));
        toggle(client, new BlockPos(3, 0, 2));
        client.player.setShiftKeyDown(false);
        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
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
        private final CompoundTag oldFood = new CompoundTag();
        private final int oldExposure;
        private final ProbeTestRig rig;
        private int ticks;
        private double initialZ;
        private BlockPos waypoint;
        private volatile int steeringPhase, steeringDirection;
        private double steeringOrigin;
        private Vec3 steeringPosition;
        private int settled;

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
            player.getFoodData().addAdditionalSaveData(oldFood);
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
            if (ticks == 216) {
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COMPASS));
                player.containerMenu.broadcastChanges();
            }
            if (ticks == 230) {
                waypoint = rig.sounder().getWaypoint();
                var tracker = player.getMainHandItem().get(DataComponents.LODESTONE_TRACKER);
                if (!recordedRoute || waypoint == null || tracker == null || tracker.tracked()
                        || !tracker.target().orElseThrow().pos().equals(waypoint)
                        || !rig.level.dimension().equals(tracker.target().orElseThrow().dimension())) {
                    throw new IllegalStateException("Client compass input did not record its world-space route");
                }
                player.setShiftKeyDown(true);
                rig.sounder().interact(player);
                player.setShiftKeyDown(false);
            }
            if (ticks == 245 && (!reloadedRoute || !waypoint.equals(rig.sounder().getWaypoint()))) {
                throw new IllegalStateException("Saved compass input did not reload its route");
            }
            if (ticks == 250) {
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                player.containerMenu.broadcastChanges();
            }
            if (ticks == 280 && (!ascending || trimMode() != 1 || RigidBodyHandle.of(rig.subLevel).getLinearVelocity().y() < .1)) {
                throw new IllegalStateException("Client ballast input did not ascend at normal fuel speed");
            }
            if (ticks == 320 && (!descending || trimMode() != -1 || RigidBodyHandle.of(rig.subLevel).getLinearVelocity().y() > -.1)) {
                throw new IllegalStateException("Client ballast input did not descend at normal fuel speed");
            }
            if (ticks == 340 && (!holdingDepth || trimMode() != 0)) {
                throw new IllegalStateException("Client ballast input did not restore RPM control");
            }
            if (!steering()) return ticks >= 345;
            if (ticks == 345) {
                steeringOrigin = heading();steeringPosition = rig.world(ProbeTestRig.SOUNDER);steeringPhase = 1;
                LogUtils.getLogger().info("Native steering begins: heading={}, position={}", Math.toDegrees(steeringOrigin), steeringPosition);
            }
            if (ticks < 345) return false;
            if (ticks > 2500) throw new IllegalStateException("Native steering timed out at phase " + steeringPhase);
            var body = RigidBodyHandle.of(rig.subLevel);
            if (steeringPhase == 3) {
                steeringDirection = 0;
                if (++settled < 30) return false;
                for (var drive : List.of(ProbeTestRig.LEFT_DRIVE, ProbeTestRig.RIGHT_DRIVE))
                    if (((ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(drive))).getSpeed() != 0)
                        throw new IllegalStateException("Native steering did not disengage both propulsion channels");
                if (Math.abs(Mth.wrapDegrees(Math.toDegrees(heading() - steeringOrigin))) > 5
                        || Math.abs(body.getAngularVelocity().y()) > .01 || !rig.cabin().isSealed())
                    throw new IllegalStateException("Native steering drifted after disengaging propulsion");
                LogUtils.getLogger().info("Native steering stopped: heading={}, angular={}, travel={}, coolant={}",
                        Math.toDegrees(heading()), body.getAngularVelocity().y(),
                        steeringPosition.distanceTo(rig.world(ProbeTestRig.SOUNDER)), rig.cabin().getCoolant());
                return true;
            }
            double target = steeringOrigin + (steeringPhase == 1 ? Math.PI / 2 : 0);
            double error = Mth.wrapDegrees(Math.toDegrees(target - heading())) * Math.PI / 180;
            double rate = body.getAngularVelocity().y();
            double command = error - rate * 4;
            steeringDirection = Math.abs(error) < Math.toRadians(2) && Math.abs(rate) < .015 ? 0 : command > 0 ? 1 : -1;
            if (ticks % 100 == 0) LogUtils.getLogger().info("Native steering phase={}, error={}, angular={}, position={}",
                    steeringPhase, Math.toDegrees(error), rate, rig.world(ProbeTestRig.SOUNDER));
            if (Math.abs(error) < Math.toRadians(5) && Math.abs(rate) < .03) settled++;else settled = 0;
            if (settled >= 15) {
                LogUtils.getLogger().info("Native steering settled: phase={}, heading={}, angular={}, travel={}",
                        steeringPhase, Math.toDegrees(heading()), rate, steeringPosition.distanceTo(rig.world(ProbeTestRig.SOUNDER)));
                settled = 0;
                if (steeringPhase == 1) {steeringPhase = 2;return false;}
                steeringDirection = 0;steeringPhase = 3;
            }
            return false;
        }

        private double heading() {
            var forward = rig.subLevel.logicalPose().transformNormal(new Vec3(0, 0, 1));
            return Math.atan2(forward.x, forward.z);
        }

        private int trimMode() {
            int left = ((ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(new BlockPos(1, 0, 2)))).getTrimMode();
            int right = ((ProbeDriveBlockEntity) rig.level.getBlockEntity(rig.local(new BlockPos(3, 0, 2)))).getTrimMode();
            if (left != right) throw new IllegalStateException("Ballast mode input reached only one device");
            return left;
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
            player.getFoodData().readAdditionalSaveData(oldFood);
            player.clearFire();
            player.getPersistentData().putInt("InnerworldHeatExposure", oldExposure);
            player.containerMenu.broadcastChanges();
        }
    }
}

package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeRecovery;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class RecoveryClientCheck {
    private static final BlockPos RESCUE_HATCH = new BlockPos(4, 1, 2);
    private static final BlockPos WRECK_ORIGIN = new BlockPos(2304, 180, 128);
    private static final BlockPos RESCUE_ORIGIN = new BlockPos(2297, 180, 160);
    private static final int PATCH_STOCK = 16;
    private static final int REPAIR_STOCK = 4;
    private static int ticks, observedStage = -1, frames;
    private static boolean clicked, compassVerified, finished;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> step, cleanup;
    private static Scene scene;
    private static RuntimeException failure;
    private static boolean movingBearingSeen, wreckHudSeen, wrongWreckHud;

    @SubscribeEvent
    public static void recordHud(ClientChatReceivedEvent.System event) {
        if (!enabled() || scene == null || !event.isOverlay()
                || !(event.getMessage().getContents() instanceof TranslatableContents message)
                || !message.getKey().equals("message.aeronautics_innerworld.sonar_hud")) return;
        var args = message.getArgs();
        if (scene.stage == 14 && args[3] instanceof Component bearing
                && bearing.getContents() instanceof TranslatableContents target
                && target.getKey().equals("message.aeronautics_innerworld.sonar_recovery_bearing")) movingBearingSeen = true;
        if (scene.stage == 20) {
            if (args[4] instanceof Number heat && heat.intValue() >= 60) wreckHudSeen = true;
            else wrongWreckHud = true;
        }
    }

    @SubscribeEvent
    public static void recordDeath(LivingDeathEvent event) {
        if (!enabled() || scene == null || !event.getEntity().getUUID().equals(scene.identity)) return;
        // Reproducible native scatter keeps this check independent of previous player RNG use.
        event.getEntity().getRandom().setSeed(1);
        scene.diedFromHeat = event.getSource().is(ResourceKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "mantle_heat")));
        scene.deathPoint = event.getEntity().position();
        scene.deathLocal = scene.rig.subLevel.logicalPose().transformPositionInverse(scene.deathPoint);
    }

    @SubscribeEvent
    public static void recordDrops(LivingDropsEvent event) {
        if (!enabled() || scene == null || !event.getEntity().getUUID().equals(scene.identity)) return;
        scene.drops.addAll(event.getDrops());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!enabled() || finished) return;
        var client = Minecraft.getInstance();
        try {
            if (++ticks > (voyage() ? 7200 : 3600) && cleanup == null) throw new IllegalStateException("Recovery client timed out at " + observedStage);
            if (cleanup != null) {
                if (!cleanup.isDone()) return;
                cleanup.join();finished = true;
                if (failure != null) throw failure;
                Files.writeString(client.gameDirectory.toPath().resolve("recovery-check.result"), "verified");
                LogUtils.getLogger().info("Recovery client check passed: native heat deaths, keep/drop inventory rules, charged anchor respawns, recovery compass target, actual item pickup and rescue navigation");
                client.stop();return;
            }
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null || ticks < 100) return;
            var server = client.getSingleplayerServer();
            if (setup == null) {var id = client.player.getUUID();setup = server.submit(() -> new Scene(server.getPlayerList().getPlayer(id)));return;}
            if (!setup.isDone()) return;
            scene = setup.join();
            if (scene.stage != observedStage) {release(client);observedStage = scene.stage;frames = 0;clicked = false;}
            frames++;
            if (observedStage == 0 && frames == 1) BookLayoutChecks.check(client, scene.manual);
            if (observedStage == 0 && frames % 12 == 0 && client.player.getMainHandItem().is(Items.GLOWSTONE)) useAnchor(client);
            if (observedStage == 1 && !clicked && frames > 8) {
                client.player.getInventory().selected = 1;useAnchor(client);clicked = true;
            }
            if ((observedStage == 4 || observedStage == 6) && !clicked && frames > 25
                    && client.screen instanceof DeathScreen && client.player.isDeadOrDying()) {
                client.player.respawn();client.setScreen(null);clicked = true;
            }
            if (observedStage == 7 && client.player.getMainHandItem().is(Items.RECOVERY_COMPASS)) {
                var property = ItemProperties.getProperty(client.player.getMainHandItem(), ResourceLocation.withDefaultNamespace("angle"));
                if (!(property instanceof CompassItemPropertyFunction compass)
                        || !scene.lastDeath.equals(compass.compassTarget.getPos(client.level, client.player.getMainHandItem(), client.player)))
                    throw new IllegalStateException("Recovery compass does not target the actual mantle death location");
                compassVerified = true;
            }
            if (observedStage == 8) {
                if (!clicked) {
                    var upper = scene.rig.local(new BlockPos(0, 2, 2));
                    if (client.level.getBlockState(upper).getValue(PressureHatchBlock.OPEN)) use(client, scene.rig, new BlockPos(0, 2, 2));
                    clicked = true;
                }
                if (scene.patchTarget != null && frames % 6 == 0
                        && client.player.getInventory().getItem(1).is(AeronauticsInnerworld.EMERGENCY_PATCH_ITEM.get())) {
                    client.player.getInventory().selected = 1;
                    placePatch(client, scene.patchTarget);
                }
                ItemEntity target = null;
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof ItemEntity item && item.isAlive() && scene.drops.stream().anyMatch(drop -> drop.getUUID().equals(item.getUUID()))
                            && (target == null || client.player.distanceToSqr(item) < client.player.distanceToSqr(target))) target = item;
                }
                if (target != null) {
                    var offset = target.position().subtract(client.player.position());
                    client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));client.player.setXRot(-15);
                    client.options.keyUp.setDown(offset.horizontalDistanceSqr() > .12);
                    client.options.keyJump.setDown(offset.y > .3);client.options.keyShift.setDown(offset.y < -.5);
                } else release(client);
            }
            if (observedStage == 20 && scene.repairTarget != null && frames % 8 == 0) {
                client.player.getInventory().selected = 3;
                var pos = scene.repairTarget;
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            }
            if (observedStage >= 11 && observedStage <= 19) {
                if (observedStage == 13 && frames == 100) Screenshot.grab(client.gameDirectory, "rescue-voyage.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Rescue voyage screenshot: {}", message.getString()));
                if (observedStage == 19 && frames == 15) Screenshot.grab(client.gameDirectory, "rescue-entry.png", client.getMainRenderTarget(),
                        message -> LogUtils.getLogger().info("Rescue entry screenshot: {}", message.getString()));
                if (observedStage == 11 && !clicked && client.player.getInventory().getItem(2).is(AeronauticsInnerworld.COOLANT_CELL.get())) {
                    client.player.getInventory().selected = 2;use(client, scene.rescue, ProbeTestRig.CABIN);clicked = true;
                }
                if (observedStage == 12 && !clicked) {
                    client.player.getInventory().selected = 0;use(client, scene.rescue, ProbeTestRig.SOUNDER);clicked = true;
                }
                if (observedStage == 13 && !clicked) {
                    client.player.getInventory().selected = 8;
                    use(client, scene.rescue, ProbeTestRig.LEFT_REVERSE);use(client, scene.rescue, ProbeTestRig.RIGHT_REVERSE);
                    use(client, scene.rescue, ProbeTestRig.LEFT_THROTTLE);use(client, scene.rescue, ProbeTestRig.RIGHT_THROTTLE);clicked = true;
                }
                if (observedStage == 14 && !clicked) {
                    use(client, scene.rescue, ProbeTestRig.LEFT_THROTTLE);use(client, scene.rescue, ProbeTestRig.RIGHT_THROTTLE);clicked = true;
                }
                if (observedStage == 14 && frames % 10 == 0 && scene.trimRequest != scene.currentTrim) {
                    int mode = scene.currentTrim;
                    int requested = scene.trimRequest;
                    client.player.setShiftKeyDown(true);
                    client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    while (mode != requested) {
                        use(client, scene.rescue, new BlockPos(1, 0, 2));use(client, scene.rescue, new BlockPos(3, 0, 2));
                        mode = mode == 0 ? 1 : mode == 1 ? -1 : 0;
                    }
                    client.player.setShiftKeyDown(false);
                    client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                }
                if (observedStage == 15 && !clicked) {use(client, scene.rescue, RESCUE_HATCH);clicked = true;}
                if (observedStage == 17 && !clicked) {use(client, scene.rescue, RESCUE_HATCH);clicked = true;}
                if (observedStage == 18 && !clicked) {use(client, scene.rig, new BlockPos(0, 2, 2));clicked = true;}
                if (observedStage == 16 || observedStage == 17 || observedStage == 19) walk(client, scene.walkTarget);
            }
            if (observedStage == 9 && !clicked && client.player.getInventory().getItem(0).is(Items.RECOVERY_COMPASS)) {
                client.player.getInventory().selected = 0;
                var pos = scene.rig.local(ProbeTestRig.SOUNDER);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
                clicked = true;
            }
            if (observedStage == 10 && frames > 15) {release(client);cleanup = server.submit(scene::cleanUp);return;}
            if (step != null) {if (!step.isDone()) return;step.join();step = null;}
            step = server.submit(scene::advance);
        } catch (Exception e) {
            release(client);client.setScreen(null);
            if (finished || cleanup != null && cleanup.isDone()) {finished = true;throw new IllegalStateException("Recovery check failed", e);}
            failure = new IllegalStateException("Recovery check failed", e);
            cleanup = client.getSingleplayerServer().submit(() -> {if (scene != null) scene.cleanUp();});
        }
    }

    private static void useAnchor(Minecraft client) {
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(scene.anchor).add(0, .5, 0), Direction.UP, scene.anchor, false));
    }

    private static void release(Minecraft client) {
        client.options.keyUp.setDown(false);client.options.keyJump.setDown(false);client.options.keyShift.setDown(false);
    }

    private static boolean enabled() {return Boolean.getBoolean("innerworld.recoveryClientCheck");}
    private static boolean voyage() {return Boolean.getBoolean("innerworld.recoveryVoyage");}

    private static void use(Minecraft client, ProbeTestRig rig, BlockPos relative) {
        var pos = rig.local(relative);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static void walk(Minecraft client, Vec3 target) {
        var offset = target.subtract(client.player.position());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));client.player.setXRot(-10);
        client.options.keyUp.setDown(offset.horizontalDistanceSqr() > .015);
        client.options.keyJump.setDown(offset.y > .02);client.options.keyShift.setDown(false);
    }

    private static void placePatch(Minecraft client, BlockPos target) {
        for (var direction : Direction.values()) {
            var support = target.relative(direction);
            var face = direction.getOpposite();
            if (!client.level.getBlockState(support).isFaceSturdy(client.level, support, face)) continue;
            var point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5));
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(point, face, support, false));
            return;
        }
        throw new IllegalStateException("No placement support at the wreck breach " + target);
    }

    private static final class Scene {
        private final MinecraftServer server;
        private final UUID identity;
        private final CompoundTag oldPlayer;
        private final ServerLevel oldLevel;
        private final Vec3 oldPosition;
        private final float oldYaw, oldPitch;
        private final GameType oldMode;
        private final ResourceKey<net.minecraft.world.level.Level> oldRespawnDimension;
        private final BlockPos oldRespawnPosition;
        private final float oldRespawnAngle;
        private final boolean oldRespawnForced, oldKeepInventory;
        private final Optional<GlobalPos> oldLastDeath;
        private final ProbeTestRig rig;
        private final ProbeTestRig rescue;
        private final ServerLevel level;
        private final BlockPos anchor;
        private final HashMap<BlockPos, BlockState> base = new HashMap<>();
        private final ArrayList<ItemEntity> drops = new ArrayList<>();
        private final ItemStack cargo, tool, manual;
        private volatile int stage = -1;
        private long since;
        private boolean diedFromHeat;
        private Vec3 deathPoint;
        private GlobalPos lastDeath;
        private Vec3 walkTarget, deathLocal;
        private double departureZ;
        private volatile BlockPos patchTarget, repairTarget;
        private int expectedRepairs;
        private volatile int trimRequest, currentTrim;

        private Scene(ServerPlayer player) {
            server = player.server;identity = player.getUUID();oldPlayer = player.saveWithoutId(new CompoundTag());
            oldLevel = player.serverLevel();oldPosition = player.position();oldYaw = player.getYRot();oldPitch = player.getXRot();oldMode = player.gameMode.getGameModeForPlayer();
            oldRespawnDimension = player.getRespawnDimension();oldRespawnPosition = player.getRespawnPosition();
            oldRespawnAngle = player.getRespawnAngle();oldRespawnForced = player.isRespawnForced();
            oldLastDeath = player.getLastDeathLocation();
            oldKeepInventory = server.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
            level = server.getLevel(Mantle.LEVEL);
            var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1,
                    java.util.List.of(new ItemStack(Items.BOOK), new ItemStack(Items.COPPER_INGOT)));
            manual = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, level)
                    .orElseThrow().value().assemble(input, level.registryAccess());
            var origin = WRECK_ORIGIN;
            var rescueOrigin = RESCUE_ORIGIN;
            if (voyage()) {
                for (var pos : BlockPos.betweenClosed(rescueOrigin.offset(-3, -80, -38), rescueOrigin.offset(15, 10, 10))) {
                    var state = level.getBlockState(pos);
                    if (state.is(Blocks.BASALT) || state.is(Blocks.SMOOTH_BASALT) || state.is(AeronauticsInnerworld.HARMONIC_ORE.get())) {
                        base.put(pos.immutable(), state);level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());
                    }
                }
            }
            rig = new ProbeTestRig(level, origin);rig.workEquipment();
            for (int y = 1; y <= 2; y++) level.setBlockAndUpdate(origin.offset(0, y, 2),
                    AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState().setValue(PressureHatchBlock.FACING, Direction.WEST));
            if (voyage()) {
                rescue = new ProbeTestRig(level, rescueOrigin);rescue.workEquipment();rescue.survivalPower();
                for (int y = 1; y <= 2; y++) level.setBlockAndUpdate(rescueOrigin.offset(4, y, 2),
                        AeronauticsInnerworld.PRESSURE_HATCH.get().defaultBlockState().setValue(PressureHatchBlock.FACING, Direction.EAST));
            } else rescue = null;
            var baseOrigin = voyage() ? rescueOrigin : origin;
            for (int x = -9; x <= -3; x++) for (int y = 5; y <= 9; y++) for (int z = 0; z <= 6; z++) {
                var pos = baseOrigin.offset(x, y, z);base.putIfAbsent(pos, level.getBlockState(pos));
                level.setBlockAndUpdate(pos, x == -9 || x == -3 || y == 5 || y == 9 || z == 0 || z == 6
                        ? AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            anchor = baseOrigin.offset(-6, 6, 3);level.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState());
            level.setBlockAndUpdate(anchor.above(3), Blocks.SEA_LANTERN.defaultBlockState());
            cargo = new ItemStack(AeronauticsInnerworld.MOLTEN_SCALE.get(), 3);cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Rescue cargo"));
            tool = new ItemStack(Items.NETHERITE_PICKAXE);tool.setDamageValue(17);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Rescue tool"));
            since = level.getGameTime();
        }

        private ServerPlayer player() {return server.getPlayerList().getPlayer(identity);}

        private void advance() {
            level.resetEmptyTime();var player = player();
            if (level.getGameTime() - since > (stage >= 11 ? 1800 : 700)) throw new IllegalStateException("Recovery stage stalled: " + stage
                    + ", health=" + player.getHealth() + ", position=" + player.position() + ", target=" + walkTarget
                    + ", exposure=" + player.getPersistentData().getInt("InnerworldHeatExposure")
                    + ", eyeFluid=" + level.getFluidState(BlockPos.containing(player.getEyePosition()))
                    + ", sealed=" + rig.cabin().isSealed());
            if (stage >= 11 && stage <= 20 && (!player.isAlive() || player.getHealth() < 6))
                throw new IllegalStateException("Rescue voyage lost its crew at " + player.position() + ", stage=" + stage);
            if (stage == -1 && rig.ready() && (rescue == null || rescue.ready())) {
                rig.assemble();rig.power();rig.cabin().addCoolant(600);rig.motor(ProbeTestRig.SAMPLER, 0);
                floodAssemblySource(WRECK_ORIGIN);
                if (rescue != null) {rescue.assemble();rescue.sampler().clearContent();}
                player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();
                player.getInventory().clearContent();player.getInventory().selected = 0;
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOWSTONE, 3));
                player.teleportTo(level, anchor.getX() - .5, anchor.getY(), anchor.getZ() + .5, -90, 30);player.containerMenu.broadcastChanges();next(0);
            } else if (stage == 0 && level.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE) == 3) {
                next(1);
            } else if (stage == 1 && anchor.equals(player.getRespawnPosition()) && player.getRespawnDimension().equals(Mantle.LEVEL) && rig.cabin().isSealed()) {
                next(2);
            } else if (stage == 2 && level.getGameTime() - since >= 100) {
                startDeath(true);next(3);
            } else if ((stage == 3 || stage == 5) && player.isDeadOrDying()) {
                if (!diedFromHeat) throw new IllegalStateException("Recovery death did not come from mantle heat");
                lastDeath = player.getLastDeathLocation().orElseThrow();
                if (!lastDeath.dimension().equals(Mantle.LEVEL) || Vec3.atCenterOf(lastDeath.pos()).distanceTo(deathPoint) > 2)
                    throw new IllegalStateException("Ship death was recorded outside its actual world location");
                if (stage == 3 && !drops.isEmpty()) throw new IllegalStateException("keepInventory produced loose inventory drops");
                if (stage == 5) {
                    int count = drops.stream().filter(item -> item.getItem().is(AeronauticsInnerworld.MOLTEN_SCALE.get())).mapToInt(item -> item.getItem().getCount()).sum();
                    if (count != 3 || drops.stream().noneMatch(item -> ItemStack.isSameItemSameComponents(item.getItem(), tool))
                            || drops.stream().anyMatch(item -> item.position().distanceTo(deathPoint) > 6))
                        throw new IllegalStateException("Native ship death lost items, components or world drop coordinates: " + drops.stream().map(ItemEntity::position).toList());
                }
                LogUtils.getLogger().info("Native mantle death: keepInventory={}, world position={}, drops={}", stage == 3, lastDeath.pos(), drops.size());
                next(stage == 3 ? 4 : 6);
            } else if ((stage == 4 || stage == 6) && player.isAlive()) {
                int expectedCharge = stage == 4 ? 2 : 1;
                if (!player.level().dimension().equals(Mantle.LEVEL) || player.position().distanceTo(Vec3.atCenterOf(anchor)) > 4
                        || level.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE) != expectedCharge)
                    throw new IllegalStateException("Native respawn did not return to the charged mantle anchor");
                var recovery = ProbeRecovery.deathTarget(player);
                if (recovery == null || !recovery.getUUID("Ship").equals(rig.subLevel.getUniqueId()))
                    throw new IllegalStateException("Native death and respawn lost the ship recovery anchor");
                if (stage == 4) {
                    if (player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) != 3
                            || player.getInventory().items.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, tool)))
                        throw new IllegalStateException("keepInventory lost the original cargo or tool after respawn");
                    startDeath(false);next(5);
                } else {
                    if (player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) != 0
                            || player.getInventory().countItem(Items.NETHERITE_PICKAXE) != 0)
                        throw new IllegalStateException("Dropping inventory also preserved a second copy after respawn");
                    player.getInventory().selected = 0;player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.RECOVERY_COMPASS));
                    player.containerMenu.broadcastChanges();next(7);
                }
            } else if (stage == 7 && compassVerified) {
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(AeronauticsInnerworld.HEATWEAVE_HELMET.get()));
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.get()));
                player.getInventory().setItem(1, new ItemStack(AeronauticsInnerworld.EMERGENCY_PATCH.get(), PATCH_STOCK));
                if (rescue != null) {
                    player.getInventory().setItem(2, new ItemStack(AeronauticsInnerworld.COOLANT_CELL.get(), 3));
                    player.getInventory().setItem(3, new ItemStack(AeronauticsInnerworld.REPAIR_KIT.get(), REPAIR_STOCK));
                }
                var boarding = rescue == null ? rig : rescue;
                var point = boarding.world(new BlockPos(2, 1, 2)).add(0, -.45, 0);
                var bounds = player.getBoundingBox().move(point.subtract(player.position()));
                if (!level.noCollision(player, bounds)) throw new IllegalStateException("The wreck has no safe crew return position");
                LogUtils.getLogger().info("Native return position={}, actual drops={}", point, drops.stream().map(ItemEntity::position).toList());
                player.teleportTo(level, point.x, point.y, point.z, 90, 0);player.containerMenu.broadcastChanges();next(rescue == null ? 8 : 11);
            } else if (stage == 8) {
                patchTarget = missingHull();
                if (!player.isAlive() || player.getHealth() < 6) throw new IllegalStateException("Rescue needs recovery: position=" + player.position()
                        + ", sealed=" + rig.cabin().isSealed() + ", heat=" + player.getPersistentData().getInt("InnerworldHeatExposure")
                        + ", drops=" + drops.stream().map(ItemEntity::position).toList());
                if (player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) == 3
                        && player.getInventory().countItem(Items.NETHERITE_PICKAXE) == 1 && rig.cabin().isSealed()) {
                    if (drops.stream().anyMatch(ItemEntity::isAlive)
                            || player.getInventory().items.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, cargo))
                            || player.getInventory().items.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, tool))
                            || player.getInventory().countItem(AeronauticsInnerworld.EMERGENCY_PATCH_ITEM.get())
                                    != PATCH_STOCK - patchedHull())
                        throw new IllegalStateException("Native pickup lost components or left duplicate loose cargo");
                    LogUtils.getLogger().info("Native rescue pickup conserved three scales and the damaged tool, patches={}, health={}", patchedHull(), player.getHealth());
                    if (rescue != null) {
                        expectedRepairs = repairCount();next(20);
                    } else next(9);
                }
            } else if (stage == 9 && hasCurrentWreckTarget(rig)) {
                if (!player.getMainHandItem().is(Items.RECOVERY_COMPASS) || player.getMainHandItem().getCount() != 1
                        || player.getMainHandItem().has(DataComponents.LODESTONE_TRACKER)
                        || !WRECK_ORIGIN.equals(rig.sounder().getHome()))
                    throw new IllegalStateException("Recovery navigation modified the compass or home berth");
                LogUtils.getLogger().info("Native recovery compass loaded the current wreck position into probe navigation; vanilla death={}", lastDeath.pos());next(10);
            } else if (stage == 20) {
                if (wrongWreckHud) throw new IllegalStateException("The rescue probe overwrote the wreck crew HUD");
                if (!wreckHudSeen || !movingBearingSeen) return;
                if (rig.cabin().getHeat() > 70) return;
                repairTarget = nextRepair();
                if (repairTarget == null) {
                    if (player.getInventory().countItem(AeronauticsInnerworld.REPAIR_KIT.get()) != REPAIR_STOCK - expectedRepairs)
                        throw new IllegalStateException("Wreck service consumed the wrong number of repair kits");
                    LogUtils.getLogger().info("Native cooled wreck service completed: repairs={}, heat={}, coolant={}",
                            expectedRepairs, rig.cabin().getHeat(), rig.cabin().getCoolant());next(9);
                }
            } else if (stage >= 11 && stage <= 19) {
                advanceVoyage();
            }
        }

        private boolean hasCurrentWreckTarget(ProbeTestRig navigator) {
            var target = navigator.sounder().getWaypoint();
            return target != null && Vec3.atCenterOf(target).distanceTo(rig.subLevel.logicalPose().transformPosition(deathLocal)) < 2;
        }

        private Iterable<BlockPos> hullCells() {
            return BlockPos.betweenClosed(rig.local(BlockPos.ZERO), rig.local(new BlockPos(4, 3, 6)));
        }

        private boolean hullBoundary(BlockPos pos) {
            var relative = pos.subtract(rig.local(BlockPos.ZERO));
            return relative.getX() == 0 || relative.getX() == 4 || relative.getY() == 0 || relative.getY() == 3
                    || relative.getZ() == 0 || relative.getZ() == 6;
        }

        private BlockPos missingHull() {
            for (var pos : hullCells()) {
                var state = level.getBlockState(pos);
                if (hullBoundary(pos) && !state.is(AeronauticsInnerworld.PRESSURE_HATCH.get())
                        && state.getCollisionShape(level, pos).isEmpty()) return pos.immutable();
            }
            return null;
        }

        private int patchedHull() {
            int count = 0;
            for (var pos : hullCells()) if (level.getBlockState(pos).is(AeronauticsInnerworld.EMERGENCY_PATCH.get())) count++;
            return count;
        }

        private int repairCount() {
            int count = 0;
            for (var actor : rig.subLevel.getPlot().getBlockEntityActors())
                if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) count++;
            for (var pos : hullCells()) if (level.getBlockState(pos).is(AeronauticsInnerworld.DAMAGED_HULL.get())) count++;
            return count;
        }

        private BlockPos nextRepair() {
            for (var actor : rig.subLevel.getPlot().getBlockEntityActors())
                if (actor instanceof ProbeDriveBlockEntity drive && drive.isFaulted()) return drive.getBlockPos();
            for (var pos : hullCells()) if (level.getBlockState(pos).is(AeronauticsInnerworld.DAMAGED_HULL.get())) return pos.immutable();
            return null;
        }

        private void floodAssemblySource(BlockPos origin) {
            // Native assembly leaves air where the hull stood; restore the lava around the assembled ship.
            int restored = 0;
            for (var pos : BlockPos.betweenClosed(origin, origin.offset(4, 3, 6))) {
                if (level.getBlockState(pos).isAir()) {
                    level.setBlockAndUpdate(pos, Blocks.LAVA.defaultBlockState());restored++;
                }
            }
            LogUtils.getLogger().info("Restored {} lava cells in the assembly source volume", restored);
        }

        private void advanceVoyage() {
            var player = player();
            if (stage == 16) walkTarget = rescue.world(RESCUE_HATCH.east()).add(0, -.45, 0);
            if (stage == 17) walkTarget = rig.world(new BlockPos(-1, 1, 2)).add(0, -.4, 0);
            if (stage == 19) walkTarget = rig.world(new BlockPos(2, 1, 2)).add(0, -.4, 0);
            if ((level.getGameTime() - since) % 100 == 0) LogUtils.getLogger().info("Rescue voyage stage={}, crew={}, spare={}, wreck={}",
                    stage, player.position(), rescue.world(new BlockPos(2, 1, 2)), rig.world(new BlockPos(2, 1, 2)));
            if (stage == 11 && rescue.cabin().getCoolant() > 0) {
                if (player.getInventory().countItem(AeronauticsInnerworld.COOLANT_CELL.get()) != 2 || !rescue.cabin().isSealed())
                    throw new IllegalStateException("Rescue preparation lost coolant or cabin protection");
                int engines = 0;
                var bounds = rescue.subLevel.getPlot().getBoundingBox();
                for (var pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                    var entity = level.getBlockEntity(pos);
                    if (entity instanceof CreativeMotorBlockEntity) throw new IllegalStateException("The rescue boat uses creative power");
                    if (entity instanceof PortableEngineBlockEntity engine) {
                        engines++;
                        if (engine.inventory.getItem(0).isEmpty() || engine.inventory.getItem(0).getCount() >= 4)
                            throw new IllegalStateException("Rescue engine did not consume its normal fuel");
                    }
                }
                if (engines != 7) throw new IllegalStateException("The rescue boat lost an engine");
                next(12);
            } else if (stage == 12 && hasCurrentWreckTarget(rescue)) {
                departureZ = rescue.world(new BlockPos(2, 1, 2)).z;next(13);
            } else if (stage == 13 && rescue.world(new BlockPos(2, 1, 2)).z <= rescue.sounder().getWaypoint().getZ() + .5) {
                double travelled = departureZ - rescue.world(new BlockPos(2, 1, 2)).z;
                if (travelled < 28 || !rescue.cabin().isSealed() || player.getPersistentData().getInt("InnerworldHeatExposure") != 0)
                    throw new IllegalStateException("The rescue approach did not preserve a protected native voyage");
                LogUtils.getLogger().info("Fueled rescue approach completed: distance={}, coolant={}, crew={}", travelled, rescue.cabin().getCoolant(), player.position());next(14);
            } else if (stage == 14) {
                var ballast = (ProbeDriveBlockEntity) level.getBlockEntity(rescue.local(new BlockPos(1, 0, 2)));
                var other = (ProbeDriveBlockEntity) level.getBlockEntity(rescue.local(new BlockPos(3, 0, 2)));
                currentTrim = ballast.getTrimMode();
                double delta = rescue.sounder().getWaypoint().getY() + .5 - rescue.world(RESCUE_HATCH).y;
                trimRequest = delta < -.65 ? -1 : delta > .65 ? 1 : 0;
                var velocity = RigidBodyHandle.of(rescue.subLevel).getLinearVelocity();
                if (Math.abs(delta) <= .65 && currentTrim == 0 && other.getTrimMode() == 0
                        && Math.abs(velocity.y()) < .15 && Math.hypot(velocity.x(), velocity.z()) < .05) {
                    LogUtils.getLogger().info("Native ballast aligned rescue hatches: height difference={}, coolant={}", delta, rescue.cabin().getCoolant());next(15);
                }
            } else if (stage == 15 && level.getBlockState(rescue.local(RESCUE_HATCH)).getValue(PressureHatchBlock.OPEN)) {
                walkTarget = rescue.world(RESCUE_HATCH.east()).add(0, -.45, 0);next(16);
            } else if (stage == 16 && player.position().distanceTo(walkTarget) < .2) {
                walkTarget = rig.world(new BlockPos(-1, 1, 2)).add(0, -.4, 0);next(17);
            } else if (stage == 17 && player.position().distanceTo(walkTarget) < .7) {
                next(18);
            } else if (stage == 18 && level.getBlockState(rig.local(new BlockPos(0, 2, 2))).getValue(PressureHatchBlock.OPEN)) {
                // A 1.8-block crew needs clearance above the floor and below the two-block doorway's ceiling.
                walkTarget = rig.world(new BlockPos(2, 1, 2)).add(0, -.4, 0);
                if (!level.noCollision(player, player.getBoundingBox().move(walkTarget.subtract(player.position()))))
                    throw new IllegalStateException("Wreck entry target does not fit the crew collision bounds");
                next(19);
            } else if (stage == 19 && player.position().distanceTo(walkTarget) < .7) {
                var local = rig.subLevel.logicalPose().transformPositionInverse(player.getBoundingBox().getCenter())
                        .subtract(Vec3.atLowerCornerOf(rig.local(BlockPos.ZERO)));
                if (local.x < 1 || local.x >= 4 || local.y < 1 || local.y >= 3 || local.z < 1 || local.z >= 6) return;
                LogUtils.getLogger().info("Native rescue transfer reached the wreck without position teleportation, health={}", player.getHealth());next(8);
            }
        }

        private void startDeath(boolean keep) {
            var player = player();server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(keep, server);
            player.getInventory().clearContent();player.getInventory().selected = 0;
            player.getInventory().setItem(0, cargo.copy());player.getInventory().setItem(1, tool.copy());
            player.experienceLevel = 0;player.totalExperience = 0;player.experienceProgress = 0;
            player.removeAllEffects();player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200));
            player.setHealth(player.getMaxHealth());player.getFoodData().setFoodLevel(6);player.getFoodData().setSaturation(0);
            player.getPersistentData().putInt("InnerworldHeatExposure", 0);diedFromHeat = false;
            var point = rig.world(new BlockPos(2, 1, 2)).add(0, -.45, 0);
            player.teleportTo(level, point.x, point.y, point.z, 180, 0);player.containerMenu.broadcastChanges();
            level.setBlockAndUpdate(rig.local(new BlockPos(0, 1, 2)), Blocks.AIR.defaultBlockState());
            if (!level.getFluidState(BlockPos.containing(player.getEyePosition())).is(FluidTags.LAVA))
                throw new IllegalStateException("The death fixture is not exposing the crew to lava");
        }

        private void next(int stage) {this.stage = stage;since = level.getGameTime();}

        private void cleanUp() {
            var player = player();
            if (!player.isAlive()) player = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            player.load(oldPlayer.copy());player.teleportTo(oldLevel, oldPosition.x, oldPosition.y, oldPosition.z, oldYaw, oldPitch);player.setGameMode(oldMode);
            player.setRespawnPosition(oldRespawnDimension, oldRespawnPosition, oldRespawnAngle, oldRespawnForced, false);
            player.setLastDeathLocation(oldLastDeath);
            player.onUpdateAbilities();player.containerMenu.broadcastChanges();
            player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
            player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
            server.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(oldKeepInventory, server);
            drops.forEach(ItemEntity::discard);
            if (rescue != null) rescue.cleanUp();
            rig.cleanUp();base.forEach(level::setBlockAndUpdate);
        }
    }
}

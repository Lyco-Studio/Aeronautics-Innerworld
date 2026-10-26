package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.integration.ProbeTestRig;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
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
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

@EventBusSubscriber(modid = "aeronautics_innerworld_checks", value = Dist.CLIENT)
public final class RecoveryClientCheck {
    private static int ticks, observedStage = -1, frames;
    private static boolean clicked, compassVerified, finished;
    private static CompletableFuture<Scene> setup;
    private static CompletableFuture<Void> step, cleanup;
    private static Scene scene;
    private static RuntimeException failure;

    @SubscribeEvent
    public static void recordDeath(LivingDeathEvent event) {
        if (!enabled() || scene == null || !event.getEntity().getUUID().equals(scene.identity)) return;
        // Reproducible native scatter keeps this check independent of previous player RNG use.
        event.getEntity().getRandom().setSeed(1);
        scene.diedFromHeat = event.getSource().is(ResourceKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "mantle_heat")));
        scene.deathPoint = event.getEntity().position();
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
            if (++ticks > 3600 && cleanup == null) throw new IllegalStateException("Recovery client timed out at " + observedStage);
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
                if (!clicked && client.player.getInventory().getItem(1).is(AeronauticsInnerworld.EMERGENCY_PATCH_ITEM.get())) {
                    client.player.getInventory().selected = 1;
                    var floor = scene.rig.local(new BlockPos(0, 0, 2));
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(floor).add(0, .5, 0), Direction.UP, floor, false));
                    clicked = true;
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
            var origin = new BlockPos(2304, 180, 128);
            rig = new ProbeTestRig(level, origin);rig.workEquipment();
            for (int x = -9; x <= -3; x++) for (int y = 5; y <= 9; y++) for (int z = 0; z <= 6; z++) {
                var pos = origin.offset(x, y, z);base.put(pos, level.getBlockState(pos));
                level.setBlockAndUpdate(pos, x == -9 || x == -3 || y == 5 || y == 9 || z == 0 || z == 6
                        ? AeronauticsInnerworld.REFRACTORY_HULL.get().defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            anchor = origin.offset(-6, 6, 3);level.setBlockAndUpdate(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState());
            level.setBlockAndUpdate(anchor.above(3), Blocks.SEA_LANTERN.defaultBlockState());
            cargo = new ItemStack(AeronauticsInnerworld.MOLTEN_SCALE.get(), 3);cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Rescue cargo"));
            tool = new ItemStack(Items.NETHERITE_PICKAXE);tool.setDamageValue(17);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("Rescue tool"));
            since = level.getGameTime();
        }

        private ServerPlayer player() {return server.getPlayerList().getPlayer(identity);}

        private void advance() {
            level.resetEmptyTime();var player = player();
            if (level.getGameTime() - since > 700) throw new IllegalStateException("Recovery stage stalled: " + stage + ", health=" + player.getHealth());
            if (stage == -1 && rig.ready()) {
                rig.assemble();rig.power();rig.cabin().addCoolant(600);rig.motor(ProbeTestRig.SAMPLER, 0);
                player.setGameMode(GameType.SURVIVAL);player.getAbilities().flying = false;player.onUpdateAbilities();
                player.getInventory().clearContent();player.getInventory().selected = 0;
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLOWSTONE, 3));
                player.teleportTo(level, anchor.getX() - .5, anchor.getY(), anchor.getZ() + .5, -90, 30);player.containerMenu.broadcastChanges();next(0);
            } else if (stage == 0 && level.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE) == 3) {
                next(1);
            } else if (stage == 1 && anchor.equals(player.getRespawnPosition()) && player.getRespawnDimension().equals(Mantle.LEVEL) && rig.cabin().isSealed()) {
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
                player.getInventory().setItem(1, new ItemStack(AeronauticsInnerworld.EMERGENCY_PATCH.get()));
                var point = rig.world(new BlockPos(2, 1, 2)).add(0, -.45, 0);
                var bounds = player.getBoundingBox().move(point.subtract(player.position()));
                if (!level.noCollision(player, bounds)) throw new IllegalStateException("The wreck has no safe crew return position");
                LogUtils.getLogger().info("Native return position={}, actual drops={}", point, drops.stream().map(ItemEntity::position).toList());
                player.teleportTo(level, point.x, point.y, point.z, 90, 0);player.containerMenu.broadcastChanges();next(8);
            } else if (stage == 8) {
                if (!player.isAlive() || player.getHealth() < 6) throw new IllegalStateException("Rescue needs recovery: position=" + player.position()
                        + ", sealed=" + rig.cabin().isSealed() + ", heat=" + player.getPersistentData().getInt("InnerworldHeatExposure")
                        + ", drops=" + drops.stream().map(ItemEntity::position).toList());
                if (player.getInventory().countItem(AeronauticsInnerworld.MOLTEN_SCALE.get()) == 3
                        && player.getInventory().countItem(Items.NETHERITE_PICKAXE) == 1 && rig.cabin().isSealed()) {
                    if (drops.stream().anyMatch(ItemEntity::isAlive)
                            || player.getInventory().items.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, cargo))
                            || player.getInventory().items.stream().noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, tool))
                            || player.getInventory().countItem(AeronauticsInnerworld.EMERGENCY_PATCH_ITEM.get()) != 0)
                        throw new IllegalStateException("Native pickup lost components or left duplicate loose cargo");
                    LogUtils.getLogger().info("Native rescue pickup conserved three scales and the damaged tool, health={}", player.getHealth());next(9);
                }
            } else if (stage == 9 && lastDeath.pos().equals(rig.sounder().getWaypoint())) {
                if (!player.getMainHandItem().is(Items.RECOVERY_COMPASS) || player.getMainHandItem().getCount() != 1
                        || player.getMainHandItem().has(DataComponents.LODESTONE_TRACKER)
                        || !new BlockPos(2304, 180, 128).equals(rig.sounder().getHome()))
                    throw new IllegalStateException("Recovery navigation modified the compass or home berth");
                LogUtils.getLogger().info("Native recovery compass loaded the actual world death location into probe navigation");next(10);
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
            drops.forEach(ItemEntity::discard);base.forEach(level::setBlockAndUpdate);rig.cleanUp();
        }
    }
}

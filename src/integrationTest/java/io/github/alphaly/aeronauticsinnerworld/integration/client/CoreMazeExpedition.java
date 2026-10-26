package io.github.alphaly.aeronauticsinnerworld.integration.client;

import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.world.Core;
import io.github.alphaly.aeronauticsinnerworld.world.structure.CoreMazePiece;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CoreMazeExpedition {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final BlockPos camp, entrance, valve;
    private final BoundingBox bounds;
    private final CoreConsoleBlockEntity console;
    private final CompoundTag originalConsole;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Map<BlockPos, CompoundTag> spawners = new HashMap<>();
    private final Set<UUID> originalEntities = new HashSet<>();
    private final Map<AdvancementHolder, Set<String>> progress = new HashMap<>();
    private volatile CoreMazeWalk walk;
    private volatile CoreWarden warden;
    private volatile int stage;
    private long since, started;
    private int observed = -1, frames;
    private float minimumHealth, lastBossHealth = 240;
    private boolean warning;
    private volatile boolean defending;
    private BlockPos destination;
    private boolean interaction;

    CoreMazeExpedition(ServerPlayer player, BlockPos camp) {
        this.player = player;this.camp = camp;level = player.server.getLevel(Core.LEVEL);
        if (player.serverLevel() != level) throw new IllegalStateException("Maze expedition must begin at an actual core arrival");
        var located = level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id("core_mazes")), player.blockPosition(), 4, false);
        if (located == null) throw new IllegalStateException("No natural maze near the core camp");
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id("core_maze"));
        var start = level.getChunkAt(located).getStartForStructure(structure);
        if (start == null || !start.isValid() || !(start.getPieces().getFirst() instanceof CoreMazePiece maze)) throw new IllegalStateException("Core camp has no natural maintenance maze");
        var box = start.getBoundingBox();entrance = maze.getEntrance();
        bounds = new BoundingBox(Math.min(box.minX() - 4, camp.getX() - 4), Math.min(box.minY() - 2, camp.getY() - 2), Math.min(box.minZ() - 4, camp.getZ() - 4),
                Math.max(box.maxX() + 4, camp.getX() + 4), box.maxY() + 2, Math.max(box.maxZ() + 4, camp.getZ() + 4));
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) level.getChunk(x, z);
        CoreConsoleBlockEntity found = null;
        for (var pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockEntity(pos) instanceof CoreConsoleBlockEntity control) found = control;
            if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) spawners.put(pos.immutable(), spawner.saveWithFullMetadata(level.registryAccess()));
        }
        if (found == null || found.hasVictory() || found.getBossId() != null) throw new IllegalStateException("Select an unused maze for the connected expedition");
        console = found;originalConsole = found.saveWithFullMetadata(level.registryAccess());
        for (var key : java.util.List.of("Nodes", "Gates", "Valves")) for (long packed : originalConsole.getLongArray(key)) {var pos = BlockPos.of(packed);blocks.put(pos, level.getBlockState(pos));}
        valve = BlockPos.of(originalConsole.getLongArray("Valves")[0]);
        level.getEntities(null, area()).forEach(entity -> originalEntities.add(entity.getUUID()));
        for (var advancement : player.server.getAdvancements().getAllAdvancements())
            progress.put(advancement, ImmutableSet.copyOf(player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria()));
        minimumHealth = player.getHealth();started = since = level.getGameTime();
        int swordWear = -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) if (player.getInventory().getItem(slot).is(Items.DIAMOND_SWORD)) swordWear = player.getInventory().getItem(slot).getDamageValue();
        LogUtils.getLogger().info("Connected maze begins at {}, entrance={}, health={}, sword wear={}, shield wear={}", player.position(), entrance, player.getHealth(),
                swordWear, player.getOffhandItem().getDamageValue());
    }

    private static ResourceLocation id(String path) {return ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, path);}
    private AABB area() {return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() + 1, bounds.maxY() + 1, bounds.maxZ() + 1).inflate(8);}

    void tickClient(Minecraft client) {
        if (!client.level.dimension().equals(Core.LEVEL)) return;
        if (observed != stage) {observed = stage;frames = 0;release(client);}
        frames++;
        if (stage == 0) {equip(client, AeronauticsInnerworld.CORE_SURVEYOR.get());if (frames == 10) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);}
        else if (stage == 7) fight(client);
        else if (walk != null && !surviveWalk(client)) walk.tickClient(client);
        if (stage == 7 && frames == 20) Screenshot.grab(client.gameDirectory, "connected-core-warden.png", client.getMainRenderTarget(),
                message -> LogUtils.getLogger().info("Connected Warden screenshot: {}", message.getString()));
    }

    boolean advance() {
        level.resetEmptyTime();minimumHealth = Math.min(minimumHealth, player.getHealth());
        if (!player.isAlive() || player.getHealth() <= 8 || level.getGameTime() - since > 3600)
            throw new IllegalStateException("Connected maze stalled: stage=" + stage + ", position=" + player.position() + ", health=" + player.getHealth() + ", arrived=" + (walk == null || walk.arrived()));
        if (walk != null) walk.advance(player.position());
        if (defending) return false;
        if (stage == 0 && player.getCooldowns().isOnCooldown(AeronauticsInnerworld.CORE_SURVEYOR.get())) walkTo(entrance, false, 1);
        else if (stage == 1 && walk.arrived()) walkTo(BlockPos.of(originalConsole.getLongArray("Nodes")[0]), true, 2);
        else if (stage >= 2 && stage <= 4) {
            var node = BlockPos.of(originalConsole.getLongArray("Nodes")[stage - 2]);
            if (!level.getBlockState(node).getValue(LeverBlock.POWERED)) return false;
            LogUtils.getLogger().info("Connected maze restored node {} at {}, health={}", stage - 1, node, player.getHealth());
            if (stage == 4) walkTo(console.getBlockPos(), true, 5);
            else walkTo(BlockPos.of(originalConsole.getLongArray("Nodes")[stage - 1]), true, stage + 1);
        } else if (stage == 5 && console.getBossId() != null && level.getEntity(console.getBossId()) instanceof CoreWarden active) {
            warden = active;
            if (warden.getHealth() != warden.getMaxHealth() || warden.isNoAi()) throw new IllegalStateException("Connected expedition requires a full-health active Warden");
            walkTo(valve, true, 6);
        } else if (stage == 6 && warden.isCoreExposed()) {walk = null;next(7);}
        else if (stage == 7) {
            warning |= warden.getHeatZone() != null;
            if ((int)warden.getHealth() / 20 != (int)lastBossHealth / 20) {
                LogUtils.getLogger().info("Connected Warden duel: boss={}, player={}, elapsed={}", warden.getHealth(), player.getHealth(), level.getGameTime() - since);lastBossHealth = warden.getHealth();
            }
            if (!warden.isAlive() && console.getBossId() == null && console.hasVictory()) {
                if (!warning || !player.getAdvancements().getOrStartProgress(player.server.getAdvancements().get(id("exploration/core_victory"))).isDone())
                    throw new IllegalStateException("Connected combat did not record its heat warning and story completion");
                walkTo(warden.blockPosition(), false, 8);
            }
        } else if (stage == 8 && player.getInventory().countItem(AeronauticsInnerworld.CORE_HEART.get()) == 1) walkTo(camp, false, 9);
        else if (stage == 9 && walk.arrived()) {
            LogUtils.getLogger().info("Connected maze complete: core heart=1, minimum health={}, ticks={}", minimumHealth, level.getGameTime() - started);next(10);return true;
        }
        return stage == 10;
    }

    private void walkTo(BlockPos pos, boolean interact, int next) {destination = pos;interaction = interact;walk = new CoreMazeWalk(level, bounds, player.blockPosition(), pos, interact, true);next(next);}
    private void next(int value) {stage = value;since = level.getGameTime();LogUtils.getLogger().info("Connected maze stage {}", value);}
    static void release(Minecraft client) {client.options.keyUp.setDown(false);client.options.keyDown.setDown(false);client.options.keyJump.setDown(false);client.options.keyShift.setDown(false);client.options.keyUse.setDown(false);if (client.player != null && client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);}
    private static void equip(Minecraft client, Item item) {
        for (int slot = 0; slot < 36; slot++) if (client.player.getInventory().getItem(slot).is(item)) {
            if (slot < 9) client.player.getInventory().selected = slot;
            else {client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slot, 0, ClickType.SWAP, client.player);client.player.getInventory().selected = 0;}return;
        }
        throw new IllegalStateException("Connected expedition lost its prepared " + item);
    }

    private boolean surviveWalk(Minecraft client) {
        Monster target = null;
        double nearest = 64;
        for (var entity : client.level.entitiesForRendering()) if (entity instanceof Monster mob && !(mob instanceof CoreWarden) && mob.isAlive()
                && client.player.hasLineOfSight(mob) && client.player.distanceToSqr(mob) < nearest) {target = mob;nearest = client.player.distanceToSqr(mob);}
        boolean eat = client.player.getFoodData().getFoodLevel() <= 18
                || client.player.getHealth() < 18 && client.player.getFoodData().getFoodLevel() < 20;
        if (target == null && !eat) {
            if (defending) {
                release(client);
                var position = client.player.blockPosition();
                walk = player.server.submit(() -> new CoreMazeWalk(level, bounds, position, destination, interaction, true)).join();
                defending = false;
            }
            return false;
        }
        defending = true;
        client.options.keyShift.setDown(false);client.options.keyJump.setDown(false);
        client.options.keyUp.setDown(!eat && nearest > 5);client.options.keyDown.setDown(eat && target != null && nearest < 16);
        if (target != null) {
            var offset = target.getEyePosition().subtract(client.player.getEyePosition());
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
            client.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())));
        }
        var item = eat ? Items.COOKED_BEEF : Items.DIAMOND_SWORD;
        if (!client.player.getMainHandItem().is(item) && client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
        equip(client, item);
        if (eat) {
            client.options.keyUse.setDown(true);
            if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        } else if (nearest < 10 && client.player.getAttackStrengthScale(0) > .95f) {
            client.options.keyUse.setDown(false);
            if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
            client.gameMode.attack(client.player, target);client.player.swing(InteractionHand.MAIN_HAND);
        } else {
            client.options.keyUse.setDown(true);
            if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.OFF_HAND);
        }
        return true;
    }

    private void fight(Minecraft client) {
        CoreWarden target = null;
        for (var entity : client.level.entitiesForRendering()) if (entity instanceof CoreWarden boss && boss.getUUID().equals(warden.getUUID())) {target = boss;break;}
        if (target == null || !target.isAlive()) {release(client);return;}
        var zone = target.getHeatZone();
        if (zone != null && client.player.position().subtract(Vec3.atBottomCenterOf(zone)).horizontalDistanceSqr() < 3.5 * 3.5) {
            var away = client.player.position().subtract(Vec3.atBottomCenterOf(zone)).multiply(1, 0, 1);
            if (away.lengthSqr() < .1) away = new Vec3(1, 0, 0);away = away.normalize();
            for (int turn = 0; turn < 4; turn++) {if (client.level.noCollision(client.player, client.player.getBoundingBox().move(away.scale(1.5)))) break;away = new Vec3(-away.z, 0, away.x);}
            client.player.setYRot((float)Math.toDegrees(Math.atan2(-away.x, away.z)));client.player.setXRot(0);
            client.options.keyUp.setDown(true);client.options.keyDown.setDown(false);client.options.keyUse.setDown(false);
            if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);return;
        }
        var offset = target.position().add(0, 1.5, 0).subtract(client.player.getEyePosition());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));client.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance())));
        double distance = client.player.position().distanceTo(target.position());boolean eat = client.player.getFoodData().getFoodLevel() <= 16;
        client.options.keyUp.setDown(!eat && distance > 2.2);client.options.keyDown.setDown(eat && distance < 9);
        var item = eat ? Items.COOKED_BEEF : Items.DIAMOND_SWORD;
        if (!client.player.getMainHandItem().is(item) && client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);equip(client, item);
        if (eat) {client.options.keyUse.setDown(true);if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);}
        else if (target.getAttackType() != 0) {client.options.keyUse.setDown(true);if (!client.player.isUsingItem()) client.gameMode.useItem(client.player, InteractionHand.OFF_HAND);}
        else {client.options.keyUse.setDown(false);if (client.player.isUsingItem()) client.gameMode.releaseUsingItem(client.player);
            if (distance < 3.2 && client.player.getAttackStrengthScale(0) > .95f) {client.gameMode.attack(client.player, target);client.player.swing(InteractionHand.MAIN_HAND);}}
    }

    void cleanUp() {
        if (warden != null) warden.discard();blocks.forEach(level::setBlockAndUpdate);
        console.loadWithComponents(originalConsole, level.registryAccess());console.setChanged();
        spawners.forEach((pos, tag) -> {var block = level.getBlockEntity(pos);block.loadWithComponents(tag, level.registryAccess());block.setChanged();});
        for (var entity : level.getEntities(null, area())) if (!originalEntities.contains(entity.getUUID())
                && (entity instanceof Mob || entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof AbstractArrow)) entity.discard();
        progress.forEach((advancement, criteria) -> {
            for (var criterion : ImmutableSet.copyOf(player.getAdvancements().getOrStartProgress(advancement).getCompletedCriteria()))
                if (!criteria.contains(criterion)) player.getAdvancements().revoke(advancement, criterion);
        });
    }
}

package io.github.alphaly.aeronauticsinnerworld.content.core;

import java.util.HashSet;
import java.util.UUID;
import java.util.Optional;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

public final class CoreWarden extends Monster {
    private static final EntityDataAccessor<Integer> ATTACK = SynchedEntityData.defineId(CoreWarden.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> EXPOSED = SynchedEntityData.defineId(CoreWarden.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<BlockPos>> HEAT_ZONE = SynchedEntityData.defineId(CoreWarden.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final EntityDataAccessor<Boolean> HEAT_ACTIVE = SynchedEntityData.defineId(CoreWarden.class, EntityDataSerializers.BOOLEAN);
    private static final ResourceKey<DamageType> CORE_HEAT = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(AeronauticsInnerworld.MOD_ID, "core_heat"));
    private final ServerBossEvent bar = new ServerBossEvent(Component.translatable("entity.aeronautics_innerworld.core_warden"),
            BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
    private final HashSet<UUID> participants = new HashSet<>();
    private BlockPos home;
    private BlockPos console;
    private Vec3 strike;
    private int windup;
    private int cooldown = 60;
    private int exposure;
    private int heatTicks;
    private int heatCooldown = 100;

    public CoreWarden(EntityType<? extends CoreWarden> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 80;
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 240).add(Attributes.ARMOR, 8)
                .add(Attributes.MOVEMENT_SPEED, .24).add(Attributes.ATTACK_DAMAGE, 9)
                .add(Attributes.FOLLOW_RANGE, 32).add(Attributes.KNOCKBACK_RESISTANCE, 1);
    }

    @Override
    protected void registerGoals() {
        targetSelector.addGoal(0, new HurtByTargetGoal(this));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ATTACK, 0);builder.define(EXPOSED, false);
        builder.define(HEAT_ZONE, Optional.empty());builder.define(HEAT_ACTIVE, false);
    }

    public void setHome(BlockPos home, BlockPos console) {
        this.home = home.immutable();this.console = console.immutable();
    }

    public int getAttackType() {
        return entityData.get(ATTACK);
    }

    public boolean isCoreExposed() {
        return entityData.get(EXPOSED);
    }

    public BlockPos getHeatZone() {
        return entityData.get(HEAT_ZONE).orElse(null);
    }

    public boolean isHeatActive() {
        return entityData.get(HEAT_ACTIVE);
    }

    public void exposeCore(int ticks) {
        exposure = Math.max(exposure, ticks);
        entityData.set(EXPOSED, true);
        clearHeat();heatCooldown = Math.max(heatCooldown, ticks);
    }

    public void recordParticipant(ServerPlayer player) {
        participants.add(player.getUUID());
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float damage) {
        if (source.getEntity() instanceof ServerPlayer player) recordParticipant(player);
        return super.hurt(source, isCoreExposed() ? damage : damage * .45f);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        var server = (ServerLevel) level();
        if (home == null) home = blockPosition();
        if (exposure > 0) exposure--;
        int phase = getHealth() > 160 ? 0 : getHealth() > 80 ? 1 : 2;
        entityData.set(EXPOSED, exposure > 0 || phase == 2);
        bar.setProgress(getHealth() / getMaxHealth());
        bar.setColor(phase == 2 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW);
        var target = getTarget();
        if (target == null || !target.isAlive() || target.distanceToSqr(Vec3.atCenterOf(home)) > 32 * 32) {
            setTarget(null);getNavigation().moveTo(home.getX() + .5, home.getY(), home.getZ() + .5, .8);
            windup = 0;entityData.set(ATTACK, 0);clearHeat();heatCooldown = 100;return;
        }
        tickHeat(server, target, phase);
        if (windup > 0) {
            getNavigation().stop();
            getLookControl().setLookAt(target);
            int attack = getAttackType();
            var center = attack == 1 ? strike : position();
            double radius = attack == 1 ? 2.5 : attack == 2 ? 4 : 6;
            if (windup % 4 == 0) {
                for (int i = 0; i < 24; i++) {
                    double angle = i * Math.PI / 12;
                    server.sendParticles(ParticleTypes.FLAME, center.x + Math.cos(angle) * radius, getY() + .15,
                            center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
                }
            }
            if (--windup == 0) {
                for (var player : server.getEntitiesOfClass(Player.class, getBoundingBox().inflate(12))) {
                    double dx = player.getX() - center.x, dz = player.getZ() - center.z;
                    if (!player.isSpectator() && Math.abs(player.getY() - getY()) < 4 && dx * dx + dz * dz <= radius * radius) {
                        player.hurt(damageSources().mobAttack(this), attack == 3 ? 12 : 9);
                        player.knockback(.7, -dx, -dz);
                    }
                }
                playSound(SoundEvents.IRON_GOLEM_ATTACK, 1.5f, .5f);
                entityData.set(ATTACK, 0);cooldown = 65 - phase * 15;
            }
            return;
        }
        getNavigation().moveTo(target, 1 + phase * .1);
        if (cooldown > 0) cooldown--;
        if (cooldown == 0 && distanceToSqr(target) < 10 * 10 && hasLineOfSight(target)) {
            strike = target.position();
            int attack = phase == 0 ? 1 : 1 + random.nextInt(phase + 1);
            entityData.set(ATTACK, attack);windup = attack == 3 ? 40 : 30;
            playSound(SoundEvents.IRON_GOLEM_REPAIR, 1.5f, .5f);
            target.sendSystemMessage(Component.translatable("message.aeronautics_innerworld.warden_attack_" + attack));
        }
    }

    private void tickHeat(ServerLevel server, LivingEntity target, int phase) {
        if (phase == 0 || exposure > 0) {clearHeat();return;}
        var zone = getHeatZone();
        if (zone == null) {
            if (--heatCooldown > 0 || target.distanceToSqr(Vec3.atCenterOf(home)) > 12 * 12 || !hasLineOfSight(target)) return;
            zone = target.blockPosition();
            entityData.set(HEAT_ZONE, Optional.of(zone));heatTicks = 110;
            playSound(SoundEvents.FIRECHARGE_USE, 1.2f, .6f);
            target.sendSystemMessage(Component.translatable("message.aeronautics_innerworld.warden_heat_warning"));
            return;
        }
        if (--heatTicks <= 0) {clearHeat();heatCooldown = 60;return;}
        boolean active = heatTicks <= 80;
        entityData.set(HEAT_ACTIVE, active);
        var center = Vec3.atBottomCenterOf(zone);
        if (heatTicks % 8 == 0) {
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                server.sendParticles(active ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME,
                        center.x + Math.cos(angle) * 2.5, center.y + .15, center.z + Math.sin(angle) * 2.5, 1, 0, 0, 0, 0);
            }
        }
        if (!active || heatTicks % 20 != 0) return;
        var source = new DamageSource(registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(CORE_HEAT), this);
        for (var player : server.getEntitiesOfClass(Player.class, new AABB(zone).inflate(3))) {
            if (player.isCreative() || player.isSpectator() || !player.isAlive() || Math.abs(player.getY() - center.y) >= 2
                    || player.position().subtract(center).horizontalDistanceSqr() > 2.5 * 2.5) continue;
            float protection = 1;
            if (player.getItemBySlot(EquipmentSlot.HEAD).is(AeronauticsInnerworld.HEATWEAVE_HELMET.get())) protection -= .25f;
            if (player.getItemBySlot(EquipmentSlot.CHEST).is(AeronauticsInnerworld.HEATWEAVE_CHESTPLATE.get())) protection -= .5f;
            player.hurt(source, 3 * protection);
        }
    }

    private void clearHeat() {
        heatTicks = 0;
        entityData.set(HEAT_ZONE, Optional.empty());entityData.set(HEAT_ACTIVE, false);
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);bar.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);bar.removePlayer(player);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!(level() instanceof ServerLevel server)) return;
        if (console != null && server.getBlockEntity(console) instanceof CoreConsoleBlockEntity control) control.defeated(getUUID());
        var advancement = server.getServer().getAdvancements().get(ResourceLocation.fromNamespaceAndPath("aeronautics_innerworld", "exploration/core_victory"));
        for (var identity : participants) {
            var player = server.getServer().getPlayerList().getPlayer(identity);
            if (player == null || advancement == null) continue;
            boolean first = !player.getAdvancements().getOrStartProgress(advancement).isDone();
            player.getAdvancements().award(advancement, "defeat");
            if (first) player.sendSystemMessage(Component.translatable("message.aeronautics_innerworld.core_ending"));
        }
        bar.removeAllPlayers();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (home != null) tag.putLong("Home", home.asLong());
        if (console != null) tag.putLong("Console", console.asLong());
        tag.putInt("Exposure", exposure);
        var players = new net.minecraft.nbt.ListTag();
        participants.forEach(id -> players.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
        tag.put("Participants", players);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        home = tag.contains("Home") ? BlockPos.of(tag.getLong("Home")) : null;
        console = tag.contains("Console") ? BlockPos.of(tag.getLong("Console")) : null;
        exposure = Math.clamp(tag.getInt("Exposure"), 0, 160);
        participants.clear();
        for (var entry : tag.getList("Participants", 8)) {
            try {participants.add(UUID.fromString(entry.getAsString()));} catch (IllegalArgumentException ignored) { }
        }
        cooldown = 60;windup = 0;entityData.set(ATTACK, 0);
        clearHeat();heatCooldown = 100;
    }
}

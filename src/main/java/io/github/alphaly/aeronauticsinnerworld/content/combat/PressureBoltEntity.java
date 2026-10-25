package io.github.alphaly.aeronauticsinnerworld.content.combat;

import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public final class PressureBoltEntity extends ThrowableItemProjectile {
    public PressureBoltEntity(EntityType<? extends PressureBoltEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected Item getDefaultItem() {
        return AeronauticsInnerworld.PRESSURE_BOLT.get();
    }

    @Override
    protected double getDefaultGravity() {
        return 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && tickCount > 24) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (!level().isClientSide) {
            hit.getEntity().hurt(damageSources().thrown(this, getOwner()), 6);
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (!level().isClientSide) {
            discard();
        }
    }
}

package io.github.alphaly.aeronauticsinnerworld.integration;

import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.MantleSamplerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

final class NaturalOreSource {
    private final GameTestHelper helper;
    private final ServerLevel level;
    private final ProbeTestRig rig;
    private final FakePlayer pilot;
    private final BlockPos ore;
    private final BlockState original;
    private boolean foundBySounder;
    private boolean cleaned;
    private int ticks;

    NaturalOreSource(GameTestHelper helper) {
        this.helper = helper;level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        ore = findDeposit();original = level.getBlockState(ore);
        var origin = ore.offset(-3, -1, 3);
        rig = new ProbeTestRig(level, origin, false);
        rig.workEquipment();rig.survivalPower();
        pilot = new FakePlayer(level, new GameProfile(UUID.fromString("2a753afd-412a-417c-b913-e7e733cb2698"), "MineralPilot"));
        ((MantleSamplerBlockEntity) level.getBlockEntity(rig.source(ProbeTestRig.SAMPLER))).clearContent();
        LogUtils.getLogger().info("Natural industrial source: deposit={}, boat={}, unchanged mining approach", ore, origin);
    }

    private BlockPos findDeposit() {
        for (int cx = 240; cx < 248; cx++) for (int cz = 240; cz < 248; cz++) {
            level.getChunk(cx, cz);
            for (var pos : BlockPos.betweenClosed(cx * 16, 166, cz * 16, cx * 16 + 15, 194, cz * 16 + 15)) {
                if (!level.getBlockState(pos).is(AeronauticsInnerworld.HARMONIC_ORE.get())) continue;
                var origin = pos.offset(-3, -1, 3);
                boolean clear = true;
                for (var block : BlockPos.betweenClosed(origin.offset(-1, -2, -2), origin.offset(5, 5, 8))) {
                    if (!level.hasChunkAt(block) || !level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {clear = false;break;}
                }
                if (clear) return pos.immutable();
            }
        }
        throw new IllegalStateException("No naturally exposed mineral with room for the sampling boat");
    }

    void tick() {
        if (cleaned) return;
        level.resetEmptyTime();
        if (rig.subLevel == null) {
            if (rig.ready()) rig.assemble();
            return;
        }
        if (!foundBySounder && Math.abs(rig.sounder().getSpeed()) >= 16) {
            rig.sounder().scan();
            var mineral = rig.sounder().getMineral();
            helper.assertTrue(mineral != null && level.getBlockState(mineral).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                    "Sounder did not discover the untouched natural mineral");
            foundBySounder = true;
        }
        if (++ticks % 10 == 0) {
            double height = rig.world(ProbeTestRig.SAMPLER).y;
            int trim = height < ore.getY() + .3 ? 1 : height > ore.getY() + .7 ? -1 : 0;
            pilot.setShiftKeyDown(true);
            for (int x : new int[] {1, 3}) {
                var pos = rig.local(new BlockPos(x, 0, 2));
                var drive = (ProbeDriveBlockEntity) level.getBlockEntity(pos);
                for (int i = 0; i < 3 && drive.getTrimMode() != trim; i++)
                    pilot.gameMode.useItemOn(pilot, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos).add(0, .49, 0), Direction.UP, pos, false));
                helper.assertTrue(drive.getTrimMode() == trim, "Natural mining trim input did not reach the paired tanks");
            }
            pilot.setShiftKeyDown(false);
        }
    }

    ItemStack recover() {
        helper.assertTrue(rig.subLevel != null && foundBySounder, "Waiting for fueled natural mineral discovery");
        var inventory = rig.sampler().inventory;
        int raw = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            helper.assertTrue(stack.isEmpty() || stack.is(AeronauticsInnerworld.RAW_HARMONIC.get()), "Natural sampler collected unexpected material");
            raw += stack.getCount();
        }
        helper.assertTrue(raw >= 1 && !level.getBlockState(ore).is(AeronauticsInnerworld.HARMONIC_ORE.get()),
                "Waiting for the naturally mined sample: cargo=" + raw + ", status=" + rig.sampler().getStatus()
                        + ", target=" + rig.sampler().getTarget() + ", sampler=" + rig.world(ProbeTestRig.SAMPLER));
        for (int slot = 0; slot < inventory.getSlots(); slot++) if (!inventory.getStackInSlot(slot).isEmpty()) {
            LogUtils.getLogger().info("Fueled sampler recovered natural ore for industrial processing; sampling ticks={}", ticks);
            var result = inventory.extractItem(slot, 1, false);
            cleanUp();return result;
        }
        throw new IllegalStateException("Natural cargo could not be recovered for processing");
    }

    void cleanUp() {
        if (cleaned) return;
        cleaned = true;
        rig.cleanUp();level.setBlockAndUpdate(ore, original);
    }
}

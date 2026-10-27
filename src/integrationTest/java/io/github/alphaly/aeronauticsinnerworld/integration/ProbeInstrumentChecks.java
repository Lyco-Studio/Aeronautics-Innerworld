package io.github.alphaly.aeronauticsinnerworld.integration;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import io.github.alphaly.aeronauticsinnerworld.AeronauticsInnerworld;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.world.Mantle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("aeronautics_innerworld_instruments")
@PrefixGameTestTemplate(false)
public final class ProbeInstrumentChecks {
    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void updatesMovingDisplayAndRecoversAfterReload(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(Mantle.LEVEL);
        var rig = new ProbeTestRig(level, new BlockPos(512, 170, 512));
        rig.workEquipment();rig.instruments();
        helper.onEachTick(level::resetEmptyTime);
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo test) { }
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo test, net.minecraft.gametest.framework.GameTestRunner runner) { rig.cleanUp(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo test, net.minecraft.gametest.framework.GameTestRunner runner) { rig.cleanUp(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldTest, net.minecraft.gametest.framework.GameTestInfo newTest,
                    net.minecraft.gametest.framework.GameTestRunner runner) { }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.ready(), "Waiting for instrument fixture; glue=" + level.getEntitiesOfClass(
                com.simibubi.create.content.contraptions.glue.SuperGlueEntity.class,
                new net.minecraft.world.phys.AABB(510, 168, 510, 520, 178, 522)).size()))
                .thenExecute(() -> {
                    helper.assertTrue(DisplaySource.getAll(level, rig.source(ProbeTestRig.SOUNDER)).contains(AeronauticsInnerworld.PROBE_NAVIGATION.get()),
                            "Sounder is absent from Create display sources");
                    rig.assemble();rig.power();
                    rig.instrumentLink().target(rig.local(ProbeTestRig.INSTRUMENT_DISPLAY));
                    rig.instrumentLink().activeSource = AeronauticsInnerworld.PROBE_NAVIGATION.get();
                    rig.instrumentLink().setChanged();
                    rig.motor(ProbeTestRig.SAMPLER, 0);
                    rig.motor(ProbeTestRig.LEFT_DRIVE, 64);rig.motor(ProbeTestRig.RIGHT_DRIVE, 64);
                }).thenExecuteAfter(40, () -> {
                    helper.assertTrue(rig.instrumentLink().getTargetPosition().equals(rig.local(ProbeTestRig.INSTRUMENT_DISPLAY)),
                            "Assembly disconnected the ship display: expected=" + rig.local(ProbeTestRig.INSTRUMENT_DISPLAY)
                                    + ", actual=" + rig.instrumentLink().getTargetPosition() + ", data="
                                    + rig.instrumentLink().saveWithFullMetadata(level.registryAccess()));
                    assertDisplay(helper, rig, "goggle.aeronautics_innerworld.velocity");
                    ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE))).fail();
                    rig.instrumentLink().activeSource = AeronauticsInnerworld.PROBE_DAMAGE.get();
                }).thenExecuteAfter(30, () -> {
                    assertDisplay(helper, rig, "goggle.aeronautics_innerworld.damaged_device");
                    rig.unload();helper.runAfterDelay(2, rig::restore);
                }).thenExecuteAfter(30, () -> {
                    helper.assertTrue(rig.instrumentLink().activeSource == AeronauticsInnerworld.PROBE_DAMAGE.get(),
                            "Reload lost the selected instrument source");
                    assertDisplay(helper, rig, "goggle.aeronautics_innerworld.damaged_device");
                    ((ProbeDriveBlockEntity) level.getBlockEntity(rig.local(ProbeTestRig.LEFT_DRIVE))).repair();
                }).thenWaitUntil(() -> assertDisplay(helper, rig, "goggle.aeronautics_innerworld.no_damage"))
                .thenExecute(() -> {
                    rig.sounder().setSpeed(0);
                }).thenExecuteAfter(25, () -> assertDisplay(helper, rig, "goggle.aeronautics_innerworld.sounder_unpowered"))
                .thenSucceed();
    }

    private static void assertDisplay(GameTestHelper helper, ProbeTestRig rig, String key) {
        var display = rig.instrumentDisplay();
        helper.assertTrue(display != null && display.getLines().stream().flatMap(line -> line.getSections().stream())
                .map(section -> section.getText()).filter(java.util.Objects::nonNull).anyMatch(text -> hasKey(text, key)),
                "Instrument display did not receive " + key + "; source=" + rig.instrumentLink().getSourcePosition()
                        + "; sounder=" + rig.sounder().getSpeed() + "; source data=" + rig.instrumentLink().activeSource
                        + "; display=" + (display == null ? "missing" : display.saveWithFullMetadata(rig.level.registryAccess())));
    }

    public static boolean hasKey(Component text, String key) {
        return text.getContents() instanceof TranslatableContents translated && translated.getKey().equals(key);
    }
}

package io.github.alphaly.aeronauticsinnerworld;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillContraption;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillControllerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillHeadEntity;
import io.github.alphaly.aeronauticsinnerworld.content.drill.MultiAxisCutterMovementBehaviour;
import io.github.alphaly.aeronauticsinnerworld.content.drill.DrillRemoteItem;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlock;
import io.github.alphaly.aeronauticsinnerworld.content.drill.BoreStationBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDeviceBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeDriveBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.CabinRegulatorBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.PressureHatchBlock;
import io.github.alphaly.aeronauticsinnerworld.content.probe.EchoSounderBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.MantleSamplerBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.probe.ProbeServiceItem;
import io.github.alphaly.aeronauticsinnerworld.world.structure.InnerworldStructures;
import io.github.alphaly.aeronauticsinnerworld.content.combat.LavaStalker;
import io.github.alphaly.aeronauticsinnerworld.content.combat.PressureBoltEntity;
import io.github.alphaly.aeronauticsinnerworld.content.combat.PressureCannonBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlock;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreConsoleBlockEntity;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreWarden;
import io.github.alphaly.aeronauticsinnerworld.content.core.CoreSurveyorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

@Mod(AeronauticsInnerworld.MOD_ID)
public final class AeronauticsInnerworld {
    public static final String MOD_ID = "aeronautics_innerworld";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<ContraptionType> CONTRAPTIONS = DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, MOD_ID);
    private static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS = DeferredRegister.create(Registries.ARMOR_MATERIAL, MOD_ID);

    public static final DeferredBlock<Block> MULTI_AXIS_CUTTER = BLOCKS.registerSimpleBlock(
            "multi_axis_cutter", BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> MULTI_AXIS_CUTTER_ITEM = ITEMS.registerSimpleBlockItem(MULTI_AXIS_CUTTER);
    public static final DeferredBlock<DrillControllerBlock> DRILL_CONTROLLER = BLOCKS.register(
            "drill_controller", () -> new DrillControllerBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f).sound(SoundType.METAL).requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> DRILL_CONTROLLER_ITEM = ITEMS.registerSimpleBlockItem(DRILL_CONTROLLER);
    public static final DeferredItem<DrillRemoteItem> DRILL_REMOTE = ITEMS.register("drill_remote",
            () -> new DrillRemoteItem(new Item.Properties().stacksTo(1)));
    public static final DeferredBlock<BoreStationBlock> BORE_STATION = BLOCKS.register("bore_station",
            () -> new BoreStationBlock(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> BORE_STATION_ITEM = ITEMS.registerSimpleBlockItem(BORE_STATION);
    public static final DeferredBlock<Block> REFRACTORY_HULL = BLOCKS.registerSimpleBlock("refractory_hull",
            BlockBehaviour.Properties.of().strength(5, 9).sound(SoundType.NETHER_BRICKS).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> REFRACTORY_HULL_ITEM = ITEMS.registerSimpleBlockItem(REFRACTORY_HULL);
    public static final DeferredBlock<Block> MANTLE_VIEWPORT = BLOCKS.registerSimpleBlock("mantle_viewport",
            BlockBehaviour.Properties.of().strength(4, 9).sound(SoundType.GLASS).requiresCorrectToolForDrops().noOcclusion());
    public static final DeferredItem<BlockItem> MANTLE_VIEWPORT_ITEM = ITEMS.registerSimpleBlockItem(MANTLE_VIEWPORT);
    public static final DeferredBlock<PressureHatchBlock> PRESSURE_HATCH = BLOCKS.register("pressure_hatch",
            () -> new PressureHatchBlock(BlockBehaviour.Properties.of().strength(5, 9).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredItem<BlockItem> PRESSURE_HATCH_ITEM = ITEMS.registerSimpleBlockItem(PRESSURE_HATCH);
    public static final DeferredBlock<ProbeDeviceBlock> MOLTEN_THRUSTER = BLOCKS.register("molten_thruster",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.THRUSTER));
    public static final DeferredItem<BlockItem> MOLTEN_THRUSTER_ITEM = ITEMS.registerSimpleBlockItem(MOLTEN_THRUSTER);
    public static final DeferredBlock<ProbeDeviceBlock> MANTLE_BALLAST = BLOCKS.register("mantle_ballast",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.BALLAST));
    public static final DeferredItem<BlockItem> MANTLE_BALLAST_ITEM = ITEMS.registerSimpleBlockItem(MANTLE_BALLAST);
    public static final DeferredBlock<ProbeDeviceBlock> CABIN_REGULATOR = BLOCKS.register("cabin_regulator",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.CABIN));
    public static final DeferredItem<BlockItem> CABIN_REGULATOR_ITEM = ITEMS.registerSimpleBlockItem(CABIN_REGULATOR);
    public static final DeferredBlock<ProbeDeviceBlock> ECHO_SOUNDER = BLOCKS.register("echo_sounder",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.SOUNDER));
    public static final DeferredItem<BlockItem> ECHO_SOUNDER_ITEM = ITEMS.registerSimpleBlockItem(ECHO_SOUNDER);
    public static final DeferredBlock<ProbeDeviceBlock> MANTLE_SAMPLER = BLOCKS.register("mantle_sampler",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.SAMPLER));
    public static final DeferredItem<BlockItem> MANTLE_SAMPLER_ITEM = ITEMS.registerSimpleBlockItem(MANTLE_SAMPLER);
    public static final DeferredBlock<ProbeDeviceBlock> PRESSURE_CANNON = BLOCKS.register("pressure_cannon",
            () -> new ProbeDeviceBlock(BlockBehaviour.Properties.of().strength(4, 9).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops(), ProbeDeviceBlock.Kind.CANNON));
    public static final DeferredItem<BlockItem> PRESSURE_CANNON_ITEM = ITEMS.registerSimpleBlockItem(PRESSURE_CANNON);
    public static final DeferredItem<Item> PRESSURE_BOLT = ITEMS.registerSimpleItem("pressure_bolt", new Item.Properties().fireResistant());
    public static final DeferredItem<Item> MOLTEN_SCALE = ITEMS.registerSimpleItem("molten_scale", new Item.Properties().fireResistant());
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> HEATWEAVE = ARMOR_MATERIALS.register("heatweave",
            () -> new ArmorMaterial(java.util.Map.of(ArmorItem.Type.HELMET, 2, ArmorItem.Type.CHESTPLATE, 5), 12,
                    SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(MOLTEN_SCALE.get()),
                    java.util.List.of(new ArmorMaterial.Layer(ResourceLocation.fromNamespaceAndPath(MOD_ID, "heatweave"))), 0, 0));
    public static final DeferredItem<ArmorItem> HEATWEAVE_HELMET = ITEMS.register("heatweave_helmet",
            () -> new ArmorItem(HEATWEAVE, ArmorItem.Type.HELMET, new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(18)).fireResistant()));
    public static final DeferredItem<ArmorItem> HEATWEAVE_CHESTPLATE = ITEMS.register("heatweave_chestplate",
            () -> new ArmorItem(HEATWEAVE, ArmorItem.Type.CHESTPLATE, new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(18)).fireResistant()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PressureCannonBlockEntity>> PRESSURE_CANNON_ENTITY =
            BLOCK_ENTITIES.register("pressure_cannon", () -> BlockEntityType.Builder.of(PressureCannonBlockEntity::new,
                    PRESSURE_CANNON.get()).build(null));
    public static final DeferredBlock<Block> HARMONIC_ORE = BLOCKS.registerSimpleBlock("harmonic_ore",
            BlockBehaviour.Properties.of().strength(4).sound(SoundType.DEEPSLATE).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> HARMONIC_ORE_ITEM = ITEMS.registerSimpleBlockItem(HARMONIC_ORE);
    public static final DeferredItem<Item> RAW_HARMONIC = ITEMS.registerSimpleItem("raw_harmonic", new Item.Properties().fireResistant());
    public static final DeferredItem<Item> HARMONIC_CRYSTAL = ITEMS.registerSimpleItem("harmonic_crystal", new Item.Properties().fireResistant());
    public static final DeferredItem<Item> RELIC_ALLOY = ITEMS.registerSimpleItem("relic_alloy", new Item.Properties().fireResistant());
    public static final DeferredItem<Item> PRESSURE_MATRIX = ITEMS.registerSimpleItem("pressure_matrix", new Item.Properties().fireResistant());
    public static final DeferredBlock<Block> DEEP_BORE_COUPLER = BLOCKS.registerSimpleBlock("deep_bore_coupler",
            BlockBehaviour.Properties.of().strength(6, 12).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> DEEP_BORE_COUPLER_ITEM = ITEMS.registerSimpleBlockItem(DEEP_BORE_COUPLER);
    public static final DeferredBlock<CoreConsoleBlock> CORE_CONSOLE = BLOCKS.register("core_console",
            () -> new CoreConsoleBlock(BlockBehaviour.Properties.of().strength(-1, 3600000).sound(SoundType.METAL).lightLevel(state -> 8)));
    public static final DeferredBlock<Block> CORE_SEAL = BLOCKS.registerSimpleBlock("core_seal",
            BlockBehaviour.Properties.of().strength(-1, 3600000).sound(SoundType.METAL));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoreConsoleBlockEntity>> CORE_CONSOLE_ENTITY =
            BLOCK_ENTITIES.register("core_console", () -> BlockEntityType.Builder.of(CoreConsoleBlockEntity::new, CORE_CONSOLE.get()).build(null));
    public static final DeferredItem<CoreSurveyorItem> CORE_SURVEYOR = ITEMS.register("core_surveyor",
            () -> new CoreSurveyorItem(new Item.Properties().stacksTo(1).fireResistant()));
    public static final DeferredItem<Item> CORE_HEART = ITEMS.registerSimpleItem("core_heart", new Item.Properties().fireResistant());
    public static final DeferredBlock<Block> RELAY_BEACON = BLOCKS.registerSimpleBlock("relay_beacon",
            BlockBehaviour.Properties.of().strength(5, 9).sound(SoundType.COPPER).requiresCorrectToolForDrops()
                    .lightLevel(state -> 10));
    public static final DeferredItem<BlockItem> RELAY_BEACON_ITEM = ITEMS.registerSimpleBlockItem(RELAY_BEACON);
    public static final DeferredBlock<Block> DAMAGED_HULL = BLOCKS.registerSimpleBlock("damaged_hull",
            BlockBehaviour.Properties.of().strength(2, 3).sound(SoundType.NETHER_BRICKS).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> DAMAGED_HULL_ITEM = ITEMS.registerSimpleBlockItem(DAMAGED_HULL);
    public static final DeferredBlock<Block> EMERGENCY_PATCH = BLOCKS.registerSimpleBlock("emergency_patch",
            BlockBehaviour.Properties.of().strength(2, 3).sound(SoundType.METAL).requiresCorrectToolForDrops());
    public static final DeferredItem<BlockItem> EMERGENCY_PATCH_ITEM = ITEMS.registerSimpleBlockItem(EMERGENCY_PATCH);
    public static final DeferredItem<ProbeServiceItem> COOLANT_CELL = ITEMS.register("coolant_cell",
            () -> new ProbeServiceItem(new Item.Properties().stacksTo(16), 120));
    public static final DeferredItem<ProbeServiceItem> STABILIZED_COOLANT_CELL = ITEMS.register("stabilized_coolant_cell",
            () -> new ProbeServiceItem(new Item.Properties().stacksTo(16), 240));
    public static final DeferredItem<ProbeServiceItem> REPAIR_KIT = ITEMS.register("repair_kit",
            () -> new ProbeServiceItem(new Item.Properties().stacksTo(16), 0));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EchoSounderBlockEntity>> ECHO_SOUNDER_ENTITY =
            BLOCK_ENTITIES.register("echo_sounder", () -> BlockEntityType.Builder.of(EchoSounderBlockEntity::new,
                    ECHO_SOUNDER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MantleSamplerBlockEntity>> MANTLE_SAMPLER_ENTITY =
            BLOCK_ENTITIES.register("mantle_sampler", () -> BlockEntityType.Builder.of(MantleSamplerBlockEntity::new,
                    MANTLE_SAMPLER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProbeDriveBlockEntity>> PROBE_DRIVE_ENTITY =
            BLOCK_ENTITIES.register("probe_drive", () -> BlockEntityType.Builder.of(ProbeDriveBlockEntity::new,
                    MOLTEN_THRUSTER.get(), MANTLE_BALLAST.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CabinRegulatorBlockEntity>> CABIN_REGULATOR_ENTITY =
            BLOCK_ENTITIES.register("cabin_regulator", () -> BlockEntityType.Builder.of(CabinRegulatorBlockEntity::new,
                    CABIN_REGULATOR.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BoreStationBlockEntity>> BORE_STATION_ENTITY =
            BLOCK_ENTITIES.register("bore_station", () -> BlockEntityType.Builder.of(
                    BoreStationBlockEntity::new, BORE_STATION.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DrillControllerBlockEntity>> DRILL_CONTROLLER_ENTITY =
            BLOCK_ENTITIES.register("drill_controller", () -> BlockEntityType.Builder.of(
                    DrillControllerBlockEntity::new, DRILL_CONTROLLER.get()).build(null));
    public static final DeferredHolder<ContraptionType, ContraptionType> DRILL_CONTRAPTION =
            CONTRAPTIONS.register("drill_head", () -> new ContraptionType(DrillContraption::new));
    public static final DeferredHolder<EntityType<?>, EntityType<DrillHeadEntity>> DRILL_HEAD_ENTITY =
            ENTITIES.register("drill_head", () -> EntityType.Builder.<DrillHeadEntity>of(DrillHeadEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(12).updateInterval(3).fireImmune().build(MOD_ID + ":drill_head"));
    public static final DeferredHolder<EntityType<?>, EntityType<LavaStalker>> LAVA_STALKER =
            ENTITIES.register("lava_stalker", () -> EntityType.Builder.of(LavaStalker::new, MobCategory.MONSTER)
                    .sized(1.1f, .7f).clientTrackingRange(8).fireImmune().build(MOD_ID + ":lava_stalker"));
    public static final DeferredHolder<EntityType<?>, EntityType<LavaStalker>> MANTLE_GRAZER =
            ENTITIES.register("mantle_grazer", () -> EntityType.Builder.of(LavaStalker::new, MobCategory.CREATURE)
                    .sized(1.6f, 1).clientTrackingRange(8).fireImmune().build(MOD_ID + ":mantle_grazer"));
    public static final DeferredHolder<EntityType<?>, EntityType<PressureBoltEntity>> PRESSURE_BOLT_ENTITY =
            ENTITIES.register("pressure_bolt", () -> EntityType.Builder.<PressureBoltEntity>of(PressureBoltEntity::new, MobCategory.MISC)
                    .sized(.25f, .25f).clientTrackingRange(8).updateInterval(1).fireImmune().build(MOD_ID + ":pressure_bolt"));
    public static final DeferredHolder<EntityType<?>, EntityType<CoreWarden>> CORE_WARDEN =
            ENTITIES.register("core_warden", () -> EntityType.Builder.of(CoreWarden::new, MobCategory.MONSTER)
                    .sized(1.8f, 3.2f).clientTrackingRange(12).fireImmune().build(MOD_ID + ":core_warden"));

    public AeronauticsInnerworld(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, InnerworldConfig.SPEC);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        CONTRAPTIONS.register(modBus);
        ARMOR_MATERIALS.register(modBus);
        InnerworldStructures.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::buildCreativeTab);
        modBus.addListener(this::capabilities);
        modBus.addListener(this::attributes);
        modBus.addListener(this::spawnPlacements);
    }

    private void attributes(EntityAttributeCreationEvent event) {
        event.put(LAVA_STALKER.get(), LavaStalker.attributes().build());
        event.put(CORE_WARDEN.get(), CoreWarden.attributes().build());
        event.put(MANTLE_GRAZER.get(), LavaStalker.attributes().add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 40)
                .add(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, 4).build());
    }

    private void spawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(LAVA_STALKER.get(), SpawnPlacementTypes.NO_RESTRICTIONS, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                LavaStalker::canSpawn, RegisterSpawnPlacementsEvent.Operation.REPLACE);
        event.register(MANTLE_GRAZER.get(), SpawnPlacementTypes.NO_RESTRICTIONS, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                LavaStalker::canSpawn, RegisterSpawnPlacementsEvent.Operation.REPLACE);
    }

    private void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, MANTLE_SAMPLER_ENTITY.get(),
                (entity, side) -> entity.inventory);
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, PRESSURE_CANNON_ENTITY.get(),
                (entity, side) -> entity.ammunition);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            MovementBehaviour.REGISTRY.register(MULTI_AXIS_CUTTER.get(), new MultiAxisCutterMovementBehaviour());
            BlockMovementChecks.registerNotSupportiveCheck((state, direction) ->
                    state.is(MULTI_AXIS_CUTTER) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) -> {
                if (state.is(DRILL_CONTROLLER) || state.is(CORE_CONSOLE) || state.is(CORE_SEAL) || (state.is(BORE_STATION)
                        && level.getBlockEntity(pos) instanceof BoreStationBlockEntity station && station.getPartner() != null)) {
                    return CheckResult.FAIL;
                }
                return CheckResult.PASS;
            });
        });
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(MULTI_AXIS_CUTTER_ITEM);
            event.accept(DRILL_CONTROLLER_ITEM);
            event.accept(DRILL_REMOTE);
            event.accept(BORE_STATION_ITEM);
            event.accept(REFRACTORY_HULL_ITEM);
            event.accept(MANTLE_VIEWPORT_ITEM);
            event.accept(PRESSURE_HATCH_ITEM);
            event.accept(MOLTEN_THRUSTER_ITEM);
            event.accept(MANTLE_BALLAST_ITEM);
            event.accept(CABIN_REGULATOR_ITEM);
            event.accept(ECHO_SOUNDER_ITEM);
            event.accept(MANTLE_SAMPLER_ITEM);
            event.accept(PRESSURE_CANNON_ITEM);
            event.accept(PRESSURE_BOLT);
            event.accept(MOLTEN_SCALE);
            event.accept(HEATWEAVE_HELMET);
            event.accept(HEATWEAVE_CHESTPLATE);
            event.accept(HARMONIC_ORE_ITEM);
            event.accept(RAW_HARMONIC);
            event.accept(HARMONIC_CRYSTAL);
            event.accept(RELIC_ALLOY);
            event.accept(PRESSURE_MATRIX);
            event.accept(DEEP_BORE_COUPLER_ITEM);
            event.accept(CORE_SURVEYOR);
            event.accept(CORE_HEART);
            event.accept(RELAY_BEACON_ITEM);
            event.accept(DAMAGED_HULL_ITEM);
            event.accept(EMERGENCY_PATCH_ITEM);
            event.accept(COOLANT_CELL);
            event.accept(STABILIZED_COOLANT_CELL);
            event.accept(REPAIR_KIT);
        }
    }
}

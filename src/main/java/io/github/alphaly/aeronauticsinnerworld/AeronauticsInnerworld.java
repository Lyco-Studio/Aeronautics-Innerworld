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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
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

@Mod(AeronauticsInnerworld.MOD_ID)
public final class AeronauticsInnerworld {
    public static final String MOD_ID = "aeronautics_innerworld";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<ContraptionType> CONTRAPTIONS = DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, MOD_ID);

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
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DrillControllerBlockEntity>> DRILL_CONTROLLER_ENTITY =
            BLOCK_ENTITIES.register("drill_controller", () -> BlockEntityType.Builder.of(
                    DrillControllerBlockEntity::new, DRILL_CONTROLLER.get()).build(null));
    public static final DeferredHolder<ContraptionType, ContraptionType> DRILL_CONTRAPTION =
            CONTRAPTIONS.register("drill_head", () -> new ContraptionType(DrillContraption::new));
    public static final DeferredHolder<EntityType<?>, EntityType<DrillHeadEntity>> DRILL_HEAD_ENTITY =
            ENTITIES.register("drill_head", () -> EntityType.Builder.<DrillHeadEntity>of(DrillHeadEntity::new, MobCategory.MISC)
                    .sized(1, 1).clientTrackingRange(12).updateInterval(3).fireImmune().build(MOD_ID + ":drill_head"));

    public AeronauticsInnerworld(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, InnerworldConfig.SPEC);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        CONTRAPTIONS.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::buildCreativeTab);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            MovementBehaviour.REGISTRY.register(MULTI_AXIS_CUTTER.get(), new MultiAxisCutterMovementBehaviour());
            BlockMovementChecks.registerNotSupportiveCheck((state, direction) ->
                    state.is(MULTI_AXIS_CUTTER) ? CheckResult.SUCCESS : CheckResult.PASS);
            BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) ->
                    state.is(DRILL_CONTROLLER) ? CheckResult.FAIL : CheckResult.PASS);
        });
    }

    private void buildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) {
            event.accept(MULTI_AXIS_CUTTER_ITEM);
            event.accept(DRILL_CONTROLLER_ITEM);
            event.accept(DRILL_REMOTE);
        }
    }
}

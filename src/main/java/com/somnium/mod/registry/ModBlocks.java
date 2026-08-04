package com.somnium.mod.registry;

import com.somnium.mod.SomniumMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Блоки мода.
 *  - DREAM_ORE ("Руда сноведений") — добывается в обычном мире киркой (уровень камня),
 *    генерируется жилами как медь (см. data/somnium/worldgen/configured_feature/dream_ore.json).
 *  - DREAM_BLOCK ("Блок сноведений") — крафтится из 9 слитков; крепкий, как обсидиан —
 *    из него строится рамка портала в мир снов (по аналогии с обсидианом портала в ад).
 *  - DREAM_PORTAL ("Портал снов") — внутренность рамки после поджога зажигалкой;
 *    стояние внутри 4 секунды переносит в мир снов и обратно (см. DreamPortalHelper).
 */
public final class ModBlocks {

    private ModBlocks() {}

    public static final Block DREAM_ORE = register("dream_ore", new Block(
            AbstractBlock.Settings.create()
                    .mapColor(MapColor.STONE_GRAY)
                    .requiresTool()
                    .strength(3.0f, 3.0f) // как медная руда
                    .sounds(BlockSoundGroup.STONE)));

    public static final Block DREAM_BLOCK = register("dream_block", new Block(
            AbstractBlock.Settings.create()
                    .mapColor(MapColor.PURPLE)
                    .requiresTool()
                    .strength(25.0f, 1200.0f) // крепкий, ближе к обсидиану — рамка портала
                    .luminance(state -> 7)
                    .sounds(BlockSoundGroup.LODESTONE)));

    public static final com.somnium.mod.block.DreamPortalBlock DREAM_PORTAL =
            register("dream_portal", new com.somnium.mod.block.DreamPortalBlock(
                    AbstractBlock.Settings.create()
                            .mapColor(MapColor.PURPLE)
                            .noCollision()
                            .nonOpaque()
                            .luminance(state -> 11)
                            .strength(-1.0f, 3600000.0f) // нельзя сломать рукой, как портал ада
                            .dropsNothing()
                            .pistonBehavior(PistonBehavior.BLOCK)
                            .sounds(BlockSoundGroup.GLASS)));

    private static <T extends Block> T register(String path, T block) {
        RegistryKey<Block> key = RegistryKey.of(RegistryKeys.BLOCK, SomniumMod.id(path));
        Registry.register(Registries.BLOCK, key, block);
        // Портал не имеет предметной формы — он только в мире, как портал ада
        if (!(block instanceof com.somnium.mod.block.DreamPortalBlock)) {
            RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, SomniumMod.id(path));
            Registry.register(Registries.ITEM, itemKey, new BlockItem(block, new Item.Settings()));
        }
        return block;
    }

    public static void register() {
        SomniumMod.LOGGER.info("[Somnium] Блоки зарегистрированы");
    }
}

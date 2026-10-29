package com.somnium.mod.haunt;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CarvedPumpkinBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.SignBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationPropertyHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Постройки-видения: появляются рядом с игроком (перед ним, за спиной, вдали или кольцом
 * вокруг) и через несколько секунд исчезают. Блоки ложные: их видит только этот игрок,
 * настоящий мир не меняется.
 *
 * Шаблон — слои снизу вверх, начиная с высоты base (0 — уровень ног, -1 — земля).
 * В слое ряды через "/" идут от ближнего к дальнему, символы ряда — слева направо.
 * Пробел — ничего, остальные символы — блоки из state().
 */
final class FakeStructures {

    enum Place { FRONT, BEHIND, FAR, AROUND }

    private record Build(String id, float maxSanity, Place place, int dist, int life, int base,
                         @Nullable SoundEvent sound, @Nullable String sign, String[] layers) {}

    private static final List<Build> BUILDS = new ArrayList<>();

    private FakeStructures() {}

    private static void b(String id, float maxSanity, Place place, int dist, int life, int base,
                          @Nullable SoundEvent sound, @Nullable String sign, String... layers) {
        BUILDS.add(new Build(id, maxSanity, place, dist, life, base, sound, sign, layers));
    }

    static void registerAll() {
        for (Build build : BUILDS) {
            WorldEvents.add("build_" + build.id(), build.maxSanity(), 3, (p, w, r) -> place(p, w, r, build));
        }
    }

    static int count() {
        return BUILDS.size();
    }

    // Кольцо из символа c шириной n (n нечётное), остальное пусто
    private static String ring(int n, char c) {
        StringBuilder out = new StringBuilder();
        double radius = (n - 1) / 2.0;
        for (int z = 0; z < n; z++) {
            if (z > 0) out.append('/');
            for (int x = 0; x < n; x++) {
                double d = Math.sqrt((x - radius) * (x - radius) + (z - radius) * (z - radius));
                out.append(Math.abs(d - radius) < 0.6 ? c : ' ');
            }
        }
        return out.toString();
    }

    private static String fill(int w, int d, char c) {
        StringBuilder out = new StringBuilder();
        for (int z = 0; z < d; z++) {
            if (z > 0) out.append('/');
            out.append(String.valueOf(c).repeat(w));
        }
        return out.toString();
    }

    private static String[] repeat(String layer, int times) {
        String[] out = new String[times];
        java.util.Arrays.fill(out, layer);
        return out;
    }

    static {
        Place F = Place.FRONT, B = Place.BEHIND, R = Place.FAR, A = Place.AROUND;
        SoundEvent none = null;

        b("grave", 45, F, 6, 240, 0, SoundEvents.BLOCK_GRAVEL_BREAK, "grave",
                "   / ! / 7 / 7 / S ", "   /   /   /   / S ", "   /   /   /   /SSS", "   /   /   /   / S ");
        b("cross_wood", 45, B, 4, 200, 0, none, null, " F ", " F ", "FFF", " F ");
        b("cross_stone", 45, R, 20, 300, 0, none, null, " W ", " W ", " W ", "WWW", " W ");
        b("shrine", 40, B, 5, 240, 0, SoundEvents.BLOCK_CANDLE_EXTINGUISH, null, "###/#K#/###", "c c/   /c c");
        b("lone_door", 45, F, 7, 300, 0, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, null, "d", "u");
        b("bed_behind", 40, B, 2, 200, 0, none, null, "v/n");
        b("torch_ring", 40, A, 0, 160, 0, SoundEvents.BLOCK_FIRE_AMBIENT, null, ring(7, 'R'));
        b("obsidian_pillar", 45, R, 22, 300, 0, none, null, "O", "O", "O", "O", "O", "O", "l");
        b("stairs_nowhere", 45, F, 6, 300, 0, none, null,
                "4/4/4/4/4/4", " /4/4/4/4/4", " / /4/4/4/4", " / / /4/4/4", " / / / /4/4", " / / / / /4");
        b("pit", 35, F, 6, 160, -7, SoundEvents.BLOCK_LAVA_AMBIENT, null,
                "VV/VV", "../..", "../..", "../..", "../..", "../..", "../..");
        b("shack", 45, F, 9, 300, 0, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, null, "CdC/CLC/CCC", "CuC/g g/CgC", "CCC/CCC/CCC");
        b("well", 45, F, 8, 300, -1, SoundEvents.BLOCK_WATER_AMBIENT, null,
                "   / w /   ", "SSS/S S/SSS", "F F/   /F F", "DDD/DDD/DDD");
        b("scarecrow", 40, B, 5, 240, 0, none, null, " F ", " H ", "FHF", " P ");
        b("cage", 45, F, 6, 240, 0, SoundEvents.BLOCK_CHAIN_PLACE, null, "III/IKI/III", "III/I I/III", "III/III/III");
        b("cage_around", 25, A, 0, 60, 0, SoundEvents.BLOCK_IRON_DOOR_CLOSE, null, "III/I I/III", "III/I I/III");
        b("skull_pile", 40, B, 4, 200, 0, none, null, "XKX/KXK", " K / X ");
        b("nether_portal", 40, R, 22, 300, 0, SoundEvents.BLOCK_PORTAL_AMBIENT, null,
                "OOOO", "O99O", "O99O", "O99O", "OOOO");
        b("blood_splat", 40, A, 0, 300, -1, none, null, "  r  / rrr /rr rr/ rrr /  r  ");
        b("footprints", 40, B, 1, 300, -1, none, null, "2 / 2/2 / 2/2 / 2/2 / 2");
        b("sign_circle", 35, A, 0, 240, 0, none, "sign", "  !  /     /!   !/     /  !  ");
        b("lantern_path", 45, F, 3, 300, 0, none, null, "F/ / /F/ / /F/ / /F", "L/ / /L/ / /L/ / /L");
        b("monolith", 45, R, 25, 300, 0, none, null, repeat("BB", 6));
        b("dead_tree", 45, B, 7, 300, 0, SoundEvents.BLOCK_WOOD_BREAK, null, " x ", " x ", " x ", "xxx", "x x", "x  ");
        b("gallows", 35, F, 8, 300, 0, SoundEvents.BLOCK_CHAIN_STEP, null, "D  ", "D P", "D i", "DDD");
        b("wall_behind", 40, B, 2, 120, 0, SoundEvents.BLOCK_STONE_PLACE, null, "CCCCC", "CCCCC", "CCCCC");
        b("doorway", 45, F, 6, 300, 0, none, null, "S S", "S S", "SSS");
        b("tombstone_row", 40, F, 7, 300, 0, none, null, "S S S S S", "S S S S S");
        b("candle_circle", 40, A, 0, 240, 0, SoundEvents.BLOCK_CANDLE_AMBIENT, null, ring(5, 'c'));
        b("pumpkin_ring", 35, A, 0, 200, 0, none, null, ring(7, 'J'));
        b("snowman", 45, B, 3, 200, 0, SoundEvents.BLOCK_SNOW_STEP, null, "+", "+", "P");
        b("lone_chest", 45, F, 6, 300, 0, none, "chest", "!/h");
        b("bell_post", 45, R, 16, 200, 0, SoundEvents.BLOCK_BELL_USE, null, "F", "F", "b");
        b("pyramid", 45, R, 24, 300, 0, none, null, fill(5, 5, 'a'), "     / aaa / aaa / aaa /     ", "     /     /  a  /     /     ");
        b("totem", 40, B, 6, 240, 0, none, null, "o", "P", "o", "J", "o", "k");
        b("mirror_wall", 35, F, 3, 120, 0, SoundEvents.BLOCK_GLASS_PLACE, null, "GGG", "GGG", "GGG");
        b("crying_ring", 35, A, 0, 200, 0, none, null, ring(7, 'Y'));
        b("sculk_patch", 30, A, 0, 200, -1, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, null, fill(5, 5, 'Q'));
        b("cobweb_ring", 30, A, 0, 120, 0, SoundEvents.ENTITY_SPIDER_AMBIENT, null, ring(5, 'Z'));
        b("lava_pool", 40, F, 10, 200, -1, SoundEvents.BLOCK_LAVA_POP, null, "VVV/VVV");
        b("fire_ring", 30, A, 0, 100, 0, SoundEvents.ITEM_FLINTANDSTEEL_USE, null, ring(7, 'f'));
        b("wither_roses", 40, A, 0, 240, 0, none, null, "U   U/  U  /U   U/ U U /  U  ");
        b("black_cube", 40, F, 10, 300, 0, none, null, "BdB/BBB/BBB", "BuB/BBB/BBB", "BBB/BBB/BBB");
        b("spawner", 40, F, 5, 200, 0, SoundEvents.BLOCK_FIRE_AMBIENT, null, "M", "f");
        b("end_rods", 45, R, 18, 300, 0, none, null, repeat("E", 7));
        b("target_ring", 40, A, 0, 200, 0, none, null, ring(7, 't'));
        b("torch_arrow", 40, F, 2, 300, 0, none, null, "  T  /  T  /  T  /  T  /T T T/ TTT /  T  ");
        b("hatches", 40, F, 4, 200, 0, none, null, "z z z/     /z z z/     /z z z");
        b("lamp_post", 45, R, 14, 300, 0, none, null, "D", "D", "D", "L");
        String henge = "     4     /           / 4       4 /           /           /4         4/"
                + "           /           / 4       4 /           /     4     ";
        b("stone_henge", 40, A, 0, 300, 0, none, null, henge, henge, henge);
        b("tnt_stack", 30, B, 3, 120, 0, SoundEvents.ENTITY_TNT_PRIMED, null, "y", "y", "y");
        b("bookshelves", 45, F, 6, 300, 0, SoundEvents.ITEM_BOOK_PAGE_TURN, null, "eee", "eee", "eee");
        b("bone_tree", 40, R, 15, 300, 0, none, null, "X", "X", "X", "X", "XXX", " X ");
        b("lit_hut", 45, R, 18, 300, 0, none, null, "CdC/CLC/CCC", "CuC/G G/CGC", "CCC/CCC/CCC");
        b("netherrack_cross", 35, B, 6, 240, 0, SoundEvents.BLOCK_FIRE_AMBIENT, null, " N ", " N ", "NNN", " N ", " f ");
        b("ice_statue", 40, F, 7, 300, 0, SoundEvents.BLOCK_GLASS_HIT, null, "p p", " p ", "ppp", " p ");
        b("coffin", 40, F, 5, 240, 0, SoundEvents.BLOCK_CHEST_CLOSE, null, "O/O/O", "O/O/O");
        b("graves_around", 35, A, 0, 300, 0, none, null,
                "S       S/         /   S     /         /S       S/         /     S   /         /S       S");
        b("door_behind", 40, B, 2, 160, 0, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, null, "d", "u");
        b("observers", 35, F, 5, 200, 0, SoundEvents.BLOCK_PISTON_CONTRACT, null, "j", "j", "j", "j");
        b("pumpkin_wall", 40, F, 8, 240, 0, none, null, "PPPPP", "PPPPP", "PPPPP");
        b("red_carpet", 40, F, 2, 300, 0, none, null, "5/5/5/5/5/6");
        b("signpost", 45, F, 5, 300, 0, none, "road", "F", "F", "!");
        b("floating_window", 40, F, 6, 240, 0, none, null, " ", "SGS", "SGS", "SSS");
        b("mossy_ruin", 45, R, 14, 300, 0, none, null, "%&% %&/%   &", "% &  %");
        b("obsidian_ring", 35, A, 0, 240, 0, none, null, ring(9, 'O'));
        b("skull_row", 40, F, 5, 240, 0, none, null, "K K K K K");
        b("soul_fire_ring", 30, A, 0, 160, -1, SoundEvents.BLOCK_SOUL_SAND_BREAK, null, ring(5, '3'), ring(5, 's'));
        b("black_door_far", 40, R, 20, 300, 0, none, null, "OdO", "OuO", "OOO");
        b("hole_behind", 35, B, 3, 120, -4, none, null, "../..", "../..", "../..", "../..");
    }

    /** Блок по символу; toPlayer — направление от постройки к игроку. */
    @Nullable
    private static BlockState state(char c, Direction toPlayer, Direction forward, Direction right) {
        return switch (c) {
            case 'C' -> Blocks.COBBLESTONE.getDefaultState();
            case 'S' -> Blocks.STONE_BRICKS.getDefaultState();
            case 'W' -> Blocks.COBBLESTONE_WALL.getDefaultState();
            case 'F' -> Blocks.OAK_FENCE.getDefaultState();
            case 'D' -> Blocks.DARK_OAK_FENCE.getDefaultState();
            case 'O' -> Blocks.OBSIDIAN.getDefaultState();
            case 'B' -> Blocks.BLACK_CONCRETE.getDefaultState();
            case 'R' -> Blocks.REDSTONE_TORCH.getDefaultState();
            case 'T' -> Blocks.TORCH.getDefaultState();
            case 'L' -> Blocks.LANTERN.getDefaultState();
            case 'l' -> Blocks.SOUL_LANTERN.getDefaultState();
            case 'P' -> Blocks.CARVED_PUMPKIN.getDefaultState().with(CarvedPumpkinBlock.FACING, toPlayer);
            case 'J' -> Blocks.JACK_O_LANTERN.getDefaultState().with(CarvedPumpkinBlock.FACING, toPlayer);
            case 'H' -> Blocks.HAY_BLOCK.getDefaultState();
            case 'K' -> Blocks.SKELETON_SKULL.getDefaultState();
            case 'k' -> Blocks.WITHER_SKELETON_SKULL.getDefaultState();
            case 'N' -> Blocks.NETHERRACK.getDefaultState();
            case 'f' -> Blocks.FIRE.getDefaultState();
            case 's' -> Blocks.SOUL_FIRE.getDefaultState();
            case 'X' -> Blocks.BONE_BLOCK.getDefaultState();
            case 'I' -> Blocks.IRON_BARS.getDefaultState();
            case 'G' -> Blocks.GLASS.getDefaultState();
            case 'g' -> Blocks.GLASS_PANE.getDefaultState();
            case 'E' -> Blocks.END_ROD.getDefaultState();
            case 'Y' -> Blocks.CRYING_OBSIDIAN.getDefaultState();
            case 'Q' -> Blocks.SCULK.getDefaultState();
            case 'Z' -> Blocks.COBWEB.getDefaultState();
            case 'V' -> Blocks.LAVA.getDefaultState();
            case 'w' -> Blocks.WATER.getDefaultState();
            case 'U' -> Blocks.WITHER_ROSE.getDefaultState();
            case 'M' -> Blocks.SPAWNER.getDefaultState();
            case 't' -> Blocks.TARGET.getDefaultState();
            case 'y' -> Blocks.TNT.getDefaultState();
            case 'e' -> Blocks.BOOKSHELF.getDefaultState();
            case 'a' -> Blocks.SANDSTONE.getDefaultState();
            case 'x' -> Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState();
            case 'o' -> Blocks.OAK_LOG.getDefaultState();
            case 'p' -> Blocks.PACKED_ICE.getDefaultState();
            case 'h' -> Blocks.CHEST.getDefaultState();
            case 'b' -> Blocks.BELL.getDefaultState();
            case 'i' -> Blocks.CHAIN.getDefaultState();
            case 'z' -> Blocks.IRON_TRAPDOOR.getDefaultState();
            case 'r' -> Blocks.RED_CONCRETE.getDefaultState();
            case '+' -> Blocks.SNOW_BLOCK.getDefaultState();
            case '#' -> Blocks.POLISHED_BLACKSTONE.getDefaultState();
            case '%' -> Blocks.MOSSY_COBBLESTONE.getDefaultState();
            case '&' -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            case '2' -> Blocks.SOUL_SAND.getDefaultState();
            case '3' -> Blocks.SOUL_SOIL.getDefaultState();
            case '4' -> Blocks.STONE.getDefaultState();
            case '5' -> Blocks.RED_CARPET.getDefaultState();
            case '6' -> Blocks.DARK_OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, forward);
            case '7' -> Blocks.COARSE_DIRT.getDefaultState();
            case '9' -> Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, right.getAxis());
            case '.' -> Blocks.AIR.getDefaultState();
            case 'c' -> Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, 3).with(CandleBlock.LIT, true);
            case 'j' -> Blocks.OBSERVER.getDefaultState().with(ObserverBlock.FACING, toPlayer);
            case 'd' -> Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, toPlayer).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            case 'u' -> Blocks.DARK_OAK_DOOR.getDefaultState().with(DoorBlock.FACING, toPlayer).with(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            case 'v' -> Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, forward).with(BedBlock.PART, BedPart.FOOT);
            case 'n' -> Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, forward).with(BedBlock.PART, BedPart.HEAD);
            default -> null;
        };
    }

    private static boolean place(ServerPlayerEntity p, ServerWorld w, Random r, Build build) {
        Direction facing = p.getHorizontalFacing();
        Direction forward = build.place() == Place.BEHIND ? facing.getOpposite() : facing;
        Direction right = forward.rotateYClockwise();
        Direction toPlayer = forward.getOpposite();

        String[][] layers = new String[build.layers().length][];
        int width = 0;
        int depth = 0;
        for (int i = 0; i < layers.length; i++) {
            layers[i] = build.layers()[i].split("/", -1);
            depth = Math.max(depth, layers[i].length);
            for (String row : layers[i]) width = Math.max(width, row.length());
        }

        BlockPos anchor;
        if (build.place() == Place.AROUND) {
            anchor = p.getBlockPos();
        } else {
            Vec3d point = p.getPos().add(Vec3d.of(forward.getVector()).multiply(build.dist()));
            if (build.place() == Place.FAR) point = point.add(Vec3d.of(right.getVector()).multiply(r.nextInt(13) - 6));
            anchor = Horror.ground(w, point.add(0, 1, 0), 8);
            if (anchor == null) return false;
        }
        int xOff = (width - 1) / 2;
        int zOff = build.place() == Place.AROUND ? (depth - 1) / 2 : 0;

        for (int li = 0; li < layers.length; li++) {
            int y = build.base() + li;
            for (int z = 0; z < layers[li].length; z++) {
                String row = layers[li][z];
                for (int x = 0; x < row.length(); x++) {
                    char c = row.charAt(x);
                    if (c == ' ') continue;
                    BlockPos pos = anchor.offset(right, x - xOff).offset(forward, z - zOff).up(y);
                    if (build.place() == Place.AROUND && pos.getX() == anchor.getX() && pos.getZ() == anchor.getZ()) continue;
                    if (c == '!') {
                        placeSign(p, pos, build.sign(), r);
                        continue;
                    }
                    BlockState state = state(c, toPlayer, forward, right);
                    if (state != null) Horror.fakeBlock(p, pos, state, build.life());
                }
            }
        }
        if (build.sound() != null) Horror.sound(p, build.sound(), anchor.toCenterPos(), 1.0f, 0.7f);
        return true;
    }

    private static void placeSign(ServerPlayerEntity p, BlockPos pos, @Nullable String kind, Random r) {
        BlockState state = Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, RotationPropertyHelper.fromYaw(p.getYaw() + 180f));
        Text line = "grave".equals(kind)
                ? Text.translatable("somnium.build.grave", p.getName())
                : Text.translatable("somnium.build." + (kind == null ? "sign" : kind) + "." + (1 + r.nextInt(4)));
        SignBlockEntity sign = new SignBlockEntity(pos, state);
        sign.setText(new SignText().withMessage(1, line.copy().formatted(Formatting.DARK_RED)), true);
        Horror.fakeBlock(p, pos, state, 240);
        p.networkHandler.sendPacket(BlockEntityUpdateS2CPacket.create(sign));
    }
}

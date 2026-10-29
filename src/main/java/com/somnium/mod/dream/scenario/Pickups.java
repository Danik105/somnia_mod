package com.somnium.mod.dream.scenario;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Блоки-цели сна, которые надо найти и нажать ПКМ (тетради, предохранители, книги).
 * Общие на всю группу: что подобрал один, засчитано всем.
 */
final class Pickups {

    private final List<BlockPos> left = new ArrayList<>();
    private int total;

    void add(BlockPos pos) {
        left.add(pos.toImmutable());
        total++;
    }

    /** true, если это была цель и она засчитана. */
    boolean take(BlockPos pos) {
        return left.remove(pos);
    }

    boolean contains(BlockPos pos) {
        return left.contains(pos);
    }

    int remaining() {
        return left.size();
    }

    int collected() {
        return total - left.size();
    }

    int total() {
        return total;
    }

    List<BlockPos> positions() {
        return left;
    }

    /** Подсвечивает оставшиеся цели частицами — так их видно издалека. */
    void sparkle(ServerWorld world, ParticleEffect particle) {
        for (BlockPos pos : left) {
            world.spawnParticles(particle, pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 2, 0.25, 0.25, 0.25, 0.0);
        }
    }
}

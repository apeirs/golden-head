package dev.goldenhead.utils;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hypixel Pit slime launchers (spawn corners and the pads on the roads). A pad is worth taking when
 * walking onto it and riding the launch gets you closer to the goal than sprinting the whole way.
 * The carry is about 30 blocks; the server does the actual launch once you step on the slime.
 */
public final class LaunchPads {
    /** Horizontal blocks a Pit launcher throws you. */
    public static final double CARRY = 30;
    /** Walking distance a couple of seconds in the air is worth. */
    private static final double AIR_COST = 10;
    /** Ignore a pad unless it beats walking by this many blocks. */
    private static final double MARGIN = 8;
    private static final double SCAN = 72;

    private LaunchPads() {
    }

    public static final class Pad {
        public final BlockPos stand;
        public final Vec3d center;
        /** Horizontal unit vector the launcher throws you. */
        public final Vec3d direction;
        /** Slime blocks in this launcher. */
        public final Set<BlockPos> blocks;

        Pad(BlockPos stand, Vec3d center, Vec3d direction, Set<BlockPos> blocks) {
            this.stand = stand;
            this.center = center;
            this.direction = direction;
            this.blocks = blocks;
        }
    }

    /** The pad that beats walking, or null. */
    public static Pad best(Vec3d from, Vec3d to) {
        double bar = horiz(from, to) - MARGIN;
        Pad best = null;
        for (Pad pad : find()) {
            double cost = cost(from, to, pad);
            if (cost < bar) {
                bar = cost;
                best = pad;
            }
        }
        return best;
    }

    /** Whether this pad still beats walking to {@code to}. */
    public static boolean worth(Vec3d from, Vec3d to, Pad pad) {
        return cost(from, to, pad) + MARGIN < horiz(from, to);
    }

    /** Every walkable launcher currently loaded. */
    public static List<Pad> all() {
        return find();
    }

    private static double cost(Vec3d from, Vec3d to, Pad pad) {
        Vec3d land = pad.center.add(pad.direction.multiply(CARRY));
        double padToTarget = horiz(pad.center, to);
        double fromLand = horiz(land, to);
        // The throw has to move us toward them. Overshooting and walking back is part of fromLand.
        if (fromLand + 4 >= padToTarget) return Double.POSITIVE_INFINITY;
        double climb = Math.abs(from.y - pad.center.y);
        return horiz(from, pad.center) + climb + AIR_COST + fromLand;
    }

    private static List<Pad> find() {
        List<Pad> pads = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return pads;

        List<BlockPos> slime = BlockScan.find(Set.of(Blocks.SLIME_BLOCK), SCAN);
        Set<BlockPos> left = new HashSet<>();
        for (BlockPos pos : slime) if (walkable(pos)) left.add(pos.toImmutable());

        while (!left.isEmpty()) {
            BlockPos start = left.iterator().next();
            left.remove(start);
            List<BlockPos> cluster = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                cluster.add(pos);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos next = pos.add(dx, dy, dz);
                    if (left.remove(next)) queue.add(next);
                }
            }
            if (cluster.size() < 2) continue;
            Pad pad = toPad(cluster);
            if (pad != null) pads.add(pad);
        }
        return pads;
    }

    private static Pad toPad(List<BlockPos> blocks) {
        double x = 0, y = 0, z = 0;
        for (BlockPos pos : blocks) {
            x += pos.getX() + 0.5;
            y += pos.getY();
            z += pos.getZ() + 0.5;
        }
        Vec3d center = new Vec3d(x / blocks.size(), y / blocks.size(), z / blocks.size());
        Vec3d direction = direction(blocks, center);
        if (direction == null) return null;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d me = mc.player.getEntityPos();
        BlockPos stand = blocks.get(0);
        double best = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            double dx = pos.getX() + 0.5 - me.x;
            double dz = pos.getZ() + 0.5 - me.z;
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                stand = pos;
            }
        }
        Set<BlockPos> slime = new HashSet<>();
        for (BlockPos pos : blocks) slime.add(pos.toImmutable());
        return new Pad(stand.toImmutable(), center, direction, Set.copyOf(slime));
    }

    /**
     * Pads on the spawn platform throw you away from its center. A long pad throws you across its
     * short side, off the lip when there is one, otherwise still away from spawn.
     */
    private static Vec3d direction(List<BlockPos> blocks, Vec3d center) {
        Vec3d outward = outward(center);
        if (outward != null && SpawnArea.get() != null) {
            double dx = center.x - SpawnArea.get().x;
            double dz = center.z - SpawnArea.get().z;
            if (dx * dx + dz * dz <= (SpawnArea.RADIUS + 12) * (SpawnArea.RADIUS + 12)) return outward;
        }

        double xx = 0, zz = 0, xz = 0;
        for (BlockPos pos : blocks) {
            double px = pos.getX() + 0.5 - center.x;
            double pz = pos.getZ() + 0.5 - center.z;
            xx += px * px;
            zz += pz * pz;
            xz += px * pz;
        }
        boolean longPad = xx + zz > blocks.size() * 1.2;
        if (!longPad) return outward;

        double angle = 0.5 * Math.atan2(2 * xz, xx - zz);
        Vec3d side = new Vec3d(-Math.sin(angle), 0, Math.cos(angle));
        boolean lipA = lip(center, side);
        boolean lipB = lip(center, side.multiply(-1));
        if (lipA && !lipB) return side;
        if (lipB && !lipA) return side.multiply(-1);
        if (outward == null) return null;
        return outward.dotProduct(side) >= 0 ? side : side.multiply(-1);
    }

    private static Vec3d outward(Vec3d center) {
        Vec3d spawn = SpawnArea.get();
        if (spawn == null) return null;
        double dx = center.x - spawn.x;
        double dz = center.z - spawn.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 4) return null;
        return new Vec3d(dx / len, 0, dz / len);
    }

    /** True when a couple of blocks this way the floor drops away. */
    private static boolean lip(Vec3d center, Vec3d dir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockPos ahead = BlockPos.ofFloored(center.x + dir.x * 2.5, center.y, center.z + dir.z * 2.5);
        return mc.world.getBlockState(ahead.down()).isAir();
    }

    private static boolean walkable(BlockPos slime) {
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockState above = mc.world.getBlockState(slime.up());
        return above.getCollisionShape(mc.world, slime.up()).isEmpty();
    }

    private static double horiz(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}

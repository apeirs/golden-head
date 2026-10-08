package dev.goldenhead.utils;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Grid path for Care Package and Streak. Other Pit modules still sprint with {@link Mover}; this is not Baritone.
 * Feet stand on a block, jump one block, and fall as far as the floor below. A launch pad is one jump
 * of about {@link LaunchPads#CARRY} blocks along its throw, because stepping on the slime launches you.
 */
public final class Route {
    private static final int BUDGET = 4000;
    private static final int MARGIN_XZ = 64;
    private static final int MARGIN_UP = 16;
    private static final int MARGIN_DOWN = 24;
    private static final int MAX_DROP = 128;
    private static final double LAUNCH_COST = 7;
    private static final double INSET = 0.15;
    private static final double BODY = 1.8;

    private Route() {
    }

    /** One cell of a path. {@code launch} is set on the pad cell; the next cell is where that throw lands. */
    public static final class Step {
        public final BlockPos pos;
        public final Vec3d launch;

        Step(BlockPos pos, Vec3d launch) {
            this.pos = pos.toImmutable();
            this.launch = launch;
        }
    }

    /** Shortest walk to {@code goal} through loaded chunks. Avoided cells are treated as solid for this search. */
    public static List<Step> find(BlockPos start, BlockPos goal, Set<BlockPos> avoid) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || start == null || goal == null) return List.of();
        Set<BlockPos> blocked = avoid == null ? Set.of() : avoid;
        return new Search(mc.world, start.toImmutable(), goal.toImmutable(), blocked).run();
    }

    /**
     * Straight walk from the eyes' feet to {@code to} stays on a floor and does not cross slime.
     * Samples every half block. A floor more than one block off the line, or a pad, stops it.
     */
    public static boolean lineClear(Vec3d from, BlockPos to, Set<BlockPos> avoid) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || from == null || to == null) return false;
        Set<BlockPos> blocked = avoid == null ? Set.of() : avoid;
        Map<BlockPos, Boolean> stand = new HashMap<>();
        Map<BlockPos, Boolean> body = new HashMap<>();
        double x1 = to.getX() + 0.5;
        double y1 = to.getY();
        double z1 = to.getZ() + 0.5;
        double dx = x1 - from.x;
        double dy = y1 - from.y;
        double dz = z1 - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(horiz / 0.5));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double x = from.x + dx * t;
            double y = from.y + dy * t;
            double z = from.z + dz * t;
            int base = (int) Math.floor(y);
            boolean floor = false;
            for (int oy = -1; oy <= 1; oy++) {
                BlockPos feet = BlockPos.ofFloored(x, base + oy, z);
                if (mc.world.getBlockState(feet.down()).isOf(Blocks.SLIME_BLOCK)) continue;
                if (standable(mc.world, feet, stand, body, blocked)) {
                    floor = true;
                    break;
                }
            }
            if (!floor) return false;
        }
        return true;
    }

    /** Follows a {@link Route} with {@link Mover}, and rides a launch pad when the path crosses one. */
    public static final class Follower {
        private static final int STUCK_TICKS = 12;
        private static final int SLIME_STUCK_TICKS = 30;
        private static final int LOOKAHEAD = 6;
        private static final double OFF_PATH_SQ = 3 * 3;
        private static final double NODE_REACH_SQ = 4;
        private static final double PAD_ENGAGE_SQ = 36;

        private final Set<BlockPos> avoid = new HashSet<>();
        /** Cells a player shoved us out of. Kept for a short time so the next path does not walk back into them. */
        private final Map<BlockPos, Long> shoveUntil = new HashMap<>();
        private List<Step> path = List.of();
        private BlockPos goalPos;
        private Vec3d avoidAnchor;
        private Vec3d lastPos;
        private Vec3d launchDir;
        private int cursor;
        private int ticks;
        private int stuck;
        private int padArm;
        private int yield;
        private int slide;
        private int slideSide;
        private double slideX;
        private double slideZ;
        private int lastHurt;
        private int blockedTicks;
        private long padWorldTime = Long.MIN_VALUE;
        private boolean coasting;

        public void clear() {
            path = List.of();
            avoid.clear();
            shoveUntil.clear();
            goalPos = null;
            avoidAnchor = null;
            lastPos = null;
            launchDir = null;
            cursor = 0;
            ticks = 0;
            stuck = 0;
            padArm = 0;
            yield = 0;
            slide = 0;
            slideSide = 0;
            slideX = 0;
            slideZ = 0;
            lastHurt = 0;
            blockedTicks = 0;
            padWorldTime = Long.MIN_VALUE;
            coasting = false;
        }

        public boolean usingPad() {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc.world != null && mc.world.getTime() == padWorldTime;
        }

        public void follow(Mover mover, BlockPos goal, int range, Vec3d look) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null || goal == null || look == null) return;
            // The path goes around walls. Mover's own strafe is what wedges this into corners.
            mover.hugWalls = false;
            mover.sprintJump = false;

            Vec3d me = mc.player.getEntityPos();
            Vec3d velocity = mc.player.getVelocity();
            double horizontal = velocity.x * velocity.x + velocity.z * velocity.z;
            if (coasting) {
                if (mc.player.isOnGround() && horizontal < 0.05) {
                    coasting = false;
                    path = List.of();
                } else {
                    markPad(mc);
                    mover.stop();
                    Vec3d face = launchDir == null ? look : me.add(launchDir.multiply(4)).add(0, 1, 0);
                    mover.lookAt(face, mover.rotationSpeed);
                    return;
                }
            }
            if (padArm > 0 && !mc.player.isOnGround() && horizontal > 1.0) {
                coasting = true;
                markPad(mc);
                mover.stop();
                Vec3d face = launchDir == null ? look : me.add(launchDir.multiply(4)).add(0, 1, 0);
                mover.lookAt(face, mover.rotationSpeed);
                return;
            }

            // A knockback moves us a couple of blocks. That must not forget the player we were just pushed out of.
            if (avoidAnchor != null && mc.player.hurtTime == 0 && mc.player.isOnGround()) {
                double dx = me.x - avoidAnchor.x;
                double dz = me.z - avoidAnchor.z;
                if (dx * dx + dz * dz > 9) {
                    avoid.clear();
                    avoidAnchor = null;
                }
            }

            long now = mc.world.getTime();
            shoveUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
            int hurt = mc.player.hurtTime;
            boolean justHit = hurt > lastHurt && hurt > 0;
            lastHurt = hurt;
            if (justHit && padArm == 0 && !onSlime(mc)) {
                PlayerEntity attacker = nearestOther(mc, 36);
                if (attacker != null) rememberShove(attacker, now, goal);
                // Let the hit carry us out. Walking forward here steps straight back into them.
                yield = 6;
                path = List.of();
            }
            if (yield > 0) {
                yield--;
                mover.stop();
                mover.lookAt(look, mover.rotationSpeed);
                return;
            }
            if (shoveAside(mc, mover, me, goal)) return;

            boolean moved = true;
            if (lastPos != null) {
                double dx = me.x - lastPos.x;
                double dz = me.z - lastPos.z;
                moved = dx * dx + dz * dz >= 0.0004;
            }
            lastPos = me;
            boolean slime = onSlime(mc);
            if (!mc.player.isOnGround() || moved) stuck = 0;
            else stuck++;
            if (stuck >= (slime ? SLIME_STUCK_TICKS : STUCK_TICKS)) {
                stuck = 0;
                BlockPos feet = mc.player.getBlockPos();
                BlockPos front = blockInFront(mc);
                if (front != null && !front.equals(feet)) avoid.add(front.toImmutable());
                if (cursor < path.size()) {
                    BlockPos node = path.get(cursor).pos;
                    // The next stair cell is above the feet. Blacklisting it makes the climb illegal.
                    if (!node.equals(feet) && node.getY() <= feet.getY()) avoid.add(node);
                }
                if (avoid.size() > 20) avoid.clear();
                avoidAnchor = me;
                path = List.of();
            }

            boolean goalMoved = goalPos == null || goalPos.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ()) > 36;
            if (goalMoved) {
                avoid.clear();
                avoidAnchor = null;
            }
            ticks++;
            // A full search is heavy. Refresh when the goal moves, the walk leaves the path,
            // someone is standing on it, the planned path is about to run out, or about two seconds have passed.
            Set<BlockPos> closed = closed(goal);
            boolean extend = ticks >= 20 && pathRunsOut(me, goal);
            if (path.isEmpty() || goalMoved || offPath(me) || extend || ticks >= 40 || pathHits(mc.player.getBlockPos(), closed)) {
                ticks = 0;
                goalPos = goal.toImmutable();
                path = find(mc.player.getBlockPos(), goal, closed);
                if (path.isEmpty()) path = List.of(new Step(mc.player.getBlockPos(), null));
                cursor = nearest(me);
            }

            advance(me);
            Step launch = upcomingLaunch(me);
            if (launch != null && launch.launch != null) {
                mover.nudge = 0;
                markPad(mc);
                padArm = 30;
                launchDir = launch.launch;
                if (onLaunchPad(mc, launch)) {
                    Vec3d ahead = me.add(launch.launch.multiply(4));
                    mover.tick(BlockPos.ofFloored(ahead), 1, ahead.add(0, 1, 0));
                } else {
                    mover.tick(launch.pos.down(), 1, Vec3d.ofCenter(launch.pos));
                }
                return;
            }
            padArm = Math.max(0, padArm - 1);

            if (path.size() <= 1) {
                if (mover.nudge == 0) biasAway(mc, mover, me);
                mover.tick(goal, range, look);
                return;
            }
            BlockPos way = waypoint(me, closed);
            mover.tick(way, 1, new Vec3d(way.getX() + 0.5, way.getY() + 1.4, way.getZ() + 0.5));
        }

        private void markPad(MinecraftClient mc) {
            if (mc.world != null) padWorldTime = mc.world.getTime();
        }

        /** A fresh path starts at the search origin, which is behind us after we have already walked. */
        private int nearest(Vec3d me) {
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < path.size(); i++) {
                double d = flatSq(me, path.get(i).pos);
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            return nearest;
        }

        private void advance(Vec3d me) {
            int limit = Math.min(path.size() - 1, cursor + LOOKAHEAD);
            for (int i = cursor; i <= limit; i++) {
                Step step = path.get(i);
                if (step.launch != null) {
                    if (flatSq(me, step.pos) <= PAD_ENGAGE_SQ) cursor = i;
                    return;
                }
                if (distanceSq(me, step.pos) <= NODE_REACH_SQ) cursor = i;
            }
        }

        private Step upcomingLaunch(Vec3d me) {
            int limit = Math.min(path.size(), cursor + LOOKAHEAD);
            for (int i = cursor; i < limit; i++) {
                Step step = path.get(i);
                if (step.launch == null) continue;
                return flatSq(me, step.pos) <= PAD_ENGAGE_SQ ? step : null;
            }
            return null;
        }

        private BlockPos waypoint(Vec3d me, Set<BlockPos> blocked) {
            int index = Math.min(cursor, path.size() - 1);
            Step here = path.get(index);
            // The throw is the next move. Walking past this cell cuts across the slime.
            if (here.launch != null) return here.pos;
            BlockPos chosen = here.pos;
            int limit = Math.min(path.size() - 1, cursor + LOOKAHEAD);
            for (int i = cursor + 1; i <= limit; i++) {
                Step step = path.get(i);
                if (step.launch != null) break;
                if (!lineClear(me, step.pos, blocked)) break;
                chosen = step.pos;
            }
            return chosen;
        }

        /** The path stops short of the chest and we are close to that end, so the next search can use newly loaded chunks. */
        private boolean pathRunsOut(Vec3d me, BlockPos goal) {
            if (path.size() <= 1) return true;
            Step end = path.get(path.size() - 1);
            double dx = end.pos.getX() + 0.5 - goal.getX();
            double dz = end.pos.getZ() + 0.5 - goal.getZ();
            int dy = Math.abs(end.pos.getY() - goal.getY());
            if (dx * dx + dz * dz <= 9 && dy <= 2) return false;
            return flatSq(me, end.pos) <= 32 * 32;
        }

        private boolean offPath(Vec3d me) {
            if (path.size() <= 1) return false;
            int from = Math.max(0, cursor - 4);
            int to = Math.min(path.size() - 1, cursor + 20);
            double best = Double.MAX_VALUE;
            for (int i = from; i <= to; i++) {
                double d = distanceSq(me, path.get(i).pos);
                if (d < best) best = d;
            }
            return best > OFF_PATH_SQ;
        }

        private boolean onLaunchPad(MinecraftClient mc, Step launch) {
            BlockPos feet = mc.player.getBlockPos();
            BlockPos slime = launch.pos.down();
            for (BlockPos pos : new BlockPos[] {feet, feet.down()}) {
                if (!mc.world.getBlockState(pos).isOf(Blocks.SLIME_BLOCK)) continue;
                if (pos.getSquaredDistance(slime) <= 144) return true;
            }
            return false;
        }

        private boolean onSlime(MinecraftClient mc) {
            BlockPos feet = mc.player.getBlockPos();
            return mc.world.getBlockState(feet).isOf(Blocks.SLIME_BLOCK)
                || mc.world.getBlockState(feet.down()).isOf(Blocks.SLIME_BLOCK);
        }

        private BlockPos blockInFront(MinecraftClient mc) {
            float yaw = mc.player.getYaw() * 0.017453292F;
            Vec3d feet = mc.player.getEntityPos();
            double fx = -MathHelper.sin(yaw);
            double fz = MathHelper.cos(yaw);
            return BlockPos.ofFloored(feet.x + fx * 0.8, feet.y, feet.z + fz * 0.8);
        }

        /** Wall avoids, cells a shove just cleared, and players who are not already on the chest. */
        private Set<BlockPos> closed(BlockPos goal) {
            Set<BlockPos> blocked = new HashSet<>(avoid);
            blocked.addAll(shoveUntil.keySet());
            crowd(goal, blocked);
            return blocked;
        }

        private boolean pathHits(BlockPos feet, Set<BlockPos> blocked) {
            if (path.size() <= 1) return false;
            for (int i = Math.max(cursor, 0); i < path.size(); i++) {
                BlockPos pos = path.get(i).pos;
                if (pos.equals(feet)) continue;
                if (blocked.contains(pos)) return true;
            }
            return false;
        }

        /**
         * Someone is in the way. Strafe off them, and if they stay there step out to the side
         * instead of holding forward. A launch ride is left alone.
         * @return true when this tick already moved
         */
        private boolean shoveAside(MinecraftClient mc, Mover mover, Vec3d me, BlockPos goal) {
            if (onSlime(mc)) {
                slide = 0;
                blockedTicks = 0;
                return false;
            }
            if (slide <= 0) {
                PlayerEntity near = nearestOther(mc, 6.25);
                boolean touching = near != null && mc.player.horizontalCollision
                    && me.squaredDistanceTo(near.getEntityPos()) <= 1.44;
                if (touching) {
                    mover.autoJump = false;
                    int away = sideAway(mc, near);
                    mover.nudge = away;
                    blockedTicks++;
                    if (blockedTicks >= 4) {
                        blockedTicks = 0;
                        rememberShove(near, mc.world.getTime(), goal);
                        slide = 8;
                        slideSide = away;
                        aimSlide(me, near);
                        path = List.of();
                    }
                } else {
                    blockedTicks = 0;
                    if (near != null && inFront(mc, near)) {
                        mover.autoJump = false;
                        mover.nudge = sideAway(mc, near);
                    }
                }
            }
            if (slide > 0) {
                slide--;
                Vec3d side = me.add(slideX * 2.5, 0, slideZ * 2.5);
                double gx = goal.getX() + 0.5 - me.x;
                double gz = goal.getZ() + 0.5 - me.z;
                double glen = Math.sqrt(gx * gx + gz * gz);
                if (glen > 0.2) side = side.add(gx / glen * 2, 0, gz / glen * 2);
                mover.autoJump = false;
                mover.nudge = slideSide;
                mover.tick(BlockPos.ofFloored(side), 1, side.add(0, 1.2, 0));
                return true;
            }
            return false;
        }

        /** Keep the cells around a player who just hit or pinned us, unless they are the last few blocks to the chest. */
        private void rememberShove(PlayerEntity player, long now, BlockPos goal) {
            if (player == null || goal == null) return;
            BlockPos feet = player.getBlockPos().toImmutable();
            if (nearGoal(goal, feet)) return;
            long until = now + 40;
            shoveUntil.put(feet, until);
            shoveUntil.put(feet.north(), until);
            shoveUntil.put(feet.south(), until);
            shoveUntil.put(feet.east(), until);
            shoveUntil.put(feet.west(), until);
        }

        private void aimSlide(Vec3d me, PlayerEntity other) {
            double ax = me.x - other.getX();
            double az = me.z - other.getZ();
            double len = Math.sqrt(ax * ax + az * az);
            if (len < 0.05) {
                slideX = 1;
                slideZ = 0;
            } else {
                slideX = ax / len;
                slideZ = az / len;
            }
        }

        /** Straight-line fallback still steps around a player instead of sprinting into them. */
        private void biasAway(MinecraftClient mc, Mover mover, Vec3d me) {
            if (onSlime(mc)) return;
            PlayerEntity near = nearestOther(mc, 6.25);
            if (near == null || !inFront(mc, near)) return;
            mover.autoJump = false;
            mover.nudge = sideAway(mc, near);
        }

        private static double distanceSq(Vec3d me, BlockPos pos) {
            double dx = me.x - (pos.getX() + 0.5);
            double dy = me.y - pos.getY();
            double dz = me.z - (pos.getZ() + 0.5);
            return dx * dx + dy * dy + dz * dz;
        }

        private static double flatSq(Vec3d me, BlockPos pos) {
            double dx = me.x - (pos.getX() + 0.5);
            double dz = me.z - (pos.getZ() + 0.5);
            return dx * dx + dz * dz;
        }
    }

    private static boolean loaded(World world, BlockPos pos) {
        return world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean standable(World world, BlockPos pos, Map<BlockPos, Boolean> stand, Map<BlockPos, Boolean> body, Set<BlockPos> avoid) {
        Boolean known = stand.get(pos);
        if (known != null) return known;
        boolean ok = !avoid.contains(pos) && loaded(world, pos) && bodyClear(world, pos, body) && floor(world, pos);
        stand.put(pos.toImmutable(), ok);
        return ok;
    }

    private static boolean bodyClear(World world, BlockPos pos, Map<BlockPos, Boolean> body) {
        Boolean known = body.get(pos);
        if (known != null) return known;
        boolean ok = false;
        if (loaded(world, pos) && loaded(world, pos.up()) && !hazard(world, pos) && !hazard(world, pos.up())) {
            Box box = new Box(pos.getX() + INSET, pos.getY(), pos.getZ() + INSET, pos.getX() + 1 - INSET, pos.getY() + BODY, pos.getZ() + 1 - INSET);
            ok = world.isBlockSpaceEmpty(null, box);
        }
        body.put(pos.toImmutable(), ok);
        return ok;
    }

    /** Collision under the feet, and not a damaging floor. Fences count; their shape is taller than a block. */
    private static boolean floor(World world, BlockPos pos) {
        if (!loaded(world, pos.down())) return false;
        Box box = new Box(pos.getX() + INSET, pos.getY() - 0.2, pos.getZ() + INSET, pos.getX() + 1 - INSET, pos.getY(), pos.getZ() + 1 - INSET);
        if (world.isBlockSpaceEmpty(null, box)) return false;
        BlockState under = world.getBlockState(pos.down());
        return !under.isOf(Blocks.LAVA) && !under.isOf(Blocks.MAGMA_BLOCK) && !under.isOf(Blocks.CACTUS)
            && !under.getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean hazard(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
            || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH)
            || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)
            || state.isOf(Blocks.WITHER_ROSE) || state.getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean headroom(World world, BlockPos pos) {
        if (!loaded(world, pos.up(2))) return false;
        if (hazard(world, pos.up(2))) return false;
        Box box = new Box(pos.getX() + INSET, pos.getY() + 1.7, pos.getZ() + INSET, pos.getX() + 1 - INSET, pos.getY() + 2.15, pos.getZ() + 1 - INSET);
        return world.isBlockSpaceEmpty(null, box);
    }

    /** Players more than 5 blocks from the chest block their own cell, the four beside it, and the cell they are stepping into. */
    private static void crowd(BlockPos goal, Set<BlockPos> into) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || goal == null || into == null) return;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!other(mc, player)) continue;
            BlockPos feet = player.getBlockPos().toImmutable();
            if (nearGoal(goal, feet)) continue;
            into.add(feet);
            into.add(feet.north());
            into.add(feet.south());
            into.add(feet.east());
            into.add(feet.west());
            Vec3d velocity = player.getVelocity();
            int sx = velocity.x > 0.05 ? 1 : velocity.x < -0.05 ? -1 : 0;
            int sz = velocity.z > 0.05 ? 1 : velocity.z < -0.05 ? -1 : 0;
            if (sx != 0 || sz != 0) into.add(feet.add(sx, 0, sz).toImmutable());
        }
    }

    private static boolean nearGoal(BlockPos goal, BlockPos feet) {
        int dx = feet.getX() - goal.getX();
        int dz = feet.getZ() - goal.getZ();
        return dx * dx + dz * dz <= 25;
    }

    private static PlayerEntity nearestOther(MinecraftClient mc, double rangeSq) {
        if (mc.player == null || mc.world == null) return null;
        PlayerEntity best = null;
        double bestD = rangeSq;
        Vec3d me = mc.player.getEntityPos();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!other(mc, player)) continue;
            double distance = me.squaredDistanceTo(player.getEntityPos());
            if (distance <= bestD) {
                bestD = distance;
                best = player;
            }
        }
        return best;
    }

    private static boolean other(MinecraftClient mc, PlayerEntity player) {
        return player != null && player != mc.player && player.isAlive() && !player.isSpectator()
            && player.getUuid().version() != 2;
    }

    /** -1 left, +1 right, away from the other player. Straight ahead picks right. */
    private static int sideAway(MinecraftClient mc, PlayerEntity other) {
        Vec3d me = mc.player.getEntityPos();
        int side = sideOf(mc, me.x - other.getX(), me.z - other.getZ());
        return side == 0 ? 1 : side;
    }

    private static int sideOf(MinecraftClient mc, double wx, double wz) {
        float yaw = mc.player.getYaw() * 0.017453292F;
        double fx = -MathHelper.sin(yaw);
        double fz = MathHelper.cos(yaw);
        double left = wx * fz + wz * (-fx);
        if (Math.abs(left) < 0.2 && Math.abs(wx * fx + wz * fz) > Math.abs(left)) return 0;
        return left > 0 ? -1 : 1;
    }

    private static boolean inFront(MinecraftClient mc, PlayerEntity other) {
        float yaw = mc.player.getYaw() * 0.017453292F;
        double fx = -MathHelper.sin(yaw);
        double fz = MathHelper.cos(yaw);
        Vec3d me = mc.player.getEntityPos();
        double ox = other.getX() - me.x;
        double oz = other.getZ() - me.z;
        double len = Math.sqrt(ox * ox + oz * oz);
        if (len < 0.01) return true;
        return (ox * fx + oz * fz) / len > 0.35;
    }

    private static final class Search {
        private final World world;
        private final BlockPos start;
        private final BlockPos goal;
        private final Set<BlockPos> avoid;
        private final Map<BlockPos, LaunchPads.Pad> pads = new HashMap<>();
        private final Map<LaunchPads.Pad, BlockPos> landings = new HashMap<>();
        private final Map<BlockPos, Boolean> stand = new HashMap<>();
        private final Map<BlockPos, Boolean> body = new HashMap<>();
        private final Map<BlockPos, Double> bestG = new HashMap<>();
        private final Map<BlockPos, Link> links = new HashMap<>();
        private final PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f).thenComparingDouble(Open::g));
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        private BlockPos best;
        private double bestH;

        private Search(World world, BlockPos start, BlockPos goal, Set<BlockPos> avoid) {
            this.world = world;
            this.start = start;
            this.goal = goal;
            Set<BlockPos> blocked = new HashSet<>();
            if (avoid != null) blocked.addAll(avoid);
            crowd(goal, blocked);
            this.avoid = blocked;
            minX = Math.min(start.getX(), goal.getX()) - MARGIN_XZ;
            maxX = Math.max(start.getX(), goal.getX()) + MARGIN_XZ;
            minY = Math.min(start.getY(), goal.getY()) - MARGIN_DOWN;
            maxY = Math.max(start.getY(), goal.getY()) + MARGIN_UP;
            minZ = Math.min(start.getZ(), goal.getZ()) - MARGIN_XZ;
            maxZ = Math.max(start.getZ(), goal.getZ()) + MARGIN_XZ;
            for (LaunchPads.Pad pad : LaunchPads.all()) {
                for (BlockPos slime : pad.blocks) pads.put(slime.up(), pad);
            }
        }

        private List<Step> run() {
            if (arrived(start)) return List.of(new Step(start, null));
            best = start;
            bestH = heuristic(start);
            bestG.put(start, 0.0);
            open.add(new Open(bestH, 0, start));
            int expansions = 0;
            while (!open.isEmpty() && expansions < BUDGET) {
                Open cur = open.poll();
                double known = bestG.getOrDefault(cur.pos, Double.POSITIVE_INFINITY);
                if (cur.g > known + 1e-6) continue;
                if (arrived(cur.pos)) return stampStartPad(build(cur.pos));
                expansions++;
                expand(cur.pos, cur.g);
            }
            return stampStartPad(build(best));
        }

        /** Standing on a pad launches even when the landing chunk is not loaded yet. */
        private List<Step> stampStartPad(List<Step> steps) {
            LaunchPads.Pad pad = pads.get(start);
            if (pad == null || steps.isEmpty() || steps.get(0).launch != null || !steps.get(0).pos.equals(start)) return steps;
            steps.set(0, new Step(start, pad.direction));
            return steps;
        }

        private void expand(BlockPos from, double g) {
            LaunchPads.Pad under = pads.get(from);
            if (under != null) {
                BlockPos land = landing(under);
                if (land != null) relax(land, g + LAUNCH_COST, from, from, under.direction, false);
                return;
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    tryDrop(from, 0, 0, g);
                    tryLaunch(from, from.down(), g);
                    continue;
                }
                BlockPos same = from.add(dx, 0, dz);
                tryWalk(from, same, g);
                if (headroom(world, from)) tryWalk(from, same.up(), g);
                tryDrop(from, dx, dz, g);
                for (int dy = -1; dy <= 1; dy++) tryLaunch(from, from.add(dx, dy, dz), g);
            }
        }

        private void tryWalk(BlockPos from, BlockPos dest, double g) {
            if (!inBox(dest) || pads.containsKey(dest) || !standable(dest) || !enterable(from, dest)) return;
            relax(dest, g + stepCost(from, dest), from, null, null, true);
        }

        /** First solid floor at or below this column. Air keeps the scan going; a blocked column stops it. */
        private void tryDrop(BlockPos from, int dx, int dz, double g) {
            if (dx != 0 && dz != 0) {
                if (!bodyClear(world, from.add(dx, 0, 0), body) || !bodyClear(world, from.add(0, 0, dz), body)) return;
            }
            int limit = Math.min(MAX_DROP, from.getY() - minY);
            for (int dy = 1; dy <= limit; dy++) {
                BlockPos dest = from.add(dx, -dy, dz);
                if (!inBox(dest) || !loaded(world, dest)) break;
                if (columnBlocked(from, dest)) break;
                if (pads.containsKey(dest) || !standable(dest)) continue;
                relax(dest, g + stepCost(from, dest), from, null, null, true);
                break;
            }
        }

        private void tryLaunch(BlockPos from, BlockPos padFeet, double g) {
            LaunchPads.Pad pad = pads.get(padFeet);
            if (pad == null || padFeet.equals(from)) return;
            if (!standable(padFeet) || !enterable(from, padFeet)) return;
            BlockPos land = landing(pad);
            if (land == null || land.equals(from)) return;
            relax(land, g + stepCost(from, padFeet) + LAUNCH_COST, from, padFeet, pad.direction, false);
        }

        private BlockPos landing(LaunchPads.Pad pad) {
            if (landings.containsKey(pad)) return landings.get(pad);
            Vec3d spot = pad.center.add(pad.direction.multiply(LaunchPads.CARRY));
            int lx = (int) Math.floor(spot.x);
            int lz = (int) Math.floor(spot.z);
            int padY = (int) Math.floor(pad.center.y);
            // A lower road is always loaded, so the old deep scan never kept a landing above the pad.
            BlockPos found = null;
            int bestDy = Integer.MAX_VALUE;
            for (int dy = -4; dy <= 8; dy++) {
                BlockPos floor = firstFloor(lx, lz, padY + dy, padY + dy);
                if (floor == null) continue;
                int ady = Math.abs(floor.getY() - padY);
                if (ady < bestDy) {
                    bestDy = ady;
                    found = floor;
                }
            }
            if (found == null) found = firstFloor(lx, lz, padY - 5, padY - 80);
            landings.put(pad, found);
            return found;
        }

        /** Highest floor in the closest column that has one. The center column wins when it has a floor. */
        private BlockPos firstFloor(int lx, int lz, int top, int bottom) {
            BlockPos bestFloor = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                for (int y = top; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(lx + dx, y, lz + dz);
                    if (!loaded(world, pos)) break;
                    if (pads.containsKey(pos) || !standable(pos)) continue;
                    double d = dx * (double) dx + dz * (double) dz;
                    if (d < bestD) {
                        bestD = d;
                        bestFloor = pos;
                    }
                    break;
                }
            }
            return bestFloor;
        }

        private void relax(BlockPos node, double g, BlockPos parent, BlockPos pad, Vec3d launch, boolean mustInBox) {
            if (mustInBox && !inBox(node)) return;
            if (avoid.contains(node)) return;
            if (g >= bestG.getOrDefault(node, Double.POSITIVE_INFINITY) - 1e-6) return;
            bestG.put(node, g);
            links.put(node, new Link(parent, pad, launch));
            double h = heuristic(node);
            if (h < bestH - 1e-6) {
                bestH = h;
                best = node;
            }
            open.add(new Open(g + h, g, node));
        }

        private boolean standable(BlockPos pos) {
            return Route.standable(world, pos, stand, body, avoid);
        }

        private boolean columnBlocked(BlockPos from, BlockPos dest) {
            for (int y = from.getY(); y > dest.getY(); y--) {
                if (!bodyClear(world, new BlockPos(dest.getX(), y, dest.getZ()), body)) return true;
            }
            return false;
        }

        private boolean enterable(BlockPos from, BlockPos dest) {
            int dx = dest.getX() - from.getX();
            int dz = dest.getZ() - from.getZ();
            int dy = dest.getY() - from.getY();
            if (dx != 0 && dz != 0) {
                if (!bodyClear(world, from.add(dx, 0, 0), body) || !bodyClear(world, from.add(0, 0, dz), body)) return false;
                if (dy > 0 && (!bodyClear(world, from.add(dx, 1, 0), body) || !bodyClear(world, from.add(0, 1, dz), body))) return false;
            }
            if (dy > 0 && !headroom(world, from)) return false;
            return dy >= 0 || !columnBlocked(from, dest);
        }

        private boolean inBox(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        private boolean arrived(BlockPos pos) {
            int dx = pos.getX() - goal.getX();
            int dz = pos.getZ() - goal.getZ();
            int dy = pos.getY() - goal.getY();
            return Math.abs(dy) <= 2 && dx * dx + dz * dz <= 4;
        }

        /**
         * Height is part of the estimate. A flat node under the chest used to beat the staircase,
         * and the 4000-step budget was spent on that floor.
         */
        private double heuristic(BlockPos pos) {
            double dx = Math.abs(pos.getX() - goal.getX());
            double dz = Math.abs(pos.getZ() - goal.getZ());
            double diag = Math.min(dx, dz);
            double flat = diag * 1.41421356237 + (Math.max(dx, dz) - diag);
            int dy = goal.getY() - pos.getY();
            if (dy > 0) flat += dy * 1.6;
            else flat += (-dy) * 0.15;
            return flat;
        }

        private static double stepCost(BlockPos from, BlockPos to) {
            int dx = Math.abs(to.getX() - from.getX());
            int dz = Math.abs(to.getZ() - from.getZ());
            int dy = to.getY() - from.getY();
            double cost = dx != 0 && dz != 0 ? 1.41421356237 : (dx == 0 && dz == 0 ? 0.4 : 1);
            if (dy > 0) cost += 0.6;
            else if (dy < 0) cost += 0.15 * -dy;
            return cost;
        }

        private List<Step> build(BlockPos end) {
            ArrayDeque<BlockPos> chain = new ArrayDeque<>();
            BlockPos cur = end;
            int guard = 0;
            while (cur != null && guard++ < 10000) {
                chain.addFirst(cur);
                Link link = links.get(cur);
                if (link == null) break;
                cur = link.parent;
            }
            List<Step> steps = new ArrayList<>();
            for (BlockPos pos : chain) {
                Link link = links.get(pos);
                if (link != null && link.pad != null) {
                    if (!steps.isEmpty() && steps.get(steps.size() - 1).pos.equals(link.pad)) {
                        Step last = steps.remove(steps.size() - 1);
                        steps.add(new Step(last.pos, link.launch));
                    } else steps.add(new Step(link.pad, link.launch));
                }
                steps.add(new Step(pos, null));
            }
            if (steps.isEmpty()) steps.add(new Step(start, null));
            return steps;
        }
    }

    private record Open(double f, double g, BlockPos pos) {
    }

    private static final class Link {
        private final BlockPos parent;
        private final BlockPos pad;
        private final Vec3d launch;

        private Link(BlockPos parent, BlockPos pad, Vec3d launch) {
            this.parent = parent;
            this.pad = pad;
            this.launch = launch;
        }
    }
}

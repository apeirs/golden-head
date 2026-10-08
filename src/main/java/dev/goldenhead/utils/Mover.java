package dev.goldenhead.utils;

import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Sprints the player toward a goal. Hops a one-block step, walks off a drop, and strafes around a wall.
 * Call {@link #tick} every tick while moving and {@link #stop} when done.
 */
public class Mover {
    /** Ticks barely moving before we commit to sliding the other way. */
    private static final int STUCK_TICKS = 15;
    /** How long that slide is held. */
    private static final int STUCK_STRAFE_TICKS = 15;
    /** How long to keep a chosen side before picking again. */
    private static final int WALL_STRAFE_TICKS = 6;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private boolean pressing;
    private Vec3d lastPos;
    private BlockPos lastGoal;
    private int stillTicks;
    private int strafeLock;
    private int lockedSide;
    private int sprintJumpCooldown;

    public boolean autoJump = true;
    public boolean sprintJump;
    public double rotationSpeed = 0.6;
    /** Wall strafing. Path following turns this off so a slide cannot leave the path and wedge in a corner. */
    public boolean hugWalls = true;
    /** One-tick strafe added on top of the path. -1 is left, +1 is right. Cleared by the next {@link #tick}. */
    public int nudge;

    /**
     * One tick of moving toward {@code goal}. {@code look} is what to face.
     * Inside {@code range} blocks (horizontal) we stop starting a wall slide; the caller is about to arrive.
     */
    public void tick(BlockPos goal, int range, Vec3d look) {
        if (mc.player == null || mc.world == null || goal == null || look == null) return;

        if (lastGoal != null && lastGoal.getSquaredDistance(goal.getX(), goal.getY(), goal.getZ()) > 64) {
            strafeLock = 0;
            stillTicks = 0;
            lockedSide = 0;
        }
        lastGoal = goal.toImmutable();

        boolean unstuck = noteMotion();
        lookAt(look, rotationSpeed);
        drive(goal, range, unstuck);
    }

    /** Sprint-jump away from the spawn center until the player reaches the platform edge. */
    public void leaveSpawn() {
        Vec3d spawn = SpawnArea.get();
        if (mc.player == null || spawn == null) return;

        Vec3d me = mc.player.getEntityPos();
        Vec3d away = new Vec3d(me.x - spawn.x, 0, me.z - spawn.z);
        if (away.lengthSquared() < 0.04) {
            float yaw = mc.player.getYaw() * 0.017453292F;
            away = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        }
        Vec3d dest = spawn.add(away.normalize().multiply(SpawnArea.RADIUS + 10));
        dest = new Vec3d(dest.x, spawn.y - 8, dest.z);
        tick(BlockPos.ofFloored(dest), 1, dest);
    }

    /** Run straight to the pit center at X=0, Z=0, then let gravity carry the player down. */
    public void enterPit() {
        Vec3d spawn = SpawnArea.get();
        if (mc.player == null || spawn == null) return;

        Vec3d feet = mc.player.getEntityPos();
        double dx = feet.x, dz = feet.z;
        if (dx * dx + dz * dz <= 1.5 * 1.5) {
            unpress();
            return;
        }

        Vec3d dest = new Vec3d(0, spawn.y - 8, 0);
        tick(BlockPos.ofFloored(dest), 1, dest);
    }

    /** Stop moving. */
    public void stop() {
        unpress();
        lastPos = null;
        lastGoal = null;
        stillTicks = 0;
        strafeLock = 0;
        lockedSide = 0;
        sprintJumpCooldown = 0;
    }

    /** Stop moving. Same as {@link #stop()}. */
    public void release() {
        stop();
    }

    public void lookAt(Vec3d pos, double speed) {
        float yaw = (float) Rotations.getYaw(pos);
        float pitch = (float) Rotations.getPitch(pos);
        float s = (float) speed;
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()) * s);
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + (pitch - mc.player.getPitch()) * s, -90, 90));
    }

    /** @return true on the tick a stuck slide starts */
    private boolean noteMotion() {
        Vec3d now = mc.player.getEntityPos();
        if (lastPos != null) {
            double dx = now.x - lastPos.x;
            double dz = now.z - lastPos.z;
            if (dx * dx + dz * dz < 0.0004) stillTicks++;
            else stillTicks = 0;
        }
        lastPos = now;
        if (stillTicks < STUCK_TICKS) return false;
        lockedSide = lockedSide > 0 ? -1 : 1;
        strafeLock = STUCK_STRAFE_TICKS;
        stillTicks = 0;
        return true;
    }

    private void drive(BlockPos goal, int range, boolean unstuck) {
        Vec3d fwd = forward();
        Vec3d feet = mc.player.getEntityPos();
        BlockPos front = BlockPos.ofFloored(feet.x + fwd.x * 0.8, feet.y, feet.z + fwd.z * 0.8);
        boolean low = blocked(front);
        boolean high = blocked(front.up());
        boolean headroom = !blocked(front.up(2));
        boolean step = low && !high && headroom;
        boolean wall = !step && (high && (low || mc.player.horizontalCollision) || (low && !headroom));

        double dx = feet.x - (goal.getX() + 0.5);
        double dz = feet.z - (goal.getZ() + 0.5);
        boolean near = dx * dx + dz * dz <= (double) range * range;

        int side = 0;
        if (hugWalls) {
            if (strafeLock > 0) side = lockedSide;
            else if (wall && !near) {
                lockedSide = bestSide(fwd, goal);
                strafeLock = WALL_STRAFE_TICKS;
                side = lockedSide;
            }
            if (strafeLock > 0) strafeLock--;
        }
        if (nudge != 0) {
            side = nudge;
            nudge = 0;
        }

        boolean ceiling = blocked(mc.player.getBlockPos().up(2));
        boolean jump = autoJump && mc.player.isOnGround() && !ceiling
            && (step || unstuck || (mc.player.horizontalCollision && !high));
        if (autoJump && mc.player.isTouchingWater() && goal.getY() > feet.y + 0.5) jump = true;

        if (sprintJump && mc.player.isOnGround() && !ceiling && sprintJumpCooldown <= 0) {
            jump = true;
            sprintJumpCooldown = 8;
        } else if (sprintJumpCooldown > 0) sprintJumpCooldown--;

        press(mc.options.forwardKey, true);
        press(mc.options.sprintKey, true);
        press(mc.options.jumpKey, jump);
        press(mc.options.leftKey, side < 0);
        press(mc.options.rightKey, side > 0);
        pressing = true;
    }

    /** -1 is left, +1 is right. The open side that ends nearer the goal. */
    private int bestSide(Vec3d fwd, BlockPos goal) {
        Vec3d left = new Vec3d(fwd.z, 0, -fwd.x);
        double leftCost = sideCost(left, goal);
        double rightCost = sideCost(left.multiply(-1), goal);
        if (leftCost == rightCost) return lockedSide == 0 ? -1 : lockedSide;
        return leftCost < rightCost ? -1 : 1;
    }

    private double sideCost(Vec3d side, BlockPos goal) {
        Vec3d pos = mc.player.getEntityPos().add(side);
        BlockPos feet = BlockPos.ofFloored(pos.x, mc.player.getY(), pos.z);
        double penalty = blocked(feet) || blocked(feet.up()) ? 10_000 : 0;
        double dx = pos.x - (goal.getX() + 0.5);
        double dz = pos.z - (goal.getZ() + 0.5);
        return penalty + dx * dx + dz * dz;
    }

    private Vec3d forward() {
        float yaw = mc.player.getYaw() * 0.017453292F;
        return new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
    }

    private boolean blocked(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.isOf(Blocks.COBWEB) || !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private void press(KeyBinding key, boolean pressed) {
        key.setPressed(pressed || Input.isPressed(key));
    }

    private void unpress() {
        if (!pressing) return;
        pressing = false;
        press(mc.options.forwardKey, false);
        press(mc.options.sprintKey, false);
        press(mc.options.jumpKey, false);
        press(mc.options.leftKey, false);
        press(mc.options.rightKey, false);
    }
}

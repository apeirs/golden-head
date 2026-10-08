package dev.goldenhead.modules;

import dev.goldenhead.utils.BlockScan;
import dev.goldenhead.utils.Clicker;
import dev.goldenhead.utils.Mover;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared engine for event objectives you have to click: find the closest target (block or entity), sprint
 * there, lock on and spam clicks at a random interval. Clicks run per rendered frame, not per tick, so
 * intervals under 50 ms (one tick) actually happen.
 */
public abstract class ClickTarget extends Module {
    public enum ClickMode { Left, Right, Both }

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();
    protected final SettingGroup sgMove = settings.createGroup("Movement");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away to look for the target.")
        .defaultValue(48)
        .range(4, 128)
        .sliderRange(8, 96)
        .build()
    );

    private final Setting<ClickMode> clickMode = sgGeneral.add(new EnumSetting.Builder<ClickMode>()
        .name("click")
        .description("Which click the server counts. Both sends a left and a right click each time.")
        .defaultValue(ClickMode.Both)
        .build()
    );

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay-ms")
        .description("Shortest time between clicks.")
        .defaultValue(20)
        .range(1, 5000)
        .sliderRange(1, 500)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-ms")
        .description("Longest time between clicks. Each wait is random between min and max.")
        .defaultValue(100)
        .range(1, 5000)
        .sliderRange(1, 500)
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Click once the target's center is this close to your eyes.")
        .defaultValue(4.0)
        .range(1, 6)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing your arm on each click.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgMove.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("How fast to turn toward the target each tick. 1 = instant.")
        .defaultValue(0.6)
        .range(0.05, 1)
        .build()
    );

    private final Setting<Boolean> run = sgMove.add(new BoolSetting.Builder()
        .name("run")
        .description("Move to the target until it is in reach.")
        .defaultValue(true)
        .visible(this::canMove)
        .build()
    );

    private final Setting<Boolean> autoJump = sgMove.add(new BoolSetting.Builder()
        .name("auto-jump")
        .description("Jump onto a one-block step, and when you run into something you can hop.")
        .defaultValue(true)
        .visible(() -> canMove() && run.get())
        .build()
    );

    protected BlockPos targetBlock;
    protected Entity targetEntity;
    private boolean inReach;
    private int scanTimer;
    private long nextClick;
    protected final Mover mover = new Mover();
    private final Map<BlockPos, Integer> clicks = new HashMap<>();
    private boolean spawnExitInProgress;

    protected ClickTarget(Category category, String name, String description) {
        super(category, name, description);
    }

    /** Blocks worth scanning for; sections without any of them are skipped. */
    protected abstract Set<Block> candidateBlocks();

    /** Whether a candidate block at this position is an actual target. */
    protected boolean isTargetBlock(BlockPos pos) {
        return true;
    }

    protected boolean isTargetEntity(Entity entity) {
        return false;
    }

    /** How far to scan for a block target. Defaults to the range setting. */
    protected double scanRange() {
        return range.get();
    }

    /**
     * Where to walk before a block target is loaded. Null stands still.
     * Clicking still requires a real target in reach.
     */
    protected Vec3d approachPoint() {
        return null;
    }

    /** Bonus (in squared blocks) that makes a block win over closer ones, e.g. 400 = worth 20 blocks of walking. */
    protected double weight(BlockPos pos) {
        return 0;
    }

    /** Stop clicking a block after this many clicks if it is still there (0 = no limit). */
    protected int clickLimit(BlockPos pos) {
        return 0;
    }

    /** Extra gate for clicking (e.g. not while a looting screen is open). */
    protected boolean canClick() {
        return mc.currentScreen == null;
    }

    /** Whether this module may steer the player's movement keys toward its target. */
    protected boolean canMove() {
        return true;
    }

    protected void onClicked() {
    }

    /** Whether this event must leave the protected spawn before selecting its event target. */
    protected boolean leaveSpawnFirst() {
        return false;
    }

    /** Whether this event has appeared and spawn movement should begin. */
    protected boolean shouldLeaveSpawn() {
        return true;
    }

    /** Whether to chain sprint jumps while this module is moving. */
    protected boolean sprintJump() {
        return false;
    }

    /** Movement used to leave the protected spawn (events can use a direct pit entry). */
    protected void moveOutOfSpawn() {
        mover.leaveSpawn();
    }

    /** Whether this event should keep clicking on game ticks while the window is unfocused. */
    protected boolean clickWhileUnfocused() {
        return false;
    }

    @Override
    public void onActivate() {
        targetBlock = null;
        targetEntity = null;
        scanTimer = 0;
        nextClick = 0;
        inReach = false;
        clicks.clear();
        spawnExitInProgress = false;
    }

    @Override
    public void onDeactivate() {
        mover.release();
        spawnExitInProgress = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (--scanTimer <= 0) {
            scanTimer = 5;
            findTarget();
        }
        if (targetBlock != null && !(candidateBlocks().contains(mc.world.getBlockState(targetBlock).getBlock()) && isUsableBlock(targetBlock))) targetBlock = null;
        if (targetEntity != null && (targetEntity.isRemoved() || !isTargetEntity(targetEntity))) targetEntity = null;

        if (mc.currentScreen != null) {
            inReach = false;
            mover.release();
            return;
        }

        if (leaveSpawnFirst() && shouldLeaveSpawn()) {
            if (SpawnArea.isInSpawn(mc.player)) spawnExitInProgress = true;
            if (spawnExitInProgress) {
                int exitY = SpawnArea.exitY();
                if (exitY == Integer.MIN_VALUE || mc.player.getY() > exitY) {
                    inReach = false;
                    mover.autoJump = autoJump.get();
                    mover.sprintJump = sprintJump();
                    mover.rotationSpeed = rotationSpeed.get();
                    moveOutOfSpawn();
                    return;
                }
                spawnExitInProgress = false;
                mover.stop();
            }
        }

        Vec3d aim = aimPoint();
        if (aim == null) {
            inReach = false;
            Vec3d approach = approachPoint();
            if (!canMove()) {
                mover.release();
                if (approach != null) mover.lookAt(approach, rotationSpeed.get());
                return;
            }
            if (approach == null || !run.get()) {
                mover.release();
                return;
            }
            moveToward(BlockPos.ofFloored(approach), 3, approach);
            return;
        }

        // A chest one floor up is already inside the reach sphere. Stopping there never walks the stairs.
        boolean level = targetBlock == null || Math.abs(mc.player.getBlockY() - targetBlock.getY()) <= 2;
        inReach = level && mc.player.getEyePos().distanceTo(aim) <= reach.get();
        if (!canMove() || !run.get() || inReach) {
            mover.stop();
            mover.lookAt(aim, rotationSpeed.get());
            return;
        }

        BlockPos goal = targetBlock != null ? targetBlock : targetEntity.getBlockPos();
        moveToward(goal, Math.max(1, (int) Math.floor(reach.get()) - 1), aim);
    }

    private void moveToward(BlockPos goal, int range, Vec3d look) {
        mover.autoJump = autoJump.get();
        mover.sprintJump = sprintJump();
        mover.rotationSpeed = rotationSpeed.get();
        follow(goal, range, look);
    }

    /**
     * Walk toward {@code goal} while looking at {@code look}.
     * Care Package follows a path. Other modules sprint straight at the goal.
     */
    protected void follow(BlockPos goal, int range, Vec3d look) {
        mover.tick(goal, range, look);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (clickWhileUnfocused() && !mc.isWindowFocused()) return;
        tryClick();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (!clickWhileUnfocused() || mc.isWindowFocused()) return;
        tryClick();
    }

    private void tryClick() {
        if (!inReach || mc.player == null || !canClick()) return;
        Vec3d aim = aimPoint();
        if (aim == null || System.currentTimeMillis() < nextClick) return;

        click(aim);
        onClicked();
        int min = Math.min(minDelay.get(), maxDelay.get()), max = Math.max(minDelay.get(), maxDelay.get());
        nextClick = System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private Vec3d aimPoint() {
        if (targetBlock != null) return Vec3d.ofCenter(targetBlock);
        if (targetEntity != null) return targetEntity.getBoundingBox().getCenter();
        return null;
    }

    private void findTarget() {
        Vec3d eye = mc.player.getEyePos();
        double scan = scanRange();
        double best = scan * scan;
        targetBlock = null;
        targetEntity = null;

        for (BlockPos pos : BlockScan.find(candidateBlocks(), scan + 2)) {
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos)) - weight(pos);
            if (d < best && isUsableBlock(pos)) {
                best = d;
                targetBlock = pos;
            }
        }

        for (Entity entity : mc.world.getEntities()) {
            if (!isTargetEntity(entity)) continue;
            double d = eye.squaredDistanceTo(entity.getBoundingBox().getCenter());
            if (d < best) {
                best = d;
                targetEntity = entity;
                targetBlock = null;
            }
        }
    }

    private boolean isUsableBlock(BlockPos pos) {
        int limit = clickLimit(pos);
        return isTargetBlock(pos) && (limit <= 0 || clicks.getOrDefault(pos, 0) < limit);
    }

    private void click(Vec3d aim) {
        boolean left = clickMode.get() != ClickMode.Right;
        boolean right = clickMode.get() != ClickMode.Left;

        if (targetEntity != null) Clicker.entity(targetEntity, left, right, swing.get());
        else {
            Clicker.block(targetBlock, left, right, swing.get());
            clicks.merge(targetBlock, 1, Integer::sum);
        }
    }

    @Override
    public String getInfoString() {
        Vec3d aim = aimPoint();
        return aim == null || mc.player == null ? null : String.format("%.0fm", mc.player.getEyePos().distanceTo(aim));
    }
}

package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.kos.Kos;
import dev.goldenhead.utils.Mover;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Pit combat: sprint at the best target and hit at a random interval. Targets are real players only
 * (tab-listed, not NPCs), never friends, never TDM teammates. A non-empty KOS list is the only set of
 * names it will chase. Beasts first. A target that takes no damage
 * after several hits (spawn protection, invulnerable) is skipped for a while.
 */
public class AutoFight extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay-ms")
        .description("Shortest time between hits.")
        .defaultValue(40)
        .range(1, 1000)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-ms")
        .description("Longest time between hits. Each wait is random between min and max.")
        .defaultValue(95)
        .range(1, 1000)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("Hit when the target's hitbox is this close (1.8 reach is 3).")
        .defaultValue(3.0)
        .range(1, 6)
        .build()
    );

    private final Setting<Double> searchRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("search-range")
        .description("How far to look for targets.")
        .defaultValue(32)
        .range(4, 128)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Boolean> chase = sgGeneral.add(new BoolSetting.Builder()
        .name("chase")
        .description("Sprint to targets out of reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("How fast to turn toward the target each tick. 1 = instant.")
        .defaultValue(0.8)
        .range(0.05, 1)
        .build()
    );

    /** Set by Auto Event while it drives movement itself (collecting, delivering). */
    public boolean paused;

    /** Extra target rule set by Auto Event (e.g. only players on the KOTH platform). Null = none. */
    public Predicate<PlayerEntity> extraFilter;
    /** Where to stand while there is no target (set by Auto Event). Null = stay put. */
    public Box holdZone;

    private final Mover mover = new Mover();
    private final Map<UUID, Long> skipUntil = new HashMap<>();
    private PlayerEntity target;
    private int hitsWithoutDamage;
    private long nextHit;

    public AutoFight() {
        super(MeteorPitAddon.PIT, "auto-fight", "Sprints at the closest valid enemy and hits at a random interval (default 40-95 ms).");
    }

    @Override
    public void onActivate() {
        target = null;
        skipUntil.clear();
        nextHit = 0;
    }

    @Override
    public void onDeactivate() {
        mover.release();
        target = null;
        paused = false;
        extraFilter = null;
        holdZone = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (paused || Streak.ownsMovement()) {
            mover.stop();
            target = null;
            return;
        }

        // Nobody can be hit from (or in) spawn: get out first.
        if (SpawnArea.isInSpawn(mc.player)) {
            target = null;
            mover.rotationSpeed = rotationSpeed.get();
            mover.leaveSpawn();
            return;
        }

        if (target == null || !isValid(target)) {
            target = findTarget();
            hitsWithoutDamage = 0;
        }

        mover.rotationSpeed = rotationSpeed.get();
        if (target == null) {
            if (holdZone != null && !holdZone.contains(mc.player.getEntityPos())) {
                Vec3d center = holdZone.getCenter();
                mover.tick(net.minecraft.util.math.BlockPos.ofFloored(center), 1, center);
            } else mover.stop();
            return;
        }

        Vec3d aim = target.getBoundingBox().getCenter().add(0, target.getHeight() * 0.25, 0);
        if (chase.get()) mover.tick(target.getBlockPos(), 1, aim);
        else {
            mover.stop();
            mover.lookAt(aim, rotationSpeed.get());
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (target == null || mc.player == null || mc.currentScreen != null) return;
        if (System.currentTimeMillis() < nextHit || distanceTo(target) > attackRange.get()) return;
        if (!HitSelect.allow(target)) return;

        // Taking damage resets hurtTime to 10; if it never goes up the hits aren't landing.
        if (target.hurtTime > 0) hitsWithoutDamage = 0;
        else if (++hitsWithoutDamage > 12) {
            skipUntil.put(target.getUuid(), System.currentTimeMillis() + 5000);
            target = null;
            return;
        }

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        int min = Math.min(minDelay.get(), maxDelay.get()), max = Math.max(minDelay.get(), maxDelay.get());
        nextHit = System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private PlayerEntity findTarget() {
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isValid(player)) continue;
            double score = mc.player.squaredDistanceTo(player) - (Beast.isBeast(player) ? 1e6 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private boolean isValid(PlayerEntity player) {
        if (player == mc.player || !player.isAlive() || player.isRemoved() || player.isCreative() || player.isSpectator()) return false;
        if (SpawnArea.isInSpawn(player)) return false;
        if (mc.player.squaredDistanceTo(player) > searchRange.get() * searchRange.get()) return false;
        if (!isRealPlayer(player) || !Friends.get().shouldAttack(player) || !Kos.allows(player) || !TeamDeathmatch.allowTarget(player) || !Streak.allowTarget(player)) return false;
        Long skip = skipUntil.get(player.getUuid());
        if (skip != null && skip > System.currentTimeMillis()) return false;
        return extraFilter == null || extraFilter.test(player);
    }

    // NPCs are player entities too; real players are in the tab list and don't use version-2 UUIDs.
    private boolean isRealPlayer(PlayerEntity player) {
        if (player.getUuid().version() == 2 || mc.getNetworkHandler() == null) return false;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null;
    }

    private double distanceTo(PlayerEntity player) {
        Vec3d eye = mc.player.getEyePos();
        Box box = player.getBoundingBox();
        double x = Math.clamp(eye.x, box.minX, box.maxX), y = Math.clamp(eye.y, box.minY, box.maxY), z = Math.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3d(x, y, z));
    }

    public PlayerEntity getTarget() {
        return target;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }
}

package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.AimLock;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Presses the attack key on a random jitter-click gap and turns off when you die.
 * Clicks while ZAimbot is locked on. Within reach the crosshair has to be that enemy.
 * Past reach the crosshair cannot be them, so it still clicks when someone closer is under it:
 * that swing hits whoever is standing in the way. It does not click at air or blocks.
 * This is only a left-click input. The game handles the hit on its normal input tick.
 * Sending the attack packet directly rubberbands you.
 */
public class AutoClicker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay-ms")
        .description("Shortest time between left clicks.")
        .defaultValue(40)
        .range(1, 1000)
        .sliderRange(20, 200)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-ms")
        .description("Longest time between left clicks. Each wait is random between min and max.")
        .defaultValue(65)
        .range(1, 1000)
        .sliderRange(20, 200)
        .build()
    );

    private long nextClick;

    public AutoClicker() {
        super(MeteorPitAddon.PIT, "auto-clicker", "Left-clicks at a random 40-65 ms interval while an enemy is locked on, and still clicks when they are out of reach if someone closer is under the crosshair. Turns off when you die.");
    }

    @Override
    public void onActivate() {
        nextClick = System.currentTimeMillis() + delay();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;
        if (mc.player.isDead() || mc.player.deathTime > 0 || mc.player.getHealth() <= 0) toggle();
    }

    // After ZAimbot's render, so the lock is this frame's target and not the previous one.
    @EventHandler(priority = EventPriority.LOWEST)
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (mc.player.isDead() || mc.player.deathTime > 0 || mc.player.getHealth() <= 0) return;
        if (Streak.ownsMovement()) return;
        if (!lockedOn()) return;
        // The crosshair entity is who the click actually hits. Their hurt time is the one that matters.
        if (!HitSelect.allow(mc.targetedEntity)) return;
        if (System.currentTimeMillis() < nextClick) return;

        // Same edge a real click produces. handleInputEvents turns it into doAttack next tick.
        KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(mc.options.attackKey.getBoundKeyTranslationKey()));
        nextClick = System.currentTimeMillis() + delay();
    }

    /**
     * ZAimbot has an enemy. Within reach the crosshair has to be on them.
     * Past reach it cannot be, so a different living entity under the crosshair is someone
     * in the way and the click hits them. No crosshair entity means the swing would miss or mine.
     */
    private boolean lockedOn() {
        Entity locked = AimLock.get();
        if (locked == null || SpawnArea.isInSpawn(mc.player) || SpawnArea.isInSpawn(locked)) return false;
        if (mc.targetedEntity == locked) return Streak.allowTarget(locked);
        if (!(mc.targetedEntity instanceof LivingEntity)) return false;
        if (distanceTo(locked) <= mc.player.getEntityInteractionRange()) return false;
        return lookingAt(locked) && canHit(mc.targetedEntity);
    }

    /** Same skips the aimbot uses, for the person standing in front of a farther lock. */
    private boolean canHit(Entity entity) {
        if (SpawnArea.isInSpawn(entity) || !TeamDeathmatch.allowTarget(entity) || !Streak.allowTarget(entity)) return false;
        return !(entity instanceof PlayerEntity player) || Friends.get().shouldAttack(player);
    }

    /** True when the look direction meets the lock's hitbox, so the crosshair entity is on the way to them. */
    private boolean lookingAt(Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVec(1.0f).multiply(128));
        return entity.getBoundingBox().raycast(eye, end).isPresent();
    }

    private double distanceTo(Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        Box box = entity.getBoundingBox();
        double x = Math.clamp(eye.x, box.minX, box.maxX);
        double y = Math.clamp(eye.y, box.minY, box.maxY);
        double z = Math.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3d(x, y, z));
    }

    private int delay() {
        int min = Math.min(minDelay.get(), maxDelay.get()), max = Math.max(minDelay.get(), maxDelay.get());
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}

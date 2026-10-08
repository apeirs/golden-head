package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.Mover;
import dev.goldenhead.utils.Route;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Walks to one player and never attacks them. Movement is Care Package's {@link Route}: a grid path
 * around walls and other players, launch pads included, and stop once your eyes are within {@link #REACH}
 * of them. Look at a player when you turn it on, or type their name. While that player is in the world,
 * this owns movement so aim and chase modules do not turn you away or hit them.
 */
public class Streak extends Module {
    /** Same closeness Dragon Egg uses before it stops running. */
    private static final double REACH = 4.0;
    /** Same turn rate Dragon Egg uses. */
    private static final double ROTATION_SPEED = 0.6;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> target = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("Player to walk to. Look at someone when you enable this and their name is filled in.")
        .defaultValue("")
        .build()
    );

    private final Mover mover = new Mover();
    private final Route.Follower router = new Route.Follower();
    private boolean warned;

    public Streak() {
        super(MeteorPitAddon.PIT, "streak", "Walks toward the player you selected and does not attack them.");
    }

    @Override
    public void onActivate() {
        warned = false;
        router.clear();
        PlayerEntity looked = lookedAt();
        if (looked != null) target.set(looked.getName().getString());
    }

    @Override
    public void onDeactivate() {
        mover.release();
        router.clear();
    }

    /** True while this is on and that player is in the world, so walking owns the keys and the camera. */
    public static boolean ownsMovement() {
        Streak streak = Modules.get().get(Streak.class);
        return streak != null && streak.isActive() && streak.findSelected() != null;
    }

    /** False only for the selected player while this is on. */
    public static boolean allowTarget(Entity entity) {
        if (!(entity instanceof PlayerEntity player)) return true;
        Streak streak = Modules.get().get(Streak.class);
        if (streak == null || !streak.isActive()) return true;
        String name = streak.target.get().trim();
        if (name.isEmpty()) return true;
        return !player.getName().getString().equalsIgnoreCase(name);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.isDead() || mc.player.deathTime > 0 || mc.player.getHealth() <= 0) {
            toggle();
            return;
        }
        if (mc.currentScreen != null) {
            mover.stop();
            return;
        }

        String name = target.get().trim();
        if (name.isEmpty()) {
            mover.stop();
            router.clear();
            if (!warned) {
                ChatUtils.warning("Look at a player, or set a target.");
                warned = true;
            }
            return;
        }

        PlayerEntity player = findSelected();
        if (player == null) {
            mover.stop();
            router.clear();
            return;
        }

        // Run until the aim point is in reach, then hold the look.
        Vec3d aim = player.getBoundingBox().getCenter();
        if (mc.player.getEyePos().distanceTo(aim) <= REACH && !router.usingPad()) {
            mover.stop();
            mover.lookAt(aim, ROTATION_SPEED);
            return;
        }

        mover.autoJump = true;
        mover.rotationSpeed = ROTATION_SPEED;
        router.follow(mover, player.getBlockPos(), Math.max(1, (int) Math.floor(REACH) - 1), aim);
    }

    private PlayerEntity findSelected() {
        String name = target.get().trim();
        if (name.isEmpty() || mc.world == null) return null;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!player.isAlive() || player.isRemoved()) continue;
            if (player.getName().getString().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    /** Real player under the crosshair, never an NPC. */
    private PlayerEntity lookedAt() {
        if (!(mc.targetedEntity instanceof PlayerEntity player) || !isPerson(player)) return null;
        return player;
    }

    private boolean isPerson(PlayerEntity player) {
        if (player == mc.player || !player.isAlive() || player.isSpectator()) return false;
        if (player.getUuid().version() == 2 || mc.getNetworkHandler() == null) return false;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
        return entry != null;
    }

    @Override
    public String getInfoString() {
        String name = target.get().trim();
        if (name.isEmpty()) return null;
        return router.usingPad() ? name + " pad" : name;
    }
}

package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * Jumps once when a hit's knockback arrives, if you are on the ground. The knockback packet is not
 * changed, so the server and the client move you the same way and you are not pulled back.
 * A jump is a real keypress. It does not shorten a push the server already decided; at EU-to-NA
 * ping that decision is in the packet before the jump can matter. It does get you off the ground
 * for the next hit.
 */
public class JumpReset extends Module {
    /** Sideways speed change big enough to be a hit, not a tiny velocity sync. */
    private static final double HIT = 0.2;

    private boolean pressed;

    public JumpReset() {
        super(MeteorPitAddon.PIT, "jump-reset", "Jumps when a hit lands so the server agrees with the movement. Does not change knockback.");
    }

    @Override
    public void onDeactivate() {
        release();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.currentScreen != null || pressed) return;
        if (!(event.packet instanceof EntityVelocityUpdateS2CPacket packet)) return;
        if (packet.getEntityId() != mc.player.getId()) return;
        if (!mc.player.isOnGround() || SpawnArea.isInSpawn(mc.player)) return;
        if (Input.isPressed(mc.options.jumpKey)) return;

        Vec3d commanded = packet.getVelocity();
        Vec3d current = mc.player.getVelocity();
        double dx = commanded.x - current.x, dz = commanded.z - current.z;
        if (dx * dx + dz * dz < HIT * HIT) return;

        mc.options.jumpKey.setPressed(true);
        pressed = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // One tick is one jump. The normal input path sends it, then the key is let go.
        if (pressed) release();
    }

    private void release() {
        if (!pressed) return;
        pressed = false;
        if (mc.player != null && !Input.isPressed(mc.options.jumpKey)) mc.options.jumpKey.setPressed(false);
    }
}

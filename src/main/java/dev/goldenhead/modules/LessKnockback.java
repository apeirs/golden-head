package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.EntityVelocityUpdateS2CPacketAccessor;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * Keeps a fraction of the sideways knockback from a hit. Vertical knockback is unchanged, so you
 * still pop up, but you are not pushed as far out of range. Meant for the EU-to-NA delay, where the
 * return swing arrives after the full push has already carried you off them.
 */
public class LessKnockback extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> horizontal = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal")
        .description("How much of the sideways knockback to keep. 1 is vanilla, 0.85 is a slight trim.")
        .defaultValue(0.85)
        .range(0.7, 1)
        .sliderRange(0.7, 1)
        .build()
    );

    public LessKnockback() {
        super(MeteorPitAddon.PIT, "less-knockback", "Takes slightly less horizontal knockback from hits so a high-ping counter can still reach.");
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.player == null || !(event.packet instanceof EntityVelocityUpdateS2CPacket packet)) return;
        if (packet.getEntityId() != mc.player.getId()) return;

        double keep = horizontal.get();
        if (keep >= 1) return;

        Vec3d commanded = packet.getVelocity();
        Vec3d current = mc.player.getVelocity();
        // The packet is the velocity the server wants. Scale only the horizontal change from where we are now.
        double x = current.x + (commanded.x - current.x) * keep;
        double z = current.z + (commanded.z - current.z) * keep;
        ((EntityVelocityUpdateS2CPacketAccessor) packet).meteor$setVelocity(new Vec3d(x, commanded.y, z));
    }
}

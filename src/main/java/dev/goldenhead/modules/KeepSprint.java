package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.entity.player.AttackEntityEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.LivingEntity;

/**
 * 1.8 clients drop sprint when you attack. Turn it back on if forward is still held.
 * Does not touch velocity. Undoing the hit slowdown on the client is what rubberbands.
 */
public class KeepSprint extends Module {
    private boolean restore;

    public KeepSprint() {
        super(MeteorPitAddon.PIT, "keep-sprint", "Keeps sprint on after a hit while you hold forward. Does not change knockback.");
    }

    @Override
    public void onDeactivate() {
        restore = false;
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (mc.player == null || !mc.player.isSprinting()) return;
        if (!(event.entity instanceof LivingEntity)) return;
        if (SpawnArea.isInSpawn(mc.player) || WTap.isTapping()) return;
        restore = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!restore || mc.player == null) return;
        restore = false;
        if (WTap.isTapping() || SpawnArea.isInSpawn(mc.player)) return;
        if (Input.isPressed(mc.options.forwardKey)) mc.player.setSprinting(true);
    }
}

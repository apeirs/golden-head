package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.AimLock;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import meteordevelopment.orbit.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Requests sprint through the normal movement input whenever forward movement can sprint. */
public class WwSprint extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("meteor-pit");
    private boolean diagnosedSprintDrop;

    public WwSprint() {
        super(MeteorPitAddon.COMBAT_18, "ww-sprint", "Keeps sprint requested while you hold forward whenever vanilla allows sprinting.");
    }

    // Respawn and attack handling can clear the sprint flag after input was sampled. Restore the
    // flag at the end of the tick so the next movement update sends the normal sprint start state.
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            diagnosedSprintDrop = false;
            return;
        }

        boolean forward = isPressed(mc.options.forwardKey);
        if (!forward) {
            diagnosedSprintDrop = false;
            return;
        }
        if (mc.player.isSprinting()) {
            diagnosedSprintDrop = false;
            return;
        }

        String reason = sprintBlockReason();
        if (!diagnosedSprintDrop) {
            LOG.warn("Ww Sprint observed sprint off while forward was held; reason={}, lock={}, w-tap={}, hunger={}, using-item={}, sneaking={}, vehicle={}, gliding={}, on-ground={}, jump-held={}, vertical-velocity={}, screen={}",
                reason == null ? "none; reasserting sprint" : reason,
                lockSprintOverride(), WTap.isTapping(), mc.player.getHungerManager().getFoodLevel(),
                mc.player.isUsingItem(), mc.player.isSneaking(), mc.player.hasVehicle(), mc.player.isGliding(),
                mc.player.isOnGround(), isPressed(mc.options.jumpKey), mc.player.getVelocity().y,
                mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName());
            diagnosedSprintDrop = true;
        }

        if (reason == null) mc.player.setSprinting(true);
    }

    /** Called while KeyboardInput samples this tick, so vanilla sees the sprint request in time. */
    public static boolean shouldForceSprint() {
        return sprintBlockReason() == null;
    }

    /** Returns the current reason sprint cannot be requested, or null if it should be active. */
    private static String sprintBlockReason() {
        WwSprint module = meteordevelopment.meteorclient.systems.modules.Modules.get().get(WwSprint.class);
        if (module == null || !module.isActive()) return "module disabled";
        var mc = MeteorClient.mc;
        if (mc.player == null) return "no player (dead/respawning or disconnected)";
        if (mc.world == null) return "no world (disconnected or respawning)";
        if (mc.currentScreen != null) return "screen open: " + mc.currentScreen.getClass().getSimpleName();
        if (!lockSprintOverride() && WTap.isTapping()) return "W-Tap reset is active";

        var player = mc.player;
        if (player.isDead() || player.getHealth() <= 0) return "player is dead";
        if (player.isSneaking()) return "sneaking";
        if (player.isUsingItem()) return "using an item";
        if (player.hasVehicle()) return "riding a vehicle";
        if (player.isGliding()) return "gliding";
        if (player.hasStatusEffect(StatusEffects.BLINDNESS)) return "blindness effect";
        if (!player.getHungerManager().canSprint() && !player.getAbilities().allowFlying) return "hunger too low";
        if (isPressed(mc.options.backKey)) return "backward input held";
        if (!isPressed(mc.options.forwardKey)) return "forward input is not held";
        return null;
    }

    /** A live aim lock means sprint is part of the user's combat movement; W-Tap must not cancel it. */
    public static boolean lockSprintOverride() {
        WwSprint module = meteordevelopment.meteorclient.systems.modules.Modules.get().get(WwSprint.class);
        return module != null && module.isActive() && AimLock.get() != null;
    }

    @EventHandler
    private void onPositionCorrection(PacketEvent.Receive event) {
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet) || mc.player == null) return;
        var player = mc.player;
        LOG.warn("Ww Sprint saw server position correction: pos={}, velocity={}, sprinting={}, forward={}, jump={}, horizontal-collision={}, vertical-collision={}, packet={}",
            player.getEntityPos(), player.getVelocity(), player.isSprinting(), isPressed(mc.options.forwardKey),
            isPressed(mc.options.jumpKey), player.horizontalCollision, player.verticalCollision, packet);
    }

    private static boolean isPressed(KeyBinding bind) {
        if (Input.isPressed(bind)) return true;
        InputUtil.Key key = InputUtil.fromTranslationKey(bind.getBoundKeyTranslationKey());
        return key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(MeteorClient.mc.getWindow(), key.getCode());
    }
}

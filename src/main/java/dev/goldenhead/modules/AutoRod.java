package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.utils.AimLock;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/**
 * 1.8 rod combo. Throws the fishing rod at the locked player, waits a few ticks, reels, then
 * goes back to the previous slot. Does nothing if the hotbar has no rod. Real item use, not a
 * packet edit.
 */
public class AutoRod extends Module {
    private enum Step { IDLE, THROW, WAIT, REEL, BACK }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> cooldownMs = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ms")
        .description("Wait between rod combos.")
        .defaultValue(800)
        .range(200, 3000)
        .sliderRange(400, 1500)
        .build()
    );

    private final Setting<Integer> reelTicks = sgGeneral.add(new IntSetting.Builder()
        .name("reel-ticks")
        .description("Ticks between the throw and the reel.")
        .defaultValue(5)
        .range(2, 15)
        .sliderRange(3, 10)
        .build()
    );

    private Step step = Step.IDLE;
    private int wait;
    private long next;
    private boolean swapped;

    public AutoRod() {
        super(MeteorPitAddon.PIT, "auto-rod", "Throws and reels a hotbar fishing rod at the locked player, then switches back.");
    }

    @Override
    public void onDeactivate() {
        finish();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) {
            finish();
            return;
        }

        switch (step) {
            case THROW -> {
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
                wait = reelTicks.get();
                step = Step.WAIT;
            }
            case WAIT -> {
                if (--wait <= 0) step = Step.REEL;
            }
            case REEL -> {
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);
                step = Step.BACK;
            }
            case BACK -> finish();
            case IDLE -> {
                if (System.currentTimeMillis() < next || mc.player.isUsingItem()) return;
                if (mc.player.fishHook != null) return;
                if (SpawnArea.isInSpawn(mc.player)) return;
                Entity locked = AimLock.get();
                if (!(locked instanceof PlayerEntity player) || SpawnArea.isInSpawn(player)) return;
                if (player.hurtTime > 0) return;
                double dist = mc.player.distanceTo(player);
                if (dist < 2.5 || dist > 8) return;
                var rod = InvUtils.findInHotbar(Items.FISHING_ROD);
                if (!rod.found() || !rod.isHotbar()) return;
                if (!rod.isMainHand()) {
                    if (!InvUtils.swap(rod.slot(), true)) return;
                    swapped = true;
                }
                step = Step.THROW;
            }
        }
    }

    private void finish() {
        if (swapped) InvUtils.swapBack();
        swapped = false;
        if (step != Step.IDLE) next = System.currentTimeMillis() + cooldownMs.get();
        step = Step.IDLE;
    }
}

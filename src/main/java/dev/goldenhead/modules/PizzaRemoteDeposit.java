package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.mixin.TextDisplayAccessor;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/** Manually probes whether the Pizza stand accepts a right-click from beyond interaction range. */
public class PizzaRemoteDeposit extends Module {
    private final Setting<Integer> scanRange = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("scan-range")
        .description("How far to search loaded entities for the Pizza stand and its NPC.")
        .defaultValue(128)
        .range(16, 256)
        .sliderRange(32, 192)
        .build()
    );

    private boolean attempted;
    private int pizzasBefore;
    private long attemptedAt;
    private String serverReply;
    private String status = "finding Pizza stand";

    public PizzaRemoteDeposit() {
        super(MeteorPitAddon.PIT, "remote-cash-deposit", "Send one manual right-click to the Pizza stand NPC from your current position. Does not move; the server may reject it.");
    }

    @Override
    public void onActivate() {
        attempted = false;
        pizzasBefore = 0;
        attemptedAt = 0;
        serverReply = null;
        status = "finding Pizza stand";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) return;

        if (attempted) {
            if (pizzaCount(player) > pizzasBefore) {
                info("Server accepted the remote stand interaction: pizza count increased.");
                toggle();
                return;
            }
            if (System.currentTimeMillis() - attemptedAt >= 1800) {
                if (serverReply != null) info("Pizza stand replied: %s", serverReply);
                else info("Remote right-click sent; no confirmation or pizza-count change was observed.");
                toggle();
            }
            return;
        }

        Entity stand = nearestPizzaStand(player);
        if (stand == null) {
            status = "waiting for loaded Pizza stand";
            return;
        }
        Entity npc = nearestStandNpc(stand.getEntityPos());
        if (npc == null) {
            status = "waiting for stand NPC";
            return;
        }

        pizzasBefore = pizzaCount(player);
        attemptedAt = System.currentTimeMillis();
        attempted = true;
        status = "testing server response";
        double distance = Math.sqrt(player.squaredDistanceTo(npc));
        mc.interactionManager.interactEntity(player, npc, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        info("Sent one right-click to the Pizza stand NPC from %.1f blocks away.", distance);
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!attempted) return;
        String line = PitUtils.clean(event.getMessage());
        if (line.contains("pizza") || line.contains("cash") || line.contains("deposit") || line.contains("dollar")) {
            serverReply = line;
        }
    }

    private Entity nearestPizzaStand(ClientPlayerEntity player) {
        Entity best = null;
        double bestDistance = (double) scanRange.get() * scanRange.get();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof DisplayEntity.TextDisplayEntity display)) continue;
            Text text = ((TextDisplayAccessor) display).goldenhead$getText();
            if (text == null || !PitUtils.clean(text).contains("pizza")) continue;
            double distance = player.squaredDistanceTo(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private Entity nearestStandNpc(Vec3d stand) {
        Entity best = null;
        double bestDistance = 9;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity) || entity instanceof PlayerEntity || entity instanceof ArmorStandEntity) continue;
            double distance = entity.getEntityPos().squaredDistanceTo(stand);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static int pizzaCount(ClientPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && PitUtils.clean(stack.getName()).contains("pizza")) count += stack.getCount();
        }
        return count;
    }

    @Override
    public String getInfoString() {
        return status;
    }
}

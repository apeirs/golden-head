package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.pittrade.MysticRead;
import dev.goldenhead.pittrade.PitTraderScreen;
import dev.goldenhead.pittrade.TradeOffer;
import dev.goldenhead.pittrade.VileBook;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a mystic, shows its vile price, and watches equipped items for that build.
 * Underpay and real price each send /trade. With both off, the only result is the popup.
 */
public class PitTrader extends Module {
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private final SettingGroup sgWatch = settings.getDefaultGroup();
    private final SettingGroup sgOffer = settings.createGroup("Offer");

    private final Setting<Integer> range = sgWatch.add(new IntSetting.Builder()
        .name("range")
        .description("How far to look for the item, in blocks.")
        .defaultValue(32)
        .range(4, 128)
        .sliderRange(8, 64)
        .build()
    );

    private final Setting<Boolean> exact = sgWatch.add(new BoolSetting.Builder()
        .name("exact-enchants")
        .description("Only match when they have these enchants and no extras. Off still requires the ones you picked.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> skipFriends = sgWatch.add(new BoolSetting.Builder()
        .name("skip-friends")
        .description("Don't alert or trade friends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> alertSeconds = sgWatch.add(new IntSetting.Builder()
        .name("alert-seconds")
        .description("How long the popup stays up.")
        .defaultValue(6)
        .range(2, 30)
        .sliderRange(2, 12)
        .build()
    );

    private final Setting<Integer> realertSeconds = sgWatch.add(new IntSetting.Builder()
        .name("realert-seconds")
        .description("Don't pop the same player and item again until this long has passed.")
        .defaultValue(25)
        .range(5, 300)
        .sliderRange(5, 60)
        .build()
    );

    public final Setting<Boolean> underpay = sgOffer.add(new BoolSetting.Builder()
        .name("underpay")
        .description("Send /trade and put in this percent of the vile price. Wins if real price is also on.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> underpayPercent = sgOffer.add(new IntSetting.Builder()
        .name("underpay-percent")
        .description("Percent of the price to offer. Rounds down to a whole vile.")
        .defaultValue(80)
        .range(1, 100)
        .sliderRange(50, 100)
        .visible(underpay::get)
        .build()
    );

    public final Setting<Boolean> realPrice = sgOffer.add(new BoolSetting.Builder()
        .name("real-price")
        .description("Send /trade and put in the real price, rounded to a whole vile.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> confirm = sgOffer.add(new BoolSetting.Builder()
        .name("confirm-offer")
        .description("Press accept only after their side has the item and your vile is in. Off leaves the offer sitting there.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> clickDelay = sgOffer.add(new IntSetting.Builder()
        .name("click-delay-ms")
        .description("Gap between trade-window clicks. One click at a time.")
        .defaultValue(150)
        .range(50, 1000)
        .sliderRange(50, 400)
        .build()
    );

    private final Setting<Integer> tradeCooldown = sgOffer.add(new IntSetting.Builder()
        .name("trade-cooldown-s")
        .description("Wait this long before asking the same player to trade again.")
        .defaultValue(45)
        .range(10, 300)
        .sliderRange(15, 120)
        .build()
    );

    public final VileBook.Wanted wanted = new VileBook.Wanted();
    private final TradeOffer offer = new TradeOffer();
    private final Map<String, Long> seenAt = new LinkedHashMap<>();
    private final List<Alert> alerts = new ArrayList<>();

    public PitTrader() {
        super(MeteorPitAddon.PIT, "pit-trader", "Prices a mystic, pops up when someone is wearing it, and can offer underpay or the real vile price.");
    }

    public MinecraftClient client() {
        return mc;
    }

    public boolean confirmOffer() {
        return confirm.get();
    }

    public boolean exactEnchants() {
        return exact.get();
    }

    public int clickDelayMs() {
        return clickDelay.get();
    }

    public long tradeCooldownMs() {
        return tradeCooldown.get() * 1000L;
    }

    @Override
    public void onDeactivate() {
        offer.clear();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList row = theme.horizontalList();
        row.add(theme.item(MysticRead.icon(wanted.kind, wanted.color))).padRight(4);
        VileBook.Quote quote = VileBook.quote(wanted.kind, wanted.enchants, wanted.priceLives);
        String text = VileBook.itemName(wanted.kind, wanted.color, wanted.enchants) + "   " + VileBook.vile(quote.hundredths);
        if (quote.confidence == VileBook.Confidence.ESTIMATE) text += " est.";
        row.add(theme.label(text)).padRight(6);
        WButton open = row.add(theme.button("Edit")).widget();
        open.action = () -> mc.setScreen(new PitTraderScreen(GuiThemes.get(), this));
        return row;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        offer.tick(this);
        if (mc.player.age % 10 != 0) return;
        scan();
    }

    private void scan() {
        long now = System.currentTimeMillis();
        double reach = range.get();
        double reachSq = reach * reach;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.getUuid().version() == 2) continue;
            if (skipFriends.get() && Friends.get().isFriend(player)) continue;
            if (player.squaredDistanceTo(mc.player) > reachSq) continue;

            for (EquipmentSlot slot : SLOTS) {
                ItemStack stack = player.getEquippedStack(slot);
                MysticRead.Seen seen = MysticRead.parse(stack);
                if (!MysticRead.matches(seen, wanted, exact.get())) continue;

                String key = player.getUuid() + ":" + slot.getName() + ":" + seen.signature();
                Long last = seenAt.get(key);
                boolean again = last == null || now - last >= realertSeconds.get() * 1000L;
                if (again) {
                    seenAt.put(key, now);
                    alert(player, seen);
                }
                if (underpay.get() || realPrice.get()) offer(player, seen);
            }
        }
        if (seenAt.size() > 300) {
            Iterator<Map.Entry<String, Long>> it = seenAt.entrySet().iterator();
            while (seenAt.size() > 200 && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private void alert(PlayerEntity player, MysticRead.Seen seen) {
        int lives = seen.lives >= 0 ? seen.lives : wanted.priceLives;
        VileBook.Quote quote = priceOf(seen, lives);
        String item = VileBook.itemName(seen.kind, seen.color, seen.enchants);
        String name = player.getName().getString();

        StringBuilder detail = new StringBuilder();
        String enchants = VileBook.enchantList(seen.enchants);
        if (!enchants.isEmpty()) detail.append(enchants).append(" · ");
        detail.append("worth ").append(VileBook.vile(quote.hundredths));
        if (quote.confidence == VileBook.Confidence.ESTIMATE) detail.append(" est.");
        if (seen.lives < 0) detail.append(" · lives ?");
        else detail.append(" · ").append(seen.lives).append(" lives");
        detail.append(offerNote(quote, seen));

        alerts.add(new Alert(name + " has " + item, detail.toString(), System.currentTimeMillis() + alertSeconds.get() * 1000L));
        while (alerts.size() > 4) alerts.remove(0);
        info("%s has %s.", name, item);
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f));
    }

    private void offer(PlayerEntity player, MysticRead.Seen seen) {
        boolean livesKnown = seen.lives >= 0 || !priceOf(seen, wanted.priceLives).perLife();
        int lives = seen.lives >= 0 ? seen.lives : wanted.priceLives;
        VileBook.Quote quote = priceOf(seen, lives);
        int count = VileBook.offerCount(quote.hundredths, underpay.get(), underpayPercent.get());
        offer.request(this, player, count, livesKnown);
    }

    private VileBook.Quote priceOf(MysticRead.Seen seen, int lives) {
        Map<String, Integer> priced = wanted.enchants.isEmpty() ? seen.enchants : wanted.enchants;
        return VileBook.quote(wanted.kind, priced, lives);
    }

    private String offerNote(VileBook.Quote quote, MysticRead.Seen seen) {
        if (!underpay.get() && !realPrice.get()) return "";
        if (seen.lives < 0 && quote.perLife()) return " · not offering";
        int count = VileBook.offerCount(quote.hundredths, underpay.get(), underpayPercent.get());
        if (count <= 0) return " · offer is 0v";
        int have = MysticRead.countVile(mc.player);
        if (have < count) return " · need " + count + "v";
        return underpay.get() ? " · offering " + count + "v under" : " · offering " + count + "v";
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        long now = System.currentTimeMillis();
        alerts.removeIf(alert -> alert.until < now);
        if (alerts.isEmpty() || mc.options.hudHidden || mc.textRenderer == null) return;

        int y = 28;
        for (Alert alert : alerts) {
            int width = Math.max(mc.textRenderer.getWidth(alert.title), mc.textRenderer.getWidth(alert.detail)) + 18;
            int x = (event.screenWidth - width) / 2;
            int height = 28;
            event.drawContext.fill(x, y, x + width, y + height, 0xE0121216);
            event.drawContext.fill(x, y, x + 3, y + height, 0xFFE0B040);
            event.drawContext.drawTextWithShadow(mc.textRenderer, alert.title, x + 8, y + 4, 0xFFFFFF);
            event.drawContext.drawTextWithShadow(mc.textRenderer, alert.detail, x + 8, y + 15, 0xFFE0B060);
            y += height + 4;
        }
    }

    @Override
    public String getInfoString() {
        VileBook.Quote quote = VileBook.quote(wanted.kind, wanted.enchants, wanted.priceLives);
        String price = VileBook.vile(quote.hundredths);
        if (underpay.get()) return price + " under";
        if (realPrice.get()) return price + " real";
        return price;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        if (tag == null) return null;
        tag.putString("traderKind", wanted.kind.name());
        tag.putString("traderColor", wanted.color.name());
        tag.putInt("traderPriceLives", wanted.priceLives);
        tag.putInt("traderMinLives", wanted.minLives);
        NbtCompound enchants = new NbtCompound();
        wanted.enchants.forEach(enchants::putInt);
        tag.put("traderEnchants", enchants);
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        try {
            wanted.kind = VileBook.MysticKind.valueOf(tag.getString("traderKind", wanted.kind.name()));
        } catch (IllegalArgumentException ignored) {
            wanted.kind = VileBook.MysticKind.PANTS;
        }
        try {
            wanted.color = VileBook.PantColor.valueOf(tag.getString("traderColor", wanted.color.name()));
        } catch (IllegalArgumentException ignored) {
            wanted.color = VileBook.PantColor.RED;
        }
        wanted.priceLives = Math.max(1, tag.getInt("traderPriceLives", wanted.priceLives));
        wanted.minLives = Math.max(0, tag.getInt("traderMinLives", wanted.minLives));
        wanted.enchants.clear();
        NbtCompound enchants = tag.getCompoundOrEmpty("traderEnchants");
        for (String key : enchants.getKeys()) {
            int tier = enchants.getInt(key, 0);
            if (tier >= 1 && tier <= 3 && VileBook.get(key) != null) wanted.enchants.put(VileBook.get(key).name, tier);
        }
        return this;
    }

    private record Alert(String title, String detail, long until) {}
}

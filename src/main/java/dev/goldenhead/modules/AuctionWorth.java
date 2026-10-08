package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.pittrade.MysticRead;
import dev.goldenhead.pittrade.VileBook;
import dev.goldenhead.utils.EventTracker;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pit auction: open the menu, read the item (fresh or enchanted), and price it from the trader catalog.
 * Never bids. Bids spend gold.
 */
public class AuctionWorth extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> announce = sgGeneral.add(new BoolSetting.Builder()
        .name("announce")
        .description("Print the item and its catalog price in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> openMenu = sgGeneral.add(new BoolSetting.Builder()
        .name("open-menu")
        .description("Use the auction's click command to open the menu.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> closeMenu = sgGeneral.add(new BoolSetting.Builder()
        .name("close-menu")
        .description("Close the menu after reading it, when this module opened it.")
        .defaultValue(true)
        .build()
    );

    private final EventTracker tracker = new EventTracker();
    private String command;
    private long openedAt;
    private boolean openedByUs;
    private boolean live;
    private boolean gaveUp;
    private String announced = "";
    private String infoPrice = "";

    public AuctionWorth() {
        super(MeteorPitAddon.PIT, "auction-worth", "Opens a Pit auction, reads the item, and prices it from the trader catalog. Works on enchanted mystics. Never bids.");
    }

    @Override
    public void onActivate() {
        command = null;
        openedAt = 0;
        openedByUs = false;
        live = false;
        gaveUp = false;
        announced = "";
        infoPrice = "";
        tracker.reset();
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        tracker.reset();
        onActivate();
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        Text message = event.getMessage();
        String line = PitUtils.clean(message);
        tracker.onChat(line);
        if (!line.contains("auction")) return;
        String cmd = findRunCommand(message);
        if (cmd != null) command = cmd;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        tracker.tickBossBar();
        boolean running = tracker.running(EventTracker.PitEvent.AUCTION);
        if (!running) {
            if (live) {
                live = false;
                command = null;
                openedAt = 0;
                openedByUs = false;
                gaveUp = false;
            }
        } else {
            if (!live) {
                live = true;
                gaveUp = false;
                openedAt = 0;
                openedByUs = false;
                announced = "";
                infoPrice = "";
            }
            if (openMenu.get() && command != null && announced.isEmpty() && !gaveUp && openedAt == 0 && mc.currentScreen == null) {
                String cmd = command.startsWith("/") ? command : "/" + command;
                ChatUtils.sendPlayerMsg(cmd, false);
                openedAt = System.currentTimeMillis();
                openedByUs = true;
            }
        }

        long now = System.currentTimeMillis();
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            if (openedAt != 0 && now - openedAt > 8000) {
                gaveUp = true;
                openedAt = 0;
                openedByUs = false;
                if (announce.get()) info("Auction menu didn't open.");
            }
            return;
        }
        String title = PitUtils.clean(screen.getTitle());
        boolean titled = title.contains("auction") || title.contains("bid");
        boolean waiting = openedAt != 0;
        if (!titled && !waiting) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        List<ItemStack> prizes = prizes(handler);
        boolean catalog = hasCatalogItem(prizes);
        if (!prizes.isEmpty() && (titled || catalog)) {
            publish(prizes);
            if (openedByUs && closeMenu.get()) mc.player.closeHandledScreen();
            openedAt = 0;
            openedByUs = false;
            return;
        }
        if (waiting && now - openedAt > 8000) {
            gaveUp = true;
            openedAt = 0;
            if (announce.get()) info("Auction menu had no item to price.");
            if (openedByUs && titled && closeMenu.get()) mc.player.closeHandledScreen();
            openedByUs = false;
        }
    }

    private void publish(List<ItemStack> prizes) {
        String text = describe(prizes);
        infoPrice = shortPrice(prizes);
        if (text.equals(announced)) return;
        announced = text;
        gaveUp = false;
        if (!announce.get()) return;
        info("Auction: %s", text);
        mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f));
    }

    private static List<ItemStack> prizes(ScreenHandler handler) {
        int total = handler.slots.size();
        int container = total > 36 ? total - 36 : total;
        List<ItemStack> mystics = new ArrayList<>();
        List<ItemStack> other = new ArrayList<>();
        for (int i = 0; i < container; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (MysticRead.parse(stack) != null || MysticRead.isVile(stack)) mystics.add(stack);
            else if (!chrome(stack)) other.add(stack);
        }
        return mystics.isEmpty() ? other : mystics;
    }

    private static boolean hasCatalogItem(List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (MysticRead.parse(stack) != null || MysticRead.isVile(stack)) return true;
        }
        return false;
    }

    /** Glass, bidder heads, and bid buttons. The prize itself is not chrome, even if its lore says click. */
    private static boolean chrome(ItemStack stack) {
        String name = PitUtils.clean(stack.getName());
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        if (id.contains("stained_glass_pane") || id.equals("glass_pane") || id.equals("barrier") || id.contains("player_head")) return true;
        if (name.contains("bid") || name.contains("click to") || name.contains("close") || name.contains("go back")
            || name.contains("confirm") || name.contains("cancel") || name.contains("highest bidder")
            || name.contains("current bid") || name.contains("time left") || name.contains("raise")) return true;
        if (id.equals("gold_ingot") || id.equals("gold_nugget") || id.equals("gold_block") || id.equals("raw_gold")) {
            return name.matches(".*\\d+\\s*g.*") || name.matches(".*\\+\\s*\\d+.*");
        }
        return false;
    }

    private static String describe(List<ItemStack> stacks) {
        StringBuilder out = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (out.length() > 0) out.append(" | ");
            out.append(describeOne(stack));
        }
        return out.toString();
    }

    private static String describeOne(ItemStack stack) {
        int count = Math.max(1, stack.getCount());
        MysticRead.Seen seen = MysticRead.parse(stack);
        if (seen != null) return describeMystic(seen, count);
        if (MysticRead.isVile(stack)) return count + " Chunk of Vile · " + VileBook.vile(count * 100);
        String name = Formatting.strip(stack.getName().getString());
        if (name == null || name.isBlank()) name = Registries.ITEM.getId(stack.getItem()).getPath();
        return (count > 1 ? count + "x " : "") + name + " · not in the trader catalog";
    }

    private static String describeMystic(MysticRead.Seen seen, int count) {
        int lives = seen.lives >= 0 ? seen.lives : 1;
        VileBook.Quote quote = VileBook.quote(seen.kind, seen.enchants, lives);
        long hundredths = (long) quote.hundredths * count;
        if (hundredths > Integer.MAX_VALUE) hundredths = Integer.MAX_VALUE;

        StringBuilder text = new StringBuilder();
        if (count > 1) text.append(count).append("x ");
        text.append(VileBook.itemName(seen.kind, seen.color, seen.enchants));
        String enchants = VileBook.enchantList(seen.enchants);
        if (!enchants.isEmpty()) text.append(" · ").append(enchants);
        if (seen.lives >= 0) text.append(" · ").append(seen.lives).append(seen.lives == 1 ? " life" : " lives");
        else if (quote.perLife()) text.append(" · lives not shown, per-life rows counted as 1");
        text.append(" · worth ").append(VileBook.vile((int) hundredths));
        if (quote.confidence == VileBook.Confidence.ESTIMATE || (seen.lives < 0 && quote.perLife())) text.append(" est.");
        return text.toString();
    }

    private static String shortPrice(List<ItemStack> prizes) {
        if (prizes.size() != 1) return prizes.size() + " items";
        ItemStack stack = prizes.getFirst();
        MysticRead.Seen seen = MysticRead.parse(stack);
        if (seen != null) {
            int lives = seen.lives >= 0 ? seen.lives : 1;
            VileBook.Quote quote = VileBook.quote(seen.kind, seen.enchants, lives);
            long hundredths = (long) quote.hundredths * Math.max(1, stack.getCount());
            if (hundredths > Integer.MAX_VALUE) hundredths = Integer.MAX_VALUE;
            String price = VileBook.vile((int) hundredths);
            if (quote.confidence == VileBook.Confidence.ESTIMATE || (seen.lives < 0 && quote.perLife())) price += " est.";
            return price;
        }
        if (MysticRead.isVile(stack)) return VileBook.vile(Math.max(1, stack.getCount()) * 100);
        return "no price";
    }

    private static String findRunCommand(Text text) {
        List<String> found = new ArrayList<>();
        text.visit((style, part) -> {
            if (style.getClickEvent() instanceof ClickEvent.RunCommand run) found.add(run.command());
            return Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return found.isEmpty() ? null : found.getFirst();
    }

    @Override
    public String getInfoString() {
        if (live && announced.isEmpty() && !gaveUp) return "auction";
        if (gaveUp && infoPrice.isEmpty()) return "no item";
        return infoPrice.isEmpty() ? null : infoPrice;
    }
}

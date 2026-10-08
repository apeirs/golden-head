package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.pittrade.MysticRead;
import dev.goldenhead.pittrade.VileBook;
import dev.goldenhead.utils.PitUtils;
import meteordevelopment.meteorclient.events.game.ItemStackTooltipEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Item hover in the Pit. Every tooltip gets one catalog line.
 * An item the trader catalog does not price says nothing worth.
 */
public class EstWorth extends Module {
    public EstWorth() {
        super(MeteorPitAddon.PIT, "est-worth", "On every item tooltip in the Pit, shows the catalog estimate. An item with no price says nothing worth.");
    }

    @EventHandler
    private void onTooltip(ItemStackTooltipEvent event) {
        if (!PitUtils.sidebarContains("pit")) return;
        ItemStack stack = event.itemStack();
        if (stack == null || stack.isEmpty()) return;

        Integer hundredths = hundredths(stack);
        if (hundredths == null || hundredths <= 0) {
            event.appendEnd(Text.literal("nothing worth").formatted(Formatting.GRAY));
            return;
        }
        event.appendEnd(Text.literal("est worth " + VileBook.vile(hundredths)).formatted(Formatting.YELLOW));
    }

    /** Stack price in hundredths of a vile, or null when the catalog has no price. */
    private static Integer hundredths(ItemStack stack) {
        int count = Math.max(1, stack.getCount());
        MysticRead.Seen seen = MysticRead.parse(stack);
        if (seen != null && baseMatches(stack, seen)) {
            // The Pit spawn bow can carry ordinary item lore that overlaps the bow-enchant
            // catalog. A mystic bow has an explicit tier/name marker or a lives counter.
            if (seen.kind == VileBook.MysticKind.BOW && !isMysticBow(stack, seen)) return null;
            int lives = seen.lives >= 0 ? seen.lives : 1;
            int each = VileBook.quote(seen.kind, seen.enchants, lives).hundredths;
            if (each <= 0) return null;
            long total = (long) each * count;
            if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) total;
        }
        if (MysticRead.isVile(stack)) {
            long total = 100L * count;
            if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) total;
        }
        return null;
    }

    /**
     * The catalog kind has to be the item the Pit actually uses.
     * A diamond sword is not a mystic sword, and diamond boots are not armageddon.
     */
    private static boolean baseMatches(ItemStack stack, MysticRead.Seen seen) {
        Item item = stack.getItem();
        return switch (seen.kind) {
            case SWORD -> item == Items.GOLDEN_SWORD;
            case BOW -> item instanceof BowItem;
            case PANTS, DARK, RAGE, SEWER, AQUA -> item == Items.LEATHER_LEGGINGS;
            case ARCHANGEL -> item == Items.IRON_CHESTPLATE;
            case ARMAGEDDON -> item == Items.DIAMOND_BOOTS;
            case HELMET -> item == Items.GOLDEN_HELMET;
            case CACTUS -> item == Items.CACTUS;
            case FEATHER -> item == Items.FEATHER;
            case GEM -> item == Items.EMERALD;
            case CAKE -> item == Items.CAKE;
            case BUNDLE -> item == Items.CHEST_MINECART || item == Items.MINECART;
        };
    }

    private static boolean isMysticBow(ItemStack stack, MysticRead.Seen seen) {
        String name = dev.goldenhead.utils.PitUtils.clean(stack.getName());
        return seen.lives >= 0 || name.contains("mystic") || name.contains("fresh") || name.contains("tier");
    }
}

package dev.goldenhead.pittrade;

import dev.goldenhead.utils.PitUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a Pit mystic off an item stack, and builds the icon shown next to its price. */
public final class MysticRead {
    private static final Pattern LIVES_FRONT = Pattern.compile("lives:?\\s*(\\d+)(?:\\s*/\\s*(\\d+))?");
    private static final Pattern LIVES_BACK = Pattern.compile("(\\d+)(?:\\s*/\\s*(\\d+))?\\s*lives");

    public static final class Seen {
        public final VileBook.MysticKind kind;
        public final VileBook.PantColor color;
        public final LinkedHashMap<String, Integer> enchants = new LinkedHashMap<>();
        public final int lives;
        public final int maxLives;
        public final String rawName;

        Seen(VileBook.MysticKind kind, VileBook.PantColor color, int lives, int maxLives, String rawName) {
            this.kind = kind;
            this.color = color;
            this.lives = lives;
            this.maxLives = maxLives;
            this.rawName = rawName;
        }

        public String signature() {
            StringBuilder out = new StringBuilder();
            out.append(kind.name()).append(':');
            if (color != null) out.append(color.name());
            out.append(':').append(lives);
            enchants.forEach((name, tier) -> out.append(':').append(name).append(tier));
            return out.toString();
        }
    }

    private MysticRead() {}

    public static Seen parse(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        String rawName = stack.getName().getString();
        String name = PitUtils.clean(stack.getName());
        LinkedHashMap<String, Integer> enchants = new LinkedHashMap<>();
        int lives = -1;
        int maxLives = -1;

        // The name sometimes carries an enchant ("Solitude III Pants") and the lore carries the rest.
        VileBook.readEnchants(name, enchants);

        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                String cleaned = PitUtils.clean(line);
                int[] livesFound = readLives(cleaned);
                if (livesFound != null) {
                    lives = livesFound[0];
                    if (livesFound[1] >= 0) maxLives = livesFound[1];
                    continue;
                }
                VileBook.readEnchants(cleaned, enchants);
            }
        }

        VileBook.MysticKind named = kindOf(name);
        VileBook.MysticKind kind = named != null ? named : kindFromStack(stack, name, enchants);
        if (kind == null) return null;
        if (kind == VileBook.MysticKind.BUNDLE && name.contains("empty")) return null;

        enchants.entrySet().removeIf(entry -> !VileBook.fits(VileBook.get(entry.getKey()), kind));

        VileBook.PantColor color = colorOf(kind, name, stack);
        Seen seen = new Seen(kind, color, lives, maxLives, rawName);
        seen.enchants.putAll(enchants);
        return seen;
    }

    private static int[] readLives(String cleaned) {
        if (cleaned == null || !cleaned.contains("lives")) return null;
        Matcher front = LIVES_FRONT.matcher(cleaned);
        if (front.find()) return new int[] {Integer.parseInt(front.group(1)), front.group(2) == null ? -1 : Integer.parseInt(front.group(2))};
        Matcher back = LIVES_BACK.matcher(cleaned);
        if (back.find()) return new int[] {Integer.parseInt(back.group(1)), back.group(2) == null ? -1 : Integer.parseInt(back.group(2))};
        return null;
    }

    /**
     * Kind when the display name never says pants, sword, or bow.
     * Enchanted auction items are often just "Tier III" plus the enchant lines.
     */
    private static VileBook.MysticKind kindFromStack(ItemStack stack, String name, Map<String, Integer> enchants) {
        VileBook.MysticKind fromItem = itemKind(stack, name);
        VileBook.MysticKind fromEnchants = enchantKind(enchants);
        if (fromItem != null && !fromItem.enchantable()) return fromItem;
        if (fromItem == VileBook.MysticKind.RAGE || fromItem == VileBook.MysticKind.DARK) return fromItem;
        if (fromEnchants != null && (fromItem == null || fromEnchants == fromItem || fitsAll(enchants, fromEnchants))) return fromEnchants;
        return fromItem;
    }

    private static boolean fitsAll(Map<String, Integer> enchants, VileBook.MysticKind kind) {
        for (String name : enchants.keySet()) if (!VileBook.fits(VileBook.get(name), kind)) return false;
        return true;
    }

    private static VileBook.MysticKind itemKind(ItemStack stack, String name) {
        Item item = stack.getItem();
        if (item == Items.CACTUS) return VileBook.MysticKind.CACTUS;
        if (item == Items.FEATHER) return VileBook.MysticKind.FEATHER;
        if (item == Items.EMERALD && (name.contains("gem") || name.contains("legit"))) return VileBook.MysticKind.GEM;
        if (item == Items.CAKE && name.contains("mini")) return VileBook.MysticKind.CAKE;
        if ((item == Items.CHEST_MINECART || item == Items.MINECART) && name.contains("bundle") && !name.contains("empty")) {
            return VileBook.MysticKind.BUNDLE;
        }
        if (name.contains("archangel")) return VileBook.MysticKind.ARCHANGEL;
        if (name.contains("armageddon")) return VileBook.MysticKind.ARMAGEDDON;
        if (name.contains("golden helmet") || name.contains("gold helmet")) return VileBook.MysticKind.HELMET;
        if (name.contains("philosopher") || name.contains("cactus")) return VileBook.MysticKind.CACTUS;
        if (name.contains("funky feather") || (name.contains("feather") && !name.contains("falling"))) return VileBook.MysticKind.FEATHER;
        if (name.contains("totally legit") || name.contains("legit gem")) return VileBook.MysticKind.GEM;
        if (name.contains("mini cake")) return VileBook.MysticKind.CAKE;
        if (name.contains("bundle")) return VileBook.MysticKind.BUNDLE;
        if (name.contains("evil") || name.contains("dark pants") || name.contains("dark")) return VileBook.MysticKind.DARK;
        if (name.contains("rage")) return VileBook.MysticKind.RAGE;
        if (name.contains("sewer")) return VileBook.MysticKind.SEWER;
        if (name.contains("aqua")) return VileBook.MysticKind.AQUA;

        boolean hint = name.contains("mystic") || name.contains("tier") || name.contains("fresh")
            || name.contains("pants") || name.contains("sword") || name.contains("bow") || name.contains("helmet");
        if (item instanceof BowItem && hint) return VileBook.MysticKind.BOW;
        if (stack.isIn(ItemTags.SWORDS) && hint) return VileBook.MysticKind.SWORD;
        if (item == Items.LEATHER_LEGGINGS && hint) return VileBook.MysticKind.PANTS;
        if (item == Items.GOLDEN_HELMET && hint) return VileBook.MysticKind.HELMET;
        return null;
    }

    /** The one kind every listed enchant can live on. Pants enchants also fit rage, so those stay pants unless a rage-only enchant is present. */
    private static VileBook.MysticKind enchantKind(Map<String, Integer> enchants) {
        if (enchants.isEmpty()) return null;
        EnumSet<VileBook.MysticKind> possible = null;
        boolean rageOnly = false;
        for (String name : enchants.keySet()) {
            VileBook.Enchant enchant = VileBook.get(name);
            if (enchant == null) continue;
            if (enchant.kinds.contains(VileBook.MysticKind.RAGE) && !enchant.kinds.contains(VileBook.MysticKind.PANTS)) rageOnly = true;
            EnumSet<VileBook.MysticKind> fits = EnumSet.noneOf(VileBook.MysticKind.class);
            for (VileBook.MysticKind kind : VileBook.MysticKind.values()) if (VileBook.fits(enchant, kind)) fits.add(kind);
            if (possible == null) possible = fits;
            else possible.retainAll(fits);
        }
        if (possible == null || possible.isEmpty()) return null;
        if (possible.size() == 1) return possible.iterator().next();
        // Every pants enchant also fits rage. That pair is pants unless a rage-only enchant narrowed it.
        if (possible.equals(EnumSet.of(VileBook.MysticKind.PANTS, VileBook.MysticKind.RAGE))) {
            return rageOnly ? VileBook.MysticKind.RAGE : VileBook.MysticKind.PANTS;
        }
        if (rageOnly && possible.contains(VileBook.MysticKind.RAGE)) return VileBook.MysticKind.RAGE;
        return null;
    }

    public static boolean matches(Seen seen, VileBook.Wanted wanted, boolean exact) {
        if (seen == null || wanted == null || seen.kind != wanted.kind) return false;
        if (wanted.kind == VileBook.MysticKind.PANTS && seen.color != wanted.color) return false;

        if (wanted.enchants.isEmpty()) return seen.enchants.isEmpty();

        for (Map.Entry<String, Integer> entry : wanted.enchants.entrySet()) {
            Integer have = seen.enchants.get(entry.getKey());
            int need = entry.getValue();
            if (have == null || have < need) return false;
        }
        if (exact && seen.enchants.size() != wanted.enchants.size()) return false;
        return wanted.minLives <= 0 || seen.lives < 0 || seen.lives >= wanted.minLives;
    }

    public static boolean isVile(ItemStack stack) {
        return stack != null && !stack.isEmpty() && PitUtils.clean(stack.getName()).contains("vile");
    }

    public static int countVile(PlayerEntity player) {
        if (player == null) return 0;
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isVile(stack)) count += stack.getCount();
        }
        return count;
    }

    public static ItemStack icon(VileBook.MysticKind kind, VileBook.PantColor color) {
        ItemStack stack = switch (kind) {
            case SWORD -> new ItemStack(Items.GOLDEN_SWORD);
            case BOW -> new ItemStack(Items.BOW);
            case ARCHANGEL -> new ItemStack(Items.IRON_CHESTPLATE);
            case ARMAGEDDON -> new ItemStack(Items.DIAMOND_BOOTS);
            case HELMET -> new ItemStack(Items.GOLDEN_HELMET);
            case CACTUS -> new ItemStack(Items.CACTUS);
            case BUNDLE -> new ItemStack(Items.CHEST_MINECART);
            case FEATHER -> new ItemStack(Items.FEATHER);
            case GEM -> new ItemStack(Items.EMERALD);
            case CAKE -> new ItemStack(Items.CAKE);
            default -> dyedPants(kind, color);
        };
        return stack;
    }

    private static ItemStack dyedPants(VileBook.MysticKind kind, VileBook.PantColor color) {
        ItemStack stack = new ItemStack(Items.LEATHER_LEGGINGS);
        int rgb = switch (kind) {
            case DARK -> 0x1A1A1A;
            case RAGE -> 0xAA2A2A;
            case SEWER -> 0x3A6B3A;
            case AQUA -> 0x3AD4D4;
            default -> color == null ? VileBook.PantColor.RED.rgb : color.rgb;
        };
        stack.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb));
        return stack;
    }

    private static VileBook.MysticKind kindOf(String name) {
        if (name.contains("archangel")) return VileBook.MysticKind.ARCHANGEL;
        if (name.contains("armageddon")) return VileBook.MysticKind.ARMAGEDDON;
        if (name.contains("golden helmet") || name.contains("gold helmet")) return VileBook.MysticKind.HELMET;
        if (name.contains("philosopher") || name.contains("cactus")) return VileBook.MysticKind.CACTUS;
        if (name.contains("funky feather") || (name.contains("feather") && !name.contains("falling"))) return VileBook.MysticKind.FEATHER;
        if (name.contains("totally legit") || name.contains("legit gem")) return VileBook.MysticKind.GEM;
        if (name.contains("mini cake")) return VileBook.MysticKind.CAKE;
        if (name.contains("bundle")) return VileBook.MysticKind.BUNDLE;
        if (name.contains("evil pants") || name.contains("dark pants")) return VileBook.MysticKind.DARK;
        if (name.contains("rage pants")) return VileBook.MysticKind.RAGE;
        if (name.contains("sewer pants")) return VileBook.MysticKind.SEWER;
        if (name.contains("aqua pants")) return VileBook.MysticKind.AQUA;
        if (name.contains("pants")) return VileBook.MysticKind.PANTS;
        if (name.contains("sword")) return VileBook.MysticKind.SWORD;
        if (name.contains("bow")) return VileBook.MysticKind.BOW;
        return null;
    }

    private static VileBook.PantColor colorOf(VileBook.MysticKind kind, String name, ItemStack stack) {
        if (kind != VileBook.MysticKind.PANTS) return null;
        for (VileBook.PantColor color : VileBook.PantColor.values()) {
            if (name.contains(color.label.toLowerCase(Locale.ROOT))) return color;
        }
        DyedColorComponent dye = stack.get(DataComponentTypes.DYED_COLOR);
        if (dye == null) return null;
        VileBook.PantColor nearest = null;
        int best = Integer.MAX_VALUE;
        int rgb = dye.rgb() & 0xFFFFFF;
        for (VileBook.PantColor color : VileBook.PantColor.values()) {
            int distance = distance(rgb, color.rgb);
            if (distance < best) {
                best = distance;
                nearest = color;
            }
        }
        return best < 80 * 80 * 3 ? nearest : null;
    }

    private static int distance(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255);
        int dg = ((a >> 8) & 255) - ((b >> 8) & 255);
        int db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }
}

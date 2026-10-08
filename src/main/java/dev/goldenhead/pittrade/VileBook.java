package dev.goldenhead.pittrade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vile prices for Pit mystics.
 * <p>
 * Point prices are the middle of a quoted band when a 2024–2026 print exists.
 * Fresh: color pants 2v (21 Apr 2025), sword 7v of 6–8 (4 Apr 2026), bow 3.5v of 3–4,
 * sewer 6.5v of 6–7, aqua 0.5v, dark 4.5v of 4–5 (4 Sep 2024), rage 17.5v of 17–18,
 * golden helmet 50/50 30v of 27–32 (18 Apr 2026), archangel 63v and armageddon 64v
 * (namuwiki / jp wiki, late 2025). Vile was still quoted at 5,000g on 8 Aug 2025.
 * Sweaty III is 0.5v per life. Sweaty II is 3.5v. Boo-boo III, Protection III, Sharp III,
 * Knockback III, the XP enchants and Combo: Heal III are the April 2026 bands, stored as the middle.
 * Perun, Solitude, Regularity and Gamble III use the forum prints (Perun III ~210v, Solitude III ~33v,
 * Regularity III 1v per life, Gamble III about 64v). Plain Mirror had no current print; combos that
 * include it trade far higher, so its row stays an estimate. Per-life rows multiply by the lives spinner.
 * An enchanted total is the sum of the selected rows. Fresh is a different item and is not added on top.
 * Trade goods have no enchants. Cactus is 2.2v (21 Feb 2026 buy print; 9 cactus = 1 bundle).
 * A filled pants bundle is 19v, the middle of 18–20 on 18 Apr 2026. Funky Feather is the middle
 * of the wiki's 30v and 35v lines, and a gem is the middle of 40–45v. Both are estimates.
 * Mini Cake is 4v, the old 1–3 fresh ratio at 2v a fresh. Repair kits cannot be traded.
 */
public final class VileBook {
    public enum Confidence { QUOTED, ESTIMATE }

    public enum MysticKind {
        PANTS("Pants"),
        SWORD("Sword"),
        BOW("Bow"),
        DARK("Dark Pants"),
        RAGE("Rage Pants"),
        SEWER("Sewer Pants"),
        AQUA("Aqua Pants"),
        ARCHANGEL("Archangel Chestplate"),
        ARMAGEDDON("Armageddon Boots"),
        HELMET("Golden Helmet"),
        CACTUS("Philosopher's Cactus"),
        BUNDLE("Pants Bundle"),
        FEATHER("Funky Feather"),
        GEM("Totally Legit Gem"),
        CAKE("Mini Cake");

        public final String label;

        MysticKind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public boolean cappedTokens() {
            return this == PANTS || this == SWORD || this == BOW || this == RAGE;
        }

        public boolean enchantable() {
            return cappedTokens() || this == DARK;
        }

        /** Kept specials people trade for vile. They have no enchants and no lives. */
        public boolean tradeGood() {
            return this == CACTUS || this == BUNDLE || this == FEATHER || this == GEM || this == CAKE;
        }
    }

    public enum PantColor {
        RED("Red", 0xFF5555),
        ORANGE("Orange", 0xFFAA00),
        YELLOW("Yellow", 0xFFFF55),
        GREEN("Green", 0x55FF55),
        BLUE("Blue", 0x5555FF);

        public final String label;
        public final int rgb;

        PantColor(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Hundredths of a vile. 0.5v per life is 50, and the quote multiplies by lives. */
    public record Tier(int hundredths, boolean perLife, Confidence confidence) {}

    public static final class Enchant {
        public final String name;
        public final boolean rare;
        /** One checkbox, no tier. Dark-pants enchants work this way. */
        public final boolean single;
        public final Tier t1, t2, t3;
        public final EnumSet<MysticKind> kinds = EnumSet.noneOf(MysticKind.class);

        Enchant(String name, boolean rare, boolean single, Tier t1, Tier t2, Tier t3) {
            this.name = name;
            this.rare = rare;
            this.single = single;
            this.t1 = t1;
            this.t2 = t2;
            this.t3 = t3;
        }

        public Tier tier(int n) {
            return switch (n) {
                case 1 -> t1;
                case 2 -> t2;
                case 3 -> t3;
                default -> null;
            };
        }

        public int maxTier() {
            if (t3 != null) return 3;
            if (t2 != null) return 2;
            return 1;
        }
    }

    public static final class Line {
        public final String name;
        public final int tier;
        public final int hundredths;
        public final Confidence confidence;
        public final boolean perLife;
        public final int rateHundredths;

        Line(String name, int tier, int hundredths, Confidence confidence, boolean perLife, int rateHundredths) {
            this.name = name;
            this.tier = tier;
            this.hundredths = hundredths;
            this.confidence = confidence;
            this.perLife = perLife;
            this.rateHundredths = rateHundredths;
        }
    }

    public static final class Quote {
        public final int hundredths;
        public final Confidence confidence;
        public final boolean fresh;
        public final List<Line> lines;
        public final int freshHundredths;
        public final Confidence freshConfidence;

        Quote(int hundredths, Confidence confidence, boolean fresh, List<Line> lines, int freshHundredths, Confidence freshConfidence) {
            this.hundredths = hundredths;
            this.confidence = confidence;
            this.fresh = fresh;
            this.lines = lines;
            this.freshHundredths = freshHundredths;
            this.freshConfidence = freshConfidence;
        }

        public boolean perLife() {
            for (Line line : lines) if (line.perLife) return true;
            return false;
        }
    }

    public static final class Wanted {
        public MysticKind kind = MysticKind.PANTS;
        public PantColor color = PantColor.RED;
        public final LinkedHashMap<String, Integer> enchants = new LinkedHashMap<>();
        public int priceLives = 20;
        public int minLives = 1;
    }

    private static final List<Enchant> ALL = new ArrayList<>();
    private static final Map<String, Enchant> BY_NAME = new LinkedHashMap<>();
    private static final List<Enchant> BY_LENGTH = new ArrayList<>();

    private VileBook() {}

    public static List<Enchant> forKind(MysticKind kind) {
        List<Enchant> out = new ArrayList<>();
        for (Enchant enchant : ALL) if (fits(enchant, kind)) out.add(enchant);
        return out;
    }

    /** Rage pants keep their own enchants and can also roll the normal pants pool. */
    public static boolean fits(Enchant enchant, MysticKind kind) {
        if (enchant == null || kind == null) return false;
        if (enchant.kinds.contains(kind)) return true;
        return kind == MysticKind.RAGE && enchant.kinds.contains(MysticKind.PANTS);
    }

    public static Enchant get(String name) {
        if (name == null) return null;
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** A lore line that is one enchant, or null. */
    public static Match matchLine(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) return null;
        String s = normalize(cleaned);
        if (s.isEmpty() || s.contains("lives")) return null;

        for (Enchant enchant : BY_LENGTH) {
            String key = normalize(enchant.name);
            int tier = 0;
            if (s.equals(key)) {
                // A bare name is tier I. Dark enchants and auction lines often omit the numeral.
                tier = 1;
            } else if (s.startsWith(key)) {
                String rest = s.substring(key.length()).trim();
                if (rest.startsWith(":") || rest.startsWith("-")) rest = rest.substring(1).trim();
                tier = roman(rest);
                if (tier == 0 && enchant.single && rest.isEmpty()) tier = 1;
            } else if (s.endsWith(" " + key)) {
                tier = roman(s.substring(0, s.length() - key.length()).trim());
            } else {
                continue;
            }
            if (tier <= 0) continue;
            if (enchant.single) tier = 1;
            if (enchant.tier(tier) == null) tier = enchant.maxTier();
            return new Match(enchant, tier);
        }
        return null;
    }

    public record Match(Enchant enchant, int tier) {}

    /** Pull every enchant on a lore line into {@code into}. A bare name counts as tier I. */
    public static void readEnchants(String cleaned, Map<String, Integer> into) {
        if (cleaned == null || cleaned.isBlank() || into == null) return;
        String s = normalize(cleaned);
        if (s.isEmpty() || s.contains("lives")) return;

        Match whole = matchLine(cleaned);
        if (whole != null) into.merge(whole.enchant().name, whole.tier(), Math::max);

        boolean[] used = new boolean[s.length()];
        if (whole != null) {
            int at = wordIndex(s, normalize(whole.enchant().name), 0);
            if (at >= 0) mark(used, at, at + normalize(whole.enchant().name).length());
        }
        for (Enchant enchant : BY_LENGTH) {
            String key = normalize(enchant.name);
            int from = 0;
            while (from < s.length()) {
                int at = wordIndex(s, key, from);
                if (at < 0) break;
                int end = at + key.length();
                if (overlaps(used, at, end)) {
                    from = at + 1;
                    continue;
                }
                int tier = roman(s.substring(end).trim());
                if (tier <= 0) {
                    from = at + 1;
                    continue;
                }
                if (enchant.single) tier = 1;
                if (enchant.tier(tier) == null) tier = enchant.maxTier();
                into.merge(enchant.name, tier, Math::max);
                mark(used, at, end);
                break;
            }
        }
    }

    public static Quote quote(MysticKind kind, Map<String, Integer> selected, int lives) {
        int fresh = freshHundredths(kind);
        Confidence freshConfidence = freshConfidence(kind);
        int usedLives = Math.max(1, lives);
        if (selected == null || selected.isEmpty()) {
            return new Quote(fresh, freshConfidence, true, List.of(), fresh, freshConfidence);
        }

        List<Line> lines = new ArrayList<>();
        int sum = 0;
        Confidence confidence = Confidence.QUOTED;
        for (Map.Entry<String, Integer> entry : selected.entrySet()) {
            Enchant enchant = get(entry.getKey());
            if (!fits(enchant, kind)) continue;
            int tierN = enchant.single ? 1 : entry.getValue();
            Tier tier = enchant.tier(tierN);
            if (tier == null) continue;
            int hundredths = tier.perLife ? tier.hundredths * usedLives : tier.hundredths;
            if (tier.confidence == Confidence.ESTIMATE) confidence = Confidence.ESTIMATE;
            sum += hundredths;
            lines.add(new Line(enchant.name, tierN, hundredths, tier.confidence, tier.perLife, tier.perLife ? tier.hundredths : 0));
        }
        if (lines.isEmpty()) return new Quote(fresh, freshConfidence, true, List.of(), fresh, freshConfidence);
        return new Quote(sum, confidence, false, List.copyOf(lines), fresh, freshConfidence);
    }

    public static int freshHundredths(MysticKind kind) {
        return switch (kind) {
            case PANTS -> 200;
            case SWORD -> 700;
            case BOW -> 350;
            case SEWER -> 650;
            case AQUA -> 50;
            case DARK -> 450;
            case RAGE -> 1750;
            case ARCHANGEL -> 6300;
            case ARMAGEDDON -> 6400;
            case HELMET -> 3000;
            case CACTUS -> 220;
            case BUNDLE -> 1900;
            case FEATHER -> 3250;
            case GEM -> 4250;
            case CAKE -> 400;
        };
    }

    public static Confidence freshConfidence(MysticKind kind) {
        return switch (kind) {
            case FEATHER, GEM, CAKE -> Confidence.ESTIMATE;
            default -> Confidence.QUOTED;
        };
    }

    public static String itemName(MysticKind kind, PantColor color, Map<String, Integer> selected) {
        boolean fresh = selected == null || selected.isEmpty();
        return switch (kind) {
            case PANTS -> (fresh ? "Fresh " : "") + (color == null ? "" : color.label + " ") + "Pants";
            case SWORD -> fresh ? "Fresh Mystic Sword" : "Mystic Sword";
            case BOW -> fresh ? "Fresh Mystic Bow" : "Mystic Bow";
            case DARK -> fresh ? "Fresh Dark Pants" : "Dark Pants";
            case RAGE -> fresh ? "Fresh Rage Pants" : "Rage Pants";
            case SEWER -> fresh ? "Fresh Sewer Pants" : "Sewer Pants";
            case AQUA -> fresh ? "Fresh Aqua Pants" : "Aqua Pants";
            case ARCHANGEL -> "Archangel Chestplate";
            case ARMAGEDDON -> "Armageddon Boots";
            case HELMET -> fresh ? "Fresh Golden Helmet" : "Golden Helmet";
            case CACTUS -> "Philosopher's Cactus";
            case BUNDLE -> "Pants Bundle";
            case FEATHER -> "Funky Feather";
            case GEM -> "Totally Legit Gem";
            case CAKE -> "Mini Cake";
        };
    }

    public static String enchantList(Map<String, Integer> selected) {
        if (selected == null || selected.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : selected.entrySet()) {
            if (out.length() > 0) out.append(", ");
            Enchant enchant = get(entry.getKey());
            out.append(entry.getKey());
            if (enchant == null || !enchant.single) out.append(' ').append(romanNumeral(entry.getValue()));
        }
        return out.toString();
    }

    /** Whole vile to put in the trade. Underpay rounds down. Real price rounds to the nearest vile. */
    public static int offerCount(int hundredths, boolean underpay, int percent) {
        if (hundredths <= 0) return 0;
        if (underpay) return (int) ((long) hundredths * Math.max(0, percent) / 10_000L);
        return (hundredths + 50) / 100;
    }

    public static String vile(int hundredths) {
        if (hundredths < 0) hundredths = 0;
        int whole = hundredths / 100;
        int frac = hundredths % 100;
        if (frac == 0) return whole + "v";
        if (frac % 10 == 0) return whole + "." + (frac / 10) + "v";
        return whole + "." + (frac < 10 ? "0" : "") + frac + "v";
    }

    public static String romanNumeral(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "";
        };
    }

    public static int tokens(Map<String, Integer> selected) {
        int n = 0;
        if (selected == null) return 0;
        for (int tier : selected.values()) n += Math.max(0, tier);
        return n;
    }

    private static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("rare!", "");
        s = s.replace('’', '\'').replace('`', '\'').replace("\"", "");
        s = s.replace("'", "");
        s = s.replaceAll("[^a-z0-9:!+ -]", " ");
        return s.replaceAll(" +", " ").trim();
    }

    /** Roman numeral, or a lone 1/2/3, at the start of {@code rest}. 0 if there is none. */
    private static int roman(String rest) {
        rest = rest.replaceFirst("^[\\s:!+\\-]+", "");
        if (rest.startsWith("iii")) return 3;
        if (rest.startsWith("ii")) return 2;
        if (rest.startsWith("i")) return 1;
        if (startsToken(rest, "3")) return 3;
        if (startsToken(rest, "2")) return 2;
        if (startsToken(rest, "1")) return 1;
        return 0;
    }

    private static boolean startsToken(String rest, String token) {
        if (!rest.startsWith(token)) return false;
        if (rest.length() == token.length()) return true;
        char next = rest.charAt(token.length());
        return next == ' ' || next == ',' || next == '.' || next == '/' || next == ')';
    }

    private static int wordIndex(String s, String key, int from) {
        if (key.isEmpty()) return -1;
        int at = Math.max(0, from);
        while (at <= s.length() - key.length()) {
            int found = s.indexOf(key, at);
            if (found < 0) return -1;
            boolean left = found == 0 || s.charAt(found - 1) == ' ';
            int end = found + key.length();
            boolean right = end == s.length() || s.charAt(end) == ' ';
            if (left && right) return found;
            at = found + 1;
        }
        return -1;
    }

    private static void mark(boolean[] used, int from, int to) {
        for (int i = Math.max(0, from); i < to && i < used.length; i++) used[i] = true;
    }

    private static boolean overlaps(boolean[] used, int from, int to) {
        for (int i = Math.max(0, from); i < to && i < used.length; i++) if (used[i]) return true;
        return false;
    }

    private static Tier T(String spec) {
        Confidence confidence = Confidence.ESTIMATE;
        boolean perLife = false;
        String number = spec;
        if (number.startsWith("qp")) {
            confidence = Confidence.QUOTED;
            perLife = true;
            number = number.substring(2);
        } else if (number.startsWith("ep")) {
            perLife = true;
            number = number.substring(2);
        } else if (number.startsWith("q")) {
            confidence = Confidence.QUOTED;
            number = number.substring(1);
        } else if (number.startsWith("e")) {
            number = number.substring(1);
        }
        int hundredths = (int) Math.round(Double.parseDouble(number) * 100.0);
        return new Tier(hundredths, perLife, confidence);
    }

    /** Estimate curve: I ≈ 1/4 of III, II ≈ 1/2, rounded to 0.1v and at least 0.1v. */
    private static Tier[] curve(double three) {
        return new Tier[] { T(est(three * 0.25)), T(est(three * 0.5)), T(est(three)) };
    }

    private static String est(double vile) {
        double rounded = Math.round(vile * 10.0) / 10.0;
        if (rounded < 0.1) rounded = 0.1;
        return "e" + rounded;
    }

    private static void add(String name, boolean rare, Tier t1, Tier t2, Tier t3, MysticKind... kinds) {
        Enchant enchant = new Enchant(name, rare, t2 == null && t3 == null, t1, t2, t3);
        Collections.addAll(enchant.kinds, kinds);
        ALL.add(enchant);
        BY_NAME.put(name.toLowerCase(Locale.ROOT), enchant);
    }

    private static void add(String name, boolean rare, Tier[] curve, MysticKind... kinds) {
        add(name, rare, curve[0], curve[1], curve[2], kinds);
    }

    private static void single(String name, boolean rare, String spec, MysticKind kind) {
        add(name, rare, T(spec), null, null, kind);
    }

    static {
        Tier[] sweaty = { T("e0.8"), T("q3.5"), T("qp0.5") };
        Tier[] xpBoost = { T("e0.6"), T("e1.2"), T("q2.5") };
        Tier[] xpBump = { T("e0.4"), T("e0.8"), T("q1.5") };
        Tier[] goldBoost = curve(2.0);
        Tier[] goldBump = curve(1.2);
        Tier[] moctezuma = curve(1.5);
        Tier[] rich = curve(0.8);
        Tier[] strike = curve(0.6);
        Tier[] radar = curve(1.0);

        MysticKind[] gear = { MysticKind.PANTS, MysticKind.SWORD, MysticKind.BOW };
        add("Sweaty", false, sweaty, gear);
        add("XP Boost", false, xpBoost, gear);
        add("XP Bump", false, xpBump, gear);
        add("Gold Boost", false, goldBoost, gear);
        add("Gold Bump", false, goldBump, gear);
        add("Moctezuma", false, moctezuma, gear);
        add("Critically Rich", false, rich, gear);
        add("Strike Gold", false, strike, gear);
        add("Pants Radar", false, radar, gear);

        // Pants. III anchors that are quoted stay quoted; the rest of the curve is an estimate.
        add("Protection", false, T("e0.9"), T("e1.8"), T("q3.5"), MysticKind.PANTS);
        add("Boo-boo", false, T("e1"), T("e2.5"), T("q5"), MysticKind.PANTS);
        add("Mirror", true, curve(20), MysticKind.PANTS);
        add("Solitude", true, T("q5"), T("q14.5"), T("q33"), MysticKind.PANTS);
        add("Retro-Gravity Microcosm", true, T("e4"), T("e10"), T("e15"), MysticKind.PANTS);
        add("Fractional Reserve", true, curve(8), MysticKind.PANTS);
        add("Critically Funky", false, curve(15), MysticKind.PANTS);
        add("Phoenix", true, curve(7), MysticKind.PANTS);
        add("Singularity", true, curve(6), MysticKind.PANTS);
        add("Assassin", true, curve(4), MysticKind.PANTS);
        add("Double-jump", true, curve(6), MysticKind.PANTS);
        add("Gomraw's Heart", true, curve(4), MysticKind.PANTS);
        add("Divine Miracle", true, curve(3), MysticKind.PANTS);
        add("Escape Pod", true, curve(3), MysticKind.PANTS);
        add("Paparazzi", true, curve(4), MysticKind.PANTS);
        add("Instaboom", true, curve(3), MysticKind.PANTS);
        add("Wolf Pack", true, curve(3), MysticKind.PANTS);
        add("Snowballs", true, curve(2.5), MysticKind.PANTS);
        add("Hearts", false, curve(1.5), MysticKind.PANTS);
        add("Golden Heart", false, curve(2.5), MysticKind.PANTS);
        add("Peroxide", false, curve(2.5), MysticKind.PANTS);
        add("Pit Blob", true, curve(2), MysticKind.PANTS);
        add("Last Stand", false, curve(2), MysticKind.PANTS);
        add("Gotta go fast", false, curve(1.5), MysticKind.PANTS);
        add("Ring Armor", false, curve(1.5), MysticKind.PANTS);
        add("Diamond Allergy", false, curve(1.5), MysticKind.PANTS);
        add("David and Goliath", false, curve(2), MysticKind.PANTS);
        add("Not Gladiator", false, curve(1.6), MysticKind.PANTS);
        add("Hunt the Hunter", false, curve(1.5), MysticKind.PANTS);
        add("Counter-Offensive", false, curve(1.5), MysticKind.PANTS);
        add("Danger Close", false, curve(1.5), MysticKind.PANTS);
        add("Revitalize", false, curve(1.5), MysticKind.PANTS);
        add("Snowmen Army", true, curve(1.5), MysticKind.PANTS);
        add("Prick", false, curve(1.2), MysticKind.PANTS);
        add("Pit MBA", false, curve(2), MysticKind.PANTS);
        add("Respawn: Resistance", false, curve(1.2), MysticKind.PANTS);
        add("TNT", false, curve(1), MysticKind.PANTS);
        add("Steaks", false, curve(1), MysticKind.PANTS);
        add("Excess", false, curve(1), MysticKind.PANTS);
        add("Electrolytes", false, curve(1), MysticKind.PANTS);
        add("Self-checkout", false, curve(1), MysticKind.PANTS);
        add("Respawn: Absorption", false, curve(1), MysticKind.PANTS);
        add("Martyrdom", true, curve(1), MysticKind.PANTS);
        add("Lodbrok", false, curve(0.8), MysticKind.PANTS);
        add("Negotiator", false, curve(0.8), MysticKind.PANTS);
        add("Cricket", false, curve(0.8), MysticKind.PANTS);
        add("Billy", false, curve(0.8), MysticKind.PANTS);
        add("McSwimmer", false, curve(0.8), MysticKind.PANTS);
        add("Creative", false, curve(0.6), MysticKind.PANTS);
        add("Eggs", false, curve(0.6), MysticKind.PANTS);
        add("Purple Gold", false, curve(0.6), MysticKind.PANTS);
        add("Pebble", false, curve(0.5), MysticKind.PANTS);

        // Swords. Sharp, Knockback, Combo: XP and the per-life heal anchor are quoted at III.
        add("Combo: Perun's Wrath", true, T("q10.5"), T("q38"), T("q210"), MysticKind.SWORD);
        add("Executioner", true, T("e6.5"), T("e22"), T("e128"), MysticKind.SWORD);
        add("Gamble", true, T("e10"), T("e45"), T("q64"), MysticKind.SWORD);
        add("Billionaire", true, T("e8.5"), T("e17.5"), T("e45"), MysticKind.SWORD);
        add("Combo: Heal", false, T("e2"), T("e4"), T("q7"), MysticKind.SWORD);
        add("Sharp", false, T("e0.8"), T("e1.5"), T("q3"), MysticKind.SWORD);
        add("Knockback", true, T("e1"), T("e2"), T("q4"), MysticKind.SWORD);
        add("Combo: XP", false, T("e0.6"), T("e1.2"), T("q2.5"), MysticKind.SWORD);
        add("Combo: Stun", true, curve(3), MysticKind.SWORD);
        add("Lifesteal", false, curve(3), MysticKind.SWORD);
        add("Hemorrhage", true, curve(2.5), MysticKind.SWORD);
        add("Berserker", false, curve(2.5), MysticKind.SWORD);
        add("Combo: Damage", false, curve(2.5), MysticKind.SWORD);
        add("The Punch", true, curve(2), MysticKind.SWORD);
        add("Bullet Time", false, curve(2), MysticKind.SWORD);
        add("Pain Focus", false, curve(2), MysticKind.SWORD);
        add("Duelist", false, curve(2), MysticKind.SWORD);
        add("Speedy Hit", true, curve(1.5), MysticKind.SWORD);
        add("Bruiser", false, curve(1.5), MysticKind.SWORD);
        add("Crush", false, curve(1.5), MysticKind.SWORD);
        add("Shark", false, curve(1.5), MysticKind.SWORD);
        add("Pitpocket", false, curve(1.5), MysticKind.SWORD);
        add("Combo: Swift", false, curve(1.5), MysticKind.SWORD);
        add("Hidden Jewel", true, T("e4"), null, null, MysticKind.SWORD);
        add("King Buster", false, curve(1.2), MysticKind.SWORD);
        add("Punisher", false, curve(1.2), MysticKind.SWORD);
        add("Diamond Stomp", false, curve(1.2), MysticKind.SWORD);
        add("Bounty Reaper", false, curve(1.2), MysticKind.SWORD);
        add("Revengeance", false, curve(1.2), MysticKind.SWORD);
        add("Sierra", false, curve(1.2), MysticKind.SWORD);
        add("Beat the Spammers", false, curve(1), MysticKind.SWORD);
        add("Healer", true, curve(1), MysticKind.SWORD);
        add("Guts", false, curve(1), MysticKind.SWORD);
        add("Counter-Janitor", false, curve(1), MysticKind.SWORD);
        add("Grasshopper", false, curve(0.8), MysticKind.SWORD);
        add("Fancy Raider", false, curve(0.8), MysticKind.SWORD);
        add("Gold and Boosted", false, curve(0.8), MysticKind.SWORD);
        add("Speedy Kill", false, curve(0.8), MysticKind.SWORD);

        // Bows. No 2025–2026 print turned up for these, so the whole pool is estimated.
        add("Telebow", true, curve(6), MysticKind.BOW);
        add("Robinhood", true, curve(5), MysticKind.BOW);
        add("True Shot", true, curve(5), MysticKind.BOW);
        add("Mega Longbow", true, curve(4), MysticKind.BOW);
        add("Chipping", false, curve(3.5), MysticKind.BOW);
        add("Volley", true, curve(3), MysticKind.BOW);
        add("Lucky Shot", true, curve(3), MysticKind.BOW);
        add("Pullbow", true, curve(2.5), MysticKind.BOW);
        add("Parasite", false, curve(2), MysticKind.BOW);
        add("Devil Chicks!", true, curve(2), MysticKind.BOW);
        add("Explosive", true, curve(2), MysticKind.BOW);
        add("Arrow Armory", false, curve(2), MysticKind.BOW);
        add("Fletching", false, curve(1.5), MysticKind.BOW);
        add("Sprint Drain", false, curve(1.5), MysticKind.BOW);
        add("Wasp", false, curve(1.5), MysticKind.BOW);
        add("Bottomless Quiver", false, curve(1.5), MysticKind.BOW);
        add("Jumpspammer", false, curve(1.2), MysticKind.BOW);
        add("Sniper", false, curve(1.2), MysticKind.BOW);
        add("Pin down", false, curve(1.2), MysticKind.BOW);
        add("Spammer and Proud", false, curve(1.2), MysticKind.BOW);
        add("Faster than their shadow", false, curve(1.2), MysticKind.BOW);
        add("Mixed Combat", false, curve(1.2), MysticKind.BOW);
        add("Push comes to shove", false, curve(1.2), MysticKind.BOW);
        add("First Shot", false, curve(1), MysticKind.BOW);
        add("What doesn't kill you", false, curve(0.8), MysticKind.BOW);

        // Dark pants are single-level. Venom, Mind Assault and Misery are per-life wiki estimates.
        single("Somber", false, "e6", MysticKind.DARK);
        single("Heartripper", true, "e8", MysticKind.DARK);
        single("Combo: Venom", true, "ep6", MysticKind.DARK);
        single("Nostalgia", true, "e5", MysticKind.DARK);
        single("Grim Reaper", false, "e5", MysticKind.DARK);
        single("Lycanthropy", false, "e4", MysticKind.DARK);
        single("Misery", false, "ep0.5", MysticKind.DARK);
        single("Hedge Fund", false, "e3", MysticKind.DARK);
        single("Mind Assault", false, "ep0.7", MysticKind.DARK);
        single("Sanguisuge", false, "e3", MysticKind.DARK);
        single("Needless Suffering", false, "e2", MysticKind.DARK);
        single("Spite", false, "e2", MysticKind.DARK);
        single("Golden Handcuffs", true, "e2", MysticKind.DARK);

        add("Regularity", true, T("q2"), T("qp0.5"), T("qp1"), MysticKind.RAGE);
        add("New Deal", false, T("e0.5"), T("e1"), T("q2"), MysticKind.RAGE);
        add("Think of the People", true, curve(8), MysticKind.RAGE);
        add("Really Toxic", false, T("e0.3"), T("e0.6"), T("e1"), MysticKind.RAGE);
        add("Heigh-Ho", false, curve(1.5), MysticKind.RAGE);
        add("Do it like the French", false, curve(1.2), MysticKind.RAGE);
        add("Combo: Breaching Charge", false, curve(1.5), MysticKind.RAGE);
        add("Sybil", false, curve(1.2), MysticKind.RAGE);
        add("Brakes!", false, curve(1), MysticKind.RAGE);
        add("Ace of Spades", false, curve(0.6), MysticKind.RAGE);
        add("Deal with the Devil", true, curve(2), MysticKind.RAGE);

        BY_LENGTH.addAll(ALL);
        BY_LENGTH.sort((a, b) -> Integer.compare(b.name.length(), a.name.length()));
    }
}

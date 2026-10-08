package dev.goldenhead.utils;

import dev.goldenhead.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Pit event is running, from the server's chat lines and the event boss bar:
 * "MAJOR EVENT! SPIRE in 1 min" (countdown), "MINOR EVENT! DRAGON EGG starting now!" (start),
 * "PIT EVENT ENDED: SPIRE" / "DRAGON EGG OVER" (end). An event with no end line expires after its duration.
 * Formats come from open-source Pit mods (PitEvents, Pit-Bot, pit-grinder); log and compare in-game if one
 * doesn't trigger.
 */
public final class EventTracker {
    public enum PitEvent {
        // Longest names first where one contains another.
        EVERYONE_BOUNTY(false, 1, "EVERYONE GETS A BOUNTY"),
        KING_OF_THE_LADDER(false, 3, "KING OF THE LADDER", "KOTL"),
        KING_OF_THE_HILL(false, 4, "KING OF THE HILL", "KOTH"),
        TEAM_DEATHMATCH(true, 5, "TEAM DEATHMATCH", "TEAM DEATH MATCH", "TDM"),
        CARE_PACKAGE(false, 3, "CARE PACKAGE"),
        DRAGON_EGG(false, 4, "DRAGON EGG"),
        GIANT_CAKE(false, 2, "GIANT CAKE"),
        QUICK_MATHS(false, 1, "QUICK MATHS"),
        TWO_X_REWARDS(false, 4, "2X REWARDS", "DOUBLE REWARDS"),
        AUCTION(false, 1, "AUCTION"),
        RAGE_PIT(true, 4, "RAGE PIT"),
        BLOCKHEAD(true, 5, "BLOCKHEAD"),
        ROBBERY(true, 4, "ROBBERY"),
        SQUADS(true, 5, "SQUADS"),
        RAFFLE(true, 5, "RAFFLE"),
        PIZZA(true, 5, "PIZZA"),
        SPIRE(true, 5, "SPIRE"),
        BEAST(true, 5, "BEAST");

        public final boolean major;
        public final long durationMs;
        private final String[] names;

        PitEvent(boolean major, int minutes, String... names) {
            this.major = major;
            this.durationMs = minutes * 60_000L;
            this.names = names;
        }

        public static PitEvent match(String upper) {
            for (PitEvent e : values()) for (String n : e.names) if (upper.contains(n)) return e;
            return null;
        }
    }

    private static final Pattern COUNTDOWN = Pattern.compile("\\bIN (\\d+) ?M");

    private PitEvent minor, major, pending;
    private long minorUntil, majorUntil, pendingAt;

    public PitEvent current() {
        long now = System.currentTimeMillis();
        if (pending != null && now >= pendingAt) {
            start(pending);
            pending = null;
        }
        if (major != null && now > majorUntil) major = null;
        if (minor != null && now > minorUntil) minor = null;
        return major != null ? major : minor;
    }

    public PitEvent pending() {
        return pending;
    }

    /** True while this event is the running major or the running minor. A major does not hide a minor. */
    public boolean running(PitEvent event) {
        current();
        return event != null && (event == major || event == minor);
    }

    public long pendingInMs() {
        return pending == null ? Long.MAX_VALUE : pendingAt - System.currentTimeMillis();
    }

    public void reset() {
        minor = major = pending = null;
    }

    /** Feed every chat line (colors stripped). */
    public void onChat(String line) {
        String s = line.trim().toUpperCase(Locale.ROOT);

        if (s.startsWith("PIT EVENT ENDED")) {
            PitEvent e = PitEvent.match(s.substring("PIT EVENT ENDED".length()));
            end(e);
            return;
        }

        if (s.startsWith("MINOR EVENT!") || s.startsWith("MAJOR EVENT!")) {
            String rest = s.substring(12).trim();
            PitEvent e = PitEvent.match(rest);
            if (e == null) return;
            if (rest.contains("ENDED") || rest.contains(" OVER")) {
                end(e);
                return;
            }
            Matcher m = COUNTDOWN.matcher(rest);
            if (!rest.contains("STARTING NOW") && !rest.contains(" FOR ") && m.find()) {
                pending = e;
                pendingAt = System.currentTimeMillis() + Long.parseLong(m.group(1)) * 60_000L;
            } else start(e);
            return;
        }

        // "DRAGON EGG OVER", "DRAGON EGG! event over!", "QUICK MATHS OVER"
        if (!s.contains(":") && (s.endsWith(" OVER") || s.endsWith(" OVER!") || s.contains("EVENT OVER"))) {
            PitEvent e = PitEvent.match(s);
            if (e != null) end(e);
        }
    }

    /** Call every tick: an event name in the boss bar means it is running now. */
    public void tickBossBar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        for (ClientBossBar bar : ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).goldenhead$getBossBars().values()) {
            String name = PitUtils.clean(bar.getName()).toUpperCase(Locale.ROOT);
            PitEvent e = PitEvent.match(name);
            if (e == null || name.contains("STARTING") || COUNTDOWN.matcher(name).find() && !name.contains("ENDS")) continue;
            long until = System.currentTimeMillis() + 5_000;
            if (e.major) {
                major = e;
                majorUntil = Math.max(majorUntil, until);
            } else {
                minor = e;
                minorUntil = Math.max(minorUntil, until);
            }
        }
    }

    private void start(PitEvent e) {
        long until = System.currentTimeMillis() + e.durationMs + 20_000;
        if (e.major) {
            major = e;
            majorUntil = until;
            if (pending == e) pending = null;
        } else {
            minor = e;
            minorUntil = until;
        }
    }

    private void end(PitEvent e) {
        if (e == null || e == major) major = null;
        if (e == null || e == minor) minor = null;
        if (e != null && e == pending) pending = null;
    }
}

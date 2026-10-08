package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import dev.goldenhead.pittrade.MysticRead;
import dev.goldenhead.mixin.BossBarHudAccessor;
import dev.goldenhead.mixin.TextDisplayAccessor;
import dev.goldenhead.utils.BlockScan;
import dev.goldenhead.utils.PitUtils;
import dev.goldenhead.utils.Route;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.ContainerSlotUpdateEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Care Package event: finds the package chest (a chest that appears mid-game, or one under a "care package"
 * hologram) out to the loaded chunks, paths to it, and clicks it. Launch pads are part of that path.
 * Every right click counts toward the 200 clicks and the first one after that opens it. Only a mystic
 * sword, mystic bow, or pants are taken. The mystic is shift-clicked the moment it arrives. Nothing else
 * is clicked, and nothing is thrown.
 */
public class CarePackage extends ClickTarget {
    private static final Set<Block> CHESTS = Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST);

    private final SettingGroup sgLoot = settings.createGroup("Loot");

    private final Setting<String> hologram = sgLoot.add(new StringSetting.Builder()
        .name("hologram-keyword")
        .description("A chest under a hologram containing this counts as the package, even if it was there before. Empty = off.")
        .defaultValue("care package")
        .build()
    );

    private final Setting<String> titleKeyword = sgLoot.add(new StringSetting.Builder()
        .name("title-keyword")
        .description("Also loot any chest screen whose title contains this. Empty = only the chest you were just clicking.")
        .defaultValue("care package")
        .build()
    );

    private final Setting<Boolean> autoClose = sgLoot.add(new BoolSetting.Builder()
        .name("auto-close")
        .description("Close the chest once the mystic sword, bow, or pants are taken, or the chest has none.")
        .defaultValue(true)
        .build()
    );

    /** Loaded chunks cover a Pit quadrant from spawn. The range setting still wins if it is higher. */
    private static final double FAR_SCAN = 160;
    /**
     * A package that landed before this module was enabled has no block-update to follow.
     * Keep adopting the nearest open chest this close until one is locked.
     */
    private static final double LATE_ADOPT_RANGE = 32;
    /** How long after enable to keep searching, even if the boss bar was missed. */
    private static final long SEEK_MS = 20_000L;
    /** Chest removed. Wait this many ticks in case it is still falling into place, then turn off. */
    private static final int GONE_TICKS = 20;

    private final Set<BlockPos> fresh = new HashSet<>();
    /** Chests placed while the module was on. Those win over chests that were already in the world. */
    private final Set<BlockPos> placed = new HashSet<>();
    private final Set<BlockPos> done = new HashSet<>();
    /** Last package spot per area name, so the next one in that area is walked toward before its chunk loads. */
    private final Map<String, BlockPos> learned = new HashMap<>();

    private BlockPos lastClicked;
    private long lastClickTime;
    private int lootSyncId = -1;
    private int finishedSync = -1;
    private long lastGrabTick = Long.MIN_VALUE;
    private long lastGrabMs;
    /** World tick the chest window was accepted. The open-tick shift-click is the one ViaVersion drops. */
    private long openedTick = Long.MIN_VALUE;
    private long contentsAt;
    /** Last shift-click has not been answered by a server slot packet yet. */
    private boolean awaitServer;
    private boolean seenContents;
    private BlockPos lootChest;
    private long openedAt;
    private boolean sawKeeper;
    private String area;
    private long areaUntil;
    private long seekUntil;
    private boolean lockedOn;
    private int absent;
    /** Announcement seen. Until the drop is in view, do not grab an older chest that happens to be nearby. */
    private boolean fromAnnouncement;
    private long announcedAt;
    /** Turn on next tick. Toggling from inside the chat event would subscribe mid-dispatch. */
    private boolean arm;
    /** Horizontal spot the chickens or the falling chest were last seen. The landing is on this column. */
    private Double dropX;
    private Double dropZ;
    private long fallTick = Long.MIN_VALUE;
    private Vec3d fallCached;
    private final Route.Follower router = new Route.Follower();
    private final DropWatch dropWatch = new DropWatch();

    public CarePackage() {
        super(MeteorPitAddon.PIT, "care-package", "Runs to the care package, opens it, and takes the mystic sword, bow, or pants.");
        // Stays subscribed while the module is off, so the drop can turn it on as the chest is still falling.
        MeteorClient.EVENT_BUS.subscribe(dropWatch);
    }

    @Override
    protected void follow(BlockPos goal, int range, Vec3d look) {
        router.follow(mover, goal, range, look);
    }

    @Override
    protected boolean canClick() {
        return super.canClick()
            && targetBlock != null
            && mc.crosshairTarget instanceof BlockHitResult hit
            && hit.getBlockPos().equals(targetBlock);
    }

    @Override
    protected boolean clickWhileUnfocused() {
        return true;
    }

    @Override
    public void onActivate() {
        super.onActivate();
        router.clear();
        fresh.clear();
        placed.clear();
        lockedOn = false;
        absent = 0;
        seekUntil = System.currentTimeMillis() + SEEK_MS;
        lastClicked = null;
        lastClickTime = 0;
        // The chest is already in the world when this is turned on after the drop. Block updates
        // only see placements from now on, and the hologram may already be gone.
        adoptLanded();
        resetLoot();
    }

    @Override
    public void onDeactivate() {
        router.clear();
        super.onDeactivate();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        router.clear();
        fresh.clear();
        placed.clear();
        done.clear();
        lockedOn = false;
        absent = 0;
        dropX = null;
        dropZ = null;
        fromAnnouncement = false;
        announcedAt = 0;
        arm = false;
        fallTick = Long.MIN_VALUE;
        area = null;
        resetLoot();
    }

    // "MINOR EVENT! CARE PACKAGE in Temple Area" — remember the last package spot for that area.
    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        noteArea(Beast.clean(event.getMessage()).toUpperCase(Locale.ROOT));
    }

    private void noteArea(String line) {
        int at = line.indexOf("CARE PACKAGE IN ");
        if (at < 0) return;
        String name = line.substring(at + "CARE PACKAGE IN ".length()).replaceAll("[^A-Z ]", "").trim();
        if (name.isEmpty()) return;
        area = name;
        areaUntil = System.currentTimeMillis() + 3 * 60_000L + 20_000L;
        fromAnnouncement = true;
        announcedAt = System.currentTimeMillis();
    }

    @EventHandler
    private void onRemember(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        Vec3d fall = fallingLanding();
        adoptLanded();
        if (area != null && targetBlock != null) learned.put(area, targetBlock.toImmutable());
        // The chest block blinks while it is lowered. That is still the package, not it leaving.
        if (fall != null && !landedAtDrop()) {
            absent = 0;
            return;
        }
        if (packageHere()) {
            lockedOn = true;
            absent = 0;
            return;
        }
        // A falling package removes its previous block for a moment before the next one exists.
        if (!lockedOn || ++absent < GONE_TICKS) return;
        lockedOn = false;
        area = null;
        arm = false;
        fromAnnouncement = false;
        fresh.clear();
        placed.clear();
        if (isActive()) toggle();
    }

    /** Chest placed while we were off, or still settling. Hologram first, then the nearest open chest nearby. */
    private void adoptLanded() {
        BlockPos marked = chestUnderHologram();
        if (marked != null) {
            fresh.add(marked);
            return;
        }
        BlockPos dropped = chestNearDrop();
        if (dropped != null) {
            fresh.add(dropped);
            return;
        }
        // A chest that landed while watching is the package. Don't also grab an older map chest.
        if (!placed.isEmpty() || lockedOn || !seeking()) return;
        // The announcement means the chest is still in the air somewhere else. A nearby open chest is the map.
        if (fromAnnouncement && dropX == null && System.currentTimeMillis() < announcedAt + 90_000L) return;
        BlockPos nearest = nearestOpenChest();
        if (nearest != null) fresh.add(nearest);
    }

    private boolean seeking() {
        if (System.currentTimeMillis() < seekUntil) return true;
        return eventLive();
    }

    private boolean eventLive() {
        if (area != null && System.currentTimeMillis() <= areaUntil) return true;
        if (mc.inGameHud == null) return false;
        for (ClientBossBar bar : ((BossBarHudAccessor) mc.inGameHud.getBossBarHud()).goldenhead$getBossBars().values()) {
            if (Beast.clean(bar.getName()).contains("care package")) return true;
        }
        return false;
    }

    private boolean packageHere() {
        if (targetBlock != null && mc.world.getBlockState(targetBlock).getBlock() instanceof ChestBlock && isTargetBlock(targetBlock)) return true;
        for (BlockPos pos : fresh) {
            if (grounded(pos) && mc.world.getBlockState(pos).getBlock() instanceof ChestBlock) return true;
        }
        return false;
    }

    private BlockPos nearestOpenChest() {
        BlockPos best = null;
        double bestDistance = LATE_ADOPT_RANGE * LATE_ADOPT_RANGE;
        Vec3d eye = mc.player.getEyePos();
        for (BlockPos pos : BlockScan.find(CHESTS, LATE_ADOPT_RANGE)) {
            if (done.contains(pos) || !openAbove(pos)) continue;
            if (Math.abs(pos.getY() - mc.player.getBlockY()) > 8) continue;
            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    /** Sky, leaves, or grass above. A sewer chest has a solid ceiling and is left alone. */
    private boolean openAbove(BlockPos pos) {
        BlockPos above = pos.up();
        return mc.world.getBlockState(above).getCollisionShape(mc.world, above).isEmpty();
    }

    @Override
    protected double scanRange() {
        return Math.max(super.scanRange(), FAR_SCAN);
    }

    @Override
    protected Vec3d approachPoint() {
        Vec3d fall = fallingLanding();
        if (fall != null) return fall;
        Vec3d live = hologramPos();
        if (live != null) return live;
        if (area == null || System.currentTimeMillis() > areaUntil) return null;
        BlockPos known = learned.get(area);
        return known == null ? null : Vec3d.ofCenter(known);
    }

    /**
     * Where the package will land. The chest comes down on chickens with leads, so it is visible
     * long before a chest block exists. Cached for the world tick.
     */
    private Vec3d fallingLanding() {
        if (mc.world == null || mc.player == null) return null;
        long now = mc.world.getTime();
        if (now == fallTick) return fallCached;
        fallTick = now;
        fallCached = findFalling();
        return fallCached;
    }

    private Vec3d findFalling() {
        double rangeSq = scanRange() * scanRange();
        List<ChickenEntity> flock = new ArrayList<>();
        Vec3d chest = null;
        double chestD = rangeSq;
        for (Entity entity : mc.world.getEntities()) {
            if (mc.player.squaredDistanceTo(entity) > rangeSq) continue;
            if (entity instanceof ChickenEntity chicken && airborne(chicken)) flock.add(chicken);
            Vec3d fallingChest = fallingChest(entity);
            if (fallingChest == null) continue;
            double distance = mc.player.squaredDistanceTo(entity);
            if (distance < chestD) {
                chestD = distance;
                chest = fallingChest;
            }
        }
        Vec3d spot = chest != null ? chest : flockSpot(flock);
        if (spot == null) spot = floatingChest();
        if (spot == null) return null;
        dropX = spot.x;
        dropZ = spot.z;
        return landingUnder(spot);
    }

    /** Two or more chickens in the air, close together. That is the package coming down, not a mob. */
    private Vec3d flockSpot(List<ChickenEntity> flock) {
        Vec3d best = null;
        int bestCount = 1;
        for (ChickenEntity chicken : flock) {
            double x = 0;
            double y = 0;
            double z = 0;
            int count = 0;
            for (ChickenEntity other : flock) {
                if (chicken.squaredDistanceTo(other) > 64) continue;
                x += other.getX();
                y += other.getY();
                z += other.getZ();
                count++;
            }
            if (count <= bestCount) continue;
            bestCount = count;
            best = new Vec3d(x / count, y / count, z / count);
        }
        return best;
    }

    private boolean airborne(ChickenEntity chicken) {
        if (chicken.isOnGround()) return false;
        BlockPos below = chicken.getBlockPos().down();
        return mc.world.getBlockState(below).getCollisionShape(mc.world, below).isEmpty();
    }

    private Vec3d fallingChest(Entity entity) {
        if (entity instanceof FallingBlockEntity falling && falling.getBlockState().getBlock() instanceof ChestBlock) {
            return falling.getEntityPos();
        }
        if (entity instanceof DisplayEntity.BlockDisplayEntity display && display.getData().blockState().getBlock() instanceof ChestBlock) {
            return display.getEntityPos();
        }
        return null;
    }

    /** A chest block with nothing under it is still being lowered. */
    private Vec3d floatingChest() {
        BlockPos best = null;
        double bestDistance = scanRange() * scanRange();
        Vec3d eye = mc.player.getEyePos();
        for (BlockPos pos : BlockScan.find(CHESTS, scanRange())) {
            if (done.contains(pos) || grounded(pos)) continue;
            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            best = pos;
        }
        return best == null ? null : Vec3d.ofCenter(best);
    }

    private Vec3d landingUnder(Vec3d spot) {
        int x = (int) Math.floor(spot.x);
        int z = (int) Math.floor(spot.z);
        int y = (int) Math.floor(spot.y);
        if (!mc.world.isChunkLoaded(x >> 4, z >> 4)) return new Vec3d(x + 0.5, mc.player.getY(), z + 0.5);
        int bottom = Math.max(mc.world.getBottomY(), y - 80);
        for (int at = y; at >= bottom; at--) {
            BlockPos below = new BlockPos(x, at - 1, z);
            BlockState state = mc.world.getBlockState(below);
            if (state.getCollisionShape(mc.world, below).isEmpty()) continue;
            if (state.isOf(Blocks.LAVA) || state.getFluidState().isIn(FluidTags.LAVA)) continue;
            return new Vec3d(x + 0.5, at, z + 0.5);
        }
        return new Vec3d(x + 0.5, mc.player.getY(), z + 0.5);
    }

    private boolean grounded(BlockPos pos) {
        BlockPos below = pos.down();
        BlockState state = mc.world.getBlockState(below);
        return !state.getCollisionShape(mc.world, below).isEmpty()
            && !state.isOf(Blocks.LAVA)
            && !state.getFluidState().isIn(FluidTags.LAVA);
    }

    private boolean landedAtDrop() {
        return chestNearDrop() != null;
    }

    private BlockPos chestNearDrop() {
        if (dropX == null || dropZ == null || mc.world == null) return null;
        BlockPos best = null;
        double bestDistance = 36;
        for (BlockPos pos : BlockScan.find(CHESTS, scanRange())) {
            if (done.contains(pos) || !grounded(pos)) continue;
            double dx = pos.getX() + 0.5 - dropX;
            double dz = pos.getZ() + 0.5 - dropZ;
            double distance = dx * dx + dz * dz;
            if (distance > bestDistance) continue;
            bestDistance = distance;
            best = pos.toImmutable();
        }
        return best;
    }

    /** Hears the announcement and sees the chickens while this module is still off. */
    private final class DropWatch {
        @EventHandler
        private void onMessage(ReceiveMessageEvent event) {
            String line = Beast.clean(event.getMessage()).toUpperCase(Locale.ROOT);
            if (!line.contains("CARE PACKAGE IN ")) return;
            noteArea(line);
            if (!isActive() && mc.world != null && PitUtils.sidebarContains("pit")) arm = true;
        }

        @EventHandler
        private void onTick(TickEvent.Pre event) {
            if (isActive() || mc.player == null || mc.world == null) return;
            if (!PitUtils.sidebarContains("pit")) return;
            if (!arm && fallingLanding() == null) return;
            arm = false;
            toggle();
        }
    }

    private Vec3d hologramPos() {
        if (mc.world == null || mc.player == null) return null;

        double best = scanRange() * scanRange();
        Vec3d found = null;
        for (Entity entity : mc.world.getEntities()) {
            if (!namedPackage(entity)) continue;
            double distance = mc.player.squaredDistanceTo(entity);
            if (distance < best) {
                best = distance;
                found = entity.getEntityPos();
            }
        }
        return found;
    }

    /**
     * Marker armor stands and text displays often have an empty hitbox, so a box search misses them.
     * The visible name can be the custom name or the team prefix.
     */
    private boolean namedPackage(Entity entity) {
        String key = hologram.get().toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty() || entity instanceof PlayerEntity) return false;
        Text label = entity instanceof DisplayEntity.TextDisplayEntity display ? ((TextDisplayAccessor) display).goldenhead$getText() : entity.getCustomName();
        if (label == null || Beast.clean(label).isBlank()) label = entity.getDisplayName();
        return label != null && Beast.clean(label).contains(key);
    }

    /** Closest chest sitting under a care-package hologram, out to the scan range. */
    private BlockPos chestUnderHologram() {
        if (mc.world == null || mc.player == null) return null;
        BlockPos best = null;
        double bestDistance = scanRange() * scanRange();
        for (Entity entity : mc.world.getEntities()) {
            if (!namedPackage(entity)) continue;
            double distance = mc.player.squaredDistanceTo(entity);
            if (distance >= bestDistance) continue;
            BlockPos under = chestBelow(entity);
            if (under == null) continue;
            bestDistance = distance;
            best = under;
        }
        return best;
    }

    private BlockPos chestBelow(Entity entity) {
        BlockPos origin = BlockPos.ofFloored(entity.getX(), entity.getY(), entity.getZ());
        for (int dy = 0; dy >= -6; dy--) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = origin.add(dx, dy, dz);
                if (done.contains(pos)) continue;
                if (mc.world.getBlockState(pos).getBlock() instanceof ChestBlock) return pos.toImmutable();
            }
        }
        return null;
    }

    private void resetLoot() {
        lootSyncId = -1;
        finishedSync = -1;
        sawKeeper = false;
        seenContents = false;
        awaitServer = false;
        lastGrabTick = Long.MIN_VALUE;
        lastGrabMs = 0;
        openedTick = Long.MIN_VALUE;
        contentsAt = 0;
    }

    // A chest placed while we watch (the package landing) rather than one loaded with the map.
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        boolean isChest = event.newState.getBlock() instanceof ChestBlock;
        boolean wasChest = event.oldState != null && event.oldState.getBlock() instanceof ChestBlock;
        if (isChest && !wasChest) {
            BlockPos pos = event.pos.toImmutable();
            fresh.add(pos);
            placed.add(pos);
        } else if (!isChest) {
            fresh.remove(event.pos);
            placed.remove(event.pos);
            done.remove(event.pos);
        }
    }

    @Override
    protected Set<Block> candidateBlocks() {
        return CHESTS;
    }

    @Override
    protected boolean isTargetBlock(BlockPos pos) {
        return !done.contains(pos) && grounded(pos) && (fresh.contains(pos) || underHologram(pos) || nearDrop(pos));
    }

    private boolean underHologram(BlockPos pos) {
        if (mc.world == null) return false;
        for (Entity entity : mc.world.getEntities()) {
            if (!namedPackage(entity)) continue;
            double dx = entity.getX() - (pos.getX() + 0.5);
            double dz = entity.getZ() - (pos.getZ() + 0.5);
            double dy = entity.getY() - pos.getY();
            if (dx * dx + dz * dz <= 2.25 && dy >= -0.5 && dy <= 6) return true;
        }
        return false;
    }

    private boolean nearDrop(BlockPos pos) {
        if (dropX == null || dropZ == null) return false;
        double dx = pos.getX() + 0.5 - dropX;
        double dz = pos.getZ() + 0.5 - dropZ;
        return dx * dx + dz * dz <= 36;
    }

    @Override
    protected void onClicked() {
        if (targetBlock != null) lastClicked = targetBlock;
        lastClickTime = System.currentTimeMillis();
    }

    // Slot packets are already applied. A tick is only a retry; it must not decide the item is gone.
    @EventHandler
    private void onInventory(InventoryEvent event) {
        loot(true);
    }

    @EventHandler
    private void onSlot(ContainerSlotUpdateEvent event) {
        loot(true);
    }

    @EventHandler
    private void onTickLoot(TickEvent.Post event) {
        loot(false);
    }

    private void loot(boolean fromServer) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return;
        if (handler.syncId == finishedSync) return;

        long now = System.currentTimeMillis();
        long tick = mc.world == null ? Long.MIN_VALUE : mc.world.getTime();
        if (handler.syncId != lootSyncId) {
            if (!isPackageScreen()) return;
            lootSyncId = handler.syncId;
            lootChest = lastClicked;
            openedAt = now;
            openedTick = tick;
            sawKeeper = false;
            seenContents = false;
            awaitServer = false;
            contentsAt = 0;
            lastGrabTick = Long.MIN_VALUE;
        }

        if (fromServer) awaitServer = false;

        int containerSize = handler.getRows() * 9;
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        boolean stillThere = false;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (fromServer && stack != null && !stack.isEmpty() && !seenContents) {
                seenContents = true;
                contentsAt = now;
            }
            int rank = keeperRank(stack);
            if (rank == Integer.MAX_VALUE) continue;
            stillThere = true;
            if (rank < bestRank) {
                bestRank = rank;
                best = i;
            }
        }

        // clickSlot shift-clicks locally before the packet leaves. ViaVersion cancelled that packet at
        // Garden, the local slot was already empty, and the next tick turned the module off.
        boolean due = !awaitServer || now - lastGrabMs >= 150;
        if (best >= 0 && due && tick != lastGrabTick && tick != openedTick) {
            ItemStack[] before = new ItemStack[handler.slots.size()];
            for (int i = 0; i < before.length; i++) before[i] = handler.getSlot(i).getStack().copy();
            ItemStack cursor = handler.getCursorStack().copy();
            mc.interactionManager.clickSlot(handler.syncId, best, 0, SlotActionType.QUICK_MOVE, mc.player);
            for (int i = 0; i < before.length; i++) handler.getSlot(i).setStackNoCallbacks(before[i]);
            handler.setCursorStack(cursor);
            sawKeeper = true;
            awaitServer = true;
            lastGrabMs = now;
            lastGrabTick = tick;
        }

        if (fromServer && !stillThere && (sawKeeper || (seenContents && now - contentsAt > 800))) finish();
    }

    /** Leather pants, a golden sword, or a bow, including a mystic whose name says so. */
    private static int keeperRank(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Integer.MAX_VALUE;
        if (stack.isOf(Items.LEATHER_LEGGINGS)) return 0;
        if (stack.isOf(Items.GOLDEN_SWORD)) return 1;
        if (stack.isOf(Items.BOW)) return 2;
        MysticRead.Seen seen = MysticRead.parse(stack);
        if (seen == null) return Integer.MAX_VALUE;
        return switch (seen.kind) {
            case PANTS, DARK, RAGE, SEWER, AQUA -> 0;
            case SWORD -> 1;
            case BOW -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    private void finish() {
        if (lootChest != null) done.add(lootChest);
        finishedSync = lootSyncId;
        if (autoClose.get() && mc.player.currentScreenHandler.syncId == lootSyncId) mc.player.closeHandledScreen();
        area = null;
        fromAnnouncement = false;
        dropX = null;
        dropZ = null;
        sawKeeper = false;
        lootSyncId = -1;
        if (isActive()) toggle();
    }

    // Once this activation has clicked the package, the chest window is the package even if it
    // arrives late. The title is "Chest", and the old 1.5s window had already expired at Palace.
    private boolean isPackageScreen() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) return lastClicked != null;
        String title = Beast.clean(screen.getTitle());
        String key = titleKeyword.get().toLowerCase(Locale.ROOT).trim();
        if (!key.isEmpty() && title.contains(key)) return true;
        if (!title.isBlank() && !title.equals("chest") && !title.equals("large chest")) return false;
        return lastClicked != null || chestBeside();
    }

    private boolean chestBeside() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        for (int dx = -4; dx <= 4; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos pos = feet.add(dx, dy, dz);
            if (done.contains(pos)) continue;
            if (!(mc.world.getBlockState(pos).getBlock() instanceof ChestBlock)) continue;
            if (feet.getSquaredDistance(pos) <= 25) return true;
        }
        return false;
    }

    @Override
    public String getInfoString() {
        if (lootSyncId != -1) return "looting";
        String distance = super.getInfoString();
        if (router.usingPad()) return distance == null ? "pad" : distance + " pad";
        if (distance != null) return distance;
        if (targetBlock == null && fallingLanding() != null) return "falling";
        return approachPoint() != null ? "targeting" : null;
    }
}

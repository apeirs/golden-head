package dev.goldenhead.modules;

import dev.goldenhead.MeteorPitAddon;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Set;

/**
 * Dragon Egg event: locks onto the closest dragon egg / dragon head (block, or an armor stand wearing one),
 * gets to it and spam-clicks it, so a click lands right as the server's per-player cooldown ends.
 */
public class DragonEgg extends ClickTarget {
    private static final Set<Block> TARGET_BLOCKS = Set.of(Blocks.DRAGON_EGG, Blocks.DRAGON_HEAD, Blocks.DRAGON_WALL_HEAD);

    public DragonEgg() {
        super(MeteorPitAddon.PIT, "dragon-egg", "Locks onto the closest dragon egg/head, runs to it and spam-clicks it (random 20-100 ms).");
    }

    @Override
    protected Set<Block> candidateBlocks() {
        return TARGET_BLOCKS;
    }

    @Override
    protected boolean leaveSpawnFirst() {
        return true;
    }

    @Override
    protected boolean shouldLeaveSpawn() {
        return targetBlock != null || targetEntity != null;
    }

    @Override
    protected boolean sprintJump() {
        return true;
    }

    @Override
    protected void moveOutOfSpawn() {
        mover.enterPit();
    }

    @Override
    protected boolean clickWhileUnfocused() {
        return true;
    }

    @Override
    protected boolean isTargetEntity(Entity entity) {
        if (!(entity instanceof ArmorStandEntity stand)) return false;
        ItemStack head = stand.getEquippedStack(EquipmentSlot.HEAD);
        return head.isOf(Items.DRAGON_HEAD) || head.isOf(Items.DRAGON_EGG);
    }
}

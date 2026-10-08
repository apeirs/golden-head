package dev.goldenhead.mixin;

import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.player.NoInteract;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// No Interact means "skip friends", not "skip everyone outside KOS". Keep that check on isFriend.
@Mixin(value = NoInteract.class, remap = false)
public class NoInteractMixin {
    @Redirect(
        method = {"shouldAttackEntity", "shouldInteractEntity"},
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/systems/friends/Friends;shouldAttack(Lnet/minecraft/class_1657;)Z"
        )
    )
    private boolean goldenhead$friendsOnly(Friends friends, PlayerEntity player) {
        return !friends.isFriend(player);
    }
}

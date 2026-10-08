package dev.goldenhead.mixin;

import dev.goldenhead.kos.Kos;
import dev.goldenhead.modules.Streak;
import meteordevelopment.meteorclient.systems.friends.Friends;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A non-empty KOS list is the only set of players attack modules will pick. Friends still return false.
@Mixin(value = Friends.class, remap = false)
public class FriendsMixin {
    @Inject(method = "shouldAttack", at = @At("RETURN"), cancellable = true)
    private void goldenhead$kos(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && (!Kos.allows(player) || !Streak.allowTarget(player))) cir.setReturnValue(false);
    }
}

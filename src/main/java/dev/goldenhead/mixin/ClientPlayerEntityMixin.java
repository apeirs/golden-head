package dev.goldenhead.mixin;

import dev.goldenhead.modules.WwSprint;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reasserts the sprint state after vanilla movement checks, before movement is sent to the server. */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "tickMovement", at = @At("TAIL"))
    private void goldenhead$keepSprint(CallbackInfo ci) {
        if (!WwSprint.shouldForceSprint()) return;
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (!player.isSprinting()) player.setSprinting(true);
    }
}

package dev.goldenhead.mixin;

import dev.goldenhead.modules.WwSprint;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the sprint request to this tick's sampled input. The actual sprint state still follows
 * Minecraft's normal eligibility checks and gets sent through its normal movement packets.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void goldenhead$wwSprint(CallbackInfo ci) {
        if (!WwSprint.shouldForceSprint()) return;
        boolean left = playerInput.left();
        boolean right = playerInput.right();
        playerInput = new PlayerInput(playerInput.forward(), playerInput.backward(), left, right,
            playerInput.jump(), playerInput.sneak(), true);
    }
}

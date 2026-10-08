package dev.goldenhead.mixin;

import dev.goldenhead.modules.FriendAlert;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla name plates, used when Meteor's Nametags module is not drawing the player.
 * Meteor clears the label when its own nametags are on, and that null is left alone.
 */
@Mixin(EntityRenderer.class)
public abstract class FriendNametagMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void goldenhead$friendName(Entity entity, CallbackInfoReturnable<Text> cir) {
        Text name = cir.getReturnValue();
        if (name == null || !(entity instanceof PlayerEntity player)) return;
        SettingColor color = FriendAlert.nametagColorFor(player);
        if (color == null) return;
        int rgb = (color.r << 16) | (color.g << 8) | color.b;
        cir.setReturnValue(name.copy().styled(style -> style.withColor(rgb)));
    }
}

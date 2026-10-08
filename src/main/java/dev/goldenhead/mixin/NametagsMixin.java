package dev.goldenhead.mixin;

import dev.goldenhead.modules.Beast;
import dev.goldenhead.modules.FriendAlert;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.render.Nametags;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Friends get a green name and a green plate. Beasts keep their color when the player is not a friend.
// nameColor is the first Color local in renderNametagPlayer. Meteor is intermediary at runtime.
@Mixin(value = Nametags.class, remap = false)
public abstract class NametagsMixin {
    @Unique
    private PlayerEntity goldenhead$player;

    @Unique
    private final Color goldenhead$edge = new Color();

    @Unique
    private final Color goldenhead$plate = new Color();

    @Inject(method = "renderNametagPlayer", at = @At("HEAD"))
    private void goldenhead$capturePlayer(Render2DEvent event, PlayerEntity player, boolean shadow, CallbackInfo ci) {
        goldenhead$player = player;
    }

    @Inject(method = "renderNametagPlayer", at = @At("RETURN"))
    private void goldenhead$clearPlayer(Render2DEvent event, PlayerEntity player, boolean shadow, CallbackInfo ci) {
        goldenhead$player = null;
    }

    // Ignore-friends would skip the tag entirely. A friend we are coloring still gets one.
    @Redirect(
        method = "onTick",
        at = @At(
            value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/systems/friends/Friends;isFriend(Lnet/minecraft/class_1657;)Z"
        )
    )
    private boolean goldenhead$keepFriendTags(Friends friends, PlayerEntity player) {
        if (FriendAlert.nametagColorFor(player) != null) return false;
        return friends.isFriend(player);
    }

    @ModifyVariable(method = "renderNametagPlayer", at = @At("STORE"), ordinal = 0)
    private Color goldenhead$nameColor(Color color) {
        if (goldenhead$player == null) return color;
        SettingColor friend = FriendAlert.nametagColorFor(goldenhead$player);
        if (friend != null) return friend;
        SettingColor beast = Beast.nametagColorFor(goldenhead$player);
        return beast != null ? beast : color;
    }

    // Full-green edge, then a light green fill. The name stays pure green, so the tag reads
    // louder than a beast's red name on the normal black plate.
    @Inject(method = "drawBg", at = @At("HEAD"), cancellable = true)
    private void goldenhead$friendPlate(double x, double y, double width, double height, CallbackInfo ci) {
        SettingColor friend = FriendAlert.nametagColorFor(goldenhead$player);
        if (friend == null) return;

        goldenhead$edge.set(friend.r, friend.g, friend.b, 230);
        goldenhead$plate.set(friend.r / 12, 42 + friend.g / 10, friend.b / 12, 145);

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(x - 2.5, y - 2.5, width + 5, height + 5, goldenhead$edge);
        Renderer2D.COLOR.quad(x - 1, y - 1, width + 2, height + 2, goldenhead$plate);
        Renderer2D.COLOR.render();
        ci.cancel();
    }
}

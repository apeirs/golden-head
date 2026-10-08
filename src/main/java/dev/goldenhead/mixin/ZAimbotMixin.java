package dev.goldenhead.mixin;

import dev.goldenhead.kos.Kos;
import dev.goldenhead.modules.Beast;
import dev.goldenhead.modules.Streak;
import dev.goldenhead.modules.TeamDeathmatch;
import dev.goldenhead.utils.AimLock;
import dev.goldenhead.utils.SpawnArea;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

// @Pseudo: Meteorist is optional; without it this mixin is simply never applied.
@Pseudo
@Mixin(targets = "zgoly.meteorist.modules.zaimbot.ZAimbot", remap = false)
public abstract class ZAimbotMixin {
    @Shadow
    @Final
    private Setting<SortPriority> priority;

    @Shadow
    private boolean entityCheck(Entity entity) {
        throw new AssertionError();
    }

    @Shadow
    private void aim(LivingEntity player, Entity target) {
        throw new AssertionError();
    }

    // Never aim at players in spawn (they can't be hit), TDM teammates, or anyone outside a non-empty KOS list.
    @Inject(method = "entityCheck", at = @At("RETURN"), cancellable = true)
    private void goldenhead$teamFilter(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (SpawnArea.isInSpawn(entity) || !TeamDeathmatch.allowTarget(entity) || !Streak.allowTarget(entity)) cir.setReturnValue(false);
        else if (entity instanceof PlayerEntity player && !Kos.allows(player)) cir.setReturnValue(false);
    }

    // Off while we stand in spawn; during Beast, aim at a valid beast first, otherwise ZAimbot picks as usual.
    @Inject(method = "onRender3D", at = @At("HEAD"), cancellable = true)
    private void goldenhead$beastFirst(Render3DEvent event, CallbackInfo ci) {
        // Drop last frame's lock. aim() puts it back only when ZAimbot really has someone.
        AimLock.clear();
        if (mc.player == null || mc.world == null) return;
        // Streak is walking at one player and must not be turned toward someone else.
        if (Streak.ownsMovement()) {
            ci.cancel();
            return;
        }
        if (SpawnArea.isInSpawn(mc.player)) {
            ci.cancel();
            return;
        }
        if (!Beast.shouldPrioritize()) return;

        Entity beast = TargetUtils.get(entity -> Beast.isBeast(entity) && entityCheck(entity), priority.get());
        if (beast == null) return;

        aim(mc.player, beast);
        ci.cancel();
    }

    @Inject(method = "aim", at = @At("HEAD"))
    private void goldenhead$rememberLock(LivingEntity player, Entity target, CallbackInfo ci) {
        AimLock.set(target);
    }
}

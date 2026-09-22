package dev.holo795.crashsleuth.fixture.mixin;

import dev.holo795.crashsleuth.fixture.FixtureMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throws while an entity is ticked ("entity-tick" mode): the crash report then names that entity.
 * Versions Fabric has mappings for run under the class_ names; the newest ones ship their real names,
 * so both are named here and the one that is missing is simply skipped.
 */
@Mixin(targets = {"net.minecraft.class_1297", "net.minecraft.world.entity.Entity"})
public class EntityTickMixin {
    @Inject(method = {"method_5773()V", "tick()V"}, at = @At("HEAD"), require = 0)
    private void crashSleuthFixture(CallbackInfo info) {
        if (FixtureMod.mode().equals("entity-tick")) {
            throw new IllegalStateException("Shop hologram lost its anchor");
        }
    }
}

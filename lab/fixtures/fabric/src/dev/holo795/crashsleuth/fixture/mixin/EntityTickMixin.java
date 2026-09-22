package dev.holo795.crashsleuth.fixture.mixin;

import dev.holo795.crashsleuth.fixture.FixtureMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Throws while an entity is ticked ("entity-tick" mode): the crash report then names that entity. */
@Mixin(targets = "net.minecraft.class_1297")
public class EntityTickMixin {
    @Inject(method = "method_5773()V", at = @At("HEAD"))
    private void crashSleuthFixture(CallbackInfo info) {
        if (FixtureMod.mode().equals("entity-tick")) {
            throw new IllegalStateException("Shop hologram lost its anchor");
        }
    }
}

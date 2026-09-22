package dev.holo795.crashsleuth.fixture.mixin;

import dev.holo795.crashsleuth.fixture.FixtureMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The other way blocks reach the screen ("block-render" mode), so the crash does not depend on timing. */
@Mixin(targets = "net.minecraft.class_778")
public class BlockModelMixin {
    @Inject(method = "method_3374", at = @At("HEAD"), require = 0)
    private void crashSleuthFixture(CallbackInfo info) {
        if (FixtureMod.mode().equals("block-render")) {
            throw new IllegalStateException("Shop sign model is not loaded");
        }
    }
}

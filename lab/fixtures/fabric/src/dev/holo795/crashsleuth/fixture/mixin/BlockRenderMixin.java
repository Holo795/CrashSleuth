package dev.holo795.crashsleuth.fixture.mixin;

import dev.holo795.crashsleuth.fixture.FixtureMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Throws while a block is being put into the world's geometry ("block-render" mode). */
@Mixin(targets = "net.minecraft.class_776")
public class BlockRenderMixin {
    // method_3355 is renderBatched: every block of a chunk goes through it while the world is drawn.
    // By name only: at runtime the arguments carry the game's own class names, not the ones of the mapping file.
    @Inject(method = "method_3355", at = @At("HEAD"))
    private void crashSleuthFixture(CallbackInfo info) {
        if (FixtureMod.mode().equals("block-render")) {
            throw new IllegalStateException("Shop sign model is not loaded");
        }
    }
}

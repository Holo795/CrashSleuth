package dev.holo795.crashsleuth.fixture.mixin;

import dev.holo795.crashsleuth.fixture.FixtureMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throws while a furnace is ticked ("block-entity-tick" mode): the server is ticking a block entity,
 * so its crash report names that block and where it stands. The lab places the furnace with a command.
 * class_2609 is AbstractFurnaceBlockEntity and method_31651 its server tick, under the names of the
 * versions Fabric has mappings for.
 */
@Mixin(targets = {"net.minecraft.class_2609", "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity"})
public class FurnaceTickMixin {
    @Inject(method = {"method_31651", "serverTick"}, at = @At("HEAD"), require = 0)
    private static void crashSleuthFixture(CallbackInfo info) {
        if (FixtureMod.mode().equals("block-entity-tick")) {
            throw new IllegalStateException("Shop furnace lost its recipe");
        }
    }
}

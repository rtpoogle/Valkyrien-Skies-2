package org.valkyrienskies.mod.forge.mixin.compat.create.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.ghostblock.GhostBlockParams;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSClientGameUtils;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.mixin.mod_compat.create.accessors.GhostBlockParamsAccessor;

@Mixin(
    targets = {
        "net.createmod.catnip.ghostblock.GhostBlockRenderer$DefaultGhostBlockRenderer",
        "net.createmod.catnip.ghostblock.GhostBlockRenderer$TransparentGhostBlockRenderer"
    }
)
public class MixinGhostBlockRenderer {

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0)
    )
    private void redirectTranslate(
        final PoseStack instance, final double pose, final double d, final double e) {
        VSClientGameUtils.transformRenderIfInShipyard(instance, pose, d, e);
    }
}

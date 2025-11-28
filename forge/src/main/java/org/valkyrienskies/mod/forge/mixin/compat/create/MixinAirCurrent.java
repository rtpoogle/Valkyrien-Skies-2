package org.valkyrienskies.mod.forge.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import java.util.Iterator;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBi;
import org.joml.primitives.AABBic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.handling.DefaultShipyardEntityHandler;
import org.valkyrienskies.mod.common.entity.handling.VSEntityManager;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;
import org.valkyrienskies.mod.common.world.RaycastUtilsKt;
import org.valkyrienskies.mod.forge.mixinducks.mod_compat.create.IExtendedAirCurrentSource;

@Mixin(value = AirCurrent.class)
public abstract class MixinAirCurrent {
    @Unique
    private static final boolean[] FALSE_THEN_TRUE = new boolean[]{false, true};
    @Unique
    private static final double NON_BLOCK_EXTEND = 1 / 32d;
    @Unique
    private static final double EPS1 = 1e-6;
    @Unique
    private static final double EPS2 = 2e-6;
    @Unique
    private static final double EPS3 = 4e-6;

    @Shadow
    @Final
    public IAirCurrentSource source;
    @Shadow
    public Direction direction;
    @Shadow
    public boolean pushing;
    @Shadow
    public float maxDistance;
    @Shadow
    protected List<Pair<TransportedItemStackHandlerBehaviour, FanProcessingType>> affectedItemHandlers;

    @Unique
    private double shipScale = 1.0;
    @Unique
    private List<AdvancedAirCurrentSegment> segments = new ArrayList<>();

    @Shadow
    private static boolean shouldAlwaysPass(BlockState state) {
        return false;
    }

    @Shadow
    protected abstract int getLimit();

    @Unique
    private Ship getShip() {
        if (source instanceof IExtendedAirCurrentSource se) {
            return se.getShip();
        }
        if (source.getAirCurrentWorld() != null) {
            return VSGameUtilsKt.getShipManagingPos(source.getAirCurrentWorld(), source.getAirCurrentPos());
        }
        return null;
    }

    @Inject(method = "getFlowLimit", at = @At("HEAD"), cancellable = true, remap = false)
    private static void clipFlowLimit(Level level, BlockPos start, float max, Direction facing, CallbackInfoReturnable<Float> cir) {
        Ship ship = VSGameUtilsKt.getShipManagingPos(level, start);
        if (ship != null) {
            Vector3d startVec = ship.getTransform().getShipToWorld().transformPosition(new Vector3d(start.getX() + 0.5, start.getY() + 0.5, start.getZ() + 0.5));
            Vector3d direction = ship.getTransform().getShipToWorld().transformDirection(VectorConversionsMCKt.toJOMLD(facing.getNormal()));
            startVec.add(direction.x, direction.y, direction.z);
            direction.mul(max);
            Vec3 mcStart = VectorConversionsMCKt.toMinecraft(startVec);
            BlockHitResult result = RaycastUtilsKt.clipIncludeShips(level,
                    new ClipContext(
                            mcStart,
                            VectorConversionsMCKt.toMinecraft(startVec.add(direction.x, direction.y, direction.z)),
                            ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE,
                            CollisionContext.empty()));

            // Distance from start to end but, its not squared so, slow -_-
            cir.setReturnValue((float) result.getLocation().distanceTo(mcStart));
        } else {
            BlockPos end = start.relative(facing, (int) max);
            if (VSGameUtilsKt.getShipsIntersecting(level,
                    new AABB(start.getX(), start.getY(), start.getZ(),
                            end.getX() + 1.0, end.getY() + 1.0, end.getZ() + 1.0)).iterator().hasNext()) {
                Vec3 centerStart = Vec3.atCenterOf(start);
                BlockHitResult result = RaycastUtilsKt.clipIncludeShips(level,
                        new ClipContext(
                                centerStart.add(facing.getStepX(), facing.getStepY(), facing.getStepZ()),
                                Vec3.atCenterOf(end),
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()));

            direction.mul(flowLimit);
            final Vec3 startPos = VectorConversionsMCKt.toMinecraft(startVec);
            final Vec3 endPos = VectorConversionsMCKt.toMinecraft(startVec.add(direction.x, direction.y, direction.z));
            final BlockHitResult result = level.clip(new AirFlowClipContext(level, start, startPos, endPos, MixinAirCurrent::shouldAlwaysPass));

            // Convert world space distance to ship space distance by dividing by shipScale
            double limit = result.getLocation().distanceTo(startPos) / shipScale + EPS2;
            // crazy Create compat
            if (result.getType() == HitResult.Type.BLOCK) {
                final BlockPos pos = result.getBlockPos();
                if (level.getBlockState(pos).getCollisionShape(level, pos) != Shapes.block()) {
                    limit += NON_BLOCK_EXTEND;
                }
            }
            cir.setReturnValue((float) (limit));
            return;
        }
        final BlockPos end = start.relative(facing, (int) (Math.ceil(flowLimit)));
        if (
            VSGameUtilsKt.getShipsIntersecting(
                level,
                new AABB(start.getX(), start.getY(), start.getZ(), end.getX() + 1, end.getY() + 1, end.getZ() + 1)
            )
                .iterator()
                .hasNext()
        ) {
            final Vec3 startPos = Vec3.atCenterOf(start).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
            final Vec3 endPos = Vec3.atCenterOf(end).add(facing.getStepX() * 0.5, facing.getStepY() * 0.5, facing.getStepZ() * 0.5);
            final BlockHitResult result = level.clip(new AirFlowClipContext(level, start, startPos, endPos, MixinAirCurrent::shouldAlwaysPass));
            double limit = result.getLocation().distanceTo(startPos) + EPS2;
            // crazy Create compat
            if (result.getType() == HitResult.Type.BLOCK) {
                final BlockPos pos = result.getBlockPos();
                if (level.getBlockState(pos).getCollisionShape(level, pos) != Shapes.block()) {
                    limit += NON_BLOCK_EXTEND;
                }
            }
            cir.setReturnValue(Math.min((float) (limit), flowLimit));
        }
    }

    @Inject(
        method = "rebuild",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/fan/IAirCurrentSource;getAirCurrentWorld()Lnet/minecraft/world/level/Level;",
            remap = true
        ),
        remap = false
    )
    private void calcScaling(CallbackInfo ci) {
        Ship ship = this.getShip();
        if (ship != null) {
            final Vector3dc scaling = ship.getTransform().getShipToWorldScaling();
            this.shipScale = this.direction.getAxis().choose(scaling.x(), scaling.y(), scaling.z());
        }
    }

    /**
     * MIT License
     * Copyright (c) The Create Team / The Creators of Create
     * Modified by zyxkad, 2025
     *
     * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
     * and associated documentation files (the "Software"), to deal in the Software without restriction,
     * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
     * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
     * subject to the following conditions:
     *
     * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
     *
     * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
     * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
     * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
     * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
     * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
     * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
     */
    @Inject(method = "rebuild", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/AirCurrent;findAffectedHandlers()V"), remap = false)
    private void calcSegments(final CallbackInfo ci) {
        this.segments.clear();
        final Level level = this.source.getAirCurrentWorld();
        final BlockPos start = this.source.getAirCurrentPos();
        final Vec3 startCenter = start.getCenter();
        AdvancedAirCurrentSegment currentSegment = null;
        FanProcessingType type = null;

        final int limit = this.getLimit();
        // Note: Weird create behaviour that makes pulling fan process depot right under a processor
        // but not for pushing fan.

        final Vec3 delta = new Vec3(this.direction.getStepX() * 0.5, this.direction.getStepY() * 0.5, this.direction.getStepZ() * 0.5);
        final Vec3 startPos = startCenter.add(delta);
        final Vec3 endPos = startCenter.relative(this.direction, this.maxDistance).add(delta);
        final AdvancedBlockWalker walker = new AdvancedBlockWalker(level, startPos, endPos, !this.pushing, true);
        while (walker.hasNext()) {
            final AdvancedBlockWalker.BlockPosWithDistance data = walker.next();
            final FanProcessingType newType = FanProcessingType.getAt(level, data.pos());
            double dist = data.distance();
            if (dist < Integer.MAX_VALUE && Math.abs(dist - (int) (dist)) < EPS1) {
                dist = (int) (dist);
            }
            if (newType != null) {
                type = newType;
            }
            if (currentSegment == null) {
                currentSegment = new AdvancedAirCurrentSegment();
                currentSegment.startOffset = dist;
                currentSegment.type = type;
            } else if (currentSegment.type != type) {
                currentSegment.endOffset = dist;
                this.segments.add(currentSegment);
                currentSegment = new AdvancedAirCurrentSegment();
                currentSegment.startOffset = dist;
                currentSegment.type = type;
            }
        }
        if (currentSegment != null) {
            currentSegment.endOffset = this.pushing ? limit : 0;
            this.segments.add(currentSegment);
        }
    }

    /**
     * On scaled ships we move the entity position closer to the current source, so that subsequently called distance
     * calculations that might or might not be Create-specific give a value accounted for ship-to-world scaling.
     */
    @WrapOperation(method = "tickAffectedEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 transformEntityPos(Entity instance, Operation<Vec3> original) {
        Vec3 result = original.call(instance);

        Ship ship = this.getShip();
        if (ship == null || VSEntityManager.INSTANCE.getHandler(instance) instanceof DefaultShipyardEntityHandler) {
            return result;
        }
        Vector3dc sourcePos = VectorConversionsMCKt.toJOML(source.getAirCurrentPos().getCenter());
        Vector3dc naiveEntityPos = ship.getWorldToShip().transformPosition(VectorConversionsMCKt.toJOML(result));

        Vector3dc distanceFromSource = VectorConversionsMCKt.toJOML(source.getAirCurrentPos().getCenter()).sub(naiveEntityPos);
        Vector3dc adjustedEntityPos = sourcePos.sub(
            distanceFromSource.div(this.shipScale, new Vector3d()),
            new Vector3d()
        );
        return VectorConversionsMCKt.toMinecraft(adjustedEntityPos);
    }

    /**
     * Our fake entity position is really useful for all ship- and scale-aware of distance calculations, particles
     * should be spawned where the entity actually is.
     */
    @ModifyArg(
        method = "tickAffectedEntities",
        at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/fan/processing/FanProcessingType;spawnProcessingParticles(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;)V"),
        index = 1
    )
    private void harvester(Level world, CallbackInfo ci, Iterator iterator, Entity entity, Vec3i flow, float speed,
        float sneakModifier, double entityDistance, double entityDistanceOld, float acceleration) {
        Ship ship = getShip();
        if (ship != null) {
            Vector3d tempVec = new Vector3d();
            ship.getTransform().getShipToWorld().transformDirection(flow.getX(), flow.getY(), flow.getZ(), tempVec);
            Vec3 transformedFlow = VectorConversionsMCKt.toMinecraft(tempVec);

            Vec3 previousMotion = instance.getDeltaMovement();
            double xIn = Mth.clamp(transformedFlow.x * acceleration - previousMotion.x, -maxAcceleration, maxAcceleration);
            double yIn = Mth.clamp(transformedFlow.y * acceleration - previousMotion.y, -maxAcceleration, maxAcceleration);
            double zIn = Mth.clamp(transformedFlow.z * acceleration - previousMotion.z, -maxAcceleration, maxAcceleration);
            motion = previousMotion.add(new Vec3(xIn, yIn, zIn).scale(1 / 8f));
        }
        original.call(instance, motion);
    }

    /**
     * MIT License
     * Copyright (c) The Create Team / The Creators of Create
     * Modified by zyxkad, 2025
     */
    @Inject(method = "findAffectedHandlers", at = @At("HEAD"), cancellable = true, remap = false)
    private void findAffectedHandlers(final CallbackInfo ci) {
        ci.cancel();
        this.affectedItemHandlers.clear();
        final Level level = this.source.getAirCurrentWorld();
        final BlockPos start = this.source.getAirCurrentPos();
        final Vec3 startCenter = start.getCenter();

        final List<AdvancedBlockWalker.BlockPosWithDistance> datas = new ArrayList<>();

        final Vec3 delta = new Vec3(this.direction.getStepX() * 0.5, this.direction.getStepY() * 0.5, this.direction.getStepZ() * 0.5);
        final Vec3 startPos = startCenter.add(delta);
        final Vec3 endPos = startCenter.relative(this.direction, this.maxDistance).add(delta);
        final AdvancedBlockWalker walker = new AdvancedBlockWalker(level, startPos, endPos, !this.pushing, false);
        while (walker.hasNext()) {
            datas.add(walker.next());
        }

        final Set<BlockPos> processed = new HashSet<>();

        // Process below blocks such as depot, after processed all blocks on the path,
        // so vertical current will process with correct FanProcessingType.
        for (final boolean checkBelow : FALSE_THEN_TRUE) {
            for (final AdvancedBlockWalker.BlockPosWithDistance data : datas) {
                final BlockPos pos = checkBelow ? data.pos().below() : data.pos();
                final TransportedItemStackHandlerBehaviour behaviour =
                    BlockEntityBehaviour.get(level, pos, TransportedItemStackHandlerBehaviour.TYPE);
                if (behaviour == null) {
                    continue;
                }
                // Move the check point towards the block center for a bit,
                // so getTypeAt0 can correctly handle the case that a depot is
                // right after a processor.
                double dist = data.distance() + EPS3;
                if (dist < Integer.MAX_VALUE && Math.abs(dist - (int) (dist)) < EPS1) {
                    dist = (int) (dist);
                }
                if (dist > this.maxDistance) {
                    continue;
                }
                if (!processed.add(pos)) {
                    continue;
                }
                FanProcessingType type = FanProcessingType.getAt(level, pos);
                if (type == null) {
                    type = this.getTypeAt0(dist);
                }
                this.affectedItemHandlers.add(Pair.of(behaviour, type));
            }
        }
    }

    // Require 0 because this mixin doesn't work in create 0.5.1f
    @Redirect(method = "tickAffectedEntities", at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/math/VecHelper;getCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;"), allow = 1, require = 0, remap = false)
    private Vec3 redirectGetCenterOf(Vec3i pos) {
        Ship ship = getShip();
        Vec3 result = VecHelper.getCenterOf(pos);
        if (ship != null && this.source.getAirCurrentWorld() != null) {
            Vector3d tempVec = new Vector3d();
            ship.getTransform().getShipToWorld().transformPosition(result.x, result.y, result.z, tempVec);
            result = VectorConversionsMCKt.toMinecraft(tempVec);
        }
        if (offset < 0 || offset > this.maxDistance) {
            return null;
        }
        if (this.pushing) {
            for (final AdvancedAirCurrentSegment segment : this.segments) {
                if (offset <= segment.endOffset) {
                    return segment.type;
                }
            }
        } else {
            for (final AdvancedAirCurrentSegment segment : this.segments) {
                if (offset >= segment.endOffset) {
                    return segment.type;
                }
            }
        }
        return null;
    }
}

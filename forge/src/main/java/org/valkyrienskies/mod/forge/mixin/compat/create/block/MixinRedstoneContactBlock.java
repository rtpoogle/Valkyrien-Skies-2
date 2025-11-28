package org.valkyrienskies.mod.forge.mixin.compat.create.block;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.redstone.contact.RedstoneContactBlock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.VectorConversionsMCKt;

@Mixin(RedstoneContactBlock.class)
public abstract class MixinRedstoneContactBlock {

    @Shadow
    @Final
    public static BooleanProperty POWERED;
    @Unique
    private static final double CHECK_BOUND = 2.0 / 16;
    @Unique
    private static final double INTERSECT_BOUND = CHECK_BOUND + 0.1;

    @Inject(method = "onRemove", at = @At("HEAD"))
    private void injectOnRemove(BlockState state, Level worldIn, BlockPos pos, BlockState newState, boolean isMoving, CallbackInfo ci) {
        if (state.getBlock() == RedstoneContactBlock.class.cast(this) && newState.isAir()) {
            Pair<Level, BlockPos> key = Pair.of(worldIn, pos);
            if (state.getValue(POWERED) && contactCache.containsKey(key)) {
                worldIn.scheduleTick(contactCache.get(key), AllBlocks.REDSTONE_CONTACT.get(), 2, TickPriority.NORMAL);
                contactCache.remove(key);
            }
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE_ASSIGN", shift = At.Shift.BY, by = 2, target = "Lcom/simibubi/create/content/redstone/contact/RedstoneContactBlock;hasValidContact(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z"), locals = LocalCapture.CAPTURE_FAILHARD, remap = false)
    private void injectTick(BlockState state, ServerLevel worldIn, BlockPos pos, RandomSource random, CallbackInfo ci, boolean hasValidContact) {
        if (VSGameUtilsKt.isBlockInShipyard(worldIn, pos)) {
            Pair<Level, BlockPos> key = Pair.of(worldIn, pos);
            if (!hasValidContact && state.getValue(POWERED) && contactCache.containsKey(key)) {
                worldIn.scheduleTick(contactCache.get(key), AllBlocks.REDSTONE_CONTACT.get(), 2, TickPriority.NORMAL);
                contactCache.remove(key);
            }
            worldIn.scheduleTick(pos, AllBlocks.REDSTONE_CONTACT.get(), 2, TickPriority.NORMAL);
        }
    }

    @Unique
    private static boolean hasContact(Level world, Ship ship, Vector3d searchPos, Direction direction, Ship shipItr) {
        BlockState blockState = world.getBlockState(BlockPos.containing(VectorConversionsMCKt.toMinecraft(searchPos)));
        if (AllBlocks.REDSTONE_CONTACT.has(blockState)) {
            Vector3d worldDirection = toJOML(Vec3.atLowerCornerOf(direction.getNormal()));
            Vector3d targetDirection = toJOML(Vec3.atLowerCornerOf(blockState.getValue(RedstoneContactBlock.FACING).getNormal()));
            if (ship != null) {
                ship.getShipToWorld().transformDirection(worldDirection, worldDirection);
            }
        }
        if (ship != null) {
            final Matrix4dc shipMat = ship.getWorldToShip();
            for (final Vector3d checkPoint : checkPoints) {
                shipMat.transformPosition(checkPoint);
            }
        }
        for (final Vector3d checkPoint : checkPoints) {
            if (selfPos.equals(BlockPos.containing(checkPoint.x, checkPoint.y, checkPoint.z))) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "hasValidContact", at = @At("RETURN"), cancellable = true, remap = false)
    private static void injectHasValidContact(LevelAccessor world, BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        boolean result = false;
        Level worldLevel = (Level) world;
        BlockState blockState = world.getBlockState(pos.relative(direction));
        if (AllBlocks.REDSTONE_CONTACT.has(blockState)) {
            cir.setReturnValue(blockState.getValue(RedstoneContactBlock.FACING) == direction.getOpposite());
        } else {

            AABB searchAABB = new AABB(pos.relative(direction));
            Vector3d searchPos = toJOML(Vec3.atCenterOf(pos.relative(direction)));
            Ship ship = VSGameUtilsKt.getShipManagingPos(worldLevel, pos);
            if (VSGameUtilsKt.isBlockInShipyard(worldLevel, pos) && ship != null) {
                Vector3d tempVec = toJOML(Vec3.atCenterOf(pos.relative(direction)));
                searchPos = ship.getShipToWorld().transformPosition(tempVec, new Vector3d());
                ship.getShipToWorld().transformPosition(tempVec, tempVec);
                double bounds = 0.25;
                searchAABB = new AABB(tempVec.x - bounds, tempVec.y - bounds, tempVec.z - bounds,
                        tempVec.x + bounds, tempVec.y + bounds, tempVec.z + bounds);

                result = hasContact(worldLevel, ship, searchPos, direction, null);
            }
            Iterator<Ship> ships = VSGameUtilsKt.getShipsIntersecting(worldLevel, searchAABB).iterator();
            if (ships.hasNext() && !result) {
                do {
                    Ship shipItr = ships.next();
                    if (shipItr == ship) continue;
                    Vector3d newSearchPos = shipItr.getWorldToShip().transformPosition(searchPos, new Vector3d());
                    result = hasContact(worldLevel, ship, newSearchPos, direction, shipItr);
                    if (result) searchPos = newSearchPos;
                } while (ships.hasNext() && !result);
            }
            if (result) {
                contactCache.put(Pair.of(worldLevel, pos), BlockPos.containing(VectorConversionsMCKt.toMinecraft(searchPos)));
                world.scheduleTick(BlockPos.containing(VectorConversionsMCKt.toMinecraft(searchPos)), AllBlocks.REDSTONE_CONTACT.get(), 2, TickPriority.NORMAL);
            }
            cir.setReturnValue(result);
        }
        final Level level = (Level) (world);
        final BlockPos detectPos = pos.relative(direction);
        final BlockState facingState = world.getBlockState(detectPos);
        if (isContact(facingState)) {
            cir.setReturnValue(facingState.getValue(FACING) == direction.getOpposite());
            return;
        }
        if (world.getBlockState(pos).is(AllBlocks.ELEVATOR_CONTACT.get())) {
            // DO NOT RAY CAST ELEVATOR CONTACT
            // BECAUSE IT IS
            // BASED ON ELEVATOR'S
            // TARGET POSITION
            // NOT THE
            // PEER CONTACT'S POSITION
            return;
        }
        final Vec3 point = detectPos.getCenter();
        final Vector3d[] checkPoints = makeCheckPoints(point, direction);
        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship != null) {
            final Matrix4dc shipMat = ship.getShipToWorld();
            for (final Vector3d checkPoint : checkPoints) {
                shipMat.transformPosition(checkPoint);
            }
        }
        final AABB searchAABB = VSGameUtilsKt.transformAabbToWorld(level, new AABB(
            point.x - INTERSECT_BOUND, point.y - INTERSECT_BOUND, point.z - INTERSECT_BOUND,
            point.x + INTERSECT_BOUND, point.y + INTERSECT_BOUND, point.z + INTERSECT_BOUND
        ));
        BlockPos foundBlock = null;
        boolean found = false;

        for (final Vector3d checkPoint : checkPoints) {
            foundBlock = BlockPos.containing(checkPoint.x, checkPoint.y, checkPoint.z);
            if (hasContact(world, pos, direction, ship, foundBlock, null)) {
                found = true;
                break;
            }
            final Vec3 checkPos = new Vec3(checkPoint.x, checkPoint.y, checkPoint.z);
            for (final AbstractContraptionEntity contraption : world.getEntitiesOfClass(AbstractContraptionEntity.class, searchAABB)) {
                final Vec3 localPos = contraption.toLocalVector(checkPos, 1);
                final StructureBlockInfo info = contraption.getContraption().getBlocks().get(BlockPos.containing(localPos));
                if (info == null) {
                    continue;
                }
                if (!isContact(info.state())) {
                    continue;
                }
                final Direction dir = info.state().getValue(FACING);
                final Vec3 checkVec = contraption.toGlobalVector(localPos.relative(dir, 0.65), 1);
                final Vector3d checkP = new Vector3d(checkVec.x, checkVec.y, checkVec.z);
                if (ship != null) {
                    ship.getWorldToShip().transformPosition(checkP);
                }
                if (pos.equals(BlockPos.containing(checkP.x, checkP.y, checkP.z))) {
                    foundBlock = null;
                    found = true;
                    break;
                }
            }
            if (found) {
                break;
            }
        }
        if (!found) {
            final Vector3d foundPos = new Vector3d();
            for (final Ship targetShip : VSGameUtilsKt.getShipsIntersecting(level, searchAABB)) {
                for (final Vector3d checkPoint : checkPoints) {
                    targetShip.getWorldToShip().transformPosition(checkPoint, foundPos);
                    if (targetShip != ship) {
                        foundBlock = BlockPos.containing(foundPos.x, foundPos.y, foundPos.z);
                        if (hasContact(world, pos, direction, ship, foundBlock, targetShip)) {
                            found = true;
                            break;
                        }
                    }
                    final Vec3 checkPos = new Vec3(foundPos.x, foundPos.y, foundPos.z);
                    final AABB searchAABB2 = VectorConversionsMCKt.toMinecraft(
                        VectorConversionsMCKt.toJOML(searchAABB).transform(targetShip.getWorldToShip())
                    );
                    for (final AbstractContraptionEntity contraption : world.getEntitiesOfClass(AbstractContraptionEntity.class, searchAABB2)) {
                        final Vec3 localPos = contraption.toLocalVector(checkPos, 1);
                        final StructureBlockInfo info = contraption.getContraption().getBlocks().get(BlockPos.containing(localPos));
                        if (info == null) {
                            continue;
                        }
                        if (!isContact(info.state())) {
                            continue;
                        }
                        final Direction dir = info.state().getValue(FACING);
                        final Vec3 checkVec = contraption.toGlobalVector(localPos.relative(dir, 0.65), 1);
                        final Vector3d checkP = new Vector3d(checkVec.x, checkVec.y, checkVec.z);
                        if (targetShip != ship) {
                            targetShip.getShipToWorld().transformPosition(checkP);
                            if (ship != null) {
                                ship.getWorldToShip().transformPosition(checkP);
                            }
                        }
                        if (pos.equals(BlockPos.containing(checkP.x, checkP.y, checkP.z))) {
                            foundBlock = null;
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
        }
        if (!found) {
            return;
        }
        if (foundBlock != null) {
            final BlockState targetState = world.getBlockState(foundBlock);
            if (!targetState.getValue(POWERED)) {
                level.setBlockAndUpdate(foundBlock, targetState.setValue(POWERED, true));
            }
        }
        cir.setReturnValue(true);
    }

    @Unique
    private static boolean isContact(final BlockState state) {
        return state.is(AllBlocks.REDSTONE_CONTACT.get()) || state.is(AllBlocks.ELEVATOR_CONTACT.get());
    }

    @Unique
    private static Vector3d[] makeCheckPoints(final Vec3 point, final Direction direction) {
        return switch (direction.getAxis()) {
            case X -> new Vector3d[]{
                new Vector3d(point.x, point.y - CHECK_BOUND, point.z - CHECK_BOUND),
                new Vector3d(point.x, point.y - CHECK_BOUND, point.z + CHECK_BOUND),
                new Vector3d(point.x, point.y + CHECK_BOUND, point.z - CHECK_BOUND),
                new Vector3d(point.x, point.y + CHECK_BOUND, point.z + CHECK_BOUND)
            };
            case Y -> new Vector3d[]{
                new Vector3d(point.x - CHECK_BOUND, point.y, point.z - CHECK_BOUND),
                new Vector3d(point.x - CHECK_BOUND, point.y, point.z + CHECK_BOUND),
                new Vector3d(point.x + CHECK_BOUND, point.y, point.z - CHECK_BOUND),
                new Vector3d(point.x + CHECK_BOUND, point.y, point.z + CHECK_BOUND)
            };
            case Z -> new Vector3d[]{
                new Vector3d(point.x - CHECK_BOUND, point.y - CHECK_BOUND, point.z),
                new Vector3d(point.x - CHECK_BOUND, point.y + CHECK_BOUND, point.z),
                new Vector3d(point.x + CHECK_BOUND, point.y - CHECK_BOUND, point.z),
                new Vector3d(point.x + CHECK_BOUND, point.y + CHECK_BOUND, point.z)
            };
        };
    }
}

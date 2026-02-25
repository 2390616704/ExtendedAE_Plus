package com.extendedae_plus.content.router;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 样板路由器方块
 *
 * 功能：
 * 1. 从相邻容器读取样板（样板总线模式）
 * 2. 根据样板后缀智能路由材料到对应的样板供应器
 *
 * 交互：
 * - 右键点击路由器会尝试打开相邻容器的GUI
 * - 无需自己的GUI界面
 */
public class PatternRouterBlock extends Block implements EntityBlock {

    public PatternRouterBlock(Properties props) {
        super(props);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PatternRouterBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            // 尝试打开相邻容器的GUI
            if (tryOpenAdjacentContainer(serverPlayer, level, pos)) {
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 尝试打开相邻容器的GUI
     * 遍历6个方向，找到第一个可以打开的容器
     */
    private boolean tryOpenAdjacentContainer(ServerPlayer player, Level level, BlockPos pos) {
        // 遍历6个方向
        for (Direction dir : Direction.values()) {
            BlockPos adjacentPos = pos.relative(dir);
            BlockEntity be = level.getBlockEntity(adjacentPos);

            // 方法1: 直接检查MenuProvider
            if (be instanceof MenuProvider provider) {
                NetworkHooks.openScreen(player, provider, adjacentPos);
                return true;
            }

            // 方法2: 通过BlockState获取MenuProvider
            BlockState adjacentState = level.getBlockState(adjacentPos);
            MenuProvider provider = adjacentState.getMenuProvider(level, adjacentPos);
            if (provider != null) {
                NetworkHooks.openScreen(player, provider, adjacentPos);
                return true;
            }
        }
        return false;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                               Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);

        // 通知BlockEntity相邻方块变化
        if (level.getBlockEntity(pos) instanceof PatternRouterBlockEntity routerBE) {
            routerBE.onNeighborChanged();
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                        BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // 方块被破坏时的清理工作
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}

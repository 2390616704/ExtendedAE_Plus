package com.extendedae_plus.content.router;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 样板路由器方块
 *
 * 功能：
 * 1. 通过漏斗输入编码样板
 * 2. 自动根据样板名称后缀插入到匹配的供应器
 * 3. 如果没有匹配的供应器，样板保留在存储中
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
    public void onRemove(BlockState state, Level level, BlockPos pos,
                        BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // 方块被破坏时的清理工作
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }
}

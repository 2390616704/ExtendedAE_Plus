package com.extendedae_plus.content.router;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * 样板输入总线方块
 *
 * 功能：
 * 1. 玩家将编码样板放入配置槽
 * 2. 自动根据样板名称后缀插入到匹配的供应器
 * 3. 如果没有匹配的供应器，样板保留在槽中
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
        System.out.println("[PatternRouterBlock] use() called at " + pos);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            System.out.println("[PatternRouterBlock] Server side, player: " + serverPlayer.getName().getString());
            BlockEntity be = level.getBlockEntity(pos);
            System.out.println("[PatternRouterBlock] BlockEntity: " + (be == null ? "null" : be.getClass().getSimpleName()));
            if (be instanceof PatternRouterBlockEntity routerBE) {
                System.out.println("[PatternRouterBlock] Opening GUI for PatternRouter");
                // 打开GUI
                NetworkHooks.openScreen(serverPlayer, routerBE, pos);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
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

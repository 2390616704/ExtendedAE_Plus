package com.extendedae_plus.content.router;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
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
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        
        if (!level.isClientSide) {
            // 检查相邻方块是否有容器
            boolean hasAdjacentContainer = false;
            for (Direction direction : Direction.values()) {
                BlockPos adjacentPos = pos.relative(direction);
                BlockEntity adjacentBE = level.getBlockEntity(adjacentPos);
                if (adjacentBE != null) {
                    // 检查是否有物品处理能力（容器）
                    var cap = adjacentBE.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).orElse(null);
                    if (cap != null) {
                        hasAdjacentContainer = true;
                        break;
                    }
                }
            }
            
            if (!hasAdjacentContainer) {
                // 向附近玩家发送提示
                for (Player player : level.players()) {
                    if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 64) {
                        player.displayClientMessage(
                            Component.literal("[样板路由器] ").withStyle(net.minecraft.ChatFormatting.YELLOW)
                                .append(Component.translatable("message.extendedae_plus.router.no_adjacent_container").withStyle(net.minecraft.ChatFormatting.WHITE)),
                            true
                        );
                    }
                }
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PatternRouterBlockEntity router) {
                // 尝试打开相邻容器的GUI
                boolean openedContainer = false;
                for (Direction direction : Direction.values()) {
                    BlockPos adjacentPos = pos.relative(direction);
                    BlockEntity adjacentBE = level.getBlockEntity(adjacentPos);
                    if (adjacentBE != null) {
                        // 检查是否有物品处理能力（容器）
                        var cap = adjacentBE.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).orElse(null);
                        if (cap != null) {
                            // 尝试打开相邻容器的GUI
                            if (adjacentBE instanceof net.minecraft.world.MenuProvider menuProvider) {
                                serverPlayer.openMenu(menuProvider);
                                openedContainer = true;
                                break;
                            }
                        }
                    }
                }
                
                if (!openedContainer) {
                    // 没有找到可打开的容器，发送提示
                    player.displayClientMessage(
                        Component.literal("[样板路由器] ").withStyle(net.minecraft.ChatFormatting.YELLOW)
                            .append(Component.translatable("message.extendedae_plus.router.no_container_to_open").withStyle(net.minecraft.ChatFormatting.WHITE)),
                        true
                    );
                }
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

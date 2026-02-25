package com.extendedae_plus.content.router;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.crafting.PatternProviderPart;
import com.extendedae_plus.mixin.ae2.accessor.PatternProviderLogicAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 路由管理器
 *
 * 核心功能：
 * 1. 从样板自定义名称提取配方类型后缀
 * 2. 在AE网格中查找匹配的样板供应器
 * 3. 将合成材料转发到目标供应器
 * 4. 处理路由失败的错误提示
 */
public class RoutingManager {

    /**
     * 根据样板路由材料到目标供应器
     *
     * @param patternDetails 样板详情
     * @param inputs 输入材料
     * @param grid AE网格
     * @param player 发起合成的玩家（用于错误提示，可为null）
     * @return 是否成功路由
     */
    public boolean routePattern(IPatternDetails patternDetails,
                                KeyCounter[] inputs,
                                IGrid grid,
                                @Nullable ServerPlayer player) {
        // 1. 提取样板自定义名称的后缀
        String suffix = extractSuffix(patternDetails);

        if (suffix == null || suffix.isEmpty()) {
            sendError(player, "样板未设置配方类型后缀（格式: '物品名 - 配方类型'）");
            return false;
        }

        // 2. 在网格中查找匹配的样板供应器
        List<PatternProviderLogic> matchedProviders = findMatchedProviders(grid, suffix);

        if (matchedProviders.isEmpty()) {
            sendError(player, "未找到匹配 '" + suffix + "' 的样板供应器");
            return false;
        }

        // 3. 尝试推送材料到第一个可用供应器
        for (PatternProviderLogic provider : matchedProviders) {
            try {
                boolean success = provider.pushPattern(patternDetails, inputs);
                if (success) {
                    // 成功推送，返回true
                    return true;
                }
            } catch (Exception e) {
                // 继续尝试下一个供应器
            }
        }

        // 所有匹配的供应器都失败
        sendError(player, "所有匹配 '" + suffix + "' 的供应器都已满或繁忙");
        return false;
    }

    /**
     * 从样板自定义名称中提取后缀
     * 格式: "输出物品 - 配方类型" → 提取 "配方类型"
     *
     * 注意：由于IPatternDetails API的限制，这里需要特殊处理
     * 如果无法直接获取样板ItemStack，可能需要其他方法
     */
    private String extractSuffix(IPatternDetails pattern) {
        try {
            // 尝试方法1: 从样板的输出物品名称中提取
            // 这是一个简化的实现，实际可能需要访问样板ItemStack
            // TODO: 需要确认正确的API来获取样板的自定义名称

            // 临时实现：从样板的主要输出获取名称
            var primaryOutput = pattern.getPrimaryOutput();
            if (primaryOutput != null) {
                var stack = primaryOutput.what().wrapForDisplayOrFilter();
                if (stack instanceof ItemStack) {
                    ItemStack itemStack = (ItemStack) stack;
                    if (itemStack.hasCustomHoverName()) {
                        String name = itemStack.getHoverName().getString();
                        int separatorIndex = name.lastIndexOf(" - ");
                        if (separatorIndex != -1) {
                            return name.substring(separatorIndex + 3).trim();
                        }
                    }
                }
            }

            // 如果主要输出没有自定义名称，尝试使用配方类型作为后缀
            // 这需要根据实际样板格式调整
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查找匹配的样板供应器
     * 匹配规则: 供应器的自定义名称包含样板的后缀
     */
    private List<PatternProviderLogic> findMatchedProviders(IGrid grid, String suffix) {
        Set<PatternProviderLogic> allLogics = new HashSet<>();

        // 收集所有样板供应器的逻辑实例（去重）
        try {
            // 方块形式的样板供应器
            Set<PatternProviderBlockEntity> blocks = grid.getMachines(PatternProviderBlockEntity.class);
            for (PatternProviderBlockEntity be : blocks) {
                if (be != null && be.getLogic() != null) {
                    allLogics.add(be.getLogic());
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // Part形式的样板供应器
            Set<PatternProviderPart> parts = grid.getMachines(PatternProviderPart.class);
            for (PatternProviderPart part : parts) {
                if (part != null && part.getLogic() != null) {
                    allLogics.add(part.getLogic());
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            // 兼容：任意实现了PatternProviderLogicHost的机器
            Set<PatternProviderLogicHost> hosts = grid.getMachines(PatternProviderLogicHost.class);
            for (PatternProviderLogicHost host : hosts) {
                if (host != null && host.getLogic() != null) {
                    allLogics.add(host.getLogic());
                }
            }
        } catch (Throwable ignored) {
        }

        // 过滤匹配的供应器
        List<PatternProviderLogic> matched = new ArrayList<>();
        for (PatternProviderLogic logic : allLogics) {
            Component providerName = getProviderCustomName(logic);
            if (providerName != null) {
                String providerNameStr = providerName.getString();
                if (providerNameStr.contains(suffix)) {
                    matched.add(logic);
                }
            }
        }

        return matched;
    }

    /**
     * 获取样板供应器的自定义名称
     */
    private Component getProviderCustomName(PatternProviderLogic logic) {
        try {
            // 通过Accessor获取host
            if (logic instanceof PatternProviderLogicAccessor accessor) {
                PatternProviderLogicHost host = accessor.eap$host();
                if (host == null) {
                    return null;
                }

                // 获取BlockEntity
                BlockEntity be = host.getBlockEntity();
                if (be instanceof Nameable nameable) {
                    Component customName = nameable.getCustomName();
                    if (customName != null) {
                        return customName;
                    }
                    // 如果没有自定义名称，返回显示名称
                    return nameable.getDisplayName();
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 发送错误消息到玩家聊天栏
     */
    private void sendError(@Nullable ServerPlayer player, String message) {
        if (player != null) {
            Component msg = Component.literal("[样板路由器] ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(msg);
        }
    }

    /**
     * 发送成功消息到玩家（可选功能）
     */
    @SuppressWarnings("unused")
    private void sendSuccess(@Nullable ServerPlayer player, String message) {
        if (player != null) {
            Component msg = Component.literal("[样板路由器] ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
            player.displayClientMessage(msg, true); // 显示在动作栏
        }
    }
}

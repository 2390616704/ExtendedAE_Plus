package com.extendedae_plus.content.router;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.crafting.PatternProviderPart;
import com.extendedae_plus.mixin.ae2.accessor.PatternProviderLogicAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 样板插入管理器
 *
 * 核心功能：
 * 1. 从样板自定义名称提取配方类型后缀
 * 2. 在AE网格中查找匹配的样板供应器
 * 3. 将样板 ItemStack 插入到目标供应器的样板槽
 * 4. 处理插入失败的错误提示
 */
public class PatternInsertionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatternInsertionManager.class);

    /**
     * 尝试将样板插入到匹配的供应器
     *
     * @param patternStack 样板物品
     * @param grid AE网格
     * @param player 操作的玩家（用于错误提示，可为null）
     * @param busPos 样板输入总线的坐标（用于错误提示）
     * @return 是否成功插入
     */
    public boolean insertPattern(ItemStack patternStack,
                                  IGrid grid,
                                  @Nullable ServerPlayer player,
                                  BlockPos busPos) {
        System.out.println("[PatternInsertionManager] insertPattern() called");
        System.out.println("[PatternInsertionManager] patternStack: " + patternStack);
        LOGGER.info("========== [样板输入总线] 尝试插入样板 ==========");
        LOGGER.info("样板ItemStack: {}", patternStack);

        // 1. 提取样板自定义名称的后缀
        String suffix = extractSuffix(patternStack);
        LOGGER.info("提取到的后缀: {}", suffix);

        String patternDisplayName = getPatternDisplayName(patternStack);

        if (suffix == null || suffix.isEmpty()) {
            LOGGER.warn("样板后缀为空或null");
            sendError(player, "message.extendedae_plus.router.no_suffix", patternDisplayName);
            return false;
        }

        // 2. 在网格中查找匹配的样板供应器
        List<PatternProviderLogic> matchedProviders = findMatchedProviders(grid, suffix);
        LOGGER.info("找到 {} 个匹配 '{}' 的供应器", matchedProviders.size(), suffix);

        if (matchedProviders.isEmpty()) {
            LOGGER.warn("未找到匹配的供应器");
            sendError(player, "message.extendedae_plus.router.no_provider", patternDisplayName, suffix, suffix);
            return false;
        }

        // 3. 尝试将样板插入到第一个有空槽的供应器
        for (int i = 0; i < matchedProviders.size(); i++) {
            PatternProviderLogic provider = matchedProviders.get(i);
            LOGGER.info("尝试插入到第 {} 个供应器", i + 1);
            try {
                if (tryInsertToProvider(provider, patternStack.copy())) {
                    LOGGER.info("========== 插入成功 ==========");
                    sendSuccess(player, "message.extendedae_plus.router.pattern_inserted", patternDisplayName);
                    return true;
                }
            } catch (Exception e) {
                LOGGER.error("插入到供应器时发生异常", e);
            }
        }

        // 所有匹配的供应器都满了
        LOGGER.warn("所有匹配的供应器都满了");
        sendError(player, "message.extendedae_plus.router.providers_full", patternDisplayName);
        return false;
    }

    /**
     * 尝试将样板插入到供应器的样板槽
     */
    private boolean tryInsertToProvider(PatternProviderLogic logic, ItemStack patternStack) {
        // 直接使用公共 API
        var patternInv = logic.getPatternInv();
        if (patternInv == null) {
            LOGGER.warn("PatternInventory为null");
            return false;
        }

        // 尝试插入样板（使用 InternalInventory 的正常方法，让监听器正常触发）
        ItemStack remaining = patternInv.addItems(patternStack.copy());

        if (remaining.isEmpty()) {
            // 完全插入成功
            LOGGER.info("样板插入成功");
            return true;
        } else if (remaining.getCount() < patternStack.getCount()) {
            // 部分插入（理论上样板不应该堆叠，所以这不应该发生）
            LOGGER.info("样板部分插入，剩余: {}", remaining.getCount());
            return false;
        }

        LOGGER.info("供应器所有槽位都满了");
        return false;
    }

    /**
     * 获取样板的显示名称
     */
    private String getPatternDisplayName(ItemStack patternStack) {
        try {
            if (patternStack.hasCustomHoverName()) {
                return patternStack.getHoverName().getString();
            }
            return patternStack.getDisplayName().getString();
        } catch (Exception e) {
            LOGGER.error("获取样板显示名称失败", e);
        }
        return "未知样板";
    }

    /**
     * 从样板自定义名称中提取后缀
     * 格式: "输出物品_配方类型" → 提取 "配方类型"
     */
    private String extractSuffix(ItemStack patternStack) {
        System.out.println("[PatternInsertionManager] extractSuffix() called");
        LOGGER.info("---------- 开始提取样板后缀 ----------");
        try {
            if (!patternStack.hasCustomHoverName()) {
                System.out.println("[PatternInsertionManager] no custom hover name");
                LOGGER.info("样板没有自定义hover名称");
                return null;
            }

            String name = patternStack.getHoverName().getString();
            System.out.println("[PatternInsertionManager] pattern name: " + name);
            LOGGER.info("自定义HoverName: {}", name);

            // 使用下划线分隔符
            int separatorIndex = name.lastIndexOf("_");
            System.out.println("[PatternInsertionManager] separator index: " + separatorIndex);
            LOGGER.info("分隔符位置: {}", separatorIndex);

            if (separatorIndex != -1) {
                String suffix = name.substring(separatorIndex + 1).trim();
                System.out.println("[PatternInsertionManager] extracted suffix: " + suffix);
                LOGGER.info("提取到的后缀: {}", suffix);
                return suffix;
            } else {
                System.out.println("[PatternInsertionManager] separator '_' not found");
                LOGGER.info("未找到分隔符 '_'");
            }

            return null;

        } catch (Exception e) {
            System.out.println("[PatternInsertionManager] exception in extractSuffix: " + e.getMessage());
            e.printStackTrace();
            LOGGER.error("提取样板后缀时发生异常", e);
            return null;
        }
    }

    /**
     * 公共方法：供BlockEntity调用提取后缀
     */
    public String extractSuffixPublic(ItemStack patternStack) {
        return extractSuffix(patternStack);
    }

    /**
     * 查找匹配的样板供应器
     * 匹配规则: 供应器的自定义名称包含样板的后缀
     */
    private List<PatternProviderLogic> findMatchedProviders(IGrid grid, String suffix) {
        LOGGER.info("---------- 开始查找匹配的供应器 ----------");
        LOGGER.info("要匹配的后缀: {}", suffix);

        Set<PatternProviderLogic> allLogics = new HashSet<>();

        // 收集所有样板供应器的逻辑实例（去重）
        try {
            // 方块形式的样板供应器
            Set<PatternProviderBlockEntity> blocks = grid.getMachines(PatternProviderBlockEntity.class);
            LOGGER.info("找到 {} 个PatternProviderBlockEntity", blocks.size());
            for (PatternProviderBlockEntity be : blocks) {
                if (be != null && be.getLogic() != null) {
                    allLogics.add(be.getLogic());
                }
            }
        } catch (Throwable e) {
            LOGGER.error("收集PatternProviderBlockEntity时出错", e);
        }

        try {
            // Part形式的样板供应器
            Set<PatternProviderPart> parts = grid.getMachines(PatternProviderPart.class);
            LOGGER.info("找到 {} 个PatternProviderPart", parts.size());
            for (PatternProviderPart part : parts) {
                if (part != null && part.getLogic() != null) {
                    allLogics.add(part.getLogic());
                }
            }
        } catch (Throwable e) {
            LOGGER.error("收集PatternProviderPart时出错", e);
        }

        try {
            // 兼容：任意实现了PatternProviderLogicHost的机器
            Set<PatternProviderLogicHost> hosts = grid.getMachines(PatternProviderLogicHost.class);
            LOGGER.info("找到 {} 个PatternProviderLogicHost", hosts.size());
            for (PatternProviderLogicHost host : hosts) {
                if (host != null && host.getLogic() != null) {
                    allLogics.add(host.getLogic());
                }
            }
        } catch (Throwable e) {
            LOGGER.error("收集PatternProviderLogicHost时出错", e);
        }

        LOGGER.info("总共收集到 {} 个供应器逻辑实例", allLogics.size());

        // 过滤匹配的供应器
        List<PatternProviderLogic> matched = new ArrayList<>();
        for (PatternProviderLogic logic : allLogics) {
            Component providerName = getProviderCustomName(logic);
            LOGGER.info("供应器名称: {}", providerName == null ? "null" : providerName.getString());

            if (providerName != null) {
                String providerNameStr = providerName.getString();
                boolean contains = providerNameStr.contains(suffix);
                LOGGER.info("名称 '{}' 是否包含后缀 '{}': {}", providerNameStr, suffix, contains);

                if (contains) {
                    matched.add(logic);
                    LOGGER.info("匹配！添加到结果列表");
                }
            }
        }

        LOGGER.info("最终匹配到 {} 个供应器", matched.size());
        return matched;
    }

    /**
     * 公共方法：供BlockEntity调用查找匹配的供应器
     */
    public List<PatternProviderLogic> findMatchedProvidersPublic(IGrid grid, String suffix) {
        return findMatchedProviders(grid, suffix);
    }

    /**
     * 获取样板供应器的自定义名称
     */
    private Component getProviderCustomName(PatternProviderLogic logic) {
        try {
            if (logic instanceof PatternProviderLogicAccessor accessor) {
                PatternProviderLogicHost host = accessor.eap$host();
                if (host == null) {
                    return null;
                }

                BlockEntity be = host.getBlockEntity();
                if (be instanceof Nameable nameable) {
                    Component customName = nameable.getCustomName();
                    if (customName != null) {
                        return customName;
                    }
                    return nameable.getDisplayName();
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("获取供应器自定义名称时出错", e);
            return null;
        }
    }

    /**
     * 发送错误消息到玩家聊天栏
     */
    private void sendError(@Nullable ServerPlayer player, String translationKey, Object... args) {
        if (player != null) {
            Component msg = Component.literal("[")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.translatable("block.extendedae_plus.pattern_router").withStyle(ChatFormatting.RED))
                    .append(Component.literal("] ").withStyle(ChatFormatting.RED))
                    .append(Component.translatable(translationKey, args).withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(msg);
        }
    }

    /**
     * 发送成功消息到玩家
     */
    private void sendSuccess(@Nullable ServerPlayer player, String translationKey, Object... args) {
        if (player != null) {
            Component msg = Component.literal("[")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.translatable("block.extendedae_plus.pattern_router").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("] ").withStyle(ChatFormatting.GREEN))
                    .append(Component.translatable(translationKey, args).withStyle(ChatFormatting.WHITE));
            player.displayClientMessage(msg, true); // 显示在动作栏
        }
    }
}

package com.extendedae_plus.util.uploadPattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.core.definitions.AEItems;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.util.inv.FilteredInternalInventory;
import com.extendedae_plus.content.router.PatternRouterBlockEntity;
import com.extendedae_plus.util.PatternProviderDataUtil;
import com.extendedae_plus.util.PatternTerminalUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 样板路由器上传工具类
 * 基于ExtendedAE_Plus官方的ProviderUploadUtil，但适配样板路由器的自动工作需求
 */
public final class PatternRouterUploadUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternRouterUploadUtil.class);
    
    private static final String ENCODE_PLAYER_KEY = "encodePlayer";
    
    private PatternRouterUploadUtil() {}
    
    /**
     * 从样板NBT中获取编码玩家名称
     */
    @Nullable
    public static String getEncodePlayerName(ItemStack pattern) {
        if (pattern.isEmpty() || !pattern.hasTag()) {
            return null;
        }
        CompoundTag tag = pattern.getTag();
        if (tag == null || !tag.contains(ENCODE_PLAYER_KEY)) {
            return null;
        }
        return tag.getString(ENCODE_PLAYER_KEY);
    }
    
    /**
     * 从玩家名称获取ServerPlayer对象
     */
    @Nullable
    public static ServerPlayer getPlayerFromName(String playerName, ServerLevel level) {
        if (playerName == null || playerName.isBlank() || level == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayerByName(playerName);
    }
    
    /**
     * 从样板名称提取配方类型后缀
     * 格式: "物品名_配方类型后缀"
     */
    @Nullable
    public static String extractRecipeTypeSuffix(ItemStack pattern) {
        if (pattern.isEmpty() || !pattern.hasCustomHoverName()) {
            return null;
        }
        
        String name = pattern.getHoverName().getString();
        if (name == null || name.isBlank()) {
            return null;
        }
        
        // 使用下划线分隔符
        int separatorIndex = name.lastIndexOf("_");
        if (separatorIndex == -1) {
            return null;
        }
        
        String suffix = name.substring(separatorIndex + 1).trim();
        return suffix.isBlank() ? null : suffix;
    }
    
    /**
     * 从编码样板获取配方类型搜索键
     * 优先从样板名称提取，如果失败则尝试解码样板
     */
    @Nullable
    public static String getRecipeSearchKey(ItemStack pattern, Level level) {
        if (pattern.isEmpty() || level == null) {
            return null;
        }
        
        // 1. 优先从样板名称提取后缀
        String suffix = extractRecipeTypeSuffix(pattern);
        if (suffix != null) {
            return suffix;
        }
        
        // 2. 尝试解码样板获取配方信息
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, level);
            if (details == null) {
                return null;
            }
            
            // 尝试从样板详情获取更多信息
            // 注意：IPatternDetails接口不直接提供配方类型信息
            // 使用推导方法作为回退
            return RecipeTypeNameConfig.deriveSearchKeyFromUnknownRecipe(details);
            
        } catch (Exception e) {
            LOGGER.error("解码样板获取配方类型失败", e);
            return null;
        }
    }
    
    /**
     * 查找匹配的样板供应器
     * 基于供应器组名匹配配方类型搜索键
     */
    public static List<PatternContainer> findMatchedProviders(IGrid grid, String recipeSearchKey) {
        List<PatternContainer> matched = new ArrayList<>();
        if (grid == null || recipeSearchKey == null || recipeSearchKey.isBlank()) {
            return matched;
        }
        
        try {
            List<PatternContainer> allProviders = PatternTerminalUtil.listAvailableProvidersFromGrid(grid);
            for (PatternContainer provider : allProviders) {
                if (provider == null || !provider.isVisibleInTerminal()) {
                    continue;
                }
                
                // 获取供应器显示名
                String providerName = PatternProviderDataUtil.getProviderDisplayName(provider);
                if (providerName == null || providerName.isBlank()) {
                    continue;
                }
                
                // 检查供应器名称是否包含配方类型搜索键
                // 注意：providerName可能是Component序列化的JSON，我们需要解析它
                try {
                    // 尝试解析Component
                    Component component = Component.Serializer.fromJson(providerName);
                    if (component != null) {
                        String plainName = component.getString();
                        if (plainName.contains(recipeSearchKey)) {
                            matched.add(provider);
                        }
                    } else {
                        // 如果不是JSON，直接检查字符串
                        if (providerName.contains(recipeSearchKey)) {
                            matched.add(provider);
                        }
                    }
                } catch (Exception e) {
                    // 解析失败，直接检查字符串
                    if (providerName.contains(recipeSearchKey)) {
                        matched.add(provider);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("查找匹配的供应器失败", e);
        }
        
        return matched;
    }
    
    /**
     * 上传样板到匹配的供应器
     * 返回true表示至少成功上传了一个样板
     */
    public static boolean uploadPatternToMatchedProviders(PatternRouterBlockEntity router, ItemStack pattern) {
        if (router == null || pattern.isEmpty() || !PatternDetailsHelper.isEncodedPattern(pattern)) {
            return false;
        }
        
        Level level = router.getLevel();
        if (level == null || level.isClientSide()) {
            return false;
        }
        
        ServerLevel serverLevel = (ServerLevel) level;
        
        // 1. 获取配方类型搜索键
        String recipeSearchKey = getRecipeSearchKey(pattern, level);
        if (recipeSearchKey == null || recipeSearchKey.isBlank()) {
            LOGGER.warn("无法获取样板的配方类型搜索键");
            return false;
        }
        
        // 2. 获取网格
        IGrid grid = router.getMainNode().getGrid();
        if (grid == null) {
            LOGGER.warn("样板路由器未连接到网格");
            return false;
        }
        
        // 3. 查找匹配的供应器
        List<PatternContainer> matchedProviders = findMatchedProviders(grid, recipeSearchKey);
        if (matchedProviders.isEmpty()) {
            LOGGER.warn("未找到匹配 '{}' 的供应器", recipeSearchKey);
            return false;
        }
        
        LOGGER.info("找到 {} 个匹配 '{}' 的供应器", matchedProviders.size(), recipeSearchKey);
        
        // 4. 尝试上传到每个匹配的供应器
        boolean success = false;
        ItemStack remaining = pattern.copy();
        
        for (PatternContainer provider : matchedProviders) {
            if (remaining.isEmpty()) {
                break;
            }
            
            InternalInventory inv = provider.getTerminalPatternInventory();
            if (inv == null || inv.size() <= 0) {
                continue;
            }
            
            // 检查是否有空槽位
            boolean hasEmptySlot = false;
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStackInSlot(i).isEmpty()) {
                    hasEmptySlot = true;
                    break;
                }
            }
            
            if (!hasEmptySlot) {
                continue;
            }
            
            // 尝试插入样板
            ItemStack nextRemaining = new FilteredInternalInventory(inv, new ExtendedAEPatternFilter()).addItems(remaining.copy());
            if (nextRemaining.getCount() < remaining.getCount()) {
                success = true;
                remaining = nextRemaining;
                LOGGER.info("样板成功插入供应器");
                
                if (remaining.isEmpty()) {
                    break;
                }
            }
        }
        
        return success;
    }
    
    /**
     * ExtendedAE Pattern Filter（从ProviderUploadUtil复制）
     */
    private static class ExtendedAEPatternFilter implements appeng.util.inv.filter.IAEItemFilter {
        @Override
        public boolean allowExtract(InternalInventory inv, int slot, int amount) {
            return true;
        }

        @Override
        public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
            return !stack.isEmpty() && PatternDetailsHelper.isEncodedPattern(stack);
        }
    }
}
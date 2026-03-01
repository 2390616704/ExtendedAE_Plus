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
import appeng.util.inv.filter.IAEItemFilter;
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
import java.util.Comparator;
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
     * 注意：样板路由器只支持单个搜索键，用于精确匹配目标供应器
     */
    @Nullable
    public static String extractRecipeTypeSuffix(ItemStack pattern) {
        if (pattern.isEmpty() || !pattern.hasCustomHoverName()) {
            LOGGER.debug("[样板路由器] 提取后缀失败：样板为空或无自定义名称");
            return null;
        }
        
        String name = pattern.getHoverName().getString();
        LOGGER.debug("[样板路由器] 样板名称：{}", name);
        
        if (name == null || name.isBlank()) {
            LOGGER.debug("[样板路由器] 样板名称为空");
            return null;
        }
        
        // 使用下划线分隔符
        int separatorIndex = name.lastIndexOf("_");
        LOGGER.debug("[样板路由器] 下划线位置：{}", separatorIndex);
        
        if (separatorIndex == -1) {
            LOGGER.debug("[样板路由器] 未找到下划线分隔符");
            return null;
        }
        
        String suffix = name.substring(separatorIndex + 1).trim();
        LOGGER.debug("[样板路由器] 提取的后缀：{}", suffix);
        
        return suffix.isBlank() ? null : suffix;
    }
    
    /**
     * 从编码样板获取配方类型搜索键
     * 优先从样板名称提取，如果失败则尝试解码样板
     */
    @Nullable
    public static String getRecipeSearchKey(ItemStack pattern, Level level) {
        if (pattern.isEmpty() || level == null) {
            LOGGER.warn("[样板路由器] 获取搜索键失败：参数无效");
            return null;
        }
        
        String patternName = pattern.hasCustomHoverName() ? pattern.getHoverName().getString() : "无名称";
        LOGGER.info("[样板路由器] 开始获取搜索键：pattern={}", patternName);
        
        // 1. 优先从样板名称提取后缀
        String suffix = extractRecipeTypeSuffix(pattern);
        if (suffix != null) {
            LOGGER.info("[样板路由器] 从名称提取后缀成功：{}", suffix);
            return suffix;
        }
        
        LOGGER.info("[样板路由器] 无法从名称提取后缀，尝试解码样板");
        
        // 2. 尝试解码样板获取配方信息
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(pattern, level);
            if (details == null) {
                LOGGER.warn("[样板路由器] 解码样板失败：details为null");
                return null;
            }
            
            LOGGER.info("[样板路由器] 解码成功，details类型：{}", details.getClass().getSimpleName());
            
            // 尝试从样板详情获取更多信息
            // 注意：IPatternDetails接口不直接提供配方类型信息
            // 使用推导方法作为回退
            String derivedKey = RecipeTypeNameConfig.deriveSearchKeyFromUnknownRecipe(details);
            LOGGER.info("[样板路由器] 推导搜索键：{}", derivedKey);
            return derivedKey;
            
        } catch (Exception e) {
            LOGGER.error("[样板路由器] 解码样板获取配方类型失败", e);
            return null;
        }
    }
    
    /**
     * 检查供应器名称是否匹配搜索键
     * 匹配规则：供应器名称包含搜索键（大小写不敏感）
     */
    private static boolean providerNameMatches(String providerName, String recipeSearchKey) {
        if (providerName == null || providerName.isBlank() || recipeSearchKey == null || recipeSearchKey.isBlank()) {
            return false;
        }
        
        // 样板路由器只支持单个搜索键
        String searchKey = recipeSearchKey.trim();
        if (searchKey.isEmpty()) {
            return false;
        }
        
        // 尝试解析Component JSON
        String plainName = providerName;
        try {
            Component component = Component.Serializer.fromJson(providerName);
            if (component != null) {
                plainName = component.getString();
            }
        } catch (Exception e) {
            // 不是JSON格式，使用原始字符串
        }
        
        // 大小写不敏感匹配
        return plainName.toLowerCase().contains(searchKey.toLowerCase());
    }
    
    /**
     * 查找匹配的样板供应器
     * 基于供应器组名匹配配方类型搜索键
     * 样板路由器需要精确匹配，找到名称包含搜索键的供应器
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
                
                // 检查供应器名称是否匹配搜索键
                if (providerNameMatches(providerName, recipeSearchKey)) {
                    matched.add(provider);
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
            LOGGER.warn("[样板路由器] 上传失败：参数无效 router={}, pattern={}, isEncoded={}", 
                router == null, pattern.isEmpty(), PatternDetailsHelper.isEncodedPattern(pattern));
            return false;
        }
        
        Level level = router.getLevel();
        if (level == null || level.isClientSide()) {
            LOGGER.warn("[样板路由器] 上传失败：level无效 level={}, isClientSide={}", level, level != null && level.isClientSide());
            return false;
        }
        
        ServerLevel serverLevel = (ServerLevel) level;
        
        // 1. 获取配方类型搜索键
        String recipeSearchKey = getRecipeSearchKey(pattern, level);
        LOGGER.info("[样板路由器] 获取搜索键：pattern={}, searchKey={}", 
            pattern.getHoverName().getString(), recipeSearchKey);
        
        if (recipeSearchKey == null || recipeSearchKey.isBlank()) {
            LOGGER.warn("[样板路由器] 无法获取样板的配方类型搜索键 pattern={}", pattern.getHoverName().getString());
            return false;
        }
        
        // 2. 获取网格
        IGrid grid = router.getMainNode().getGrid();
        if (grid == null) {
            LOGGER.warn("[样板路由器] 样板路由器未连接到网格");
            return false;
        }
        
        // 3. 查找匹配的供应器
        List<PatternContainer> matchedProviders = findMatchedProviders(grid, recipeSearchKey);
        LOGGER.info("[样板路由器] 找到 {} 个匹配 '{}' 的供应器", matchedProviders.size(), recipeSearchKey);
        
        if (matchedProviders.isEmpty()) {
            LOGGER.warn("[样板路由器] 未找到匹配 '{}' 的供应器", recipeSearchKey);
            return false;
        }
        
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
    private static class ExtendedAEPatternFilter implements IAEItemFilter {
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
package com.extendedae_plus.client.event;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.extendedae_plus.ExtendedAEPlus;
import com.extendedae_plus.client.ModKeybindings;
import com.extendedae_plus.init.ModNetwork;
import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
import com.extendedae_plus.network.pattern.CreateCtrlQPatternC2SPacket;
import com.extendedae_plus.util.RecipeFinderUtil;
import com.extendedae_plus.util.uploadPattern.RecipeTypeNameConfig;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ctrl+Q快捷创建样板事件监听器
 */
@Mod.EventBusSubscriber(modid = ExtendedAEPlus.MODID, value = Dist.CLIENT)
public class CtrlQPatternKeyHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ExtendedAE Plus - CtrlQ");
    private static boolean ctrlQKeyHeld;

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();

        if (!ModKeybindings.CREATE_PATTERN_KEY.matches(keyCode, scanCode)) {
            return;
        }
        // Guard against GLFW key-repeat while key is still held down.
        if (ctrlQKeyHeld) {
            event.setCanceled(true);
            return;
        }
        ctrlQKeyHeld = true;

        if (JeiRuntimeProxy.get() == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Optional<ITypedIngredient<?>> ingredient = JeiRuntimeProxy.getIngredientUnderMouse();
        Optional<com.extendedae_plus.integration.jei.JeiRecipeBookmarkContext> recipeBookmark =
            JeiRuntimeProxy.getRecipeBookmarkContextUnderMouse();

        if (ingredient.isEmpty() && recipeBookmark.isEmpty()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.translatable("message.extendedae_plus.hover_item_first"),
                    true
                );
            }
            return;
        }

        Recipe<?> selectedRecipe = null;
        if (recipeBookmark.isPresent()) {
            selectedRecipe = RecipeFinderUtil.findRecipeById(mc.level, recipeBookmark.get().recipeId());
        }

        if (selectedRecipe == null && ingredient.isPresent()) {
            List<Recipe<?>> recipes = RecipeFinderUtil.findRecipesByIngredient(ingredient.get(), mc.level);
            if (recipes.isEmpty() && recipeBookmark.isPresent()) {
                recipes = RecipeFinderUtil.findRecipesByOutputItem(recipeBookmark.get().outputPreview(), mc.level);
            }
            if (recipes.isEmpty()) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                        Component.translatable("message.extendedae_plus.no_recipes_found"),
                        true
                    );
                }
                return;
            }
            selectedRecipe = RecipeFinderUtil.selectBestRecipe(recipes);
            if (selectedRecipe == null) {
                return;
            }
        }

        if (selectedRecipe == null && recipeBookmark.isPresent()) {
            List<Recipe<?>> recipes = RecipeFinderUtil.findRecipesByOutputItem(recipeBookmark.get().outputPreview(), mc.level);
            selectedRecipe = RecipeFinderUtil.selectBestRecipe(recipes);
        }

        if (selectedRecipe == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                    Component.translatable("message.extendedae_plus.no_recipes_found"),
                    true
                );
            }
            return;
        }

        boolean fromRecipeTypeBookmark = recipeBookmark.isPresent();
        boolean isCraftingPattern = selectedRecipe instanceof CraftingRecipe;
        List<ItemStack> selectedIngredients;
        if (recipeBookmark.isPresent() && !recipeBookmark.get().recipeInputs().isEmpty()) {
            selectedIngredients = new ArrayList<>(recipeBookmark.get().recipeInputs());
        } else {
            selectedIngredients = selectIngredientsWithJeiPriority(selectedRecipe);
        }

        if (fromRecipeTypeBookmark && !isMatrixRecipeType(selectedRecipe)) {
            String searchKey = RecipeTypeNameConfig.mapRecipeTypeToSearchKey(selectedRecipe);
            if ((searchKey == null || searchKey.isBlank()) && recipeBookmark.isPresent()) {
                searchKey = RecipeTypeNameConfig.mapRecipeTypeIdToSearchKey(recipeBookmark.get().recipeTypeId());
            }
            if (searchKey != null && !searchKey.isBlank()) {
                RecipeTypeNameConfig.setLastProcessingName(searchKey);
            }
        }

        ModNetwork.CHANNEL.sendToServer(new CreateCtrlQPatternC2SPacket(
            selectedRecipe.getId(),
            isCraftingPattern,
            selectedIngredients,
            fromRecipeTypeBookmark
        ));

        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (ModKeybindings.CREATE_PATTERN_KEY.matches(event.getKeyCode(), event.getScanCode())) {
            ctrlQKeyHeld = false;
        }
    }

    private static boolean isMatrixRecipeType(Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe
            || recipe instanceof StonecutterRecipe
            || recipe instanceof SmithingRecipe;
    }

    private static List<ItemStack> selectIngredientsWithJeiPriority(Recipe<?> recipe) {
        List<? extends ITypedIngredient<?>> bookmarks = JeiRuntimeProxy.getBookmarkList();
        Map<AEKey, Integer> priorities = new HashMap<>();
        AtomicInteger index = new AtomicInteger(Integer.MAX_VALUE);

        LOGGER.info("[CTRL+Q] ========== 开始选择原材料 ==========");
        LOGGER.info("[CTRL+Q] 配方: {}", recipe.getId());
        LOGGER.info("[CTRL+Q] 书签数量: {}", bookmarks.size());

        for (ITypedIngredient<?> ingredient : bookmarks) {
            ingredient.getIngredient(VanillaTypes.ITEM_STACK).ifPresent(itemStack -> {
                AEKey key = AEItemKey.of(itemStack);
                int priority = index.getAndDecrement();
                priorities.put(key, priority);
                LOGGER.info("[CTRL+Q]   书签: {} -> 优先级: {}",
                    itemStack.getHoverName().getString(), priority);
            });
        }

        LOGGER.info("[CTRL+Q] 优先级映射完成，共 {} 个物品", priorities.size());

        List<ItemStack> selected = new ArrayList<>();
        int ingredientIndex = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            LOGGER.info("[CTRL+Q] --- 原料槽 {} ---", ingredientIndex++);

            if (ingredient.isEmpty()) {
                selected.add(ItemStack.EMPTY);
                LOGGER.info("[CTRL+Q]   空原料，跳过");
                continue;
            }

            ItemStack[] items = ingredient.getItems();
            if (items.length == 0) {
                selected.add(ItemStack.EMPTY);
                LOGGER.info("[CTRL+Q]   无可选物品，跳过");
                continue;
            }

            LOGGER.info("[CTRL+Q]   可选物品数量: {}", items.length);
            for (int i = 0; i < items.length; i++) {
                AEKey key = AEItemKey.of(items[i]);
                int priority = priorities.getOrDefault(key, Integer.MAX_VALUE);
                LOGGER.info("[CTRL+Q]     [{}] {} -> 优先级: {}",
                    i, items[i].getHoverName().getString(), priority);
            }

            ItemStack best = items[0];
            int bestPriority = Integer.MAX_VALUE;
            AEKey firstKey = AEItemKey.of(best);
            if (priorities.containsKey(firstKey)) {
                bestPriority = priorities.get(firstKey);
            }

            for (int i = 1; i < items.length; i++) {
                AEKey key = AEItemKey.of(items[i]);
                int priority = priorities.getOrDefault(key, Integer.MAX_VALUE);
                if (priority < bestPriority) {
                    bestPriority = priority;
                    best = items[i];
                    LOGGER.info("[CTRL+Q]     更新最佳: {} (优先级: {})",
                        best.getHoverName().getString(), priority);
                }
            }

            LOGGER.info("[CTRL+Q]   ✓ 最终选择: {} (优先级: {})",
                best.getHoverName().getString(), bestPriority);
            selected.add(best.copy());
        }

        LOGGER.info("[CTRL+Q] ========== 原材料选择完成 ==========");
        return selected;
    }
}

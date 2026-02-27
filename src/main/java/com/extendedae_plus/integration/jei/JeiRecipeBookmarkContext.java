package com.extendedae_plus.integration.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI recipe bookmark context captured from bookmark overlay hover.
 */
public record JeiRecipeBookmarkContext(
    ResourceLocation recipeId,
    ResourceLocation recipeTypeId,
    ItemStack outputPreview,
    List<ItemStack> recipeInputs
) {
    public JeiRecipeBookmarkContext {
        outputPreview = outputPreview == null ? ItemStack.EMPTY : outputPreview.copy();
        List<ItemStack> normalized = new ArrayList<>();
        if (recipeInputs != null) {
            for (ItemStack input : recipeInputs) {
                if (input != null && !input.isEmpty()) {
                    normalized.add(input.copy());
                }
            }
        }
        recipeInputs = List.copyOf(normalized);
    }
}

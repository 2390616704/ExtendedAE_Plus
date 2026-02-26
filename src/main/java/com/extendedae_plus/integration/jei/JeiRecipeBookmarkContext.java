package com.extendedae_plus.integration.jei;

import net.minecraft.resources.ResourceLocation;

/**
 * JEI recipe bookmark context captured from bookmark overlay hover.
 */
public record JeiRecipeBookmarkContext(ResourceLocation recipeId, ResourceLocation recipeTypeId) {
}

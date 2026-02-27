package com.extendedae_plus.network.pattern;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.core.definitions.AEItems;
import appeng.items.tools.powered.WirelessCraftingTerminalItem;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.me.helpers.PlayerSource;
import com.extendedae_plus.util.uploadPattern.MatrixUploadUtil;
import com.extendedae_plus.util.uploadPattern.ProviderUploadUtil;
import com.extendedae_plus.util.wireless.WirelessTerminalLocator;
import de.mari_023.ae2wtlib.terminal.WTMenuHost;
import de.mari_023.ae2wtlib.wut.WTDefinition;
import de.mari_023.ae2wtlib.wut.WUTHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.network.NetworkEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * C2S: Ctrl+Q 快速创建样板。
 */
public class CreateCtrlQPatternC2SPacket {
    private static final String LAST_CTRLQ_HASH_KEY = "eap_ctrlq_last_request_hash";
    private static final String LAST_CTRLQ_TICK_KEY = "eap_ctrlq_last_request_tick";

    private final ResourceLocation recipeId;
    private final boolean isCraftingPattern;
    private final List<ItemStack> selectedIngredients;
    private final boolean fromRecipeTypeBookmark;

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients) {
        this(recipeId, isCraftingPattern, selectedIngredients, false);
    }

    public CreateCtrlQPatternC2SPacket(ResourceLocation recipeId, boolean isCraftingPattern, List<ItemStack> selectedIngredients, boolean fromRecipeTypeBookmark) {
        this.recipeId = recipeId;
        this.isCraftingPattern = isCraftingPattern;
        this.selectedIngredients = selectedIngredients;
        this.fromRecipeTypeBookmark = fromRecipeTypeBookmark;
    }

    public static void encode(CreateCtrlQPatternC2SPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.recipeId);
        buf.writeBoolean(msg.isCraftingPattern);
        buf.writeInt(msg.selectedIngredients.size());
        for (ItemStack stack : msg.selectedIngredients) {
            buf.writeItem(stack);
        }
        buf.writeBoolean(msg.fromRecipeTypeBookmark);
    }

    public static CreateCtrlQPatternC2SPacket decode(FriendlyByteBuf buf) {
        ResourceLocation recipeId = buf.readResourceLocation();
        boolean isCraftingPattern = buf.readBoolean();
        int count = buf.readInt();
        List<ItemStack> ingredients = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ingredients.add(buf.readItem());
        }
        boolean fromRecipeTypeBookmark = buf.readBoolean();
        return new CreateCtrlQPatternC2SPacket(recipeId, isCraftingPattern, ingredients, fromRecipeTypeBookmark);
    }

    public static void handle(CreateCtrlQPatternC2SPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        var ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }

            RecipeManager recipeManager = player.level().getRecipeManager();
            var recipeOpt = recipeManager.byKey(msg.recipeId);
            if (recipeOpt.isEmpty()) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.recipe_not_found"), false);
                return;
            }
            Recipe<?> recipe = recipeOpt.get();

            if (isDuplicateRequest(player, msg)) {
                return;
            }

            if (!consumeBlankPattern(player)) {
                player.displayClientMessage(Component.translatable("message.extendedae_plus.no_blank_pattern"), false);
                return;
            }

            ItemStack pattern = createPattern(recipe, msg.isCraftingPattern, msg.selectedIngredients, player);
            if (pattern.isEmpty()) {
                player.getInventory().add(AEItems.BLANK_PATTERN.stack());
                player.displayClientMessage(Component.translatable("message.extendedae_plus.pattern_creation_failed"), false);
                return;
            }

            if (msg.fromRecipeTypeBookmark) {
                handleRecipeBookmarkFlow(player, recipe, pattern);
                return;
            }

            if (!player.getInventory().add(pattern)) {
                player.drop(pattern, false);
            }
        });
        ctx.setPacketHandled(true);
    }

    private static void handleRecipeBookmarkFlow(ServerPlayer player, Recipe<?> recipe, ItemStack pattern) {
        if (isMatrixRecipeType(recipe)) {
            MatrixUploadUtil.MatrixUploadStatus status = MatrixUploadUtil.uploadPatternToMatrixFromPlayerNetwork(player, pattern.copy());
            if (status == MatrixUploadUtil.MatrixUploadStatus.SUCCESS) {
                return;
            }

            if (status == MatrixUploadUtil.MatrixUploadStatus.NO_MATRIX
                || status == MatrixUploadUtil.MatrixUploadStatus.NO_NETWORK) {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.fail_no_matrix"), false);
            } else if (status == MatrixUploadUtil.MatrixUploadStatus.DUPLICATE) {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.repetition"), false);
            } else {
                player.displayClientMessage(Component.translatable("extendedae_plus.upload_to_matrix.fail_full"), false);
            }
            return;
        }

        String pendingId = ProviderUploadUtil.beginPendingCtrlQUpload(player, pattern);
        if (pendingId == null) {
            player.displayClientMessage(Component.translatable("message.extendedae_plus.pattern_creation_failed"), false);
            return;
        }

        if (!ProviderUploadUtil.openProviderSelectionForPlayerNetwork(player)) {
            ProviderUploadUtil.clearPendingCtrlQUpload(player);
            player.displayClientMessage(Component.translatable("message.extendedae_plus.no_provider_found"), false);
        }
    }

    private static boolean isMatrixRecipeType(Recipe<?> recipe) {
        return recipe instanceof CraftingRecipe
            || recipe instanceof StonecutterRecipe
            || recipe instanceof SmithingRecipe;
    }

    private static boolean isDuplicateRequest(ServerPlayer player, CreateCtrlQPatternC2SPacket msg) {
        long now = player.level().getGameTime();
        int hash = computeRequestHash(msg);

        var data = player.getPersistentData();
        long lastTick = data.getLong(LAST_CTRLQ_TICK_KEY);
        int lastHash = data.getInt(LAST_CTRLQ_HASH_KEY);

        data.putLong(LAST_CTRLQ_TICK_KEY, now);
        data.putInt(LAST_CTRLQ_HASH_KEY, hash);

        return hash == lastHash && now - lastTick <= 1;
    }

    private static int computeRequestHash(CreateCtrlQPatternC2SPacket msg) {
        int hash = 17;
        hash = 31 * hash + msg.recipeId.hashCode();
        hash = 31 * hash + (msg.isCraftingPattern ? 1 : 0);
        hash = 31 * hash + msg.selectedIngredients.size();
        for (ItemStack stack : msg.selectedIngredients) {
            hash = 31 * hash + computeStackHash(stack);
        }
        // Intentionally ignore fromRecipeTypeBookmark in dedupe to collapse mixed duplicate paths.
        return hash;
    }

    private static int computeStackHash(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int hash = 17;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        hash = 31 * hash + (itemId == null ? 0 : itemId.hashCode());
        hash = 31 * hash + stack.getCount();
        hash = 31 * hash + (stack.getTag() == null ? 0 : stack.getTag().hashCode());
        return hash;
    }

    private static boolean consumeBlankPattern(ServerPlayer player) {
        if (tryExtractFromNetwork(player)) {
            return true;
        }

        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(AEItems.BLANK_PATTERN.asItem())) {
                stack.shrink(1);
                return true;
            }
        }

        return false;
    }

    private static boolean tryExtractFromNetwork(ServerPlayer player) {
        WirelessTerminalLocator.LocatedTerminal located = WirelessTerminalLocator.find(player);
        ItemStack terminal = located.stack;
        if (terminal.isEmpty()) {
            return false;
        }

        IGrid grid;
        boolean usedWtHost;

        String curiosSlotId = located.getCuriosSlotId();
        int curiosIndex = located.getCuriosIndex();

        if (curiosSlotId != null && curiosIndex >= 0) {
            try {
                String current = WUTHandler.getCurrentTerminal(terminal);
                WTDefinition def = WUTHandler.wirelessTerminals.get(current);
                if (def == null) {
                    return false;
                }
                WTMenuHost wtHost = def.wTMenuHostFactory().create(player, null, terminal, (p, sub) -> {
                });
                if (wtHost == null) {
                    return false;
                }
                var node = wtHost.getActionableNode();
                if (node == null) {
                    return false;
                }
                grid = node.getGrid();
                if (grid == null || !wtHost.drainPower()) {
                    return false;
                }
                usedWtHost = true;
            } catch (Exception e) {
                return false;
            }
        } else {
            WirelessCraftingTerminalItem wct = terminal.getItem() instanceof WirelessCraftingTerminalItem c ? c : null;
            WirelessTerminalItem wt = wct != null ? wct : (terminal.getItem() instanceof WirelessTerminalItem t ? t : null);
            if (wt == null) {
                return false;
            }
            grid = wt.getLinkedGrid(terminal, player.serverLevel(), player);
            if (grid == null || !wt.hasPower(player, 0.5, terminal)) {
                return false;
            }
            usedWtHost = false;
        }

        AEItemKey blankPatternKey = AEItemKey.of(AEItems.BLANK_PATTERN.stack());
        IEnergyService energy = grid.getEnergyService();
        MEStorage storage = grid.getStorageService().getInventory();
        long extracted = StorageHelper.poweredExtraction(energy, storage, blankPatternKey, 1, new PlayerSource(player));

        if (extracted <= 0) {
            return false;
        }

        if (!usedWtHost) {
            WirelessCraftingTerminalItem wct2 = terminal.getItem() instanceof WirelessCraftingTerminalItem c2 ? c2 : null;
            WirelessTerminalItem wt2 = wct2 != null ? wct2 : (terminal.getItem() instanceof WirelessTerminalItem t2 ? t2 : null);
            if (wt2 != null) {
                wt2.usePower(player, 0.5, terminal);
            }
        }
        located.commit();
        return true;
    }

    private static ItemStack createPattern(Recipe<?> recipe, boolean isCrafting, List<ItemStack> selectedIngredients, ServerPlayer player) {
        try {
            if (isCrafting && recipe instanceof CraftingRecipe) {
                ItemStack[] inputs = new ItemStack[9];
                for (int i = 0; i < 9; i++) {
                    inputs[i] = i < selectedIngredients.size() ? selectedIngredients.get(i).copy() : ItemStack.EMPTY;
                }
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                ItemStack encoded = invokePatternEncode(
                    "encodeCraftingPattern",
                    recipe,
                    inputs,
                    output,
                    true,
                    false
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            if (recipe instanceof StonecutterRecipe) {
                ItemStack input = selectedIngredients.isEmpty() ? ItemStack.EMPTY : selectedIngredients.get(0);
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                if (input.isEmpty() || output.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack encoded = invokePatternEncode(
                    "encodeStonecuttingPattern",
                    recipe,
                    AEItemKey.of(input),
                    AEItemKey.of(output),
                    true
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            if (recipe instanceof SmithingRecipe) {
                ItemStack template = selectedIngredients.size() > 0 ? selectedIngredients.get(0) : ItemStack.EMPTY;
                ItemStack base = selectedIngredients.size() > 1 ? selectedIngredients.get(1) : ItemStack.EMPTY;
                ItemStack addition = selectedIngredients.size() > 2 ? selectedIngredients.get(2) : ItemStack.EMPTY;
                ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();
                if (template.isEmpty() || base.isEmpty() || addition.isEmpty() || output.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack encoded = invokePatternEncode(
                    "encodeSmithingTablePattern",
                    recipe,
                    AEItemKey.of(template),
                    AEItemKey.of(base),
                    AEItemKey.of(addition),
                    AEItemKey.of(output),
                    true
                );
                if (!encoded.isEmpty()) {
                    encoded.getOrCreateTag().putString("encodePlayer", player.getName().getString());
                }
                return encoded;
            }

            List<GenericStack> inputs = new ArrayList<>();
            List<GenericStack> outputs = new ArrayList<>();
            for (ItemStack item : selectedIngredients) {
                if (!item.isEmpty()) {
                    inputs.add(new GenericStack(AEItemKey.of(item), item.getCount()));
                }
            }

            ItemStack result = recipe.getResultItem(player.level().registryAccess());
            if (!result.isEmpty()) {
                outputs.add(new GenericStack(AEItemKey.of(result), result.getCount()));
            }

            ItemStack encodedPattern = PatternDetailsHelper.encodeProcessingPattern(
                inputs.toArray(new GenericStack[0]),
                outputs.toArray(new GenericStack[0])
            );
            encodedPattern.getOrCreateTag().putString("encodePlayer", player.getName().getString());
            return encodedPattern;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack invokePatternEncode(String methodName, Recipe<?> recipe, Object... args) {
        try {
            for (Method method : PatternDetailsHelper.class.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length + 1) {
                    continue;
                }
                Object recipeArg = adaptRecipeArgument(parameterTypes[0], recipe);
                if (recipeArg == null) {
                    continue;
                }

                Object[] invokeArgs = new Object[parameterTypes.length];
                invokeArgs[0] = recipeArg;
                System.arraycopy(args, 0, invokeArgs, 1, args.length);

                Object result = method.invoke(null, invokeArgs);
                if (result instanceof ItemStack stack) {
                    return stack;
                }
            }
        } catch (Throwable ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static Object adaptRecipeArgument(Class<?> expectedType, Recipe<?> recipe) {
        if (expectedType.isInstance(recipe)) {
            return recipe;
        }
        if (!"net.minecraft.world.item.crafting.RecipeHolder".equals(expectedType.getName())) {
            return null;
        }

        try {
            for (Constructor<?> ctor : expectedType.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 2) {
                    continue;
                }
                if (ResourceLocation.class.isAssignableFrom(params[0]) && params[1].isInstance(recipe)) {
                    ctor.setAccessible(true);
                    return ctor.newInstance(recipe.getId(), recipe);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

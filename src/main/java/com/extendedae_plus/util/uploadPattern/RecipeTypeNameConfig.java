package com.extendedae_plus.util.uploadPattern;

import com.extendedae_plus.integration.jei.JeiRuntimeProxy;
import com.google.gson.*;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.extendedae_plus.util.GlobalSendMessage.sendPlayerMessage;
import static com.extendedae_plus.util.Logger.EAP$LOGGER;

/**
 * 负责配置文件 extendedae_plus/recipe_type_names.json 的加载与写入，
 * 以及 recipeType -> 中文名称 / 搜索关键字 的映射逻辑。
 */
public final class RecipeTypeNameConfig {
    private static final String CONFIG_PATH = "extendedae_plus/recipe_type_names.json";
    private static final Map<ResourceLocation, String> CUSTOM_NAMES = new ConcurrentHashMap<>();
    // 允许使用最终搜索关键字（通常为 path 或自定义短语）作为键，例如："assembler": "组装机"
    private static final Map<String, String> CUSTOM_ALIASES = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    // 缓存工作方块映射，避免重复遍历 JEI 类别
    private static volatile Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> WORKSTATION_MAPPING_CACHE = null;
    private static volatile long CACHE_TIMESTAMP = 0;
    private static final long CACHE_VALIDITY_MS = 30000; // 30 秒有效期

    static {
        try {
            loadRecipeTypeNames();
        } catch (Throwable t) {
            EAP$LOGGER.warn("ExtendedAE_Plus: 映射文件解析失败, {}", t.getMessage());
        }
    }

    private RecipeTypeNameConfig() {}

    // 最近通过 JEI 填充到编码终端的处理配方的搜索键列表，用逗号分隔
    // 例如："熔炉,高炉,组装机"
    public static volatile String lastProcessingName = "分子装配室,熔炉";//比填写null强
    public static void setLastProcessingName(String name) {
        lastProcessingName = name;
    }
    
    /**
     * 追加搜索键到lastProcessingName，用逗号分隔
     * 如果name已存在，则不重复添加
     */
    public static void appendLastProcessingName(String name) {
        if (name == null || name.isBlank()) {
            return;
        }

        String trimmedName = name.trim();

        if (lastProcessingName == null || lastProcessingName.isBlank()) {
            lastProcessingName = trimmedName;
            return;
        }

        // 分割现有的搜索键
        String[] existingKeys = lastProcessingName.split(",");
        for (String existingKey : existingKeys) {
            if (existingKey.trim().equals(trimmedName)) {
                // 已存在，不重复添加
                return;
            }
        }

        // 追加新的搜索键（无长度限制）
        lastProcessingName = lastProcessingName + "," + trimmedName;
    }

    /**
     * 生成默认的配方类型映射，用于配置文件模板。
     *
     * @return 默认映射
     */
    private static Map<String, String> getDefaultMappings() {
        Map<String, String> mappings = new HashMap<>();
        // 添加原版和常见模组的默认映射
        mappings.put("minecraft:smelting", "熔炉");
        mappings.put("minecraft:blasting", "高炉");
        mappings.put("minecraft:smoking", "烟熏");
        mappings.put("minecraft:campfire_cooking", "营火");
        mappings.put("gtceu:assembler", "组装机");
        mappings.put("assembler", "组装机");
        return mappings;
    }

    /**
     * 创建默认配置文件模板。
     *
     * @return 默认的 JSON 对象
     */
    private static JsonObject createDefaultTemplate() {
        JsonObject tmpl = new JsonObject();
        // 将默认映射写入 JSON
        getDefaultMappings().forEach(tmpl::addProperty);
        return tmpl;
    }

    /**
     * 加载 JSON 配置文件，若文件不存在返回空对象。
     *
     * @param cfgPath 文件路径
     * @return JSON 对象
     * @throws IOException         如果文件读取失败
     * @throws JsonSyntaxException 如果 JSON 解析失败
     */
    private static JsonObject loadJsonConfig(Path cfgPath) throws IOException, JsonSyntaxException {
        // 文件不存在返回空对象
        if (!Files.exists(cfgPath)) return new JsonObject();
        String json = Files.readString(cfgPath);
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        // 确保返回非 null 对象
        return obj != null ? obj : new JsonObject();
    }

    /**
     * 保存 JSON 配置到文件。
     *
     * @param cfgPath 文件路径
     * @param config  JSON 对象
     * @throws IOException 如果文件写入失败
     */
    private static void saveJsonConfig(Path cfgPath, JsonObject config) throws IOException {
        Files.createDirectories(cfgPath.getParent());
        // 写入格式化 JSON
        Files.writeString(cfgPath, GSON.toJson(config));
    }

    /**
     * 加载配方类型名称映射。如果配置文件不存在，则生成默认模板。
     * 支持 ResourceLocation 格式（namespace:path）和别名格式（仅 path）。
     *
     * @throws IOException 如果文件读写失败
     */
    public static synchronized void loadRecipeTypeNames() throws IOException {
        // 获取配置文件路径
        Path cfgPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_PATH);
        JsonObject config = loadJsonConfig(cfgPath);
        if (config.entrySet().isEmpty()) {
            // 文件为空或不存在时生成默认模板
            config = createDefaultTemplate();
            saveJsonConfig(cfgPath, config);
        }

        Map<ResourceLocation, String> nameMap = new HashMap<>();
        Map<String, String> alias = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                String name = value.getAsString();
                if (name == null || name.isBlank()) continue;
                if (key.contains(":")) {
                    try {
                        // 解析完整 ID
                        ResourceLocation rl = new ResourceLocation(key);
                        nameMap.put(rl, name);
                    } catch (Exception ignored) {}
                } else {
                    // 存入别名映射（小写）
                    alias.put(key.toLowerCase(), name);
                }
            }
        }

        CUSTOM_NAMES.clear();
        // 批量更新 ResourceLocation 映射
        CUSTOM_NAMES.putAll(nameMap);
        CUSTOM_ALIASES.clear();
        // 批量更新别名映射
        CUSTOM_ALIASES.putAll(alias);
    }

    /**
     * 新增或更新别名到名称的映射，并保存到配置文件。
     *
     * @param aliasKey 最终搜索关键字（不含冒号），大小写不敏感
     * @param value  名称
     * @return 是否写入成功
     */
    public static synchronized boolean addOrUpdateAliasMapping(String aliasKey, String value) {
        if (aliasKey == null || aliasKey.isBlank() || value == null || value.isBlank()) {
            return false; // 输入验证
        }
        try {
            Path cfgPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_PATH); // 获取配置文件路径
            JsonObject config = loadJsonConfig(cfgPath); // 加载现有配置
            String key = aliasKey.trim();
            config.addProperty(key, value); // 更新或添加映射
            saveJsonConfig(cfgPath, config); // 保存到文件

            // 更新内存映射
            if (key.contains(":")) {
                try {
                    ResourceLocation rl = new ResourceLocation(key); // 解析完整 ID
                    CUSTOM_NAMES.put(rl, value); // 更新 ResourceLocation 映射
                } catch (Exception ignored) {}
            } else {
                CUSTOM_ALIASES.put(key.toLowerCase(), value); // 更新别名映射（小写）
            }
            return true;
        } catch (IOException | JsonSyntaxException e) {
            sendPlayerMessage(Component.translatable("extendedae_plus.message.config_update_failed", e.getMessage()));            return false;
        }
    }

    /**
     * 按值精确匹配删除映射（支持别名与完整ID）。
     *
     * @param delValue 名称
     * @return 删除的条目数量
     */
    public static synchronized int removeMappingsByCnValue(String delValue) {
        if (delValue == null || delValue.trim().isEmpty()) return 0; // 输入验证
        try {
            Path cfgPath = FMLPaths.CONFIGDIR.get().resolve(CONFIG_PATH); // 获取配置文件路径
            JsonObject config = loadJsonConfig(cfgPath); // 加载现有配置

            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : config.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive() && delValue.equals(value.getAsString())) {
                    toRemove.add(entry.getKey()); // 收集匹配中文名称的键
                }
            }

            if (toRemove.isEmpty()) return 0;

            // 从 JSON 中移除
            toRemove.forEach(config::remove); // 移除匹配的键
            saveJsonConfig(cfgPath, config); // 保存更新后的配置

            // 更新内存映射
            for (String key : toRemove) {
                if (key.contains(":")) {
                    try {
                        ResourceLocation rl = new ResourceLocation(key); // 解析完整 ID
                        if (delValue.equals(CUSTOM_NAMES.get(rl))) {
                            CUSTOM_NAMES.remove(rl); // 移除匹配的 ResourceLocation 映射
                        }
                    } catch (Exception ignored) {}
                } else {
                    String lower = key.toLowerCase();
                    if (delValue.equals(CUSTOM_ALIASES.get(lower))) {
                        CUSTOM_ALIASES.remove(lower); // 移除匹配的别名映射
                    }
                }
            }
            return toRemove.size();
        } catch (IOException | JsonSyntaxException e) {
            sendPlayerMessage(Component.translatable("extendedae_plus.message.config_delete_failed", e.getMessage()));
            return 0;
        }
    }

    /**
     * 映射配方类型到搜索关键字，优先使用别名或自定义名称。
     *
     * @param recipe 配方对象
     * @return 搜索关键字（自定义名称、别名或类型路径），或 null 如果无效
     */
    public static String mapRecipeTypeToSearchKey(Recipe<?> recipe) {
        if (recipe == null) return null;
        RecipeType<?> type = recipe.getType();
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        if (key == null) return type.toString();//至少'能量处理器'配方会走这里
        return mapRecipeTypeIdToSearchKey(key);
    }

    /**
     * 按配方类型ID映射到搜索关键字，优先使用别名或自定义名称。
     *
     * @param key 配方类型ID
     * @return 搜索关键字（自定义名称、别名或类型路径），或 null 如果无效
     */
    public static String mapRecipeTypeIdToSearchKey(ResourceLocation key) {
        if (key == null) return null;
        String path = key.getPath().toLowerCase();
        // 优先查别名，再查完整 ID，最后用路径
        return CUSTOM_ALIASES.getOrDefault(path, CUSTOM_NAMES.getOrDefault(key, path));
    }

    /**
     * 通过反射映射 GTCEu 配方到搜索关键字。
     *
     * @param gtRecipeObj GTCEu 配方对象
     * @return 搜索关键字，或 null 如果映射失败
     */
    public static String mapGTCEuRecipeToSearchKey(Object gtRecipeObj) {
        if (gtRecipeObj == null) return null;
        try {
            // 获取配方类型
            Method mGetType = gtRecipeObj.getClass().getMethod("getType");
            Object typeObj = mGetType.invoke(gtRecipeObj);
            String idStr = String.valueOf(typeObj);
            if (idStr == null || idStr.isBlank()) return null;
            // 解析类型 ID
            ResourceLocation rl = new ResourceLocation(idStr);
            // 1) 别名优先（使用 path 作为最终搜索关键字）
            String path = rl.getPath();
            if (path != null) {
                String alias = CUSTOM_ALIASES.get(path.toLowerCase());
                if (alias != null && !alias.isBlank()) return alias;
            }
            // 2) 再查完整ID映射
            String custom = CUSTOM_NAMES.get(rl);
            // 3) 默认返回自定义名称或路径
            return custom != null && !custom.isBlank() ? custom : path;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从未知配方类推导搜索关键字。
     *
     * @param recipeBase 配方对象
     * @return 推导的搜索关键字，或 null 如果失败
     */
    public static String deriveSearchKeyFromUnknownRecipe(Object recipeBase) {
        if (recipeBase == null) return null;
        try {
            Class<?> cls = recipeBase.getClass();
            String simple = cls.getSimpleName();
            String pkg = cls.getName();

            String namespace = null;
            String lower = pkg.toLowerCase();
            // 检测模组命名空间
            if (lower.contains("gtceu")) namespace = "gtceu";
            else if (lower.contains("gregtech")) namespace = "gregtech";
            else if (lower.contains("projecte")) namespace = "projecte";
            else if (lower.contains("create")) namespace = "create";
            else if (lower.contains("immersiveengineering")) namespace = "immersive";

            String token = toSearchToken(simple); // 转换类名为关键字
            String key = (namespace != null && token != null && !token.isBlank()) ?
                    namespace + " " + token :
                    token;
            if (key == null || key.isBlank()) return null;
            // 尝试别名映射（大小写不敏感）
            String alias = CUSTOM_ALIASES.get(key.toLowerCase());
            // 返回别名或推导的键
            return alias != null && !alias.isBlank() ? alias : key;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 将类名转换为搜索关键字。
     *
     * @param simpleName 类简单名称
     * @return 转换后的关键字，或 null 如果无效
     */
    private static String toSearchToken(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return null;
        // 去掉常见后缀
        String s = simpleName
                .replaceAll("Recipe(s)?$", "")
                .replaceAll("Category$", "")
                .replaceAll("JEI$", "")
                .replaceAll("(?<!^)([A-Z])", " $1") // 驼峰转空格
                .toLowerCase()
                .trim();
        return s.isBlank() ? null : s;
    }

    /**
     * 通过 JEI API 获取配方对应工作方块的本地化名称
     *
     * @param recipe 配方对象
     * @return 工作方块的本地化名称，失败返回 null
     */
    public static String getWorkstationNameFromJEI(Recipe<?> recipe) {
        if (recipe == null) {
            return null;
        }

        IJeiRuntime runtime = JeiRuntimeProxy.get();
        if (runtime == null) {
            EAP$LOGGER.debug("[JEI] JEI Runtime 未初始化，无法获取工作方块名称");
            return null;
        }

        try {
            IRecipeManager recipeManager = runtime.getRecipeManager();

            // 遍历所有 JEI 配方类别
            for (IRecipeCategory<?> category : recipeManager.createRecipeCategoryLookup().get().toList()) {
                try {
                    mezz.jei.api.recipe.RecipeType<?> jeiRecipeType = category.getRecipeType();

                    // 获取该类别的所有配方
                    List<?> recipesInCategory = recipeManager.createRecipeLookup(jeiRecipeType)
                        .get()
                        .toList();

                    // 检查当前配方是否在此类别中
                    boolean found = false;
                    for (Object recipeObj : recipesInCategory) {
                        if (recipeObj == recipe) {
                            found = true;
                            break;
                        }
                        // 也比对 recipe ID
                        if (recipeObj instanceof Recipe<?> r && r.getId().equals(recipe.getId())) {
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        continue;
                    }

                    EAP$LOGGER.debug("[JEI] 找到配方对应的类别: {}", category.getClass().getSimpleName());

                    // 方式1：优先从图标获取工作方块 ItemStack，然后获取其名称
                    IDrawable icon = category.getIcon();
                    if (icon != null) {
                        ItemStack workstationStack = extractWorkstationStackFromIcon(runtime, icon);
                        if (!workstationStack.isEmpty()) {
                            String workstationName = workstationStack.getHoverName().getString();
                            if (workstationName != null && !workstationName.isBlank()) {
                                EAP$LOGGER.info("[JEI] 从图标提取工作方块名称: {}", workstationName);
                                return workstationName;
                            }
                        }
                    }

                    // 方式2：从类别标题获取（兜底方案）
                    Component title = category.getTitle();
                    if (title != null) {
                        String titleStr = title.getString();
                        if (titleStr != null && !titleStr.isBlank()) {
                            EAP$LOGGER.info("[JEI] 从类别标题获取名称: {}", titleStr);
                            return titleStr;
                        }
                    }

                } catch (Exception e) {
                    EAP$LOGGER.debug("[JEI] 处理类别时出错: {}", e.getMessage());
                }
            }

            EAP$LOGGER.warn("[JEI] 未找到配方 {} 对应的 JEI 类别", recipe.getId());

        } catch (Exception e) {
            EAP$LOGGER.error("[JEI] 获取工作方块名称时发生异常", e);
        }

        return null;
    }

    /**
     * 从 IDrawable 图标中提取工作方块名称
     * 优先使用 JEI 的公开 API，如果失败则尝试反射
     *
     * @param runtime JEI 运行时
     * @param icon    图标对象
     * @return 工作方块的本地化名称，失败返回 null
     */
    private static String extractWorkstationNameFromIcon(IJeiRuntime runtime, IDrawable icon) {
        if (runtime == null || icon == null) {
            return null;
        }

        try {
            // 方式1：尝试通过 JEI 的 IngredientManager 提取（如果icon实现了相关接口）
            // 某些 IDrawable 实现可能提供了获取底层ingredient的方法
            Class<?> iconClass = icon.getClass();
            String className = iconClass.getName();

            // 常见的 JEI Drawable 类型：ItemStackRenderer, DrawableIngredient 等
            EAP$LOGGER.debug("[JEI] Icon 类型: {}", className);

            // 方式2：反射获取 ItemStack 字段（作为回退方案）
            // JEI 的 ItemStackRenderer 通常包含一个 itemStack 字段
            java.lang.reflect.Field[] fields = iconClass.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                // 查找 ItemStack 类型的字段
                if (field.getType() == ItemStack.class ||
                    field.getName().toLowerCase().contains("itemstack") ||
                    field.getName().toLowerCase().contains("stack") ||
                    field.getName().toLowerCase().contains("item")) {

                    field.setAccessible(true);
                    Object value = field.get(icon);

                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        // 获取物品的本地化名称
                        String name = stack.getHoverName().getString();
                        EAP$LOGGER.debug("[JEI] 从图标字段 '{}' 提取到方块名称: {}", field.getName(), name);
                        return name;
                    }
                }

                // 查找可能是 List<ItemStack> 或 ItemStack[] 的字段
                if (field.getName().toLowerCase().contains("stacks") ||
                    field.getName().toLowerCase().contains("items")) {

                    field.setAccessible(true);
                    Object value = field.get(icon);

                    if (value instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof ItemStack stack && !stack.isEmpty()) {
                            String name = stack.getHoverName().getString();
                            EAP$LOGGER.debug("[JEI] 从图标列表字段 '{}' 提取到方块名称: {}", field.getName(), name);
                            return name;
                        }
                    }

                    if (value != null && value.getClass().isArray()) {
                        Object[] arr = (Object[]) value;
                        if (arr.length > 0 && arr[0] instanceof ItemStack stack && !stack.isEmpty()) {
                            String name = stack.getHoverName().getString();
                            EAP$LOGGER.debug("[JEI] 从图标数组字段 '{}' 提取到方块名称: {}", field.getName(), name);
                            return name;
                        }
                    }
                }
            }

            // 方式3：尝试调用可能的 getter 方法
            Method[] methods = iconClass.getMethods();
            for (Method method : methods) {
                if (method.getParameterCount() != 0) {
                    continue;
                }

                String methodName = method.getName();
                // 查找可能返回 ItemStack 的方法
                if ((methodName.equals("getItemStack") ||
                     methodName.equals("getStack") ||
                     methodName.equals("getItem")) &&
                    method.getReturnType() == ItemStack.class) {

                    Object result = method.invoke(icon);
                    if (result instanceof ItemStack stack && !stack.isEmpty()) {
                        String name = stack.getHoverName().getString();
                        EAP$LOGGER.debug("[JEI] 从图标方法 '{}' 提取到方块名称: {}", methodName, name);
                        return name;
                    }
                }
            }

            EAP$LOGGER.debug("[JEI] 无法从图标 {} 提取方块名称", className);
        } catch (Exception e) {
            EAP$LOGGER.debug("[JEI] 从图标提取方块名称失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从 JEI 书签中提取所有工作方块物品
     * 返回物品 ItemStack 列表，保持书签顺序
     *
     * @return 玩家收藏的所有物品（包含潜在的工作方块）
     */
    public static List<ItemStack> getBookmarkedWorkstations() {
        EAP$LOGGER.debug("[JEI] >> getBookmarkedWorkstations");

        List<? extends mezz.jei.api.ingredients.ITypedIngredient<?>> bookmarks = JeiRuntimeProxy.getBookmarkList();
        if (bookmarks == null) {
            EAP$LOGGER.debug("[JEI] << 书签列表为 null");
            return java.util.Collections.emptyList();
        }

        if (bookmarks.isEmpty()) {
            EAP$LOGGER.info("[JEI] << 玩家没有收藏任何书签");
            return java.util.Collections.emptyList();
        }

        EAP$LOGGER.info("[JEI] 书签列表包含 {} 个项目", bookmarks.size());

        List<ItemStack> workstations = new ArrayList<>();
        for (int i = 0; i < bookmarks.size(); i++) {
            mezz.jei.api.ingredients.ITypedIngredient<?> ingredient = bookmarks.get(i);
            Optional<ItemStack> stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK);
            if (stack.isPresent()) {
                ItemStack item = stack.get();
                workstations.add(item);
                EAP$LOGGER.debug("[JEI]   书签[{}]: {} (是 ItemStack)", i, item.getHoverName().getString());
            } else {
                EAP$LOGGER.debug("[JEI]   书签[{}]: 不是 ItemStack (类型: {})", i, ingredient.getType());
            }
        }

        EAP$LOGGER.info("[JEI] << 提取到 {} 个物品书签", workstations.size());
        return workstations;
    }

    /**
     * 从 IDrawable 图标中提取工作方块 ItemStack（而非仅名称）
     * 使用 JEI 的 DrawableIngredient 内部结构提取
     *
     * @param runtime JEI 运行时
     * @param icon    图标对象
     * @return 工作方块 ItemStack，失败返回空栈
     */
    private static ItemStack extractWorkstationStackFromIcon(IJeiRuntime runtime, IDrawable icon) {
        if (runtime == null || icon == null) {
            return ItemStack.EMPTY;
        }

        try {
            Class<?> iconClass = icon.getClass();
            String className = iconClass.getName();
            EAP$LOGGER.debug("[JEI]     提取图标 ItemStack，图标类型: {}", className);

            // 方式1：如果是 DrawableIngredient，直接提取 typedIngredient 字段
            if (className.contains("DrawableIngredient")) {
                java.lang.reflect.Field[] fields = iconClass.getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    if (field.getName().equals("typedIngredient") ||
                        field.getType().getSimpleName().contains("TypedIngredient")) {

                        field.setAccessible(true);
                        Object typedIngredient = field.get(icon);

                        if (typedIngredient != null) {
                            // 调用 getIngredient(VanillaTypes.ITEM_STACK) 方法
                            try {
                                java.lang.reflect.Method getIngredientMethod =
                                    typedIngredient.getClass().getMethod("getIngredient", mezz.jei.api.ingredients.IIngredientType.class);

                                Object ingredientObj = getIngredientMethod.invoke(typedIngredient, VanillaTypes.ITEM_STACK);

                                if (ingredientObj instanceof Optional<?> opt && opt.isPresent()) {
                                    Object value = opt.get();
                                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                                        EAP$LOGGER.debug("[JEI]     ✓ 从 DrawableIngredient.typedIngredient 提取到: {}",
                                            stack.getHoverName().getString());
                                        return stack;
                                    }
                                }
                            } catch (Exception e) {
                                EAP$LOGGER.debug("[JEI]     获取 ingredient 失败: {}", e.getMessage());
                            }
                        }
                    }
                }
            }

            // 方式2：尝试通用反射获取 ItemStack 字段（兜底）
            java.lang.reflect.Field[] fields = iconClass.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                // 查找 ItemStack 类型的字段
                if (field.getType() == ItemStack.class ||
                    field.getName().toLowerCase().contains("itemstack") ||
                    field.getName().toLowerCase().contains("stack") ||
                    field.getName().toLowerCase().contains("item")) {

                    field.setAccessible(true);
                    Object value = field.get(icon);

                    if (value instanceof ItemStack stack && !stack.isEmpty()) {
                        EAP$LOGGER.debug("[JEI]     ✓ 从字段 '{}' 提取到 ItemStack: {}",
                            field.getName(), stack.getHoverName().getString());
                        return stack;
                    }
                }

                // 查找可能是 List<ItemStack> 或 ItemStack[] 的字段
                if (field.getName().toLowerCase().contains("stacks") ||
                    field.getName().toLowerCase().contains("items")) {

                    field.setAccessible(true);
                    Object value = field.get(icon);

                    if (value instanceof List<?> list && !list.isEmpty()) {
                        Object first = list.get(0);
                        if (first instanceof ItemStack stack && !stack.isEmpty()) {
                            EAP$LOGGER.debug("[JEI]     ✓ 从列表字段 '{}' 提取到 ItemStack: {}",
                                field.getName(), stack.getHoverName().getString());
                            return stack;
                        }
                    }

                    if (value != null && value.getClass().isArray()) {
                        Object[] arr = (Object[]) value;
                        if (arr.length > 0 && arr[0] instanceof ItemStack stack && !stack.isEmpty()) {
                            EAP$LOGGER.debug("[JEI]     ✓ 从数组字段 '{}' 提取到 ItemStack: {}",
                                field.getName(), stack.getHoverName().getString());
                            return stack;
                        }
                    }
                }
            }

            // 方式3：尝试调用可能的 getter 方法
            Method[] methods = iconClass.getMethods();
            for (Method method : methods) {
                if (method.getParameterCount() != 0) {
                    continue;
                }

                String methodName = method.getName();
                // 查找可能返回 ItemStack 的方法
                if ((methodName.equals("getItemStack") ||
                     methodName.equals("getStack") ||
                     methodName.equals("getItem")) &&
                    method.getReturnType() == ItemStack.class) {

                    Object result = method.invoke(icon);
                    if (result instanceof ItemStack stack && !stack.isEmpty()) {
                        EAP$LOGGER.debug("[JEI]     ✓ 从方法 '{}' 提取到 ItemStack: {}",
                            methodName, stack.getHoverName().getString());
                        return stack;
                    }
                }
            }

            EAP$LOGGER.debug("[JEI]     ✗ 无法从图标提取 ItemStack");

        } catch (Exception e) {
            EAP$LOGGER.debug("[JEI] 从图标提取工作方块栈失败: {}", e.getMessage());
        }

        return ItemStack.EMPTY;
    }

    /**
     * 构建工作方块物品到配方类别的映射关系
     * 策略：优先从图标提取 ItemStack，失败则通过类别标题与物品名称匹配
     *
     * @param runtime JEI 运行时
     * @return Map<Item, Set<ResourceLocation>> 工作方块物品到 JEI 配方类别 UID 的映射
     */
    public static Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> buildWorkstationToRecipeTypeMapping(IJeiRuntime runtime) {
        Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> mapping = new HashMap<>();

        if (runtime == null) {
            EAP$LOGGER.debug("[JEI] buildWorkstationToRecipeTypeMapping: runtime 为 null");
            return mapping;
        }

        EAP$LOGGER.info("[JEI] ========== 开始构建工作方块映射 ==========");

        try {
            IRecipeManager recipeManager = runtime.getRecipeManager();
            List<IRecipeCategory<?>> allCategories = recipeManager.createRecipeCategoryLookup().get().toList();
            EAP$LOGGER.info("[JEI] JEI 总共有 {} 个配方类别", allCategories.size());

            // 第一阶段：尝试从图标提取工作方块
            Map<ResourceLocation, String> categoryTitles = new HashMap<>();
            int iconSuccessCount = 0;

            for (int i = 0; i < allCategories.size(); i++) {
                IRecipeCategory<?> category = allCategories.get(i);
                try {
                    mezz.jei.api.recipe.RecipeType<?> jeiRecipeType = category.getRecipeType();
                    ResourceLocation recipeTypeId = jeiRecipeType.getUid();
                    Component title = category.getTitle();

                    // 保存标题用于后续名称匹配
                    if (title != null) {
                        categoryTitles.put(recipeTypeId, title.getString());
                    }

                    // 尝试从图标提取
                    IDrawable icon = category.getIcon();
                    if (icon != null) {
                        ItemStack workstationStack = extractWorkstationStackFromIcon(runtime, icon);
                        if (!workstationStack.isEmpty()) {
                            net.minecraft.world.item.Item workstationItem = workstationStack.getItem();
                            mapping.computeIfAbsent(workstationItem, k -> new java.util.HashSet<>())
                                   .add(recipeTypeId);

                            iconSuccessCount++;
                        }
                    }

                } catch (Exception e) {
                    EAP$LOGGER.debug("[JEI] 处理类别时出错: {}", e.getMessage());
                }
            }

            EAP$LOGGER.info("[JEI] 图标提取阶段完成，成功识别 {} 个工作方块", iconSuccessCount);


        } catch (Exception e) {
            EAP$LOGGER.error("[JEI] 构建工作方块映射时出错", e);
        }

        EAP$LOGGER.info("[JEI] ========== 工作方块映射构建完成 ==========");
        EAP$LOGGER.info("[JEI] 共识别出 {} 个工作方块", mapping.size());

        return mapping;
    }

    /**
     * 构建工作方块到配方类别的映射（带缓存）
     * 使用 30 秒缓存避免重复遍历 JEI 类别
     *
     * @param runtime JEI 运行时
     * @return 工作方块物品到配方类别 UID 的映射
     */
    public static Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> buildWorkstationToRecipeTypeMappingCached(IJeiRuntime runtime) {
        long now = System.currentTimeMillis();

        // 检查缓存是否有效
        if (WORKSTATION_MAPPING_CACHE != null && now - CACHE_TIMESTAMP < CACHE_VALIDITY_MS) {
            long age = now - CACHE_TIMESTAMP;
            EAP$LOGGER.debug("[JEI] 使用工作方块映射缓存 (年龄: {} ms, 剩余有效期: {} ms)",
                age, CACHE_VALIDITY_MS - age);
            return WORKSTATION_MAPPING_CACHE;
        }

        if (WORKSTATION_MAPPING_CACHE != null) {
            EAP$LOGGER.debug("[JEI] 工作方块映射缓存已过期，重新构建");
        } else {
            EAP$LOGGER.debug("[JEI] 首次构建工作方块映射缓存");
        }

        // 重建缓存
        Map<net.minecraft.world.item.Item, java.util.Set<ResourceLocation>> mapping = buildWorkstationToRecipeTypeMapping(runtime);
        WORKSTATION_MAPPING_CACHE = mapping;
        CACHE_TIMESTAMP = now;

        EAP$LOGGER.info("[JEI] 工作方块映射缓存已更新，包含 {} 个工作方块", mapping.size());

        return mapping;
    }
}

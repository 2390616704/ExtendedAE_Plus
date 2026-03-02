# Ctrl+Q 效率提升文档

## 核心设计理念

**目标**：演进代码直接或间接提高玩家样板编写效率

---

## 效率提升的具体体现

### 2.1 三级配方优先级选择

**效率提升**：免去手动从10+个配方中选择的步骤

- **Priority 1（最高优先级）**：收藏工作方块自动匹配
  → 直接选中你想要的工作方块配方
  → 例如：收藏熔炉后，悬停铁锭按Ctrl+Q自动选择熔炉配方而非切割配方

- **Priority 2（次优先级）**：工作台配方优先
  → 最常见的场景无需思考
  → 例如：悬停木板按Ctrl+Q自动选择工作台配方

- **Priority 3（兜底方案）**：智能回退
  → 总能创建样板，不会失败
  → 即使没有收藏工作方块，也会返回第一个可用配方

**实现机制**：
```java
// src/main/java/com/extendedae_plus/util/RecipeFinderUtil.java
public static RecipeInfo selectBestRecipe(List<RecipeInfo> recipes) {
    // 1. 检查玩家JEI书签中收藏的工作方块
    RecipeInfo bookmarkedRecipe = selectRecipeByBookmarkedWorkstation(recipes);
    if (bookmarkedRecipe != null) return bookmarkedRecipe;

    // 2. 优先选择工作台配方
    for (RecipeInfo info : recipes) {
        if (info.isCraftingRecipe()) return info;
    }

    // 3. 返回第一个配方
    return recipes.get(0);
}
```

### 2.2 JEI书签优先级材料选择

**效率提升**：免去手动在配方GUI中切换材料的步骤

- 自动从收藏的材料中选择 → 直接使用你仓库里有的材料
- 优先级按收藏顺序 → 你最先收藏的优先使用
- 支持多种材料配方 → 自动选择最高优先级材料

**实现机制**：
```java
// src/main/java/com/extendedae_plus/client/event/CtrlQPatternKeyHandler.java
private static List<ItemStack> selectIngredientsWithJeiPriority(RecipeInfo recipeInfo) {
    // 获取JEI书签并构建优先级映射
    List<? extends ITypedIngredient<?>> bookmarks = JeiRuntimeProxy.getBookmarkList();
    Map<Item, Integer> priorities = new HashMap<>();

    // 数值越小优先级越高（最先收藏的优先级最高）
    AtomicInteger index = new AtomicInteger(Integer.MAX_VALUE);
    for (ITypedIngredient<?> ingredient : bookmarks) {
        ingredient.getIngredient(VanillaTypes.ITEM_STACK).ifPresent(itemStack ->
            priorities.put(itemStack.getItem(), index.getAndDecrement())
        );
    }

    // 使用RecipeInfo的方法选择最佳输入
    return recipeInfo.selectBestInputs(priorities);
}
```

### 2.3 工作方块后缀标记

**效率提升**：免去查看样板内容才能确定工作方块的步骤

- 样板名称显示工作方块（如"铁锭_熔炉"）→ 一眼识别配方类型
- 供应器选择界面自动筛选 → 只显示匹配的供应器
- 支持本地化名称 → 显示"熔炉"而非"smelting"

**实现机制**：
```java
// src/main/java/com/extendedae_plus/network/pattern/CreateCtrlQPatternC2SPacket.java
private static void addRecipeTypeSuffixToPattern(ItemStack pattern, Recipe<?> recipe, ItemStack outputItem) {
    // 从JEI获取本地化工作方块名称（三层回退策略）
    String workstationName = RecipeTypeNameConfig.getWorkstationNameFromJEI(recipe);

    // 回退：使用配方类型路径
    if (workstationName == null || workstationName.isBlank()) {
        RecipeType<?> type = recipe.getType();
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        workstationName = key != null ? key.getPath() : "unknown";
    }

    // 设置样板显示名称
    String itemName = outputItem.getHoverName().getString();
    String patternName = itemName + "_" + workstationName;
    pattern.setHoverName(Component.literal(patternName));
}
```

**三层回退策略**：
1. **优先**：从JEI类别图标提取ItemStack的本地化名称（如"熔炉"）
2. **次选**：使用JEI类别标题
3. **兜底**：使用配方类型路径（如"smelting"）

### 2.4 配方书签直接支持

**效率提升**：免去从JEI配方界面手动复制的步骤

- 直接在JEI配方书签上Ctrl+Q → 配方类型、输入输出全自动提取
- 区分合成/加工配方 → 自动选择上传目标（矩阵/供应器）
- 支持复杂配方（流体、GTCEu等）→ 自动处理特殊配方类型

**实现机制**：
```java
// src/main/java/com/extendedae_plus/client/event/CtrlQPatternKeyHandler.java
private static void handleRecipeBookmark(Object recipeBookmark) {
    if (isCraftingRecipe(recipeBookmark)) {
        // 合成配方分支：自动上传到装配矩阵
        handleCraftingRecipeBookmark(recipeBookmark);
    } else {
        // 加工配方分支：打开供应器选择界面
        handleProcessingRecipeBookmark(recipeBookmark);
    }
}
```

### 2.5 工作方块类别记忆

**效率提升**：免去每次都要搜索供应器的步骤

- 自动记住最后使用的工作方块类型 → 打开供应器界面已预填搜索词
- 智能从配方类型推导中文名称 → 搜索"熔炉"而不是"smelting"
- 支持多个工作方块类别 → 自动构建搜索关键词列表

**实现机制**：
```java
// src/main/java/com/extendedae_plus/util/uploadPattern/RecipeTypeNameConfig.java
public static volatile String lastProcessingName = "分子装配室,熔炉";

// 客户端设置工作方块类别
private static void setLastProcessingNameFromRecipe(Object recipeBase) {
    String name = RecipeTypeNameConfig.mapRecipeTypeToSearchKey(recipe);
    if (name != null && !name.isBlank()) {
        RecipeTypeNameConfig.setLastProcessingName(name);
    }
}

// 服务端打开供应器选择界面时使用
String recent = RecipeTypeNameConfig.lastProcessingName; // "熔炉"
```

---

## 效率量化对比

### 传统样板编写流程（约12-15步，3-5分钟）

1. 打开样板编码终端
2. 在JEI中查找配方
3. 记住配方的输入材料
4. 在终端中逐个放入材料
5. 如果材料有多种选择，手动在GUI中切换（每个材料1-3次点击）
6. 点击编码按钮
7. 从背包取出样板
8. 打开供应器
9. 搜索对应的供应器类型（输入关键词）
10. 放入样板
11. 关闭界面
12. 重复以上步骤创建其他样板


---

## 设计决策与效率的关系

### 为什么要收藏工作方块？
**决策理由**：让系统知道你的优先级，免去选择步骤

**效率收益**：
- 免去从10+个配方中手动选择的步骤
- 自动匹配你想要的工作方块配方
- 适应不同玩家的工作方块偏好（有人喜欢熔炉，有人喜欢高炉）


### 为什么要样板后缀？
**决策理由**：免去查看样板内容的步骤，提高识别速度

**效率收益**：
- 一眼识别样板的工作方块类型
- 供应器选择界面自动筛选（只显示匹配的供应器）
- 批量创建样板时不会混淆（熔炉配方和高炉配方一目了然）


### Git操作规范
**提交和推送必须先得到开发者的明示**

### 其它

- 开发者说话总是话里有话,开发者大部分情况下不会把需求说的太过具体,
- 开发者说应该是怎么怎么样的,通常是预期效果之一
- 
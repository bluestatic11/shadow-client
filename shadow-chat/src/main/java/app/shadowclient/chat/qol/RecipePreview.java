package app.shadowclient.chat.qol;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tooltip recipe preview backed by the client recipe book — when
 * any item with a known crafting / smelting / stonecutting / smithing
 * recipe is hovered, a {@code [Recipe]} block is appended showing the
 * ingredients laid out as text. Pulls live from
 * {@link ClientRecipeBook} so the coverage is "every recipe the
 * server has told you about" — vanilla, plugin-added, datapacks.
 *
 * <p>A small curated fallback table covers a handful of items that
 * don't have a crafting recipe but where it's useful to say where
 * they come from (Ender pearls, totems, saddles…).
 *
 * <p>Toggle: {@code /recipe on|off|status} (alias {@code /rp}).
 */
public final class RecipePreview {

    /** Curated fallback notes for items without a real recipe. */
    private static final Map<String, String[]> FALLBACK = new HashMap<>();
    static {
        FALLBACK.put("ender_pearl", new String[]{"§eDrop from §fEndermen§e, found in §fEnd City§e chests"});
        FALLBACK.put("saddle",       new String[]{"§eFound in dungeon / nether fortress / bastion / village chests"});
        FALLBACK.put("totem_of_undying", new String[]{"§eDropped by §fEvokers§e in §fWoodland Mansions§e or §fraids"});
        FALLBACK.put("name_tag",     new String[]{"§eFound in dungeon / mineshaft chests, or fished up rarely"});
        FALLBACK.put("nether_star",  new String[]{"§eDropped by §fThe Wither§e (3× Wither Skeleton Skulls on Soul Sand T)"});
        FALLBACK.put("dragon_egg",   new String[]{"§eOne-shot drop on first §fEnder Dragon§e defeat"});
        FALLBACK.put("elytra",       new String[]{"§eFound in the End ship at the end of an §fEnd City"});
        FALLBACK.put("trident",      new String[]{"§eRare drop from §fDrowned§e (≈8.5% with looting III)"});
        FALLBACK.put("heart_of_the_sea",  new String[]{"§eFound in §fBuried Treasure§e chests (need a treasure map)"});
        FALLBACK.put("nautilus_shell",    new String[]{"§eRare drop from §fDrowned§e + fishing + ambient ocean spawn"});
        FALLBACK.put("netherite_scrap",   new String[]{"§eSmelt §fAncient Debris§e (found Y=8-22 in the Nether)"});
        FALLBACK.put("ancient_debris",    new String[]{"§eMine with a §fdiamond pickaxe§e or better, Y=8-22 in the Nether"});
        FALLBACK.put("echo_shard",   new String[]{"§eFound in §fAncient City§e chests in Deep Dark biomes"});
        FALLBACK.put("disc_fragment_5", new String[]{"§eFound in §fAncient City§e chests"});
        FALLBACK.put("sniffer_egg",  new String[]{"§eFound by §fbrushing Suspicious Sand§e in Warm Ocean Ruins"});
        FALLBACK.put("turtle_egg",   new String[]{"§eLaid by §fturtles§e on sand beaches"});
        FALLBACK.put("ghast_tear",   new String[]{"§eDropped by §fGhasts§e in the Nether"});
        FALLBACK.put("blaze_rod",    new String[]{"§eDropped by §fBlazes§e in Nether Fortresses"});
    }

    /** Max recipes to render per tooltip — avoids spamming on items with many variants. */
    private static final int MAX_RECIPES_PER_TOOLTIP = 2;

    private RecipePreview() {}

    /** Count of fallback annotations. Real recipes come live from the recipe book. */
    public static int knownRecipeCount() { return FALLBACK.size(); }

    /**
     * Single-slot tooltip cache. ItemTooltipCallback fires every FRAME
     * the tooltip is visible, and the dynamic path walks the entire
     * client recipe book — hundreds of entries vanilla, thousands on
     * modded servers. Hovering is by nature one-item-at-a-time, so one
     * cached block keyed by item identity removes ~59 of every 60
     * walks. 5s expiry catches mid-session recipe-book additions.
     */
    private static Item cacheItem;
    private static long cacheBuiltMs;
    private static List<Component> cacheBlock = List.of();

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, ctx, tooltipType, lines) -> {
            if (!Qol.recipePreviewEnabled) return;
            if (stack == null || stack.isEmpty()) return;

            long now = System.currentTimeMillis();
            if (stack.getItem() == cacheItem && now - cacheBuiltMs < 5000) {
                lines.addAll(cacheBlock);
                return;
            }
            List<Component> block = new ArrayList<>();
            int rendered = tryAppendDynamicRecipes(stack, block);
            if (rendered == 0) {
                tryAppendFallback(stack, block);
            }
            cacheItem = stack.getItem();
            cacheBuiltMs = now;
            cacheBlock = block;
            lines.addAll(block);
        });
    }

    /**
     * Walk the client recipe book for recipes whose result matches the
     * hovered item, format up to {@link #MAX_RECIPES_PER_TOOLTIP} of
     * them, append to the tooltip. Returns the number of recipes
     * actually rendered.
     */
    private static int tryAppendDynamicRecipes(ItemStack hovered, List<Component> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;
        ClientRecipeBook book;
        try {
            book = mc.player.getRecipeBook();
        } catch (Throwable t) {
            return 0;
        }
        if (book == null) return 0;

        ContextMap context;
        try {
            context = SlotDisplayContext.fromLevel(mc.level);
        } catch (Throwable t) {
            return 0;
        }

        int rendered = 0;
        for (RecipeCollection collection : book.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                RecipeDisplay display = entry.display();
                ItemStack result = resolveFirst(display.result(), context);
                if (result.isEmpty()) continue;
                if (!ItemStack.isSameItem(result, hovered)) continue;

                if (rendered == 0) {
                    lines.add(Component.literal(""));
                    MutableComponent header = Component.literal("[Recipe]")
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
                    lines.add(header);
                }
                if (rendered > 0) {
                    lines.add(Component.literal("§7— or —"));
                }
                appendRecipeBody(display, context, result, lines);
                rendered++;
                if (rendered >= MAX_RECIPES_PER_TOOLTIP) {
                    // Hint that there might be more
                    boolean more = hasMoreRecipes(book, context, hovered, rendered);
                    if (more) {
                        lines.add(Component.literal("§7(more recipes available — see Recipe Book)"));
                    }
                    return rendered;
                }
            }
        }
        return rendered;
    }

    /** Format one recipe's body lines based on its concrete display type. */
    private static void appendRecipeBody(RecipeDisplay display, ContextMap context,
                                         ItemStack result, List<Component> lines) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            lines.add(Component.literal("§7Crafting Table §8(shaped)"));
            int w = shaped.width(), h = shaped.height();
            List<SlotDisplay> ings = shaped.ingredients();
            for (int row = 0; row < h; row++) {
                StringBuilder sb = new StringBuilder("  ");
                for (int col = 0; col < w; col++) {
                    int idx = row * w + col;
                    if (idx >= ings.size()) { sb.append("§8·§r "); continue; }
                    ItemStack ing = resolveFirst(ings.get(idx), context);
                    if (ing.isEmpty()) {
                        sb.append("§8·§r ");
                    } else {
                        sb.append("§f[").append(shortName(ing)).append("]§r ");
                    }
                }
                lines.add(Component.literal(sb.toString()));
            }
            lines.add(Component.literal("§7→ §f" + result.getCount() + "× " + shortName(result)));
            return;
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            lines.add(Component.literal("§7Crafting Table §8(shapeless)"));
            List<ItemStack> ings = resolveAll(shapeless.ingredients(), context);
            // Group by item to show counts
            for (String row : groupedIngredients(ings)) {
                lines.add(Component.literal("  §f" + row));
            }
            lines.add(Component.literal("§7→ §f" + result.getCount() + "× " + shortName(result)));
            return;
        }
        if (display instanceof FurnaceRecipeDisplay furnace) {
            ItemStack ing = resolveFirst(furnace.ingredient(), context);
            lines.add(Component.literal("§7Furnace / Smoker / Blast Furnace"));
            lines.add(Component.literal("  §f" + (ing.isEmpty() ? "?" : shortName(ing))
                    + " §7→ §f" + result.getCount() + "× " + shortName(result)));
            return;
        }
        if (display instanceof StonecutterRecipeDisplay sc) {
            ItemStack ing = resolveFirst(sc.input(), context);
            lines.add(Component.literal("§7Stonecutter"));
            lines.add(Component.literal("  §f" + (ing.isEmpty() ? "?" : shortName(ing))
                    + " §7→ §f" + result.getCount() + "× " + shortName(result)));
            return;
        }
        if (display instanceof SmithingRecipeDisplay sm) {
            ItemStack template = resolveFirst(sm.template(), context);
            ItemStack base     = resolveFirst(sm.base(),     context);
            ItemStack addition = resolveFirst(sm.addition(), context);
            lines.add(Component.literal("§7Smithing Table"));
            lines.add(Component.literal("  template §f" + (template.isEmpty() ? "?" : shortName(template))));
            lines.add(Component.literal("  base     §f" + (base.isEmpty()     ? "?" : shortName(base))));
            lines.add(Component.literal("  add      §f" + (addition.isEmpty() ? "?" : shortName(addition))));
            lines.add(Component.literal("§7→ §f" + result.getCount() + "× " + shortName(result)));
            return;
        }
        // Unknown display type — at least say something useful.
        lines.add(Component.literal("§7Yields §f" + result.getCount() + "× " + shortName(result)));
    }

    /** Quick scan to see if the recipe book has any extra recipes beyond the {@code rendered} cap. */
    private static boolean hasMoreRecipes(ClientRecipeBook book, ContextMap context,
                                          ItemStack hovered, int rendered) {
        int seen = 0;
        for (RecipeCollection collection : book.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                ItemStack result = resolveFirst(entry.display().result(), context);
                if (result.isEmpty()) continue;
                if (!ItemStack.isSameItem(result, hovered)) continue;
                seen++;
                if (seen > rendered) return true;
            }
        }
        return false;
    }

    private static ItemStack resolveFirst(SlotDisplay slot, ContextMap context) {
        try {
            List<ItemStack> all = new ArrayList<>();
            slot.resolveForStacks(context).forEach(all::add);
            return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static List<ItemStack> resolveAll(List<SlotDisplay> slots, ContextMap context) {
        List<ItemStack> out = new ArrayList<>(slots.size());
        for (SlotDisplay s : slots) {
            out.add(resolveFirst(s, context));
        }
        return out;
    }

    /** Returns the item's display name minus the surrounding brackets-and-namespace fuss. */
    private static String shortName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "?";
        String n = stack.getHoverName().getString();
        // Hover names can include a leading "Block of " etc. — keep as-is; trim is fine.
        if (n.length() > 28) n = n.substring(0, 27) + "…";
        return n;
    }

    /**
     * For shapeless recipes, group identical ingredients and report
     * "{count}× {name}" rather than listing every slot. Empty stacks
     * are dropped.
     */
    private static List<String> groupedIngredients(List<ItemStack> stacks) {
        // Preserve order of first occurrence so the printed lines match
        // the recipe ordering when ingredients are deliberately ordered.
        Map<String, Integer> count = new java.util.LinkedHashMap<>();
        for (ItemStack s : stacks) {
            if (s == null || s.isEmpty()) continue;
            String key = shortName(s);
            count.merge(key, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>(count.size());
        count.forEach((name, n) -> out.add(n > 1 ? n + "× " + name : name));
        return out;
    }

    private static void tryAppendFallback(ItemStack hovered, List<Component> lines) {
        Item item = hovered.getItem();
        Identifier rl = BuiltInRegistries.ITEM.getKey(item);
        if (rl == null) return;
        String[] fb = FALLBACK.get(rl.getPath());
        if (fb == null) return;
        lines.add(Component.literal(""));
        MutableComponent header = Component.literal("[Source]")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
        lines.add(header);
        for (String l : fb) lines.add(Component.literal(l));
    }
}

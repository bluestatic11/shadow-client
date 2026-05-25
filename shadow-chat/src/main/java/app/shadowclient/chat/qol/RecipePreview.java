package app.shadowclient.chat.qol;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Tooltip recipe preview for common items. Hover any item the table
 * knows about in your inventory / creative menu and the tooltip
 * grows a {@code [Recipe]} block describing the ingredients, the
 * arrangement, and the yield.
 *
 * <p>The table is hand-curated rather than driven by the client-side
 * recipe API for a deliberate reason: 1.21.11+ ships recipes to
 * clients as {@code RecipeDisplay} summaries optimized for the
 * vanilla recipe book, and reassembling them into a tooltip-friendly
 * text grid is messy. A small JSON-less table for the ~50 most-
 * crafted items covers nearly all minigame / survival use cases and
 * stays trivially extensible — just call
 * {@link #add(String, String...)} with new entries.
 *
 * <p>Toggle: {@code /recipe on|off|status} (alias {@code /rp}).
 */
public final class RecipePreview {

    /** One recipe entry: a list of human-readable lines to append. */
    private record Entry(String[] lines) {}

    private static final Map<String, Entry> TABLE = new HashMap<>();

    static {
        // ─── basic tools / sticks / torches ──────────────────────────
        recipe("stick",
                "§7Crafting Table",
                "§7  2× §fOak Planks§7 (stacked vertically)",
                "§7  → 4× §fStick");
        recipe("torch",
                "§7Crafting Table",
                "§7  1× §fCoal§7 (or charcoal) above 1× §fStick",
                "§7  → 4× §fTorch");
        recipe("soul_torch",
                "§7Crafting Table",
                "§7  1× §fCoal§7 + 1× §fStick§7 + 1× §fSoul Soil/Sand",
                "§7  → 4× §fSoul Torch");
        recipe("ladder",
                "§7Crafting Table",
                "§7  §fSticks§7 in 2 vertical columns (7 sticks)",
                "§7  → 3× §fLadder");

        // ─── workbench / storage / utility blocks ────────────────────
        recipe("crafting_table",
                "§7Crafting Table",
                "§7  4× §fOak Planks§7 in a 2×2 square",
                "§7  → 1× §fCrafting Table");
        recipe("furnace",
                "§7Crafting Table",
                "§7  8× §fCobblestone§7 ringing the outer slots",
                "§7  → 1× §fFurnace");
        recipe("blast_furnace",
                "§7Crafting Table",
                "§7  5× §fSmooth Stone§7 around a §fFurnace§7 with 3× §fIron Ingot§7 on top",
                "§7  → 1× §fBlast Furnace");
        recipe("smoker",
                "§7Crafting Table",
                "§7  4× any §fLog§7 around a §fFurnace",
                "§7  → 1× §fSmoker");
        recipe("chest",
                "§7Crafting Table",
                "§7  8× §fOak Planks§7 ringing the outer slots",
                "§7  → 1× §fChest");
        recipe("ender_chest",
                "§7Crafting Table",
                "§7  8× §fObsidian§7 ringing 1× §fEye of Ender",
                "§7  → 1× §fEnder Chest");
        recipe("hopper",
                "§7Crafting Table",
                "§7  5× §fIron Ingot§7 in a V + 1× §fChest§7 in the center",
                "§7  → 1× §fHopper");
        recipe("dispenser",
                "§7Crafting Table",
                "§7  7× §fCobblestone§7 + 1× §fBow§7 + 1× §fRedstone",
                "§7  → 1× §fDispenser");
        recipe("dropper",
                "§7Crafting Table",
                "§7  7× §fCobblestone§7 ringing 1× §fRedstone",
                "§7  → 1× §fDropper");
        recipe("observer",
                "§7Crafting Table",
                "§7  6× §fCobblestone§7 + 2× §fRedstone§7 + 1× §fNether Quartz",
                "§7  → 1× §fObserver");
        recipe("piston",
                "§7Crafting Table",
                "§7  3× §fOak Planks§7 (top), 4× §fCobblestone§7 (sides+bottom),",
                "§7  1× §fIron Ingot§7 (center), 1× §fRedstone§7 (below)",
                "§7  → 1× §fPiston");
        recipe("sticky_piston",
                "§7Crafting Table",
                "§7  1× §fSlimeball§7 above a §fPiston",
                "§7  → 1× §fSticky Piston");
        recipe("anvil",
                "§7Crafting Table",
                "§7  3× §fIron Block§7 (top row) + 4× §fIron Ingot§7 (column under)",
                "§7  → 1× §fAnvil");
        recipe("enchanting_table",
                "§7Crafting Table",
                "§7  1× §fBook§7 above 2× §fDiamond§7 + 4× §fObsidian",
                "§7  → 1× §fEnchanting Table");
        recipe("beacon",
                "§7Crafting Table",
                "§7  5× §fGlass§7 around 1× §fNether Star§7 above 3× §fObsidian",
                "§7  → 1× §fBeacon");

        // ─── tools (iron tier as the canonical example) ──────────────
        recipe("iron_pickaxe",
                "§7Crafting Table",
                "§7  3× §fIron Ingot§7 (top row) + 2× §fStick§7 (column)",
                "§7  → 1× §fIron Pickaxe");
        recipe("iron_axe",
                "§7Crafting Table",
                "§7  3× §fIron Ingot§7 in L + 2× §fStick§7 (column)",
                "§7  → 1× §fIron Axe");
        recipe("iron_shovel",
                "§7Crafting Table",
                "§7  1× §fIron Ingot§7 over 2× §fStick§7 (vertical)",
                "§7  → 1× §fIron Shovel");
        recipe("iron_hoe",
                "§7Crafting Table",
                "§7  2× §fIron Ingot§7 (top row) + 2× §fStick§7 (column)",
                "§7  → 1× §fIron Hoe");
        recipe("iron_sword",
                "§7Crafting Table",
                "§7  2× §fIron Ingot§7 stacked + 1× §fStick§7 below",
                "§7  → 1× §fIron Sword");
        recipe("shears",
                "§7Crafting Table",
                "§7  2× §fIron Ingot§7 diagonally",
                "§7  → 1× §fShears");
        recipe("flint_and_steel",
                "§7Crafting Table",
                "§7  1× §fIron Ingot§7 + 1× §fFlint§7 (diagonal)",
                "§7  → 1× §fFlint and Steel");
        recipe("bucket",
                "§7Crafting Table",
                "§7  3× §fIron Ingot§7 in V",
                "§7  → 1× §fBucket");
        recipe("compass",
                "§7Crafting Table",
                "§7  4× §fIron Ingot§7 around 1× §fRedstone",
                "§7  → 1× §fCompass");
        recipe("clock",
                "§7Crafting Table",
                "§7  4× §fGold Ingot§7 around 1× §fRedstone",
                "§7  → 1× §fClock");
        recipe("fishing_rod",
                "§7Crafting Table",
                "§7  3× §fStick§7 diagonally + 2× §fString§7 (column on right)",
                "§7  → 1× §fFishing Rod");

        // ─── armor (iron canonical) ──────────────────────────────────
        recipe("iron_helmet",
                "§7Crafting Table",
                "§7  5× §fIron Ingot§7 in a U (top + sides)",
                "§7  → 1× §fIron Helmet");
        recipe("iron_chestplate",
                "§7Crafting Table",
                "§7  8× §fIron Ingot§7 with the top-center empty",
                "§7  → 1× §fIron Chestplate");
        recipe("iron_leggings",
                "§7Crafting Table",
                "§7  7× §fIron Ingot§7 (top row + two columns)",
                "§7  → 1× §fIron Leggings");
        recipe("iron_boots",
                "§7Crafting Table",
                "§7  4× §fIron Ingot§7 (two columns, bottom 2 rows)",
                "§7  → 1× §fIron Boots");
        recipe("shield",
                "§7Crafting Table",
                "§7  6× §fOak Planks§7 + 1× §fIron Ingot§7 in the top center",
                "§7  → 1× §fShield");

        // ─── food / consumables ──────────────────────────────────────
        recipe("bread",
                "§7Crafting Table",
                "§7  3× §fWheat§7 in a row",
                "§7  → 1× §fBread");
        recipe("cake",
                "§7Crafting Table",
                "§7  3× §fMilk Bucket§7 + 2× §fSugar§7 + 1× §fEgg§7 + 3× §fWheat",
                "§7  → 1× §fCake");
        recipe("golden_apple",
                "§7Crafting Table",
                "§7  8× §fGold Ingot§7 around 1× §fApple",
                "§7  → 1× §fGolden Apple");
        recipe("golden_carrot",
                "§7Crafting Table",
                "§7  8× §fGold Nugget§7 around 1× §fCarrot",
                "§7  → 1× §fGolden Carrot");

        // ─── nether / end essentials ─────────────────────────────────
        recipe("ender_eye",
                "§7Crafting Table",
                "§7  1× §fEnder Pearl§7 + 1× §fBlaze Powder",
                "§7  → 1× §fEye of Ender");
        recipe("blaze_powder",
                "§7Crafting Table",
                "§7  1× §fBlaze Rod",
                "§7  → 2× §fBlaze Powder");
        recipe("ender_pearl",
                "§eDrop from §fEndermen§e or §fEnd Cities");
        recipe("totem_of_undying",
                "§eDropped by §fEvokers§e in §fWoodland Mansions / raids");

        // ─── transit / boats / minecarts ─────────────────────────────
        recipe("oak_boat",
                "§7Crafting Table",
                "§7  5× §fOak Planks§7 in a U",
                "§7  → 1× §fOak Boat");
        recipe("minecart",
                "§7Crafting Table",
                "§7  5× §fIron Ingot§7 in a U",
                "§7  → 1× §fMinecart");
        recipe("rail",
                "§7Crafting Table",
                "§7  6× §fIron Ingot§7 (2 columns) + 1× §fStick§7 (center)",
                "§7  → 16× §fRail");
        recipe("powered_rail",
                "§7Crafting Table",
                "§7  6× §fGold Ingot§7 + 1× §fStick§7 + 1× §fRedstone",
                "§7  → 6× §fPowered Rail");
        recipe("saddle",
                "§eFound in chests §7(dungeons / nether fortresses / bastions)");

        // ─── redstone ────────────────────────────────────────────────
        recipe("redstone_torch",
                "§7Crafting Table",
                "§7  1× §fRedstone§7 above 1× §fStick",
                "§7  → 1× §fRedstone Torch");
        recipe("repeater",
                "§7Crafting Table",
                "§7  2× §fRedstone Torch§7 + 1× §fRedstone§7 + 3× §fStone",
                "§7  → 1× §fRepeater");
        recipe("comparator",
                "§7Crafting Table",
                "§7  3× §fRedstone Torch§7 + 1× §fNether Quartz§7 + 3× §fStone",
                "§7  → 1× §fComparator");
        recipe("redstone_block",
                "§7Crafting Table",
                "§7  9× §fRedstone§7 in a 3×3 square",
                "§7  → 1× §fBlock of Redstone");

        // ─── misc ────────────────────────────────────────────────────
        recipe("book",
                "§7Crafting Table",
                "§7  3× §fPaper§7 + 1× §fLeather",
                "§7  → 1× §fBook");
        recipe("paper",
                "§7Crafting Table",
                "§7  3× §fSugar Cane§7 in a row",
                "§7  → 3× §fPaper");
        recipe("bookshelf",
                "§7Crafting Table",
                "§7  6× §fOak Planks§7 + 3× §fBook",
                "§7  → 1× §fBookshelf");
        recipe("name_tag",
                "§eFound in dungeon / mineshaft chests",
                "§eOr fished up rarely");
    }

    private static void recipe(String itemPath, String... lines) {
        TABLE.put(itemPath, new Entry(lines));
    }

    /** Add an entry at runtime (e.g. from another mod / config). */
    public static void add(String itemPath, String... lines) {
        TABLE.put(itemPath, new Entry(lines));
    }

    public static int knownRecipeCount() { return TABLE.size(); }

    private RecipePreview() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, tooltipType, lines) -> {
            if (!Qol.recipePreviewEnabled) return;
            if (stack == null || stack.isEmpty()) return;
            Item item = stack.getItem();
            Identifier rl = BuiltInRegistries.ITEM.getKey(item);
            if (rl == null) return;
            // Strip the namespace — the table is keyed by path so a
            // resource pack adding "minecraft:diamond_sword" still hits
            // the same entry as the vanilla one.
            Entry e = TABLE.get(rl.getPath());
            if (e == null) return;

            lines.add(Component.literal(""));
            MutableComponent header = Component.literal("[Recipe]")
                    .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true));
            lines.add(header);
            for (String l : e.lines) {
                lines.add(Component.literal(l));
            }
        });
    }
}

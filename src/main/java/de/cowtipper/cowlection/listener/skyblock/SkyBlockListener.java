package de.cowtipper.cowlection.listener.skyblock;

import de.cowtipper.cowlection.Cowlection;
import de.cowtipper.cowlection.config.MooConfig;
import de.cowtipper.cowlection.util.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkyBlockListener {
    private final Cowlection main;
    /**
     * timestamp example: 4/20/20 4:20 AM
     */
    private final Pattern SB_TIMESTAMP_PATTERN = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{2}) (\\d{1,2}):(\\d{2}) (AM|PM)$");
    private final NumberFormat numberFormatter;

    public SkyBlockListener(Cowlection main) {
        this.main = main;
        numberFormatter = NumberFormat.getNumberInstance(Locale.US);
        numberFormatter.setMaximumFractionDigits(0);
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent e) {
        if (e.itemStack == null || e.toolTip == null) {
            return;
        }
        // remove unnecessary tooltip entries: dyed leather armor
        NBTTagCompound nbtDisplay = e.itemStack.getSubCompound("display", false);
        if (nbtDisplay != null && nbtDisplay.hasKey("color", Constants.NBT.TAG_INT)) {
            if (Minecraft.getMinecraft().gameSettings.advancedItemTooltips) {
                e.toolTip.removeIf(line -> line.startsWith("Color: #"));
            } else {
                e.toolTip.removeIf(line -> line.equals(EnumChatFormatting.ITALIC + StatCollector.translateToLocal("item.dyed")));
            }
        }

        // remove unnecessary tooltip entries: enchantments (already added via lore)
        NBTTagList enchantments = e.itemStack.getEnchantmentTagList();
        if (enchantments != null) {
            for (int enchantmentNr = 0; enchantmentNr < enchantments.tagCount(); ++enchantmentNr) {
                int enchantmentId = enchantments.getCompoundTagAt(enchantmentNr).getShort("id");
                int enchantmentLevel = enchantments.getCompoundTagAt(enchantmentNr).getShort("lvl");

                if (Enchantment.getEnchantmentById(enchantmentId) != null) {
                    e.toolTip.remove(Enchantment.getEnchantmentById(enchantmentId).getTranslatedName(enchantmentLevel));
                }
            }
        }

        if (!MooConfig.showAdvancedTooltips && !Keyboard.isKeyDown(Keyboard.KEY_LMENU)) {
            return;
        }
        // add item age to tooltip
        NBTTagCompound extraAttributes = e.itemStack.getSubCompound("ExtraAttributes", false);
        if (extraAttributes != null && extraAttributes.hasKey("timestamp")) {
            String rawTimestamp = extraAttributes.getString("timestamp");
            Matcher sbTimestampMatcher = SB_TIMESTAMP_PATTERN.matcher(rawTimestamp);
            if (sbTimestampMatcher.matches()) {
                // Timezone = America/Toronto! headquarter is in Val-des-Monts, Quebec, Canada; timezone can also be confirmed by looking at the timestamps of New Year Cakes
                ZonedDateTime dateTime = getDateTimeWithZone(sbTimestampMatcher, ZoneId.of("America/Toronto")); // EDT/EST
                String dateTimeFormatted = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz"));

                int index = Math.max(0, e.toolTip.size() - (e.showAdvancedItemTooltips ? /* item name & nbt info */ 2 : 0));

                if (Keyboard.isKeyDown(Keyboard.KEY_LMENU)) {
                    // full tooltip
                    e.toolTip.add(index, "Timestamp: " + EnumChatFormatting.DARK_GRAY + dateTimeFormatted);
                    e.toolTip.add(index, "Item age: " + EnumChatFormatting.DARK_GRAY + Utils.getDurationAsWords(dateTime.toEpochSecond() * 1000).first());
                } else {
                    // abbreviated tooltip
                    e.toolTip.add(index, "Item age: " + EnumChatFormatting.DARK_GRAY + Utils.getDurationAsWord(dateTime.toEpochSecond() * 1000));
                }
            }
        }

        // for auction house: show price for each item if multiple items are sold at once
        if (e.entityPlayer != null && e.entityPlayer.openContainer instanceof ContainerChest) {
            int stackSize = e.itemStack.stackSize;
            if ((stackSize == 1 && !isSubmitBidItem(e.itemStack)) || e.toolTip.size() < 4) {
                // only 1 item or irrelevant tooltip - nothing to do here, abort!
                return;
            }

            if (isSubmitBidItem(e.itemStack)) {
                // special case: "place bid on an item" interface ("Auction View")
                ItemStack auctionedItem = e.entityPlayer.openContainer.getInventory().get(13);
                stackSize = auctionedItem.stackSize;
                if (stackSize == 1) {
                    // still only 1 item, abort!
                    return;
                }
            }

            List<String> toolTip = e.toolTip;

            // starting with i=1 because first line is never the one we're looking for
            for (int i = 1; i < toolTip.size(); i++) {
                String toolTipLineUnformatted = EnumChatFormatting.getTextWithoutFormattingCodes(toolTip.get(i));
                if (toolTipLineUnformatted.startsWith("Top bid: ")
                        || toolTipLineUnformatted.startsWith("Starting bid: ")
                        || toolTipLineUnformatted.startsWith("Buy it now: ")
                        || toolTipLineUnformatted.startsWith("Sold for: ")
                        || toolTipLineUnformatted.startsWith("New bid: ") /* special case: 'Submit Bid' item */) {

                    try {
                        long price = numberFormatter.parse(StringUtils.substringBetween(toolTipLineUnformatted, ": ", " coins")).longValue();
                        double priceEach = price / (double) stackSize;
                        String formattedPriceEach = priceEach < 5000 ? numberFormatter.format(priceEach) : Utils.formatNumberWithAbbreviations(priceEach);
                        String pricePerItem = EnumChatFormatting.YELLOW + " (" + formattedPriceEach + " each)";
                        toolTip.set(i, toolTip.get(i) + pricePerItem);
                        return;
                    } catch (ParseException ex) {
                        return;
                    }
                }
            }
        }
    }

    private ZonedDateTime getDateTimeWithZone(Matcher sbTimestampMatcher, ZoneId zoneId) {
        int year = 2000 + Integer.parseInt(sbTimestampMatcher.group(3));
        int month = Integer.parseInt(sbTimestampMatcher.group(1));
        int day = Integer.parseInt(sbTimestampMatcher.group(2));
        int hour = (Integer.parseInt(sbTimestampMatcher.group(4)) + (sbTimestampMatcher.group(6).equals("PM") ? 12 : 0)) % 24;
        int minute = Integer.parseInt(sbTimestampMatcher.group(5));

        LocalDateTime localDateTime = LocalDateTime.of(year, month, day, hour, minute);

        return ZonedDateTime.of(localDateTime, zoneId);
    }

    private boolean isSubmitBidItem(ItemStack itemStack) {
        return (itemStack.getItem().equals(Items.gold_nugget) || itemStack.getItem().equals(Item.getItemFromBlock(Blocks.gold_block)))
                && (itemStack.hasDisplayName() && (itemStack.getDisplayName().endsWith("Submit Bid") || itemStack.getDisplayName().endsWith("Collect Auction")));
    }
}

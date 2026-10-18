package com.resextraflags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.gui.FlagUtil;
import com.bekvon.bukkit.residence.gui.setFlagInfo;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import net.Zrips.CMILib.Container.PageInfo;
import net.Zrips.CMILib.GUI.CMIGui;
import net.Zrips.CMILib.GUI.CMIGuiButton;
import net.Zrips.CMILib.GUI.GUIManager.GUIRows;

/**
 * Residence 的 flag 菜单不给自定义 flag 提供标题和说明文字，内置 flag 的
 * 标题/描述也来自 Flags 枚举（无法按领地定制）。这里继承 FlagUtil 接管开菜单
 * 的入口，在按钮建好之后直接重写物品的 displayName 和 lore。
 *
 * 之前 PortalFlagUtil 的两种 hack（往 description 里塞 null 键、只改标题）
 * 都保留不下来多份不同描述：Flags.getFlag(自定义flag) 恒为 null，所有自定义
 * flag 共用同一个 null 键。所以现在统一在 ItemStack 上重建 lore。
 *
 * note：openUI 在 FlagUtil 里是 private，所以照抄一份。
 */
public class FlagGuiHook extends FlagUtil {

    private static final List<String> PORTAL_DESC = Arrays.asList(
            ChatColor.YELLOW + "开启后允许对面维度的掉落物穿过地狱门",
            ChatColor.YELLOW + "在你的领地内新建地狱门",
            ChatColor.YELLOW + "（一般用于黑曜石机）");

    private static final List<String> ANIMALS_DESC = Arrays.asList(
            ChatColor.YELLOW + "是否允许友好生物（动物）",
            ChatColor.YELLOW + "自然凭空刷新");

    private static final List<String> TRADER_DESC = Arrays.asList(
            ChatColor.YELLOW + "是否允许流浪商人自然刷新");

    private static final List<String> SPECIAL_DESC = Arrays.asList(
            ChatColor.YELLOW + "是否允许友好生物特殊刷新，",
            ChatColor.YELLOW + "包括动物、村民的繁殖与转化，",
            ChatColor.YELLOW + "铁傀儡的刷新与建造等");

    /** 被新三件套取代的旧细分 flag，从菜单里隐藏（命令仍可用，但不再生效） */
    private static final String[] LEGACY_FLAGS = {
            Flags.canimals.name(), Flags.nanimals.name(), Flags.sanimals.name()
    };

    public FlagGuiHook(Residence plugin) {
        super(plugin);
    }

    @Override
    public void openSetFlagGui(Player player, ClaimedResidence res, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;
        setFlagInfo info = new setFlagInfo(res, player, resadmin);
        info.recalculate();
        stripLegacy(info);
        openUi(info, new PageInfo(45, info.getButtons().size(), page), player,
                lm.Gui_Set_Title.getMessage(res.getName()));
    }

    @Override
    public void openPsetFlagGui(Player player, UUID targetPlayer, ClaimedResidence res, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;
        setFlagInfo info = new setFlagInfo(res, player, targetPlayer, resadmin);
        info.recalculate();
        stripLegacy(info);
        openUi(info, new PageInfo(45, info.getButtons().size(), page), player,
                lm.Gui_Pset_Title.getMessage(res.getName(), ResidencePlayer.getName(targetPlayer)));
    }

    /** 去掉旧的 canimals / nanimals / sanimals 按钮 */
    private static void stripLegacy(setFlagInfo info) {
        info.getButtons().removeIf(button -> isLegacy(button.getItem()));
    }

    private static boolean isLegacy(ItemStack item) {
        if (item == null)
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;
        String plain = ChatColor.stripColor(meta.getDisplayName());
        if (plain == null)
            return false;
        for (String legacy : LEGACY_FLAGS) {
            if (plain.equalsIgnoreCase(rawDisplay(legacy)))
                return true;
        }
        return false;
    }

    /** 照抄 FlagUtil.openUI（那边是 private），并在加按钮后重写标题与 lore */
    private void openUi(setFlagInfo info, PageInfo pi, Player player, String title) {
        CMIGui gui = new CMIGui(player) {
            @Override
            public void pageChange(int page) {
                openUi(info, new PageInfo(45, info.getButtons().size(), page), player, title);
            }
        };
        gui.setTitle(title);
        gui.setInvSize(GUIRows.r6);
        for (CMIGuiButton one : info.getButtons()) {
            if (pi.isContinue())
                continue;
            if (pi.isBreak())
                break;
            // 顺序不能反：addButton 内部第一件事就是调 button.updateLooks()，而 setFlagInfo
            // 的按钮把 updateLooks() 重写成了 setItem(updateLook(key)) —— 先改名会被当场重算覆盖。
            gui.addButton(one);
            one.setItem(renamed(one.getItem()));
        }
        gui.addPagination(pi);
        gui.fillEmptyButtons();
        gui.open();
    }

    /**
     * 按 flag 重写按钮：标题换中文名，lore 重建成
     * [状态行] + [本插件的说明] + [原生操作说明]。
     *
     * 原生 updateLook 的 lore 顺序是 状态行 + description + Gui_Actions，
     * 而 description 对自定义 flag 全是 null 键，所以中间部分直接丢弃重建。
     */
    static ItemStack renamed(ItemStack item) {
        if (item == null || item.getType().isAir())
            return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return item;
        String key = flagKeyOf(meta.getDisplayName());
        if (key == null)
            return item;

        String title = titleFor(key);
        if (title != null)
            meta.setDisplayName(lm.Gui_Flag_NameColor.getMessage() + title);

        List<String> lore = new ArrayList<>();
        List<String> old = meta.getLore();
        if (old != null && !old.isEmpty())
            lore.add(old.get(0)); // 状态行（真/假/未设置）
        List<String> desc = descFor(key);
        if (desc != null)
            lore.addAll(desc);
        lore.addAll(lm.Gui_Actions.getMessageList());
        meta.setLore(lore);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * 从按钮当前的显示名反推 flag 键。
     *
     * Residence 给按钮的标题是 CMIText.firstToUpperCase(flagName)，其中内置 flag
     * 的 flagName 是 Flags.getName()（可能已被语言文件翻译），自定义 flag 则是键名本身。
     * 所以这里用同样的算法计算期望显示名再比对。
     */
    static String flagKeyOf(String displayName) {
        String plain = displayName == null ? null : ChatColor.stripColor(displayName);
        if (plain == null)
            return null;
        String[] keys = {
                ResExtraFlags.PORTAL_FLAG,
                ResExtraFlags.ANIMALS_FLAG,
                ResExtraFlags.TRADER_FLAG,
                ResExtraFlags.SPECIAL_FLAG
        };
        for (String key : keys) {
            if (plain.equalsIgnoreCase(rawDisplay(key)))
                return key;
        }
        return null;
    }

    private static String rawDisplay(String flagKey) {
        Flags flag = Flags.getFlag(flagKey);
        return firstUpper(flag != null ? flag.getName() : flagKey);
    }

    private static String firstUpper(String value) {
        if (value == null || value.isEmpty())
            return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String titleFor(String key) {
        switch (key) {
        case ResExtraFlags.PORTAL_FLAG:
            return "Portalitem-创建地狱门";
        case ResExtraFlags.ANIMALS_FLAG:
            return "友好生物自然刷新";
        case ResExtraFlags.TRADER_FLAG:
            return "流浪商人刷新";
        case ResExtraFlags.SPECIAL_FLAG:
            return "友好生物特殊刷新";
        default:
            return null;
        }
    }

    private static List<String> descFor(String key) {
        switch (key) {
        case ResExtraFlags.PORTAL_FLAG:
            return PORTAL_DESC;
        case ResExtraFlags.ANIMALS_FLAG:
            return ANIMALS_DESC;
        case ResExtraFlags.TRADER_FLAG:
            return TRADER_DESC;
        case ResExtraFlags.SPECIAL_FLAG:
            return SPECIAL_DESC;
        default:
            return null;
        }
    }
}

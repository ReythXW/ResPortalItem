package com.resportalitem;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.bekvon.bukkit.residence.Residence;
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
 * Residence 的 flag 菜单不给自定义 flag 显示说明文字。
 *
 * 原因：setFlagInfo.description 是拿 Flags 枚举逐项填的，而自定义 flag 不在枚举里，
 * Flags.getFlag("portalitem") 返回 null，于是 updateLook 里那句
 * description.containsKey(flag) 永远为 false。
 *
 * 这里继承 FlagUtil 接管开菜单的入口，在 recalculate() 之前往那张 map 里补一条
 * null 键的说明 —— updateLook 查 null 就能命中。note：openUI 在 FlagUtil 里是
 * private，所以照抄一份。
 */
public class PortalFlagUtil extends FlagUtil {

    /** updateLook 里 flavor 前缀由 lm.Gui_Flag_NameColor 给，这里只需跟它一致地染色 */
    static final List<String> DESC = Arrays.asList(
            ChatColor.YELLOW + "开启后允许对面维度的掉落物穿过地狱门",
            ChatColor.YELLOW + "在你的领地内新建地狱门",
            ChatColor.YELLOW + "（一般用于黑曜石机）");

    /** 菜单里这个 flag 的标题。原生是 CMIText.firstToUpperCase(flagName)，也就是 Portalitem */
    static final String NAME = "Portalitem-创建地狱门";

    /** 原生标题（用来认出该改哪个按钮） */
    private static final String RAW_NAME = "Portalitem";

    public PortalFlagUtil(Residence plugin) {
        super(plugin);
    }

    @Override
    public void openSetFlagGui(Player player, ClaimedResidence res, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;
        setFlagInfo info = new setFlagInfo(res, player, resadmin);
        injectDesc(info);
        info.recalculate();
        openUi(info, new PageInfo(45, info.getButtons().size(), page), player,
                lm.Gui_Set_Title.getMessage(res.getName()));
    }

    @Override
    public void openPsetFlagGui(Player player, UUID targetPlayer, ClaimedResidence res, boolean resadmin, int page) {
        if (player == null || !player.isOnline())
            return;
        setFlagInfo info = new setFlagInfo(res, player, targetPlayer, resadmin);
        injectDesc(info);
        info.recalculate();
        openUi(info, new PageInfo(45, info.getButtons().size(), page), player,
                lm.Gui_Pset_Title.getMessage(res.getName(), ResidencePlayer.getName(targetPlayer)));
    }

    @SuppressWarnings("unchecked")
    private static void injectDesc(setFlagInfo info) {
        try {
            Field f = setFlagInfo.class.getDeclaredField("description");
            f.setAccessible(true);
            Object raw = f.get(info);
            if (raw instanceof Map)
                ((Map<Object, Object>) raw).put(null, DESC);
        } catch (Throwable t) {
            // 拿不到就退化成没有说明文字，菜单本身照常能用
        }
    }

    /**
     * 标题也没法配置：updateLook 里写死 setDisplayName(Gui_Flag_NameColor +
     * firstToUpperCase(flagName))，自定义 flag 不在 Flags 枚举里，没有翻译表可查。
     * 只能在按钮生成之后按显示名认出来再改。
     */
    static ItemStack renamed(ItemStack item) {
        if (item == null || item.getType().isAir())
            return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !isPortalitem(meta.getDisplayName()))
            return item;
        meta.setDisplayName(lm.Gui_Flag_NameColor.getMessage() + NAME);
        item.setItemMeta(meta);
        return item;
    }

    static boolean isPortalitem(String displayName) {
        String plain = displayName == null ? null : ChatColor.stripColor(displayName);
        return RAW_NAME.equalsIgnoreCase(plain);
    }

    /** 照抄 FlagUtil.openUI（那边是 private） */
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
}

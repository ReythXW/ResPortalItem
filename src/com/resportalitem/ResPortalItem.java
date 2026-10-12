package com.resportalitem;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.gui.FlagUtil;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;

/**
 * 让非玩家实体（掉落物等）能在领地内触发地狱门创建，而不需要放开 build 权限。
 *
 * 原理：Residence 的 PortalCreateEvent 处理器对"穿门实体不是 Player"的情况走
 * FlagPermissions.has(Flags.build, true)，也就是直接读领地的 build flag。本插件在
 * HIGHEST 优先级（晚于 Residence 的 NORMAL）接手它已经取消掉的事件，改用独立的
 * portalitem flag 重新判定，因此两者互不影响。
 */
public class ResPortalItem extends JavaPlugin implements Listener {

    /** 自定义领地 flag：控制非玩家实体能否在领地内触发建门 */
    public static final String FLAG = "portalitem";

    /** tab 补全里的别名。注册成"只含 portalitem 一个成员的 flag 组"，所以设它就是设 portalitem */
    public static final String ALIAS = "Portalitem-创建地狱门";

    /** /res set 菜单里这个 flag 的图标 */
    private static final String FLAG_GUI_ITEM = "OBSIDIAN";

    private boolean onlyItems;
    private boolean globalAllow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.onlyItems = getConfig().getBoolean("only-items", true);
        this.globalAllow = getConfig().getBoolean("global-allow", false);

        if (!globalAllow) {
            // Residence 官方扩展点：注册后 /res set|pset|gset 立即认得这个 flag，
            // 并且它会随领地存档一起持久化。
            FlagPermissions.addFlag(FLAG);
            getLogger().info("Registered custom flag '" + FLAG + "'.");
            getLogger().info("Use: /res set <residence> " + FLAG + " true");
            installAlias();
            installFlagGui();
        } else {
            getLogger().warning("global-allow=true -> non-player entities may create portals inside ANY residence.");
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * 让 tab 补全里同时有 portalitem 和"Portalitem-创建地狱门"，两者指向同一个值。
     *
     * 靠的是 Residence 的 flag 组：ResidencePermissions.setFlag 一进来就检查
     * validFlagGroups，命中就直接转发给 setFlagGroup（排在所有权限校验之前，且不做
     * toLowerCase）。组里只放 portalitem 一个成员，于是"设这个组"和"设 portalitem"
     * 完全等价——写进去的还是 portalitem，不带任何多余数据。
     *
     * 注意不能用 FlagPermissions.addFlag(ALIAS)：那会把别名加成一条独立 flag
     * （/res set 菜单会多出一个重复按钮），而且它末尾的 validFlagGroups.remove(flag)
     * 会把刚建好的组删掉。
     */
    @SuppressWarnings("unchecked")
    private void installAlias() {
        try {
            String key = ALIAS.toLowerCase();
            FlagPermissions.addFlagToFlagGroup(key, FLAG);

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(FlagPermissions.class, MethodHandles.lookup());

            // addFlagToFlagGroup 把组名强制小写了，而 tab 给的是 ALIAS 的展示写法，
            // 大小写对不上就落空，所以再挂一个展示写法的键指向同一份成员表。
            MethodHandle groupsGetter = lookup.findStaticGetter(FlagPermissions.class, "validFlagGroups", HashMap.class);
            HashMap<String, HashMap<String, FlagState>> groups =
                    (HashMap<String, HashMap<String, FlagState>>) groupsGetter.invoke();
            HashMap<String, FlagState> members = groups.get(key);
            if (members != null)
                groups.put(ALIAS, members);

            // tab 补全的候选来自 FlagPermissions.getAllPossibleFlags()，也就是这个静态列表；
            // 直接往里加名字就够了，不用碰 getAllFlags()（那才是菜单按钮的来源）。
            MethodHandle flagsGetter = lookup.findStaticGetter(FlagPermissions.class, "validFlags", ArrayList.class);
            ArrayList<String> validFlags = (ArrayList<String>) flagsGetter.invoke();
            if (!validFlags.contains(ALIAS))
                validFlags.add(ALIAS);

            getLogger().info("Registered flag alias '" + ALIAS + "' (same value as " + FLAG + ").");
        } catch (Throwable t) {
            getLogger().warning("Flag alias hook failed: " + t);
        }
    }

    /**
     * 让 /res set 菜单认识 portalitem：图标写在 flags.yml 的 Global.FlagGui 里，
     * 说明文字只能靠换掉 Residence 的 FlagUtil 实例来补（见 PortalFlagUtil）。
     */
    private void installFlagGui() {
        try {
            File flagsYml = new File(Residence.getInstance().getDataFolder(), "flags.yml");
            // FlagGui 段只是被 FlagUtil.load() 当"有哪些 flag 要显示图标"的名单用，
            // 所以这里必须先出现在 FlagPermission 段里，图标才会被读走。
            boolean a = injectSection(flagsYml, "FlagPermission:", "    " + FLAG + ": true");
            boolean b = injectSection(flagsYml, "FlagGui:", "    " + FLAG + ": " + FLAG_GUI_ITEM);
            if (a || b)
                getLogger().info("Added '" + FLAG + "' to flags.yml (FlagPermission/FlagGui).");

            Residence res = Residence.getInstance();
            res.getFlagUtilManager(); // 确保 Residence 那边已经建好实例

            PortalFlagUtil mine = new PortalFlagUtil(res);
            mine.load(); // 重新读 flags.yml，把图标装进 FlagData

            // 注意：getDeclaredField(s) 内部走 getDeclaredFields0，会一次性解析该类的
            // 全部字段类型；Residence 有个 WorldEditPlugin 字段而服务端没装 WorldEdit
            // -> NoClassDefFoundError。MethodHandle.findSetter 只解析目标字段这一个类型。
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Residence.class, MethodHandles.lookup());
            MethodHandle setter = lookup.findSetter(Residence.class, "FlagUtilManager", FlagUtil.class);
            setter.invoke(res, mine);
            getLogger().info("Hooked Residence flag GUI (icon + description).");
        } catch (Throwable t) {
            getLogger().warning("Flag GUI hook failed: " + t);
        }
    }

    /**
     * 往 flags.yml 的某个二级段落里插一行，保留文件原有的注释和排版。
     * 已经存在同名条目就原样不动。
     */
    private static boolean injectSection(File file, String header, String entry) {
        try {
            if (!file.isFile())
                return false;

            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));

            int h = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.trim().equals(header) && l.length() - l.stripLeading().length() == 2) {
                    h = i;
                    break;
                }
            }
            if (h < 0)
                return false;

            int insertAt = h + 1;
            for (int i = h + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.trim().isEmpty()) {
                    insertAt = i + 1;
                    continue;
                }
                if (l.length() - l.stripLeading().length() <= 2)
                    break; // 段落结束
                if (l.trim().equals(entry.trim()))
                    return false; // 已经有了
                insertAt = i + 1;
            }

            lines.add(insertAt, entry);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 点一下 flag 按钮后 setFlagInfo.updateLooks() 会拿 updateLook() 重造那个物品，
     * 标题又变回 Portalitem，所以在所有监听器跑完之后再补一刀。
     * CMIGui 会把点击 cancel 掉，原版逻辑不执行，这里直接改槽位是稳的。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory())
            return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir())
            return;
        if (clicked.getItemMeta() == null
                || !PortalFlagUtil.isPortalitem(clicked.getItemMeta().getDisplayName()))
            return;
        event.getClickedInventory().setItem(event.getSlot(), PortalFlagUtil.renamed(clicked));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR)
            return;

        // Residence 在 NORMAL 优先级；只有它已经拦下时才需要本插件接手
        if (!event.isCancelled())
            return;

        Entity entity = event.getEntity();
        if (entity instanceof Player)
            return; // 玩家亲自穿门 -> 完全按 build 判定，本插件不干预
        if (onlyItems && !(entity instanceof Item))
            return; // 配置为只放行掉落物时，怪物等一律不动

        if (globalAllow) {
            event.setCancelled(false);
            return;
        }

        // 遍历门涉及到的每一个方块。Residence 自己只取边界盒的两个对角，门跨界时会漏。
        List<BlockState> blocks = event.getBlocks();
        for (BlockState state : blocks) {
            ClaimedResidence res = Residence.getInstance().getResidenceManager().getByLoc(state.getLocation());
            if (res == null)
                continue; // 领地外本来就不受限
            // 默认值 false：领地没显式设过 portalitem 时一律禁止，与 build 完全无关
            boolean allow = res.getPermissions().has(FLAG, false);
            if (!allow)
                return; // 有任何一处不允许 -> 维持取消
        }

        event.setCancelled(false);
    }
}

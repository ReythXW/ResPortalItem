package com.resextraflags;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.gui.FlagUtil;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.utils.Utils;

/**
 * 给 Residence 补充/改写领地的实体相关 flag。
 *
 * 1. portalitem：非玩家实体（掉落物）可以在领地内创建地狱门，与 build 独立。
 * 2. animals：语义改写为「友好生物自然刷新」，只控制自然/区块生成的友好生物。
 * 3. wanderingtrader：控制流浪商人和商人羊驼的刷新。
 * 4. specialanimals：控制其余非自然来源的友好生物刷新（繁殖、治愈、铁傀儡等）。
 *
 * 生成判定在 HIGHEST 优先级接手：Residence 自己的 onCreatureSpawn 在 LOWEST
 * 按旧的 animals/nanimals/sanimals/canimals 逻辑取消事件，这里按新语义重判并
 * 覆盖结果。怪物不归本插件管，仍由 Residence 的 monsters 系列处理。
 */
public class ResExtraFlags extends JavaPlugin implements Listener {

    /** 自定义领地 flag：控制非玩家实体能否在领地内触发建门 */
    public static final String PORTAL_FLAG = "portalitem";

    /** portalitem 的 tab 补全别名（注册成只含 PORTAL_FLAG 的 flag 组） */
    public static final String PORTAL_ALIAS = "Portalitem-创建地狱门";

    /** 原 animals flag：现在只控制"自然刷新" */
    public static final String ANIMALS_FLAG = "animals";

    /** 流浪商人 / 商人羊驼刷新 */
    public static final String TRADER_FLAG = "wanderingtrader";

    /** 其余非自然来源的友好生物刷新 */
    public static final String SPECIAL_FLAG = "specialanimals";

    /** tab 补全 / /res set 里使用的中英对照别名（设置效果等同于对应的原名） */
    public static final String ANIMALS_ALIAS = "animals-友好生物自然刷新";
    public static final String TRADER_ALIAS = "wanderingtrader-流浪商人刷新";
    public static final String SPECIAL_ALIAS = "specialanimals-友好生物特殊刷新";

    /** /res set 菜单里的图标；同时用于往 flags.yml 注入 FlagPermission / FlagGui 两段 */
    private static final LinkedHashMap<String, String> GUI_ICONS = new LinkedHashMap<>();

    static {
        GUI_ICONS.put(PORTAL_FLAG, "OBSIDIAN");
        GUI_ICONS.put(TRADER_FLAG, "WANDERING_TRADER_SPAWN_EGG");
        GUI_ICONS.put(SPECIAL_FLAG, "GOLDEN_APPLE");
    }

    /**
     * 系统边缘生成：它们不是「刷新」，只是随别的东西带出来的生成事件
     * （坐骑、骑士、蜜蜂出巢、鹦鹉离肩、穿下界门、药水效果），
     * 不应由任何刷新 flag 管理 —— 遇到时保持放行。
     */
    private static final EnumSet<SpawnReason> IGNORED_REASONS = EnumSet.of(
            SpawnReason.MOUNT,
            SpawnReason.JOCKEY,
            SpawnReason.BEEHIVE,
            SpawnReason.SHOULDER_ENTITY,
            SpawnReason.NETHER_PORTAL,
            SpawnReason.POTION_EFFECT);

    private boolean onlyItems;
    private boolean globalAllow;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.onlyItems = getConfig().getBoolean("only-items", true);
        this.globalAllow = getConfig().getBoolean("global-allow", false);

        // 让内置 animals 的翻译名变成中英对照形式：
        //  - /res set 的 tab 补全会显示 f.getName()（= 这个翻译名）和原名 animals 两条
        //  - 输入 "animals-友好生物自然刷新" 时 Flags.getFlag 会直接归一化回 animals
        // 注意语言文件重载会把它覆盖回旧翻译，但下面的 flag 组仍能让别名生效。
        Flags.animals.setTranslated(ANIMALS_ALIAS);

        // 生成相关 flag 始终注册；global-allow 只影响建门逻辑
        FlagPermissions.addFlag(TRADER_FLAG);
        FlagPermissions.addFlag(SPECIAL_FLAG);

        if (!globalAllow) {
            // Residence 官方扩展点：注册后 /res set|pset|gset 立即认得这个 flag，
            // 并且它会随领地存档一起持久化。
            FlagPermissions.addFlag(PORTAL_FLAG);
            getLogger().info("Registered custom flag '" + PORTAL_FLAG + "'.");
            getLogger().info("Use: /res set <residence> " + PORTAL_FLAG + " true");
        } else {
            getLogger().warning("global-allow=true -> non-player entities may create portals inside ANY residence.");
        }

        installAliases();
        installFlagGui();
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * 注册中英对照别名：别名与原名指向同一个值。
     *
     * 靠的是 Residence 的 flag 组：ResidencePermissions.setFlag 会先用
     * Flags.getFlag 归一化（内置 flag 的翻译名也能直接命中），再检查
     * validFlagGroups，命中就转发给 setFlagGroup。组里只放原名一个成员，
     * 于是"设这个组"和"设原名"完全等价——写进去的还是原名，不带别名键。
     *
     * 注意不能用 FlagPermissions.addFlag(ALIAS)：那会把别名加成一条独立 flag
     * （/res set 菜单会多出一个重复按钮），而且它末尾的 validFlagGroups.remove(flag)
     * 会把刚建好的组删掉。要让它出现在 tab 里，直接往 validFlags 加即可。
     */
    private void installAliases() {
        registerAlias(PORTAL_ALIAS, PORTAL_FLAG);
        registerAlias(ANIMALS_ALIAS, ANIMALS_FLAG);
        registerAlias(TRADER_ALIAS, TRADER_FLAG);
        registerAlias(SPECIAL_ALIAS, SPECIAL_FLAG);
    }

    @SuppressWarnings("unchecked")
    private void registerAlias(String alias, String flag) {
        try {
            String key = alias.toLowerCase();

            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(FlagPermissions.class, MethodHandles.lookup());
            MethodHandle flagsGetter = lookup.findStaticGetter(FlagPermissions.class, "validFlags", ArrayList.class);
            ArrayList<String> validFlags = (ArrayList<String>) flagsGetter.invoke();

            // 重载插件时别名可能还留在 validFlags 里，而 addFlagToFlagGroup 有
            // "名字已是 flag 就不建组" 的守卫；先摘出去，保证组一定会重建。
            validFlags.remove(alias);
            validFlags.remove(key);

            FlagPermissions.addFlagToFlagGroup(key, flag);

            // addFlagToFlagGroup 把组名强制小写了，而 tab 给的是别名的展示写法，
            // 大小写对不上就落空，所以再挂一个展示写法的键指向同一份成员表。
            MethodHandle groupsGetter = lookup.findStaticGetter(FlagPermissions.class, "validFlagGroups", HashMap.class);
            HashMap<String, HashMap<String, FlagState>> groups =
                    (HashMap<String, HashMap<String, FlagState>>) groupsGetter.invoke();
            HashMap<String, FlagState> members = groups.get(key);
            if (members != null)
                groups.put(alias, members);

            // tab 补全的候选来自 getAllPossibleFlags()（validFlags ∪ validPlayerFlags）；
            // 自定义别名放这里就够了，不用碰 getAllFlags()（那才是菜单按钮的来源）。
            validFlags.add(alias);

            getLogger().info("Registered flag alias '" + alias + "' (same value as " + flag + ").");
        } catch (Throwable t) {
            getLogger().warning("Flag alias hook failed for '" + alias + "': " + t);
        }
    }

    /**
     * 让 /res set 菜单认识扩展 flag：图标写在 flags.yml 的 Global.FlagGui 里，
     * 标题和说明文字靠替换 Residence 的 FlagUtil 实例来补（见 FlagGuiHook）。
     */
    private void installFlagGui() {
        try {
            File flagsYml = new File(Residence.getInstance().getDataFolder(), "flags.yml");
            // FlagGui 段只是被 FlagUtil.load() 当"有哪些 flag 要显示图标"的名单用，
            // 所以这里必须先出现在 FlagPermission 段里，图标才会被读走。
            boolean changed = false;
            for (Map.Entry<String, String> icon : GUI_ICONS.entrySet()) {
                changed |= injectSection(flagsYml, "FlagPermission:", "    " + icon.getKey() + ": true");
                changed |= injectSection(flagsYml, "FlagGui:", "    " + icon.getKey() + ": " + icon.getValue());
            }
            if (changed)
                getLogger().info("Added extra flags to flags.yml (FlagPermission/FlagGui).");

            Residence res = Residence.getInstance();
            res.getFlagUtilManager(); // 确保 Residence 那边已经建好实例

            FlagGuiHook mine = new FlagGuiHook(res);
            mine.load(); // 重新读 flags.yml，把图标装进 FlagData

            // 注意：getDeclaredField(s) 内部走 getDeclaredFields0，会一次性解析该类的
            // 全部字段类型；Residence 有个 WorldEditPlugin 字段而服务端没装 WorldEdit
            // -> NoClassDefFoundError。MethodHandle.findSetter 只解析目标字段这一个类型。
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Residence.class, MethodHandles.lookup());
            MethodHandle setter = lookup.findSetter(Residence.class, "FlagUtilManager", FlagUtil.class);
            setter.invoke(res, mine);
            getLogger().info("Hooked Residence flag GUI (titles + descriptions).");
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
     * 标题和 lore 又变回原生样式，所以在所有监听器跑完之后再补一刀。
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
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null || FlagGuiHook.flagKeyOf(meta.getDisplayName()) == null)
            return;
        event.getClickedInventory().setItem(event.getSlot(), FlagGuiHook.renamed(clicked));
    }

    /**
     * 友好生物生成权限的最终裁决。
     *
     * Residence 在 LOWEST 优先级按旧逻辑（animals/nanimals/sanimals/canimals）
     * 可能已经取消事件。这里在 HIGHEST 用新语义重新判定：
     *
     * - 流浪商人 / 商人羊驼       -> wanderingtrader
     * - 铁傀儡（任何生成方式）     -> specialanimals
     * - 自然 / 区块生成           -> animals（友好生物自然刷新）
     * - 其余所有来源               -> specialanimals（友好生物特殊刷新）
     *
     * 怪物（Utils.isAnimal 为 false）不处理，交给 Residence 的 monsters 系列。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity == null)
            return;
        if (!Utils.isAnimal(entity))
            return;

        String flag;
        if (entity instanceof WanderingTrader || entity instanceof TraderLlama) {
            flag = TRADER_FLAG;
        } else if (entity instanceof IronGolem) {
            flag = SPECIAL_FLAG;
        } else if (IGNORED_REASONS.contains(event.getSpawnReason())) {
            // 系统边缘行为不归任何刷新 flag 管；覆盖 Residence 旧逻辑可能做的取消
            event.setCancelled(false);
            return;
        } else if (isNatural(event.getSpawnReason())) {
            flag = ANIMALS_FLAG;
        } else {
            flag = SPECIAL_FLAG;
        }

        FlagPermissions perms = FlagPermissions.getPerms(event.getLocation());
        // 默认 true：没有显式设置的领地保持原来的允许行为
        boolean allow = perms.has(flag, true);
        event.setCancelled(!allow);
    }

    /**
     * 自然凭空刷新：含区块生成，以及服务端给「自然刷出的非成年动物」用的
     * OCELOT_BABY（Bukkit 的历史命名，实际不是"村庄猫带幼崽"）。
     * 繁殖、刷怪笼、刷怪蛋、治愈等都不算。
     */
    private static boolean isNatural(SpawnReason reason) {
        return reason == SpawnReason.NATURAL
                || reason == SpawnReason.CHUNK_GEN
                || reason == SpawnReason.OCELOT_BABY;
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
            // 默认值 false：领地没显式设过 PORTAL_FLAG 时一律禁止，与 build 完全无关
            boolean allow = res.getPermissions().has(PORTAL_FLAG, false);
            if (!allow)
                return; // 有任何一处不允许 -> 维持取消
        }

        event.setCancelled(false);
    }
}

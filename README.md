# ResExtraFlags

给 [Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/) 补充和改写领地 flag。

包含两部分：

1. **`portalitem`** —— 让掉落物等非玩家实体在领地内创建地狱门，不需要放开领地的 `build` 权限。
2. **友好生物刷新权限改写** —— 把原本笼统的 `animals` 拆成三个独立开关。

> 本插件由 ResPortalItem 改名而来。升级时请**删除旧的 `ResPortalItem-1.0.0.jar`**，否则两个插件会同时接管同一批事件。

## 一、portalitem

黑曜石机之类的装置靠**掉落物穿门**触发对面传送门的生成。但 Residence 会拦住它：掉落物等非玩家实体穿门时同样按领地的 `build` flag 判定。想让装置工作就得放开 `build`，代价是整个领地都能被随意破坏。

本插件提供一个**独立于 `build` 的专用开关** `portalitem`，只放行"非玩家实体建门"这一件事，其余照旧。

- 自定义领地 flag `portalitem`，**默认 `false`**、不跟随 `build`，每个领地单独控制
- 玩家本人穿门**完全不受影响**，仍由 Residence 按 `build` 判定
- `/res set` 菜单里有专属图标（黑曜石）、中文标题与三条说明文字
- tab 补全含中文别名 `Portalitem-创建地狱门`（与 `portalitem` 是同一个值）
- 门跨领地边界时**逐方块判定**，不错过任何一处未放行的领地

## 二、友好生物刷新权限（改写）

Residence 原本用一个 `animals` 总开关控制所有友好生物的所有生成方式，`canimals` / `nanimals` / `sanimals` 再按生成原因细分。本插件把语义重写为：

| flag | GUI 标题 | 管什么 | 默认 |
|---|---|---|---|
| `animals` | **友好生物自然刷新** | 只控制**自然生成**（`NATURAL` / `CHUNK_GEN`）的友好生物，例如猪、牛、羊、鸡、鱿鱼、发光鱿鱼 | 允许 |
| `wanderingtrader` | **流浪商人刷新** | 流浪商人和商人羊驼的刷新 | 允许 |
| `specialanimals` | **友好生物特殊刷新** | 其余非自然来源的友好生物生成：繁殖、村民治愈、铁傀儡、刷怪蛋、刷怪笼、命令生成等（系统边缘事件除外，见下） | 允许 |

三个 flag 都提供**中英对照别名**，在 tab 补全里与原名并列显示，设置效果完全相同：

| 别名 | 等同 |
|---|---|
| `animals-友好生物自然刷新` | `animals` |
| `wanderingtrader-流浪商人刷新` | `wanderingtrader` |
| `specialanimals-友好生物特殊刷新` | `specialanimals` |

菜单里的说明文字：

- 友好生物自然刷新：*是否允许友好生物（动物）自然凭空刷新*
- 流浪商人刷新：*是否允许流浪商人自然刷新*
- 友好生物特殊刷新：*是否允许友好生物特殊刷新，包括动物、村民的繁殖与转化，铁傀儡的刷新与建造等*

判定顺序：

1. 流浪商人 / 商人羊驼 → `wanderingtrader`（不看生成原因）
2. 铁傀儡 → `specialanimals`（**任何形式**生成的铁傀儡都算特殊刷新）
3. **系统边缘生成 → 不归任何 flag，直接放行**
4. 自然 / 区块生成（含 `OCELOT_BABY`，即自然刷出的非成年动物）→ `animals`
5. 其余一切来源 → `specialanimals`

**系统边缘生成**（`MOUNT` / `JOCKEY` / `BEEHIVE` / `SHOULDER_ENTITY` / `NETHER_PORTAL` / `POTION_EFFECT`）不是真正的“刷新”，只是随其它东西带出来的生成事件——鸡骑士的坐骑、蜜蜂出巢、鹦鹉离肩、穿下界门等。它们不受三个刷新 flag 影响，始终放行。

三个 flag 的默认值都是**允许**：没显式设置过的领地保持原来的放行行为。想禁止必须显式设为 `false`。

### 旧的细分 flag

`canimals` / `nanimals` / `sanimals` 由本插件接管，**已从 GUI 隐藏，设置它们不再影响生成**。请改用上面三个新 flag。

- 命令仍然认得它们（为了不破坏旧存档/脚本），但生成判定只看新三件套。
- `animals` 的底层键名保持不变，已有领地的设置值继续有效，只是语义从"所有生成"缩窄为"自然刷新"。

### "友好生物"的范围

沿用 Residence 自己的 `Utils.isAnimal()`：`Animals`、`WaterMob`、`NPC`（村民/流浪商人）、蝙蝠、雪傀儡、铁傀儡、悦灵，以及新版本的特殊动物。怪物（僵尸、骷髅、苦力怕等）不归本插件管，仍由 Residence 的 `monsters` 系列处理。

### 与 Residence 的协作方式

Residence 的 `ResidenceEntityListener.onCreatureSpawn` 在 `LOWEST` 优先级按旧逻辑取消事件；本插件在 `HIGHEST` 优先级重新判定，允许时撤销取消、禁止时补上取消。因此旧 flag 的取消结果会被新语义覆盖。

> 副作用提示：因为无法区分"这个取消是谁做的"，本插件在放行时会把事件恢复为未取消。如果同一位置还有其它保护插件也取消了生成，也会被一并放开。

## 安装

1. 服务器需要 Residence（及其依赖 CMILib）
2. **删除旧的 `ResPortalItem-*.jar`（如果存在）**
3. 把 `ResExtraFlags-1.3.0.jar` 放进 `plugins/`
4. 重启或重载服务器

## 使用

```
# portalitem
/res set <领地> portalitem true
/res set <领地> portalitem false
/res set <领地> portalitem remove

# 友好生物刷新
/res set <领地> animals true|false|remove
/res set <领地> wanderingtrader true|false|remove
/res set <领地> specialanimals true|false|remove

# 用中英对照别名设置，效果完全相同
/res set <领地> animals-友好生物自然刷新 true
/res set <领地> wanderingtrader-流浪商人刷新 true
/res set <领地> specialanimals-友好生物特殊刷新 true
```

在 `/res set` 菜单里，标题和说明文字已改为中文。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `only-items` | `true` | 只放行掉落物；设为 `false` 时所有非玩家实体（含僵尸猪灵等怪物）都能触发建门 |
| `global-allow` | `false` | **危险开关**：忽略 `portalitem` 与 `build`，任何非玩家实体都能在任意领地建门。只影响建门，不影响生物刷新权限 |

## 原理

### portalitem

Residence 自己监听 `PortalCreateEvent`（NORMAL 优先级），对"穿门实体不是玩家"的情况直接按 `build` flag 取消事件。本插件在 **HIGHEST** 优先级接手它已取消的事件，改用 `portalitem` 重新判定。

### 生物刷新

本插件监听 `CreatureSpawnEvent`（HIGHEST 优先级），按实体类型和 `SpawnReason` 选择对应的新 flag，再覆盖 Residence 的取消结果。`animals` 只认 `NATURAL` 和 `CHUNK_GEN`，其余原因全部落到 `specialanimals`（铁傀儡和流浪商人优先按实体类型判定）。

## 依赖

- Paper / Spigot 1.21（`api-version: '1.21'`）
- Residence（`depend`）
- CMILib（Residence 的依赖）

## 从源码构建

本项目为手工 `javac` 编译（无 Gradle/Maven 脚本）。classpath 需要：服务端 API jar、Residence、CMILib、adventure-api 等依赖。

```bash
javac -encoding UTF-8 -cp "leaves-api.jar;Residence.jar;CMILib.jar;adventure-api.jar;adventure-key.jar" \
      -d build src/com/resextraflags/*.java
cp src/config.yml src/plugin.yml build/
cd build && jar cf ../ResExtraFlags-1.3.0.jar .
```

> Windows 下 classpath 分隔符为 `;`，Linux / macOS 下为 `:`。

## 作者

Nowhere

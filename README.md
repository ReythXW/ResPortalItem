# ResPortalItem

让**掉落物等非玩家实体**在 [Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/) 领地内创建地狱门 —— **不需要放开领地的 `build` 权限**。

## 解决什么问题

黑曜石机之类的装置靠**掉落物穿门**触发对面传送门的生成。但 Residence 会拦住它：掉落物等非玩家实体穿门时同样按领地的 `build` flag 判定。想让装置工作就得放开 `build`，代价是整个领地都能被随意破坏。

本插件提供一个**独立于 `build` 的专用开关** `portalitem`，只放行"非玩家实体建门"这一件事，其余照旧。

## 特性

- 自定义领地 flag `portalitem`，**默认 `false`**、不跟随 `build`，每个领地单独控制
- 玩家本人穿门**完全不受影响**，仍由 Residence 按 `build` 判定
- `/res set` 菜单里有专属图标（黑曜石）、中文标题与三条说明文字
- tab 补全含中文别名 `Portalitem-创建地狱门`（与 `portalitem` 是同一个值）
- 门跨领地边界时**逐方块判定**，不错过任何一处未放行的领地

## 安装

1. 服务器需要 [Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/)（及其依赖 CMILib）
2. 把 `ResPortalItem-1.0.0.jar` 放进 `plugins/`
3. 重启或重载服务器

## 使用

```
/res set <领地> portalitem true      # 允许掉落物在该领地建门
/res set <领地> portalitem false     # 禁止（即使 build 为 true）
/res set <领地> portalitem remove    # 取消设置，回到默认（禁止）
```

在 `/res set` 菜单里，该 flag 标题显示为「Portalitem-创建地狱门」，图标为黑曜石。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `only-items` | `true` | 只放行掉落物；设为 `false` 时所有非玩家实体（含僵尸猪灵等怪物）都能触发 |
| `global-allow` | `false` | **危险开关**：忽略 `portalitem` 与 `build`，任何非玩家实体都能在任意领地建门 |

## 原理

Residence 自己监听 `PortalCreateEvent`（NORMAL 优先级），对"穿门实体不是玩家"的情况直接按 `build` flag 取消事件。本插件在 **HIGHEST** 优先级接手它已取消的事件，改用 `portalitem` 重新判定 —— 两者互不干扰，装卸插件都不改变原有行为。

判定细节：

- 只处理 `NETHER_PAIR`（主世界 ↔ 下界配对生成）
- 玩家实体一概不问（交给 Residence 的 `build` 判定）
- 遍历门涉及的所有方块逐一查领地 —— Residence 原生只取边界盒的两个对角，门跨领地边界时会漏判

## 依赖

- Paper / Spigot 1.21（`api-version: '1.21'`）
- [Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/)（`depend`）
- CMILib（Residence 的依赖；本插件编译时也需要）

## 从源码构建

本项目为手工 `javac` 编译（无 Gradle/Maven 脚本）。classpath 需要：服务端 jar（`org.bukkit.*`）、Residence（`com.bekvon.bukkit.residence.*`）、CMILib（`net.Zrips.CMILib.*`）。

```bash
javac -encoding UTF-8 -cp "paper.jar;Residence.jar;CMILib.jar" -d build src/com/resportalitem/*.java
cd build && jar cf ../ResPortalItem-1.0.0.jar com config.yml plugin.yml
```

> Windows 下 classpath 分隔符为 `;`，Linux / macOS 下为 `:`。

## 作者

Nowhere

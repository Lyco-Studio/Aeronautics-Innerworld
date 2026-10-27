# 安装与开始远征

Aeronautics Innerworld 面向 Minecraft 1.21.1 / NeoForge。游戏使用 Java 21，可由启动器管理。

## 已验证的运行组合

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.247 |
| [Create](https://github.com/Creators-of-Create/Create) | 6.0.10 |
| [Create Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project) | 1.3.2 |
| [Sable](https://github.com/ryanhcode/sable) | 2.0.6 |
| Patchouli，可选 | NeoForge 1.21.1 版，93 或更新版 |

## 安装

1. 在启动器中创建 Minecraft 1.21.1 实例并安装 NeoForge。专用服务器也使用这一运行组合。
2. 从上游取得 Create、Create Aeronautics 和 Sable 的对应发布包，放入游戏目录的 `mods` 文件夹。
3. 将本包 `mods` 文件夹中的 Aeronautics Innerworld jar 放入同一文件夹。联机时，服务器与玩家客户端均安装本 mod 及必需依赖。
4. 启动前配置 Aeronautics 1.3.2 的加载顺序。在游戏目录的 `config/fml.toml` 中加入下表；已有 `[dependencyOverrides]` 时合并条目，只保留一个同名表。分发包中的 `examples/fml.toml` 提供同一配置示例。

```toml
[dependencyOverrides]
simulated = ["+create"]
aeronautics = ["+create", "+simulated"]
offroad = ["+create", "+simulated"]
```

此配置让 Create 先于 Simulated、Aeronautics 与 Offroad 初始化，处理该固定版本组合的并行加载冲突。

5. 启动游戏，确认模组列表中出现 Aeronautics Innerworld，再创建或进入世界。专用服务器先按 Minecraft 提示完成 EULA 配置。

可选安装 [Patchouli](https://modrinth.com/mod/patchouli) 以使用图文任务簿；需要此联动时，服务器和客户端一同安装。用书、铜锭和红石合成任务簿，可查看任务树、章节解锁、完成状态、配方和一次性里程碑奖励。

## 第一段旅程

用一本书和一个铜锭合成原版远征手册。取得安山机壳后，本 mod 的现有设备与补给配方会加入原版配方书。

先在主世界建立钻探与补给能力，用钻头进入地幔顶部并横向开挖基地。探测器在地幔基地内组装，随后开始熔岩海远征；地核阶段通过钻探连接抵达，以步行和个人装备完成探索。

完整顺序见[三维度生存路线](docs/PROGRESSION.md)，材料数量和施工布局可按其中的链接逐项查阅。地幔基地使用充能重生锚，床留在主世界或地核营地使用。

本包包含本 mod、原创文档及加载配置示例。依赖另行安装，其许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；本项目使用 [MIT 许可证](LICENSE)。

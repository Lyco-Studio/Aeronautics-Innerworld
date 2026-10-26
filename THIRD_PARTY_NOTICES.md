# 第三方依赖说明

本项目通过开发依赖使用下列软件；构建本项目的 mod 不会将这些 mod 或其资源打包进产物。各依赖的原始许可证和声明仍适用。

| 软件 | 许可证 | 使用要求 |
| --- | --- | --- |
| [Create](https://github.com/Creators-of-Create/Create/blob/mc1.21.1-6.0.10/LICENSE.md) | 代码为 MIT；美术等资源保留所有权利 | 复制代码须保留 MIT 版权及许可声明；资源不可按 MIT 自由使用。 |
| [Create Aeronautics / Simulated Project](https://github.com/Creators-of-Aeronautics/Simulated-Project/blob/3705df2062bebba42c3ab3d6b3dd09faa1512850/LICENSE.md) | 代码为 MIT；指定资源目录保留所有权利 | 复制代码须保留 MIT 版权及许可声明；资源另受限制。 |
| [Sable](https://github.com/ryanhcode/sable/blob/mc1.21.1-2.0.6-neoforge/LICENSE.md) | PolyForm Shield 1.0.0 | 具有非竞争限制，属于源码可用许可证，不是 OSI 开源许可证。当前用途为扩展其生态的附属 mod；后续功能须继续遵守非竞争条款。分发其软件须提供许可证或许可证链接及上游要求的声明。 |
| [Patchouli](https://github.com/VazkiiMods/Patchouli/blob/1.21.x/LICENSE) | CC BY-NC-SA 3.0 Unported | 含非商业及相同方式共享限制，不是 OSI 开源许可证。本项目使用独立安装的可选联动，不复制、修改或打包其代码和美术；原创任务书数据保持本项目许可证。重新分发或修改 Patchouli 时须遵守其署名、非商业及相同方式共享条款。 |
| Create 内置的 [Registrate](https://github.com/tterrag1098/Registrate/blob/1.21.1/dev/LICENSE)、Flywheel、Ponder | 分别为 MPL 2.0、MIT、MIT | 作为独立依赖使用；不复制或修改上游代码，不在本项目产物中重新打包。 |
| Sable 内置的 Sable Companion、Veil、Rapier 适配模块 | 分别为 MIT、LGPL 3.0、PolyForm Shield 1.0.0；Rapier 原生库为 Apache 2.0 | 使用上游发布包提供的组件，保留其许可证与声明；Rapier 适配模块遵守 Sable 的非竞争限制。 |
| [NeoForge](https://github.com/neoforged/NeoForge/blob/1.21.1/LICENSE.txt) | LGPL 2.1 | 作为独立加载器依赖使用；复制、修改或分发其代码时遵守对应义务。 |
| [ModDevGradle](https://github.com/neoforged/ModDevGradle/blob/main/LICENSE) | LGPL 2.1 | 仅用于构建工具链。 |
| [Gradle](https://github.com/gradle/gradle/blob/v9.2.1/LICENSE) | Apache 2.0 及其第三方声明 | 随仓库保留的 Wrapper 许可与声明见 `gradle/wrapper/LICENSE` 和 `gradle/wrapper/NOTICE`。 |
| GitHub Actions 的 [checkout](https://github.com/actions/checkout/blob/main/LICENSE) 与 [setup-java](https://github.com/actions/setup-java/blob/main/LICENSE) | MIT | 仅用于远程构建工作流，不进入 mod 发布包。 |

Minecraft 的下载和使用受 [Minecraft EULA](https://www.minecraft.net/eula) 约束。开发用 JDK 按其发行版许可证使用；Eclipse Temurin 的 OpenJDK 代码采用 GPLv2 并带 Classpath Exception，另含第三方许可声明。

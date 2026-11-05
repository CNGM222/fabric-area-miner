# Area Miner（Minecraft 1.21.11 / Fabric）

这是一个**仅客户端** Fabric mod：依次选定两个方块作为长方体区域的角点，显示区域线框，然后通过客户端交互管理器逐块挖掘；不会添加服务端代码、执行命令或直接修改世界方块。

## 默认按键

- **Z**：循环设置角点 1、角点 2。先把准星对准方块再按；选区包含两个端点方块。
- **F8**：开始/停止。正在运行时按 F8 会停止；完成、切换维度或无法找到安全路径时会自动停止。
- 两个按键均可在 Minecraft「选项 → 控制 → 按键绑定 → 区域挖掘」中修改。

选区用青色框显示，当前待挖方块以橙色框标出。再次按 Z 设置角点 1 会重新开始选区流程。

## 移动与安全边界

mod 通过 `attackBlock` / `updateBlockBreakingProgress` 使用常规方块挖掘交互，并等待实际视线射线命中目标。远距离时会在已加载区块内搜索安全地面路线，控制前进和单格跳跃；仅支持最多约 1 格高低差的地面寻路，不会游泳、攀爬、挖开路障或穿墙。到不了时会停止而不是绕过保护或强制挖掘。

- 最大选区体积：32,768 格；开始前会检查选区覆盖的所有区块已加载，避免只处理部分区域。
- 路径搜索上限：2,000 个地面节点；路点连续 100 tick 无进展时自动停止，避免卡住后持续移动。
- 不挖空气、流体和硬度为负的方块。
- 自动移动会暂时占用前进/跳跃键；停止时恢复原按键状态。请勿在移动过程中离开游戏或进入危险地形。
- 多人服务器可能禁止自动化操作；请先确认服务器规则。客户端模组不绕过服务器权限、领地保护或游戏模式限制。

## 构建

需要 JDK 21 或更新版本、Gradle 9.8 或更新版本，并能访问 Fabric Maven 与 Mojang 下载站点：

```powershell
gradle build
```

产物位于 `build/libs/`。将 remapped jar 放入客户端实例的 `mods` 文件夹，并安装相同 Minecraft 版本的 Fabric Loader 及 Fabric API。项目版本固定为 Minecraft 1.21.11、Yarn build 6、Fabric Loader 0.20.1、Fabric API 0.141.6+1.21.11。

## Release

推送 `v*` tag 会触发 GitHub Actions 构建；只有构建成功后才会创建 GitHub release 并附上 remapped mod jar。工作流使用 JDK 21 和 Gradle 9.8。

## Acknowledgements

寻路进度监测参考了 Baritone 这类客户端自动寻路器的设计思路；渲染和输入 API 依据 Fabric 官方 API。实现没有复制 Baritone、Litematica 或 VeinMiner 的源代码。

## License

当前仓库未指定开源许可证；公开可见不等于授予复制、修改或再发布权限。

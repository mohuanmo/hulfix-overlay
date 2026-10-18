# hulfix-overlay

HULFix v8 — LineageOS 20 (Android 13) 的 Xposed 悬浮通知（Heads-Up）模块：可编程的冷却引擎、消息去重台账、4 种内置悬浮窗样式与 Material 3 Expressive 设置 App。

## 架构总览

```
com.example.hulfix
├── MainHook                  Xposed 入口：多 hook 点挂载（SystemUI）
├── core/                     纯 JVM 逻辑（无 Android 依赖，可单测）
│   ├── Settings              全部可配置项模型（默认值对齐旧版硬编码）
│   ├── SettingsRepository    hook 进程读设置：广播携带全量 JSON（主通道）+ XSharedPreferences/mtime 兜底
│   ├── CooldownEngine        冷却引擎：决策链 21 种抑制原因，顺序见下
│   ├── MessageLedger         消息台账：LRU 256 条，同 key+同内容保鲜去重
│   └── SceneDetector         场景检测：熄屏/DND/锁屏/面板/通话/前台，TTL 缓存
├── overlay/                  悬浮窗渲染与交互
│   ├── OverlayController     中枢：视图池 / 状态机 / 生命周期锁 / 事件广播
│   ├── OverlayRenderer       StyleSpec → 视图树（真实悬浮窗与设置页预览共用）
│   ├── StyleSpec/StyleRegistry  数据驱动样式 + JSON 序列化（4 内置 + 自定义导入；contentHash 走 toJson().toString().hashCode()，规避 JSONObject.hashCode() 的跨实现语义分叉）
│   ├── LensShader            AGSL 真折射着色器源码（纯 JVM，可单测；SDF 法线 + Snell + 色散 + Schlick 菲涅尔）
│   ├── LensUniforms          折射参数包（纯 JVM，钳制/NaN 拦截，可单测）
│   ├── LensEngine/Impl       引擎门面与 API 33+ 实现（RuntimeShader 编译 + RenderEffect 挂载 + 自动降级）
│   ├── LensBackdropView      玻璃背景层：绘制"玻璃后方内容代理"并施加真折射
│   ├── SpringAnimation       自研物理弹簧（Choreographer 驱动，120Hz 自适应）
│   ├── AnimPresets           入场/退场/回弹/按压编排
│   ├── GestureHandler        滑动手势（方向锁 + 开关过滤）
│   ├── RingDrawable          倒计时进度环（霓虹样式）
│   └── NeonStrokeDrawable    渐变扫掠描边（霓虹样式）
├── ui/                       Material 3 Expressive 设置 App
│   ├── SettingsActivity      平板自适应（sw≥600dp 切 NavigationRail）+ 5 页保活导航（add + show/hide）+ 主题色应用
│   ├── SettingsViews         开关/滑块/分段按钮/入口项/限宽容器（640dp 居中）
│   ├── StyleFragment         样式选择 + 实时预览 + 文件/JSON 导入 + 内置规范 + 主题颜色
│   ├── StyleImportDoc        内置样式 JSON 规范全文与示例（与 StyleImportTest 互锁防漂移）
│   ├── AnimFragment          动画/手势设置 + 预览
│   ├── PositionFragment      9 宫格锚点 + 偏移/缩放微调
│   ├── AppsFragment          黑名单/白名单/前台抑制 + 通知类型过滤
│   ├── EngineFragment        引擎状态/暂停/冷却规则/台账/忽略列表/场景开关
│   ├── AppPickerDialog       应用多选（后台加载 + 搜索 + 系统应用过滤）
│   ├── LedgerDialog          消息台账可视化（弹出/抑制历史）
│   ├── IgnoredDialog         已忽略通知管理（可单条解除）
│   ├── SettingsStore         App 侧唯一写入口：落盘 + 150ms 合并广播热更新
│   └── StatsReceiver         接收 SystemUI 回报事件（统计/台账镜像/忽略列表）
└── stub/                     Xposed API stub（compileOnly）
```

## 冷却引擎决策链

按成本与优先级排序，`CooldownEngine.evaluate` 依次判定，命中即返回（`Decision.reason` 为 21 种中文可查原因之一）：

| 顺序 | 规则 | 说明 |
|---|---|---|
| 1 | 总开关 | 模块整体开关 |
| 2 | 手动暂停 | 设置页 5/15/30/60 分钟，墙钟截止 |
| 3 | 场景阻断 | 熄屏 / 免打扰 / 锁屏 / 面板展开 / 通话中 / 前台抑制（均可开关） |
| 4 | 通知属性 | 常驻 / 前台服务 / 媒体会话 / 低优先级频道（均可开关） |
| 5 | 过期通知 | 迟到超过保鲜期（8s）直接丢弃 |
| 6 | 应用过滤 | 黑名单 / 白名单 |
| 7 | **引擎开关** | 关闭后仅保留 1-6 硬规则 |
| 8 | **用户划掉** | 最高优先级引擎规则：显式操作强于时间冷却（key/应用/全局三范围 + 持久化忽略列表） |
| 9 | 同内容去重 | 同 key + 同内容哈希 + 10s 保鲜期内 → 抑制（**多 hook 点重复弹出的主防线**） |
| 10-12 | 冷却 | 同 key / 同应用 / 全局 |
| 13-14 | 限流 | 突发窗口 / 每分钟上限 |

消息防重复弹出有四重防线：台账同内容保鲜（9）→ key 冷却（10）→ 控制器 `currentKey+hash` 比对 → 生命周期锁（展示/退场期间拒绝新通知）。

## 悬浮窗样式

数据驱动（`StyleSpec` JSON），同一渲染器产出，设置页内实时预览：

| id | 名称 | 特性 |
|---|---|---|
| `standard` | 标准安卓 | AOSP 纯色、快进快出、零模糊成本 |
| `m3e_glass` | M3 Expressive 磨砂玻璃 | 28dp 圆角 + 有界背景模糊（卡片级霜面）+ 弹簧入场 |
| `ios_liquid` | iOS 26 液态玻璃 | **AGSL 真折射引擎**（默认，API 33+）：SDF 法线 + Snell 定律 + RGB 色散 + Schlick 菲涅尔 + 边缘厚度环；不满足条件时自动降级为双层折射描边 + 强模糊 + 凝华动效 |
| `neon_pulse` | 霓虹脉冲（自研） | 暗色玻璃 + 渐变扫掠描边 + 倒计时进度环 + 呼吸脉冲点 |

自定义样式：设置页「从文件导入 / 粘贴 JSON 导入」，支持单个对象 `{...}`、顶层数组 `[{...}]`、`{"styles":[...]}` 三种形态；数值自动钳制，防止非法 JSON 导致渲染异常。字段规范与示例已内置 App——导入区「查看样式规范」可随时查看（含三种文件形态、通用字段、深浅配色字段、几何排版字段取值范围与默认值、元素开关、accentMode、动画默认值），一键复制示例 JSON；该示例被 `StyleImportTest` 端到端导入断言，保证文档与实现不漂移。

## AGSL 真折射引擎

液态玻璃不再是"画出来的假玻璃"（模糊 + 亮边描边），而是自研的基于物理的折射引擎：

```
着色器管线（每像素）：
  1. 圆角矩形 SDF → 有符号距离场 d（内部为负，IQ 约定）
  2. SDF 中心差分 → 解析外法线 n（面板中心法线垂直屏幕；圆角边缘法线倾斜 = 透镜）
  3. Snell 定律：refract(I, n, 1/IOR) → 光线进入玻璃的偏折方向
  4. 按偏折方向偏移采样背景纹理 → 背景像素真实位移
  5. RGB 三通道不同折射率（±3%）→ 边缘彩虹色散
  6. Schlick 菲涅尔 F0≈4% + 掠射角增强 + 边缘厚度环 → 真实"边缘发光"
```

| 组件 | 职责 |
|---|---|
| `LensShader` | AGSL 源码 + uniform 契约（纯 JVM，结构可单测） |
| `LensUniforms` | 参数包：钳制、NaN/Infinity 拦截（防止 GPU 花屏） |
| `LensEngine` / `LensEngineImpl` | 门面 + API 33+ 实现：`RuntimeShader` 编译、`RenderEffect` 挂载 |
| `LensBackdropView` | 玻璃背景层：绘制"玻璃后方内容代理"（渐变底 + 放大 App 图标 + 光泽带）并施加折射；前景文字是兄弟 View，保持清晰 |

**配合方式**：引擎只在 `variant=liquid` 且设置开关打开且 API ≥ 33 时启用（视图池键含引擎位，开关切换自动重建）；不满足时任一条件，渲染回退到传统模拟路径，行为与旧版完全一致。着色器编译失败、驱动异常均在 View 侧静默降级。uniform 为静态值（不逐帧刷新），悬浮窗停留期间零额外 GPU 开销。

**可调参数**（设置页「真折射引擎」）：折射强度（0-48px 位移）/ 边缘透镜（0-100%）/ 色散（0-100%）/ 镜面高光（0-200%）/ 折射率 IOR（1.01-2.0，1.5=普通玻璃）。修改即时反映在预览中，真实悬浮窗在下次通知时生效。

**验证方式**：着色器源码经 glslangValidator 真编译校验（SkSL→GLSL 垫层，EXIT=0）；物理效果经 CPU 光栅化对比图验证（该验证曾抓出 SDF 距离约定写反导致整块玻璃不可见的致命 bug）。

## 手势

方向锁定滑动（横/竖可分别开关）、下拉展开通知栏、点击打开、长按 0.5s 忽略、未过阈值果冻回弹、按压缩放反馈。阈值（距离/速度）均可在设置页调整。

## 平板适配

- `smallestScreenWidthDp ≥ 600` 自动切换 NavigationRail 侧边导航（横屏手机不误判）
- 所有设置内容限宽 640dp 居中（`min(640dp, 屏宽)`，窄屏不裁切）
- 9 宫格锚点、预览卡片纯代码构建，按比例缩放
- `configChanges` 含 `screenLayout|uiMode`，`onConfigurationChanged` 手动刷新导航形态与预览

## 页面切换流畅度

设置 App 的 5 页导航从 `replace()`（每次销毁重建整棵 Fragment 视图树 + 重新编译样式预览着色器）改为 **`add()` 一次 + `show()/hide()` 保活**：

- Fragment 视图树只构建一次，切页零重建，滚动位置与滑块状态天然保留
- Activity 重建（深色/浅色切换、主题色切换、旋转）后按 tag 经 `findFragmentByTag` 换回 FragmentManager 托管实例，`show()` 不会抛异常
- 转场为 90ms 淡出 + 210ms 淡入的 cross-fade（`SpecialEffectsController` 会应用 `setCustomAnimations`）
- 预览防抖：`StylePreviewView` 记录**外观指纹**（只含影响构建路径的字段：内容哈希、深浅色、透镜开关、背景透明度、圆角/宽度缩放、最大行数、App 名开关），指纹不变仅热更新 uniform，不再重建视图、不再编译 AGSL 着色器；折射滑杆参数（厚度/边缘/色散/高光/IOR）刻意不入指纹——拖滑杆只走 uniform 热更新
- 样式列表的 mini 预览使用 `createLightweight()`（关透镜的 Settings 副本），N 张样式卡不再触发 N 次着色器编译

## 悬浮窗退场与模糊残留修复

收回悬浮窗时，磨砂玻璃的霜面曾短暂残留约一帧才消失。根因：`BackgroundBlurDrawable` 的模糊区域经窗口 Aggregator 交给 SurfaceFlinger 合成，**不随视图 alpha/位移动画淡出**——退场动画期间卡片已透明飞出，霜面矩形仍以全不透明度停留到 `removeView` 才消失。

修复（双保险）：

1. `dismissAnimated` 播放退场动画前先 `detachWindowBlur()`（摘除背景 + 恢复透明度），霜面与卡片一同退场
2. `removeOverlayImmediate` 优先同步 `removeViewImmediate`，省掉一帧 `post` 延迟；异常时回退异步移除 + 代际守卫（`mRemoveGeneration` 防止过期回调误删）

配套修复：`detachWindowBlur` 恢复透明度后，视图池复用的卡片下次弹出时背景会变成全不透明、遮住背景模糊（磨砂变纯色）——`applyWindowBlur` 挂载成功后幂等补回 `bgAlphaUserScale × BLUR_BG_DISCOUNT` 折扣。

## 主题颜色

设置页「主题颜色」区提供 9 个色板：第 1 个为 Google 四色渐变 = 跟随壁纸动态取色（`DynamicColors`，API < 31 自动 no-op），其余 8 个为预设种子色（主题 overlay 方式，`Theme.applyStyle` 公开 API）。每套种子色按 M3 色调系统生成浅色/深色双套共 72 个角色色（primary/secondary 8 个强调角色，中性背景沿用基础主题），点击即 `recreate()` 生效，深浅色切换（`onConfigurationChanged`）后自动重应用。

## 导入功能验证

`StyleImportTest`（18 例）覆盖三种文件形态、空/null/垃圾 JSON、缺字段回落默认值（逐字段断言）、越界 clamp（上下界）、7 种颜色解析、调色板回落、批量导入、`StyleRegistry.parseCustom + byId` 端到端、以及**内置规范示例的端到端导入**（示例字段断言 + JSON 往返哈希一致，文档与实现互锁）。

## 构建

```bash
# 需要 JDK 17 + Android SDK (platform 34, build-tools 34.0.0)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/
./gradlew test                 # 76 个 JVM 单元测试（引擎/台账/样式/透镜/导入）
```

`init.gradle` 为国内 Maven 镜像加速（阿里云），海外用户可删除。

## 测试覆盖

- `CooldownEngineTest`（20 例）：去重 / 三级冷却 / 突发与分钟限流 / 划掉三范围 / 场景 / 过滤 / 过期 / 引擎关闭 / 统计
- `MessageLedgerTest`（7 例）：计数 / 排序 / LRU 淘汰 / 序列化 / 划掉标记
- `StyleSpecTest`（10 例）：4 内置 / JSON 往返 / 三种导入形态 / 钳制 / 颜色 / 调色板
- `StyleImportTest`（18 例）：三种导入形态 / 缺字段回落 / clamp / 颜色解析 / 内置规范示例端到端 / 批量导入 / `StyleRegistry` 端到端 / 内容哈希同异判定
- `LensShaderTest`（7 例）：RenderEffect 入口签名 / 物理算子齐全（refract×3 色散）/ uniform 声明与常量一致 / 括号配平 / 无占位符 / 外部透明 / 无 GLSL 残留构造
- `LensUniformsTest`（9 例）：越界钳制 / NaN 与 Infinity 拦截 / 高光色转换 / 相位回绕 / equals 契约 / 默认值可用性

## 已知边界

- `getRunningTasks(1)` 检测前台应用仅在配置了前台抑制列表时查询（SystemUI 为系统进程有权限）；Android 13+ 已 deprecated 但系统进程仍可用
- 通话检测用 `AudioManager.getMode() != MODE_NORMAL`，录音场景可能误报（可在设置页关闭）
- iOS 液态玻璃在 API 33+ 上使用自研 AGSL 真折射引擎（物理折射 + 色散 + 菲涅尔）；API < 33 或驱动不兼容时自动降级为有界背景模糊 + 双描边 + 高光的视觉模拟，保证低端机兼容

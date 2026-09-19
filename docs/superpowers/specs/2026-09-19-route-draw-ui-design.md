# 绘制路线界面改版 — 设计

- 日期：2026-09-19
- 状态：设计已确认，待实现
- 基分支：`master`（绘制路线功能已合并，CI 已验证）
- 改动分支：`feat/route-draw-ui`

## 目标

重构 `RouteDrawActivity` 的界面层：顶部加搜索跳转，工具条从右侧移到左侧并改为悬浮片，去掉面板底板，新增图层切换。

**只动界面层。** 绘制层、几何、校验、数据层一律不碰 —— 那些已经过单元测试与真机验证。

## 非目标

- 不改绘制行为（点绘制/线绘制/闭合/密化的逻辑与手势一律不动）
- 不改存储格式与数据模型
- 不做真背景模糊（原因见下）
- 不改 `RouteDrawOverlayView` 的任何绘制与触摸逻辑

## 为什么不做真毛玻璃

百度地图渲染在一块 **`SurfaceView`** 上，继承链已用 `javap` 核实：

```
MapView extends ViewGroup
  └─ MapSurfaceView extends ah (RenderSurfaceView) extends android.view.SurfaceView
```

`SurfaceView` 由系统合成器在**独立图层**绘制，**不经过 View 绘制树**。而 `RenderEffect` 模糊的是「该 View 自己画的内容」——悬浮片背后的地图像素根本不在其中，所以糊不到任何东西。`Window.setBackgroundBlurRadius` 模糊的是**窗口背后**（其他应用），而地图在本窗口**内部**，同样不通。

唯一可能做到的技术路径是把工具片放进独立系统悬浮窗（`TYPE_APPLICATION_OVERLAY` + `FLAG_BLUR_BEHIND`），但那要重做触摸分发与生命周期，且效果不保证。**已裁定不做。**

结论：采用**半透明悬浮片**——这也是绝大多数 Android 应用所谓「毛玻璃 UI」的实际做法。

## 布局

```
┌──────────────────────────────────────┐
│  [🔍 搜索地点…                    ]   │ ← 搜索栏，占满宽减去图层按钮
│                            [ 图层 ]   │ ← 图层切换，搜索栏右下方
│                                       │
│                                       │
│ [‹]                                   │ ← 折叠箭头（常驻，是工具条第一项）
│ [点绘制]                              │
│ [线绘制]                              │ ← 工具条：贴左侧边缘，纵向居中
│ [锁定地图]                            │   每片独立悬浮
│ [密化]                                │
│ [撤销上一步]                          │
│ [清空]                                │
│ [完成绘制]                            │ ← 主题色强调
│                                       │
│            已绘制 3 个点              │ ← 状态文本，底部居中
└──────────────────────────────────────┘
```

`FrameLayout` 子视图顺序（决定 Z 序，后来者在上、先拿到触摸）：

1. `MapView`（底）
2. `RouteDrawOverlayView`（绘制层）
3. 顶部搜索区
4. 左侧工具条
5. 建议列表（搜索时才显示，须在绘制层之上以便接收触摸）
6. 状态文本

## 五处改动

### 1. 顶部搜索

- 复用百度 `SuggestionSearch`（与 `MainActivity` 同款），`setOnQueryTextListener` 中按输入发 `SuggestionSearchOption`
- 建议列表显示在搜索栏正下方；**必须是绘制层之上的兄弟视图**，否则绘制层会吃掉触摸
- 选中某条 → `mBaiduMap.animateMapStatus(target=该点, zoom=18)`，随后清空输入框、隐藏列表、清除焦点
- **不落标记、不弹信息窗、不写搜索历史库** —— 这是绘制界面，落下的点会与正在画的路线混淆

### 2. 工具条移到左侧

- 原 `<include>` 的 `layout_gravity` 从 `end` 改为 `start`
- 纵向居中（`center_vertical`），避开顶部搜索栏与底部状态文本
- 顺带修掉老毛病：`<include>` 上的 `layout_width="wrap_content"` 会覆盖被包含根声明的宽度，导致侧栏实际按内容定宽。改版后每片独立定位，该问题自然消失

### 3. 折叠（箭头控制）

- 箭头片常驻，是工具条的第一项
- **默认展开**
- 展开时箭头指向左（`‹`），点击收起；收起时指向右（`›`），点击展开
- 收起后除箭头外全部 `GONE`
- 箭头需要 `contentDescription`（无障碍）

### 4. 去掉底板

- 删掉 `route_draw_tools.xml` 根 `ScrollView` 的 `android:background="#F2FFFFFF"`
- 七个工具各自成为独立悬浮片
- 原文字「工具」收起按钮**删除**（被箭头取代）

### 5. 图层切换

- 位置：搜索栏的右下方
- 切换 `BaiduMap.MAP_TYPE_NORMAL` ↔ `MAP_TYPE_SATELLITE`
- 按钮文字反映**当前**状态：普通图时显示「卫星」（点了切过去），卫星图时显示「普通」

## 悬浮片材质规格

| 项 | 值 |
|---|---|
| 背景 | 白色，alpha ≈ 0.88 |
| 圆角 | 22dp（胶囊形） |
| 描边 | 1dp，白色 alpha 0.35 —— 「玻璃片」观感的关键 |
| 阴影 | elevation 4dp |
| 文字 | 深色，不透明底上保证可读 |
| 片间距 | 8dp |
| 「完成绘制」 | 背景用主题色 `#008577`，文字白色，与其余片区分 |

实现方式：一个圆角背景 drawable（`res/drawable/`），不需要新依赖。

## 文件影响

**改**

- `app/src/main/res/layout/activity_route_draw.xml` —— 大改
- `app/src/main/res/layout/route_draw_tools.xml` —— 改为悬浮片列（或并入主布局）
- `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java` —— 搜索接线、图层切换、折叠逻辑
- `app/src/main/res/values/strings.xml` —— 搜索提示、图层文案、箭头无障碍描述

**新增**

- `app/src/main/res/drawable/bg_tool_chip.xml` —— 圆角背景；强调色那片另用 `bg_tool_chip_accent.xml`

**不动**

- `RouteDrawOverlayView`、`RouteGeometry`、`RouteNameValidator`、`DataBaseRoute`、任何数据层与几何逻辑

## 验证方式

**这次与前一轮有本质不同：界面效果我可以自己看。**

我有 `adb`，可以 `exec-out screencap` 截图并直接查看。流程：

1. 改 → 构建 → `adb install -r`
2. 用户打开一次绘制界面（Activity 未导出，我无法自行拉起）
3. **我截图查看**，不满意自行调整，重复 2–3
4. 视觉层不需要用户反复用文字描述

用户需做的：每轮把界面打开一次。

编译与既有 33 个单元测试必须保持绿色，但它们覆盖不到视觉，所以**截图是本轮的主要验证手段**。

## 明确不做

- 绘制层的手势、渲染、不变量（上一轮已完成并验证）
- 数据层的任何改动
- 搜索历史记录（不做持久化）
- 真背景模糊（已裁定）
- 旋转不丢状态（仍由锁竖屏规避）

## 待确认（协调者的解读，不对则改）

1. **工具条纵向居中** —— 用户原话「工具栏垂直靠左侧」，我理解为「纵向排列 + 贴左边缘」，纵向位置取居中。若想要顶部对齐，改一个 gravity 即可，截图中可直观比较
2. **「完成绘制」强调色** —— 取主题色 `#008577`（`colorPrimary`，应用其他处一致）。截图中可见实际观感

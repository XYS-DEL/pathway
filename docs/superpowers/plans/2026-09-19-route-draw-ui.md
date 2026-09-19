# 绘制路线界面改版 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 `RouteDrawActivity` 的界面层——顶部搜索跳转、工具条移至左侧并改为悬浮片、箭头折叠、图层切换。

**Architecture:** 三层叠加不变（`MapView` → 绘制层 → 控件），只重做控件层。工具从「一个带底板的 `ScrollView` 面板」改为「左侧一列独立悬浮片」，每片是一个带圆角背景的 `TextView`/`RadioButton`。搜索复用百度 `SuggestionSearch`（与 `MainActivity` 同款）。**不碰绘制层、几何、校验、数据层。**

**Tech Stack:** Java 11、compileSdk 32 / minSdk 27、百度地图 SDK、AppCompat、JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-19-route-draw-ui-design.md`

## Global Constraints

- 包根 `com.iterlocus.pathway`；注释与提交信息用**中文**
- 用户可见文案进 `res/values/strings.xml`；无障碍描述也要给（箭头按钮）
- 出错记 `XLog.e("ROUTE: ERROR - <method>")` 并降级，不抛异常、不崩溃（作用于运行时数据路径）
- **不得修改** `RouteDrawOverlayView`、`RouteGeometry`、`RouteNameValidator`、`DataBaseRoute` —— 绘制行为与数据层一个都不动
- 路线坐标一律按 **BD09**；本改版不涉及坐标转换
- 坐标换算一律经 `Projection`；地图类型切换用 `BaiduMap.MAP_TYPE_NORMAL` / `MAP_TYPE_SATELLITE`
- 提交信息末尾加一行：`Co-Authored-By: Claude Code <noreply@anthropic.com>`
- 验证命令：`./gradlew assembleDebug lintDebug testDebugUnitTest`（33 个既有单元测试必须保持绿色）

## 已核实的外部事实（不要重新怀疑）

- **百度地图渲染在 `SurfaceView` 上**：`MapView extends ViewGroup`，内部 `MapSurfaceView extends ah(RenderSurfaceView) extends android.view.SurfaceView`。故 `RenderEffect` 糊不到地图——**本改版不做真背景模糊**，用半透明悬浮片。
- `BaiduMap.setMapType(int)` 存在，`MAP_TYPE_NORMAL` / `MAP_TYPE_SATELLITE` 为既有常量（`MainActivity` 已在用）。
- `SuggestionSearch.newInstance()` / `setOnGetSuggestionResultListener` / `requestSuggestion(SuggestionSearchOption)` / `destroy()` 均存在（`MainActivity` 已在用）。
- 当前 `activity_route_draw.xml` 的子视图顺序决定 Z 序：后来者在上、先拿到触摸。**绘制层是第 2 个子视图**，所以任何需要接收触摸的控件（搜索框、建议列表、工具片）都必须排在它**之后**。

## 三个任务的布局分工（同一文件的不同区域，顺序执行）

`activity_route_draw.xml` 被三个任务共同编辑，各自只动自己的区域，**不得重排或删除别人的区域**：

| Task | 负责区域 |
|---|---|
| 1 | 左侧工具条（`route_draw_tools`）、状态文本位置、三个背景 drawable |
| 2 | 顶部搜索栏（`route_draw_search`）、建议列表（`route_draw_suggestion_list`） |
| 3 | 图层按钮（`route_draw_layer_button`） |

---

### Task 1: 工具条悬浮片化与箭头折叠

**Files:**
- Create: `app/src/main/res/drawable/bg_tool_chip.xml`
- Create: `app/src/main/res/drawable/bg_tool_chip_toggle.xml`
- Create: `app/src/main/res/drawable/bg_tool_chip_accent.xml`
- Rewrite: `app/src/main/res/layout/route_draw_tools.xml`
- Delete: `app/src/main/res/layout/route_draw_tools.xml` 的 `ScrollView` 底板与文字「工具」按钮
- Modify: `app/src/main/res/layout/activity_route_draw.xml`（工具条区域）
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: 无（第一个任务）
- Produces（后续任务不得改动这些 id）：`R.id.route_draw_tools`、`R.id.route_draw_tools_toggle`、`R.id.route_draw_mode_group`、`R.id.route_draw_mode_point`、`R.id.route_draw_mode_line`、`R.id.route_draw_lock_map`、`R.id.route_draw_btn_densify`、`R.id.route_draw_btn_undo`、`R.id.route_draw_btn_clear`、`R.id.route_draw_btn_finish`、`R.id.route_draw_status`

- [ ] **Step 1: 加三个背景 drawable**

`app/src/main/res/drawable/bg_tool_chip.xml` —— 普通悬浮片（半透明白 + 圆角 + 细描边）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 悬浮片底板：半透明白 + 胶囊圆角 + 细描边。
     不做真模糊——地图渲染在 SurfaceView 的独立图层上，RenderEffect 看不见它。
     这条描边是「玻璃片」观感的关键，别去掉。 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E0FFFFFF" />
    <corners android:radius="22dp" />
    <stroke
        android:width="1dp"
        android:color="#59FFFFFF" />
</shape>
```

`app/src/main/res/drawable/bg_tool_chip_toggle.xml` —— 可选中片的状态选择器（模式、锁定）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 可选中片：未选中为半透明白，选中为主题色实底。
     模式选择与锁定开关共用这一个。 -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/colorPrimary" />
            <corners android:radius="22dp" />
            <stroke
                android:width="1dp"
                android:color="#59FFFFFF" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#E0FFFFFF" />
            <corners android:radius="22dp" />
            <stroke
                android:width="1dp"
                android:color="#59FFFFFF" />
        </shape>
    </item>
</selector>
```

`app/src/main/res/drawable/bg_tool_chip_accent.xml` —— 主操作片（完成绘制）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 主操作片：主题色实底，与其余片区分。 -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/colorPrimary" />
    <corners android:radius="22dp" />
</shape>
```

- [ ] **Step 2: 加字符串**

在 `strings.xml` 的 `</resources>` 之前追加：

```xml
    <!-- 绘制路线 · 改版 -->
    <string name="route_draw_tools_collapse">收起工具</string>
    <string name="route_draw_tools_expand">展开工具</string>
    <string name="route_draw_arrow_collapse">‹</string>
    <string name="route_draw_arrow_expand">›</string>
```

- [ ] **Step 3: 重写 `route_draw_tools.xml` 为悬浮片列**

整体替换为下面内容。要点：**根 `LinearLayout` 无背景**（原 `ScrollView` 的 `#F2FFFFFF` 底板删掉）、每片独立 `bg_tool_chip`、`RadioButton`/`CheckBox` 用 `android:button="@null"` 隐去系统圆点、片间 8dp。

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 左侧工具条：独立悬浮片，无底板。
     箭头片常驻（收起后只剩它）；其余片随折叠一并 GONE。
     模式选择与锁定开关用 android:button="@null" 隐去系统自带的圆点/勾选框，
     只靠 bg_tool_chip_toggle 的选中态表达。 -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/route_draw_tools"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="start|center_vertical"
    android:layout_marginStart="12dp"
    android:orientation="vertical">

    <TextView
        android:id="@+id/route_draw_tools_toggle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_tool_chip"
        android:clickable="true"
        android:contentDescription="@string/route_draw_tools_collapse"
        android:focusable="true"
        android:minWidth="44dp"
        android:paddingHorizontal="14dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_arrow_collapse"
        android:textColor="#212121"
        android:textSize="18sp"
        android:textStyle="bold" />

    <RadioGroup
        android:id="@+id/route_draw_mode_group"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:orientation="vertical">

        <RadioButton
            android:id="@+id/route_draw_mode_point"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_tool_chip_toggle"
            android:button="@null"
            android:checked="true"
            android:paddingHorizontal="16dp"
            android:paddingVertical="10dp"
            android:text="@string/route_draw_mode_point"
            android:textColor="#212121" />

        <RadioButton
            android:id="@+id/route_draw_mode_line"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:background="@drawable/bg_tool_chip_toggle"
            android:button="@null"
            android:paddingHorizontal="16dp"
            android:paddingVertical="10dp"
            android:text="@string/route_draw_mode_line"
            android:textColor="#212121" />
    </RadioGroup>

    <CheckBox
        android:id="@+id/route_draw_lock_map"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:background="@drawable/bg_tool_chip_toggle"
        android:button="@null"
        android:checked="true"
        android:paddingHorizontal="16dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_lock_map"
        android:textColor="#212121" />

    <TextView
        android:id="@+id/route_draw_btn_densify"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:background="@drawable/bg_tool_chip"
        android:clickable="true"
        android:focusable="true"
        android:paddingHorizontal="16dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_btn_densify"
        android:textColor="#212121" />

    <TextView
        android:id="@+id/route_draw_btn_undo"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:background="@drawable/bg_tool_chip"
        android:clickable="true"
        android:focusable="true"
        android:paddingHorizontal="16dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_btn_undo"
        android:textColor="#212121" />

    <TextView
        android:id="@+id/route_draw_btn_clear"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:background="@drawable/bg_tool_chip"
        android:clickable="true"
        android:focusable="true"
        android:paddingHorizontal="16dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_btn_clear"
        android:textColor="#212121" />

    <TextView
        android:id="@+id/route_draw_btn_finish"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:background="@drawable/bg_tool_chip_accent"
        android:clickable="true"
        android:focusable="true"
        android:paddingHorizontal="16dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_btn_finish"
        android:textColor="#FFFFFF"
        android:textStyle="bold" />

</LinearLayout>
```

**注意**：这个文件现在自带 `android:id="@+id/route_draw_tools"` 和 `layout_gravity`，所以主布局里的 `<include>` 标签要相应调整（见下一步）。

- [ ] **Step 4: 改 `activity_route_draw.xml` 的工具条区域**

把下面这整段（它当前就在文件里，逐字如此）删除：

```xml
    <!-- 面板收起时的入口。
         刻意放在 start|top：面板是 end 侧、match_parent 高、且在子视图列表里更靠后，
         会盖住 end 侧的一切——放 end|top 的话面板一打开，收起按钮就被自己盖住，关不掉。 -->
    <Button
        android:id="@+id/route_draw_tools_toggle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="start|top"
        android:layout_margin="12dp"
        android:text="@string/route_draw_tools_toggle" />

    <include
        android:id="@+id/route_draw_tools"
        layout="@layout/route_draw_tools"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="end" />
```

在同一位置替换为：

```xml
    <!-- 左侧工具条。放在绘制层之后，才会先拿到触摸。
         宽度/高度/gravity 由被包含的根自己声明，这里不再覆盖
         （旧版在 <include> 上写 layout_width 会覆盖根的声明，是那个老毛病的来源）。 -->
    <include
        layout="@layout/route_draw_tools"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />
```

同时把状态文本从 `start|bottom` 改到底部居中，避免与左侧工具条相撞：

```xml
    <TextView
        android:id="@+id/route_draw_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_margin="12dp"
        android:background="@drawable/bg_tool_chip"
        android:padding="8dp"
        android:text="@string/route_draw_status_idle"
        android:textSize="12sp" />
```

- [ ] **Step 5: 改 `RouteDrawActivity` 的字段类型与折叠逻辑**

按钮从 `Button` 变成 `TextView`，`mUndoButton` 的类型要改：

```java
    private TextView mUndoButton;
```

在 `onCreate` 里，原来的 `findViewById(R.id.route_draw_tools_toggle).setOnClickListener(v -> toggleToolsPanel());` 换成折叠逻辑。把 `toggleToolsPanel()` 整个替换为：

```java
    /**
     * 折叠/展开工具条。箭头片常驻，其余片随之一并隐藏。
     *
     * <p>箭头方向即「点了会往哪边动」：展开时指左（收起），收起时指右（展开）。
     */
    private void toggleToolsPanel() {
        boolean collapsed = mToolsPanel.getVisibility() != View.VISIBLE;
        applyToolsCollapsed(!collapsed);
    }

    private void applyToolsCollapsed(boolean collapsed) {
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        findViewById(R.id.route_draw_mode_group).setVisibility(visibility);
        findViewById(R.id.route_draw_lock_map).setVisibility(visibility);
        findViewById(R.id.route_draw_btn_densify).setVisibility(visibility);
        findViewById(R.id.route_draw_btn_undo).setVisibility(visibility);
        findViewById(R.id.route_draw_btn_clear).setVisibility(visibility);
        findViewById(R.id.route_draw_btn_finish).setVisibility(visibility);

        TextView toggle = findViewById(R.id.route_draw_tools_toggle);
        toggle.setText(collapsed
                ? R.string.route_draw_arrow_expand
                : R.string.route_draw_arrow_collapse);
        toggle.setContentDescription(getResources().getString(collapsed
                ? R.string.route_draw_tools_expand
                : R.string.route_draw_tools_collapse));
    }
```

`mToolsPanel` 这个字段现在没有用了（工具条本身就是那个 `LinearLayout`，不再需要单独引用它的可见性），**把字段声明与它在 `onCreate` 里的赋值一并删掉**。

`onCreate` 里加一行，确保初始状态是展开：

```java
        applyToolsCollapsed(false);
```

放在 `toggleToolsPanel` 的监听器注册之后。

- [ ] **Step 6: 编译并跑既有测试**

Run: `./gradlew assembleDebug lintDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，33 个单元测试全绿。若有 `findViewById` 类型不匹配的编译错误，检查 Step 5 的字段类型改动。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/res/drawable/bg_tool_chip.xml app/src/main/res/drawable/bg_tool_chip_toggle.xml app/src/main/res/drawable/bg_tool_chip_accent.xml app/src/main/res/layout/route_draw_tools.xml app/src/main/res/layout/activity_route_draw.xml app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java app/src/main/res/values/strings.xml
git commit -m "feat: 工具条改为左侧悬浮片，箭头控制折叠"
```

---

### Task 2: 顶部搜索跳转

**Files:**
- Modify: `app/src/main/res/layout/activity_route_draw.xml`（顶部区域、建议列表）
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 1 的 `R.id.route_draw_tools`（只读，不修改）
- Produces: `R.id.route_draw_search`、`R.id.route_draw_suggestion_list`

- [ ] **Step 1: 加字符串**

```xml
    <string name="route_draw_search_hint">搜索地点</string>
```

- [ ] **Step 2: 布局加搜索栏与建议列表**

在 `activity_route_draw.xml` 里，**紧跟在绘制层 `RouteDrawOverlayView` 之后**插入（必须在绘制层之后才拿得到触摸）：

```xml
    <!-- 顶部搜索栏。放在绘制层之后 —— 否则绘制层会吃掉触摸。 -->
    <androidx.appcompat.widget.SearchView
        android:id="@+id/route_draw_search"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:layout_marginStart="12dp"
        android:layout_marginTop="12dp"
        android:layout_marginEnd="80dp"
        android:background="@drawable/bg_tool_chip"
        android:iconifiedByDefault="false"
        android:queryHint="@string/route_draw_search_hint" />

    <!-- 搜索建议列表：同样在绘制层之后。默认隐藏，输入时才显示。 -->
    <ListView
        android:id="@+id/route_draw_suggestion_list"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:layout_marginStart="12dp"
        android:layout_marginTop="76dp"
        android:layout_marginEnd="80dp"
        android:background="@drawable/bg_tool_chip"
        android:visibility="gone" />
```

`layout_marginEnd="80dp"` 是给 Task 3 的图层按钮留位（该按钮在搜索栏右下方）。**Task 3 会用到这个间距，不要改掉。**

- [ ] **Step 3: 接线搜索**

在 `RouteDrawActivity` 加字段：

```java
    private SearchView mSearchView;
    private ListView mSuggestionList;
    private SuggestionSearch mSuggestionSearch;
```

顶部补 import：

```java
import android.widget.ArrayAdapter;

import androidx.appcompat.widget.SearchView;

import com.baidu.mapapi.search.sug.SuggestionResult;
import com.baidu.mapapi.search.sug.SuggestionSearch;
import com.baidu.mapapi.search.sug.SuggestionSearchOption;
```

在 `onCreate` 末尾加：

```java
        initSearchView();
```

新增方法：

```java
    /**
     * 顶部搜索：输入时向百度要建议，选中后把地图平移过去。
     *
     * <p>刻意不落标记、不弹信息窗、不写搜索历史——这是绘制界面，
     * 落下的点会与正在画的路线混淆。
     */
    private void initSearchView() {
        mSearchView = findViewById(R.id.route_draw_search);
        mSuggestionList = findViewById(R.id.route_draw_suggestion_list);
        mSuggestionSearch = SuggestionSearch.newInstance();

        final List<SuggestionResult.SuggestionInfo> suggestions = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        mSuggestionList.setAdapter(adapter);

        mSuggestionSearch.setOnGetSuggestionResultListener(result -> {
            suggestions.clear();
            adapter.clear();
            if (result == null || result.getAllSuggestions() == null) {
                mSuggestionList.setVisibility(View.GONE);
                return;
            }
            for (SuggestionResult.SuggestionInfo info : result.getAllSuggestions()) {
                // 没有坐标的条目（如纯行政区划）跳过，否则点了无处可去
                if (info == null || info.pt == null) {
                    continue;
                }
                suggestions.add(info);
                adapter.add(info.key);
            }
            mSuggestionList.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);
        });

        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText == null || newText.trim().isEmpty()) {
                    mSuggestionList.setVisibility(View.GONE);
                    return false;
                }
                mSuggestionSearch.requestSuggestion(new SuggestionSearchOption()
                        .city(MainActivity.mCurrentCity == null ? "" : MainActivity.mCurrentCity)
                        .keyword(newText));
                return true;
            }
        });

        mSuggestionList.setOnItemClickListener((parent, view, position, id) -> {
            SuggestionResult.SuggestionInfo picked = suggestions.get(position);
            mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newMapStatus(
                    new MapStatus.Builder().target(picked.pt).zoom(18.0f).build()));
            mSuggestionList.setVisibility(View.GONE);
            mSearchView.setQuery("", false);
            mSearchView.clearFocus();
        });
    }
```

在 `onDestroy` 里加销毁（百度要求显式释放）：

```java
        mSuggestionSearch.destroy();
```

- [ ] **Step 4: 编译并跑既有测试**

Run: `./gradlew assembleDebug lintDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，33 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/layout/activity_route_draw.xml app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java app/src/main/res/values/strings.xml
git commit -m "feat: 绘制界面顶部搜索，选中后平移地图"
```

---

### Task 3: 图层切换按钮

**Files:**
- Modify: `app/src/main/res/layout/activity_route_draw.xml`（图层按钮）
- Modify: `app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Task 1 的 `bg_tool_chip`（复用）、Task 2 在搜索栏右侧预留的 80dp
- Produces: `R.id.route_draw_layer_button`

- [ ] **Step 1: 加字符串**

按钮文字反映**当前**状态（「点它会切到哪」）：

```xml
    <string name="route_draw_layer_satellite">卫星图</string>
    <string name="route_draw_layer_normal">普通图</string>
```

- [ ] **Step 2: 布局加图层按钮**

在 `activity_route_draw.xml` 里，紧跟在搜索栏之后、建议列表之前插入（它要在搜索栏的右下方）：

```xml
    <!-- 图层切换：搜索栏的右下方。文字反映当前状态，点它会切到另一种。 -->
    <TextView
        android:id="@+id/route_draw_layer_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="top|end"
        android:layout_marginTop="68dp"
        android:layout_marginEnd="12dp"
        android:background="@drawable/bg_tool_chip"
        android:clickable="true"
        android:focusable="true"
        android:paddingHorizontal="14dp"
        android:paddingVertical="10dp"
        android:text="@string/route_draw_layer_satellite"
        android:textColor="#212121"
        android:textSize="13sp" />
```

（`marginTop="68dp"` 让它落在搜索栏下方；搜索栏占约 12+56dp，这里略低一点，压在它右下。）

- [ ] **Step 3: 接线图层切换**

在 `RouteDrawActivity` 加字段：

```java
    /** 当前是否为卫星图。默认 false，与 BaiduMap 的初始状态一致。 */
    private boolean mSatellite;
```

在 `onCreate` 里加：

```java
        initLayerButton();
```

新增方法：

```java
    /** 图层切换：普通图 ↔ 卫星图。按钮文字显示的是「点它会切到哪」。 */
    private void initLayerButton() {
        TextView button = findViewById(R.id.route_draw_layer_button);
        updateLayerButtonText(button);
        button.setOnClickListener(v -> {
            mSatellite = !mSatellite;
            mBaiduMap.setMapType(mSatellite
                    ? BaiduMap.MAP_TYPE_SATELLITE
                    : BaiduMap.MAP_TYPE_NORMAL);
            updateLayerButtonText(button);
        });
    }

    private void updateLayerButtonText(TextView button) {
        button.setText(mSatellite
                ? R.string.route_draw_layer_normal
                : R.string.route_draw_layer_satellite);
    }
```

顶部补 import（`BaiduMap` 若已存在则跳过）：

```java
import com.baidu.mapapi.map.BaiduMap;
```

- [ ] **Step 4: 编译并跑既有测试**

Run: `./gradlew assembleDebug lintDebug testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，33 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/layout/activity_route_draw.xml app/src/main/java/com/iterlocus/pathway/RouteDrawActivity.java app/src/main/res/values/strings.xml
git commit -m "feat: 绘制界面图层切换"
```

---

## 完成标准

- `./gradlew assembleDebug lintDebug testDebugUnitTest` 全绿，33 个既有单元测试不变
- 装机后：搜索能出建议、选中后地图平移过去；工具条在左侧、可折叠、箭头方向正确；图层按钮能切到卫星图；**绘制功能（点/线/闭合/密化/保存）与改版前一致**

## 视觉验证（由协调者执行，不是任务）

编译与单元测试覆盖不到视觉。改版完成后由协调者用 `adb exec-out screencap` 截图查看，不满意则自行调整。用户需在每轮把界面打开一次（Activity 未导出，协调者无法自行拉起）。

## 明确不做

- 绘制层、几何、校验、数据层的任何改动
- 真背景模糊（已裁定：地图在 `SurfaceView` 上，`RenderEffect` 糊不到）
- 搜索历史持久化
- 搜索选中点直接作为绘制起点（属另一功能）

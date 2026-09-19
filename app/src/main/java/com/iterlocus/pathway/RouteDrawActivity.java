package com.iterlocus.pathway;

import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.search.sug.SuggestionResult;
import com.baidu.mapapi.search.sug.SuggestionSearch;
import com.baidu.mapapi.search.sug.SuggestionSearchOption;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.database.DataBaseRoute;
import com.iterlocus.pathway.utils.GoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 绘制路线：在百度地图上点绘或线绘一条路线，可闭合、可密化，最后存为本地配置。
 *
 * <p>这是<b>绘制</b>，不是模拟。开始模拟、沿路线移动都在后续的线路模拟功能里。
 */
public class RouteDrawActivity extends BaseActivity {

    /**
     * 密化一次能新增的点数上限。
     *
     * <p>没有上限时，输入六七位数会让 densify 在主线程上做 N 次带线性扫描的迭代并逐个分配对象，
     * 随后绘制层还要为每个点每帧画一个圆（且整个绘制层跑在软件层上）。轻则 ANR，
     * 重则 OutOfMemoryError——那是 Error，任何 catch (Exception) 都拦不住，
     * 而点集只存在内存里，用户整条手绘路线会一起丢掉。
     */
    private static final int MAX_DENSIFY_ADD_COUNT = 1000;

    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private RouteDrawOverlayView mOverlay;

    private TextView mStatusText;
    private TextView mUndoButton;

    private SearchView mSearchView;
    private ListView mSuggestionList;
    private SuggestionSearch mSuggestionSearch;

    /** 最近一次请求的关键字。用于丢弃过期响应：用户可能已经清空输入框或又改了字。 */
    private String mPendingKeyword = "";

    private SQLiteDatabase mRouteDb;

    /** 只在进入界面时取一次位置，把地图居中；拿到就停。 */
    private LocationClient mLocClient;

    /** 撤销栈：每次「落点 / 完成一次拖绘 / 闭合 / 密化」压一份点集+闭合标记的快照。 */
    private final List<Snapshot> mUndoStack = new ArrayList<>();

    private static final class Snapshot {
        final List<LatLng> points;
        final boolean closed;

        Snapshot(List<LatLng> points, boolean closed) {
            this.points = points;
            this.closed = closed;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));
        setContentView(R.layout.activity_route_draw);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        try {
            mRouteDb = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - open database");
        }

        mMapView = findViewById(R.id.route_draw_map);
        mBaiduMap = mMapView.getMap();
        mBaiduMap.setMapStatus(MapStatusUpdateFactory.newMapStatus(
                new MapStatus.Builder().zoom(18.0f).build()));

        mOverlay = findViewById(R.id.route_draw_overlay);
        mOverlay.setBaiduMap(mBaiduMap);
        mOverlay.setOnRouteChangedListener(this::onRouteChanged);

        mStatusText = findViewById(R.id.route_draw_status);
        mUndoButton = findViewById(R.id.route_draw_btn_undo);

        findViewById(R.id.route_draw_tools_toggle).setOnClickListener(v -> toggleToolsPanel());
        applyToolsCollapsed(false);

        RadioGroup modeGroup = findViewById(R.id.route_draw_mode_group);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int mode = checkedId == R.id.route_draw_mode_line
                    ? RouteDrawOverlayView.MODE_LINE : RouteDrawOverlayView.MODE_POINT;
            mOverlay.setDrawMode(mode);
            updateStatusText();
        });

        CheckBox lockMap = findViewById(R.id.route_draw_lock_map);
        lockMap.setOnCheckedChangeListener((button, checked) -> {
            applyMapGestures(checked);
            mOverlay.setDrawEnabled(checked);
        });

        // 默认锁定：进入就能直接画
        applyMapGestures(true);
        mOverlay.setDrawEnabled(true);

        centerOnCurrentLocation();

        findViewById(R.id.route_draw_btn_densify).setOnClickListener(v -> showDensifyDialog());
        findViewById(R.id.route_draw_btn_undo).setOnClickListener(v -> undo());
        findViewById(R.id.route_draw_btn_clear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.route_draw_btn_finish).setOnClickListener(v -> showSaveDialog());

        updateStatusText();
        updateUndoButton();

        initSearchView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    protected void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopLocationClient();
        if (mSuggestionSearch != null) {
            mSuggestionSearch.destroy();
        }
        mMapView.onDestroy();
        if (mRouteDb != null) {
            mRouteDb.close();
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

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
            // 丢弃过期响应：输入框已清空、或关键字已被改掉时，
            // 这个响应不该再影响界面——否则列表会在用户已经清空或跳走之后凭空弹出。
            // 注意这段必须在清空 suggestions/adapter 之前 return，否则列表会先闪一下变空。
            CharSequence query = mSearchView.getQuery();
            String current = query == null ? "" : query.toString().trim();
            if (current.isEmpty() || !current.equals(mPendingKeyword)) {
                return;
            }

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
                mPendingKeyword = newText.trim();
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

    /** 锁定时关掉地图自身的手势，触摸全归绘制层；解锁后相反。 */
    private void applyMapGestures(boolean locked) {
        if (mBaiduMap != null) {
            mBaiduMap.getUiSettings().setAllGesturesEnabled(!locked);
        }
    }

    /**
     * 折叠/展开工具条。箭头片常驻，其余片随之一并隐藏。
     *
     * <p>箭头方向即「点了会往哪边动」：展开时指左（收起），收起时指右（展开）。
     *
     * <p>折叠状态从模式片的可见性反推：工具条根布局就是那片 LinearLayout 本身，
     * 不再为它单独留一个字段（多一份状态就多一处可能不同步的地方）。
     */
    private void toggleToolsPanel() {
        boolean collapsed = findViewById(R.id.route_draw_mode_group).getVisibility() != View.VISIBLE;
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

    private void onRouteChanged() {
        pushUndoSnapshot();
        updateStatusText();
        updateUndoButton();
    }

    /** 压入当前状态。注意：调用点是在状态<b>已经改变之后</b>，所以栈里存的是新状态，
     *  撤销时恢复到「上一个」快照需要先弹出栈顶。见 {@link #undo()}。 */
    private void pushUndoSnapshot() {
        mUndoStack.add(new Snapshot(mOverlay.getPoints(), mOverlay.isClosed()));
    }

    private void updateUndoButton() {
        if (mUndoButton != null) {
            mUndoButton.setEnabled(!mUndoStack.isEmpty());
        }
    }

    /**
     * 百度定位的失败码远多于成功码（TypeNone、TypeCriteriaException、TypeNetWorkException、
     * TypeOffLineLocationFail、TypeServerError 等十余个），所以判定必须走**成功白名单**。
     *
     * <p>反过来的写法（白名单两三个失败码、其余当成功）会让绝大多数失败落到
     * {@code animateMapStatus} 上：失败的 BDLocation 经纬度常为 0，相机会飞到几内亚湾，
     * 而且不弹任何提示——用户只看到地图莫名跑到海上。
     */
    private static boolean isLocateSuccess(int locType) {
        return locType == BDLocation.TypeGpsLocation
                || locType == BDLocation.TypeGnssLocation
                || locType == BDLocation.TypeNetWorkLocation
                || locType == BDLocation.TypeCoarseLocation
                || locType == BDLocation.TypeOffLineLocation
                || locType == BDLocation.TypeCacheLocation;
    }

    /**
     * 取一次当前位置把地图居中，省得每次进来都要手动平移。
     *
     * <p>这里刻意用 {@code setScanSpan(0)}（只定位一次），与 MainActivity 的
     * 1000ms 持续定位是两种不同配置——不要因为「看着像」就把 MainActivity 的
     * getLocationClientOption() 抄过来，那既多余又会被审查判为复制逻辑块。
     *
     * <p>定位失败不拦路：停在默认中心，提示用户手动平移。
     */
    private void centerOnCurrentLocation() {
        try {
            mLocClient = new LocationClient(getApplicationContext());
            mLocClient.registerLocationListener(new BDAbstractLocationListener() {
                @Override
                public void onReceiveLocation(BDLocation bdLocation) {
                    if (bdLocation == null || mBaiduMap == null) {
                        return;
                    }
                    if (!isLocateSuccess(bdLocation.getLocType())) {
                        GoUtils.DisplayToast(RouteDrawActivity.this,
                                getResources().getString(R.string.route_draw_locate_failed));
                        stopLocationClient();
                        return;
                    }

                    mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newMapStatus(
                            new MapStatus.Builder()
                                    .target(new LatLng(bdLocation.getLatitude(),
                                            bdLocation.getLongitude()))
                                    .zoom(18.0f)
                                    .build()));
                    stopLocationClient();
                }
            });

            LocationClientOption option = new LocationClientOption();
            // 必须与地图一致：本项目地图用的是 BD09LL（见 CLAUDE.md 的坐标系一节）
            option.setCoorType("bd09ll");
            // 0 = 只定位一次。绘制界面只要一个初始中心，不需要持续定位
            option.setScanSpan(0);
            option.setOpenGnss(true);
            option.setIsNeedAddress(false);
            option.setIsNeedLocationDescribe(false);
            option.setIsNeedLocationPoiList(false);

            mLocClient.setLocOption(option);
            mLocClient.start();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - centerOnCurrentLocation");
        }
    }

    private void stopLocationClient() {
        if (mLocClient != null) {
            mLocClient.stop();
            mLocClient = null;
        }
    }

    /** 撤销一步：丢弃当前状态，恢复到上一个快照。 */
    private void undo() {
        if (mUndoStack.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_undo_empty));
            return;
        }
        mUndoStack.remove(mUndoStack.size() - 1);   // 丢掉「当前」

        if (mUndoStack.isEmpty()) {
            mOverlay.setPoints(new ArrayList<LatLng>());
            mOverlay.setClosed(false);
        } else {
            Snapshot previous = mUndoStack.get(mUndoStack.size() - 1);
            mOverlay.setPoints(previous.points);
            mOverlay.setClosed(previous.closed);
        }
        updateStatusText();
        updateUndoButton();
    }

    /** 密化：弹框输入加点数 N，按总长等距插入 N 个新点。 */
    private void showDensifyDialog() {
        final List<LatLng> current = mOverlay.getPoints();
        if (current.size() < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_densify_no_route));
            return;
        }

        final EditText input = new EditText(this);
        input.setHint(R.string.route_draw_densify_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        input.setPadding(padding, padding / 2, padding, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_densify_title)
                .setMessage(R.string.route_draw_densify_note)
                .setView(input)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) -> {
                    int addCount;
                    try {
                        addCount = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_invalid));
                        return;
                    }
                    if (addCount <= 0) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_invalid));
                        return;
                    }
                    if (addCount > MAX_DENSIFY_ADD_COUNT) {
                        GoUtils.DisplayToast(this, getResources().getString(
                                R.string.route_draw_densify_too_many, MAX_DENSIFY_ADD_COUNT));
                        return;
                    }

                    double before = RouteGeometry.totalLengthMeters(current, mOverlay.isClosed());
                    List<LatLng> densified = RouteGeometry.densify(
                            current, mOverlay.isClosed(), addCount);
                    if (before <= 0d || densified.size() == current.size()) {
                        GoUtils.DisplayToast(this,
                                getResources().getString(R.string.route_draw_densify_no_route));
                        return;
                    }

                    mOverlay.setPoints(densified);
                    onRouteChanged();
                    GoUtils.DisplayToast(this, getResources().getString(
                            R.string.route_draw_densify_done, densified.size() - current.size()));
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    /** 清空不可撤销，二次确认。 */
    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_clear_title)
                .setMessage(R.string.route_draw_clear_message)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) -> {
                    mOverlay.clearRoute();
                    mUndoStack.clear();          // 清空后没有可回到的状态
                    updateStatusText();
                    updateUndoButton();
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    /** 保存：校验收名称与重名，再写库。 */
    private void showSaveDialog() {
        final List<LatLng> points = mOverlay.getPoints();
        if (points.size() < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_save_too_few_points));
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(padding, padding / 2, padding, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.route_draw_save_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(nameInput);

        // 列出已有名称：既让用户知道哪些名字被占用（重名会被拒），也验证保存确实生效
        TextView existing = new TextView(this);
        List<String> names = mRouteDb == null
                ? new ArrayList<String>()
                : DataBaseRoute.queryAllNames(mRouteDb);
        existing.setTextSize(12);
        if (names.isEmpty()) {
            existing.setText(R.string.route_draw_save_none);
        } else {
            StringBuilder builder = new StringBuilder(
                    getResources().getString(R.string.route_draw_save_existing));
            for (String name : names) {
                builder.append("\n· ").append(name);
            }
            existing.setText(builder.toString());
        }
        container.addView(existing);

        new AlertDialog.Builder(this)
                .setTitle(R.string.route_draw_save_title)
                .setView(container)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) ->
                        saveRoute(nameInput.getText().toString()))
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    private void saveRoute(String rawName) {
        RouteNameValidator.Problem problem = RouteNameValidator.findProblem(rawName);
        if (problem != null) {
            GoUtils.DisplayToast(this, describeNameProblem(problem));
            return;
        }

        String name = RouteNameValidator.normalize(rawName);

        if (mRouteDb == null) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_save_failed));
            return;
        }
        if (DataBaseRoute.nameExists(mRouteDb, name)) {
            GoUtils.DisplayToast(this,
                    getResources().getString(R.string.route_draw_save_duplicate));
            return;
        }

        long rowId = DataBaseRoute.insertRoute(
                mRouteDb, name, mOverlay.isClosed(), mOverlay.getPoints());
        if (rowId < 0) {
            // 撞上 UNIQUE 约束（并发兜底）也走重名提示
            GoUtils.DisplayToast(this, DataBaseRoute.nameExists(mRouteDb, name)
                    ? getResources().getString(R.string.route_draw_save_duplicate)
                    : getResources().getString(R.string.route_draw_save_failed));
            return;
        }

        GoUtils.DisplayToast(this, getResources().getString(R.string.route_draw_save_ok));
    }

    private String describeNameProblem(RouteNameValidator.Problem problem) {
        switch (problem) {
            case TOO_LONG:
                return getResources().getString(R.string.route_draw_name_too_long);
            case CONTROL_CHAR:
                return getResources().getString(R.string.route_draw_name_control_char);
            case FORBIDDEN_CHAR:
                return getResources().getString(R.string.route_draw_name_forbidden_char);
            case EMPTY:
            default:
                return getResources().getString(R.string.route_draw_name_empty);
        }
    }

    private void updateStatusText() {
        if (mStatusText == null) {
            return;
        }
        String hint = mOverlay.getDrawMode() == RouteDrawOverlayView.MODE_LINE
                ? getResources().getString(R.string.route_draw_status_line)
                : getResources().getString(R.string.route_draw_status_idle);
        String count = getResources().getString(
                R.string.route_draw_point_count, mOverlay.getPoints().size());
        mStatusText.setText(hint + "\n" + count);
    }
}

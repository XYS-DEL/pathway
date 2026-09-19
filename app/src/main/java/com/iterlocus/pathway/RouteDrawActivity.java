package com.iterlocus.pathway;

import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.model.LatLng;
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

    private MapView mMapView;
    private BaiduMap mBaiduMap;
    private RouteDrawOverlayView mOverlay;

    private ScrollView mToolsPanel;
    private TextView mStatusText;
    private Button mUndoButton;

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

        mToolsPanel = findViewById(R.id.route_draw_tools);
        mStatusText = findViewById(R.id.route_draw_status);
        mUndoButton = findViewById(R.id.route_draw_btn_undo);

        findViewById(R.id.route_draw_tools_toggle).setOnClickListener(v -> toggleToolsPanel());

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

    /** 锁定时关掉地图自身的手势，触摸全归绘制层；解锁后相反。 */
    private void applyMapGestures(boolean locked) {
        if (mBaiduMap != null) {
            mBaiduMap.getUiSettings().setAllGesturesEnabled(!locked);
        }
    }

    private void toggleToolsPanel() {
        boolean visible = mToolsPanel.getVisibility() == View.VISIBLE;
        mToolsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
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

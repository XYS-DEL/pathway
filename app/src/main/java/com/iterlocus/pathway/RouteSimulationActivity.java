package com.iterlocus.pathway;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.BaseAdapter;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.acooldog.nfc.NfcConfigStore;
import com.acooldog.nfc.NfcPayload;
import com.acooldog.nfc.NfcPayloadDispatchResult;
import com.acooldog.nfc.NfcSender;
import com.acooldog.nfc.SavedNfcConfig;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.database.DataBaseRoute;
import com.iterlocus.pathway.service.ServiceGo;
import com.iterlocus.pathway.utils.GoUtils;
import com.iterlocus.pathway.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟路线。
 *
 * <p>上下分栏：上面是地图（画出选中那条路线、标出当前位置），下面是一块可折叠的控制面板
 * （选路线、选速度、开始/结束）。真正的移动在 {@link ServiceGo} 里跑——用户按下开始之后
 * 一定会切走到目标 App，任何建立在 Activity 上的定时器都会在后台被冻结。
 *
 * <p>进度靠轮询 {@link ServiceGo.ServiceGoBinder#getRouteProgress()}，不注册回调：
 * 轮询不会在 Activity 销毁时泄漏监听器，系统重建后也自然接上，服务始终是唯一事实源。
 * 重建后靠快照里的路线名把选中行、档位、按钮文案全部对回去。
 *
 * <p><b>两份点集，各用各的</b>：{@link RouteConfig#getPoints()} 是 BD09，只用来在地图上
 * 画线与框相机（百度地图原生就是 BD09）；{@link RouteRow#wgsPoints} 是喂给
 * {@link ServiceGo.ServiceGoBinder#startRoute} 的 WGS84。两边都不再做任何换算。
 *
 * <p>本界面<b>不解析 NFC 卡片</b>：URL 与包名在模拟开起来之后用于发送伪 NDEF；载荷既可由
 * 「读取NFC」页面传入，也可从本地保存的 NFC 配置中导入。从 URL 里取坐标仍是后续工作。
 */
public class RouteSimulationActivity extends BaseActivity {

    /** NFC 卡片上的原始 URL，未做任何加工。 */
    public static final String EXTRA_CARD_URL = "EXTRA_CARD_URL";
    /** 卡片携带的包名，可能为空。 */
    public static final String EXTRA_CARD_PACKAGE = "EXTRA_CARD_PACKAGE";
    /** 来源标记，由 NFC 模块填入，是自由字符串，不要按穷举写判断。 */
    public static final String EXTRA_SOURCE = "EXTRA_SOURCE";

    private static final long PROGRESS_POLL_MS = 500L;

    /** 系统重建时存/取选中行。行序取自 DataBaseRoute.queryAll（按创建时间倒序），是稳定的。 */
    private static final String STATE_SELECTED_INDEX = "STATE_SELECTED_INDEX";
    private static final String STATE_CARD_URL = "STATE_CARD_URL";
    private static final String STATE_CARD_PACKAGE = "STATE_CARD_PACKAGE";
    private static final String STATE_CARD_SOURCE = "STATE_CARD_SOURCE";
    private static final String STATE_CARD_CONFIG_NAME = "STATE_CARD_CONFIG_NAME";
    private static final String PREF_LAST_ROUTE_NAME = "route_sim_last_route_name";
    /** 折叠态也跨重建保持，否则每次旋转面板都自己弹回来。 */
    private static final String STATE_PANEL_COLLAPSED = "STATE_PANEL_COLLAPSED";

    /** 路线折线的宽度，dp。PolylineOptions.width() 要的是像素，用前乘 density。 */
    private static final int ROUTE_LINE_WIDTH_DP = 6;
    /** 没有选中路线时框相机的兜底缩放级别。 */
    private static final float SINGLE_POINT_ZOOM = 18.0f;

    /** 列表的一行：原始配置 + 换算后的 WGS84 点集 + WGS84 总长。 */
    private static final class RouteRow {
        final RouteConfig config;
        final double[][] wgsPoints;
        final double totalMeters;

        RouteRow(RouteConfig config, double[][] wgsPoints, double totalMeters) {
            this.config = config;
            this.wgsPoints = wgsPoints;
            this.totalMeters = totalMeters;
        }
    }

    private SharedPreferences mPreferences;
    private SQLiteDatabase mRouteDb;

    private MapView mMapView;
    private BaiduMap mBaiduMap;
    /** 选中路线的折线覆盖物；没有选中时为 null。 */
    private Polyline mRouteLine;
    /** 当前位置标记；第一次真的有路线在跑时才创建，之后复用。 */
    private Marker mPositionMarker;
    /** 正开着的选路线弹窗。存下来是为了在 onDestroy 里收掉——否则弹窗开着旋转会 WindowLeaked，
     *  而且弹窗的 ListView 持有 {@link RouteListAdapter}，等于间接持有这个 Activity。 */
    private AlertDialog mRoutePickerDialog;
    private AlertDialog mNfcPickerDialog;

    private TextView mStatusText;
    private TextView mPrimaryButton;
    private TextView mPauseButton;
    private TextView mPickButton;
    private TextView mImportNfcButton;
    private TextView mToggleButton;
    private View mPanelBody;
    private RadioGroup mSpeedGroup;

    private final List<RouteRow> mRows = new ArrayList<>();
    private RouteListAdapter mAdapter;
    private int mSelectedIndex = -1;
    private double mSelectedSpeed = 1.2d;
    /** 面板是否已收起。收起后只剩把手那一行，状态文本仍可见。 */
    private boolean mPanelCollapsed;
    /**
     * 这次 {@code onResume} 之前是不是刚被重建过（旋转）。
     *
     * <p>只有 {@link #onRestoreInstanceState} 会置它——那个回调**只在重建时**跑，
     * 所以它正好等价于「这次是旋转，不是从后台回来」。用来决定 {@code onResume} 里
     * 要不要重新框相机：重建后必须框（地图回默认位置了），从后台回来绝不能框
     * （会把用户自己拖过的地图拽回去）。
     */
    private boolean mNeedsReframe;
    /** applySpeedSelection() 里的 check() 会回调监听器，用它挡住自触发。 */
    private boolean mApplyingSpeed;

    private NfcConfigStore mNfcConfigStore;
    private String mCardUrl = "";
    private String mCardPackageName = "";
    private String mCardSource = "";
    private String mCardConfigName = "";

    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean mBound;
    /** 已发起 startRoute 的意图但绑定还没完成，onServiceConnected 里补发。 */
    private boolean mPendingStart;

    private final Handler mPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPollTask = new Runnable() {
        @Override
        public void run() {
            refreshProgress();
            mPollHandler.postDelayed(this, PROGRESS_POLL_MS);
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            mBound = true;
            if (mPendingStart) {
                mPendingStart = false;
                if (mSelectedIndex >= 0 && mSelectedIndex < mRows.size()) {
                    beginRoute(mRows.get(mSelectedIndex));
                }
            }
            refreshProgress();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBinder = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));

        setContentView(R.layout.activity_route_simulation);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mRouteDb = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        mNfcConfigStore = new NfcConfigStore(this);

        mMapView = findViewById(R.id.route_sim_map);
        mBaiduMap = mMapView.getMap();

        mStatusText = findViewById(R.id.route_sim_status);
        mPrimaryButton = findViewById(R.id.route_sim_primary);
        mPauseButton = findViewById(R.id.route_sim_pause);
        mPickButton = findViewById(R.id.route_sim_pick);
        mImportNfcButton = findViewById(R.id.route_sim_import_nfc);
        mToggleButton = findViewById(R.id.route_sim_toggle);
        mPanelBody = findViewById(R.id.route_sim_body);
        mSpeedGroup = findViewById(R.id.route_sim_speed_group);

        initializeCardPayload(savedInstanceState);
        renderCardFields();

        mAdapter = new RouteListAdapter();

        mPickButton.setOnClickListener(v -> showRoutePicker());
        mImportNfcButton.setOnClickListener(v -> showNfcConfigPicker());
        findViewById(R.id.route_sim_handle).setOnClickListener(v -> togglePanel());
        applyPanelCollapsed(false);

        mSelectedSpeed = speedWalk();
        mSpeedGroup.setOnCheckedChangeListener((group, checkedId) -> onSpeedChipChanged(checkedId));

        mPrimaryButton.setOnClickListener(v -> onPrimaryClicked());
        mPauseButton.setOnClickListener(v -> onPauseClicked());

        loadRoutes();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // 只存选中行与折叠态。档位由框架自己恢复 RadioGroup，进度由轮询重新拉，都不必存。
        outState.putInt(STATE_SELECTED_INDEX, mSelectedIndex);
        outState.putBoolean(STATE_PANEL_COLLAPSED, mPanelCollapsed);
        outState.putString(STATE_CARD_URL, mCardUrl);
        outState.putString(STATE_CARD_PACKAGE, mCardPackageName);
        outState.putString(STATE_CARD_SOURCE, mCardSource);
        outState.putString(STATE_CARD_CONFIG_NAME, mCardConfigName);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // 恢复选中行不能只靠 refreshProgress 里的 selectRowByName：那条路要
        // progress.getRouteName()，**没有路线在跑时压根没有快照**，于是选中行会回到 -1
        // （用户点「开始模拟」得到「请先选一条路线」），而 RadioGroup 的档位却被框架恢复了——
        // 这种不对称会被读成 bug。
        // 此时 loadRoutes() 已在 onCreate 里跑完，mRows 与 mAdapter 都已就绪。
        //
        // 存索引之所以安全，是因为 queryAll 的排序是确定的（orderBy = CREATED_AT DESC）。
        // 若日后改了那个排序，这里必须改成按名字恢复——否则旋转后会静默选中另一条路线。
        int index = savedInstanceState.getInt(STATE_SELECTED_INDEX, -1);
        if (index >= 0 && index < mRows.size()) {
            mSelectedIndex = index;
            onSelectionChanged();
            // 地图部分刻意留到 onResume 再补一次：重建时 MapView 刚建出来，此刻画上去的
            // 覆盖物会被地图自己的初始化冲掉，相机也要等地图就绪才框得住。
            // 这个回调只在重建时跑，所以它置的标记正好等价于「这次是旋转」。
            mNeedsReframe = true;
        }

        applyPanelCollapsed(savedInstanceState.getBoolean(STATE_PANEL_COLLAPSED, false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();

        // 旋转重建后折线随旧 MapView 一起没了、相机也回了默认位置，这里补齐；
        // 从后台回来则是幂等的空操作，且绝不碰相机。见 syncMapOnResume() 的注释。
        syncMapOnResume();

        // 只在服务已经活着时才绑定：bindService 配 BIND_AUTO_CREATE 会**创建**服务，
        // 而 ServiceGo.onCreate 会装 test provider 并起前台通知——光打开这个界面就把
        // 模拟位置服务启动了，显然不是用户要的。
        // 服务没活着就不可能有路线在跑，直接按「未开始」显示即可。
        if (!mBound && ServiceGo.isAlive()) {
            // 绑定失败时 onServiceConnected 永远不会来，界面会一直按「未开始」显示，
            // 而服务可能正在跑一条路线。提示一次，别让它无声地骗人。
            if (!bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE)) {
                XLog.e("ROUTE_SIM: ERROR - onResume 绑定服务失败");
                GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_bind_failed));
            }
        }
        mPollHandler.post(mPollTask);
    }

    @Override
    protected void onPause() {
        mPollHandler.removeCallbacks(mPollTask);
        mMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mPollHandler.removeCallbacks(mPollTask);

        // 弹窗开着时被销毁（旋转 / 返回）必须收掉：不收会 WindowLeaked，
        // 而且弹窗的 ListView 持有 mAdapter，等于把这个 Activity 一起钉住。
        if (mRoutePickerDialog != null) {
            mRoutePickerDialog.dismiss();
            mRoutePickerDialog = null;
        }
        if (mNfcPickerDialog != null) {
            mNfcPickerDialog.dismiss();
            mNfcPickerDialog = null;
        }

        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        if (mRouteDb != null) {
            mRouteDb.close();
        }

        mMapView.onDestroy();

        // 刻意不停服务：用户切走之后位置要继续移动，那正是本功能的全部意义
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

    /*===== 折叠面板 =====*/

    private void togglePanel() {
        applyPanelCollapsed(!mPanelCollapsed);
    }

    /**
     * 收起/展开面板。
     *
     * <p>收起后必须留一个可点的把手（就是 {@code route_sim_handle} 那一行），否则展不回来；
     * 状态文本钉在把手上，所以播放中收起也还看得见进度。箭头与无障碍描述跟着翻转，
     * 与绘制界面工具条那个箭头片同一套路子。
     */
    private void applyPanelCollapsed(boolean collapsed) {
        mPanelCollapsed = collapsed;
        mPanelBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        mToggleButton.setText(collapsed
                ? R.string.route_sim_arrow_expand
                : R.string.route_sim_arrow_collapse);
        mToggleButton.setContentDescription(getResources().getString(collapsed
                ? R.string.route_sim_panel_expand
                : R.string.route_sim_panel_collapse));
    }

    /*===== NFC 交接字段 =====*/

    private void initializeCardPayload(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mCardUrl = valueOrEmpty(savedInstanceState.getString(STATE_CARD_URL));
            mCardPackageName = valueOrEmpty(savedInstanceState.getString(STATE_CARD_PACKAGE));
            mCardSource = valueOrEmpty(savedInstanceState.getString(STATE_CARD_SOURCE));
            mCardConfigName = valueOrEmpty(savedInstanceState.getString(STATE_CARD_CONFIG_NAME));
            return;
        }
        Intent intent = getIntent();
        mCardUrl = valueOrEmpty(intent.getStringExtra(EXTRA_CARD_URL));
        mCardPackageName = valueOrEmpty(intent.getStringExtra(EXTRA_CARD_PACKAGE));
        mCardSource = valueOrEmpty(intent.getStringExtra(EXTRA_SOURCE));
    }

    private void renderCardFields() {
        TextView content = findViewById(R.id.route_sim_content);
        if (isEmpty(mCardUrl) && isEmpty(mCardPackageName) && isEmpty(mCardSource)) {
            content.setVisibility(View.GONE);
            return;
        }

        content.setVisibility(View.VISIBLE);
        StringBuilder builder = new StringBuilder();
        builder.append(getResources().getString(R.string.route_sim_received)).append("\n\n");
        if (!isEmpty(mCardConfigName)) {
            builder.append(getString(R.string.route_sim_nfc_config_name, mCardConfigName))
                    .append("\n\n");
        }
        builder.append(EXTRA_CARD_URL).append(":\n").append(orDash(mCardUrl)).append("\n\n");
        builder.append(EXTRA_CARD_PACKAGE).append(":\n").append(orDash(mCardPackageName))
                .append("\n\n");
        builder.append(EXTRA_SOURCE).append(":\n").append(orDash(mCardSource));

        content.setText(builder.toString());
    }

    private void showNfcConfigPicker() {
        List<SavedNfcConfig> configs = mNfcConfigStore.getSavedConfigs();
        if (configs.isEmpty()) {
            GoUtils.DisplayToast(this, getString(R.string.route_sim_nfc_empty));
            return;
        }

        String[] names = new String[configs.size()];
        for (int index = 0; index < configs.size(); index++) {
            names[index] = configs.get(index).getName();
        }
        mNfcPickerDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.route_sim_nfc_pick_title)
                .setItems(names, (dialog, which) -> {
                    SavedNfcConfig config = configs.get(which);
                    mCardConfigName = config.getName();
                    mCardUrl = config.getUrl();
                    mCardPackageName = config.getPackageName();
                    mCardSource = config.getSource();
                    mNfcConfigStore.savePayload(mCardUrl, mCardPackageName, mCardSource);
                    renderCardFields();
                    GoUtils.DisplayToast(this, getString(
                            R.string.route_sim_nfc_imported, config.getName()));
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .create();
        mNfcPickerDialog.setOnDismissListener(dialog -> mNfcPickerDialog = null);
        mNfcPickerDialog.show();
    }

    private boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private String orDash(String value) {
        return isEmpty(value) ? "—" : value;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /*===== 路线列表与地图 =====*/

    private void loadRoutes() {
        mRows.clear();

        if (mRouteDb != null) {
            List<RouteConfig> configs = DataBaseRoute.queryAll(mRouteDb);
            for (RouteConfig config : configs) {
                mRows.add(buildRow(config));
            }
        } else {
            XLog.e("ROUTE_SIM: ERROR - 路线库打不开");
        }

        if (mSelectedIndex >= mRows.size()) {
            mSelectedIndex = -1;
        }

        if (mSelectedIndex < 0) {
            selectRowByName(mPreferences.getString(PREF_LAST_ROUTE_NAME, ""));
        }

        mPrimaryButton.setEnabled(!mRows.isEmpty());
        updatePickButtonText();
        updateMapForSelection();
        refreshProgress();
    }

    /**
     * 建一行：把 BD09 点集一次性换成 WGS84，并用引擎自己算出总长。
     *
     * <p>总长交给 {@link RoutePlayer} 而不是另写一份求和，是为了让列表显示的数字
     * 与真正要走的那条线严格一致——两份算法迟早会漂。
     */
    private RouteRow buildRow(RouteConfig config) {
        List<LatLng> points = config.getPoints();
        double[][] wgs = new double[points.size()][];
        for (int index = 0; index < points.size(); index++) {
            LatLng point = points.get(index);
            // MapUtils 入参是 (经度, 纬度)，返回 {经度, 纬度}；LatLng 构造是 (纬度, 经度)
            wgs[index] = MapUtils.bd2wgs(point.longitude, point.latitude);
        }
        double total = new RoutePlayer(wgs, config.isClosed(), 0d).getTotalDistance();
        return new RouteRow(config, wgs, total);
    }

    /**
     * 选中一条路线：弹出列表对话框。
     *
     * <p>直接把 {@link RouteListAdapter} 交给对话框，弹窗里的行与原来那个列表**是同一份
     * 渲染**（两行式：名称 / 点数·闭合·总长），没有第二套。
     *
     * <p>库里没有路线时没有东西可选，改用一句话说明去哪儿画。
     */
    private void showRoutePicker() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.route_sim_list_title);

        if (mRows.isEmpty()) {
            builder.setMessage(R.string.route_sim_empty);
        } else {
            builder.setAdapter(mAdapter, (dialog, which) -> {
                selectRow(which);
                dialog.dismiss();
            });
        }

        mRoutePickerDialog = builder.show();
    }

    private void selectRow(int index) {
        if (index == mSelectedIndex) {
            return;
        }
        mSelectedIndex = index;
        mPreferences.edit().putString(
                PREF_LAST_ROUTE_NAME, mRows.get(index).config.getName()).apply();
        onSelectionChanged();
    }

    /** 选中行变过之后要一起刷的三样：对话框里的高亮、按钮文案、地图上的线与相机。 */
    private void onSelectionChanged() {
        mAdapter.notifyDataSetChanged();
        updatePickButtonText();
        updateMapForSelection();
    }

    /** 按钮上写出当前选中的是哪条——列表挪进对话框之后，这是唯一的文字线索。 */
    private void updatePickButtonText() {
        if (mSelectedIndex >= 0 && mSelectedIndex < mRows.size()) {
            mPickButton.setText(getResources().getString(R.string.route_sim_pick_route_named,
                    mRows.get(mSelectedIndex).config.getName()));
        } else {
            mPickButton.setText(R.string.route_sim_list_title);
        }
    }

    /**
     * 当前选中那条路线用于地图显示的点集，{@code null} 表示没有可画的。
     *
     * <p>用的是 {@link RouteConfig#getPoints()} 那份 <b>BD09</b> 点——百度地图原生坐标系，
     * 不做任何换算（喂给服务的 WGS84 那一份在 {@link RouteRow#wgsPoints} 里，别混）。
     *
     * <p>闭合路线要把首点再补到末尾，才画得出那段回程。列表里显示的总长是含回程的
     * （{@code RoutePlayer} 对闭合路线把首尾当成一段），两者必须一致。
     */
    private List<LatLng> selectedDisplayPoints() {
        if (mSelectedIndex < 0 || mSelectedIndex >= mRows.size()) {
            return null;
        }
        RouteConfig config = mRows.get(mSelectedIndex).config;
        List<LatLng> points = config.getPoints();
        if (points.size() < 2) {
            // 一个点连不成线
            return null;
        }
        if (!config.isClosed()) {
            return points;
        }
        List<LatLng> loop = new ArrayList<>(points);
        loop.add(points.get(0));
        return loop;
    }

    /** 摘掉折线覆盖物（如果有），并把本地引用一并清掉。 */
    private void clearRouteLine() {
        if (mRouteLine == null) {
            return;
        }
        try {
            mRouteLine.remove();
        } catch (Exception e) {
            // 与 renderSelectedRoute / fitCameraTo 同一套：SDK 一旦抛就是主线程崩溃，
            // 而这条路径可达于轮询任务（refreshProgress → selectRowByName → selectRow →
            // onSelectionChanged → updateMapForSelection）。另两处都包了，这里也包上。
            XLog.e("ROUTE_SIM: ERROR - clearRouteLine");
        }
        // 抛了也要清引用，否则幂等判断会一直以为「已经画着」
        mRouteLine = null;
    }

    /**
     * 把当前选中的路线画到地图上。<b>幂等</b>：已经画着同一条就原样留着，不重复
     * {@code addOverlay}——重复堆叠会让地图上的覆盖物越攒越多。没有选中、或点不足 2 个，
     * 就什么都不做（也不动相机）。
     *
     * <p>单独抽出来是为了能在 {@code onResume} 里安全地重放一次：旋转会重建 Activity 与
     * MapView，折线随旧 MapView 一起没了，需要在新的地图上重画。
     */
    private void renderSelectedRoute() {
        if (mBaiduMap == null) {
            return;
        }
        // isRemoved() 是给「地图内部已经把覆盖物丢掉了、而本地引用还在」留的一手：
        // 只判非 null 会把这种情况误判成「已经画好了」，那条线就再也不会回来。
        if (mRouteLine != null && !mRouteLine.isRemoved()) {
            return;
        }
        mRouteLine = null;

        List<LatLng> points = selectedDisplayPoints();
        if (points == null) {
            return;
        }
        try {
            mRouteLine = (Polyline) mBaiduMap.addOverlay(new PolylineOptions()
                    .points(points)
                    .color(getResources().getColor(R.color.colorPrimary, getTheme()))
                    .width((int) (ROUTE_LINE_WIDTH_DP * getResources().getDisplayMetrics().density)));
        } catch (Exception e) {
            XLog.e("ROUTE_SIM: ERROR - renderSelectedRoute");
        }
    }

    /** 把相机框到当前选中的路线。没有可画的就什么都不做。 */
    private void frameSelectedRoute() {
        List<LatLng> points = selectedDisplayPoints();
        if (points != null) {
            fitCameraTo(points);
        }
    }

    /**
     * 选中行变了：摘掉旧线、重画新的，并把相机框到新的那条。
     *
     * <p>与 {@link #renderSelectedRoute()} 分工不同——那个是幂等的「保证画着」，
     * 这个是「换了一条，先清后画」。
     */
    private void updateMapForSelection() {
        clearRouteLine();
        renderSelectedRoute();
        frameSelectedRoute();
    }

    /**
     * 回到前台时把地图与选中态对齐。
     *
     * <p>必须分两种情况，差别只在**相机**：
     * <ul>
     *   <li><b>旋转</b>会重建 Activity 与 MapView——折线随旧 MapView 一起没了、相机也回到
     *       默认位置，所以要重画并重新框一次；
     *   <li><b>从后台回来</b>时 MapView 没有重建，重画是幂等的空操作，而相机<b>绝不能</b>
     *       重新框——那会把用户自己拖动 / 缩放过地图硬拽回去。
     * </ul>
     *
     * <p>区分依据是 {@link #onRestoreInstanceState} 只在重建时跑，它置的
     * {@link #mNeedsReframe}。没有这个标记就没法区分：{@code onResume} 两种情况下都会跑。
     */
    private void syncMapOnResume() {
        if (mNeedsReframe) {
            mNeedsReframe = false;
            // 先丢掉本地引用再画。重建后这里其实是新实例（引用必为 null），
            // 但万一地图内部丢过覆盖物而引用还在，这一步能让上面的幂等判断不至于误判。
            clearRouteLine();
            renderSelectedRoute();
            frameSelectedRoute();
            return;
        }
        renderSelectedRoute();
    }

    /**
     * 把相机框到这条路线，留一点边距。
     *
     * <p><b>只在选中路线时动相机</b>——轮询里绝不动，否则用户没法自己拖地图。
     * 尺寸要等布局完成才有值，所以投到 {@link MapView} 的消息队列里跑；
     * {@code post} 在 View 还没 attach 时会排队，attach 后执行，两边都安全。
     */
    private void fitCameraTo(List<LatLng> points) {
        mMapView.post(() -> fitCameraNow(points, true));
    }

    /**
     * 真正落相机的那一步。
     *
     * @param mayRetry 尺寸还是 0 时是否允许再等一次布局重投——只重投一次，不做成循环
     */
    private void fitCameraNow(List<LatLng> points, boolean mayRetry) {
        try {
            if (mBaiduMap == null) {
                return;
            }
            if (mMapView.getWidth() == 0 || mMapView.getHeight() == 0) {
                // 视图尚未 attach / 尚未测绘。post 的队列通常会排在首次测绘之后，但**不保证**；
                // 而这里一旦就这么放弃，失败是完全不可见的：没日志、没重试，用户看到的就是
                // 默认相机——正是「旋转后不重新框住路线」那个 bug 本身。
                // 所以记一条日志，并挂一次性布局回调重投一次。
                XLog.e(mayRetry
                        ? "ROUTE_SIM: ERROR - fitCameraTo: 地图尺寸为 0，等首次布局后重投一次"
                        : "ROUTE_SIM: ERROR - fitCameraTo: 地图尺寸仍为 0，放弃");
                if (!mayRetry) {
                    return;
                }
                mMapView.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                ViewTreeObserver observer = mMapView.getViewTreeObserver();
                                if (observer.isAlive()) {
                                    observer.removeOnGlobalLayoutListener(this);
                                }
                                // 重投时**重新按当前选中取点**，不用捕获下来的那一份：
                                // 从投递失败到这次布局之间用户可能已经改了选中项，
                                // 拿旧路线的包围盒去框、画出来的却是新折线。
                                List<LatLng> current = selectedDisplayPoints();
                                if (current != null) {
                                    fitCameraNow(current, false);
                                }
                            }
                        });
                return;
            }

            LatLngBounds.Builder builder = new LatLngBounds.Builder();
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLng = Double.MAX_VALUE;
            double maxLng = -Double.MAX_VALUE;
            for (LatLng point : points) {
                builder.include(point);
                minLat = Math.min(minLat, point.latitude);
                maxLat = Math.max(maxLat, point.latitude);
                minLng = Math.min(minLng, point.longitude);
                maxLng = Math.max(maxLng, point.longitude);
            }

            if (maxLat <= minLat && maxLng <= minLng) {
                // 所有点重合：LatLngBounds 退化成一个点，交给 newLatLngBounds 求缩放会退化
                // （跨度为 0 时算出的级别没有意义）。改用一个固定的近景级别。
                mBaiduMap.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(
                        points.get(0), SINGLE_POINT_ZOOM));
                return;
            }

            mBaiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLngBounds(
                    builder.build(), mMapView.getWidth(), mMapView.getHeight()));
        } catch (Exception e) {
            XLog.e("ROUTE_SIM: ERROR - fitCameraTo");
        }
    }

    /**
     * 按名字把选中行对回去。系统重建后界面没有任何「我刚跑了哪条」的记忆，
     * 只有轮询回来的快照，这是唯一的恢复途径。
     */
    private void selectRowByName(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (int index = 0; index < mRows.size(); index++) {
            if (name.equals(mRows.get(index).config.getName())) {
                selectRow(index);
                return;
            }
        }
    }

    /** 行的选中态靠 setActivated，由 bg_route_row / route_row_text 的 state_activated 键接住。 */
    private final class RouteListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(RouteSimulationActivity.this)
                        .inflate(R.layout.route_sim_route_item, parent, false);
            }

            RouteRow item = mRows.get(position);
            TextView name = row.findViewById(R.id.route_sim_item_name);
            TextView meta = row.findViewById(R.id.route_sim_item_meta);

            name.setText(item.config.getName());
            meta.setText(getResources().getString(R.string.route_sim_item_meta,
                    item.config.getPointCount(),
                    getResources().getString(item.config.isClosed()
                            ? R.string.route_sim_closed
                            : R.string.route_sim_open),
                    formatDistance(item.totalMeters)));

            // 回收复用，所以两个方向都要显式设
            row.setActivated(position == mSelectedIndex);
            return row;
        }
    }

    /*===== 档位 =====*/

    private void onSpeedChipChanged(int checkedId) {
        if (mApplyingSpeed) {
            return;
        }
        mSelectedSpeed = speedForChip(checkedId);
        // 播放中改档位：改的是同一个字段，引擎下一帧生效
        if (mServiceBinder != null) {
            mServiceBinder.setRouteSpeed(mSelectedSpeed);
        }
    }

    private void applySpeedSelection() {
        int checkedId = R.id.route_sim_speed_walk;
        if (Math.abs(mSelectedSpeed - speedRun()) < 0.01d) {
            checkedId = R.id.route_sim_speed_run;
        } else if (Math.abs(mSelectedSpeed - speedBike()) < 0.01d) {
            checkedId = R.id.route_sim_speed_bike;
        }
        mApplyingSpeed = true;
        mSpeedGroup.check(checkedId);
        mApplyingSpeed = false;
    }

    private double speedForChip(int checkedId) {
        if (checkedId == R.id.route_sim_speed_run) {
            return speedRun();
        }
        if (checkedId == R.id.route_sim_speed_bike) {
            return speedBike();
        }
        return speedWalk();
    }

    private double speedWalk() {
        return readSpeedPreference("setting_walk", R.string.setting_walk_default, 1.2d);
    }

    private double speedRun() {
        return readSpeedPreference("setting_run", R.string.setting_run_default, 3.6d);
    }

    private double speedBike() {
        return readSpeedPreference("setting_bike", R.string.setting_bike_default, 10.0d);
    }

    /** 读档位速度：与摇杆同一套偏好键与缺省值，读不到就用兜底值。 */
    private double readSpeedPreference(String key, int defaultResId, double fallback) {
        try {
            return Double.parseDouble(
                    mPreferences.getString(key, getResources().getString(defaultResId)));
        } catch (NumberFormatException e) {
            XLog.e("ROUTE_SIM: ERROR - readSpeedPreference");
            return fallback;
        }
    }

    /*===== 开始 / 结束 =====*/

    private void onPrimaryClicked() {
        if (currentProgress() != null) {
            stopSimulation();
        } else {
            startSimulation();
        }
    }

    private void onPauseClicked() {
        RouteProgress progress = currentProgress();
        if (progress == null || mServiceBinder == null || progress.isFinished()) {
            return;
        }
        if (progress.isPaused()) {
            mServiceBinder.resumeRoute();
        } else {
            mServiceBinder.pauseRoute();
        }
        refreshProgress();
    }

    private RouteProgress currentProgress() {
        return mServiceBinder == null ? null : mServiceBinder.getRouteProgress();
    }

    private void startSimulation() {
        if (mSelectedIndex < 0 || mSelectedIndex >= mRows.size()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_need_selection));
            return;
        }

        // 与 MainActivity.doGoLocation() 同一套预检，顺序也一致
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }
        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }
        if (!Settings.canDrawOverlays(getApplicationContext())) {
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("ROUTE_SIM: ERROR - 无悬浮窗权限");
            return;
        }
        if (!GoUtils.isAllowMockLocation(this)) {
            GoUtils.showEnableMockLocationDialog(this);
            XLog.e("ROUTE_SIM: ERROR - 无模拟位置权限");
            return;
        }

        RouteRow row = mRows.get(mSelectedIndex);

        // 服务可能没在跑，自己起。把路线首点作为 extras 传进去——ServiceGo.onStartCommand
        // 会用它们初始化位置单元格，不传就落到 DEFAULT_LAT/DEFAULT_LNG（36.66, 117.03），
        // 界面会先闪一下那个坐标才被路线第一帧覆盖。
        Intent intent = new Intent(this, ServiceGo.class);
        intent.putExtra(ServiceGo.EXTRA_SUPPRESS_JOYSTICK, true);
        if (row.wgsPoints.length > 0) {
            intent.putExtra(MainActivity.LNG_MSG_ID, row.wgsPoints[0][0]);
            intent.putExtra(MainActivity.LAT_MSG_ID, row.wgsPoints[0][1]);
        }
        intent.putExtra(MainActivity.ALT_MSG_ID, currentAltitude());
        startForegroundService(intent);

        if (mServiceBinder != null) {
            beginRoute(row);
        } else {
            // 绑定是异步的，真正的 startRoute 在 onServiceConnected 里补发
            mPendingStart = true;
            if (!bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE)) {
                // 绑定失败 → onServiceConnected 不会来 → mPendingStart 会永远挂着，
                // 「开始模拟」变成一个按了没反应的按钮。撤销意图并如实提示。
                mPendingStart = false;
                XLog.e("ROUTE_SIM: ERROR - startSimulation 绑定服务失败");
                GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_bind_failed));
            }
        }
    }

    private void beginRoute(RouteRow row) {
        if (mServiceBinder == null) {
            return;
        }
        boolean started = mServiceBinder.startRoute(
                row.config.getName(), row.wgsPoints, row.config.isClosed(), mSelectedSpeed);
        if (!started) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_sim_start_failed));
            return;
        }

        // WiFi 开着会让被 mock 的位置弹回真实位置，是本项目的已知限制。
        // 每次瞬移后都会警告，开始模拟同理。
        if (GoUtils.isWifiEnabled(this)) {
            GoUtils.showDisableWifiDialog(this);
        }

        sendCardPayload();

        refreshProgress();
    }

    private void sendCardPayload() {
        if (isEmpty(mCardUrl)) {
            return;
        }
        if (isEmpty(mCardPackageName)) {
            openCardUrl(mCardUrl);
            return;
        }
        NfcPayloadDispatchResult result = NfcSender.send(
                this, new NfcPayload(mCardUrl, mCardPackageName, mCardSource));
        if (!result.isSuccessful()) {
            XLog.e("ROUTE_SIM: ERROR - sendCardPayload: " + result.getDetail());
            GoUtils.DisplayToast(this, getString(R.string.route_sim_open_url_failed));
        }
    }

    private void openCardUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            XLog.e("ROUTE_SIM: ERROR - openCardUrl");
            GoUtils.DisplayToast(this, getString(R.string.route_sim_open_url_failed));
        }
    }

    private void stopSimulation() {
        if (mServiceBinder != null) {
            mServiceBinder.stopRoute();
        }
        refreshProgress();
    }

    private double currentAltitude() {
        try {
            return Double.parseDouble(mPreferences.getString("setting_altitude", "55.0"));
        } catch (NumberFormatException e) {
            XLog.e("ROUTE_SIM: ERROR - currentAltitude");
            return 55.0d;
        }
    }

    /*===== 进度与当前位置 =====*/

    private void refreshProgress() {
        RouteProgress progress = currentProgress();

        updatePositionMarker(progress);

        if (progress == null) {
            mStatusText.setText(R.string.route_sim_status_idle);
            mPrimaryButton.setText(R.string.route_sim_start);
            mPauseButton.setVisibility(View.GONE);
            // 没有路线在跑，可以随便换；库是空的就置灰
            mPickButton.setEnabled(!mRows.isEmpty());
            mImportNfcButton.setEnabled(true);
            return;
        }

        mPrimaryButton.setText(R.string.route_sim_stop);
        if (progress.isFinished()) {
            mPauseButton.setVisibility(View.GONE);
        } else {
            mPauseButton.setVisibility(View.VISIBLE);
            mPauseButton.setText(progress.isPaused()
                    ? R.string.route_sim_resume
                    : R.string.route_sim_pause);
        }

        // 播放中（含已到达、等用户结束）不许换路线。错误处理表里那条规则就是
        // 「不能直接切，先结束再开始」；不置灰的话，弹窗会让人选，而 500ms 后的
        // selectRowByName 又把选中翻回去——一次注定失败的操作，还附带折线与相机的抖动。
        // 置灰用的是现成的禁用片样式（bg_tool_chip / chip_text 都带禁用态），没有新资源。
        mPickButton.setEnabled(false);
        mImportNfcButton.setEnabled(false);

        // 系统重建后靠这里把选中行与档位对回去
        selectRowByName(progress.getRouteName());
        if (!progress.isPaused() && Math.abs(mSelectedSpeed - progress.getSpeed()) > 0.01d) {
            mSelectedSpeed = progress.getSpeed();
            applySpeedSelection();
        }

        if (progress.isFinished()) {
            mStatusText.setText(R.string.route_sim_status_arrived);
            return;
        }

        if (progress.isPaused()) {
            mStatusText.setText(R.string.route_sim_status_paused);
            return;
        }

        String covered = formatDistance(progress.getDistanceCovered());
        String total = formatDistance(progress.getTotalDistance());
        if (progress.getLapCount() > 0) {
            // 闭合路线每绕完一圈「已走」会归零，圈数把那件事解释清楚；
            // 不写出来用户会当成进度跳回 0 的 bug
            mStatusText.setText(getResources().getString(
                    R.string.route_sim_status_playing_lap,
                    progress.getLapCount() + 1, covered, total));
        } else {
            mStatusText.setText(getResources().getString(
                    R.string.route_sim_status_playing, covered, total));
        }
    }

    /**
     * 刷新地图上的当前位置标记。
     *
     * <p>挂在既有的 500ms 轮询里，<b>不新增定时器</b>。没有路线在跑（快照为 null）时
     * 标记不显示；到达终点后快照仍在（服务刻意不自动结束），标记停在终点，这是对的。
     *
     * <p>位置由 {@link ServiceGo.ServiceGoBinder#getCurrentPosition()} 给的是 <b>WGS84</b>，
     * 画到百度地图上要 BD09：{@code MapUtils.wgs2bd09} 入参 (经度, 纬度)、返回 {经度, 纬度}，
     * 而 {@link LatLng} 构造是 (纬度, 经度)。这是本界面唯一一处坐标换算，别在别处再加。
     */
    private void updatePositionMarker(RouteProgress progress) {
        if (mBaiduMap == null) {
            return;
        }
        try {
            if (progress == null || mServiceBinder == null) {
                if (mPositionMarker != null) {
                    mPositionMarker.setVisible(false);
                }
                return;
            }

            double[] wgs = mServiceBinder.getCurrentPosition();
            if (wgs == null || wgs.length < 2
                    || !Double.isFinite(wgs[0]) || !Double.isFinite(wgs[1])) {
                XLog.e("ROUTE_SIM: ERROR - updatePositionMarker: 非法坐标");
                return;
            }

            double[] bd09 = MapUtils.wgs2bd09(wgs[0], wgs[1]);
            LatLng position = new LatLng(bd09[1], bd09[0]);

            if (mPositionMarker == null) {
                // 图标必须用**位图**资源：BitmapDescriptorFactory.fromResource 底下是
                // BitmapFactory，它不认识 VectorDrawable（全仓唯一的先例 icon_gcoding
                // 也是 webp）。喂它一个 <vector> 只会得到一张 null 位图——标记静默不显示，
                // 编译和 lint 都不报。ic_home_position / ic_position 都是 vector，别用。
                mPositionMarker = (Marker) mBaiduMap.addOverlay(new MarkerOptions()
                        .position(position)
                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding)));
            } else {
                mPositionMarker.setPosition(position);
                mPositionMarker.setVisible(true);
            }
        } catch (Exception e) {
            XLog.e("ROUTE_SIM: ERROR - updatePositionMarker");
        }
    }

    /** 米或公里。1 公里以下用米，避免「0.1 公里」这种读不出量级的显示。 */
    private String formatDistance(double meters) {
        if (meters < 1000d) {
            return getResources().getString(R.string.route_sim_meters,
                    (int) Math.round(meters));
        }
        return getResources().getString(R.string.route_sim_kilometers, meters / 1000d);
    }
}

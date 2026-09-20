package com.iterlocus.pathway;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.preference.PreferenceManager;

import com.baidu.mapapi.model.LatLng;
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
 * <p>选一条已保存的路线、选速度档位、开始模拟。真正的移动在 {@link ServiceGo} 里跑——
 * 用户按下开始之后一定会切走到目标 App，任何建立在 Activity 上的定时器都会在后台被冻结。
 *
 * <p>进度靠轮询 {@link ServiceGo.ServiceGoBinder#getRouteProgress()}，不注册回调：
 * 轮询不会在 Activity 销毁时泄漏监听器，系统重建后也自然接上，服务始终是唯一事实源。
 * 重建后靠快照里的路线名把选中行、档位、按钮文案全部对回去。
 *
 * <p>本界面<b>不解析 NFC 卡片</b>，只把收到的东西原样列出来供核对。
 * 那三个字段的解析是后续版本的事。
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

    private TextView mStatusText;
    private TextView mPrimaryButton;
    private TextView mEmptyText;
    private ListView mRouteList;
    private RadioGroup mSpeedGroup;

    private final List<RouteRow> mRows = new ArrayList<>();
    private RouteListAdapter mAdapter;
    private int mSelectedIndex = -1;
    private double mSelectedSpeed = 1.2d;
    /** applySpeedSelection() 里的 check() 会回调监听器，用它挡住自触发。 */
    private boolean mApplyingSpeed;

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

        mStatusText = findViewById(R.id.route_sim_status);
        mPrimaryButton = findViewById(R.id.route_sim_primary);
        mEmptyText = findViewById(R.id.route_sim_empty);
        mRouteList = findViewById(R.id.route_sim_list);
        mSpeedGroup = findViewById(R.id.route_sim_speed_group);

        showReceivedCardFields();

        mAdapter = new RouteListAdapter();
        mRouteList.setAdapter(mAdapter);
        mRouteList.setOnItemClickListener((parent, view, position, id) -> selectRow(position));

        mSelectedSpeed = speedWalk();
        mSpeedGroup.setOnCheckedChangeListener((group, checkedId) -> onSpeedChipChanged(checkedId));

        mPrimaryButton.setOnClickListener(v -> onPrimaryClicked());

        loadRoutes();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // 只存选中行。档位由框架自己恢复 RadioGroup，进度由轮询重新拉，都不必存。
        outState.putInt(STATE_SELECTED_INDEX, mSelectedIndex);
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
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 只在服务已经活着时才绑定：bindService 配 BIND_AUTO_CREATE 会**创建**服务，
        // 而 ServiceGo.onCreate 会装 test provider 并起前台通知——光打开这个界面就把
        // 模拟位置服务启动了，显然不是用户要的。
        // 服务没活着就不可能有路线在跑，直接按「未开始」显示即可。
        if (!mBound && ServiceGo.isAlive()) {
            bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE);
        }
        mPollHandler.post(mPollTask);
    }

    @Override
    protected void onPause() {
        mPollHandler.removeCallbacks(mPollTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mPollHandler.removeCallbacks(mPollTask);

        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
        if (mRouteDb != null) {
            mRouteDb.close();
        }

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

    /*===== NFC 交接字段 =====*/

    private void showReceivedCardFields() {
        Intent intent = getIntent();
        String url = intent.getStringExtra(EXTRA_CARD_URL);
        String packageName = intent.getStringExtra(EXTRA_CARD_PACKAGE);
        String source = intent.getStringExtra(EXTRA_SOURCE);

        TextView content = findViewById(R.id.route_sim_content);

        // 从侧滑菜单进来时三个字段全空，这块是给开发者看的调试卷，
        // 常驻在用户可见界面上只会是三行「—」的噪音（大字号下它也是被挤出屏幕的那一块）。
        // 从 NFC 卡片进来时照旧显示，核对交接的用途不变。
        if (isEmpty(url) && isEmpty(packageName) && isEmpty(source)) {
            content.setVisibility(View.GONE);
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(getResources().getString(R.string.route_sim_received)).append("\n\n");
        builder.append(EXTRA_CARD_URL).append(":\n").append(orDash(url)).append("\n\n");
        builder.append(EXTRA_CARD_PACKAGE).append(":\n").append(orDash(packageName)).append("\n\n");
        builder.append(EXTRA_SOURCE).append(":\n").append(orDash(source));

        content.setText(builder.toString());
    }

    private boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private String orDash(String value) {
        return isEmpty(value) ? "—" : value;
    }

    /*===== 路线列表 =====*/

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

        mEmptyText.setVisibility(mRows.isEmpty() ? View.VISIBLE : View.GONE);
        mPrimaryButton.setEnabled(!mRows.isEmpty());
        mAdapter.notifyDataSetChanged();
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

    private void selectRow(int index) {
        if (index == mSelectedIndex) {
            return;
        }
        mSelectedIndex = index;
        mAdapter.notifyDataSetChanged();
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
            bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE);
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
        refreshProgress();
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

    /*===== 进度 =====*/

    private void refreshProgress() {
        RouteProgress progress = currentProgress();

        if (progress == null) {
            mStatusText.setText(R.string.route_sim_status_idle);
            mPrimaryButton.setText(R.string.route_sim_start);
            return;
        }

        mPrimaryButton.setText(R.string.route_sim_stop);

        // 系统重建后靠这里把选中行与档位对回去
        selectRowByName(progress.getRouteName());
        if (Math.abs(mSelectedSpeed - progress.getSpeed()) > 0.01d) {
            mSelectedSpeed = progress.getSpeed();
            applySpeedSelection();
        }

        if (progress.isFinished()) {
            mStatusText.setText(R.string.route_sim_status_arrived);
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

    /** 米或公里。1 公里以下用米，避免「0.1 公里」这种读不出量级的显示。 */
    private String formatDistance(double meters) {
        if (meters < 1000d) {
            return getResources().getString(R.string.route_sim_meters,
                    (int) Math.round(meters));
        }
        return getResources().getString(R.string.route_sim_kilometers, meters / 1000d);
    }
}

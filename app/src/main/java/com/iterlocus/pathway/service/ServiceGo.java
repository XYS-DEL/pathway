package com.iterlocus.pathway.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.MainActivity;
import com.iterlocus.pathway.R;
import com.iterlocus.pathway.RouteGeometry;
import com.iterlocus.pathway.RoutePlayer;
import com.iterlocus.pathway.RouteProgress;
import com.iterlocus.pathway.joystick.JoyStick;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    // 位置单元格：定位线程每 100ms 读它推给 mock provider，主线程（摇杆 listener、
    // setPosition、onStartCommand）与定位线程（advanceRoute）都会写。
    // 路线模拟让定位线程成为第一个非主线程写者，故 volatile——与 mLastTickMs 同理。
    private volatile double mCurLat = DEFAULT_LAT;
    private volatile double mCurLng = DEFAULT_LNG;
    private volatile double mCurAlt = DEFAULT_ALT;
    private volatile float mCurBea = DEFAULT_BEA;
    private volatile double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";

    /** 单次推进的时间上限，秒。doze / GC 长暂停之后不夹住会一次跳出几百米。 */
    private static final double MAX_TICK_SECONDS = 1.0;

    /** 服务存活标志，供 MainActivity 对账 isMockServStart。 */
    private static volatile boolean sAlive = false;

    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_ROUTE_STOP = "StopRoute";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    /** 当前路线。null 表示没有路线在跑。定位线程读、UI 线程写，故 volatile。 */
    private volatile RoutePlayer mRoutePlayer;
    private volatile String mRouteName;
    /** 上一 tick 的时刻，用于算真实的 dt。主线程写（onCreate / startRoute）、定位线程读写，故 volatile。 */
    private volatile long mLastTickMs;
    /** 主线程 Handler：摇杆是 View，只能在主线程碰。 */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        sAlive = true;
        // 必须在 initGoLocation() 之前：循环的第一条消息马上就会读它
        mLastTickMs = SystemClock.elapsedRealtime();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        sAlive = false;

        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        filter.addAction(SERVICE_GO_NOTE_ACTION_ROUTE_STOP);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        startForeground(SERVICE_GO_NOTE_ID, buildNotification());
    }

    /**
     * 构造前台通知。
     *
     * <p>播放路线时正文换成模拟状态，并多一个「结束路线」动作——用户整场模拟都在别的
     * App 里，没有这个动作就只能切回行屿才能停。
     *
     * <p>措辞刻意不叫「停止模拟」：那会被读成停掉整个 mock 服务。结束路线之后服务继续
     * 在终点维持位置模拟，这是对的——用户此刻正「站」在终点。
     */
    private Notification buildNotification() {
        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        // mRoutePlayer 是 volatile，读一次进局部：正文文案与动作列表取自同一个快照，
        // 否则「正在模拟路线」配上一个没有停止动作的通知（或反过来）都可能出现
        RoutePlayer player = mRoutePlayer;
        String text;
        if (player == null) {
            text = getResources().getString(R.string.app_service_tips);
        } else {
            text = getResources().getString(player.isFinished()
                    ? R.string.app_route_arrived
                    : R.string.app_route_playing);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher);

        if (player != null) {
            Intent stopIntent = new Intent(SERVICE_GO_NOTE_ACTION_ROUTE_STOP);
            // 请求码与上面两个错开；即便 action 已经不同，也别复用同一个码
            PendingIntent stopPendingPI = PendingIntent.getBroadcast(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new NotificationCompat.Action(null,
                    getResources().getString(R.string.note_route_stop), stopPendingPI));
        }

        return builder.build();
    }

    /**
     * 刷新前台通知。播放状态一变就调。{@code notify} 线程安全，定位线程可直接调。
     *
     * <p>整体护住：本方法的调用点里，`stopRoute()` 那一路在主线程上、由点击派发器
     * （`doGoLocation` → `setPosition`）可达，异常逃出去是未捕获崩溃。按项目约定
     * 记日志后降级为「通知没刷新」。构造本身在 {@code onCreate} 里没护（通知坏掉会
     * 在启动时当场暴露），所以这里吞掉不会掩盖通知格式本身的缺陷。
     */
    private void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(SERVICE_GO_NOTE_ID, buildNotification());
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - updateNotification");
        }
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                // 播放期间位置归路线，摇杆输入一律忽略。权威层：JoyStick 自己也拦，
                // 但那只是让「禁用」在观感上成立，入口分散，靠界面层拦不干净。
                if (isRoutePlaying()) {
                    return;
                }
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                if (isRoutePlaying()) {
                    return;
                }
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }
        });
        mJoyStick.show();
    }

    /**
     * 路线是否正在走。到达终点后为 false——那时位置不再被引擎推进，
     * 用户「站」在终点，摇杆要能继续用（设计文档：播放结束（用户结束 / 到达终点）
     * 时摇杆恢复可用）。所以判据是「在走」而不是「非 null」：仅凭
     * {@code mRoutePlayer != null} 会在「已到达、等待用户结束」这个合法状态下
     * 把摇杆变成一个看得见、拖不动的死控件。
     */
    private boolean isRoutePlaying() {
        RoutePlayer player = mRoutePlayer;
        return player != null && !player.isFinished();
    }

    /** 开关摇杆输入。{@code JoyStick} 是 View，只能在主线程碰。 */
    private void setJoyStickInputEnabled(boolean enabled) {
        final boolean value = enabled;
        postToMain(() -> {
            if (mJoyStick != null) {
                mJoyStick.setInputEnabled(value);
            }
        });
    }

    /**
     * 到达终点：位置停在末点，摇杆交还用户，通知改文案。
     *
     * <p>运行在定位线程上：摇杆那两下走 {@link #postToMain}。通知的
     * {@code NotificationManager.notify} 本身线程安全，直接调。
     */
    private void onRouteFinished() {
        syncJoyStickToCurrentPosition();
        setJoyStickInputEnabled(true);
        updateNotification();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        advanceRoute();
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    /**
     * 按真实经过的时间推进路线。
     *
     * <p>dt 必须用时钟差而不是写死的 0.1：{@code Thread.sleep(100)} 会漂，
     * 几公里的路线上累积误差肉眼可见。上限 {@link #MAX_TICK_SECONDS} 是防
     * doze / GC 长暂停之后一次跳出几百米——那是一条不真实的瞬移轨迹。
     *
     * <p>写回的是摇杆用的同一个「当前位置」单元格，所以 {@code setLocationGPS()} /
     * {@code setLocationNetwork()} 一行都不用改。
     *
     * <p>到达终点后本方法只是不再推进；交还摇杆、改通知文案属于表现层，在
     * onRouteFinished() 里做。
     */
    private void advanceRoute() {
        // 取时钟与推进 tick 基准留在 try 外：这两行是纯算术、不会抛，
        // 而 mLastTickMs 必须无条件每 tick 前移，否则一旦某 tick 抛异常，
        // 基准就会停在过去，之后每次 dt 都吃满 MAX_TICK_SECONDS。
        long now = SystemClock.elapsedRealtime();
        double dt = Math.min((now - mLastTickMs) / 1000.0, MAX_TICK_SECONDS);
        mLastTickMs = now;

        try {
            RoutePlayer player = mRoutePlayer;
            if (player == null || player.isFinished()) {
                return;
            }

            player.advance(dt);

            double[] position = player.getPosition();
            if (position == null) {
                return;
            }
            // 这期间 UI 线程可能已经 stopRoute() 并瞬移走（setPosition 会先停路线）。
            // 不复检的话，这次回写会盖掉瞬移目标，而彼时 mRoutePlayer 已为 null，
            // 后续 tick 不会再纠正——瞬移就静默丢了。
            if (mRoutePlayer != player) {
                return;
            }
            mCurLng = position[0];
            mCurLat = position[1];
            mCurBea = (float) player.getBearing();
            mSpeed = player.getSpeed();

            // 到达检测放在路由体末尾（回写之后）：方法开头的 isFinished() 早退保证
            // 已完成的路线不会再走到这里，所以本行恰好触发一次，不需要「是否已触发」标志。
            // 这里用的是 mRoutePlayer != player 复检之后的那个 player，语义自洽。
            if (player.isFinished()) {
                onRouteFinished();
            }
        } catch (Exception e) {
            // handleMessage 的 try 只接 InterruptedException：异常若逃出去会杀死
            // 定位线程的 Looper——isStop 仍是 false、前台通知还挂着、被 mock 的位置
            // 冻在最后一个值，且一行日志都没有。那是本 App 最坏的失效模式。
            XLog.e("SERVICEGO: ERROR - advanceRoute");
        }
    }

    /** 把摇杆内置地图同步到当前位置。只能在主线程碰这些 View。 */
    private void syncJoyStickToCurrentPosition() {
        final double lng = mCurLng;
        final double lat = mCurLat;
        final double alt = mCurAlt;
        postToMain(() -> {
            if (mJoyStick != null) {
                mJoyStick.setCurrentPosition(lng, lat, alt);
            }
        });
    }

    /** 已在主线程就直接跑，否则投递过去。服务里既有 UI 线程调用也有定位线程调用。 */
    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }

                // 走 binder 而不是直接改字段：stopRoute 里那一套（复检配合的置 null、
                // 摇杆交还、通知回收）必须整段跑，不能在这里抄一遍
                if (action.equals(SERVICE_GO_NOTE_ACTION_ROUTE_STOP)) {
                    mBinder.stopRoute();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            // 播放中瞬移是自相矛盾的状态：两个写入者抢同一个位置。
            // 先结束路线，并靠 advanceRoute 回写前的那次复检，把已经进入回写阶段的
            // 那一 tick 丢弃。这把「位置归谁」的窗口从一次 advance()+getPosition()
            // 缩到相邻几条指令，但**没有消除**：彻底的单写者需要把所有写入者都并到
            // 定位线程（摇杆那一路也要改），是另一个量级的改动，不在本次范围。
            stopRoute();

            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }

        /**
         * 开始沿路线模拟。
         *
         * @param wgsPoints 每个元素 {@code {经度, 纬度}}，<b>WGS84</b>（调用方负责从 BD09 转换）
         * @return 是否成功开始；点数不足 2 或总长为 0 时返回 false，不抛
         */
        public boolean startRoute(String routeName, double[][] wgsPoints,
                                  boolean closed, double speedMps) {
            if (wgsPoints == null || wgsPoints.length < RouteGeometry.MIN_POINTS_FOR_CLOSE) {
                return false;
            }
            // 逐行验形。RoutePlayer 的构造器直接下标取值，null 行或长度不足会抛
            // NPE/AIOOBE；非有限值则会让下面第二条拒绝失效（NaN <= 0d 为 false），
            // 于是被当成合法路线收下，最后表现为一个永远不结束、位置是 NaN 的模拟。
            // 这是 binder 上的公开入口、数据来自别的组件，按项目约定必须记日志后降级而不是抛。
            for (double[] point : wgsPoints) {
                if (point == null || point.length < 2
                        || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                    XLog.e("SERVICEGO: ERROR - startRoute: 非法坐标行");
                    return false;
                }
            }
            RoutePlayer player = new RoutePlayer(wgsPoints, closed, speedMps);
            if (player.getTotalDistance() <= 0d) {
                return false;
            }

            mRoutePlayer = player;
            mRouteName = routeName == null ? "" : routeName;
            // 归零基准时刻，否则第一帧会带上「上次 tick 到现在」的整段间隔
            mLastTickMs = SystemClock.elapsedRealtime();
            // 表现层：摇杆禁用并变灰、内置地图跳到路线起点、通知换成「正在模拟路线」
            setJoyStickInputEnabled(false);
            syncJoyStickToCurrentPosition();
            updateNotification();
            return true;
        }

        /** 结束路线模拟。没有路线在跑时什么也不做。 */
        public void stopRoute() {
            if (mRoutePlayer == null) {
                return;
            }
            mRoutePlayer = null;
            mRouteName = null;
            // 表现层：摇杆交还用户、通知退回「服务正在运行中」。
            // 放在置 null 之后——通知正文按 mRoutePlayer 是否为 null 决定
            setJoyStickInputEnabled(true);
            updateNotification();
            syncJoyStickToCurrentPosition();
        }

        /** 播放中改速度。没有路线在跑时什么也不做。 */
        public void setRouteSpeed(double speedMps) {
            RoutePlayer player = mRoutePlayer;
            if (player != null) {
                player.setSpeed(speedMps);
            }
        }

        /** 当前路线状态；没有路线在跑时返回 null。 */
        public RouteProgress getRouteProgress() {
            RoutePlayer player = mRoutePlayer;
            if (player == null) {
                return null;
            }
            return new RouteProgress(mRouteName, player.isFinished(), player.getSpeed(),
                    player.getDistanceCovered(), player.getTotalDistance(), player.getLapCount());
        }
    }

    /**
     * 服务是否存活。
     *
     * <p>供 {@code MainActivity} 对账 {@code isMockServStart}——模拟界面也能独立启动本服务，
     * 那个 Activity 私有字段会因此陈旧。顺带修掉「服务被系统杀死后字段仍是 true」的既有隐患。
     */
    public static boolean isAlive() {
        return sAlive;
    }
}



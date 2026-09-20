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
import android.content.SharedPreferences;
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
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.MainActivity;
import com.iterlocus.pathway.LocationOffset;
import com.iterlocus.pathway.R;
import com.iterlocus.pathway.RouteGeometry;
import com.iterlocus.pathway.RoutePlayer;
import com.iterlocus.pathway.RouteProgress;
import com.iterlocus.pathway.SmoothRouteOffset;
import com.iterlocus.pathway.SmoothValueOffset;
import com.iterlocus.pathway.joystick.JoyStick;

public class ServiceGo extends Service {
    public static final String EXTRA_SUPPRESS_JOYSTICK =
            "com.iterlocus.pathway.extra.SUPPRESS_JOYSTICK";
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

    /**
     * 服务存活标志。唯一的消费者是 {@code RouteSimulationActivity}：它据此决定要不要
     * {@code bindService}，免得 {@code BIND_AUTO_CREATE} 把一个已经死掉的服务凭空创建出来。
     *
     * <p>{@code MainActivity.isMockServStart} **没有**读它——那个字段的陈旧问题是已知缺陷，
     * 见 CLAUDE.md 的「已知缺陷」一节，不要在这里声称本标志解决了它。
     */
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
    private static final String SERVICE_GO_NOTE_ACTION_ROUTE_PAUSE = "PauseRoute";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

    /** 用户希望的摇杆可见性；路线开始和结束都会置 false，避免结束路线时自动弹出。 */
    private volatile boolean mJoyStickDesiredVisible = true;

    /** 当前路线。null 表示没有路线。定位线程读、UI 线程写，故 volatile。 */
    private volatile RoutePlayer mRoutePlayer;
    private volatile String mRouteName;
    private volatile boolean mRoutePaused;
    private volatile double mRouteSpeedBeforePause;
    private volatile double mRouteBaseSpeed;
    private double mRouteBaseAltitude;
    /** 与路线会话同寿命；只由定位线程推进，跨闭合路线圈次不重置。 */
    private SmoothRouteOffset mRouteOffset;
    private SmoothValueOffset mRouteSpeedOffset;
    private SmoothValueOffset mRouteAltitudeOffset;
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
        // 系统以 START_STICKY 重启本服务时会用 null Intent 调进来——这是文档化的正常路径
        // （super 的默认返回值就是 START_STICKY），不是异常输入，但直接解引用会在主线程
        // 抛 NPE 崩溃。按项目约定记日志后降级：三个值退回服务自己的缺省值。重启出来的是
        // 新实例，字段本来就在缺省值上，这里显式写一遍是为了让降级点看得见。
        if (intent == null) {
            XLog.e("SERVICEGO: ERROR - onStartCommand: intent 为 null");
            mCurLng = DEFAULT_LNG;
            mCurLat = DEFAULT_LAT;
            mCurAlt = DEFAULT_ALT;
        } else {
            mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
            if (intent.getBooleanExtra(EXTRA_SUPPRESS_JOYSTICK, false)) {
                mJoyStickDesiredVisible = false;
                mJoyStick.hide();
            }
        }

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
        filter.addAction(SERVICE_GO_NOTE_ACTION_ROUTE_PAUSE);
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
        // setPackage：把通知栏里这几个广播都收成「只发给本包」。不设的话它们是隐式广播，
        // 本 App 自己发出去的同名广播也会被别的 App 注册的 receiver 收走。
        // 注意这**只约束发送侧**——见下面 onReceive 处关于收信侧的说明。
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        showIntent.setPackage(getPackageName());
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        hideIntent.setPackage(getPackageName());
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        // mRoutePlayer 是 volatile，读一次进局部：正文文案与动作列表取自同一个快照，
        // 否则「正在模拟路线」配上一个没有停止动作的通知（或反过来）都可能出现
        RoutePlayer player = mRoutePlayer;
        String text;
        if (player == null) {
            text = getResources().getString(R.string.app_service_tips);
        } else if (player.isFinished()) {
            text = getResources().getString(R.string.app_route_arrived);
        } else if (mRoutePaused) {
            text = getResources().getString(R.string.app_route_paused);
        } else {
            text = getResources().getString(R.string.app_route_playing);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(clickPI)
                .setSmallIcon(R.mipmap.ic_launcher);

        if (player != null) {
            if (!player.isFinished()) {
                Intent pauseIntent = new Intent(SERVICE_GO_NOTE_ACTION_ROUTE_PAUSE);
                pauseIntent.setPackage(getPackageName());
                PendingIntent pausePendingPI = PendingIntent.getBroadcast(
                        this, 4, pauseIntent, PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new NotificationCompat.Action(
                        mRoutePaused ? R.drawable.ic_route_play : R.drawable.ic_route_pause,
                        getString(mRoutePaused ? R.string.note_route_resume : R.string.note_route_pause),
                        pausePendingPI));
            }
            Intent stopIntent = new Intent(SERVICE_GO_NOTE_ACTION_ROUTE_STOP);
            // 与上面两个同样收成只发给本包。这一个尤其不能漏：伪造的 StopRoute 能直接停掉
            // 正在跑的路线，比伪造摇杆可见性更糟——而它原先恰恰是唯一没设 setPackage 的。
            stopIntent.setPackage(getPackageName());
            // 请求码与上面两个错开；即便 action 已经不同，也别复用同一个码
            PendingIntent stopPendingPI = PendingIntent.getBroadcast(this, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new NotificationCompat.Action(null,
                    getResources().getString(R.string.note_route_stop), stopPendingPI));
        } else {
            builder.addAction(new NotificationCompat.Action(null,
                    getResources().getString(R.string.note_show), showPendingPI));
            builder.addAction(new NotificationCompat.Action(null,
                    getResources().getString(R.string.note_hide), hidePendingPI));
        }

        return builder.build();
    }

    /**
     * 刷新前台通知。
     *
     * <p>一律经主线程串行化，且文案在**执行时**才成型（{@code buildNotification()} 现场读
     * {@code mRoutePlayer}）——这样后到的那次自然算出正确文案，不会出现「已到达」的通知
     * 盖在已经结束的路线之上。
     *
     * <p>{@code isStop} 守卫：{@code onDestroy} 打断不了一个已经在 {@code advanceRoute}
     * 里的 tick，那个 tick 走到这里会把通知重新贴到 {@code stopForeground} 之后。
     *
     * <p>整体护住：本方法的调用点里，`stopRoute()` 那一路在主线程上、由点击派发器
     * （`doGoLocation` → `setPosition`）可达，异常逃出去是未捕获崩溃。按项目约定
     * 记日志后降级为「通知没刷新」。构造本身在 {@code onCreate} 里没护（{@code startForeground}
     * 那次不投递、同步执行，通知坏掉会在启动时当场暴露），所以这里吞掉不掩盖格式缺陷。
     */
    private void updateNotification() {
        postToMain(() -> {
            try {
                if (isStop) {
                    return;
                }
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(SERVICE_GO_NOTE_ID, buildNotification());
                }
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - updateNotification");
            }
        });
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

    /** 路线是否仍处于模拟会话。到达终点或暂停时路线仍存在，摇杆都必须保持禁用。 */
    private boolean isRoutePlaying() {
        return mRoutePlayer != null;
    }

    /**
     * 把摇杆的输入开关刷成当前该有的样子。{@code JoyStick} 是 View，只能在主线程碰。
     *
     * <p>刻意不接收一个算好的布尔值：调用点算出的值可能已经过期——定位线程算出
     * 「该恢复」的同时，主线程可能刚好开了一条新路线。让投递出去的任务自己重新求值，
     * 后到的那次自然算出正确结果，顺序就不再重要。
     */
    private void refreshJoyStickInputEnabled() {
        postToMain(() -> {
            if (mJoyStick != null) {
                mJoyStick.setInputEnabled(!isRoutePlaying());
            }
        });
    }

    /**
     * 按「是否在播放」与「用户希望的可见性」求值后落到 {@link JoyStick} 上。
     *
     * <p>播放期间<b>整个悬浮窗收起来</b>：用户抱怨它挡在目标 App 上面。只把 alpha 降到
     * 0.4 是不够的——那只是变淡，仍然占着屏幕。
     *
     * <p>「禁用」那一层（{@link #refreshJoyStickInputEnabled()} 与
     * {@code JoyStick.setInputEnabled}）仍保留，避免已进入处理流程的触摸事件越过隐藏边界；
     * 通知栏的「显示摇杆」在路线存在期间也会被拒绝。
     *
     * <p>与 {@link #refreshJoyStickInputEnabled()} 同一个模式：投递出去、由任务自己
     * 重新求值，所以即使投递被主线程的 {@code startRoute()} 插到中间，后到的那次
     * 也会算出对新路线正确的状态。
     *
     * <p>{@code isStop} 守卫：{@code onDestroy} 打断不了一个已经投递出去的任务，而那时
     * {@code mJoyStick.destroy()} 已经摘掉窗口、{@code mMapView} 也 {@code onDestroy} 了。
     * 少了这道守卫，这里会把窗口重新 {@code addView} 回来（一个杀不掉的悬浮窗），
     * 或者在 {@code WINDOW_TYPE_MAP} 模式下碰已经销毁的 MapView 而抛出。这个守卫在这里
     * 是**可靠**的：本任务跑在主线程上，而 {@code isStop} 正是主线程写的。
     * 整体也护住——{@code show()} 没有自己的 try/catch，异常逃出去是未捕获崩溃。
     *
     * <p>另外两个碰 {@code mJoyStick} 的投递不需要这道守卫：{@code setCurrentPosition}
     * 自带 try/catch（记日志后降级），{@code setInputEnabled} 只改 alpha。
     */
    private void refreshJoyStickVisibility() {
        postToMain(() -> {
            try {
                if (isStop || mJoyStick == null) {
                    return;
                }
                if (isRoutePlaying()) {
                    mJoyStick.hide();
                } else if (mJoyStickDesiredVisible) {
                    mJoyStick.show();
                } else {
                    mJoyStick.hide();
                }
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - refreshJoyStickVisibility");
            }
        });
    }

    /**
     * 到达终点：位置停在末点、速度与航向归零，摇杆继续禁用并隐藏，通知改文案。
     *
     * <p>运行在定位线程上，三件事全部走 {@link #postToMain}；而且它们都在**执行时**
     * 重读 {@code mRoutePlayer}，所以即使这三次投递被主线程上的一次
     * {@code startRoute()} 插到中间，后到的那次也会算出对新路线正确的状态。
     */
    private void onRouteFinished() {
        // 到达是**终止态**：路线不再推进，位置就静止了，
        // 而 mSpeed / mCurBea 会继续被 setLocationGPS/Network 上报——静止位置配着最后一段的
        // 10 m/s 与旧航向，正是目标 App 可以据以识别的破绽；且它比 stopRoute() 那条挂得更久
        // （要一直挂到用户手动结束）。
        mSpeed = 0d;
        mCurBea = DEFAULT_BEA;
        syncJoyStickToCurrentPosition();
        refreshJoyStickInputEnabled();
        refreshJoyStickVisibility();
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
     * <p>到达终点后本方法只是不再推进；保持摇杆禁用、改通知文案属于表现层，在
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

            if (!mRoutePaused) {
                if (mRouteSpeedOffset != null) {
                    mRouteSpeedOffset.advance(dt);
                }
                if (mRouteAltitudeOffset != null) {
                    mRouteAltitudeOffset.advance(dt);
                }
            }
            double effectiveSpeed = Math.max(0d, mRouteBaseSpeed
                    + (mRouteSpeedOffset == null ? 0d : mRouteSpeedOffset.getValue()));
            player.setSpeed(mRoutePaused ? 0d : effectiveSpeed);
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
            SmoothRouteOffset offset = mRouteOffset;
            if (offset != null) {
                if (!mRoutePaused) {
                    offset.advance(dt);
                }
                position = LocationOffset.applyMeters(position[0], position[1],
                        offset.getEastMeters(), offset.getNorthMeters());
            }
            mCurLng = position[0];
            mCurLat = position[1];
            mCurBea = (float) player.getBearing();
            mSpeed = player.getSpeed();
            mCurAlt = mRouteBaseAltitude
                    + (mRouteAltitudeOffset == null ? 0d : mRouteAltitudeOffset.getValue());

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
            // 收信侧**仍然是不设防的**，别被上面那句 setPackage 误导成已经安全了：
            // 本 receiver 是动态注册的，既没给 permission 也没带 RECEIVER_NOT_EXPORTED，
            // 所以任何 App 都能给本 App 发这三个 action——伪造的 StopRoute 能直接停掉正在跑的
            // 路线，伪造的 Show/HideJoyStick 会改写 mJoyStickDesiredVisible（用户的可见性意愿）。
            // 这是既有问题（本次只把它放大了）。
            //
            // 今天（compileSdk 32）**没有可用的公开 API 去核对发送方**。收口的办法有三条，
            // 前两条要抬 compileSdk，第三条不用：
            //   1) BroadcastReceiver.getSentFromUid() 比对 Process.myUid()——平台自己的
            //      api-versions.xml 标着 **since API 34**。注意它的 javadoc：receiver 拿不到发送方
            //      身份时返回 Process.INVALID_UID，那种情况怎么判要单独定（现在用不了，不用急）。
            //   2) Context.RECEIVER_NOT_EXPORTED（最干净，但 **API 33** 才有）。
            //   3) 给 registerReceiver 传接收方权限（**API 1 就有，这条不用抬 compileSdk**），
            //      但必须先实测确认 PendingIntent.getBroadcast 发出的广播带着本 App 的身份、
            //      过得了这道权限——否则是把一个低危漏洞换成一个静默失效的通知按钮，
            //      而这件事读文档定不了。
            // **不要用 BroadcastReceiver.getSendingUid()**：android-32 与 android-36 的 android.jar
            // 逐类搜过，它**根本不存在**；android-36.1 的 sources 连 @hide 成员都带，里面也没有它。
            // 网上有文章引它，引了编不过。
            //
            // 同类还有一处：MainActivity.mDownloadBdRcv（同样动态注册、无权限、无 export flag）。
            // 但它的 action 是平台的 android.intent.action.DOWNLOAD_COMPLETE，是否属于平台
            // protected broadcast（那样就只有系统能发）**我没能核实**，所以两者「形状同类」，
            // 「可利用性」这一处存疑。收干净时一起做。
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    // 路线存在期间完全禁用摇杆；结束路线后保持隐藏，避免结束按钮触发自动弹出。
                    if (mRoutePlayer != null) {
                        return;
                    }
                    mJoyStickDesiredVisible = true;
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStickDesiredVisible = false;
                    mJoyStick.hide();
                }

                // 走 binder 而不是直接改字段：stopRoute 里那一套（复检配合的置 null、
                // 摇杆保持隐藏、通知回收）必须整段跑，不能在这里抄一遍
                if (action.equals(SERVICE_GO_NOTE_ACTION_ROUTE_STOP)) {
                    mBinder.stopRoute();
                }
                if (action.equals(SERVICE_GO_NOTE_ACTION_ROUTE_PAUSE)) {
                    RoutePlayer player = mRoutePlayer;
                    if (player != null && !player.isFinished()) {
                        if (mRoutePaused) {
                            mBinder.resumeRoute();
                        } else {
                            mBinder.pauseRoute();
                        }
                    }
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
         * 当前被模拟的位置，{@code {经度, 纬度}}，<b>WGS84</b>。
         *
         * <p>模拟界面的地图要标出「现在人在哪」，而进度快照
         * （{@link RouteProgress}）里只有里程、没有位置，所以单开这一个只读口子。
         *
         * <p><b>这不是一次自洽的快照。</b> 三个字段虽然都是 volatile，但经纬度是两条语句分开
         * 写（{@code mCurLng = position[0]; mCurLat = position[1];}）、这里也是两次独立的
         * volatile 读，两次读之间可以跨过一次 tick，于是可能混到两个位置：经度取自这一帧、
         * 纬度取自下一帧。
         *
         * <p>误差上界就是「一个 tick 走的距离」——定位循环约 10Hz（{@code Thread.sleep(100)}），
         * 按三档速度（1.2 / 3.6 / 10.0 m/s）算约 0.12 / 0.36 / 1.0 米。步行那档不足 0.15 米，
         * 标在地图上肉眼看不出来，但这只是「量小」，不是「原子」。要真的原子，得把两个值并进
         * 一个不可变对象一起发布（或让读取方拿一次快照对象），本次没做。
         *
         * <p><b>那个界只对定位循环这个写者成立。</b> 同一对字段还有第二个写者
         * {@link ServiceGoBinder#setPosition(double, double, double)}（瞬移），它一次可以移动任意远，
         * 所以**跨过一次瞬移的读不受「一个 tick」约束**。这不是缺陷（瞬移本来就该跳很远），
         * 只是别把这个界当成对所有情况都成立。
         *
         * <p>不做任何换算——调用方要画到百度地图上，自己走 {@code MapUtils.wgs2bd09}。
         */
        public double[] getCurrentPosition() {
            return new double[]{mCurLng, mCurLat};
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
            // NPE/AIOOBE；非有限值也在这里挡掉，虽然下面那道总长判断现在同样拦得住它们
            // （见那里的注释），但让非法坐标根本不进引擎更清楚。
            // 这是 binder 上的公开入口、数据来自其他组件，按项目约定必须记日志后降级而不是抛。
            for (double[] point : wgsPoints) {
                if (point == null || point.length < 2
                        || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                    XLog.e("SERVICEGO: ERROR - startRoute: 非法坐标行");
                    return false;
                }
            }
            RoutePlayer player = new RoutePlayer(wgsPoints, closed, speedMps);
            // 写成「总长不大于 0」而不是「总长 <= 0」：两者对负值和 0 等价，但 NaN 只被前者拦下
            // （NaN <= 0d 为 false，!(NaN > 0d) 为 true）。NaN 总长是可达的——两个有限但极大的
            // 坐标（如 +1e308 与 -1e308）会让 Haversine 里的 lng2 - lng1 溢出成 ±Inf，
            // Math.sin(±Inf) 随即是 NaN。总长本身**不会**溢出成 Infinity：asin 把结果夹在 π/2
            // 内，haversine 对任何有限输入都被 πR 界住，所以唯一能漏进来的就是 NaN。
            // 漏进来会收下一条永远不结束、位置恒为 NaN 的路线。
            if (!(player.getTotalDistance() > 0d)) {
                return false;
            }

            // 顺序是 flag-last：先写名字再写 player。读取方以 player 为判据键（getRouteProgress
            // 先看 mRoutePlayer 再看 mRouteName），而 volatile 只对它**之前**的写给出 release
            // 语义——反过来写，读者可能拿到新 player 配旧名字或空名字。
            mRouteName = routeName == null ? "" : routeName;
            mRoutePaused = false;
            mRouteSpeedBeforePause = Math.max(0d, speedMps);
            mRouteBaseSpeed = Math.max(0d, speedMps);
            mRouteBaseAltitude = mCurAlt;
            createRouteOffsets();
            mJoyStickDesiredVisible = false;
            mRoutePlayer = player;
            // 归零基准时刻，否则第一帧会带上「上次 tick 到现在」的整段间隔
            mLastTickMs = SystemClock.elapsedRealtime();
            // 表现层：整个悬浮窗收起（用户抱怨它挡在目标 App 上）、摇杆禁用并变灰、
            // 内置地图跳到路线起点、通知换成「正在模拟路线」
            refreshJoyStickInputEnabled();
            refreshJoyStickVisibility();
            syncJoyStickToCurrentPosition();
            updateNotification();
            return true;
        }

        /** 结束路线模拟。没有路线在跑时什么也不做。 */
        public void stopRoute() {
            if (mRoutePlayer == null) {
                return;
            }
            // 与 startRoute 同序，也是 flag-last：先清名字再清 player。万一有人读到中间态，
            // 看到的是「有 player 却没有名字」，界面按名字恢复选中行会直接放弃
            // （selectRowByName 对空名字早退），而不是把旧名字配到另一条路线上。
            mRouteName = null;
            mRoutePlayer = null;
            mRoutePaused = false;
            mRouteSpeedBeforePause = 0d;
            mRouteBaseSpeed = 0d;
            mRouteOffset = null;
            mRouteSpeedOffset = null;
            mRouteAltitudeOffset = null;
            mJoyStickDesiredVisible = false;
            // 速度与航向随路线一起复位：路线结束了，位置就静止了，而这两个值会继续被
            // setLocationGPS/Network 上报。静止位置配 10 m/s 与最后一段的航向，正是目标 App
            // 可以据以识别的破绽。航向取 0（DEFAULT_BEA）：静止时它没有意义，真机静止历元里
            // 即便有值也是噪声，0 是最不容易被读成异常的那个取值。
            mSpeed = 0d;
            mCurBea = DEFAULT_BEA;
            // 表现层：摇杆保持隐藏、输入恢复待命，通知退回「服务正在运行中」。
            // 放在置 null 之后——两者都在执行时重读 mRoutePlayer。
            refreshJoyStickInputEnabled();
            refreshJoyStickVisibility();
            updateNotification();
            syncJoyStickToCurrentPosition();
        }

        /** 暂停路线，位置保持不变，摇杆仍然不可用。 */
        public void pauseRoute() {
            RoutePlayer player = mRoutePlayer;
            if (player == null || player.isFinished() || mRoutePaused) {
                return;
            }
            mRouteSpeedBeforePause = mRouteBaseSpeed;
            player.setSpeed(0d);
            mSpeed = 0d;
            mRoutePaused = true;
            updateNotification();
        }

        /** 继续路线，恢复暂停前的速度。 */
        public void resumeRoute() {
            RoutePlayer player = mRoutePlayer;
            if (player == null || player.isFinished() || !mRoutePaused) {
                return;
            }
            double speed = Math.max(0d, mRouteSpeedBeforePause
                    + (mRouteSpeedOffset == null ? 0d : mRouteSpeedOffset.getValue()));
            mRouteBaseSpeed = mRouteSpeedBeforePause;
            player.setSpeed(speed);
            mSpeed = speed;
            mRoutePaused = false;
            updateNotification();
        }

        /** 播放中改速度。没有路线在跑时什么也不做。 */
        public void setRouteSpeed(double speedMps) {
            RoutePlayer player = mRoutePlayer;
            if (player != null) {
                if (mRoutePaused) {
                    mRouteBaseSpeed = Math.max(0d, speedMps);
                    mRouteSpeedBeforePause = mRouteBaseSpeed;
                } else {
                    mRouteBaseSpeed = Math.max(0d, speedMps);
                    player.setSpeed(Math.max(0d, mRouteBaseSpeed
                            + (mRouteSpeedOffset == null ? 0d : mRouteSpeedOffset.getValue())));
                }
            }
        }

        /** 当前路线状态；没有路线在跑时返回 null。 */
        public RouteProgress getRouteProgress() {
            RoutePlayer player = mRoutePlayer;
            if (player == null) {
                return null;
            }
            return new RouteProgress(mRouteName, player.isFinished(), mRoutePaused, mRouteBaseSpeed,
                    player.getDistanceCovered(), player.getTotalDistance(), player.getLapCount());
        }
    }

    private void createRouteOffsets() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.getBoolean("setting_random_offset", false)) {
            mRouteOffset = null;
            mRouteSpeedOffset = null;
            mRouteAltitudeOffset = null;
            return;
        }
        String fallback = getString(R.string.setting_random_offset_default);
        double maxEast = readOffsetPreference(preferences, "setting_lon_max_offset", fallback);
        double maxNorth = readOffsetPreference(preferences, "setting_lat_max_offset", fallback);
        double maxSpeed = readOffsetPreference(preferences, "setting_speed_max_offset",
                getString(R.string.setting_speed_offset_default));
        double maxAltitude = readOffsetPreference(preferences, "setting_altitude_max_offset",
                getString(R.string.setting_altitude_offset_default));
        mRouteOffset = new SmoothRouteOffset(maxEast, maxNorth);
        mRouteSpeedOffset = new SmoothValueOffset(maxSpeed, 12d, 12d, 0.08d);
        mRouteAltitudeOffset = new SmoothValueOffset(maxAltitude, 20d, 20d, 0.25d);
    }

    private double readOffsetPreference(SharedPreferences preferences, String key, String fallback) {
        try {
            double value = Double.parseDouble(preferences.getString(key, fallback));
            return Double.isFinite(value) ? Math.abs(value) : Double.parseDouble(fallback);
        } catch (NumberFormatException e) {
            XLog.e("SERVICEGO: ERROR - readOffsetPreference");
            return Double.parseDouble(fallback);
        }
    }

    /**
     * 服务是否存活。目前唯一的调用者是 {@code RouteSimulationActivity}：服务没活着就不可能有路线
     * 在跑，也正因如此它只在存活时才绑定，免得 {@code BIND_AUTO_CREATE} 把服务创建出来。
     *
     * <p>它**不**给 {@code MainActivity.isMockServStart} 对账。那个字段同时守着两处
     * {@code unbindService}，而 {@code mServiceBinder} 只在 MainActivity 自己的连接里赋值，
     * 直接赋值会解绑一个未注册的连接并让 FAB 分支解引用空 binder——这是 CLAUDE.md「已知缺陷」
     * 一节记着的事。别在注释里给本标志安上它没有的消费者。
     */
    public static boolean isAlive() {
        return sAlive;
    }
}



package com.iterlocus.pathway;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.Projection;
import com.baidu.mapapi.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * 盖在百度地图上的绘制层。
 *
 * <p>不使用地图的 Polyline 覆盖物：折线本身它画得了，但顶点手柄、起点高亮、闭合指示、
 * 线绘制的橡皮筋仍要自定义层，两套混用更难维护。这里统一自己画，坐标全部经
 * {@link Projection} 换算，地图状态一变就重绘。
 *
 * <p>只在 {@link #setDrawEnabled(boolean)} 为 true 时消费触摸。地图手势由 Activity
 * 开关，两者互斥。
 */
public class RouteDrawOverlayView extends View {

    public static final int MODE_POINT = 0;
    public static final int MODE_LINE = 1;

    /** 闭合命中半径，dp。 */
    private static final float HIT_RADIUS_DP = 24f;
    private static final float VERTEX_RADIUS_DP = 5f;
    private static final float START_RADIUS_DP = 9f;
    private static final float LINE_WIDTH_DP = 4f;

    public interface OnRouteChangedListener {
        /** 点集或闭合状态变化。Activity 据此更新撤销栈与按钮状态。 */
        void onRouteChanged();
    }

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVertexPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStartPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<LatLng> mPoints = new ArrayList<>();
    /** 线绘制进行中的临时轨迹，抬指后并入 mPoints */
    private final List<LatLng> mStroke = new ArrayList<>();
    private final Point mScratch = new Point();

    private BaiduMap mBaiduMap;
    private boolean mClosed;
    private boolean mDrawEnabled;
    private int mMode = MODE_POINT;
    private boolean mTouching;
    private float mTouchX;
    private float mTouchY;

    private float mHitRadiusPx;
    private float mVertexRadiusPx;
    private float mStartRadiusPx;
    private float mLineWidthPx;

    private OnRouteChangedListener mListener;

    public RouteDrawOverlayView(Context context) {
        this(context, null);
    }

    public RouteDrawOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mHitRadiusPx = HIT_RADIUS_DP * density;
        mVertexRadiusPx = VERTEX_RADIUS_DP * density;
        mStartRadiusPx = START_RADIUS_DP * density;
        mLineWidthPx = LINE_WIDTH_DP * density;

        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(mLineWidthPx);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setColor(Color.parseColor("#FF3F51B5"));

        mVertexPaint.setStyle(Paint.Style.FILL);
        mVertexPaint.setColor(Color.WHITE);
        mVertexPaint.setShadowLayer(2f, 0f, 0f, Color.BLACK);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        mStartPaint.setStyle(Paint.Style.FILL);
        mStartPaint.setColor(Color.parseColor("#FFFF5722"));

        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(mLineWidthPx);
        mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        mStrokePaint.setColor(Color.parseColor("#99FF3F51B5"));
    }

    public void setOnRouteChangedListener(OnRouteChangedListener listener) {
        mListener = listener;
    }

    /** 绑定地图。绑定后自行注册状态监听，地图一动就重绘。 */
    public void setBaiduMap(BaiduMap baiduMap) {
        mBaiduMap = baiduMap;
        if (mBaiduMap != null) {
            mBaiduMap.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener() {
                @Override
                public void onMapStatusChangeStart(MapStatus mapStatus) {
                    invalidate();
                }

                @Override
                public void onMapStatusChangeStart(MapStatus mapStatus, int reason) {
                    invalidate();
                }

                @Override
                public void onMapStatusChange(MapStatus mapStatus) {
                    invalidate();
                }

                @Override
                public void onMapStatusChangeFinish(MapStatus mapStatus) {
                    invalidate();
                }
            });
        }
        invalidate();
    }

    public void setDrawMode(int mode) {
        mMode = mode;
        mStroke.clear();
        invalidate();
    }

    public int getDrawMode() {
        return mMode;
    }

    /** true 时消费触摸事件；false 时完全交给下面的地图。 */
    public void setDrawEnabled(boolean enabled) {
        mDrawEnabled = enabled;
        if (!enabled) {
            mTouching = false;
            mStroke.clear();
            invalidate();
        }
    }

    public List<LatLng> getPoints() {
        return new ArrayList<>(mPoints);
    }

    /** 整体替换点集（撤销恢复、载入时用）。 */
    public void setPoints(List<LatLng> points) {
        mPoints.clear();
        if (points != null) {
            mPoints.addAll(points);
        }
        invalidate();
    }

    public boolean isClosed() {
        return mClosed;
    }

    public void setClosed(boolean closed) {
        mClosed = closed;
        invalidate();
    }

    public void clearRoute() {
        mPoints.clear();
        mStroke.clear();
        mClosed = false;
        invalidate();
    }

    private Projection projection() {
        return mBaiduMap == null ? null : mBaiduMap.getProjection();
    }

    private Point toScreen(LatLng latLng) {
        Projection projection = projection();
        return projection == null ? null : projection.toScreenLocation(latLng);
    }

    private LatLng fromScreen(float x, float y) {
        Projection projection = projection();
        if (projection == null) {
            return null;
        }
        mScratch.set((int) x, (int) y);
        return projection.fromScreenLocation(mScratch);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mDrawEnabled || projection() == null) {
            return false;
        }

        mTouchX = event.getX();
        mTouchY = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouching = true;
                if (mMode == MODE_LINE) {
                    startStroke();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mMode == MODE_LINE) {
                    extendStroke();
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                mTouching = false;
                if (mMode == MODE_POINT) {
                    handlePointTap(event.getX(), event.getY());
                } else {
                    finishStroke(event.getX(), event.getY(), false);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                mTouching = false;
                if (mMode == MODE_LINE) {
                    finishStroke(event.getX(), event.getY(), true);
                }
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    /** 点绘制：落一个顶点，或命中起始点时闭合。 */
    private void handlePointTap(float x, float y) {
        LatLng tapped = fromScreen(x, y);
        if (tapped == null) {
            return;
        }

        if (isHittingStart(x, y, mPoints)) {
            mClosed = true;
            notifyChanged();
            return;
        }

        mPoints.add(tapped);
        // 加了新点就不再是闭合形状——想闭合就再点一次起始点
        mClosed = false;
        notifyChanged();
    }

    private void startStroke() {
        mStroke.clear();
        LatLng start = fromScreen(mTouchX, mTouchY);
        if (start != null) {
            mStroke.add(start);
        }
    }

    private void extendStroke() {
        LatLng candidate = fromScreen(mTouchX, mTouchY);
        if (candidate == null) {
            return;
        }
        LatLng last = mStroke.isEmpty() ? null : mStroke.get(mStroke.size() - 1);
        if (RouteGeometry.shouldSample(last, candidate,
                RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS)) {
            mStroke.add(candidate);
        }
    }

    private void finishStroke(float x, float y, boolean cancelled) {
        if (cancelled) {
            mStroke.clear();
            return;
        }

        LatLng end = fromScreen(x, y);
        if (end != null) {
            LatLng last = mStroke.isEmpty() ? null : mStroke.get(mStroke.size() - 1);
            if (RouteGeometry.shouldSample(last, end,
                    RouteGeometry.LINE_SAMPLE_MIN_DISTANCE_METERS)) {
                mStroke.add(end);
            }
        }

        if (mStroke.isEmpty()) {
            return;
        }

        // 首个笔画时，起点就是这条笔画自己的第一个点
        List<LatLng> reference = mPoints.isEmpty() ? mStroke : mPoints;
        boolean closedNow = isHittingStart(x, y, reference);

        mPoints.addAll(mStroke);
        mStroke.clear();
        mClosed = closedNow;
        notifyChanged();
    }

    /** 落点是否命中给定列表的首点，且点数已达闭合下限。 */
    private boolean isHittingStart(float x, float y, List<LatLng> reference) {
        if (!RouteGeometry.canClose(reference.size())) {
            return false;
        }
        Point first = toScreen(reference.get(0));
        if (first == null) {
            return false;
        }
        return RouteGeometry.isWithinHitRadius(
                (int) x, (int) y, first.x, first.y, mHitRadiusPx);
    }

    private void notifyChanged() {
        invalidate();
        if (mListener != null) {
            mListener.onRouteChanged();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (projection() == null) {
            return;
        }

        drawPolyline(canvas, mPoints, mClosed, mLinePaint);
        drawPolyline(canvas, mStroke, false, mStrokePaint);

        for (LatLng point : mPoints) {
            Point screen = toScreen(point);
            if (screen != null) {
                canvas.drawCircle(screen.x, screen.y, mVertexRadiusPx, mVertexPaint);
            }
        }

        if (!mPoints.isEmpty()) {
            Point start = toScreen(mPoints.get(0));
            if (start != null) {
                canvas.drawCircle(start.x, start.y, mStartRadiusPx, mStartPaint);
            }
        }
    }

    private void drawPolyline(Canvas canvas, List<LatLng> points, boolean closed, Paint paint) {
        if (points.size() < 2) {
            return;
        }
        Path path = new Path();
        Point first = null;
        boolean started = false;
        for (LatLng point : points) {
            Point screen = toScreen(point);
            if (screen == null) {
                continue;
            }
            if (!started) {
                path.moveTo(screen.x, screen.y);
                first = screen;
                started = true;
            } else {
                path.lineTo(screen.x, screen.y);
            }
        }
        if (closed && first != null) {
            path.lineTo(first.x, first.y);
        }
        canvas.drawPath(path, paint);
    }
}

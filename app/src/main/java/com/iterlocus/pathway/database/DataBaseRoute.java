package com.iterlocus.pathway.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.baidu.mapapi.model.LatLng;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.RouteConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 绘制路线的持久化。
 *
 * <p>点集序列化成 JSON 数组塞进一个 TEXT 列——路线点数不定，拆成行既难查也没意义。
 *
 * <p>名称列带 {@code COLLATE NOCASE UNIQUE}，重名在数据库层也拦得住。
 */
public class DataBaseRoute extends SQLiteOpenHelper {

    public static final String TABLE_NAME = "RouteConfig";
    public static final String DB_COLUMN_ID = "DB_COLUMN_ID";
    public static final String DB_COLUMN_NAME = "DB_COLUMN_NAME";
    public static final String DB_COLUMN_CLOSED = "DB_COLUMN_CLOSED";
    public static final String DB_COLUMN_POINTS = "DB_COLUMN_POINTS";
    public static final String DB_COLUMN_CREATED_AT = "DB_COLUMN_CREATED_AT";

    private static final int DB_VERSION = 1;
    private static final String DB_NAME = "RouteConfig.db";

    // 用常量拼 SQL 而不是写字面量：项目里另两个助手把列名硬写死在 SQL 里，
    // 一旦有人改了常量就对不上，这里不沿用那个写法。
    private static final String CREATE_TABLE = "create table if not exists " + TABLE_NAME
            + " (" + DB_COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + DB_COLUMN_NAME + " TEXT NOT NULL COLLATE NOCASE UNIQUE, "
            + DB_COLUMN_CLOSED + " INTEGER NOT NULL, "
            + DB_COLUMN_POINTS + " TEXT NOT NULL, "
            + DB_COLUMN_CREATED_AT + " BIGINT NOT NULL)";

    public DataBaseRoute(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(CREATE_TABLE);
        } catch (RuntimeException e) {
            /* 建表失败是编程错误（DDL 写坏了），不是运行时读写失败。
             * spec 的错误处理表只覆盖后者。吞掉会让数据库没有表、此后每次保存都无声失败，
             * 对用户是永久且无法解释的；记日志后照抛，让它在开发者第一次实测时立刻暴露。 */
            XLog.e("ROUTE: ERROR - onCreate");
            throw e;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        } catch (RuntimeException e) {
            XLog.e("ROUTE: ERROR - onUpgrade");
            throw e;
        }
    }

    /** 名称是否已被占用（大小写不敏感，与列的 COLLATE NOCASE 一致）。 */
    public static boolean nameExists(SQLiteDatabase db, String name) {
        if (db == null || name == null) {
            return false;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{DB_COLUMN_ID},
                    DB_COLUMN_NAME + " = ? COLLATE NOCASE",
                    new String[]{name.trim()}, null, null, null, "1");
            return cursor != null && cursor.moveToFirst();
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - nameExists");
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 插入一条路线，返回行 id；失败返回 -1。 */
    public static long insertRoute(SQLiteDatabase db, String name, boolean closed,
                                   List<LatLng> points) {
        if (db == null || name == null || points == null || points.isEmpty()) {
            return -1L;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(DB_COLUMN_NAME, name.trim());
            values.put(DB_COLUMN_CLOSED, closed ? 1 : 0);
            values.put(DB_COLUMN_POINTS, encodePoints(points));
            values.put(DB_COLUMN_CREATED_AT, System.currentTimeMillis() / 1000);
            return db.insert(TABLE_NAME, null, values);
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - insertRoute");
            return -1L;
        }
    }

    /** 按名称读取一条路线；不存在或读取失败返回 null。 */
    public static RouteConfig queryByName(SQLiteDatabase db, String name) {
        if (db == null || name == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, null,
                    DB_COLUMN_NAME + " = ? COLLATE NOCASE",
                    new String[]{name.trim()}, null, null, null, "1");
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            return routeFromCursor(cursor);
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - queryByName");
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** 原地更新路线，保留创建时间；失败或原路线不存在返回 false。 */
    public static boolean updateRoute(SQLiteDatabase db, String originalName, String newName,
                                      boolean closed, List<LatLng> points) {
        if (db == null || originalName == null || newName == null
                || points == null || points.isEmpty()) {
            return false;
        }
        try {
            ContentValues values = new ContentValues();
            values.put(DB_COLUMN_NAME, newName.trim());
            values.put(DB_COLUMN_CLOSED, closed ? 1 : 0);
            values.put(DB_COLUMN_POINTS, encodePoints(points));
            return db.update(TABLE_NAME, values,
                    DB_COLUMN_NAME + " = ? COLLATE NOCASE",
                    new String[]{originalName.trim()}) == 1;
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - updateRoute");
            return false;
        }
    }

    /** 按名称删除路线；不存在或删除失败返回 false。 */
    public static boolean deleteRoute(SQLiteDatabase db, String name) {
        if (db == null || name == null) {
            return false;
        }
        try {
            return db.delete(TABLE_NAME, DB_COLUMN_NAME + " = ? COLLATE NOCASE",
                    new String[]{name.trim()}) == 1;
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - deleteRoute");
            return false;
        }
    }

    /** 所有已保存路线的名称，最近创建的在前。 */
    public static List<String> queryAllNames(SQLiteDatabase db) {
        List<String> names = new ArrayList<>();
        if (db == null) {
            return names;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{DB_COLUMN_NAME},
                    null, null, null, null, DB_COLUMN_CREATED_AT + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - queryAllNames");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return names;
    }

    /** 所有已保存路线，最近创建的在前。 */
    public static List<RouteConfig> queryAll(SQLiteDatabase db) {
        List<RouteConfig> routes = new ArrayList<>();
        if (db == null) {
            return routes;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, null, null, null, null, null,
                    DB_COLUMN_CREATED_AT + " DESC");
            while (cursor != null && cursor.moveToNext()) {
                routes.add(routeFromCursor(cursor));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - queryAll");
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return routes;
    }

    private static RouteConfig routeFromCursor(Cursor cursor) {
        String name = cursor.getString(cursor.getColumnIndexOrThrow(DB_COLUMN_NAME));
        boolean closed = cursor.getInt(cursor.getColumnIndexOrThrow(DB_COLUMN_CLOSED)) == 1;
        String pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(DB_COLUMN_POINTS));
        long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(DB_COLUMN_CREATED_AT));
        return new RouteConfig(name, closed, decodePoints(pointsJson), createdAt);
    }

    /** 点集 → JSON 数组，元素形如 {"lat":39.9,"lng":116.4}。 */
    static String encodePoints(List<LatLng> points) {
        JSONArray array = new JSONArray();
        for (LatLng point : points) {
            JSONObject item = new JSONObject();
            try {
                item.put("lat", point.latitude);
                item.put("lng", point.longitude);
                array.put(item);
            } catch (Exception ignored) {
                // 单点写失败就跳过，不影响其余
            }
        }
        return array.toString();
    }

    /** JSON 数组 → 点集。解析失败的条目跳过。 */
    static List<LatLng> decodePoints(String json) {
        List<LatLng> points = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return points;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                points.add(new LatLng(item.optDouble("lat"), item.optDouble("lng")));
            }
        } catch (Exception e) {
            XLog.e("ROUTE: ERROR - decodePoints");
        }
        return points;
    }
}

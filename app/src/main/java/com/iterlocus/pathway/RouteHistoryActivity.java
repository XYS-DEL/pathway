package com.iterlocus.pathway;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.database.DataBaseRoute;
import com.iterlocus.pathway.utils.GoUtils;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 已保存路线的管理入口：查看、继续编辑或删除。 */
public class RouteHistoryActivity extends BaseActivity {

    private final List<RouteConfig> mRoutes = new ArrayList<>();
    private SQLiteDatabase mRouteDb;
    private RouteAdapter mAdapter;
    private ListView mListView;
    private TextView mEmptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, getTheme()));
        setContentView(R.layout.activity_route_history);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mListView = findViewById(R.id.route_history_list);
        mEmptyView = findViewById(R.id.route_history_empty);
        mAdapter = new RouteAdapter();
        mListView.setAdapter(mAdapter);

        try {
            mRouteDb = new DataBaseRoute(getApplicationContext()).getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ROUTE_HISTORY: ERROR - open database");
            GoUtils.DisplayToast(this, getString(R.string.route_history_load_failed));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadRoutes();
    }

    @Override
    protected void onDestroy() {
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

    private void reloadRoutes() {
        mRoutes.clear();
        if (mRouteDb != null) {
            mRoutes.addAll(DataBaseRoute.queryAll(mRouteDb));
        }
        mAdapter.notifyDataSetChanged();
        boolean empty = mRoutes.isEmpty();
        mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        mListView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void editRoute(RouteConfig route) {
        Intent intent = new Intent(this, RouteDrawActivity.class);
        intent.putExtra(RouteDrawActivity.EXTRA_EDIT_ROUTE_NAME, route.getName());
        startActivity(intent);
    }

    private void confirmDelete(RouteConfig route) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.route_history_delete_title)
                .setMessage(getString(R.string.route_history_delete_message, route.getName()))
                .setPositiveButton(R.string.route_history_delete_confirm, (dialog, which) -> {
                    if (DataBaseRoute.deleteRoute(mRouteDb, route.getName())) {
                        GoUtils.DisplayToast(this, getString(R.string.route_history_delete_ok));
                        reloadRoutes();
                    } else {
                        GoUtils.DisplayToast(this, getString(R.string.route_history_delete_failed));
                    }
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    private final class RouteAdapter extends BaseAdapter {
        private final DateFormat mDateFormat = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT);

        @Override
        public int getCount() {
            return mRoutes.size();
        }

        @Override
        public RouteConfig getItem(int position) {
            return mRoutes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(RouteHistoryActivity.this)
                        .inflate(R.layout.route_history_item, parent, false);
            }

            RouteConfig route = getItem(position);
            TextView name = row.findViewById(R.id.route_history_item_name);
            TextView meta = row.findViewById(R.id.route_history_item_meta);
            TextView date = row.findViewById(R.id.route_history_item_date);
            ImageButton edit = row.findViewById(R.id.route_history_item_edit);
            ImageButton delete = row.findViewById(R.id.route_history_item_delete);

            name.setText(route.getName());
            meta.setText(getString(R.string.route_history_item_meta,
                    route.getPointCount(), getString(route.isClosed()
                            ? R.string.route_sim_closed : R.string.route_sim_open)));
            date.setText(mDateFormat.format(new Date(route.getCreatedAt() * 1000L)));
            edit.setContentDescription(getString(R.string.route_history_edit_named, route.getName()));
            delete.setContentDescription(getString(R.string.route_history_delete_named, route.getName()));

            row.setOnClickListener(v -> editRoute(route));
            edit.setOnClickListener(v -> editRoute(route));
            delete.setOnClickListener(v -> confirmDelete(route));
            return row;
        }
    }
}

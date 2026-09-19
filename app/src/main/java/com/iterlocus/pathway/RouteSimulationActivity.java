package com.iterlocus.pathway;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

/**
 * 模拟路线 —— 占位界面。
 *
 * <p>本次只做交接验证：把 {@link NfcCardActivity} 传过来的参数原样列出来，
 * 确认字段接得上，避免功能做完才发现没接上。
 *
 * <p>解析 URL 取坐标、坐标系换算、自定义路线、选路线、开始模拟，都在后续版本实现，
 * 全部落在这个界面上。
 */
public class RouteSimulationActivity extends BaseActivity {

    /** NFC 卡片上的原始 URL，未做任何加工。 */
    public static final String EXTRA_CARD_URL = "EXTRA_CARD_URL";
    /** 卡片携带的包名，可能为空。 */
    public static final String EXTRA_CARD_PACKAGE = "EXTRA_CARD_PACKAGE";
    /** 来源标记，由 NFC 模块填入，是自由字符串，不要按穷举写判断。 */
    public static final String EXTRA_SOURCE = "EXTRA_SOURCE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));

        setContentView(R.layout.activity_route_simulation);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        Intent intent = getIntent();
        String url = intent.getStringExtra(EXTRA_CARD_URL);
        String packageName = intent.getStringExtra(EXTRA_CARD_PACKAGE);
        String source = intent.getStringExtra(EXTRA_SOURCE);

        StringBuilder builder = new StringBuilder();
        builder.append(getResources().getString(R.string.route_sim_received)).append("\n\n");
        builder.append(EXTRA_CARD_URL).append(":\n").append(orDash(url)).append("\n\n");
        builder.append(EXTRA_CARD_PACKAGE).append(":\n").append(orDash(packageName)).append("\n\n");
        builder.append(EXTRA_SOURCE).append(":\n").append(orDash(source));

        TextView content = findViewById(R.id.route_sim_content);
        content.setText(builder.toString());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String orDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }
}

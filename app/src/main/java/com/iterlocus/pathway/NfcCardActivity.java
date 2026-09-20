package com.iterlocus.pathway;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import com.acooldog.nfc.NfcConfigStore;
import com.acooldog.nfc.NfcPayload;
import com.acooldog.nfc.NfcReaderSession;
import com.acooldog.nfc.SavedNfcConfig;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.utils.GoUtils;
import com.iterlocus.pathway.utils.ShareUtils;

import java.util.List;

/**
 * 读取 NFC：把卡片上的 URL 与包名显示出来，再原样交接给模拟路线界面。
 *
 * <p>本界面<b>不做任何解析</b>——不提取坐标、不换算坐标系。卡片内容怎么解释、
 * 用来做什么，全部是 {@link RouteSimulationActivity} 的事。
 */
public class NfcCardActivity extends BaseActivity {

    private NfcReaderSession mSession;
    private NfcConfigStore mConfigStore;

    private TextView mStatusText;
    private View mGoSettingsBtn;
    private TextView mUrlText;
    private TextView mPackageText;
    private Button mSimulateBtn;

    /* 当前读到的内容。原样保存，不做加工 */
    private String mUrl = "";
    private String mPackageName = "";
    private String mSource = NfcReaderSession.DEFAULT_SOURCE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /* 为了启动欢迎页全屏，状态栏被设置了透明，会导致其他页面状态栏空白。
         * 除 WelcomeActivity 外的所有 Activity 都要手动填一次。 */
        getWindow().setStatusBarColor(getResources().getColor(R.color.colorPrimary, this.getTheme()));

        setContentView(R.layout.activity_nfc_card);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mConfigStore = new NfcConfigStore(this);
        NfcPayload lastPayload = mConfigStore.getLastPayload();
        if (!lastPayload.isEmpty()) {
            mUrl = lastPayload.getUrl();
            mPackageName = lastPayload.getPackageName();
            mSource = lastPayload.getSource();
        }

        mStatusText = findViewById(R.id.nfc_status_text);
        mGoSettingsBtn = findViewById(R.id.nfc_go_settings);
        mUrlText = findViewById(R.id.nfc_url_text);
        mPackageText = findViewById(R.id.nfc_package_text);
        mSimulateBtn = findViewById(R.id.nfc_btn_simulate);

        mGoSettingsBtn.setOnClickListener(v -> openNfcSettings());
        mSimulateBtn.setOnClickListener(v -> gotoRouteSimulation());
        findViewById(R.id.nfc_btn_save).setOnClickListener(v -> showSaveDialog());
        findViewById(R.id.nfc_btn_load).setOnClickListener(v -> showLoadDialog());
        findViewById(R.id.nfc_btn_share).setOnClickListener(v -> shareCurrent());

        mSession = new NfcReaderSession(this, this::onPayloadRead);

        renderCurrent();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSession.onResume();
        updateStatus();
    }

    @Override
    protected void onPause() {
        mSession.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 必须调 setIntent：否则 session 内部 getIntent() 拿到的还是上一个 Intent
        setIntent(intent);
        mSession.onNewIntent(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 读到标签内容时回调。载荷为空时不动界面，只提示。 */
    private void onPayloadRead(NfcPayload payload) {
        if (payload == null || payload.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_empty_tag));
            return;
        }

        mUrl = payload.getUrl();
        mPackageName = payload.getPackageName();
        mSource = payload.getSource();

        // 记住这次读到的内容，下次进这个界面先显示它
        mConfigStore.savePayload(mUrl, mPackageName, mSource);

        renderCurrent();
    }

    private void renderCurrent() {
        mUrlText.setText(mUrl.isEmpty()
                ? getResources().getString(R.string.nfc_card_url_empty)
                : mUrl);
        mPackageText.setText(mPackageName.isEmpty()
                ? getResources().getString(R.string.nfc_card_package_empty)
                : mPackageName);
        // 只要读到过非空内容就能交接。怎么用由模拟路线界面决定，这里不判断
        mSimulateBtn.setEnabled(!mUrl.isEmpty() || !mPackageName.isEmpty());
    }

    private void updateStatus() {
        switch (mSession.getStatus()) {
            case READY:
                mStatusText.setText(getResources().getString(R.string.nfc_card_status_ready));
                mGoSettingsBtn.setVisibility(View.GONE);
                break;
            case DISABLED:
                mStatusText.setText(getResources().getString(R.string.nfc_card_status_disabled));
                mGoSettingsBtn.setVisibility(View.VISIBLE);
                break;
            case NO_HARDWARE:
            default:
                mStatusText.setText(getResources().getString(R.string.nfc_card_status_no_hardware));
                mGoSettingsBtn.setVisibility(View.GONE);
                break;
        }
    }

    private void openNfcSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (Exception e) {
            XLog.e("NFC: ERROR - openNfcSettings");
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_status_disabled));
        }
    }

    /** 把读到的原始值交给模拟路线界面。 */
    private void gotoRouteSimulation() {
        if (mUrl.isEmpty() && mPackageName.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_nothing_read));
            return;
        }

        Intent intent = new Intent(NfcCardActivity.this, RouteSimulationActivity.class);
        intent.putExtra(RouteSimulationActivity.EXTRA_CARD_URL, mUrl);
        intent.putExtra(RouteSimulationActivity.EXTRA_CARD_PACKAGE, mPackageName);
        intent.putExtra(RouteSimulationActivity.EXTRA_SOURCE, mSource);
        startActivity(intent);
    }

    private void shareCurrent() {
        if (mUrl.isEmpty() && mPackageName.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_share_empty));
            return;
        }

        String packageText = mPackageName.isEmpty()
                ? getResources().getString(R.string.nfc_card_share_package_absent)
                : mPackageName;
        ShareUtils.shareText(
                this,
                getResources().getString(R.string.nfc_card_share_title),
                mUrl + "\n" + packageText);
    }

    private void showSaveDialog() {
        if (mUrl.isEmpty() && mPackageName.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_share_empty));
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(padding, padding / 2, padding, 0);

        final EditText nameInput = new EditText(this);
        nameInput.setHint(R.string.nfc_card_save_name_hint);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(nameInput);

        final EditText packageInput = new EditText(this);
        packageInput.setHint(R.string.nfc_card_save_package_hint);
        packageInput.setInputType(InputType.TYPE_CLASS_TEXT);
        packageInput.setText(mPackageName);
        container.addView(packageInput);

        /* 模块的 SavedNfcConfig.isComplete() 要求名称、URL、包名三者齐全，
         * 缺任何一个都会被 saveSavedConfig 静默丢弃（不报错、不保存），
         * 所以这里要显式说明为什么包名不能留空。 */
        TextView note = new TextView(this);
        note.setText(R.string.nfc_card_save_package_note);
        note.setTextSize(12);
        container.addView(note);

        new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_card_save_title)
                .setView(container)
                .setPositiveButton(R.string.app_dialog_confirm, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String packageName = packageInput.getText().toString().trim();
                    if (name.isEmpty() || packageName.isEmpty() || mUrl.isEmpty()) {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_save_incomplete));
                        return;
                    }
                    mConfigStore.saveSavedConfig(new SavedNfcConfig(name, mUrl, packageName, mSource));
                    GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_save_ok));
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }

    private void showLoadDialog() {
        final List<SavedNfcConfig> configs = mConfigStore.getSavedConfigs();
        if (configs.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.nfc_card_load_empty));
            return;
        }

        String[] names = new String[configs.size()];
        for (int index = 0; index < configs.size(); index++) {
            names[index] = configs.get(index).getName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.nfc_card_load_title)
                .setItems(names, (dialog, which) -> {
                    SavedNfcConfig picked = configs.get(which);
                    mUrl = picked.getUrl();
                    mPackageName = picked.getPackageName();
                    mSource = picked.getSource();
                    mConfigStore.savePayload(mUrl, mPackageName, mSource);
                    renderCurrent();
                })
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .show();
    }
}

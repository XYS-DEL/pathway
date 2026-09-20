package com.iterlocus.pathway;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;
import com.elvishew.xlog.XLog;
import com.iterlocus.pathway.service.ServiceGo;
import com.iterlocus.pathway.utils.GoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class FragmentSettings extends PreferenceFragmentCompat {

    private static final int ALTITUDE_SAMPLE_COUNT = 3;
    private static final long ALTITUDE_TIMEOUT_MS = 20_000L;
    private static final double MIN_ALTITUDE_METERS = -500d;
    private static final double MAX_ALTITUDE_METERS = 10_000d;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Double> mAltitudeSamples = new ArrayList<>();
    private LocationClient mAltitudeClient;
    private Preference mAltitudePreference;
    private AlertDialog mAltitudeDialog;
    private EditText mAltitudeInput;
    private boolean mCapturingAltitude;

    private final Runnable mAltitudeTimeout = () -> finishAltitudeCapture(false);

    // Set a non-empty decimal EditTextPreference
    private void setupDecimalEditTextPreference(EditTextPreference preference) {
        if (preference != null) {
            preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
            preference.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_CLASS_NUMBER);
                editText.setSelection(editText.length());
            });
            preference.setOnPreferenceChangeListener((pref, newValue) -> {
                if (newValue.toString().trim().isEmpty()) {
                    GoUtils.DisplayToast(this.getContext(), getResources().getString(R.string.app_error_input_null));
                    return false;
                }
                return true;
            });
        }
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences_main);

        ListPreference pfJoystick = findPreference("setting_joystick_type");
        if (pfJoystick != null) {
            // 使用自定义 SummaryProvider
            pfJoystick.setSummaryProvider((Preference.SummaryProvider<ListPreference>) preference -> Objects.requireNonNull(preference.getEntry()));
            pfJoystick.setOnPreferenceChangeListener((preference, newValue) -> !newValue.toString().trim().isEmpty());
        }

        EditTextPreference pfWalk = findPreference("setting_walk");
        setupDecimalEditTextPreference(pfWalk);

        EditTextPreference pfRun = findPreference("setting_run");
        setupDecimalEditTextPreference(pfRun);

        EditTextPreference pfBike = findPreference("setting_bike");
        setupDecimalEditTextPreference(pfBike);

        Preference pfAltitude = findPreference("setting_altitude");
        mAltitudePreference = pfAltitude;
        if (pfAltitude != null) {
            updateAltitudeSummary();
            pfAltitude.setOnPreferenceClickListener(preference -> {
                showAltitudeDialog();
                return true;
            });
        }

        EditTextPreference pfLatOffset = findPreference("setting_lat_max_offset");
        setupDecimalEditTextPreference(pfLatOffset);

        EditTextPreference pfLonOffset = findPreference("setting_lon_max_offset");
        setupDecimalEditTextPreference(pfLonOffset);

        SwitchPreferenceCompat pLog = findPreference("setting_log_off");
        if (pLog != null) {
            pLog.setOnPreferenceChangeListener((preference, newValue) -> {
                if(((SwitchPreferenceCompat) preference).isChecked() != (Boolean) newValue) {
                    XLog.d(preference.getKey() + newValue);

                    if (Boolean.parseBoolean(newValue.toString())) {
                        XLog.d("on");
                    } else {
                        XLog.d("off");
                    }
                    return true;
                } else {
                    return false;
                }
            });
        }

        EditTextPreference pfPosHisValid = findPreference("setting_history_expiration");
        setupDecimalEditTextPreference(pfPosHisValid);

        // 设置版本号
        String verName;
        verName = GoUtils.getVersionName(FragmentSettings.this.getContext());
        Preference pfVersion = findPreference("setting_version");
        if (pfVersion != null) {
            pfVersion.setSummary(verName);
        }
    }

    private void updateAltitudeSummary() {
        if (mAltitudePreference != null) {
            mAltitudePreference.setSummary(PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString("setting_altitude", getString(R.string.setting_altitude_default)));
        }
    }

    private void showAltitudeDialog() {
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        mAltitudeInput = new EditText(requireContext());
        mAltitudeInput.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        mAltitudeInput.setText(PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("setting_altitude", getString(R.string.setting_altitude_default)));
        mAltitudeInput.setSelectAllOnFocus(true);
        container.addView(mAltitudeInput);

        mAltitudeDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.setting_altitude_dialog_title)
                .setView(container)
                .setPositiveButton(R.string.app_dialog_confirm, null)
                .setNegativeButton(R.string.app_dialog_cancel, null)
                .setNeutralButton(R.string.setting_altitude_current, null)
                .create();
        mAltitudeDialog.setOnShowListener(dialog -> {
            mAltitudeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (saveAltitudeInput()) {
                    mAltitudeDialog.dismiss();
                }
            });
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.setting_altitude_current)
                            .setMessage(R.string.setting_altitude_capture_notice)
                            .setPositiveButton(R.string.app_dialog_confirm,
                                    (notice, which) -> captureCurrentAltitude())
                            .setNegativeButton(R.string.app_dialog_cancel, null)
                            .show());
        });
        mAltitudeDialog.setOnDismissListener(dialog -> {
            if (!mCapturingAltitude) {
                mAltitudeDialog = null;
                mAltitudeInput = null;
            }
        });
        mAltitudeDialog.show();
    }

    private boolean saveAltitudeInput() {
        if (mAltitudeInput == null) {
            return false;
        }
        String raw = mAltitudeInput.getText().toString().trim();
        try {
            double altitude = Double.parseDouble(raw);
            if (Double.isFinite(altitude)
                    && altitude >= MIN_ALTITUDE_METERS
                    && altitude <= MAX_ALTITUDE_METERS) {
                String stored = String.format(Locale.US, "%.1f", altitude);
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putString("setting_altitude", stored)
                        .apply();
                updateAltitudeSummary();
                return true;
            }
        } catch (NumberFormatException ignored) {
            // 统一走下面的明确提示。
        }
        GoUtils.DisplayToast(requireContext(), getString(R.string.setting_altitude_invalid));
        return false;
    }

    private void captureCurrentAltitude() {
        if (mAltitudeClient != null) {
            return;
        }
        if (ServiceGo.isAlive()) {
            GoUtils.DisplayToast(requireContext(), getString(R.string.setting_altitude_stop_mock));
            return;
        }
        if (!GoUtils.isGpsOpened(requireContext())) {
            GoUtils.showEnableGpsDialog(requireContext());
            return;
        }

        mAltitudeSamples.clear();
        mCapturingAltitude = true;
        if (mAltitudeDialog != null) {
            mAltitudeDialog.setCancelable(false);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setText(R.string.setting_altitude_locating);
        }

        try {
            mAltitudeClient = new LocationClient(requireContext().getApplicationContext());
            mAltitudeClient.registerLocationListener(new BDAbstractLocationListener() {
                @Override
                public void onReceiveLocation(BDLocation location) {
                    if (!mCapturingAltitude || !isGnssAltitude(location)) {
                        return;
                    }
                    mAltitudeSamples.add(location.getAltitude());
                    if (mAltitudeSamples.size() >= ALTITUDE_SAMPLE_COUNT) {
                        finishAltitudeCapture(true);
                    }
                }
            });

            LocationClientOption option = new LocationClientOption();
            option.setLocationMode(LocationClientOption.LocationMode.Device_Sensors);
            option.setScanSpan(1000);
            option.setOpenGnss(true);
            option.setLocationNotify(true);
            option.setIsNeedAltitude(true);
            option.SetIgnoreCacheException(true);
            mAltitudeClient.setLocOption(option);
            mAltitudeClient.start();
            mHandler.postDelayed(mAltitudeTimeout, ALTITUDE_TIMEOUT_MS);
        } catch (Exception e) {
            XLog.e("SETTINGS: ERROR - captureCurrentAltitude");
            finishAltitudeCapture(false);
        }
    }

    private boolean isGnssAltitude(BDLocation location) {
        if (location == null || !location.hasAltitude()) {
            return false;
        }
        int type = location.getLocType();
        double altitude = location.getAltitude();
        return (type == BDLocation.TypeGpsLocation || type == BDLocation.TypeGnssLocation)
                && Double.isFinite(altitude)
                && altitude >= MIN_ALTITUDE_METERS
                && altitude <= MAX_ALTITUDE_METERS;
    }

    private void finishAltitudeCapture(boolean enoughSamples) {
        if (!mCapturingAltitude && mAltitudeClient == null) {
            return;
        }
        mCapturingAltitude = false;
        mHandler.removeCallbacks(mAltitudeTimeout);
        if (mAltitudeClient != null) {
            mAltitudeClient.stop();
            mAltitudeClient = null;
        }

        if (mAltitudeDialog != null) {
            mAltitudeDialog.setCancelable(true);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
            mAltitudeDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setText(R.string.setting_altitude_current);
        }

        if (!enoughSamples || mAltitudeSamples.size() < ALTITUDE_SAMPLE_COUNT
                || mAltitudeInput == null || !isAdded()) {
            if (isAdded()) {
                GoUtils.DisplayToast(requireContext(), getString(R.string.setting_altitude_failed));
            }
            return;
        }

        Collections.sort(mAltitudeSamples);
        double altitude = mAltitudeSamples.get(mAltitudeSamples.size() / 2);
        String stored = String.format(Locale.US, "%.1f", altitude);
        mAltitudeInput.setText(stored);
        mAltitudeInput.setSelection(stored.length());
        GoUtils.DisplayToast(requireContext(),
                getString(R.string.setting_altitude_saved, altitude));
    }

    @Override
    public void onDestroy() {
        mCapturingAltitude = false;
        mHandler.removeCallbacks(mAltitudeTimeout);
        if (mAltitudeClient != null) {
            mAltitudeClient.stop();
            mAltitudeClient = null;
        }
        if (mAltitudeDialog != null) {
            mAltitudeDialog.dismiss();
            mAltitudeDialog = null;
            mAltitudeInput = null;
        }
        super.onDestroy();
    }
}

package com.liskovsoft.smartyoutubetv2.karaokemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokePairing;
import com.liskovsoft.smartyoutubetv2.karaoke.core.KaraokeProtocol;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TvFinder.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final long UI_REFRESH_MS = 50;
    private static final String PREFS = "mic";
    private static final String PREF_ADDRESS = "address";
    private static final String PREF_TV_NAME = "tv_name";
    private static final String PREF_SINGER = "singer";
    private static final String PREF_GAIN = "gain";
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<TvFinder.Tv> mTvs = new ArrayList<>();
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refreshState();
            mHandler.postDelayed(this, UI_REFRESH_MS);
        }
    };
    private TvFinder mTvFinder;
    private SharedPreferences mPrefs;
    private ImageButton mMicButton;
    private TextView mStatus;
    private LevelMeterView mLevel;
    private Button mMute;
    private TextView mGainLabel;
    private SeekBar mGain;
    private EditText mSinger;
    private TextView mTvsHint;
    private LinearLayout mTvsContainer;
    private EditText mAddress;
    /**
     * Name of the TV the address field points to, if it came from the list.
     */
    private String mSelectedTvName;
    private String mSelectedTvAddress;
    /**
     * Last failure, stays on screen after the session ended.
     */
    private String mLastError;
    private boolean mIsStartPending;
    private AlertDialog mPinDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mTvFinder = new TvFinder(this, this);

        mMicButton = findViewById(R.id.mic_button);
        mStatus = findViewById(R.id.status);
        mLevel = findViewById(R.id.level);
        mMute = findViewById(R.id.mute);
        mGainLabel = findViewById(R.id.gain_label);
        mGain = findViewById(R.id.gain);
        mSinger = findViewById(R.id.singer);
        mTvsHint = findViewById(R.id.tvs_hint);
        mTvsContainer = findViewById(R.id.tvs);
        mAddress = findViewById(R.id.address);

        mAddress.setText(mPrefs.getString(PREF_ADDRESS, ""));
        mSelectedTvName = mPrefs.getString(PREF_TV_NAME, null);
        mSelectedTvAddress = mAddress.getText().toString();
        mAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().equals(mSelectedTvAddress)) {
                    mSelectedTvName = null; // typed by hand
                    mSelectedTvAddress = null;
                }
                renderTvs();
            }
        });
        mSinger.setText(mPrefs.getString(PREF_SINGER, ""));

        int gainPercent = mPrefs.getInt(PREF_GAIN, 100);
        mGain.setProgress(gainPercent);
        applyGain(gainPercent);
        mGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyGain(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mPrefs.edit().putInt(PREF_GAIN, seekBar.getProgress()).apply();
            }
        });

        mMute.setOnClickListener(v -> {
            MicStreamService.setMuted(!MicStreamService.isMuted());
            refreshState();
        });

        mMicButton.setOnClickListener(v -> {
            if (MicStreamService.getStreamer() != null) {
                MicStreamService.stop(this);
            } else {
                startSinging();
            }
            refreshState();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mTvFinder.start();
        mHandler.post(mRefresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mTvFinder.stop();
        mHandler.removeCallbacks(mRefresh);
        dismissPinDialog();
        mPrefs.edit()
                .putString(PREF_SINGER, mSinger.getText().toString().trim())
                .apply();
    }

    @Override
    public void onTvsChanged(List<TvFinder.Tv> tvs) {
        mTvs.clear();
        mTvs.addAll(tvs);

        // Exactly one TV around and nothing picked yet: just use it
        if (tvs.size() == 1 && TextUtils.isEmpty(mAddress.getText()) && MicStreamService.getStreamer() == null) {
            selectTv(tvs.get(0));
        }

        renderTvs();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_PERMISSIONS || !mIsStartPending) {
            return;
        }

        mIsStartPending = false;

        if (hasMicPermission()) {
            startSinging();
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    private void startSinging() {
        String address = mAddress.getText().toString().trim();

        if (address.isEmpty()) {
            Toast.makeText(this, R.string.pick_tv_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasMicPermission()) {
            mIsStartPending = true;
            List<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS); // "mic is live" notification
            }
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }

        String host = parseHost(address);
        int port = parsePort(address);
        String tvLabel = mSelectedTvName != null ? mSelectedTvName : host;
        mLastError = null;

        mPrefs.edit()
                .putString(PREF_ADDRESS, address)
                .putString(PREF_TV_NAME, mSelectedTvName)
                .putString(PREF_SINGER, mSinger.getText().toString().trim())
                .apply();

        MicStreamService.start(this, host, port, tvLabel, getSingerName());
    }

    private void refreshState() {
        MicStreamer streamer = MicStreamService.getStreamer();
        boolean isLive = streamer != null;

        mMicButton.setActivated(isLive);
        mMute.setText(MicStreamService.isMuted() ? R.string.unmute : R.string.mute);
        mLevel.setLevel(isLive ? streamer.getLevel() : 0);

        int status = isLive ? streamer.getStatus() : -1;
        if (status == MicStreamer.STATUS_NEEDS_PIN || status == MicStreamer.STATUS_WRONG_PIN) {
            showPinDialog(status == MicStreamer.STATUS_WRONG_PIN);
        } else {
            dismissPinDialog();
        }

        if (!isLive) {
            mStatus.setText(mLastError != null ? mLastError : getString(R.string.tap_to_sing));
            return;
        }

        String tvName = streamer.getTvName() != null ? streamer.getTvName() :
                (mSelectedTvName != null ? mSelectedTvName : parseHost(mAddress.getText().toString().trim()));

        switch (status) {
            case MicStreamer.STATUS_CONNECTED:
                String live = getString(R.string.status_connected, tvName, streamer.getSlot(), Math.max(0, streamer.getRttMs()));
                mStatus.setText(MicStreamService.isMuted() ? live + "\n" + getString(R.string.status_muted) : live);
                break;
            case MicStreamer.STATUS_NO_ANSWER:
                mStatus.setText(getString(R.string.status_no_answer, tvName));
                break;
            case MicStreamer.STATUS_FULL:
                mStatus.setText(getString(R.string.status_full, tvName));
                break;
            case MicStreamer.STATUS_UNSUPPORTED:
                mStatus.setText(getString(R.string.status_unsupported, tvName));
                break;
            case MicStreamer.STATUS_NEEDS_PIN:
            case MicStreamer.STATUS_WRONG_PIN:
                mStatus.setText(getString(R.string.status_needs_pin, tvName));
                break;
            case MicStreamer.STATUS_PIN_LOCKED:
                mStatus.setText(getString(R.string.status_pin_locked, tvName));
                break;
            case MicStreamer.STATUS_MIC_ERROR:
                mLastError = getString(R.string.status_mic_error);
                mStatus.setText(mLastError);
                MicStreamService.stop(this);
                break;
            case MicStreamer.STATUS_NETWORK_ERROR:
                mLastError = getString(R.string.status_network_error, tvName);
                mStatus.setText(mLastError);
                MicStreamService.stop(this);
                break;
            default:
                mStatus.setText(getString(R.string.status_connecting, tvName));
                break;
        }
    }

    private void showPinDialog(boolean isWrong) {
        if (mPinDialog != null && mPinDialog.isShowing()) {
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(KaraokePairing.PIN_DIGITS)});
        input.setHint(R.string.pin_hint);

        LinearLayout container = new LinearLayout(this);
        int padding = dp(20);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mPinDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pin_title)
                .setMessage(isWrong ? R.string.pin_wrong : R.string.pin_message)
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(R.string.pin_connect, (dialog, which) -> {
                    String pin = KaraokePairing.normalizePin(input.getText().toString());
                    if (pin != null) {
                        MicStreamService.setPin(pin);
                    } else {
                        Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_SHORT).show(); // the dialog comes back
                    }
                })
                .setNegativeButton(R.string.stop, (dialog, which) -> MicStreamService.stop(this))
                .create();
        mPinDialog.show();
    }

    private void dismissPinDialog() {
        if (mPinDialog != null) {
            if (mPinDialog.isShowing()) {
                mPinDialog.dismiss();
            }
            mPinDialog = null;
        }
    }

    private void renderTvs() {
        mTvsContainer.removeAllViews();
        mTvsHint.setVisibility(mTvs.isEmpty() ? View.VISIBLE : View.GONE);

        String address = mAddress.getText().toString().trim();
        int padding = dp(14);

        for (TvFinder.Tv tv : mTvs) {
            TextView item = new TextView(this);
            item.setText(tv.toString());
            item.setTextColor(Color.WHITE);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            item.setBackgroundResource(R.drawable.bg_tv_item);
            item.setPadding(padding, padding, padding, padding);
            item.setActivated(tv.getAddress().equals(address));
            item.setOnClickListener(v -> {
                if (MicStreamService.getStreamer() != null) {
                    Toast.makeText(this, R.string.stop_to_switch, Toast.LENGTH_SHORT).show();
                    return;
                }
                selectTv(tv);
                renderTvs();
            });

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            mTvsContainer.addView(item, params);
        }
    }

    private void selectTv(TvFinder.Tv tv) {
        mSelectedTvAddress = tv.getAddress();
        mAddress.setText(mSelectedTvAddress);
        mSelectedTvName = tv.name; // after setText(), the watcher clears it otherwise
    }

    private void applyGain(int percent) {
        mGainLabel.setText(getString(R.string.mic_boost, percent));
        MicStreamService.setGain(percent / 100f);
    }

    private String getSingerName() {
        String singer = mSinger.getText().toString().trim();

        if (!singer.isEmpty()) {
            return singer;
        }

        String deviceName = Build.VERSION.SDK_INT >= 25 ? Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME) : null;

        return !TextUtils.isEmpty(deviceName) ? deviceName : Build.MODEL;
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * "192.168.1.20:47900", "192.168.1.20", "[fe80::1]:47900"
     */
    static String parseHost(String address) {
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            return end > 0 ? address.substring(1, end) : address;
        }

        int colon = address.indexOf(':');
        return colon > 0 && address.indexOf(':', colon + 1) == -1 ? address.substring(0, colon) : address;
    }

    static int parsePort(String address) {
        int colon = address.lastIndexOf(':');
        boolean hasPort = address.startsWith("[") ? address.indexOf("]:") > 0 : colon > 0 && address.indexOf(':') == colon;

        if (hasPort) {
            try {
                int port = Integer.parseInt(address.substring(colon + 1));
                if (port > 0 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // Fall back to the default
            }
        }

        return KaraokeProtocol.DEFAULT_PORT;
    }
}

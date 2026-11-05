package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Holds the volume boost for as long as the user leaves it on.
 * Primary: LoudnessEnhancer on session 0 (global).
 * Fallback: Equalizer on session 0 with shaped band gains (like commercial boosters).
 * Runs as sticky foreground service and re-applies every 45 s so TVs that drop the
 * effect keep the boost until the user cycles it back to 0 %.
 */
public class VolumeBoostService extends Service {
    private static final String CH = "volume";
    private static final long REAPPLY_MS = 45_000L;

    private LoudnessEnhancer loudness;
    private Equalizer equalizer;
    private short eqBands;
    private short eqMinLevel;
    private short eqMaxLevel;
    private boolean useEqualizer;   // true when LoudnessEnhancer failed / not available

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reapply = this::reapplyIfNeeded;
    private boolean userToldRefused;
    private long lastRaiseAt;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Volume Booster", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, CH)
                    .setContentTitle("Volume Booster")
                    .setSmallIcon(R.drawable.icon)
                    .setOngoing(true)
                    .build();
            startForeground(74, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int pct = Prefs.volumeBoostPct();
        if (pct <= 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        raise(pct, /*silent*/ false);
        main.removeCallbacks(reapply);
        main.postDelayed(reapply, REAPPLY_MS);
        return START_STICKY;
    }

    private void reapplyIfNeeded() {
        final int pct = Prefs.volumeBoostPct();
        if (pct <= 0) {
            stopSelf();
            return;
        }
        raise(pct, /*silent*/ true);
        main.postDelayed(reapply, REAPPLY_MS);
    }

    private void raise(int pct, boolean silent) {
        int gainMb = VolumeBoost.gainMb(pct);
        try {
            if (!useEqualizer) {
                if (loudness == null) {
                    loudness = new LoudnessEnhancer(0);
                }
                loudness.setTargetGain(gainMb);
                loudness.setEnabled(true);
                boolean on = loudness.getEnabled();
                lastRaiseAt = SystemClock.elapsedRealtime();
                AppLog.w("volume boost LE: " + pct + "% = +" + (gainMb / 100f) + " dB, enabled=" + on
                        + (silent ? " (re-apply)" : ""));
                if (!silent) {
                    toast(on
                            ? Ui.tr("Volume Booster") + " +" + pct + "%  (+" + (gainMb / 100) + " dB)"
                            : Ui.tr("This TV refused the volume effect."));
                } else if (!on && !userToldRefused) {
                    userToldRefused = true;
                    toast(Ui.tr("This TV refused the volume effect."));
                }
                if (on) return;
                // LE refused → fall through to Equalizer
                releaseLoudness();
                useEqualizer = true;
            }

            // Fallback: Equalizer (session 0)
            if (equalizer == null) {
                equalizer = new Equalizer(0, 0);   // priority 0, session 0 (global)
                eqBands = equalizer.getNumberOfBands();
                short[] range = equalizer.getBandLevelRange();
                eqMinLevel = range[0];
                eqMaxLevel = range[1];
                AppLog.w("volume boost EQ: bands=" + eqBands + " range=" + eqMinLevel + ".." + eqMaxLevel);
            }
            applyEqualizer(pct);
            lastRaiseAt = SystemClock.elapsedRealtime();
            AppLog.w("volume boost EQ: " + pct + "% applied" + (silent ? " (re-apply)" : ""));
            if (!silent) {
                toast(Ui.tr("Volume Booster") + " +" + pct + "% (EQ)");
            }
        } catch (RuntimeException | Error e) {
            AppLog.w("volume boost effect: " + e);
            releaseAll();
            if (!silent && !userToldRefused) {
                userToldRefused = true;
                toast(Ui.tr("This TV doesn't allow a volume boost on its sound output."));
            }
        }
    }

    /**
     * Map 0–150 % → band levels. Uses a light "shape" so very low and very high bands
     * get a bit less gain (reduces boom/hiss), same idea as commercial boosters.
     */
    private void applyEqualizer(int pct) {
        if (equalizer == null || eqBands <= 0) return;
        // Scale: 100 % → full maxLevel, 150 % → a bit over (clamped by the device)
        int target = (int) ((pct / 100.0) * eqMaxLevel);
        if (target > eqMaxLevel) target = eqMaxLevel;
        if (target < 0) target = 0;

        equalizer.setEnabled(target > 0);
        for (short i = 0; i < eqBands; i++) {
            int centerHz = equalizer.getCenterFreq(i) / 1000;
            short level;
            if (centerHz < 150) {
                level = 0;                           // protect sub-bass
            } else if (centerHz < 250) {
                level = (short) (target / 2);        // soft low-mid
            } else if (centerHz > 8000) {
                level = (short) ((target * 3) / 4);  // soft treble
            } else {
                level = (short) target;              // full boost
            }
            if (level > eqMaxLevel) level = eqMaxLevel;
            if (level < eqMinLevel) level = eqMinLevel;
            equalizer.setBandLevel(i, level);
        }
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    private void releaseLoudness() {
        if (loudness != null) {
            try {
                loudness.setEnabled(false);
                loudness.release();
            } catch (RuntimeException ignored) {
            }
            loudness = null;
        }
    }

    private void releaseEqualizer() {
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (RuntimeException ignored) {
            }
            equalizer = null;
        }
    }

    private void releaseAll() {
        releaseLoudness();
        releaseEqualizer();
        useEqualizer = false;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(reapply);
        releaseAll();
        // Keep the boost alive across process death while the user still wants it.
        if (Prefs.volumeBoostPct() > 0) {
            try {
                Intent i = new Intent(this, VolumeBoostService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(i);
                } else {
                    startService(i);
                }
            } catch (RuntimeException e) {
                AppLog.w("volume boost restart after destroy: " + e.getMessage());
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
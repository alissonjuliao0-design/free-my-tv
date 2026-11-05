package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Volume Booster: digital gain on the system output mix.
 * Primary path = LoudnessEnhancer (session 0). Fallback = Equalizer (session 0).
 * The effect only lives while the process that owns it does, so VolumeBoostService
 * keeps it in a sticky foreground service and re-applies it periodically.
 * Stays on until the user cycles the level back to 0 %.
 */
final class VolumeBoost {
    private VolumeBoost() {}

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The gain for a level, in millibels: 20 dB at 100 per cent, 30 dB at 150. */
    static int gainMb(int pct) {
        return pct * 20;
    }

    /** One step up the levels (off after the last), and applied. */
    static void cycle(Context c) {
        int now = Prefs.volumeBoostPct(), next = Prefs.VOLUME_LEVELS[0];
        for (int i = 0; i < Prefs.VOLUME_LEVELS.length; i++) {
            if (Prefs.VOLUME_LEVELS[i] == now) {
                next = Prefs.VOLUME_LEVELS[(i + 1) % Prefs.VOLUME_LEVELS.length];
                break;
            }
        }
        Prefs.setVolumeBoostPct(next);
        apply(c.getApplicationContext());
    }

    /** Starts the service with the level, or stops it when it is off. */
    static void apply(Context c) {
        Intent i = new Intent(c, VolumeBoostService.class);
        if (Prefs.volumeBoostPct() <= 0) {
            try {
                c.stopService(i);
            } catch (RuntimeException ignored) {
            }
            restoreOldVolumeSteps(c);
            main.post(() -> Toast.makeText(c, Ui.tr("Volume Booster off."), Toast.LENGTH_SHORT).show());
            return;
        }
        start(c);
    }

    /** After a restart, or when the app starts: the effect is gone with the process, so it is raised again. */
    static void restoreIfNeeded(Context c) {
        if (Prefs.volumeBoostPct() > 0) {
            start(c);
        }
    }

    private static void start(Context c) {
        Intent i = new Intent(c, VolumeBoostService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException e) {
            AppLog.w("volume boost start: " + e.getMessage());
        }
    }

    /** An earlier version of the booster changed the volume steps through ADB; this puts them back, once. */
    private static void restoreOldVolumeSteps(Context c) {
        final int original = Prefs.volumeStepsOriginal();
        if (original <= 0 || !Capabilities.adb(c)) return;
        new Thread(() -> {
            try (Adb adb = Adb.connect(c, 8_000, null)) {
                adb.shell("settings put system volume_steps " + original);
                adb.shell("settings put system media_vol_steps " + original);
                Prefs.setVolumeStepsOriginal(-1);
            } catch (Exception e) {
                AppLog.w("volume steps restore: " + e.getMessage());
            }
        }, "volume-steps").start();
    }
}
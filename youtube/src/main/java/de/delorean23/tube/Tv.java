package de.delorean23.tube;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * Fernseher (Android TV / Google TV): Bedienung mit der Fernbedienung.
 * Der ausgewählte Eintrag bekommt einen weißen Rahmen und wird etwas größer.
 * Created by DeLorean23
 */
public final class Tv {

    private static Boolean tv;

    private Tv() {
    }

    public static boolean isTv(Context c) {
        if (tv == null) {
            UiModeManager um = (UiModeManager) c.getApplicationContext().getSystemService(Context.UI_MODE_SERVICE);
            boolean t = um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
            PackageManager pm = c.getPackageManager();
            if (!t && pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) t = true;
            tv = t;
        }
        return tv;
    }

    /** Rahmen um das fokussierte Element (für alle Bildschirme der App). */
    public static void install(Activity a) {
        if (!isTv(a) || Build.VERSION.SDK_INT < 23) return;
        View decor = a.getWindow().getDecorView();
        if (decor.getTag(R.id.tvFocusTag) != null) return;
        float d = a.getResources().getDisplayMetrics().density;
        ViewTreeObserver.OnGlobalFocusChangeListener l = (oldFocus, newFocus) -> {
            if (oldFocus != null) unmark(oldFocus);
            if (newFocus != null) mark(newFocus, d);
        };
        decor.setTag(R.id.tvFocusTag, l);
        decor.getViewTreeObserver().addOnGlobalFocusChangeListener(l);
    }

    private static void mark(View v, float d) {
        // Texteingaben und der Video-Player selbst bekommen keinen Rahmen
        if (v instanceof android.widget.EditText || v instanceof androidx.media3.ui.PlayerView) return;
        if (v.getTag(R.id.tvFocusOld) == null) {
            Drawable old = v.getForeground();
            v.setTag(R.id.tvFocusOld, old != null ? old : NONE);
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22FFFFFF);
        g.setStroke(Math.round(3 * d), 0xFFFFFFFF);
        g.setCornerRadius(10 * d);
        v.setForeground(g);
        boolean big = v.getWidth() > 0 && v.getWidth() < v.getRootView().getWidth() * 0.6f;
        if (big) v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
    }

    private static void unmark(View v) {
        Object old = v.getTag(R.id.tvFocusOld);
        if (old != null) {
            v.setForeground(old == NONE ? null : (Drawable) old);
            v.setTag(R.id.tvFocusOld, null);
        }
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
    }

    private static final Object NONE = new Object();
}

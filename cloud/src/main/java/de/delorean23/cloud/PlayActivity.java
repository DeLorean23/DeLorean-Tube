package de.delorean23.cloud;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Xbox Cloud Gaming web page in phone mode (touch controls for supported games),
 * fullscreen, landscape. Also used once for signing in.
 */
public class PlayActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_SIGNIN = "signin";

    private WebView web;
    private View loading;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean signIn;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        signIn = getIntent().getBooleanExtra(EXTRA_SIGNIN, false);
        if (signIn) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setContentView(R.layout.activity_play);
        web = findViewById(R.id.web);
        loading = findViewById(R.id.loading);
        fullscreen = findViewById(R.id.fullscreen);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        ((TextView) findViewById(R.id.loading_text)).setText(
                signIn ? "Xbox Cloud Gaming" : (name != null ? name : ""));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // look like Chrome on a phone, so Xbox Cloud Gaming offers touch controls
        s.setUserAgentString(mobileChromeUa(s.getUserAgentString()));

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setBackgroundColor(0xFF000000);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() != null ? u.getScheme() : "";
                if (scheme.equals("http") || scheme.equals("https")) return false;
                return true;   // no jumps into other apps (intent:, market:, ...)
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                super.onPageStarted(v, url, f);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                loading.postDelayed(() -> loading.setVisibility(View.GONE), 600);
                if (!signIn) injectPad();
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                // only what the stream may ask for (voice chat); no camera
                List<String> ok = new ArrayList<>();
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) ok.add(res);
                }
                if (ok.isEmpty()) r.deny();
                else r.grant(ok.toArray(new String[0]));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCallback = cb;
                fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreen.setVisibility(View.VISIBLE);
                web.setVisibility(View.GONE);
                immersive();
            }

            @Override
            public void onHideCustomView() {
                leaveCustomView();
            }
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (b != null) web.restoreState(b);
        else web.loadUrl(url != null ? url : "https://www.xbox.com/de-DE/play");
        immersive();
    }

    static String mobileChromeUa(String ua) {
        String out = ua.replace("; wv", "").replaceAll("Version/\\d+(\\.\\d+)* ", "");
        if (!out.contains("Mobile")) out = out.replace("Safari/", "Mobile Safari/");
        return out;
    }

    private void leaveCustomView() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customView = null;
        customCallback = null;
        immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (signIn) return;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    // Back: during a stream ask before leaving
    @Override
    public void onBackPressed() {
        if (customView != null) { leaveCustomView(); return; }
        if (signIn) {
            if (web.canGoBack()) web.goBack();
            else finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Stream beenden?")
                .setMessage("Zurück zur Spieleliste.")
                .setPositiveButton("Beenden", (d, w) -> finish())
                .setNegativeButton("Weiterspielen", (d, w) -> immersive())
                .setOnCancelListener(d -> immersive())
                .show();
    }

    // ------------------------------------------------------------ controller
    // A Bluetooth/USB controller is passed to the page as a standard gamepad
    // (Gamepad API), the same way Chrome does it. The Xbox / Guide button
    // opens "Stream beenden?".
    private final float[] buttons = new float[17];
    private final float[] axes = new float[4];
    private boolean padSeen;

    private static int buttonIndex(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_L1: return 4;
            case KeyEvent.KEYCODE_BUTTON_R1: return 5;
            case KeyEvent.KEYCODE_BUTTON_L2: return 6;
            case KeyEvent.KEYCODE_BUTTON_R2: return 7;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 8;
            case KeyEvent.KEYCODE_BUTTON_START: return 9;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 10;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 11;
            case KeyEvent.KEYCODE_DPAD_UP: return 12;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 13;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 14;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 15;
            default: return -1;
        }
    }

    private static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (signIn || !fromPad(e.getSource())) return super.dispatchKeyEvent(e);
        if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        int i = buttonIndex(e.getKeyCode());
        if (i < 0) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP) {
            float v = e.getAction() == KeyEvent.ACTION_DOWN ? 1f : 0f;
            if (buttons[i] != v) { buttons[i] = v; sendPad(); }
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (signIn || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        axes[0] = dead(e.getAxisValue(MotionEvent.AXIS_X));
        axes[1] = dead(e.getAxisValue(MotionEvent.AXIS_Y));
        axes[2] = dead(e.getAxisValue(MotionEvent.AXIS_Z));
        axes[3] = dead(e.getAxisValue(MotionEvent.AXIS_RZ));
        float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        if (lt > 0 || buttons[6] < 1) buttons[6] = lt;
        if (rt > 0 || buttons[7] < 1) buttons[7] = rt;
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        buttons[14] = hx < -0.5f ? 1 : 0;
        buttons[15] = hx > 0.5f ? 1 : 0;
        buttons[12] = hy < -0.5f ? 1 : 0;
        buttons[13] = hy > 0.5f ? 1 : 0;
        sendPad();
        return true;
    }

    private static float dead(float v) { return Math.abs(v) < 0.08f ? 0f : v; }

    private void sendPad() {
        StringBuilder s = new StringBuilder("window.__dlpad&&window.__dlpad([");
        for (int i = 0; i < buttons.length; i++) { if (i > 0) s.append(','); s.append(buttons[i]); }
        s.append("],[");
        for (int i = 0; i < axes.length; i++) { if (i > 0) s.append(','); s.append(axes[i]); }
        s.append("])");
        if (!padSeen) { padSeen = true; injectPad(); }
        web.evaluateJavascript(s.toString(), null);
    }

    // virtual standard gamepad for the page
    private static final String PAD_JS = "(function(){if(window.__dlpad)return;"
            + "var pad={id:'Xbox Wireless Controller (STANDARD GAMEPAD Vendor: 045e Product: 0b13)',index:0,"
            + "connected:false,mapping:'standard',timestamp:0,axes:[0,0,0,0],buttons:[],vibrationActuator:null};"
            + "for(var i=0;i<17;i++)pad.buttons.push({pressed:false,touched:false,value:0});"
            + "navigator.getGamepads=function(){return pad.connected?[pad,null,null,null]:[null,null,null,null];};"
            + "window.__dlpad=function(b,a){var first=!pad.connected;pad.connected=true;"
            + "for(var i=0;i<17;i++){var v=b[i]||0;pad.buttons[i]={pressed:v>0.5,touched:v>0,value:v};}"
            + "pad.axes=a;pad.timestamp=performance.now();"
            + "if(first){var e=new Event('gamepadconnected');e.gamepad=pad;window.dispatchEvent(e);}};"
            + "})();";

    private void injectPad() {
        web.evaluateJavascript(PAD_JS, null);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
    }
}

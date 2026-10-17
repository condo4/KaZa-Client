package org.kaza;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Onboarding of the runtime permissions KaZa needs to stay reliable in background.
 *
 * Steps (only the missing ones are shown, each explained before the system dialog):
 *   1. notifications          (API 33+)
 *   2. location while in use  (API 23+)
 *   3. location all the time  (API 29+, opens the system settings page on API 30+)
 *   4. battery optimisation exemption
 *
 * Every step is proposed once automatically; afterwards it is only proposed again when
 * the activity is started with force=true (tap on the service notification, settings screen).
 * The activity runs in the main application process and notifies the service when it ends.
 */
public class PermissionActivity extends Activity
{
    private static final String TAG = "PermissionActivity";
    private static final String PREFS_NAME = "KaZaPermissionPrefs";
    private static final String EXTRA_FORCE = "force";
    private static final int REQ_NOTIFICATIONS = 1;
    private static final int REQ_LOCATION = 2;
    private static final int REQ_BACKGROUND_LOCATION = 3;
    private static final int REQ_BATTERY = 4;

    private enum Step { NOTIFICATIONS, LOCATION, BACKGROUND_LOCATION, BATTERY }

    private boolean force = false;
    private Step current = null;
    private TextView titleView;
    private TextView messageView;
    private Button actionButton;
    private Button laterButton;

    // ------------------------------------------------------------------ static helpers

    /**
     * Start the onboarding if at least one step is missing. Callable from any thread/context.
     * @param force also propose the steps already proposed (and refused) in the past
     */
    public static void startIfNeeded(Context context, boolean force)
    {
        List<Step> missing = missingSteps(context, force);
        if (missing.isEmpty()) {
            return;
        }
        Intent intent = new Intent(context, PermissionActivity.class);
        intent.putExtra(EXTRA_FORCE, force);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Cannot start PermissionActivity: " + e.getMessage());
        }
    }

    /** @return true when every permission needed for a reliable service is granted */
    public static boolean allGranted(Context context)
    {
        for (Step s : Step.values()) {
            if (isApplicable(s) && !isGranted(context, s)) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasLocationPermission(Context context)
    {
        return isGranted(context, Step.LOCATION);
    }

    public static boolean isIgnoringBatteryOptimizations(Context context)
    {
        return isGranted(context, Step.BATTERY);
    }

    private static boolean isApplicable(Step s)
    {
        switch (s) {
            case NOTIFICATIONS:        return Build.VERSION.SDK_INT >= 33;
            case LOCATION:             return Build.VERSION.SDK_INT >= 23;
            case BACKGROUND_LOCATION:  return Build.VERSION.SDK_INT >= 29;
            case BATTERY:              return Build.VERSION.SDK_INT >= 23;
        }
        return false;
    }

    private static boolean granted(Context c, String permission)
    {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isGranted(Context c, Step s)
    {
        if (!isApplicable(s)) {
            return true;
        }
        switch (s) {
            case NOTIFICATIONS:
                return granted(c, Manifest.permission.POST_NOTIFICATIONS);
            case LOCATION:
                return granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
                    || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION);
            case BACKGROUND_LOCATION:
                return granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            case BATTERY: {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
            }
        }
        return true;
    }

    private static SharedPreferences prefs(Context c)
    {
        return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean alreadyAsked(Context c, Step s)
    {
        return prefs(c).getBoolean("asked_" + s.name(), false);
    }

    private static void markAsked(Context c, Step s)
    {
        prefs(c).edit().putBoolean("asked_" + s.name(), true).apply();
    }

    private static List<Step> missingSteps(Context c, boolean force)
    {
        List<Step> out = new ArrayList<>();
        for (Step s : Step.values()) {
            if (!isApplicable(s) || isGranted(c, s)) {
                continue;
            }
            // background location can only be asked once the foreground one is granted
            if (s == Step.BACKGROUND_LOCATION && !isGranted(c, Step.LOCATION)) {
                continue;
            }
            if (force || !alreadyAsked(c, s)) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ activity

    private static boolean french()
    {
        return Locale.getDefault().getLanguage().equals("fr");
    }

    private static String tr(String fr, String en)
    {
        return french() ? fr : en;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        force = getIntent().getBooleanExtra(EXTRA_FORCE, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        titleView = new TextView(this);
        titleView.setTextSize(20);
        titleView.setPadding(0, 0, 0, pad / 2);
        root.addView(titleView);

        messageView = new TextView(this);
        messageView.setTextSize(15);
        messageView.setPadding(0, 0, 0, pad);
        root.addView(messageView);

        actionButton = new Button(this);
        root.addView(actionButton);

        laterButton = new Button(this);
        laterButton.setText(tr("Plus tard", "Later"));
        laterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null) {
                    markAsked(PermissionActivity.this, current);
                    shownInThisSession.add(current);
                }
                showNextStep();
            }
        });
        root.addView(laterButton);

        setContentView(root);
        setFinishOnTouchOutside(false);
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // Also covers the return from the system settings pages
        showNextStep();
    }

    private void showNextStep()
    {
        List<Step> missing = missingSteps(this, force);
        // Steps skipped with "Later" are marked as asked; with force they are shown again, so
        // only consider a step once per activity instance.
        missing.removeAll(shownInThisSession);
        if (missing.isEmpty()) {
            finishOnboarding();
            return;
        }
        current = missing.get(0);
        render(current);
    }

    private final List<Step> shownInThisSession = new ArrayList<>();

    private void render(final Step step)
    {
        switch (step) {
            case NOTIFICATIONS:
                titleView.setText(tr("Notifications", "Notifications"));
                messageView.setText(tr(
                    "KaZa a besoin d'afficher les alertes de votre maison (alarmes, portes, températures…).",
                    "KaZa needs to show the alerts coming from your home (alarms, doors, temperatures…)."));
                break;
            case LOCATION:
                titleView.setText(tr("Position", "Location"));
                messageView.setText(tr(
                    "KaZa peut transmettre votre position à votre serveur pour détecter votre présence.",
                    "KaZa can send your position to your own server to detect your presence."));
                break;
            case BACKGROUND_LOCATION:
                titleView.setText(tr("Position en arrière-plan", "Background location"));
                messageView.setText(tr(
                    "Pour suivre votre arrivée à la maison application fermée, choisissez « Toujours autoriser » dans l'écran suivant.",
                    "To detect your arrival at home while the app is closed, choose \"Allow all the time\" on the next screen."));
                break;
            case BATTERY:
                titleView.setText(tr("Fonctionnement en arrière-plan", "Background operation"));
                messageView.setText(tr(
                    "Pour recevoir les notifications sans retard, autorisez KaZa à ne pas être limité par l'économie de batterie.",
                    "To receive notifications without delay, allow KaZa to ignore battery optimisations."));
                break;
        }
        actionButton.setText(tr("Continuer", "Continue"));
        actionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                request(step);
            }
        });
    }

    private void request(Step step)
    {
        markAsked(this, step);
        shownInThisSession.add(step);
        try {
            switch (step) {
                case NOTIFICATIONS:
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                    break;
                case LOCATION:
                    requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                    break;
                case BACKGROUND_LOCATION:
                    requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                       REQ_BACKGROUND_LOCATION);
                    break;
                case BATTERY: {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                          Uri.parse("package:" + getPackageName()));
                    try {
                        startActivityForResult(i, REQ_BATTERY);
                    } catch (Exception e) {
                        // Some OEM ROMs do not implement the direct dialog
                        startActivityForResult(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                               REQ_BATTERY);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Request failed for " + step + ": " + e.getMessage());
            showNextStep();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.i(TAG, "Permission result for request " + requestCode);
        // onResume() runs right after and shows the next missing step
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void finishOnboarding()
    {
        // Let the service re-evaluate its foreground type and restart the position tracking
        try {
            Intent i = new Intent(this, NotificationService.class);
            i.putExtra("ACTION", "PERMISSIONS_CHANGED");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot notify the service: " + e.getMessage());
        }
        finish();
    }
}

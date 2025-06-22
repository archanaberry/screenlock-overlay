package com.archanaberry.github.screenlockoverlay;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_OVERLAY = 1001;
    private static final int REQUEST_CODE_BATTERY = 1002;
    private static final int REQUEST_CODE_NOTIFICATION = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);

        // Izin notifikasi untuk Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_NOTIFICATION);
                return;
            }
        }
        checkOverlayPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_NOTIFICATION) {
            // Setelah notif ditangani, lanjut cek overlay
            checkOverlayPermission();
        }
    }

    private void checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog();
            } else {
                requestBatteryAndStart();
            }
        } else {
            startOverlayService();
        }
    }

    private void showOverlayPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Izin Overlay Dibutuhkan")
            .setMessage("Archana Berry memerlukan izin overlay. Tekan Oke untuk menuju pengaturan.")
            .setPositiveButton("Oke", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_OVERLAY);
            })
            .setNegativeButton("Batal", (d,w)-> finish())
            .setCancelable(false)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                // Setelah overlay diizinkan, cek izin notifikasi
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && !nm.areNotificationsEnabled()) {
                    showNotificationSettingsDialog();
                } else {
                    requestBatteryAndStart();
                }
            }
            finish();
        } else if (requestCode == REQUEST_CODE_BATTERY) {
            startOverlayService();
            finish();
        }
    }

    private void showNotificationSettingsDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Izin Notifikasi Diperlukan")
            .setMessage("Agar overlay berfungsi penuh, izinkan notifikasi untuk aplikasi ini. Tekan Oke untuk membuka pengaturan notifikasi.")
            .setPositiveButton("Oke", (dialog, which) -> {
                Intent intent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                } else {
                    intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:" + getPackageName()));
                }
                startActivity(intent);
            })
            .setNegativeButton("Lewati", (d, w) -> requestBatteryAndStart())
            .setCancelable(false)
            .show();
    }

    private void requestBatteryAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_BATTERY);
        } else {
            startOverlayService();
        }
    }

    private void startOverlayService() {
        Intent svc = new Intent(this, OverlayService.class);
        ContextCompat.startForegroundService(this, svc);
    }

    public static class OverlayService extends Service {
        private FrameLayout overlayView;
        private WindowManager wm;
        private boolean isShown = false;
        private BroadcastReceiver receiver;
        private static final String CHANNEL_ID = "overlay_channel";
        private static final String ACTION_TOGGLE = "com.archanaberry.ACTION_TOGGLE";
        private static final String ACTION_EXIT = "com.archanaberry.ACTION_EXIT";
        private static final int NOTIF_ID = 1;

        @Override
        public void onCreate() {
            super.onCreate();
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            createNotificationChannel();
            registerReceiver();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForeground(NOTIF_ID, buildNotification());
            if (!isShown) showOverlay();
            return START_STICKY;
        }

        private void registerReceiver() {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_TOGGLE.equals(action)) {
                        if (isShown) hideOverlay(); else showOverlay();
                        updateNotification();
                    } else if (ACTION_EXIT.equals(action)) {
                        stopSelf();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
            filter.addAction(ACTION_EXIT);
            registerReceiver(receiver, filter);
        }

        private Notification buildNotification() {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

            PendingIntent piToggle = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_TOGGLE), flags);
            PendingIntent piExit = PendingIntent.getBroadcast(this, 1,
                new Intent(ACTION_EXIT), flags);

            String title = "Lapisan overlay anti sentuh " + (isShown ? "aktif" : "nonaktif");
            String text = "Archana Berry: Mengunci layar lapisan bawah dengan overlay untuk " +
                (isShown ? "membuka" : "mengunci") + "...";

            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .addAction(0, isShown ? "Sembunyikan" : "Tampilkan", piToggle)
                .addAction(0, "Exit", piExit)
                .build();
        }

        private void updateNotification() {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel nc = new NotificationChannel(CHANNEL_ID,
                    "Overlay Service", NotificationManager.IMPORTANCE_LOW);
                nc.setSound(null, null);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(nc);
            }
        }

        private void showOverlay() {
            if (isShown) return;
            overlayView = new FrameLayout(this);
            overlayView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            );
            overlayView.setBackgroundColor(0x33000000);
            wm.addView(overlayView, params);
            isShown = true;
        }

        private void hideOverlay() {
            if (!isShown) return;
            wm.removeView(overlayView);
            isShown = false;
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (receiver != null) unregisterReceiver(receiver);
            if (isShown) wm.removeView(overlayView);
        }

        @Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return new Binder();
        }
    }
}

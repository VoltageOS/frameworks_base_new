package com.android.internal.gmscompat;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static android.app.compat.gms.GmsCompat.appContext;

// Unprivileged app that is performing a screen capture is required by the OS to run
// a foreground service with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
public class GmcMediaProjectionService extends Service {
    private static final String TAG = "GmcMediaProjService";

    private static final ArrayMap<String, CountDownLatch> latches = new ArrayMap<>();

    public static void start() {
        if (Thread.currentThread() == appContext().getMainLooper().getThread()) {
            // otherwise, latch.await() below would deadlock
            throw new IllegalStateException("should never be called from the main thread");
        }

        String id = UUID.randomUUID().toString();
        var latch = new CountDownLatch(1);
        synchronized (latches) {
            latches.put(id, latch);
        }
        Intent intent = intent().setIdentifier(id);
        Log.d(TAG, "start " + id);
        appContext().startForegroundService(intent);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void stop() {
        Log.d(TAG, "stop");
        appContext().stopService(intent());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand " + intent);

        Notification n;
        try {
            IGms2Gca iGms2Gca = GmsCompatApp.iGms2Gca();
            if (iGms2Gca != null) {
                // notification icon and text are stored in GmsCompat app
                n = iGms2Gca.getMediaProjectionNotification();
            } else {
                // GmsCompat app not available, create a basic notification
                Log.w(TAG, "GmsCompat app not available, using fallback notification");
                n = createFallbackNotification();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get notification from GmsCompat app, using fallback", e);
            n = createFallbackNotification();
        }

        startForeground(GmsCoreConst.NOTIF_ID_MEDIA_PROJECTION_SERVICE, n);

        String id = intent.getIdentifier();
        CountDownLatch latch;

        synchronized (latches) {
            latch = latches.remove(id);
        }
        if (latch != null) {
            latch.countDown();
        } else {
            // can happen if our process died after startForegroundService() but before
            // onStartCommand(), OS recreates process in that case
            Log.e(TAG, "missing latch");
        }

        return START_NOT_STICKY;
    }

    private Notification createFallbackNotification() {
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle("Screen sharing active")
                .setContentText("Your screen is being shared")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "media_projection",
                    "Screen Sharing",
                    android.app.NotificationManager.IMPORTANCE_LOW
            );
            android.app.NotificationManager notificationManager = 
                    getSystemService(android.app.NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            builder.setChannelId("media_projection");
        }

        return builder.build();
    }

    private static Intent intent() {
        return new Intent(appContext(), GmcMediaProjectionService.class);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

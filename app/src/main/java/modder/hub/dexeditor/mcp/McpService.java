package modder.hub.dexeditor.mcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

public class McpService extends Service {
    private static final String CHANNEL_ID = "mcp_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String EXTRA_PORT = "port";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = 8788;
        if (intent != null) {
            port = intent.getIntExtra(EXTRA_PORT, 8788);
        }

        // Create notification to make it a foreground service
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DEX MCP Server")
                .setContentText("Running on port " + port)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        try {
            if (!McpServer.isRunning()) {
                McpServer.start(port);
            }
        } catch (IOException e) {
            e.printStackTrace();
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        McpServer.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "MCP Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}

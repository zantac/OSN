package com.waellotfy.PersistentSubtitle;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.runtime.*;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@DesignerComponent(version = 48,
    description = "Subtitle player with full control and settings panel.",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "aiwebres/icon.png")
@UsesPermissions(permissionNames = "android.permission.SYSTEM_ALERT_WINDOW, android.permission.READ_EXTERNAL_STORAGE, android.permission.INTERNET, android.permission.FOREGROUND_SERVICE")
@SimpleObject(external = true)
@SuppressWarnings("deprecation")
public class PersistentSubtitle extends AndroidNonvisibleComponent implements ActivityResultListener {

    private static final String TAG = "PersistentSubtitle";
    private static final int OVERLAY_REQUEST_CODE = 1234;
    private final Activity activity;
    private List<SubtitleEntry> preparedSubtitleData;

    public PersistentSubtitle(ComponentContainer container) {
        super(container.$form());
        this.activity = container.$context();
    }
    
    public static class SubtitleEntry implements Serializable {
        final long startTimeMillis, endTimeMillis;
        final String text;
        SubtitleEntry(long start, long end, String text) {
            this.startTimeMillis = start;
            this.endTimeMillis = end;
            this.text = text;
        }
    }

    //region --- Main Extension Blocks ---
    @SimpleEvent(description = "Fires after a subtitle file has been loaded. Success will be true if successful.")
    public void SubtitleLoaded(boolean success, String source) {
        EventDispatcher.dispatchEvent(this, "SubtitleLoaded", success, source);
    }

    @SimpleEvent(description = "Fires when the subtitle player is stopped and the view is removed.")
    public void PlaybackStopped() {
        EventDispatcher.dispatchEvent(this, "PlaybackStopped");
    }

    @SimpleFunction(description = "Asynchronously loads an SRT file from a Content URI (from a file picker), a direct file path, or an asset name. For encoding, use 'auto', 'UTF-8', or 'windows-1256'.")
    public void LoadSubtitleFromFile(final String uriOrPath, final String encoding) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream inputStream;
                    if (uriOrPath.startsWith("content://")) {
                        Uri contentUri = Uri.parse(uriOrPath);
                        inputStream = activity.getContentResolver().openInputStream(contentUri);
                    } else if (uriOrPath.startsWith("/")) {
                        inputStream = new FileInputStream(new File(uriOrPath));
                    } else {
                        inputStream = form.openAsset(uriOrPath);
                    }
                    
                    if (inputStream == null) {
                        throw new IOException("Could not open input stream for: " + uriOrPath);
                    }

                    parseSrtStream(inputStream, encoding);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            PersistentSubtitle.this.SubtitleLoaded(true, uriOrPath);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Error loading from File/URI: " + e.getMessage());
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            PersistentSubtitle.this.SubtitleLoaded(false, uriOrPath);
                        }
                    });
                }
            }
        }).start();
    }

    @SimpleFunction(description = "Asynchronously loads an SRT file from a URL. For encoding, use 'auto', 'UTF-8', or 'windows-1256'.")
    public void LoadSubtitleFromUrl(final String url, final String encoding) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream inputStream = new URL(url).openStream();
                    parseSrtStream(inputStream, encoding);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            PersistentSubtitle.this.SubtitleLoaded(true, url);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Error loading from URL: " + e.getMessage());
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            PersistentSubtitle.this.SubtitleLoaded(false, url);
                        }
                    });
                }
            }
        }).start();
    }
    
    @SimpleFunction(description = "Starts the background service to display subtitles. Call this after SubtitleLoaded fires with success=true.")
    public void StartPlayback() {
        if (!IsDrawOverlayPermissionGranted()) {
            RequestDrawOverlayPermission();
            Log.e(TAG, "Permission not granted. The user must grant it first.");
            return;
        }
        if (preparedSubtitleData == null || preparedSubtitleData.isEmpty()) {
            Log.e(TAG, "StartPlayback called but no subtitles are loaded or prepared.");
            return;
        }
        Intent intent = new Intent(activity, SubtitleService.class);
        intent.putExtra("SUBTITLE_DATA", (ArrayList<SubtitleEntry>) preparedSubtitleData);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(intent);
        } else {
            activity.startService(intent);
        }
    }

    @SimpleFunction(description = "Stops the background service and removes the floating view.")
    public void Stop() {
        Intent intent = new Intent(activity, SubtitleService.class);
        activity.stopService(intent);
        PlaybackStopped();
    }
    
    @SimpleFunction(description = "Sends the app to the background and shows the device's home screen. The subtitle widget will remain.")
    public void GoHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(home);
    }
    
    @SimpleFunction(description = "Checks if the 'Draw over other apps' permission has been granted.")
    public boolean IsDrawOverlayPermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(activity);
        }
        return true;
    }

    @SimpleFunction(description = "Opens the system settings screen for the user to grant the 'Draw over other apps' permission.")
    public void RequestDrawOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.getPackageName()));
                form.registerForActivityResult(this);
                activity.startActivityForResult(intent, OVERLAY_REQUEST_CODE);
            } catch (Exception e) {
                Log.e(TAG, "Could not open overlay permission screen", e);
                PermissionRequestFailed("This device does not support the standard overlay permission screen. You may need to grant it manually from settings. Error: " + e.getMessage());
            }
        } else {
            DrawOverlayPermissionResult(true);
        }
    }

    @SimpleEvent(description = "Fires after the user returns from the permission settings screen.")
    public void DrawOverlayPermissionResult(boolean granted) {
        EventDispatcher.dispatchEvent(this, "DrawOverlayPermissionResult", granted);
    }
    
    @SimpleEvent(description = "Fires if the system could not open the permission screen.")
    public void PermissionRequestFailed(String reason) {
        EventDispatcher.dispatchEvent(this, "PermissionRequestFailed", reason);
    }

    @Override
    public void resultReturned(int requestCode, int resultCode, Intent data) {
        if (requestCode == OVERLAY_REQUEST_CODE) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                   DrawOverlayPermissionResult(IsDrawOverlayPermissionGranted());
                }
            }, 500);
        }
    }
    //endregion

    //region SRT Parsing Logic (Internal)
    private void parseSrtStream(InputStream inputStream, String encoding) throws Exception {
        List<SubtitleEntry> parsedData;
        if ("auto".equalsIgnoreCase(encoding)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) > -1 ) {
                baos.write(buffer, 0, len);
            }
            baos.flush();
            byte[] streamBytes = baos.toByteArray();
            try {
                parsedData = doParse(new ByteArrayInputStream(streamBytes), StandardCharsets.UTF_8);
                this.preparedSubtitleData = parsedData;
                return;
            } catch (Exception e) {
                Log.w(TAG, "UTF-8 parsing failed, trying windows-1256.", e);
            }
            try {
                parsedData = doParse(new ByteArrayInputStream(streamBytes), Charset.forName("windows-1256"));
                this.preparedSubtitleData = parsedData;
            } catch (Exception e2) {
                throw new Exception("Failed to parse subtitle with both UTF-8 and windows-1256.", e2);
            }
        } else {
            parsedData = doParse(inputStream, Charset.forName(encoding));
            this.preparedSubtitleData = parsedData;
        }
    }

    private List<SubtitleEntry> doParse(InputStream inputStream, Charset charset) throws IOException {
        ArrayList<SubtitleEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset))) {
            String line;
            long startTime = 0, endTime = 0;
            StringBuilder textBuilder = new StringBuilder();
            boolean isTimeLine = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("-->")) {
                    if (textBuilder.length() > 0) {
                        entries.add(new SubtitleEntry(startTime, endTime, textBuilder.toString().trim()));
                        textBuilder.setLength(0);
                    }
                    String[] timeParts = line.split("-->");
                    try {
                        startTime = parseSrtTime(timeParts[0].trim());
                        endTime = parseSrtTime(timeParts[1].trim());
                        isTimeLine = true;
                    } catch (Exception e) { isTimeLine = false; }
                } else if (isTimeLine && !line.trim().isEmpty() && !line.trim().matches("\\d+")) {
                    if (textBuilder.length() > 0) textBuilder.append("\n");
                    textBuilder.append(line);
                } else if (line.trim().isEmpty()) {
                    if (textBuilder.length() > 0) {
                        entries.add(new SubtitleEntry(startTime, endTime, textBuilder.toString().trim()));
                        textBuilder.setLength(0);
                        isTimeLine = false;
                    }
                }
            }
            if (textBuilder.length() > 0) {
                entries.add(new SubtitleEntry(startTime, endTime, textBuilder.toString().trim()));
            }
        }
        if (entries.isEmpty()) throw new IOException("No valid subtitle entries found.");
        Collections.sort(entries, new Comparator<SubtitleEntry>() {
            @Override
            public int compare(SubtitleEntry o1, SubtitleEntry o2) {
                return Long.compare(o1.startTimeMillis, o2.startTimeMillis);
            }
        });
        return entries;
    }

    private long parseSrtTime(String time) {
        String cleanTime = time.split(" ")[0];
        String[] parts = cleanTime.replace(',', '.').split("\\.");
        String[] hms = parts[0].split(":");
        long ms = Long.parseLong(parts[1]);
        return (Long.parseLong(hms[0]) * 3600 + Long.parseLong(hms[1]) * 60 + Long.parseLong(hms[2])) * 1000 + ms;
    }
    //endregion
    
    public static class SubtitleService extends Service {
        private static final String PREFS_NAME = "SubtitleSettings";
        private static final String CHANNEL_ID = "SubtitleServiceChannel";
        private static final int NOTIFICATION_ID = 1;

        private WindowManager windowManager;
        private RelativeLayout floatingRootView;
        private LinearLayout settingsPanelView, floatingControlsLayout;
        private OutlineTextView floatingTextView;
        private TextView timeLabel;
        private SeekBar timeSlider;
        private ListView syncListView;
        private boolean isSettingsShowing = false, isSyncListShowing = false, isPaused = true, isDraggingSlider = false;
        private Handler timerHandler, controlsHideHandler;
        private long startTime, pauseTime = 0, totalDuration = 0;
        private int currentIndex = -1;
		private int currentFontIndex = 0;
        private WindowManager.LayoutParams rootParams;
        private float currentTextColorHue = -1; // -1 signifies WHITE
        private String currentFont = "Default";
        private List<SubtitleEntry> subtitleData;

        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public void onCreate() {
            super.onCreate();
            timerHandler = new Handler();
            controlsHideHandler = new Handler();
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent != null && intent.hasExtra("SUBTITLE_DATA")) {
                this.subtitleData = (List<SubtitleEntry>) intent.getSerializableExtra("SUBTITLE_DATA");
            }
            if (subtitleData != null && !subtitleData.isEmpty() && floatingRootView == null) {
                startAsForegroundService();
                createFloatingWidget();
                startTimingLoop();
            } else if (subtitleData == null) {
                Log.e(TAG, "Service started without subtitle data, stopping.");
                stopSelf();
            }
            return START_STICKY;
        }

        private void startAsForegroundService() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Subtitle Player Service", NotificationManager.IMPORTANCE_LOW);
                getSystemService(NotificationManager.class).createNotificationChannel(channel);
            }
            Intent notificationIntent = new Intent(this, getApplication().getClass());
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Subtitle Player")
                    .setContentText("Widget is active.")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (floatingRootView != null && floatingRootView.isAttachedToWindow()) { windowManager.removeView(floatingRootView); }
            if (settingsPanelView != null && settingsPanelView.isAttachedToWindow()) { windowManager.removeView(settingsPanelView); }
            if (syncListView != null && syncListView.isAttachedToWindow()) { windowManager.removeView(syncListView); }
            timerHandler.removeCallbacksAndMessages(null);
            controlsHideHandler.removeCallbacksAndMessages(null);
            floatingRootView = null;
            stopForeground(true);
        }
        
        private void createFloatingWidget() {
            int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            rootParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            rootParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

            floatingRootView = new RelativeLayout(this);
            LinearLayout mainContainer = new LinearLayout(this);
            mainContainer.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mainContainer.setOrientation(LinearLayout.VERTICAL);
            mainContainer.setGravity(Gravity.CENTER_HORIZONTAL);
            
            floatingTextView = new OutlineTextView(this); 
            floatingTextView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            floatingTextView.setId(View.generateViewId());
            floatingTextView.setPadding(15,15,15,15);
            floatingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            floatingTextView.setTextColor(Color.WHITE);
            floatingTextView.setGravity(Gravity.CENTER);
            floatingTextView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showControls(); }});
            mainContainer.addView(floatingTextView);

            LinearLayout timeLayout = new LinearLayout(this);
            timeLayout.setPadding(20, 5, 20, 5);
            timeLayout.setOrientation(LinearLayout.HORIZONTAL);
            timeLayout.setGravity(Gravity.CENTER_VERTICAL);
            timeLayout.setVisibility(View.GONE);
            timeLabel = new TextView(this);
            timeLabel.setTextColor(Color.WHITE);
            timeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            timeSlider = new SeekBar(this);
            timeSlider.getThumb().mutate().setAlpha(255);
            timeSlider.getProgressDrawable().mutate().setAlpha(255);
            timeSlider.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            timeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if(fromUser) seekTo(progress); }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { isDraggingSlider = true; }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    isDraggingSlider = false;
                    if (!isPaused) {
                        timerHandler.post(subtitleUpdater);
                    }
                }
            });
            timeLayout.addView(timeLabel);
            timeLayout.addView(timeSlider);
            mainContainer.addView(timeLayout);

            floatingControlsLayout = new LinearLayout(this);
            floatingControlsLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            floatingControlsLayout.setVisibility(View.GONE);
            floatingControlsLayout.addView(createControlButton("-"));
            floatingControlsLayout.addView(createControlButton("<<"));
            floatingControlsLayout.addView(createControlButton("▶"));
            floatingControlsLayout.addView(createControlButton("SYNC"));
            floatingControlsLayout.addView(createControlButton(">>"));
            floatingControlsLayout.addView(createControlButton("+"));
            floatingControlsLayout.addView(createControlButton("⚙"));
            floatingControlsLayout.addView(createControlButton("⏻"));
			floatingControlsLayout.addView(createControlButton("SP"));
            mainContainer.addView(floatingControlsLayout);

            floatingRootView.addView(mainContainer);
            windowManager.addView(floatingRootView, rootParams);
            
            loadAndApplySettings();
        }

        private final Runnable subtitleUpdater = new Runnable() {
            @Override public void run() {
                if (isPaused || isDraggingSlider || subtitleData == null) {
                    return;
                }
                long elapsedTime = System.currentTimeMillis() - startTime;
                String currentText = "";
                int newIndex = -1;
				
				 for (int i = 0; i < subtitleData.size(); i++) {
                    SubtitleEntry entry = subtitleData.get(i);
                    if (elapsedTime >= entry.startTimeMillis && elapsedTime <= entry.endTimeMillis) {
                        currentText = entry.text;
                        newIndex = i;
                        break;
                    }
                }
                
                 if (currentIndex != newIndex) {
                    floatingTextView.setText(currentText);
                    currentIndex = newIndex;
                }

                timeLabel.setText(formatDuration(elapsedTime) + " / " + formatDuration(totalDuration));
                if (!isDraggingSlider) { timeSlider.setProgress((int)elapsedTime); }
                timerHandler.postDelayed(this, 100);
            }
        };

        private void updateTextForSeek(long elapsedTime) {
             if (subtitleData == null) return;
             String currentText = "";
             int newIndex = -1;
             for (int i = 0; i < subtitleData.size(); i++) {
                 SubtitleEntry entry = subtitleData.get(i);
                 if (elapsedTime >= entry.startTimeMillis && elapsedTime <= entry.endTimeMillis) {
                     currentText = entry.text;
                     newIndex = i;
                     break;
                 }
             }
             if (currentIndex != newIndex) {
                 floatingTextView.setText(currentText);
                 currentIndex = newIndex;
             }
             timeLabel.setText(formatDuration(elapsedTime) + " / " + formatDuration(totalDuration));
             if (!isDraggingSlider) { timeSlider.setProgress((int)elapsedTime); }
        }

        private void startTimingLoop() {
            if (subtitleData == null || subtitleData.isEmpty()) { stopSelf(); return; }
            totalDuration = subtitleData.get(subtitleData.size() - 1).endTimeMillis;
            timeSlider.setMax((int)totalDuration);
            startTime = System.currentTimeMillis();
            currentIndex = -1;
            pauseTime = 0;
            isPaused = false;

            ((Button)floatingControlsLayout.getChildAt(2)).setText("❚❚");
            timerHandler.post(subtitleUpdater);
            showControls();
        }
        
        private void pausePlayback() {
            if (!isPaused) {
                pauseTime = System.currentTimeMillis();
                isPaused = true;
                timerHandler.removeCallbacks(subtitleUpdater);
                ((Button)floatingControlsLayout.getChildAt(2)).setText("▶");
            }
        }
        
        private void resumePlayback() {
            if (isPaused) {
                if (pauseTime > 0) {
                    startTime += System.currentTimeMillis() - pauseTime;
                }
                pauseTime = 0;
                isPaused = false;
                timerHandler.post(subtitleUpdater);
                ((Button)floatingControlsLayout.getChildAt(2)).setText("❚❚");
            }
        }
        
		
		
		
        private void showControls() {
            floatingControlsLayout.setVisibility(View.VISIBLE);
            ((View)timeSlider.getParent()).setVisibility(View.VISIBLE);
            rootParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(floatingRootView, rootParams);
            controlsHideHandler.removeCallbacksAndMessages(null);
            controlsHideHandler.postDelayed(new Runnable() { @Override public void run() { hideControls(); } }, 5000);
        }

        private void hideControls() {
            if(isSyncListShowing || isSettingsShowing) return;
            floatingControlsLayout.setVisibility(View.GONE);
            ((View)timeSlider.getParent()).setVisibility(View.GONE);
            rootParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(floatingRootView, rootParams);
        }
        
        private Button createControlButton(String text) {
            Button button = new Button(this);
            button.setText(text);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            button.setPadding(10,10,10,10);
            button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { handleButtonClick((Button) v); } });
            return button;
        }

        private void handleButtonClick(Button button) {
            showControls();
            String buttonText = button.getText().toString();
            if ("▶".equals(buttonText) || "❚❚".equals(buttonText)) { if (isPaused) resumePlayback(); else pausePlayback(); }
            else if (">>".equals(buttonText)) { jumpToCue(true); }
            else if ("<<".equals(buttonText)) { jumpToCue(false); }
            else if ("+".equals(buttonText)) { nudge(500); }
            else if ("-".equals(buttonText)) { nudge(-500); }
            else if ("⏻".equals(buttonText)) { stopSelf(); }
            else if ("SYNC".equals(buttonText)) { toggleSyncList(); }
            else if ("⚙".equals(buttonText)) { toggleSettingsPanel(); }
            else if ("SP".equals(buttonText)) {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage("com.waellotfy.subtitleplayer");
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launchIntent);
                    stopSelf();
                } else {
                    Log.e(TAG, "Could not find app with package: com.waellotfy.subtitleplayer");
                }
            }
        }

        private void jumpToCue(boolean isNext) {
            if (subtitleData == null || subtitleData.isEmpty()) {
                return;
            }
            int targetIndex;
            if (isNext) {
                targetIndex = (currentIndex < 0) ? 1 : currentIndex + 1;
            } else {
                targetIndex = (currentIndex < 0) ? 0 : currentIndex - 1;
            }
            if (targetIndex >= subtitleData.size()) {
                targetIndex = subtitleData.size() - 1;
            }
            if (targetIndex < 0) {
                targetIndex = 0;
            }
            seekTo(subtitleData.get(targetIndex).startTimeMillis);
        }
        
        private void seekTo(long timeInMillis) {
            long newStartTime = System.currentTimeMillis() - timeInMillis;
            startTime = newStartTime;
            if (isPaused) {
                pauseTime = newStartTime + timeInMillis;
            }
            updateTextForSeek(timeInMillis);
        }
        
        private void nudge(long ms) {
            startTime -= ms;
            if (isPaused) {
                pauseTime -= ms;
            }
        }
        
        private void toggleSettingsPanel() { if(isSettingsShowing) hideSettingsPanel(); else showSettingsPanel(); }
        
        private void showSettingsPanel() {
            if(isSettingsShowing) return; hideSyncList(); isSettingsShowing = true;
            LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(30, 20, 30, 20);
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            int screenHeight = metrics.heightPixels;
            int widgetHeight = floatingRootView != null ? floatingRootView.getHeight() : 200;
            int panelHeight = screenHeight - widgetHeight;

            content.addView(createSettingsLabel("Text Size"));
            SeekBar textSizeSlider = createSettingsSlider(10, 50, (int) (floatingTextView.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            textSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if(fromUser) floatingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10, progress)); } @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {} });
            content.addView(textSizeSlider);

            content.addView(createSettingsLabel("Text Color"));
            // CHANGED: The slider value now corresponds to hue. 0 is a special case for White.
            int sliderPos = currentTextColorHue < 0 ? 0 : (int)currentTextColorHue;
            SeekBar textColorSlider = createSettingsSlider(0, 360, sliderPos);
            textColorSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if(fromUser) {
                        if (progress == 0) {
                            currentTextColorHue = -1; // Special value for white
                            floatingTextView.setTextColor(Color.WHITE);
                        } else {
                            currentTextColorHue = (float)progress;
                            floatingTextView.setTextColor(Color.HSVToColor(new float[]{ currentTextColorHue, 1f, 1f }));
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            content.addView(textColorSlider);
            
            content.addView(createSettingsLabel("Background Transparency"));
            int currentAlpha = (floatingTextView.getBackground() instanceof ColorDrawable) ? Color.alpha(((ColorDrawable) floatingTextView.getBackground()).getColor()) : 0;
            SeekBar bgSlider = createSettingsSlider(0, 255, currentAlpha);
            bgSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if(fromUser) floatingTextView.setBackgroundColor(Color.argb(progress, 0, 0, 0)); } @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {} });
            content.addView(bgSlider);
            
            LinearLayout outlineLayout = new LinearLayout(this);
            outlineLayout.setOrientation(LinearLayout.HORIZONTAL);
            outlineLayout.setGravity(Gravity.CENTER_VERTICAL);
            
            final Switch outlineSwitch = new Switch(this);
            outlineSwitch.setText("Text Outline");
            outlineSwitch.setTextColor(Color.WHITE);
            outlineSwitch.setTextSize(20);
            outlineSwitch.setPadding(0, 20, 0, 20);
            outlineSwitch.setChecked(floatingTextView.isOutlineEnabled());
            
            final SeekBar outlineSlider = createSettingsSlider(1, 10, (int)floatingTextView.getOutlineWidth());
            outlineSlider.setEnabled(outlineSwitch.isChecked());
            
            outlineSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    floatingTextView.setOutlineEnabled(isChecked);
                    outlineSlider.setEnabled(isChecked);
                }
            });

            outlineSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        floatingTextView.setOutlineWidth(Math.max(1, progress));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            outlineLayout.addView(outlineSwitch);
            outlineLayout.addView(outlineSlider);
            content.addView(outlineLayout);

            content.addView(createSettingsLabel("View Vertical Position"));
            SeekBar yPosSlider = createSettingsSlider(-(getResources().getDisplayMetrics().heightPixels / 2), (getResources().getDisplayMetrics().heightPixels / 2), rootParams.y);
            yPosSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if(fromUser) { rootParams.y = progress; windowManager.updateViewLayout(floatingRootView, rootParams); } } @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {} });
            content.addView(yPosSlider);
            content.addView(createSettingsLabel("Text View Height (pixels)"));
            int currentHeight = floatingTextView.getLayoutParams().height > 0 ? floatingTextView.getLayoutParams().height : 150;
            SeekBar heightSlider = createSettingsSlider(50, 500, currentHeight);
            heightSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if(fromUser) { ViewGroup.LayoutParams params = floatingTextView.getLayoutParams(); params.height = progress; floatingTextView.setLayoutParams(params); } } @Override public void onStartTrackingTouch(SeekBar seekBar) {} @Override public void onStopTrackingTouch(SeekBar seekBar) {} });
            content.addView(heightSlider);
            
            content.addView(createSettingsLabel("Text Fonts"));
            LinearLayout fontSelectorLayout = new LinearLayout(this);
            fontSelectorLayout.setOrientation(LinearLayout.HORIZONTAL);
            fontSelectorLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);

            final ArrayList<String> fontList = new ArrayList<>();
            fontList.add("Default");
            try {
                AssetManager assetManager = getAssets();
                String[] assetFiles = assetManager.list("fonts");
                if (assetFiles != null) {
                    for (String filename : assetFiles) {
                        if (filename.toLowerCase().endsWith(".ttf") || filename.toLowerCase().endsWith(".otf")) {
                            fontList.add(filename);
                        }
                    }
                }
            } catch (IOException e) { Log.e(TAG, "Error listing asset fonts", e); }
            
            currentFontIndex = fontList.indexOf(currentFont);
            if (currentFontIndex == -1) currentFontIndex = 0;

            final TextView fontNameLabel = new TextView(this);
            fontNameLabel.setTextColor(Color.WHITE);
            fontNameLabel.setTextSize(20);
            fontNameLabel.setPadding(20, 0, 20, 0);
            fontNameLabel.setText(fontList.get(currentFontIndex));

            Button minusButton = new Button(this);
            minusButton.setText("-");
            minusButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentFontIndex--;
                    if (currentFontIndex < 0) { currentFontIndex = fontList.size() - 1; }
                    String newFont = fontList.get(currentFontIndex);
                    fontNameLabel.setText(newFont);
                    applyFont(newFont);
                }
            });

            Button plusButton = new Button(this);
            plusButton.setText("+");
            plusButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentFontIndex++;
                    if (currentFontIndex >= fontList.size()) { currentFontIndex = 0; }
                    String newFont = fontList.get(currentFontIndex);
                    fontNameLabel.setText(newFont);
                    applyFont(newFont);
                }
            });

            fontSelectorLayout.addView(minusButton);
            fontSelectorLayout.addView(fontNameLabel);
            fontSelectorLayout.addView(plusButton);
            content.addView(fontSelectorLayout);

            LinearLayout actionBar = new LinearLayout(this);
            actionBar.setOrientation(LinearLayout.HORIZONTAL);
            actionBar.setGravity(Gravity.CENTER);
            actionBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT){{topMargin = 20;}});
            Button resetButton = createControlButton("Reset");
            resetButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { resetSettings(); hideSettingsPanel(); }});
            Button saveButton = createControlButton("Save");
            saveButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveSettings(); hideSettingsPanel(); } });
            Button closeButton = createControlButton("Close");
            closeButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { hideSettingsPanel(); loadAndApplySettings(); } });
            actionBar.addView(resetButton); actionBar.addView(saveButton); actionBar.addView(closeButton);
            content.addView(actionBar);
            
            ScrollView mainScroller = new ScrollView(this);
            mainScroller.addView(content);
            settingsPanelView = new LinearLayout(this);
            settingsPanelView.addView(mainScroller);
            settingsPanelView.setBackgroundColor(Color.argb(230, 25, 25, 25));
            int panelFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams settingsParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, panelHeight, panelFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            settingsParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            windowManager.addView(settingsPanelView, settingsParams);
        }

        private void hideSettingsPanel() { if (settingsPanelView != null && settingsPanelView.isAttachedToWindow()) { windowManager.removeView(settingsPanelView); } settingsPanelView = null; isSettingsShowing = false; hideControls(); }
        
        private void toggleSyncList() { if (isSyncListShowing) hideSyncList(); else showSyncList(); }
        
        private void showSyncList() {
            if (isSyncListShowing || subtitleData == null || subtitleData.isEmpty()) return;
            hideSettingsPanel(); isSyncListShowing = true;
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            int screenHeight = metrics.heightPixels;
            int widgetHeight = floatingRootView != null ? floatingRootView.getHeight() : 200;
            int panelHeight = screenHeight - widgetHeight;
            
            syncListView = new ListView(this);
            syncListView.setBackgroundColor(Color.argb(220, 20, 20, 20));
            syncListView.setPadding(10, 10, 10, 10);
            SubtitleSyncAdapter adapter = new SubtitleSyncAdapter(this, subtitleData, currentIndex);
            syncListView.setAdapter(adapter);
            syncListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) { if (subtitleData != null && position < subtitleData.size()) { seekTo(subtitleData.get(position).startTimeMillis); } hideSyncList(); }
            });
            int panelFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams listParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, panelHeight, panelFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            listParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            windowManager.addView(syncListView, listParams);
            syncListView.post(new Runnable() { @Override public void run() { syncListView.setSelection(currentIndex); } });
            syncListView.requestFocus();
        }

        private void hideSyncList() { if (syncListView != null && syncListView.isAttachedToWindow()) { windowManager.removeView(syncListView); } syncListView = null; isSyncListShowing = false; hideControls(); }
        
        private void saveSettings() {
            if (floatingTextView == null || rootParams == null) return;
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            editor.putInt("textSize", (int) (floatingTextView.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            editor.putFloat("textColorHue", currentTextColorHue);
            int bgColor = (floatingTextView.getBackground() instanceof ColorDrawable) ? ((ColorDrawable) floatingTextView.getBackground()).getColor() : Color.TRANSPARENT;
            editor.putInt("bgColor", bgColor);
            editor.putBoolean("outlineEnabled", floatingTextView.isOutlineEnabled());
			editor.putInt("outlineWidth", (int)floatingTextView.getOutlineWidth());
            editor.putInt("yPosition", rootParams.y);
            editor.putInt("height", floatingTextView.getLayoutParams().height);
            editor.putString("fontName", currentFont);
            editor.apply();
        }
        
        private void loadAndApplySettings() {
            if (floatingTextView == null || rootParams == null) return;
            SharedPreferences settings = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            floatingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.getInt("textSize", 26));
            
            currentTextColorHue = settings.getFloat("textColorHue", -1f); // Default to -1 for White
            if (currentTextColorHue < 0) {
                floatingTextView.setTextColor(Color.WHITE);
            } else {
                floatingTextView.setTextColor(Color.HSVToColor(new float[]{ currentTextColorHue, 1f, 1f }));
            }
            
            floatingTextView.setBackgroundColor(settings.getInt("bgColor", Color.TRANSPARENT));
            boolean outlineEnabled = settings.getBoolean("outlineEnabled", true);
            int outlineWidth = settings.getInt("outlineWidth", 6);
            floatingTextView.setOutlineEnabled(outlineEnabled);
            floatingTextView.setOutlineWidth(outlineWidth);
            
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) floatingTextView.getLayoutParams();
            params.height = settings.getInt("height", ViewGroup.LayoutParams.WRAP_CONTENT);
            floatingTextView.setLayoutParams(params);
            applyFont(settings.getString("fontName", "Default"));
            rootParams.y = settings.getInt("yPosition", 0);
            windowManager.updateViewLayout(floatingRootView, rootParams);
        }
        
        private void resetSettings() {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
            floatingTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            floatingTextView.setTextColor(Color.WHITE);
            currentTextColorHue = -1;
            floatingTextView.setBackgroundColor(Color.TRANSPARENT);
            floatingTextView.setOutlineEnabled(true);
			floatingTextView.setOutlineWidth(6f);
            applyFont("Default");
            rootParams.y = 0;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) floatingTextView.getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            floatingTextView.setLayoutParams(params);
            windowManager.updateViewLayout(floatingRootView, rootParams);
        }
        
        private void applyFont(String fontName) {
            try {
                Typeface tf;
                if ("Default".equalsIgnoreCase(fontName)) {
                    tf = Typeface.DEFAULT;
                } else {
                    tf = Typeface.createFromAsset(getAssets(), "fonts/" + fontName);
                }
                floatingTextView.setTypeface(tf);
                currentFont = fontName;
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply font: " + fontName, e);
                floatingTextView.setTypeface(Typeface.DEFAULT);
                currentFont = "Default";
            }
        }
		
        private TextView createSettingsLabel(String text) { TextView label = new TextView(this); label.setText(text); label.setTextColor(Color.WHITE); label.setTextSize(20); label.setPadding(0, 20, 0, 5); return label; }
        
        private SeekBar createSettingsSlider(int min, int max, int current) {
            SeekBar slider = new SeekBar(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { slider.setMin(min); }
            slider.setMax(max);
            slider.setProgress(current);
            return slider;
        }
        
        private String formatDuration(long ms) { if (ms < 0) ms = 0; long s = ms / 1000; return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60); }
        
        private class SubtitleSyncAdapter extends ArrayAdapter<SubtitleEntry> {
            private final int highlightIndex;
            public SubtitleSyncAdapter(Context context, List<SubtitleEntry> entries, int highlightIndex) {
                super(context, 0, entries);
                this.highlightIndex = highlightIndex;
            }
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RelativeLayout itemLayout = new RelativeLayout(getContext());
                itemLayout.setPadding(5, 20, 5, 20);
                if (position == this.highlightIndex) {
                    itemLayout.setBackgroundColor(Color.argb(80, 70, 130, 180));
                } else {
                    itemLayout.setBackgroundColor(Color.TRANSPARENT);
                }
                TextView timeView = new TextView(getContext());
                TextView textView = new TextView(getContext());
                timeView.setTextColor(Color.LTGRAY);
                textView.setTextColor(Color.WHITE);
                timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                timeView.setId(View.generateViewId());
                RelativeLayout.LayoutParams timeParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                timeParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                timeParams.addRule(RelativeLayout.CENTER_VERTICAL);
                timeView.setLayoutParams(timeParams);
                RelativeLayout.LayoutParams textParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                textParams.addRule(RelativeLayout.LEFT_OF, timeView.getId());
                textParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                textParams.addRule(RelativeLayout.CENTER_VERTICAL);
                textParams.setMargins(10,0,25,0);
                textView.setLayoutParams(textParams);
                SubtitleEntry entry = getItem(position);
                if (entry != null) {
                    timeView.setText(formatDuration(entry.startTimeMillis));
                    textView.setText(entry.text.replace("\n", " "));
                }
                itemLayout.addView(textView);
                itemLayout.addView(timeView);
                return itemLayout;
            }
        }
        
        private class FontListAdapter extends ArrayAdapter<String> {
            public FontListAdapter(Context context, List<String> fonts) {
                super(context, android.R.layout.simple_list_item_1, fonts);
            }
            
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                return view;
            }
        }

        public static class OutlineTextView extends TextView {
            private boolean outlineEnabled = true;
            private int outlineColor = Color.BLACK;
            private float outlineWidth = 6f; // CHANGED to 6f

            public OutlineTextView(Context context) {
                super(context);
            }
            public OutlineTextView(Context context, AttributeSet attrs) {
                super(context, attrs);
            }
            public OutlineTextView(Context context, AttributeSet attrs, int defStyleAttr) {
                super(context, attrs, defStyleAttr);
            }

            public void setOutlineEnabled(boolean enabled) {
                this.outlineEnabled = enabled;
                this.invalidate();
            }
            public boolean isOutlineEnabled() {
                return this.outlineEnabled;
            }
			public void setOutlineWidth(float width) {
                this.outlineWidth = width;
                this.invalidate();
            }
            public float getOutlineWidth() {
                return this.outlineWidth;
            }

            @Override
            protected void onDraw(Canvas canvas) {
                if (outlineEnabled) {
                    int originalColor = this.getCurrentTextColor();
                    TextPaint paint = this.getPaint();

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(outlineWidth);
                    this.setTextColor(outlineColor);
                    super.onDraw(canvas);

                    paint.setStyle(Paint.Style.FILL);
                    this.setTextColor(originalColor);
                }
                super.onDraw(canvas);
            }
        }
    }
}

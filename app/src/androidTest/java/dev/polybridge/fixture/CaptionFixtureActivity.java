package dev.polybridge.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Standalone test APK activity, with a different Linux UID from the translator. */
public class CaptionFixtureActivity extends Activity {
    private MediaPlayer player;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 90);
        root.setGravity(Gravity.BOTTOM);
        TextView title = new TextView(this);
        title.setText("独立媒体播放器");
        root.addView(title);
        add(root, "播放法语样本", "tickets-fr", true);
        add(root, "播放中文样本", "station-zh", true);
        add(root, "播放禁止共享的样本", "tickets-fr", false);
        add(root, "播放纯雨声", "rain/1-21189-A-10.wav", true);
        add(root, "播放换气长语音", "rain/breathing-fr.wav", true);
        setContentView(root);
    }

    private void add(LinearLayout root, String label, String name, boolean capturable) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> {
            release();
            try {
                boolean testAsset = name.startsWith("rain/");
                Context owner = testAsset ? this : createPackageContext("dev.polybridge.app", 0);
                String path = testAsset ? name : "phrasebook/" + name + ".wav";
                try (AssetFileDescriptor asset = owner.getAssets().openFd(path)) {
                    player = new MediaPlayer();
                    player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(
                            capturable ? AudioAttributes.ALLOW_CAPTURE_BY_ALL : AudioAttributes.ALLOW_CAPTURE_BY_NONE).build());
                    player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
                    player.prepare();
                    player.start();
                }
            } catch (Exception failure) { throw new IllegalStateException("Cannot play the known fixture", failure); }
        });
        root.addView(button);
    }

    private void release() { if (player != null) { player.release(); player = null; } }
    @Override public void onDestroy() { release(); super.onDestroy(); }
}

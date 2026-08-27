package com.swiftdock.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                insetsController.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                insetsController.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Hide status bar & system UI for clean fullscreen splash
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        setContentView(R.layout.activity_splash);

        View root = findViewById(R.id.splash_root);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                v.setPadding(0, 0, 0, 0);
                return insets;
            });
            root.setPadding(0, 0, 0, 0);
        }

        ImageView logo = findViewById(R.id.splash_logo);
        TextView appName = findViewById(R.id.splash_app_name);
        ProgressBar progress = findViewById(R.id.splash_progress);

        // Start logo animation
        logo.setScaleX(0f);
        logo.setScaleY(0f);
        logo.setAlpha(0f);

        logo.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(800)
            .setInterpolator(new OvershootInterpolator(1.2f))
            .withEndAction(() -> {
                // Show app name and loading indicator
                appName.animate()
                    .alpha(1f)
                    .translationY(-10)
                    .setDuration(400)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();

                progress.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .start();

                // Start transition delay
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    // Clean fade out transition - everything fades to black
                    logo.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start();

                    appName.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .start();

                    progress.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .start();

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                        startActivity(intent);
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }, 500);

                }, 1000);
            })
            .start();
    }
}

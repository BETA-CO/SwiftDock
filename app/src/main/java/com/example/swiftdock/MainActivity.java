package com.example.swiftdock;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.navigation.fragment.NavHostFragment;

import com.example.swiftdock.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.rotationAnimation = android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE;
            getWindow().setAttributes(lp);
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> insets);
        binding.main.setPadding(0, 0, 0, 0);
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();

            appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (binding.toolbar != null && binding.toolbar.getParent() instanceof View) {
                    ((View) binding.toolbar.getParent()).setVisibility(View.GONE);
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    getWindow().setDecorFitsSystemWindows(false);
                    android.view.WindowInsetsController insetsController = getWindow().getInsetsController();
                    if (insetsController != null) {
                        insetsController.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                        insetsController.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    );
                }
                binding.main.setPadding(0, 0, 0, 0);
                ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, in) -> in);
            });
        }

        // FAB removed
        checkForMobileUpdates();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);
        NavController navController = navHostFragment.getNavController();
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private void checkForMobileUpdates() {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

        executor.execute(() -> {
            java.net.HttpURLConnection connection = null;
            java.io.BufferedReader reader = null;
            try {
                java.net.URL url = new java.net.URL("https://raw.githubusercontent.com/BETA-CO/SwiftDock/main/update_mobile.json");
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    java.io.InputStream in = connection.getInputStream();
                    reader = new java.io.BufferedReader(new java.io.InputStreamReader(in));
                    StringBuilder result = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }

                    org.json.JSONObject json = new org.json.JSONObject(result.toString());
                    int onlineVersionCode = json.optInt("versionCode", 0);
                    String onlineVersionName = json.optString("versionName", "");
                    String apkUrl = json.optString("apkUrl", "");
                    String changelog = json.optString("changelog", "");

                    // Compare with current version
                    int currentVersionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;

                    if (onlineVersionCode > currentVersionCode) {
                        handler.post(() -> showUpdatePrompt(onlineVersionName, apkUrl, changelog));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignored) {}
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void showUpdatePrompt(String versionName, String apkUrl, String changelog) {
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_update_prompt, null);
        
        android.widget.TextView tvTitle = dialogView.findViewById(R.id.tv_dialog_title);
        android.widget.TextView tvMessage = dialogView.findViewById(R.id.tv_dialog_message);
        android.widget.Button btnLater = dialogView.findViewById(R.id.btn_dialog_later);
        android.widget.Button btnUpdate = dialogView.findViewById(R.id.btn_dialog_update);

        tvTitle.setText("Update Available");
        tvMessage.setText("A new version of SwiftDock (v" + versionName + ") is available.\n\nChangelog:\n" + changelog + "\n\nDo you want to download and install it now?");

        androidx.appcompat.app.AlertDialog alertDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create();

        if (alertDialog.getWindow() != null) {
            alertDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        btnLater.setOnClickListener(v -> alertDialog.dismiss());
        btnUpdate.setOnClickListener(v -> {
            alertDialog.dismiss();
            downloadAndInstallApk(apkUrl);
        });

        alertDialog.show();
    }

    private void downloadAndInstallApk(String apkUrl) {
        android.view.View dialogView = getLayoutInflater().inflate(R.layout.dialog_update_progress, null);
        android.widget.ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar_download);
        android.widget.TextView tvProgress = dialogView.findViewById(R.id.tv_progress_percentage);

        androidx.appcompat.app.AlertDialog progressDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (progressDialog.getWindow() != null) {
            progressDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        progressDialog.show();

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

        executor.execute(() -> {
            java.net.HttpURLConnection connection = null;
            java.io.InputStream input = null;
            java.io.OutputStream output = null;
            java.io.File apkFile = null;
            try {
                java.net.URL url = new java.net.URL(apkUrl);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.connect();

                int fileLength = connection.getContentLength();
                input = connection.getInputStream();

                apkFile = new java.io.File(getExternalCacheDir(), "SwiftDockUpdate.apk");
                output = new java.io.FileOutputStream(apkFile);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        int progress = (int) (total * 100 / fileLength);
                        handler.post(() -> {
                            progressBar.setProgress(progress);
                            tvProgress.setText(progress + "%");
                        });
                    }
                    output.write(data, 0, count);
                }
                output.flush();

                final java.io.File finalApkFile = apkFile;
                handler.post(() -> {
                    progressDialog.dismiss();
                    installApk(finalApkFile);
                });

            } catch (Exception e) {
                e.printStackTrace();
                handler.post(() -> {
                    progressDialog.dismiss();
                    android.widget.Toast.makeText(this, "Download failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                });
            } finally {
                try {
                    if (output != null) output.close();
                    if (input != null) input.close();
                } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void installApk(java.io.File file) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To install updates directly, SwiftDock requires the 'Install unknown apps' permission.\n\nPlease enable it in the system settings.")
                    .setPositiveButton("Settings", (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return;
            }
        }

        android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
        );

        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
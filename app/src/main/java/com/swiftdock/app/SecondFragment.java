package com.swiftdock.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import androidx.constraintlayout.widget.ConstraintLayout;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;
public class SecondFragment extends Fragment implements NetworkClient.NetworkListener {

    private static SecondFragment instance = null;

    public static SecondFragment getInstance() {
        return instance;
    }

    public NetworkClient getNetworkClient() {
        return networkClient;
    }

    private ViewPager2 viewPager;
    private LinearLayout layoutDots;
    private TextView tvStatus;
    private ConstraintLayout layoutReconnectingOverlay;
    
    private NetworkClient networkClient;
    private List<ShortcutButton> buttonsList = new ArrayList<>();
    private static final androidx.collection.LruCache<String, android.graphics.Bitmap> iconCache = new androidx.collection.LruCache<>(50);
    private PagerAdapter pagerAdapter;

    private Dialog presentationDialog = null;
    private SensorManager sensorManager = null;
    private Sensor gyroSensor = null;
    private Sensor accelSensor = null;
    private Sensor rotationVectorSensor = null;
    private String activePresentationGyroMode = null;

    private float smoothedGyroX = 0f;
    private float smoothedGyroY = 0f;

    private float[] currentRotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];
    private float lastYaw = 0f;
    private float lastPitch = 0f;
    private float lastAccelX = 0f;
    private float lastAccelY = 0f;
    private boolean hasFirstOrientation = false;
    private boolean hasFirstAccel = false;
    private boolean isPresOrientationLocked = false;

    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                if (activePresentationGyroMode == null || networkClient == null || !networkClient.isConnected()) {
                    return;
                }
                if (event == null || event.values == null || event.values.length < 2) {
                    return;
                }

                float dx = 0f;
                float dy = 0f;

                int sensorType = event.sensor.getType();

                if (sensorType == Sensor.TYPE_GYROSCOPE) {
                    // 1. Gyroscope Angular Speed (X: Pitch, Y: Roll, Z: Yaw)
                    float rawGyroX = event.values[0];
                    float rawGyroY = event.values.length > 1 ? event.values[1] : 0f;
                    float rawGyroZ = event.values.length > 2 ? event.values[2] : 0f;

                    Context ctx = getContext();
                    boolean isLandscape = ctx != null && 
                            ctx.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

                    float gyroHoriz = isLandscape ? (-rawGyroY - rawGyroZ) : (-rawGyroZ - rawGyroY);
                    float gyroVert  = -rawGyroX; // Invert Y-axis: phone UP -> pointer UP

                    float deadZone = 0.01f;
                    if (Math.abs(gyroHoriz) < deadZone) gyroHoriz = 0f;
                    if (Math.abs(gyroVert)  < deadZone) gyroVert  = 0f;

                    dx = gyroHoriz * 2.5f;
                    dy = gyroVert  * 2.5f;
                } else if (sensorType == Sensor.TYPE_ROTATION_VECTOR || sensorType == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    // 2. Hardware Fused Orientation Vector
                    if (currentRotationMatrix == null) currentRotationMatrix = new float[9];
                    if (orientationAngles == null) orientationAngles = new float[3];

                    SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values);
                    SensorManager.getOrientation(currentRotationMatrix, orientationAngles);

                    float currentYaw   = orientationAngles[0]; // Yaw angle (radians)
                    float currentPitch = orientationAngles[1]; // Pitch angle (radians)

                    if (!hasFirstOrientation) {
                        lastYaw = currentYaw;
                        lastPitch = currentPitch;
                        hasFirstOrientation = true;
                        return;
                    }

                    float deltaYaw = currentYaw - lastYaw;
                    if (deltaYaw > Math.PI) deltaYaw -= (float)(2.0 * Math.PI);
                    if (deltaYaw < -Math.PI) deltaYaw += (float)(2.0 * Math.PI);

                    float deltaPitch = currentPitch - lastPitch;
                    if (deltaPitch > Math.PI) deltaPitch -= (float)(2.0 * Math.PI);
                    if (deltaPitch < -Math.PI) deltaPitch += (float)(2.0 * Math.PI);

                    lastYaw = currentYaw;
                    lastPitch = currentPitch;

                    dx = deltaYaw * 14.0f;
                    dy = -deltaPitch * 14.0f; // Invert Y-axis
                } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
                    // 3. Accelerometer Gravitational Tilt Delta
                    float ax = event.values[0];
                    float ay = event.values[1];

                    if (!hasFirstAccel) {
                        lastAccelX = ax;
                        lastAccelY = ay;
                        hasFirstAccel = true;
                        return;
                    }

                    float dAx = ax - lastAccelX;
                    float dAy = ay - lastAccelY;

                    lastAccelX = ax;
                    lastAccelY = ay;

                    dx = dAx * 0.12f;
                    dy = -dAy * 0.12f; // Invert Y-axis
                }

                // High-responsiveness fast alpha for 0-lag instant transmission
                float alpha = 0.85f;
                smoothedGyroX = smoothedGyroX + alpha * (dx - smoothedGyroX);
                smoothedGyroY = smoothedGyroY + alpha * (dy - smoothedGyroY);

                if (Math.abs(smoothedGyroX) > 0.0001f || Math.abs(smoothedGyroY) > 0.0001f) {
                    networkClient.sendPresentationGyro(activePresentationGyroMode, smoothedGyroX, smoothedGyroY);
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };


    private boolean isUserDisconnecting = false;
    private boolean isReconnecting = false;
    private boolean isHoldingButton = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || isUserDisconnecting) return;
            Context context = getContext();
            if (context == null) return;
            if (!networkClient.isConnected()) {
                SharedPreferences prefs = context.getSharedPreferences("SwiftDockPrefs", Context.MODE_PRIVATE);
                String savedIp = prefs.getString("paired_ip", "");
                String savedToken = prefs.getString("paired_token", "");
                String mobileName = prefs.getString("mobile_name", android.os.Build.MODEL);

                if (!android.text.TextUtils.isEmpty(savedIp) && !android.text.TextUtils.isEmpty(savedToken)) {
                    boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    int cols = isTablet() ? (isLandscape ? 5 : 3) : (isLandscape ? 4 : 2);
                    int rows = isTablet() ? (isLandscape ? 3 : 5) : (isLandscape ? 2 : 4);
                    networkClient.reconnect(savedIp, savedToken, mobileName, cols, rows);
                }
            }
        }
    };

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_second, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        instance = this;

        // Enable full sensor auto-rotation for dock screen (supporting both portrait and landscape dock layouts)
        requireActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        viewPager = view.findViewById(R.id.view_pager);
        layoutDots = view.findViewById(R.id.layout_dots);
        tvStatus = view.findViewById(R.id.tv_status);

        networkClient = NetworkClient.getInstance();
        networkClient.addListener(this);

        setButtonsList(networkClient.getCachedButtons());
        
        // Setup Pager
        pagerAdapter = new PagerAdapter();
        viewPager.setAdapter(pagerAdapter);
        updateLayoutForOrientation(getResources().getConfiguration().orientation);

        // Dot indicators initialization
        int pageCount = getPageCount();
        setupDots(pageCount);
        updateDots(0);

        // Track page selection changes to update dot indicators
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            private final android.os.Handler hideDotsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            private final Runnable hideDotsRunnable = new Runnable() {
                @Override
                public void run() {
                    if (layoutDots != null && isAdded()) {
                        layoutDots.animate()
                                .alpha(0f)
                                .setDuration(300)
                                .withEndAction(() -> layoutDots.setVisibility(View.GONE))
                                .start();
                    }
                }
            };

            private void showDotsTransiently() {
                if (layoutDots == null || !isAdded() || getPageCount() <= 1) return;
                hideDotsHandler.removeCallbacks(hideDotsRunnable);
                if (layoutDots.getVisibility() != View.VISIBLE) {
                    layoutDots.setAlpha(0f);
                    layoutDots.setVisibility(View.VISIBLE);
                    layoutDots.animate().alpha(1f).setDuration(200).start();
                } else {
                    layoutDots.setAlpha(1f);
                }
                hideDotsHandler.postDelayed(hideDotsRunnable, 1200);
            }

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels);
                if (positionOffsetPixels > 0) {
                    showDotsTransiently();
                }
            }

            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateDots(position);
                showDotsTransiently();
                if (networkClient.isConnected()) {
                    networkClient.sendPageChange(position);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                super.onPageScrollStateChanged(state);
                if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING || 
                    state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING) {
                    showDotsTransiently();
                } else if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                    hideDotsHandler.removeCallbacks(hideDotsRunnable);
                    hideDotsHandler.postDelayed(hideDotsRunnable, 1200);
                }
            }
        });

        layoutReconnectingOverlay = view.findViewById(R.id.layout_reconnecting_overlay);
        isUserDisconnecting = false;
        isReconnecting = false;

        View btnCancelReconnect = view.findViewById(R.id.btn_reconnect_cancel);
        if (btnCancelReconnect != null) {
            btnCancelReconnect.setOnClickListener(v -> {
                isUserDisconnecting = true;
                isReconnecting = false;
                reconnectHandler.removeCallbacks(reconnectRunnable);
                networkClient.disconnect();
                clearPairingPrefs();
                navigateToConnectScreen();
            });
        }
    }

    private void setButtonsList(List<ShortcutButton> buttons) {
        buttonsList.clear();
        
        ShortcutButton settingsBtn = new ShortcutButton();
        settingsBtn.setId("SWIFTDOCK_INTERNAL_SETTINGS");
        settingsBtn.setTitle("Settings");
        settingsBtn.setColor("#FFFFFF");
        settingsBtn.setIcon("settings");
        buttonsList.add(settingsBtn);
        
        if (buttons != null) {
            buttonsList.addAll(buttons);
        }
    }

    private int getPageCount() {
        if (buttonsList.isEmpty()) return 1;
        return (int) Math.ceil(buttonsList.size() / (double) getPageSize());
    }

    private void setupDots(int count) {
        if (layoutDots == null) return;
        layoutDots.removeAllViews();
        if (count <= 1) {
            layoutDots.setVisibility(View.GONE);
            return;
        }

        layoutDots.setVisibility(View.GONE);
        layoutDots.setAlpha(0f);

        for (int i = 0; i < count; i++) {
            ImageView dot = new ImageView(getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
            dot.setLayoutParams(params);
            dot.setImageResource(R.drawable.dot_inactive);
            layoutDots.addView(dot);
        }
    }

    private void updateDots(int activeIndex) {
        int count = layoutDots.getChildCount();
        for (int i = 0; i < count; i++) {
            ImageView dot = (ImageView) layoutDots.getChildAt(i);
            if (i == activeIndex) {
                dot.setImageResource(R.drawable.dot_active);
            } else {
                dot.setImageResource(R.drawable.dot_inactive);
            }
        }
    }

    private void clearPairingPrefs() {
        requireContext().getSharedPreferences("SwiftDockPrefs", 0)
                .edit()
                .putString("paired_token", "")
                .putString("paired_pc", "")
                .putString("paired_ip", "")
                .apply();
    }

    private void navigateToConnectScreen() {
        if (isAdded()) {
            try {
                androidx.navigation.NavController navController = NavHostFragment.findNavController(SecondFragment.this);
                if (navController.getCurrentDestination() != null && 
                    navController.getCurrentDestination().getId() == R.id.SecondFragment) {
                    navController.navigate(R.id.action_SecondFragment_to_FirstFragment);
                }
            } catch (Exception e) {
                // Ignore safe navigation check failure
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        reconnectHandler.removeCallbacks(reconnectRunnable);
        networkClient.stopDiscovery();
        networkClient.removeListener(this);
    }

    // Network Callbacks
    @Override
    public void onServerDiscovered(String ip, int port, String hostname) {
        if (!isAdded()) return;
        if (isReconnecting && !networkClient.isConnected()) {
            Context context = getContext();
            if (context == null) return;
            SharedPreferences prefs = context.getSharedPreferences("SwiftDockPrefs", Context.MODE_PRIVATE);
            String savedToken = prefs.getString("paired_token", "");
            String mobileName = prefs.getString("mobile_name", android.os.Build.MODEL);
            boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int cols = isTablet() ? (isLandscape ? 5 : 3) : (isLandscape ? 4 : 2);
            int rows = isTablet() ? (isLandscape ? 3 : 5) : (isLandscape ? 2 : 4);
            networkClient.reconnect(ip, savedToken, mobileName, cols, rows);
        }
    }

    private void showReconnectingOverlay(boolean show) {
        if (layoutReconnectingOverlay == null) return;
        if (show) {
            if (layoutReconnectingOverlay.getVisibility() != View.VISIBLE) {
                layoutReconnectingOverlay.setAlpha(0f);
                layoutReconnectingOverlay.setVisibility(View.VISIBLE);
                layoutReconnectingOverlay.animate().alpha(1f).setDuration(250).start();
            }
        } else {
            if (layoutReconnectingOverlay.getVisibility() == View.VISIBLE) {
                layoutReconnectingOverlay.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    layoutReconnectingOverlay.setVisibility(View.GONE);
                }).start();
            }
        }
    }

    @Override
    public void onConnectionSuccess(String token) {
        if (!isAdded()) return;
        if (isReconnecting) {
            isReconnecting = false;
            reconnectHandler.removeCallbacks(reconnectRunnable);
            networkClient.stopDiscovery();
            showReconnectingOverlay(false);
            Toast.makeText(getContext(), "Reconnected!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        if (!isAdded()) return;
        if (isReconnecting && !isUserDisconnecting) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectHandler.postDelayed(reconnectRunnable, 2500);
        }
    }

    @Override
    public void onDisconnected() {
        if (!isAdded()) return;
        if (isUserDisconnecting) {
            navigateToConnectScreen();
        } else {
            if (!isReconnecting) {
                isReconnecting = true;
                showReconnectingOverlay(true);
                Context context = getContext();
                if (context != null) {
                    networkClient.startDiscovery(context);
                }
                reconnectHandler.removeCallbacks(reconnectRunnable);
                reconnectHandler.postDelayed(reconnectRunnable, 1000);
            }
        }
    }

    private int getContainerWidth() {
        if (viewPager != null && viewPager.getWidth() > 0) {
            return viewPager.getWidth();
        }
        if (getContext() != null) {
            return getContext().getResources().getDisplayMetrics().widthPixels;
        }
        return 0;
    }

    private int getContainerHeight() {
        if (viewPager != null && viewPager.getHeight() > 0) {
            return viewPager.getHeight();
        }
        if (getContext() != null) {
            return getContext().getResources().getDisplayMetrics().heightPixels;
        }
        return 0;
    }

    private final java.util.Set<String> unlockedMobileProfiles = new java.util.HashSet<>();

    @Override
    public void onProfileUnlockResponse(String profileId, boolean success) {
        if (!isAdded() || getContext() == null) return;
        if (success) {
            unlockedMobileProfiles.add(profileId);
            Toast.makeText(getContext(), "Profile Unlocked 🔓", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Incorrect PIN. Access Denied.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onProfileUnlockRequired(String profileId) {
        if (!isAdded() || getContext() == null) return;
        NetworkClient.ProfileInfo profile = findProfileById(profileId);
        if (profile == null) {
            profile = new NetworkClient.ProfileInfo(profileId != null ? profileId : "", "Locked Profile", true);
        }
        showPinUnlockDialog(profile);
    }

    private NetworkClient.ProfileInfo findProfileById(String profileIdOrName) {
        if (networkClient != null && profileIdOrName != null) {
            List<NetworkClient.ProfileInfo> profiles = networkClient.getCachedProfiles();
            if (profiles != null) {
                for (NetworkClient.ProfileInfo p : profiles) {
                    if (p != null) {
                        if (profileIdOrName.equalsIgnoreCase(p.getId()) || profileIdOrName.equalsIgnoreCase(p.getName())) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void showPinUnlockDialog(NetworkClient.ProfileInfo profile) {
        if (getContext() == null || !isAdded() || profile == null) return;
        String profileName = profile.getName() != null ? profile.getName() : "Locked Profile";
        String profileId = profile.getId() != null ? profile.getId() : "";

        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter 4-6 digit PIN");
        int padding = dpToPx(16);
        input.setPadding(padding, padding, padding, padding);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Unlock " + profileName)
                .setMessage("Enter Profile PIN:")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    String pin = input.getText().toString().trim();
                    if (!pin.isEmpty() && networkClient != null) {
                        networkClient.sendProfileUnlockRequest(profileId, pin);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onPowerActionExecuting(String action) {
        if (!isAdded() || getContext() == null) return;
        isUserDisconnecting = true;
        if (networkClient != null) {
            networkClient.disconnect();
        }
        navigateToConnectScreen();
    }

    @Override
    public void onConfirmActionRequest(String actionId, String title, String message) {
        if (!isAdded() || getContext() == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (dialog, which) -> {
                    if (networkClient != null) {
                        networkClient.sendConfirmActionResponse(actionId, true);
                    }
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        isUserDisconnecting = true;
                        if (networkClient != null) {
                            networkClient.disconnect();
                        }
                        navigateToConnectScreen();
                    }, 400);
                })
                .setNegativeButton("No", (dialog, which) -> {
                    if (networkClient != null) {
                        networkClient.sendConfirmActionResponse(actionId, false);
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onButtonsSynced(List<ShortcutButton> buttons) {
        if (!isAdded()) return;
        setButtonsList(buttons);
        
        int currentPage = viewPager.getCurrentItem();
        int newPageCount = getPageCount();
        
        setupDots(newPageCount);
        if (pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
        }
        
        // Restore page index safely
        if (currentPage < newPageCount) {
            viewPager.setCurrentItem(currentPage, false);
            updateDots(currentPage);
        } else {
            viewPager.setCurrentItem(0, false);
            updateDots(0);
        }
    }

    @Override
    public void onTransitionToGrid() {}

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (iconCache != null) {
            iconCache.evictAll();
        }
    }

    @Override
    public void onPerformanceUpdated(int cpu, int gpu, int ram, int temp, String wifi) {
        if (!isAdded()) return;
        if (isHoldingButton) {
            // Do not refresh layout when holding a button to prevent touch event disruption
            return;
        }

        // Only refresh layout if there are active performance metric buttons on the grid
        boolean hasPerfMetricButton = false;
        if (buttonsList != null) {
            for (ShortcutButton b : buttonsList) {
                if (b != null && "System".equalsIgnoreCase(b.getActionType()) && 
                    b.getActionData() != null && b.getActionData().startsWith("perf_")) {
                    hasPerfMetricButton = true;
                    break;
                }
            }
        }

        if (hasPerfMetricButton && pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
        }
    }

    // Pager Adapter for swiping pages
    private class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.PageViewHolder> {

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_page, parent, false);
            view.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            return new PageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            int pageSize = getPageSize();
            int start = position * pageSize;
            int end = Math.min(start + pageSize, buttonsList.size());
            
            List<ShortcutButton> pageButtons = new ArrayList<>();
            if (start < buttonsList.size()) {
                pageButtons.addAll(buttonsList.subList(start, end));
            }

            boolean isLandscape = holder.gridView.getContext().getResources().getConfiguration().orientation 
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if (isTablet()) {
                holder.gridView.setNumColumns(isLandscape ? 5 : 3);
            } else {
                holder.gridView.setNumColumns(isLandscape ? 4 : 2);
            }

            GridAdapter gridAdapter = new GridAdapter(pageButtons);
            holder.gridView.setAdapter(gridAdapter);
            holder.gridView.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            holder.gridView.setDrawSelectorOnTop(false);
            holder.gridView.setNestedScrollingEnabled(false);

            float density = holder.gridView.getContext().getResources().getDisplayMetrics().density;
            int gap = (int) (12 * density);
            int minOuterMargin = (int) (16 * density);

            int numColumns = isTablet() ? (isLandscape ? 5 : 3) : (isLandscape ? 4 : 2);
            int numRows = isTablet() ? (isLandscape ? 3 : 5) : (isLandscape ? 2 : 4);

            holder.gridView.setHorizontalSpacing(gap);
            holder.gridView.setVerticalSpacing(gap);

            int parentWidth = getContainerWidth();
            int parentHeight = getContainerHeight();

            if (parentWidth > 0 && parentHeight > 0) {
                int availW = parentWidth - (2 * minOuterMargin) - ((numColumns - 1) * gap);
                int availH = parentHeight - (2 * minOuterMargin) - ((numRows - 1) * gap);

                int cWidth = availW / numColumns;
                int cHeight = availH / numRows;
                int squareSize = Math.min(cWidth, cHeight);

                int gridContentW = (numColumns * squareSize) + ((numColumns - 1) * gap);
                int gridContentH = (numRows * squareSize) + ((numRows - 1) * gap);

                int padX = Math.max(minOuterMargin, (parentWidth - gridContentW) / 2);
                int padY = Math.max(minOuterMargin, (parentHeight - gridContentH) / 2);

                holder.gridView.setPadding(padX, padY, padX, padY);
            }

            holder.gridView.setOnItemClickListener(null);
        }

        @Override
        public int getItemCount() {
            return getPageCount();
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            GridView gridView;

            public PageViewHolder(@NonNull View itemView) {
                super(itemView);
                gridView = itemView.findViewById(R.id.grid_view_page);
            }
        }
    }

    // Grid Adapter for the 4x2 button deck on each page
    private class GridAdapter extends BaseAdapter {
        private final List<ShortcutButton> pageButtons;

        public GridAdapter(List<ShortcutButton> pageButtons) {
            this.pageButtons = pageButtons;
        }

        @Override
        public int getCount() {
            return pageButtons.size();
        }

        @Override
        public Object getItem(int position) {
            return pageButtons.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_deck_button, parent, false);
                holder = new ViewHolder();
                holder.cardView = convertView.findViewById(R.id.card_view);
                holder.tvTitle = convertView.findViewById(R.id.btn_title);
                holder.ivIcon = convertView.findViewById(R.id.btn_icon);
                holder.btnContentContainer = convertView.findViewById(R.id.btn_content_container);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // Set height dynamically based on rows (2 for landscape, 4 for portrait)
            boolean isLandscape = getResources().getConfiguration().orientation 
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int numRows;
            if (isTablet()) {
                numRows = isLandscape ? 3 : 5;
            } else {
                numRows = isLandscape ? 2 : 4;
            }

            int parentHeight = (parent != null && parent.getHeight() > 0) ? parent.getHeight() : getContainerHeight();
            int parentWidth = (parent != null && parent.getWidth() > 0) ? parent.getWidth() : getContainerWidth();

            if (getContext() != null) {
                int displayW = getContext().getResources().getDisplayMetrics().widthPixels;
                int displayH = getContext().getResources().getDisplayMetrics().heightPixels;
                if (isLandscape) {
                    if (parentWidth <= 0 || parentWidth < parentHeight) parentWidth = Math.max(displayW, displayH);
                    if (parentHeight <= 0 || parentHeight > parentWidth) parentHeight = Math.min(displayW, displayH);
                }
            }

            int squareSize = 0;

            if (parentHeight > 0 && parentWidth > 0) {
                float density = getResources().getDisplayMetrics().density;
                int gap = (int) (12 * density);
                int minOuterMargin = (int) (16 * density);

                int numColumns = isTablet() ? (isLandscape ? 5 : 3) : (isLandscape ? 4 : 2);

                int availW = parentWidth - (2 * minOuterMargin) - ((numColumns - 1) * gap);
                int availH = parentHeight - (2 * minOuterMargin) - ((numRows - 1) * gap);

                int cWidth = availW / numColumns;
                int cHeight = availH / numRows;
                squareSize = Math.min(cWidth, cHeight);

                ViewGroup.LayoutParams lp = convertView.getLayoutParams();
                if (lp == null) {
                    lp = new GridView.LayoutParams(squareSize, squareSize);
                } else {
                    lp.width = squareSize;
                    lp.height = squareSize;
                }
                convertView.setLayoutParams(lp);

                // Enforce perfect 1:1 square shape for cardView centered inside cell
                android.widget.FrameLayout.LayoutParams cardLp = (android.widget.FrameLayout.LayoutParams) holder.cardView.getLayoutParams();
                if (cardLp == null) {
                    cardLp = new android.widget.FrameLayout.LayoutParams(squareSize, squareSize);
                } else {
                    cardLp.width = squareSize;
                    cardLp.height = squareSize;
                }
                cardLp.gravity = android.view.Gravity.CENTER;
                holder.cardView.setLayoutParams(cardLp);
            }

            ShortcutButton btn = pageButtons.get(position);

            // Handle invisible spacer buttons (used to fill presentation mode grid)
            if ("spacer".equals(btn.getIcon())) {
                holder.cardView.setVisibility(View.INVISIBLE);
                holder.ivIcon.setVisibility(View.GONE);
                holder.tvTitle.setVisibility(View.GONE);
                return convertView;
            } else {
                holder.cardView.setVisibility(View.VISIBLE);
            }

            boolean isPerfBtn = "System".equalsIgnoreCase(btn.getActionType()) && 
                                btn.getActionData() != null && 
                                btn.getActionData().toLowerCase().startsWith("perf_");
            String iconValue = btn.getIcon();
            boolean isTextBtn = iconValue != null && iconValue.startsWith("text:");
            
            android.widget.FrameLayout.LayoutParams iconLp = (android.widget.FrameLayout.LayoutParams) holder.ivIcon.getLayoutParams();
            if (isPerfBtn) {
                int size = isTablet() ? 44 : 34;
                iconLp.width = dpToPx(size);
                iconLp.height = dpToPx(size);
                iconLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                iconLp.topMargin = dpToPx(6);
                iconLp.leftMargin = dpToPx(6);
                iconLp.rightMargin = 0;
                iconLp.bottomMargin = 0;
                
                holder.tvTitle.setVisibility(View.VISIBLE);
                String valStr = "";
                float textSize = 22f; // Visibly big text size in center
                switch (btn.getActionData().toLowerCase()) {
                    case "perf_cpu": valStr = NetworkClient.currentCpu + "%"; break;
                    case "perf_gpu": valStr = NetworkClient.currentGpu + "%"; break;
                    case "perf_ram": valStr = NetworkClient.currentRam + "%"; break;
                    case "perf_temp": valStr = NetworkClient.currentTemp + "°C"; break;
                    case "perf_wifi": 
                        valStr = NetworkClient.currentWifi; 
                        textSize = 15f; // Smaller text size to avoid overflow on speed units
                        break;
                }
                holder.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
                holder.tvTitle.setText(valStr);
            } else if (isTextBtn) {
                iconLp.width = 0;
                iconLp.height = 0;
                iconLp.gravity = android.view.Gravity.CENTER;
                iconLp.topMargin = 0;
                iconLp.leftMargin = 0;
                iconLp.rightMargin = 0;
                iconLp.bottomMargin = 0;
                
                holder.tvTitle.setVisibility(View.VISIBLE);
                String displayTxt = iconValue.substring(5);
                float textSize = displayTxt.length() <= 3 ? (isTablet() ? 56f : 42f) : (isTablet() ? 28f : 20f); // Large bold font for mobile text icons like F1..F12
                holder.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
                holder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                holder.tvTitle.setText(displayTxt);
            } else {
                int iconPx = squareSize > 0 ? (int) (squareSize * 0.52f) : dpToPx(isTablet() ? 72 : 56);
                iconLp.width = iconPx;
                iconLp.height = iconPx;
                iconLp.gravity = android.view.Gravity.CENTER;
                iconLp.topMargin = 0;
                iconLp.leftMargin = 0;
                iconLp.rightMargin = 0;
                iconLp.bottomMargin = 0;
                
                holder.tvTitle.setVisibility(View.GONE);
                holder.tvTitle.setText(btn.getTitle());
            }
            holder.ivIcon.setLayoutParams(iconLp);

            // Apply solid/gradient black keycap with tactile press states
            holder.cardView.setBackground(getFuturisticKeycapDrawable(btn.getColor(), squareSize));

            // Remove any dynamically added grid view from holder.btnContentContainer first
            View oldDynamicGrid = holder.btnContentContainer.findViewWithTag("dynamic_url_grid");
            if (oldDynamicGrid != null && holder.btnContentContainer instanceof ViewGroup) {
                ((ViewGroup) holder.btnContentContainer).removeView(oldDynamicGrid);
            }

            // Check if profile button targets a locked profile
            boolean isLockedProfileBtn = false;
            if ("profile".equalsIgnoreCase(btn.getActionType())) {
                NetworkClient.ProfileInfo targetProf = findProfileById(btn.getActionData());
                if (targetProf != null) {
                    isLockedProfileBtn = targetProf.isLocked();
                } else {
                    List<NetworkClient.ProfileInfo> profiles = networkClient != null ? networkClient.getCachedProfiles() : null;
                    if (profiles != null && btn.getActionData() != null) {
                        for (NetworkClient.ProfileInfo p : profiles) {
                            if (p != null && (btn.getActionData().equalsIgnoreCase(p.getId()) || btn.getActionData().equalsIgnoreCase(p.getName()))) {
                                isLockedProfileBtn = p.isLocked();
                                break;
                            }
                        }
                    }
                }
            }

            if (isLockedProfileBtn) {
                holder.ivIcon.setVisibility(View.VISIBLE);
                renderSingleIcon(holder.ivIcon, "locked_profile");
            } else if (iconValue != null && iconValue.contains("|")) {
                String[] parts = iconValue.split("\\|");
                if (parts.length > 1) {
                    holder.ivIcon.setVisibility(View.GONE);
                    View gridView = createDynamicGrid(parts, squareSize);
                    if (holder.btnContentContainer instanceof ViewGroup) {
                        ((ViewGroup) holder.btnContentContainer).addView(gridView);
                    }
                } else {
                    holder.ivIcon.setVisibility(View.VISIBLE);
                    renderSingleIcon(holder.ivIcon, parts[0]);
                }
            } else if (isTextBtn) {
                holder.ivIcon.setVisibility(View.GONE);
            } else {
                holder.ivIcon.setVisibility(View.VISIBLE);
                renderSingleIcon(holder.ivIcon, iconValue);
            }

            final View finalCardView = holder.cardView;
            convertView.setOnTouchListener(new View.OnTouchListener() {
                private final Handler repeatHandler = new Handler(Looper.getMainLooper());
                private final Runnable repeatRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (networkClient.isConnected()) {
                            networkClient.sendButtonPress(btn.getId());
                            repeatHandler.postDelayed(this, 100);
                        }
                    }
                };

                private float downX = 0f;
                private float downY = 0f;
                private boolean isDragging = false;

                private void triggerHapticFeedback(View view) {
                    if (view == null) return;
                    try {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                    } catch (Exception e) {
                        try {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        } catch (Exception ignored) {}
                    }
                }

                private void animateDown() {
                    if (finalCardView == null) return;
                    finalCardView.animate().cancel();
                    finalCardView.animate()
                            .scaleX(0.90f)
                            .scaleY(0.90f)
                            .translationY(dpToPx(3.5f))
                            .alpha(0.85f)
                            .setDuration(70)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator())
                            .start();
                }

                private void animateUp() {
                    if (finalCardView == null) return;
                    finalCardView.animate().cancel();
                    finalCardView.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationY(0f)
                            .alpha(1.0f)
                            .setDuration(120)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f))
                            .start();
                }

                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    switch (event.getAction()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            isDragging = false;
                            isHoldingButton = true;
                            triggerHapticFeedback(v);
                            animateDown();

                            if (isActionRepeatable(btn)) {
                                if (networkClient.isConnected()) {
                                    networkClient.sendButtonPress(btn.getId());
                                }
                                repeatHandler.removeCallbacks(repeatRunnable);
                                repeatHandler.postDelayed(repeatRunnable, 400);
                            }
                            if (v.getParent() != null) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            return true;

                        case android.view.MotionEvent.ACTION_MOVE:
                            if (isDragging) return false;
                            float dx = event.getRawX() - downX;
                            float dy = event.getRawY() - downY;
                            float distance = (float) Math.sqrt(dx * dx + dy * dy);
                            int touchSlop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                            if (distance > touchSlop) {
                                isDragging = true;
                                isHoldingButton = false;
                                repeatHandler.removeCallbacks(repeatRunnable);
                                animateUp();
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(false);
                                }
                                return false;
                            }
                            return true;

                        case android.view.MotionEvent.ACTION_UP:
                            isHoldingButton = false;
                            repeatHandler.removeCallbacks(repeatRunnable);
                            animateUp();

                            if (!isDragging) {
                                if ("SWIFTDOCK_INTERNAL_SETTINGS".equals(btn.getId())) {
                                    showSettingsDialog();
                                } else if ("profile".equalsIgnoreCase(btn.getActionType())) {
                                    String targetProfileId = btn.getActionData();
                                    NetworkClient.ProfileInfo targetProfile = findProfileById(targetProfileId);
                                    if (targetProfile != null && targetProfile.isLocked()) {
                                        showPinUnlockDialog(targetProfile);
                                    } else {
                                        if (networkClient.isConnected()) {
                                            networkClient.sendButtonPress(btn.getId());
                                        }
                                    }
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "presentation_mode".equalsIgnoreCase(btn.getActionData())) {
                                    enterPresentationMode();
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pres_exit".equalsIgnoreCase(btn.getActionData())) {
                                    exitPresentationMode();
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pres_spotlight".equalsIgnoreCase(btn.getActionData())) {
                                    if (!isPresOrientationLocked) {
                                        if (getContext() != null) {
                                            Toast.makeText(getContext(), "Please lock orientation first!", Toast.LENGTH_SHORT).show();
                                        }
                                        return true;
                                    }
                                    if ("spotlight".equals(activePresentationGyroMode)) {
                                        stopGyroSensor();
                                        if (networkClient != null) networkClient.sendPresentationCmd("exit");
                                        btn.setColor("#12121A");
                                    } else {
                                        startGyroSensor("spotlight");
                                        if (networkClient != null) networkClient.sendPresentationCmd("spotlight");
                                        btn.setColor("#1E3A5F");
                                    }
                                    v.post(() -> notifyDataSetChanged());
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pres_laser".equalsIgnoreCase(btn.getActionData())) {
                                    if (!isPresOrientationLocked) {
                                        if (getContext() != null) {
                                            Toast.makeText(getContext(), "Please lock orientation first!", Toast.LENGTH_SHORT).show();
                                        }
                                        return true;
                                    }
                                    if ("laser".equals(activePresentationGyroMode)) {
                                        stopGyroSensor();
                                        if (networkClient != null) networkClient.sendPresentationCmd("exit");
                                        btn.setColor("#12121A");
                                    } else {
                                        startGyroSensor("laser");
                                        if (networkClient != null) networkClient.sendPresentationCmd("laser");
                                        btn.setColor("#1E3A5F");
                                    }
                                    v.post(() -> notifyDataSetChanged());
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pres_prev".equalsIgnoreCase(btn.getActionData())) {
                                    if (networkClient != null) networkClient.sendPresentationCmd("prev_slide");
                                } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pres_next".equalsIgnoreCase(btn.getActionData())) {
                                    if (networkClient != null) networkClient.sendPresentationCmd("next_slide");
                                } else if (!isActionRepeatable(btn)) {
                                    if (networkClient.isConnected()) {
                                        networkClient.sendButtonPress(btn.getId());
                                    }
                                }
                            }
                            return true;

                        case android.view.MotionEvent.ACTION_CANCEL:
                            isHoldingButton = false;
                            repeatHandler.removeCallbacks(repeatRunnable);
                            animateUp();
                            return true;
                    }
                    return false;
                }
            });



            return convertView;
        }

        private void renderSingleIcon(ImageView ivIcon, String iconValue) {
            if (iconValue != null && iconValue.startsWith("data:")) {
                try {
                    android.graphics.Bitmap cachedBitmap = iconCache.get(iconValue);
                    if (cachedBitmap != null) {
                        ivIcon.setImageBitmap(cachedBitmap);
                        ivIcon.setImageTintList(null);
                        return;
                    }
                    String base64 = iconValue;
                    if (base64.contains(",")) {
                        base64 = base64.substring(base64.indexOf(",") + 1);
                    } else if (base64.startsWith("data:")) {
                        base64 = base64.substring(5);
                    }
                    byte[] decodedBytes = android.util.Base64.decode(base64.trim(), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    if (bitmap != null) {
                        iconCache.put(iconValue, bitmap);
                        ivIcon.setImageBitmap(bitmap);
                        ivIcon.setImageTintList(null); // Remove white tint
                    } else {
                        ivIcon.setImageResource(getIconDrawableId(iconValue));
                        ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                    }
                } catch (Exception e) {
                    ivIcon.setImageResource(getIconDrawableId(iconValue));
                    ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                }
            } else if (iconValue != null && iconValue.startsWith("svgpath:")) {
                try {
                    android.graphics.Bitmap cachedBitmap = iconCache.get(iconValue);
                    if (cachedBitmap != null) {
                        ivIcon.setImageBitmap(cachedBitmap);
                        ivIcon.setImageTintList(null);
                        return;
                    }
                    String pathData = iconValue.substring(8);
                    android.graphics.Path path = androidx.core.graphics.PathParser.createPathFromPathData(pathData);
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                    android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(Color.WHITE);
                    paint.setStyle(android.graphics.Paint.Style.FILL);

                    android.graphics.RectF bounds = new android.graphics.RectF();
                    path.computeBounds(bounds, true);
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        android.graphics.Matrix matrix = new android.graphics.Matrix();
                        matrix.setRectToRect(bounds, new android.graphics.RectF(16, 16, 112, 112), android.graphics.Matrix.ScaleToFit.CENTER);
                        path.transform(matrix);
                    }

                    canvas.drawPath(path, paint);
                    iconCache.put(iconValue, bmp);
                    ivIcon.setImageBitmap(bmp);
                    ivIcon.setImageTintList(null);
                } catch (Exception e) {
                    ivIcon.setImageResource(getIconDrawableId(iconValue));
                    ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                }
            } else if (iconValue != null && (iconValue.startsWith("http://") || iconValue.startsWith("https://"))) {
                try {
                    android.graphics.Bitmap cachedBitmap = iconCache.get(iconValue);
                    if (cachedBitmap != null) {
                        ivIcon.setImageBitmap(cachedBitmap);
                        ivIcon.setImageTintList(null);
                    } else {
                        ivIcon.setImageResource(getIconDrawableId(iconValue));
                        ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                        final String urlStr = iconValue;
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    java.net.URL url = new java.net.URL(urlStr);
                                    final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(url.openConnection().getInputStream());
                                    if (bmp != null) {
                                        iconCache.put(urlStr, bmp);
                                        if (getActivity() != null) {
                                            getActivity().runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    ivIcon.setImageBitmap(bmp);
                                                    ivIcon.setImageTintList(null);
                                                }
                                            });
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }).start();
                    }
                } catch (Exception e) {
                    ivIcon.setImageResource(getIconDrawableId(iconValue));
                    ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                }
            } else {
                ivIcon.setImageResource(getIconDrawableId(iconValue));
                ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            }
        }

        private View createDynamicGrid(String[] parts, int squareSize) {
            android.widget.LinearLayout root = new android.widget.LinearLayout(getContext());
            root.setTag("dynamic_url_grid");
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            int gridSizePx = squareSize > 0 ? (int) (squareSize * 0.65f) : dpToPx(isTablet() ? 84 : 64);
            android.widget.FrameLayout.LayoutParams rootLp = new android.widget.FrameLayout.LayoutParams(
                    gridSizePx, gridSizePx, android.view.Gravity.CENTER
            );
            root.setLayoutParams(rootLp);
            root.setGravity(android.view.Gravity.CENTER);

            android.widget.LinearLayout row1 = new android.widget.LinearLayout(getContext());
            row1.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row1.setGravity(android.view.Gravity.CENTER);
            android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
            );
            row1.setLayoutParams(rowLp);

            android.widget.LinearLayout row2 = new android.widget.LinearLayout(getContext());
            row2.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row2.setGravity(android.view.Gravity.CENTER);
            row2.setLayoutParams(rowLp);

            int size = Math.min(parts.length, 4);
            for (int i = 0; i < size; i++) {
                ImageView iv = new ImageView(getContext());
                android.widget.LinearLayout.LayoutParams ivLp = new android.widget.LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f
                );
                ivLp.setMargins(dpToPx(1), dpToPx(1), dpToPx(1), dpToPx(1));
                iv.setLayoutParams(ivLp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);

                String part = parts[i].trim();
                renderSingleIcon(iv, part);

                if (i < 2) {
                    row1.addView(iv);
                } else {
                    row2.addView(iv);
                }
            }

            root.addView(row1);
            if (size > 2) {
                root.addView(row2);
            }
            return root;
        }

        private final java.util.Map<String, android.graphics.drawable.Drawable.ConstantState> keycapDrawableCache = new java.util.HashMap<>();

        private android.graphics.drawable.Drawable getFuturisticKeycapDrawable(String colorHex, int squareSize) {
            String cacheKey = (colorHex != null ? colorHex.trim().toUpperCase() : "DEF") + "_" + squareSize;
            android.graphics.drawable.Drawable.ConstantState cachedState = keycapDrawableCache.get(cacheKey);
            if (cachedState != null) {
                return cachedState.newDrawable();
            }

            float superellipseRadius = squareSize > 0 ? (squareSize * 0.28f) : dpToPx(28);

            int strokeColor = Color.TRANSPARENT;
            int strokeWidth = 0;
            if (colorHex != null && !colorHex.trim().isEmpty() 
                    && !colorHex.equalsIgnoreCase("#FFFFFF") 
                    && !colorHex.equalsIgnoreCase("#000000") 
                    && !colorHex.equalsIgnoreCase("#12121A")
                    && !colorHex.equalsIgnoreCase("#1E3A5F")
                    && !colorHex.equalsIgnoreCase("#6366F1")) {
                try {
                    strokeColor = Color.parseColor(colorHex.trim());
                    strokeWidth = dpToPx(2);
                } catch (Exception ignored) {}
            }

            GradientDrawable defaultState = new GradientDrawable();
            defaultState.setColors(new int[]{Color.parseColor("#12121A"), Color.parseColor("#06060A")});
            defaultState.setOrientation(GradientDrawable.Orientation.TL_BR);
            defaultState.setCornerRadius(superellipseRadius);
            if (strokeWidth > 0) {
                defaultState.setStroke(strokeWidth, strokeColor);
            } else {
                defaultState.setStroke(0, Color.TRANSPARENT);
            }

            GradientDrawable pressedState = new GradientDrawable();
            pressedState.setColors(new int[]{Color.parseColor("#1C1C26"), Color.parseColor("#0C0C12")});
            pressedState.setOrientation(GradientDrawable.Orientation.TL_BR);
            pressedState.setCornerRadius(superellipseRadius);
            if (strokeWidth > 0) {
                pressedState.setStroke(strokeWidth, strokeColor);
            } else {
                pressedState.setStroke(0, Color.TRANSPARENT);
            }

            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, pressedState);
            sld.addState(new int[]{}, defaultState);

            android.graphics.drawable.Drawable.ConstantState cs = sld.getConstantState();
            if (cs != null) {
                keycapDrawableCache.put(cacheKey, cs);
            }
            return sld;
        }

        private int dpToPx(float dp) {
            if (getContext() == null) return (int) dp;
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        private int getIconDrawableId(String iconName) {
            if (iconName == null) return R.drawable.ic_default;
            switch (iconName.toLowerCase()) {
                case "locked_profile":
                    return R.drawable.ic_locked_profile;
                case "play":
                case "media_play":
                    return R.drawable.ic_play;
                case "media_pause":
                    return R.drawable.ic_pause;
                case "media_play_pause":
                    return R.drawable.ic_play;
                case "folder":
                    return R.drawable.ic_folder;
                case "app_default":
                    return R.drawable.ic_app_default;
                case "settings":
                    return R.drawable.ic_settings;
                case "keyboard":
                case "hotkey":
                    return R.drawable.ic_keyboard;
                case "volume_up":
                    return R.drawable.ic_volume_up;
                case "volume_down":
                    return R.drawable.ic_volume_down;
                case "volume_mute":
                    return R.drawable.ic_volume_mute;
                case "brightness":
                case "brightness_up":
                    return R.drawable.ic_brightness_up;
                case "brightness_down":
                    return R.drawable.ic_brightness_down;
                case "media_next":
                    return R.drawable.ic_media_next;
                case "media_prev":
                    return R.drawable.ic_media_prev;
                case "media_forward_10":
                    return R.drawable.ic_media_forward_10;
                case "media_backward_10":
                    return R.drawable.ic_media_backward_10;
                case "web":
                case "url":
                    return R.drawable.ic_web;
                case "mic":
                    return R.drawable.ic_mic;
                case "camera":
                    return R.drawable.ic_camera;
                case "code":
                    return R.drawable.ic_code;
                case "rocket":
                    return R.drawable.ic_rocket;
                case "pc_shutdown":
                    return R.drawable.ic_power;
                case "pc_sleep":
                    return R.drawable.ic_sleep;
                case "pc_lock":
                    return R.drawable.ic_lock;
                case "pc_restart":
                    return R.drawable.ic_restart;
                case "perf_cpu":
                    return R.drawable.ic_perf_cpu;
                case "perf_gpu":
                    return R.drawable.ic_perf_gpu;
                case "perf_ram":
                    return R.drawable.ic_perf_ram;
                case "perf_temp":
                    return R.drawable.ic_perf_temp;
                case "perf_wifi":
                    return R.drawable.ic_perf_wifi;
                case "wifi":
                    return R.drawable.ic_wifi;
                case "wifi_off":
                    return R.drawable.ic_wifi_off;
                case "bluetooth":
                    return R.drawable.ic_bluetooth;
                case "bluetooth_off":
                    return R.drawable.ic_bluetooth_off;
                case "screen_record":
                    return R.drawable.ic_screen_record;
                case "screenshot":
                    return R.drawable.ic_camera;
                case "home_screen":
                    return R.drawable.ic_home;
                case "close_all_apps":
                    return R.drawable.ic_close;
                case "presentation_mode":
                case "presentation":
                    return R.drawable.ic_presentation;
                case "close":
                    return R.drawable.ic_close;
                case "laser":
                    return R.drawable.ic_laser;
                case "spotlight":
                    return R.drawable.ic_spotlight;
                case "arrow_left":
                    return R.drawable.ic_arrow_left;
                case "arrow_right":
                    return R.drawable.ic_arrow_right;
                default:
                    return R.drawable.ic_default;
            }
        }

        private class ViewHolder {
            View cardView;
            TextView tvTitle;
            ImageView ivIcon;
            View btnContentContainer;
        }
    }

    private void updateLayoutForOrientation(int orientation) {
        boolean isLandscape = (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        boolean isTabletDevice = isTablet();
        boolean swipeHorizontal = isTabletDevice || isLandscape;

        if (viewPager != null) {
            viewPager.setOrientation(swipeHorizontal ? ViewPager2.ORIENTATION_HORIZONTAL : ViewPager2.ORIENTATION_VERTICAL);
            viewPager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
        if (layoutDots != null) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp = 
                (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) layoutDots.getLayoutParams();
            if (swipeHorizontal) {
                layoutDots.setOrientation(LinearLayout.HORIZONTAL);
                
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
                
                lp.bottomMargin = dpToPx(12);
                lp.rightMargin = 0;
                lp.leftMargin = 0;
                lp.topMargin = 0;
            } else {
                layoutDots.setOrientation(LinearLayout.VERTICAL);
                
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET;
                
                lp.rightMargin = dpToPx(16);
                lp.leftMargin = 0;
                lp.bottomMargin = 0;
                lp.topMargin = 0;
            }
            layoutDots.setLayoutParams(lp);
        }
        if (pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
        }
        if (networkClient.isConnected()) {
            int cols = isTabletDevice ? (isLandscape ? 5 : 3) : (isLandscape ? 4 : 2);
            int rows = isTabletDevice ? (isLandscape ? 3 : 5) : (isLandscape ? 2 : 4);
            networkClient.sendLayoutChange(cols, rows);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayoutForOrientation(newConfig.orientation);
        if (presentationDialog != null && presentationDialog.isShowing()) {
            try {
                presentationDialog.dismiss();
            } catch (Exception e) {}
            enterPresentationMode();
        }
    }

    private void showSettingsDialog() {
        if (!isAdded()) return;
        
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_settings, null);
        
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        View btnChangeName = dialogView.findViewById(R.id.option_change_name);
        View btnChangeProfile = dialogView.findViewById(R.id.option_change_profile);
        View btnClearCache = dialogView.findViewById(R.id.option_clear_cache);
        View btnDisconnect = dialogView.findViewById(R.id.option_disconnect);
        View btnCancel = dialogView.findViewById(R.id.option_cancel);

        // Bind dynamic device name to option summary
        final android.content.SharedPreferences prefs = requireContext().getSharedPreferences("SwiftDockPrefs", android.content.Context.MODE_PRIVATE);
        String currentMobileName = prefs.getString("mobile_name", android.os.Build.MODEL);
        TextView tvDeviceSummary = dialogView.findViewById(R.id.tv_device_name_summary);
        if (tvDeviceSummary != null) {
            tvDeviceSummary.setText("Name: " + currentMobileName);
        }

        // Bind current profile name to profile summary
        TextView tvProfileSummary = dialogView.findViewById(R.id.tv_profile_summary);
        if (tvProfileSummary != null) {
            List<NetworkClient.ProfileInfo> profiles = networkClient.getCachedProfiles();
            String activeId = networkClient.getCurrentProfileId();
            for (NetworkClient.ProfileInfo p : profiles) {
                if (p.getId().equals(activeId)) {
                    tvProfileSummary.setText(p.getName());
                    break;
                }
            }
        }

        btnChangeName.setOnClickListener(v -> {
            dialog.dismiss();
            showChangeNameDialog();
        });

        btnChangeProfile.setOnClickListener(v -> {
            dialog.dismiss();
            showChangeProfileDialog();
        });

        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                dialog.dismiss();
                Context context = getContext();
                if (context != null) {
                    boolean success = NetworkClient.clearAppCache(context);
                    if (success) {
                        Toast.makeText(context, "Cache cleared successfully!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "Failed to clear cache.", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        btnDisconnect.setOnClickListener(v -> {
            dialog.dismiss();
            showDisconnectConfirmation();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void showChangeProfileDialog() {
        if (!isAdded()) return;

        List<NetworkClient.ProfileInfo> profiles = networkClient.getCachedProfiles();
        String activeId = networkClient.getCurrentProfileId();

        if (profiles.isEmpty()) {
            Toast.makeText(getContext(), "No profiles available", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_change_profile, null);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout container = dialogView.findViewById(R.id.profiles_container);

        for (NetworkClient.ProfileInfo profile : profiles) {
            View itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_profile_option, container, false);
            
            TextView nameView = itemView.findViewById(R.id.profile_name);
            ImageView lockView = itemView.findViewById(R.id.profile_lock_icon);
            ImageView checkView = itemView.findViewById(R.id.profile_check);
            
            nameView.setText(profile.getName());
            
            if (profile.isLocked()) {
                if (lockView != null) lockView.setVisibility(View.VISIBLE);
            } else {
                if (lockView != null) lockView.setVisibility(View.GONE);
            }
            
            if (profile != null && profile.getId() != null && profile.getId().equals(activeId)) {
                checkView.setVisibility(View.VISIBLE);
            } else {
                checkView.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (profile.isLocked()) {
                    dialog.dismiss();
                    showPinUnlockDialog(profile);
                } else {
                    networkClient.sendChangeProfile(profile.getId());
                    dialog.dismiss();
                    Toast.makeText(getContext(), "Switching to " + profile.getName(), Toast.LENGTH_SHORT).show();
                }
            });

            container.addView(itemView);
        }

        dialog.show();
    }

    private void showChangeNameDialog() {
        if (!isAdded()) return;
        final android.content.SharedPreferences prefs = requireContext().getSharedPreferences("SwiftDockPrefs", android.content.Context.MODE_PRIVATE);
        String currentMobileName = prefs.getString("mobile_name", android.os.Build.MODEL);

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_change_name, null);
        
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        final android.widget.EditText input = dialogView.findViewById(R.id.edit_mobile_name);
        if (input != null) {
            input.setText(currentMobileName);
            input.setSelection(currentMobileName.length());
        }

        View btnCancel = dialogView.findViewById(R.id.btn_cancel);
        View btnSave = dialogView.findViewById(R.id.btn_save);

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnSave != null && input != null) {
            btnSave.setOnClickListener(v -> {
                String newName = input.getText().toString().trim();
                if (!android.text.TextUtils.isEmpty(newName)) {
                    prefs.edit().putString("mobile_name", newName).apply();
                    Toast.makeText(getContext(), "Device name saved. It will apply on next connection.", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(getContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show();
                }
            });
        }

        dialog.show();
    }

    private void showDisconnectConfirmation() {
        if (!isAdded()) return;
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null);
        
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = dialogView.findViewById(R.id.dialog_confirm_title);
        TextView tvMessage = dialogView.findViewById(R.id.dialog_confirm_message);
        View btnNo = dialogView.findViewById(R.id.btn_confirm_no);
        Button btnYes = dialogView.findViewById(R.id.btn_confirm_yes);

        if (tvTitle != null) tvTitle.setText("Confirm Disconnect");
        if (tvMessage != null) tvMessage.setText("Are you sure you want to disconnect from SwiftDock?");
        
        if (btnYes != null) {
            btnYes.setBackgroundResource(R.drawable.button_danger);
            btnYes.setText("Disconnect");
            btnYes.setOnClickListener(v -> {
                dialog.dismiss();
                isUserDisconnecting = true;
                networkClient.disconnect();
                navigateToConnectScreen();
            });
        }

        if (btnNo != null) {
            btnNo.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }


    private boolean isActionRepeatable(ShortcutButton btn) {
        if (btn == null) return false;
        String type = btn.getActionType();
        String data = btn.getActionData();
        if ("System".equalsIgnoreCase(type) && data != null) {
            String dataLower = data.toLowerCase();
            return dataLower.equals("volume_up") ||
                   dataLower.equals("volume_down") ||
                   dataLower.equals("brightness_up") ||
                   dataLower.equals("brightness_down");
        }
        return false;
    }

    private int dpToPx(float dp) {
        if (getContext() == null) return (int) dp;
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private boolean isTablet() {
        if (getContext() == null) return false;
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }
    private android.graphics.drawable.Drawable getFuturisticKeycapDrawable(String colorHex, int squareSize) {
        float superellipseRadius = squareSize > 0 ? (squareSize * 0.28f) : dpToPx(28);

        int strokeColor = Color.TRANSPARENT;
        int strokeWidth = 0;
        if (colorHex != null && !colorHex.trim().isEmpty() 
                && !colorHex.equalsIgnoreCase("#FFFFFF") 
                && !colorHex.equalsIgnoreCase("#000000") 
                && !colorHex.equalsIgnoreCase("#12121A")
                && !colorHex.equalsIgnoreCase("#1E3A5F")
                && !colorHex.equalsIgnoreCase("#6366F1")) {
            try {
                strokeColor = Color.parseColor(colorHex.trim());
                strokeWidth = dpToPx(2);
            } catch (Exception ignored) {}
        }

        GradientDrawable defaultState = new GradientDrawable();
        defaultState.setColors(new int[]{Color.parseColor("#12121A"), Color.parseColor("#06060A")});
        defaultState.setOrientation(GradientDrawable.Orientation.TL_BR);
        defaultState.setCornerRadius(superellipseRadius);
        if (strokeWidth > 0) {
            defaultState.setStroke(strokeWidth, strokeColor);
        } else {
            defaultState.setStroke(0, Color.TRANSPARENT);
        }

        GradientDrawable pressedState = new GradientDrawable();
        pressedState.setColors(new int[]{Color.parseColor("#1C1C26"), Color.parseColor("#0C0C12")});
        pressedState.setOrientation(GradientDrawable.Orientation.TL_BR);
        pressedState.setCornerRadius(superellipseRadius);
        if (strokeWidth > 0) {
            pressedState.setStroke(strokeWidth, strokeColor);
        } else {
            pressedState.setStroke(0, Color.TRANSPARENT);
        }

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressedState);
        sld.addState(new int[]{}, defaultState);
        return sld;
    }

    private View createKeycapButton(int iconRes, String labelText, View.OnClickListener onClick, int squareSize) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.item_presentation_button, null, false);
        View cardView = view.findViewById(R.id.card_view);
        TextView tvTitle = view.findViewById(R.id.btn_title);
        ImageView ivIcon = view.findViewById(R.id.btn_icon);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(squareSize, squareSize);
        lp.setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        view.setLayoutParams(lp);

        android.widget.FrameLayout.LayoutParams cardLp = (android.widget.FrameLayout.LayoutParams) cardView.getLayoutParams();
        if (cardLp == null) {
            cardLp = new android.widget.FrameLayout.LayoutParams(squareSize, squareSize);
        } else {
            cardLp.width = squareSize;
            cardLp.height = squareSize;
        }
        cardLp.gravity = android.view.Gravity.CENTER;
        cardView.setLayoutParams(cardLp);

        cardView.setBackground(getFuturisticKeycapDrawable("#12121A", squareSize));

        if (ivIcon != null) {
            ivIcon.setVisibility(View.VISIBLE);
            ivIcon.setImageResource(iconRes);
            ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        }

        if (tvTitle != null) {
            tvTitle.setVisibility(View.VISIBLE);
            tvTitle.setText(labelText);
            tvTitle.setTextColor(Color.parseColor("#9CA3AF"));
        }

        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0f;
            private float downY = 0f;

            private void animateDown() {
                cardView.animate().cancel();
                cardView.animate()
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .translationY(dpToPx(3.5f))
                        .alpha(0.85f)
                        .setDuration(70)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .start();
            }

            private void animateUp() {
                cardView.animate().cancel();
                cardView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationY(0f)
                        .alpha(1.0f)
                        .setDuration(120)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                        .start();
            }

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        try {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                        } catch (Exception e) {}
                        animateDown();
                        return true;

                    case android.view.MotionEvent.ACTION_UP:
                        animateUp();
                        float deltaX = Math.abs(event.getRawX() - downX);
                        float deltaY = Math.abs(event.getRawY() - downY);
                        if (deltaX < dpToPx(10) && deltaY < dpToPx(10)) {
                            if (onClick != null) {
                                onClick.onClick(v);
                            }
                        }
                        return true;

                    case android.view.MotionEvent.ACTION_CANCEL:
                        animateUp();
                        return true;
                }
                return false;
            }
        });

        return view;
    }

    private void enterPresentationMode() {
        if (getActivity() == null || getContext() == null) return;

        // Initialize sensor manager and fused motion sensors (Gyroscope + Accelerometer + Rotation Vector)
        if (sensorManager == null) {
            sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (rotationVectorSensor == null) {
                    rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                }
                gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
                accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }

        // Retrieve true physical screen dimensions for current device orientation
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                requireActivity().getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            } catch (Exception e) {
                dm = getResources().getDisplayMetrics();
            }
        } else {
            dm = getResources().getDisplayMetrics();
        }

        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        boolean isLandscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (screenW > screenH) {
            isLandscape = true;
        }

        float density = dm.density;
        int gap = (int) (12 * density);       // 12dp gap
        int margin = (int) (16 * density);    // 16dp margin

        int numColumns = isLandscape ? 4 : 2;
        int numRows = isLandscape ? 2 : 3;

        int availW = screenW - (2 * margin) - ((numColumns - 1) * gap);
        int availH = screenH - (2 * margin) - ((numRows - 1) * gap);
        int cWidth = availW / numColumns;
        int cHeight = availH / numRows;
        int squareSize = Math.min(cWidth, cHeight);
        if (squareSize <= 0) squareSize = dpToPx(80);

        // Build the dialog
        presentationDialog = new Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_presentation_mode, null, false);
        presentationDialog.setContentView(dialogView);
        presentationDialog.setCancelable(false);

        // Configure Dialog Window for Edge-to-Edge Fullscreen & Immersive Sticky Mode (Hide Gesture/Nav Bar)
        Window window = presentationDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.black);

            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            hideDialogSystemBars(window);
        }

        LinearLayout container = dialogView.findViewById(R.id.presGridContainer);
        populatePresContainer(container);

        presentationDialog.setOnDismissListener(dialog -> {
            stopGyroSensor();
            isPresOrientationLocked = false;
            if (getActivity() != null) {
                try {
                    getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                } catch (Exception e) {}
            }
        });

        presentationDialog.setOnShowListener(dialog -> {
            if (presentationDialog != null && presentationDialog.getWindow() != null) {
                hideDialogSystemBars(presentationDialog.getWindow());
            }
        });

        presentationDialog.show();
    }

    private void refreshPresDialogGrid() {
        if (presentationDialog != null && presentationDialog.isShowing()) {
            try {
                LinearLayout container = presentationDialog.findViewById(R.id.presGridContainer);
                if (container != null) {
                    container.post(() -> {
                        try {
                            if (presentationDialog != null && presentationDialog.isShowing() && isAdded()) {
                                populatePresContainer(container);
                            }
                        } catch (Exception e) {}
                    });
                }
            } catch (Exception e) {}
        }
    }

    private void populatePresContainer(LinearLayout container) {
        Context context = getContext();
        android.app.Activity activity = getActivity();
        if (container == null || context == null || activity == null) return;
        container.removeAllViews();

        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            } catch (Exception e) {
                dm = context.getResources().getDisplayMetrics();
            }
        } else {
            dm = context.getResources().getDisplayMetrics();
        }

        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        boolean isLandscape = context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (screenW > screenH) {
            isLandscape = true;
        }

        float density = dm.density;
        int gap = (int) (12 * density);       // 12dp gap
        int margin = (int) (16 * density);    // 16dp margin

        int numColumns = isLandscape ? 4 : 2;
        int numRows = isLandscape ? 2 : 3;

        int availW = screenW - (2 * margin) - ((numColumns - 1) * gap);
        int availH = screenH - (2 * margin) - ((numRows - 1) * gap);
        int cWidth = availW / numColumns;
        int cHeight = availH / numRows;
        int squareSize = Math.min(cWidth, cHeight);
        if (squareSize <= 0) squareSize = dpToPx(80);

        // 6 Button definitions in requested order: {iconRes, idx}
        int[][] buttonDefs = {
            {R.drawable.ic_spotlight, 0},        // 1. Spotlight
            {R.drawable.ic_laser, 1},            // 2. Laser Pointer
            {R.drawable.ic_arrow_left, 2},       // 3. Left Arrow
            {R.drawable.ic_arrow_right, 3},      // 4. Right Arrow
            {R.drawable.ic_screen_rotation, 4},  // 5. Orientation Lock
            {R.drawable.ic_close, 5},            // 6. Exit
        };

        int totalButtons = 6;
        int buttonIndex = 0;

        for (int r = 0; r < numRows; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r < numRows - 1) {
                rowLp.bottomMargin = gap;
            }
            row.setLayoutParams(rowLp);

            for (int c = 0; c < numColumns; c++) {
                View btn;
                if (buttonIndex < totalButtons) {
                    int iconRes = buttonDefs[buttonIndex][0];
                    int idx = buttonDefs[buttonIndex][1];
                    View.OnClickListener listener = null;
                    String bgHex = "#12121A";

                    switch (idx) {
                        case 0: // 1. Spotlight
                            if ("spotlight".equals(activePresentationGyroMode)) bgHex = "#2563EB";
                            listener = v -> {
                                Context ctx = getContext();
                                if (!isPresOrientationLocked) {
                                    if (ctx != null) {
                                        Toast.makeText(ctx, "Please lock orientation first!", Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if ("spotlight".equals(activePresentationGyroMode)) {
                                    stopGyroSensor();
                                    if (networkClient != null) networkClient.sendPresentationCmd("exit");
                                } else {
                                    startGyroSensor("spotlight");
                                    if (networkClient != null) networkClient.sendPresentationCmd("spotlight");
                                }
                                refreshPresDialogGrid();
                            };
                            break;

                        case 1: // 2. Laser Pointer
                            if ("laser".equals(activePresentationGyroMode)) bgHex = "#DC2626";
                            listener = v -> {
                                Context ctx = getContext();
                                if (!isPresOrientationLocked) {
                                    if (ctx != null) {
                                        Toast.makeText(ctx, "Please lock orientation first!", Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if ("laser".equals(activePresentationGyroMode)) {
                                    stopGyroSensor();
                                    if (networkClient != null) networkClient.sendPresentationCmd("exit");
                                } else {
                                    startGyroSensor("laser");
                                    if (networkClient != null) networkClient.sendPresentationCmd("laser");
                                }
                                refreshPresDialogGrid();
                            };
                            break;

                        case 2: // 3. Left Arrow (Previous Slide)
                            listener = v -> {
                                if (networkClient != null) networkClient.sendPresentationCmd("prev_slide");
                            };
                            break;

                        case 3: // 4. Right Arrow (Next Slide)
                            listener = v -> {
                                if (networkClient != null) networkClient.sendPresentationCmd("next_slide");
                            };
                            break;

                        case 4: // 5. Orientation Lock (Dynamic Icon & Color)
                            if (isPresOrientationLocked) {
                                iconRes = R.drawable.ic_lock;
                            } else {
                                iconRes = R.drawable.ic_screen_rotation;
                            }
                            listener = v -> {
                                isPresOrientationLocked = !isPresOrientationLocked;
                                android.app.Activity act = getActivity();
                                Context ctx = getContext();
                                if (act != null && ctx != null) {
                                    if (isPresOrientationLocked) {
                                        int currentOrient = ctx.getResources().getConfiguration().orientation;
                                        if (currentOrient == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                                        } else {
                                            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                                        }
                                        Toast.makeText(ctx, "Orientation Locked", Toast.LENGTH_SHORT).show();
                                    } else {
                                        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                                        Toast.makeText(ctx, "Orientation Unlocked", Toast.LENGTH_SHORT).show();
                                    }
                                }
                                refreshPresDialogGrid();
                            };
                            break;

                        case 5: // 6. Exit
                            listener = v -> exitPresentationMode();
                            break;
                    }
                    btn = createPresButton(context, iconRes, squareSize, bgHex, listener);
                } else {
                    btn = new View(context);
                    btn.setVisibility(View.INVISIBLE);
                }

                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(squareSize, squareSize);
                if (c > 0) {
                    btnLp.leftMargin = gap;
                }
                btn.setLayoutParams(btnLp);
                row.addView(btn);

                buttonIndex++;
            }

            container.addView(row);
        }
    }

    private void hideDialogSystemBars(Window window) {
        if (window == null) return;
        try {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Exception e) {}

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        try {
            window.getDecorView().setSystemUiVisibility(flags);
            window.getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    window.getDecorView().setSystemUiVisibility(flags);
                    try {
                        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(window, window.getDecorView());
                        if (ctrl != null) {
                            ctrl.hide(WindowInsetsCompat.Type.systemBars());
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {}
    }

    private View createPresButton(Context context, int iconRes, int size, String bgColorHex, View.OnClickListener onClick) {
        if (context == null) context = requireContext();
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(context);

        // Card background — same keycap as main dock with dynamic color state
        android.widget.FrameLayout card = new android.widget.FrameLayout(context);
        android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(size, size);
        cardLp.gravity = android.view.Gravity.CENTER;
        card.setLayoutParams(cardLp);
        card.setBackground(getFuturisticKeycapDrawable(bgColorHex != null ? bgColorHex : "#12121A", size));

        // Icon — same 52% ratio as main dock
        ImageView icon = new ImageView(context);
        int iconPx = (int) (size * 0.52f);
        android.widget.FrameLayout.LayoutParams iconLp = new android.widget.FrameLayout.LayoutParams(iconPx, iconPx);
        iconLp.gravity = android.view.Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));

        card.addView(icon);
        wrapper.addView(card);

        // Same touch animation as main dock
        wrapper.setOnTouchListener(new View.OnTouchListener() {
            private float downX = 0f, downY = 0f;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        try { v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING); } catch (Exception e) {}
                        card.animate().cancel();
                        card.animate().scaleX(0.90f).scaleY(0.90f).translationY(dpToPx(3.5f))
                                .alpha(0.85f).setDuration(70)
                                .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        card.animate().cancel();
                        card.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
                                .setDuration(120)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                        if (Math.abs(event.getRawX() - downX) < dpToPx(10)
                                && Math.abs(event.getRawY() - downY) < dpToPx(10)) {
                            if (onClick != null) onClick.onClick(v);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        card.animate().cancel();
                        card.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f)
                                .setDuration(120)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
                        return true;
                }
                return false;
            }
        });

        return wrapper;
    }

    private void startGyroSensor(String mode) {
        activePresentationGyroMode = mode;
        hasFirstOrientation = false;
        hasFirstAccel = false;
        smoothedGyroX = 0f;
        smoothedGyroY = 0f;

        Context context = getContext();
        if (sensorManager == null && context != null) {
            try {
                sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            } catch (Exception ignored) {}
        }

        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(gyroListener);
            } catch (Exception ignored) {}

            Sensor targetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (targetSensor == null) {
                targetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
                if (targetSensor == null) {
                    targetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                }
                if (targetSensor == null) {
                    targetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
            }

            if (targetSensor != null) {
                try {
                    sensorManager.registerListener(gyroListener, targetSensor, SensorManager.SENSOR_DELAY_GAME);
                } catch (Exception e) {
                    try {
                        sensorManager.registerListener(gyroListener, targetSensor, SensorManager.SENSOR_DELAY_NORMAL);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void stopGyroSensor() {
        activePresentationGyroMode = null;
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(gyroListener);
            } catch (Exception ignored) {}
        }
    }

    private void exitPresentationMode() {
        stopGyroSensor();
        if (networkClient != null) {
            networkClient.sendPresentationCmd("exit");
        }
        if (presentationDialog != null) {
            if (presentationDialog.isShowing()) {
                try {
                    presentationDialog.dismiss();
                } catch (Exception e) {}
            }
            presentationDialog = null;
        }
        isPresOrientationLocked = false;
        if (getActivity() != null) {
            try {
                getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            } catch (Exception e) {}
        }
    }

    private int getPageSize() {
        return isTablet() ? 15 : 8;
    }
}
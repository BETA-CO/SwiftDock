package com.example.swiftdock;

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
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class SecondFragment extends Fragment implements NetworkClient.NetworkListener {

    private ViewPager2 viewPager;
    private LinearLayout layoutDots;
    private TextView tvStatus;
    private ConstraintLayout layoutReconnectingOverlay;
    
    private NetworkClient networkClient;
    private List<ShortcutButton> buttonsList = new ArrayList<>();
    private PagerAdapter pagerAdapter;

    private boolean isUserDisconnecting = false;
    private boolean isReconnecting = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || isUserDisconnecting) return;
            if (!networkClient.isConnected()) {
                SharedPreferences prefs = requireContext().getSharedPreferences("SwiftDockPrefs", Context.MODE_PRIVATE);
                String savedIp = prefs.getString("paired_ip", "");
                String savedToken = prefs.getString("paired_token", "");
                String mobileName = prefs.getString("mobile_name", android.os.Build.MODEL);

                if (!android.text.TextUtils.isEmpty(savedIp) && !android.text.TextUtils.isEmpty(savedToken)) {
                    networkClient.reconnect(savedIp, savedToken, mobileName);
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

        // Force Landscape orientation during dashboard grid view
        requireActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        viewPager = view.findViewById(R.id.view_pager);
        layoutDots = view.findViewById(R.id.layout_dots);
        tvStatus = view.findViewById(R.id.tv_status);

        networkClient = NetworkClient.getInstance();
        networkClient.addListener(this);

        setButtonsList(networkClient.getCachedButtons());
        
        // Setup Pager
        pagerAdapter = new PagerAdapter();
        viewPager.setAdapter(pagerAdapter);

        // Dot indicators initialization
        int pageCount = getPageCount();
        setupDots(pageCount);
        updateDots(0);

        // Track page selection changes to update dot indicators
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateDots(position);
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
        settingsBtn.setColor("#6366F1");
        settingsBtn.setIcon("settings");
        buttonsList.add(settingsBtn);
        
        if (buttons != null) {
            buttonsList.addAll(buttons);
        }
    }

    private int getPageCount() {
        if (buttonsList.isEmpty()) return 1;
        return (int) Math.ceil(buttonsList.size() / 8.0);
    }

    private void setupDots(int count) {
        layoutDots.removeAllViews();
        if (count <= 1) return; // No need for dots if there is only 1 page

        for (int i = 0; i < count; i++) {
            ImageView dot = new ImageView(getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(6, 0, 6, 0);
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
        networkClient.removeListener(this);
    }

    // Network Callbacks
    @Override
    public void onServerDiscovered(String ip, int port, String hostname) {}

    @Override
    public void onConnectionSuccess(String token) {
        if (!isAdded()) return;
        if (isReconnecting) {
            isReconnecting = false;
            reconnectHandler.removeCallbacks(reconnectRunnable);
            if (layoutReconnectingOverlay != null) {
                layoutReconnectingOverlay.setVisibility(View.GONE);
            }
            Toast.makeText(getContext(), "Reconnected!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        if (!isAdded()) return;
        if (isReconnecting) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectHandler.postDelayed(reconnectRunnable, 3000);
        }
    }

    @Override
    public void onDisconnected() {
        if (!isAdded()) return;
        if (isUserDisconnecting) {
            navigateToConnectScreen();
        } else {
            isReconnecting = true;
            if (layoutReconnectingOverlay != null) {
                layoutReconnectingOverlay.setVisibility(View.VISIBLE);
            }
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectHandler.post(reconnectRunnable);
        }
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
    public void onPerformanceUpdated(int cpu, int gpu, int ram, int temp, String wifi) {
        if (!isAdded()) return;
        if (pagerAdapter != null) {
            pagerAdapter.notifyDataSetChanged();
        }
    }

    // Pager Adapter for swiping pages
    private class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.PageViewHolder> {

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_page, parent, false);
            return new PageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            int start = position * 8;
            int end = Math.min(start + 8, buttonsList.size());
            
            List<ShortcutButton> pageButtons = new ArrayList<>();
            if (start < buttonsList.size()) {
                pageButtons.addAll(buttonsList.subList(start, end));
            }

            GridAdapter gridAdapter = new GridAdapter(pageButtons);
            holder.gridView.setAdapter(gridAdapter);

            // Handle grid button taps
            holder.gridView.setOnItemClickListener((parent, view, gridPos, id) -> {
                int absolutePos = (position * 8) + gridPos;
                if (absolutePos < buttonsList.size()) {
                    ShortcutButton btn = buttonsList.get(absolutePos);
                    if ("SWIFTDOCK_INTERNAL_SETTINGS".equals(btn.getId())) {
                        showSettingsDialog();
                    } else if ("System".equalsIgnoreCase(btn.getActionType()) && "pc_shutdown".equalsIgnoreCase(btn.getActionData())) {
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Confirm Shutdown")
                            .setMessage("Are you sure you want to shut down the computer?")
                            .setPositiveButton("Yes", (dialog, which) -> {
                                networkClient.sendButtonPress(btn.getId());
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    networkClient.disconnect();
                                    if (getActivity() != null) {
                                        getActivity().finishAffinity();
                                    }
                                }, 150);
                            })
                            .setNegativeButton("No", null)
                            .show();
                    } else {
                        networkClient.sendButtonPress(btn.getId());
                    }
                }
            });
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
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // Set height to exactly half of available parent height (since 2 rows), accounting for padding and cell spacing
            int parentHeight = parent.getHeight();
            if (parentHeight > 0) {
                int verticalSpacing = (int) (12 * getResources().getDisplayMetrics().density);
                int padding = (int) (16 * getResources().getDisplayMetrics().density);
                int availableHeight = parentHeight - (padding * 2) - verticalSpacing;

                ViewGroup.LayoutParams lp = convertView.getLayoutParams();
                if (lp == null) {
                    lp = new GridView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, availableHeight / 2);
                } else {
                    lp.height = availableHeight / 2;
                }
                convertView.setLayoutParams(lp);

                // Make the card view fill the parent cell
                ViewGroup.LayoutParams cardLp = holder.cardView.getLayoutParams();
                if (cardLp != null) {
                    cardLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    holder.cardView.setLayoutParams(cardLp);
                }
            }

            ShortcutButton btn = pageButtons.get(position);
            boolean isPerfBtn = "System".equalsIgnoreCase(btn.getActionType()) && 
                                btn.getActionData() != null && 
                                btn.getActionData().toLowerCase().startsWith("perf_");
            String iconValue = btn.getIcon();
            boolean isTextBtn = iconValue != null && iconValue.startsWith("text:");
            
            android.widget.FrameLayout.LayoutParams iconLp = (android.widget.FrameLayout.LayoutParams) holder.ivIcon.getLayoutParams();
            if (isPerfBtn) {
                iconLp.width = dpToPx(34);
                iconLp.height = dpToPx(34);
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
                float textSize = displayTxt.length() <= 2 ? 24f : 13f; // Large font for 1-2 char emojis/letters, smaller for words
                holder.tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSize);
                holder.tvTitle.setText(displayTxt);
            } else {
                iconLp.width = dpToPx(48);
                iconLp.height = dpToPx(48);
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
            holder.cardView.setBackground(getFuturisticKeycapDrawable(btn.getColor()));

            // Remove any dynamically added grid view from holder.cardView first
            View oldDynamicGrid = holder.cardView.findViewWithTag("dynamic_url_grid");
            if (oldDynamicGrid != null && holder.cardView instanceof ViewGroup) {
                ((ViewGroup) holder.cardView).removeView(oldDynamicGrid);
            }

            if (iconValue != null && iconValue.contains("|")) {
                String[] parts = iconValue.split("\\|");
                if (parts.length > 1) {
                    holder.ivIcon.setVisibility(View.GONE);
                    View gridView = createDynamicGrid(parts);
                    if (holder.cardView instanceof ViewGroup) {
                        ((ViewGroup) holder.cardView).addView(gridView);
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
            if (isActionRepeatable(btn)) {
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

                    @Override
                    public boolean onTouch(View v, android.view.MotionEvent event) {
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        switch (event.getAction()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                if (networkClient.isConnected()) {
                                    networkClient.sendButtonPress(btn.getId());
                                }
                                repeatHandler.removeCallbacks(repeatRunnable);
                                repeatHandler.postDelayed(repeatRunnable, 400);
                                v.setPressed(true);
                                if (finalCardView != null) {
                                    finalCardView.setPressed(true);
                                }
                                return true;

                            case android.view.MotionEvent.ACTION_MOVE:
                                // Cancel repeating if the touch moves too far outside the bounds of the button (100px buffer slop)
                                float x = event.getX();
                                float y = event.getY();
                                float slop = 100f;
                                if (x < -slop || x > v.getWidth() + slop || y < -slop || y > v.getHeight() + slop) {
                                    repeatHandler.removeCallbacks(repeatRunnable);
                                    v.setPressed(false);
                                    if (finalCardView != null) {
                                        finalCardView.setPressed(false);
                                    }
                                }
                                return true;

                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:
                                repeatHandler.removeCallbacks(repeatRunnable);
                                v.setPressed(false);
                                if (finalCardView != null) {
                                    finalCardView.setPressed(false);
                                }
                                return true;
                        }
                        return false;
                    }
                });
            } else {
                convertView.setOnTouchListener(null);
            }

            return convertView;
        }

        private void renderSingleIcon(ImageView ivIcon, String iconValue) {
            if (iconValue != null && iconValue.startsWith("data:")) {
                try {
                    String base64 = iconValue.substring(5);
                    byte[] decodedBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    if (bitmap != null) {
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
            } else {
                ivIcon.setImageResource(getIconDrawableId(iconValue));
                ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            }
        }

        private View createDynamicGrid(String[] parts) {
            android.widget.LinearLayout root = new android.widget.LinearLayout(getContext());
            root.setTag("dynamic_url_grid");
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.FrameLayout.LayoutParams rootLp = new android.widget.FrameLayout.LayoutParams(
                    dpToPx(54), dpToPx(54), android.view.Gravity.CENTER
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

        private android.graphics.drawable.Drawable getFuturisticKeycapDrawable(String colorHex) {
            GradientDrawable defaultState = new GradientDrawable();
            defaultState.setColors(new int[]{Color.parseColor("#0E0E14"), Color.parseColor("#040406")});
            defaultState.setOrientation(GradientDrawable.Orientation.TL_BR);
            defaultState.setCornerRadius(dpToPx(28));
            defaultState.setStroke(dpToPx(1.5f), Color.parseColor("#1EFFFFFF"));

            GradientDrawable pressedState = new GradientDrawable();
            pressedState.setColors(new int[]{Color.parseColor("#1F1F2E"), Color.parseColor("#0E0E14")});
            pressedState.setOrientation(GradientDrawable.Orientation.TL_BR);
            pressedState.setCornerRadius(dpToPx(28));
            pressedState.setStroke(dpToPx(2f), Color.parseColor("#806366F1")); // Indigo highlight stroke on press

            android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, pressedState);
            sld.addState(new int[]{}, defaultState);
            return sld;
        }

        private int dpToPx(float dp) {
            if (getContext() == null) return (int) dp;
            return (int) (dp * getResources().getDisplayMetrics().density);
        }

        private int getIconDrawableId(String iconName) {
            if (iconName == null) return R.drawable.ic_default;
            switch (iconName.toLowerCase()) {
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
                default:
                    return R.drawable.ic_default;
            }
        }

        private class ViewHolder {
            View cardView;
            TextView tvTitle;
            ImageView ivIcon;
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
        View btnPairNew = dialogView.findViewById(R.id.option_pair_new);
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

        btnPairNew.setOnClickListener(v -> {
            dialog.dismiss();
            showPairNewConfirmation();
        });

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
            ImageView checkView = itemView.findViewById(R.id.profile_check);
            
            nameView.setText(profile.getName());
            
            if (profile.getId().equals(activeId)) {
                checkView.setVisibility(View.VISIBLE);
            } else {
                checkView.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                networkClient.sendChangeProfile(profile.getId());
                dialog.dismiss();
                Toast.makeText(getContext(), "Switching to " + profile.getName(), Toast.LENGTH_SHORT).show();
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
        if (tvMessage != null) tvMessage.setText("Are you sure you want to disconnect and exit SwiftDock?");
        
        if (btnYes != null) {
            btnYes.setBackgroundResource(R.drawable.button_danger);
            btnYes.setText("Disconnect");
            btnYes.setOnClickListener(v -> {
                dialog.dismiss();
                isUserDisconnecting = true;
                networkClient.disconnect();
                requireActivity().finishAffinity();
            });
        }

        if (btnNo != null) {
            btnNo.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void showPairNewConfirmation() {
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

        if (tvTitle != null) tvTitle.setText("Pair New Device");
        if (tvMessage != null) tvMessage.setText("Are you sure you want to unpair this computer and pair a new device?");
        
        if (btnYes != null) {
            btnYes.setBackgroundResource(R.drawable.button_danger);
            btnYes.setText("Unpair & Pair");
            btnYes.setOnClickListener(v -> {
                dialog.dismiss();
                isUserDisconnecting = true;
                networkClient.disconnect();
                clearPairingPrefs();
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

    private int dpToPx(int dp) {
        if (getContext() == null) return dp;
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
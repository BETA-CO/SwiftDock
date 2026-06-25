package com.example.swiftdock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import java.util.List;

public class FirstFragment extends Fragment implements NetworkClient.NetworkListener {

    private static final String PREFS_NAME = "SwiftDockPrefs";
    private static final String KEY_MOBILE_NAME = "mobile_name";
    private static final String KEY_PAIRED_TOKEN = "paired_token";
    private static final String KEY_PAIRED_PC = "paired_pc";
    private static final String KEY_PAIRED_IP = "paired_ip";

    private LinearLayout layoutStep1, layoutStep2, layoutStep3, layoutReconnecting, layoutReconnectOptions;
    private EditText editMobileName;
    private TextView tvDiscoveryStatus, tvReconnectStatus, tvReconnectSubtitle, tvReconnectOptionsSubtitle;
    private TextView tvPinDigit1, tvPinDigit2, tvPinDigit3, tvPinDigit4;
    private Button btnStep2Connect, btnRetryReconnect, btnConnectOld, btnPairNew, btnStep1Back;
    private ProgressBar progressReconnect;

    private String discoveredIp;

    private String savedToken;
    private String savedPcName;
    private String mobileName;
    private boolean isAttemptingAutoReconnect = false;
    private boolean isFirstTimePairing = false;

    private NetworkClient networkClient;
    private final StringBuilder pinBuilder = new StringBuilder();

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_first, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Force Landscape orientation during wizard connection
        requireActivity().setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Bind layouts
        layoutStep1 = view.findViewById(R.id.layout_step1);
        layoutStep2 = view.findViewById(R.id.layout_step2);
        layoutStep3 = view.findViewById(R.id.layout_step3);
        layoutReconnecting = view.findViewById(R.id.layout_reconnecting);
        layoutReconnectOptions = view.findViewById(R.id.layout_reconnect_options);

        // Bind inputs/buttons
        editMobileName = view.findViewById(R.id.edit_mobile_name);
        tvDiscoveryStatus = view.findViewById(R.id.tv_discovery_status);
        tvReconnectStatus = view.findViewById(R.id.tv_reconnect_status);
        tvReconnectSubtitle = view.findViewById(R.id.tv_reconnect_subtitle);
        tvReconnectOptionsSubtitle = view.findViewById(R.id.tv_reconnect_options_subtitle);
        btnStep2Connect = view.findViewById(R.id.btn_step2_connect);
        btnRetryReconnect = view.findViewById(R.id.btn_retry_reconnect);
        btnConnectOld = view.findViewById(R.id.btn_connect_old);
        btnPairNew = view.findViewById(R.id.btn_pair_new);
        btnStep1Back = view.findViewById(R.id.btn_step1_back);
        progressReconnect = view.findViewById(R.id.progress_reconnect);

        tvPinDigit1 = view.findViewById(R.id.pin_digit1);
        tvPinDigit2 = view.findViewById(R.id.pin_digit2);
        tvPinDigit3 = view.findViewById(R.id.pin_digit3);
        tvPinDigit4 = view.findViewById(R.id.pin_digit4);

        Button btnStep1Next = view.findViewById(R.id.btn_step1_next);
        Button btnStep2Back = view.findViewById(R.id.btn_step2_back);
        Button btnCancelReconnect = view.findViewById(R.id.btn_cancel_reconnect);

        // Load settings
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mobileName = prefs.getString(KEY_MOBILE_NAME, Build.MODEL);
        savedToken = prefs.getString(KEY_PAIRED_TOKEN, "");
        savedPcName = prefs.getString(KEY_PAIRED_PC, "");
        final String savedIp = prefs.getString(KEY_PAIRED_IP, "");

        editMobileName.setText(mobileName);

        // Setup Network Client
        networkClient = NetworkClient.getInstance();
        networkClient.addListener(this);

        // Start scanning automatically on load to warm up cache
        startDiscovery();

        // Setup custom pin entry keypad
        setupKeypad(view);

        // Setup Retry Listener
        btnRetryReconnect.setOnClickListener(v -> {
            isAttemptingAutoReconnect = true;
            btnRetryReconnect.setVisibility(View.GONE);
            progressReconnect.setVisibility(View.VISIBLE);
            tvReconnectStatus.setText("Reconnecting to " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName) + "...");
            tvReconnectSubtitle.setText("Searching for your paired computer on Wi-Fi.");
            if (!TextUtils.isEmpty(savedIp)) {
                networkClient.reconnect(savedIp, savedToken, mobileName);
            } else {
                startDiscovery();
            }
            startReconnectTimeout(view);
        });

        // Wizard Flow Initial State
        if (!TextUtils.isEmpty(savedToken)) {
            // Setup choices buttons
            btnConnectOld.setOnClickListener(oldBtnView -> {
                isAttemptingAutoReconnect = true;
                isFirstTimePairing = false;
                showPanel(layoutReconnecting);
                tvReconnectStatus.setText("Reconnecting to " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName) + "...");
                btnRetryReconnect.setVisibility(View.GONE);
                progressReconnect.setVisibility(View.VISIBLE);

                if (!TextUtils.isEmpty(savedIp)) {
                    networkClient.reconnect(savedIp, savedToken, mobileName);
                } else {
                    startDiscovery();
                }
                startReconnectTimeout(view);
            });

            btnPairNew.setOnClickListener(newBtnView -> {
                isAttemptingAutoReconnect = false;
                stopDiscovery();
                networkClient.disconnect();
                // ACCIDENTAL CLICK FIX: Do NOT clear pairing data here. Just go to Step 1.
                showPanel(layoutStep1);
            });

            // We have a saved pairing token, show reconnect options screen
            showPanel(layoutReconnectOptions);
            tvReconnectOptionsSubtitle.setText("Would you like to connect to '" + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName) + "'?");
            btnConnectOld.setText("Connect to " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName));
        } else {
            showPanel(layoutStep1);
        }

        // Button Listeners
        btnStep1Next.setOnClickListener(v -> {
            String name = editMobileName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                name = Build.MODEL;
            }
            mobileName = name;
            prefs.edit().putString(KEY_MOBILE_NAME, name).apply();
            
            isFirstTimePairing = true;
            // Move to Step 2
            showPanel(layoutStep2);
            startDiscovery();
        });

        btnStep1Back.setOnClickListener(v -> {
            // Recover from accidental "Pair New Device" click
            showPanel(layoutReconnectOptions);
        });

        btnStep2Back.setOnClickListener(v -> {
            stopDiscovery();
            isFirstTimePairing = false;
            showPanel(layoutStep1);
        });

        btnStep2Connect.setOnClickListener(v -> {
            String pin = pinBuilder.toString();
            if (TextUtils.isEmpty(discoveredIp)) {
                Toast.makeText(getContext(), "Searching for computer... Please make sure SwiftDock is running.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pin.length() != 4) {
                Toast.makeText(getContext(), "Please enter a valid 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            isFirstTimePairing = true;
            btnStep2Connect.setEnabled(false);
            btnStep2Connect.setText("Connecting...");
            networkClient.connectAndAuthenticate(discoveredIp, pin, mobileName);
        });

        btnCancelReconnect.setOnClickListener(v -> {
            isAttemptingAutoReconnect = false;
            stopDiscovery();
            networkClient.disconnect();
            clearPairingData();
            showPanel(layoutStep1);
        });
    }

    private void setupKeypad(View view) {
        int[] buttonIds = new int[]{
                R.id.keypad_0, R.id.keypad_1, R.id.keypad_2, R.id.keypad_3,
                R.id.keypad_4, R.id.keypad_5, R.id.keypad_6, R.id.keypad_7,
                R.id.keypad_8, R.id.keypad_9
        };

        for (int i = 0; i < buttonIds.length; i++) {
            final String digit = String.valueOf(i);
            View btn = view.findViewById(buttonIds[i]);
            if (btn != null) {
                btn.setOnClickListener(v -> {
                    if (pinBuilder.length() < 4) {
                        pinBuilder.append(digit);
                        updatePinDisplay();
                    }
                });
            }
        }

        View btnBack = view.findViewById(R.id.keypad_backspace);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (pinBuilder.length() > 0) {
                    pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                    updatePinDisplay();
                }
            });
        }

        View btnClear = view.findViewById(R.id.keypad_clear);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                pinBuilder.setLength(0);
                updatePinDisplay();
            });
        }
    }

    private void updatePinDisplay() {
        String pin = pinBuilder.toString();
        
        if (tvPinDigit1 != null) tvPinDigit1.setText(pin.length() > 0 ? String.valueOf(pin.charAt(0)) : "");
        if (tvPinDigit2 != null) tvPinDigit2.setText(pin.length() > 1 ? String.valueOf(pin.charAt(1)) : "");
        if (tvPinDigit3 != null) tvPinDigit3.setText(pin.length() > 2 ? String.valueOf(pin.charAt(2)) : "");
        if (tvPinDigit4 != null) tvPinDigit4.setText(pin.length() > 3 ? String.valueOf(pin.charAt(3)) : "");
        
        if (btnStep2Connect != null) {
            btnStep2Connect.setEnabled(pin.length() == 4);
        }
    }

    private void startDiscovery() {
        if (isAdded()) {
            networkClient.acquireMulticastLock(requireContext());
            networkClient.startDiscovery(requireContext());
        }
    }

    private void stopDiscovery() {
        networkClient.stopDiscovery();
        networkClient.releaseMulticastLock();
    }

    private void showPanel(LinearLayout panel) {
        layoutStep1.setVisibility(View.GONE);
        layoutStep2.setVisibility(View.GONE);
        layoutStep3.setVisibility(View.GONE);
        layoutReconnecting.setVisibility(View.GONE);
        layoutReconnectOptions.setVisibility(View.GONE);

        panel.setVisibility(View.VISIBLE);

        if (panel == layoutStep1) {
            // Show/Hide recovery back button based on saved pairing status
            if (btnStep1Back != null) {
                btnStep1Back.setVisibility(TextUtils.isEmpty(savedToken) ? View.GONE : View.VISIBLE);
            }
            editMobileName.requestFocus();
            showKeyboard(editMobileName);
        } else {
            editMobileName.clearFocus();
            hideKeyboard();

            if (panel == layoutStep2) {
                // Clear PIN entry when entering panel
                pinBuilder.setLength(0);
                updatePinDisplay();
            }
        }
    }

    private void showKeyboard(View view) {
        if (getContext() == null) return;
        view.postDelayed(() -> {
            if (isAdded()) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) 
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }, 100);
    }

    private void hideKeyboard() {
        if (getContext() == null) return;
        android.view.View focusedView = requireActivity().getCurrentFocus();
        if (focusedView == null) {
            focusedView = new android.view.View(requireContext());
        }
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) 
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focusedView.getWindowToken(), 0);
        }
    }

    private void startReconnectTimeout(final View view) {
        view.postDelayed(() -> {
            if (isAttemptingAutoReconnect && isAdded()) {
                isAttemptingAutoReconnect = false;
                stopDiscovery();
                
                // Show Connection Offline screen with retry option
                progressReconnect.setVisibility(View.GONE);
                tvReconnectStatus.setText("Connection Offline");
                tvReconnectSubtitle.setText("Could not find " + (TextUtils.isEmpty(savedPcName) ? "paired computer" : savedPcName) + ". Make sure SwiftDock is running.");
                btnRetryReconnect.setVisibility(View.VISIBLE);
            }
        }, 6000);
    }

    private void clearPairingData() {
        savedToken = "";
        savedPcName = "";
        discoveredIp = null;
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAIRED_TOKEN, "")
                .putString(KEY_PAIRED_PC, "")
                .putString(KEY_PAIRED_IP, "")
                .apply();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        networkClient.removeListener(this);
    }

    // Network Callbacks
    @Override
    public void onServerDiscovered(String ip, int port, String hostname) {
        if (!isAdded()) return;

        tvDiscoveryStatus.setText("Ready to Connect to " + hostname);
        discoveredIp = ip;
        
        if (isAttemptingAutoReconnect && !TextUtils.isEmpty(savedToken)) {
            // Auto-reconnect triggered on discovery
            isAttemptingAutoReconnect = false;
            networkClient.reconnect(ip, savedToken, mobileName);
        }
    }

    @Override
    public void onConnectionSuccess(String token) {
        if (!isAdded()) return;

        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ip = discoveredIp != null ? discoveredIp : prefs.getString(KEY_PAIRED_IP, "");
        String hostname = networkClient.getDiscoveredHostname();
        if (TextUtils.isEmpty(hostname)) {
            hostname = "Computer";
        }

        prefs.edit()
                .putString(KEY_PAIRED_TOKEN, token)
                .putString(KEY_PAIRED_PC, hostname)
                .putString(KEY_PAIRED_IP, ip)
                .apply();

        stopDiscovery();
        
        if (isFirstTimePairing) {
            showPanel(layoutStep3);
        } else {
            // Jump directly to grid
            NavHostFragment.findNavController(FirstFragment.this)
                    .navigate(R.id.action_FirstFragment_to_SecondFragment);
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        if (!isAdded()) return;

        Toast.makeText(getContext(), reason, Toast.LENGTH_LONG).show();
        btnStep2Connect.setEnabled(true);
        btnStep2Connect.setText("Connect");

        if (isAttemptingAutoReconnect) {
            isAttemptingAutoReconnect = false;
            stopDiscovery();
            
            // Show Connection Offline screen with retry option
            progressReconnect.setVisibility(View.GONE);
            tvReconnectStatus.setText("Connection Failed");
            tvReconnectSubtitle.setText(reason);
            btnRetryReconnect.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDisconnected() {
        if (!isAdded()) return;

        btnStep2Connect.setEnabled(true);
        btnStep2Connect.setText("Connect");
    }

    @Override
    public void onButtonsSynced(List<ShortcutButton> buttons) {
        // Handled in SecondFragment (Grid screen)
    }

    @Override
    public void onTransitionToGrid() {
        if (!isAdded()) return;

        // Transition to second fragment (shortcuts grid)
        NavHostFragment.findNavController(FirstFragment.this)
                .navigate(R.id.action_FirstFragment_to_SecondFragment);
    }
}
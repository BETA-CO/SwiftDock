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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FirstFragment extends Fragment implements NetworkClient.NetworkListener {

    private static final String PREFS_NAME = "SwiftDockPrefs";
    private static final String KEY_MOBILE_NAME = "mobile_name";
    private static final String KEY_PAIRED_TOKEN = "paired_token";
    private static final String KEY_PAIRED_PC = "paired_pc";
    private static final String KEY_PAIRED_IP = "paired_ip";

    private LinearLayout layoutReconnecting, layoutReconnectOptions;
    private TextView tvReconnectStatus, tvReconnectSubtitle, tvReconnectOptionsSubtitle, tvReconnectOptionsTitle;
    private Button btnRetryReconnect, btnConnectOld;
    private ProgressBar progressReconnect;

    private String discoveredIp;
    private final Map<String, String> discoveredServers = new HashMap<>();

    private String savedToken;
    private String savedPcName;
    private String mobileName;
    private boolean isAttemptingAutoReconnect = false;
    private boolean isConnecting = false;

    private NetworkClient networkClient;

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
        layoutReconnecting = view.findViewById(R.id.layout_reconnecting);
        layoutReconnectOptions = view.findViewById(R.id.layout_reconnect_options);

        // Bind inputs/buttons
        tvReconnectStatus = view.findViewById(R.id.tv_reconnect_status);
        tvReconnectSubtitle = view.findViewById(R.id.tv_reconnect_subtitle);
        tvReconnectOptionsSubtitle = view.findViewById(R.id.tv_reconnect_options_subtitle);
        tvReconnectOptionsTitle = view.findViewById(R.id.tv_reconnect_options_title);
        btnRetryReconnect = view.findViewById(R.id.btn_retry_reconnect);
        btnConnectOld = view.findViewById(R.id.btn_connect_old);
        progressReconnect = view.findViewById(R.id.progress_reconnect);

        Button btnCancelReconnect = view.findViewById(R.id.btn_cancel_reconnect);

        // Load settings
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mobileName = prefs.getString(KEY_MOBILE_NAME, Build.MODEL);
        savedToken = prefs.getString(KEY_PAIRED_TOKEN, "");
        savedPcName = prefs.getString(KEY_PAIRED_PC, "");
        final String savedIp = prefs.getString(KEY_PAIRED_IP, "");

        // Setup Network Client
        networkClient = NetworkClient.getInstance();
        networkClient.addListener(this);

        if (tvReconnectOptionsTitle != null) {
            tvReconnectOptionsTitle.setText("Connect to Device");
        }

        // Configure "Connect to Device" button action
        btnConnectOld.setOnClickListener(v -> {
            if (discoveredServers.size() > 1) {
                // Show choice dialog
                final List<String> ips = new ArrayList<>(discoveredServers.keySet());
                final List<String> hostnames = new ArrayList<>(discoveredServers.values());
                CharSequence[] items = hostnames.toArray(new CharSequence[0]);

                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Select a Computer")
                    .setItems(items, (dialog, which) -> {
                        String selectedIp = ips.get(which);
                        String selectedName = hostnames.get(which);

                        discoveredIp = selectedIp;
                        isConnecting = true;
                        showPanel(layoutReconnecting);
                        tvReconnectStatus.setText("Connecting to " + selectedName + "...");
                        tvReconnectSubtitle.setText("Establishing connection...");
                        btnRetryReconnect.setVisibility(View.GONE);
                        progressReconnect.setVisibility(View.VISIBLE);

                        networkClient.connectAndAuthenticate(selectedIp, "0000", mobileName);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                isConnecting = true;
                showPanel(layoutReconnecting);
                tvReconnectStatus.setText("Connecting to " + (TextUtils.isEmpty(discoveredIp) ? (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName) : networkClient.getDiscoveredHostname()) + "...");
                tvReconnectSubtitle.setText("Establishing connection...");
                btnRetryReconnect.setVisibility(View.GONE);
                progressReconnect.setVisibility(View.VISIBLE);

                if (!TextUtils.isEmpty(discoveredIp)) {
                    networkClient.connectAndAuthenticate(discoveredIp, "0000", mobileName);
                } else if (!TextUtils.isEmpty(savedIp)) {
                    isAttemptingAutoReconnect = true;
                    networkClient.reconnect(savedIp, savedToken, mobileName);
                }
            }
        });

        // Setup Cancel button on connecting screen to go back to choices
        btnCancelReconnect.setOnClickListener(v -> {
            isConnecting = false;
            isAttemptingAutoReconnect = false;
            stopDiscovery();
            networkClient.disconnect();
            
            showPanel(layoutReconnectOptions);
            startDiscovery();
        });

        // Initialize state: Show Connect Options panel by default
        showPanel(layoutReconnectOptions);
        isConnecting = false;

        // If there's a saved connection, make it the default option on the button
        if (!TextUtils.isEmpty(savedIp) && !TextUtils.isEmpty(savedToken)) {
            btnConnectOld.setEnabled(true);
            btnConnectOld.setText("Connect to " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName));
            tvReconnectOptionsSubtitle.setText("Saved computer: " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName) + ". Tap below to connect.");
        } else {
            // Disabled until a server is discovered on Wi-Fi
            btnConnectOld.setEnabled(false);
            btnConnectOld.setText("Connect to Device");
            tvReconnectOptionsSubtitle.setText("Searching for computer on Wi-Fi...");
        }

        // Start discovery in background
        startDiscovery();
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
        layoutReconnecting.setVisibility(View.GONE);
        layoutReconnectOptions.setVisibility(View.GONE);

        panel.setVisibility(View.VISIBLE);
    }

    private void clearPairingData() {
        savedToken = "";
        savedPcName = "";
        discoveredIp = null;
        discoveredServers.clear();
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
        stopDiscovery();
        networkClient.removeListener(this);
    }
 
    // Network Callbacks
    @Override
    public void onServerDiscovered(String ip, int port, String hostname) {
        if (!isAdded()) return;
 
        discoveredServers.put(ip, hostname);
        
        if (layoutReconnectOptions.getVisibility() == View.VISIBLE) {
            if (discoveredServers.size() == 1) {
                discoveredIp = ip;
                tvReconnectOptionsSubtitle.setText("Found computer: " + hostname + " on Wi-Fi.");
                btnConnectOld.setText("Connect to " + hostname);
            } else {
                discoveredIp = null; // Forces user to choose from dialog
                tvReconnectOptionsSubtitle.setText("Found " + discoveredServers.size() + " computers on Wi-Fi. Tap below to select.");
                btnConnectOld.setText("Connect to Device...");
            }
            btnConnectOld.setEnabled(true);
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
        isConnecting = false;
        
        // Jump directly to grid
        NavHostFragment.findNavController(FirstFragment.this)
                .navigate(R.id.action_FirstFragment_to_SecondFragment);
    }
 
    @Override
    public void onConnectionFailed(String reason) {
        if (!isAdded()) return;
 
        isConnecting = false;
        isAttemptingAutoReconnect = false;
 
        Toast.makeText(getContext(), reason, Toast.LENGTH_SHORT).show();
 
        showPanel(layoutReconnectOptions);
        
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedIp = prefs.getString(KEY_PAIRED_IP, "");
        String savedPcName = prefs.getString(KEY_PAIRED_PC, "");
 
        if (discoveredServers.size() > 1) {
            tvReconnectOptionsSubtitle.setText("Connection failed. Found " + discoveredServers.size() + " computers on Wi-Fi.");
            btnConnectOld.setText("Connect to Device...");
            btnConnectOld.setEnabled(true);
        } else if (!TextUtils.isEmpty(discoveredIp)) {
            String hostname = discoveredServers.get(discoveredIp);
            if (TextUtils.isEmpty(hostname)) hostname = networkClient.getDiscoveredHostname();
            tvReconnectOptionsSubtitle.setText("Connection failed. Found computer: " + (TextUtils.isEmpty(hostname) ? "Device" : hostname));
            btnConnectOld.setText("Connect to " + (TextUtils.isEmpty(hostname) ? "Device" : hostname));
            btnConnectOld.setEnabled(true);
        } else if (!TextUtils.isEmpty(savedIp)) {
            tvReconnectOptionsSubtitle.setText("Connection failed. Saved computer: " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName));
            btnConnectOld.setText("Connect to " + (TextUtils.isEmpty(savedPcName) ? "Computer" : savedPcName));
            btnConnectOld.setEnabled(true);
        } else {
            tvReconnectOptionsSubtitle.setText("Connection failed. Searching on Wi-Fi...");
            btnConnectOld.setText("Connect to Device");
            btnConnectOld.setEnabled(false);
        }
 
        startDiscovery();
    }
 
    @Override
    public void onDisconnected() {
        if (!isAdded()) return;
        isConnecting = false;
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
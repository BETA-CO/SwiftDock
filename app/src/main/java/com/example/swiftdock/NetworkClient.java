package com.example.swiftdock;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class NetworkClient {
    private static final String TAG = "SwiftDockNetwork";
    private static final int UDP_PORT = 19002;
    
    private static NetworkClient instance;

    public interface NetworkListener {
        void onServerDiscovered(String ip, int port, String hostname);
        void onConnectionSuccess(String token);
        void onConnectionFailed(String reason);
        void onDisconnected();
        void onButtonsSynced(List<ShortcutButton> buttons);
        void onTransitionToGrid();
        default void onPerformanceUpdated(int cpu, int gpu, int ram, int temp, String wifi) {}
        default void onProfilesSynced(List<ProfileInfo> profiles, String currentProfileId) {}
    }

    public static class ProfileInfo {
        private String id;
        private String name;

        public ProfileInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }

    public static int currentCpu = 0;
    public static int currentGpu = 0;
    public static int currentRam = 0;
    public static int currentTemp = 0;
    public static String currentWifi = "0 KB/s";

    private Socket tcpSocket;
    private OutputStream outputStream;
    private Thread tcpThread;
    private Thread udpThread;
    private boolean isRunning = false;
    private final Handler mainHandler;
    private final List<NetworkListener> listeners = new ArrayList<>();

    // Keep track of discovered server
    private String discoveredIp;
    private int discoveredPort = 19001;
    private String discoveredHostname;

    private List<ShortcutButton> cachedButtons = new ArrayList<>();
    private List<ProfileInfo> cachedProfiles = new ArrayList<>();
    private String currentProfileId = "";

    private NetworkClient() {
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized NetworkClient getInstance() {
        if (instance == null) {
            instance = new NetworkClient();
        }
        return instance;
    }

    public void addListener(NetworkListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(NetworkListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public String getDiscoveredHostname() {
        return discoveredHostname;
    }

    public boolean isConnected() {
        return tcpSocket != null && tcpSocket.isConnected() && !tcpSocket.isClosed();
    }

    public List<ShortcutButton> getCachedButtons() {
        return cachedButtons;
    }

    public List<ProfileInfo> getCachedProfiles() {
        return cachedProfiles;
    }

    public String getCurrentProfileId() {
        return currentProfileId;
    }

    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    public void acquireMulticastLock(android.content.Context context) {
        try {
            android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("SwiftDockLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
                Log.d(TAG, "Multicast lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire multicast lock: " + e.getMessage());
        }
    }

    public void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                Log.d(TAG, "Multicast lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release multicast lock: " + e.getMessage());
        }
    }

    private ExecutorService scanExecutor;

    private boolean isPrivateIPv4(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return (first == 172 && second >= 16 && second <= 31);
        } catch (Exception e) {
            return false;
        }
    }

    private String getLocalSubnet(android.content.Context context) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    java.net.NetworkInterface iface = interfaces.nextElement();
                    if (iface.isLoopback() || !iface.isUp()) {
                        continue;
                    }
                    
                    java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivateIPv4(ip))) {
                                int lastDot = ip.lastIndexOf('.');
                                if (lastDot > 0) {
                                    Log.d(TAG, "Resolved local subnet prefix via NetworkInterface: " + ip.substring(0, lastDot + 1));
                                    return ip.substring(0, lastDot + 1);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get subnet via NetworkInterface: " + e.getMessage());
        }

        // Fallback to WifiManager
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm != null) {
                android.net.wifi.WifiInfo connectionInfo = wm.getConnectionInfo();
                if (connectionInfo != null) {
                    int ipAddress = connectionInfo.getIpAddress();
                    if (ipAddress != 0) {
                        String subnet = String.format("%d.%d.%d.",
                                (ipAddress & 0xff),
                                (ipAddress >> 8 & 0xff),
                                (ipAddress >> 16 & 0xff));
                        Log.d(TAG, "Resolved local subnet prefix via WifiManager fallback: " + subnet);
                        return subnet;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback WifiManager failed: " + e.getMessage());
        }
        return null;
    }

    public void startNetworkScan(final android.content.Context context) {
        final String subnet = getLocalSubnet(context);
        if (subnet == null) {
            Log.e(TAG, "Cannot start subnet scan: local subnet is null.");
            return;
        }

        if (scanExecutor != null && !scanExecutor.isShutdown()) {
            scanExecutor.shutdownNow();
        }

        new Thread(() -> {
            Log.d(TAG, "Starting parallel subnet scan on subnet: " + subnet);
            scanExecutor = Executors.newFixedThreadPool(30);

            for (int i = 1; i <= 254; i++) {
                final String host = subnet + i;
                scanExecutor.execute(() -> {
                    Socket socket = null;
                    try {
                        socket = new Socket();
                        socket.connect(new InetSocketAddress(host, 19001), 800); // 800ms TCP connection check
                        
                        Log.d(TAG, "Found server via TCP scan at " + host);
                        
                        // Query the hostname from the server using the DISCOVER command
                        OutputStream out = socket.getOutputStream();
                        JSONObject discoverReq = new JSONObject();
                        discoverReq.put("type", "DISCOVER");
                        discoverReq.put("deviceName", "DiscoveryClient");
                        out.write((discoverReq.toString() + "\n").getBytes("UTF-8"));
                        out.flush();
                        
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String responseLine = reader.readLine();
                        String hostname = "Computer";
                        if (responseLine != null && !responseLine.trim().isEmpty()) {
                            JSONObject responseObj = new JSONObject(responseLine);
                            if ("DISCOVER_RESPONSE".equalsIgnoreCase(responseObj.optString("type"))) {
                                hostname = responseObj.optString("deviceName", "Computer");
                            }
                        }
                        
                        discoveredIp = host;
                        discoveredPort = 19001;
                        discoveredHostname = hostname;
                        
                        notifyServerDiscovered(host, 19001, discoveredHostname);
                    } catch (Exception e) {
                        // Port closed or unreachable
                    } finally {
                        if (socket != null) {
                            try { socket.close(); } catch (Exception e) {}
                        }
                    }
                });
            }

            scanExecutor.shutdown();
            try {
                scanExecutor.awaitTermination(6, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Subnet scan interrupted");
            }
            Log.d(TAG, "Subnet scan finished");
        }).start();
    }

    // Start UDP discovery service and TCP subnet scan in parallel
    public void startDiscovery(final android.content.Context context) {
        startNetworkScan(context);

        if (udpThread != null && udpThread.isAlive()) return;

        udpThread = new Thread(() -> {
            DatagramSocket udpSocket = null;
            try {
                udpSocket = new DatagramSocket(null);
                udpSocket.setReuseAddress(true);
                udpSocket.bind(new InetSocketAddress(UDP_PORT));
                byte[] buffer = new byte[1024];

                Log.d(TAG, "UDP Discovery listener started on port " + UDP_PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength()).trim();
                    // Expected format: SwiftDock-Server:<Port>:<Hostname>
                    if (message.startsWith("SwiftDock-Server:")) {
                        String[] parts = message.split(":", 3);
                        if (parts.length >= 3) {
                            String ip = packet.getAddress().getHostAddress();
                            int port = Integer.parseInt(parts[1]);
                            String hostname = parts[2];

                            discoveredIp = ip;
                            discoveredPort = port;
                            discoveredHostname = hostname;

                            notifyServerDiscovered(ip, port, hostname);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "UDP Discovery error: " + e.getMessage());
            } finally {
                if (udpSocket != null && !udpSocket.isClosed()) {
                    udpSocket.close();
                }
            }
        });
        udpThread.start();
    }

    public void stopDiscovery() {
        if (udpThread != null) {
            udpThread.interrupt();
            udpThread = null;
        }
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
    }

    // Connect and Pair with PIN
    public void connectAndAuthenticate(final String ip, final String pin, final String mobileName) {
        if (ip == null || ip.trim().isEmpty()) {
            notifyConnectionFailed("Please enter a valid IP address.");
            return;
        }

        new Thread(() -> {
            try {
                closeTcpConnection();
                
                tcpSocket = new Socket();
                tcpSocket.connect(new InetSocketAddress(ip, discoveredPort), 4000);
                outputStream = tcpSocket.getOutputStream();

                // Send AUTH request
                JSONObject authRequest = new JSONObject();
                authRequest.put("type", "AUTH");
                authRequest.put("pin", pin);
                authRequest.put("deviceName", mobileName);

                sendRaw(authRequest.toString());

                // Read AUTH response
                BufferedReader reader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                String responseLine = reader.readLine();
                if (responseLine == null) {
                    notifyConnectionFailed("Disconnected by server during pairing.");
                    closeTcpConnection();
                    return;
                }

                JSONObject authResponse = new JSONObject(responseLine);
                String status = authResponse.optString("status");
                
                if ("SUCCESS".equalsIgnoreCase(status)) {
                    String token = authResponse.optString("token");
                    notifyConnectionSuccess(token);
                    startSessionReader(reader);
                } else {
                    String reason = authResponse.optString("reason", "Incorrect PIN");
                    notifyConnectionFailed(reason);
                    closeTcpConnection();
                }
            } catch (Exception e) {
                Log.e(TAG, "TCP connection error: " + e.getMessage());
                notifyConnectionFailed("Failed to connect: " + e.getMessage());
                closeTcpConnection();
            }
        }).start();
    }

    // Reconnect with existing token
    public void reconnect(final String ip, final String savedToken, final String mobileName) {
        if (ip == null || ip.trim().isEmpty()) {
            notifyConnectionFailed("Server IP address is missing.");
            return;
        }

        new Thread(() -> {
            try {
                closeTcpConnection();

                tcpSocket = new Socket();
                tcpSocket.connect(new InetSocketAddress(ip, discoveredPort), 3000);
                outputStream = tcpSocket.getOutputStream();

                // Send RECONNECT request
                JSONObject reconnectRequest = new JSONObject();
                reconnectRequest.put("type", "RECONNECT");
                reconnectRequest.put("token", savedToken);
                reconnectRequest.put("deviceName", mobileName);

                sendRaw(reconnectRequest.toString());

                // Read response
                BufferedReader reader = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                String responseLine = reader.readLine();
                if (responseLine == null) {
                    notifyConnectionFailed("Auto-reconnection failed: Disconnected by server.");
                    closeTcpConnection();
                    return;
                }

                JSONObject authResponse = new JSONObject(responseLine);
                String status = authResponse.optString("status");

                if ("SUCCESS".equalsIgnoreCase(status)) {
                    notifyConnectionSuccess(savedToken);
                    startSessionReader(reader);
                } else {
                    notifyConnectionFailed("Saved pairing session expired.");
                    closeTcpConnection();
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto-reconnect error: " + e.getMessage());
                notifyConnectionFailed("Could not connect automatically: " + e.getMessage());
                closeTcpConnection();
            }
        }).start();
    }

    public void sendButtonPress(String buttonId) {
        new Thread(() -> {
            try {
                JSONObject pressRequest = new JSONObject();
                pressRequest.put("type", "BUTTON_PRESS");
                pressRequest.put("id", buttonId);
                sendRaw(pressRequest.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending button press: " + e.getMessage());
            }
        }).start();
    }

    public void sendChangeProfile(String profileId) {
        new Thread(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("type", "CHANGE_PROFILE");
                request.put("profileId", profileId);
                sendRaw(request.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error sending change profile: " + e.getMessage());
            }
        }).start();
    }

    public void disconnect() {
        new Thread(this::closeTcpConnection).start();
    }

    private void sendRaw(String msg) throws Exception {
        if (outputStream != null) {
            byte[] data = (msg + "\n").getBytes("UTF-8");
            outputStream.write(data);
            outputStream.flush();
        } else {
            throw new Exception("Output stream is null. Socket not connected.");
        }
    }

    private void closeTcpConnection() {
        isRunning = false;
        if (tcpThread != null) {
            tcpThread.interrupt();
            tcpThread = null;
        }
        try {
            if (outputStream != null) outputStream.close();
            if (tcpSocket != null) tcpSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing sockets: " + e.getMessage());
        }
        tcpSocket = null;
        outputStream = null;
        cachedButtons.clear();
        notifyDisconnected();
    }

    private void startSessionReader(final BufferedReader reader) {
        isRunning = true;
        tcpThread = new Thread(() -> {
            try {
                while (isRunning && tcpSocket != null && !tcpSocket.isClosed()) {
                    String line = reader.readLine();
                    if (line == null) break; // Disconnected

                    if (!line.trim().isEmpty()) {
                        processIncomingMessage(line);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Session read error: " + e.getMessage());
            } finally {
                closeTcpConnection();
            }
        });
        tcpThread.start();
    }

    private void processIncomingMessage(String json) {
        try {
            JSONObject root = new JSONObject(json);
            String type = root.optString("type");

            if ("SYNC_BUTTONS".equalsIgnoreCase(type)) {
                JSONArray buttonsArray = root.optJSONArray("buttons");
                List<ShortcutButton> syncedButtons = new ArrayList<>();
                if (buttonsArray != null) {
                    for (int i = 0; i < buttonsArray.length(); i++) {
                        JSONObject btnObj = buttonsArray.getJSONObject(i);
                        ShortcutButton btn = new ShortcutButton();
                        btn.setId(btnObj.getString("Id"));
                        btn.setTitle(btnObj.optString("Title", ""));
                        btn.setColor(btnObj.optString("Color", "#6366F1"));
                        btn.setIcon(btnObj.optString("Icon", "default"));
                        btn.setActionType(btnObj.optString("ActionType", ""));
                        btn.setActionData(btnObj.optString("ActionData", ""));
                        syncedButtons.add(btn);
                    }
                }
                cachedButtons = syncedButtons;
                notifyButtonsSynced(syncedButtons);
            } else if ("TRANSITION_GRID".equalsIgnoreCase(type)) {
                notifyTransitionToGrid();
            } else if ("PERFORMANCE_UPDATE".equalsIgnoreCase(type)) {
                int cpu = root.optInt("cpu", 0);
                int gpu = root.optInt("gpu", 0);
                int ram = root.optInt("ram", 0);
                int temp = root.optInt("temp", 0);
                String wifi = root.optString("wifi", "0 KB/s");

                currentCpu = cpu;
                currentGpu = gpu;
                currentRam = ram;
                currentTemp = temp;
                currentWifi = wifi;

                notifyPerformanceUpdated(cpu, gpu, ram, temp, wifi);
            } else if ("SYNC_PROFILES".equalsIgnoreCase(type)) {
                String activeProfileId = root.optString("currentProfileId", "");
                JSONArray profilesArray = root.optJSONArray("profiles");
                List<ProfileInfo> profiles = new ArrayList<>();
                if (profilesArray != null) {
                    for (int i = 0; i < profilesArray.length(); i++) {
                        JSONObject pObj = profilesArray.getJSONObject(i);
                        profiles.add(new ProfileInfo(
                                pObj.optString("id", ""),
                                pObj.optString("name", "")
                        ));
                    }
                }
                cachedProfiles = profiles;
                currentProfileId = activeProfileId;
                notifyProfilesSynced(profiles, activeProfileId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing incoming packet: " + e.getMessage());
        }
    }

    // Thread-safe listeners notify helpers
    private void notifyServerDiscovered(final String ip, final int port, final String hostname) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onServerDiscovered(ip, port, hostname);
                }
            }
        });
    }

    private void notifyConnectionSuccess(final String token) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onConnectionSuccess(token);
                }
            }
        });
    }

    private void notifyConnectionFailed(final String reason) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onConnectionFailed(reason);
                }
            }
        });
    }

    private void notifyDisconnected() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onDisconnected();
                }
            }
        });
    }

    private void notifyButtonsSynced(final List<ShortcutButton> buttons) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onButtonsSynced(buttons);
                }
            }
        });
    }

    private void notifyTransitionToGrid() {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onTransitionToGrid();
                }
            }
        });
    }

    private void notifyPerformanceUpdated(final int cpu, final int gpu, final int ram, final int temp, final String wifi) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onPerformanceUpdated(cpu, gpu, ram, temp, wifi);
                }
            }
        });
    }

    private void notifyProfilesSynced(final List<ProfileInfo> profiles, final String activeProfileId) {
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (NetworkListener l : listeners) {
                    l.onProfilesSynced(profiles, activeProfileId);
                }
            }
        });
    }
}

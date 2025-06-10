package li.power.app.wearos.teslakeywearble.services;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanFilter;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import com.tesla.generated.universalmessage.UniversalMessage;
import li.power.app.wearos.teslakeywearble.R;
import li.power.app.wearos.teslakeywearble.database.CarDao;
import li.power.app.wearos.teslakeywearble.models.Car;
import li.power.app.wearos.teslakeywearble.utils.KeyUtils;
import org.bouncycastle.util.encoders.Hex;

import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * BLE service - Implement the Session management process of Tesla's official vehicle-command
 * Reference: https://github.com/teslamotors/vehicle-command/blob/main/internal/dispatcher/
 */
public class BLEService extends Service {

    private static final String TAG = "BLEService";

    // Front service notification constants
    private static final String CHANNEL_ID = "tesla_ble_service";
    private static final String CHANNEL_NAME = "Tesla BLE Service";
    private static final int NOTIFICATION_ID = 1;
    private static final String ONGOING_ACTIVITY_TAG = "tesla_connection";

    // Tesla VCSEC Service & Characteristics UUIDs
    private static final String TESLA_SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e";
    private static final String TESLA_WRITE_CHARACTERISTIC_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e";
    private static final String TESLA_READ_CHARACTERISTIC_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e";
    private static final String CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    // Tesla掃描相關常量
    private static final ParcelUuid TESLA_BEACON_UUID = ParcelUuid.fromString("00001122-0000-1000-8000-00805F9B34FB");
    private static final long SCAN_TIMEOUT_MS = 30000; // 30秒掃描超時
    private static final long SCAN_RETRY_DELAY_MS = 10000; // 10秒重試延遲

    // 分片訊息處理常量
    private static final long FRAGMENT_TIMEOUT_MS = 10000; // 10秒分片接收超時
    private static final int MAX_MESSAGE_SIZE = 4096; // 最大訊息大小

    // Connection status constants
    public static final String CONNECTION_STATUS_DISCONNECTED = "DISCONNECTED";
    public static final String CONNECTION_STATUS_CONNECTING = "CONNECTING";
    public static final String CONNECTION_STATUS_CONNECTED = "CONNECTED";
    public static final String CONNECTION_STATUS_SESSION_ESTABLISHING = "SESSION_ESTABLISHING";
    public static final String CONNECTION_STATUS_READY = "READY";
    public static final String CONNECTION_STATUS_ERROR = "ERROR";
    public static final String CONNECTION_STATUS_SCANNING = "SCANNING";

    // Automatic retry constants
    private static final int MAX_RETRY_COUNT = 5;           // Maximum retry count
    private static final long RETRY_DELAY_MS = 8000;       // 初始延遲提高到8秒
    private static final long RETRY_INCREASE_MS = 5000;    // 每次重試增加5秒
    private static final long MAX_RETRY_DELAY_MS = 60000;  // 最大延遲增加到60秒

    // Session state enums
    public enum SessionState {
        INACTIVE,
        HANDSHAKE_REQUEST_SENT,
        HANDSHAKE_RESPONSE_RECEIVED,
        AUTHENTICATED,
        ERROR
    }

    /**
     * 分片訊息接收狀態類
     */
    private static class FragmentReceiveState {
        private int expectedLength = -1;        // 預期的總訊息長度
        private byte[] buffer;                  // 接收緩衝區
        private int receivedLength = 0;         // 已接收的長度
        private long startTime;                 // 開始接收時間
        private boolean isFirstFragment = true; // 是否為第一個分片

        public FragmentReceiveState() {
            this.startTime = System.currentTimeMillis();
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - startTime > timeoutMs;
        }

        public void reset() {
            expectedLength = -1;
            buffer = null;
            receivedLength = 0;
            startTime = System.currentTimeMillis();
            isFirstFragment = true;
        }
    }

    // Service Binder
    public class BLEServiceBinder extends Binder {
        public BLEService getService() {
            return BLEService.this;
        }
    }

    // Member variables
    private final IBinder binder = new BLEServiceBinder();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;
    private BluetoothLeScanner bluetoothLeScanner;
    private Handler handler;
    private CarDao carDao;

    // LE掃描相關
    private boolean isScanning = false;
    private Runnable scanTimeoutRunnable;
    private String scanTargetVehicleId = null;
    private int scanRetryCount = 0;
    private static final int MAX_SCAN_RETRY_COUNT = 10; // 最大掃描重試次數

    // Vehicle connection management
    private Map<String, BluetoothGatt> vehicleConnections = new ConcurrentHashMap<>();
    private Map<String, Map<UniversalMessage.Domain, SessionManager.Session>> vehicleSessions = new ConcurrentHashMap<>();
    private Map<String, BluetoothGattCharacteristic> writeCharacteristics = new ConcurrentHashMap<>();
    private Map<String, BluetoothGattCharacteristic> readCharacteristics = new ConcurrentHashMap<>();

    // 分片訊息接收狀態管理
    private Map<String, FragmentReceiveState> fragmentStates = new ConcurrentHashMap<>();

    // Store vehicle private keys
    private Map<String, PrivateKey> vehiclePrivateKeys = new ConcurrentHashMap<>();

    // Currently selected vehicle
    private String currentVehicleId = null;
    private String connectionStatus = CONNECTION_STATUS_DISCONNECTED;

    byte[] connectionId = new byte[16];

    // Automatic retry management
    private int retryCount = 0;
    private boolean autoRetryEnabled = true;
    private Runnable retryConnectionRunnable;
    private String vin;
    
    // 定期清理任務
    private static final long CLEANUP_INTERVAL_MS = 30000; // 30秒清理一次
    private Runnable fragmentCleanupRunnable;

    // Service callbacks
    public interface BLEServiceCallback {
        void onVehicleConnected(String vehicleId);

        void onVehicleDisconnected(String vehicleId);

        void onSessionEstablished(String vehicleId, UniversalMessage.Domain domain);

        void onSessionFailed(String vehicleId, UniversalMessage.Domain domain, String error);

        void onCommandResponse(String vehicleId, byte[] response);

        void onVehicleStatusReceived(String vehicleId, SessionManager.VehicleStatus status);

        void onError(String vehicleId, String error);

        // Added connection status change callback
        void onConnectionStatusChanged(String vehicleId, String status);

        // Added vehicle operation result callback
        void onVehicleOperationResult(String operation, boolean success, String message);

    }

    private BLEServiceCallback serviceCallback;

    // Notification and Ongoing Activity management
    private NotificationManager notificationManager;

    /**
     * LE掃描回調
     */
    private ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            try {
                BluetoothDevice device = result.getDevice();
                String deviceAddress = device.getAddress();
                String deviceName = device.getName();
                int rssi = result.getRssi();

                Log.d(TAG, "掃描到設備: " + deviceName + " 地址: " + deviceAddress + " RSSI: " + rssi);

                // 檢查是否是目標車輛
                if (scanTargetVehicleId != null && scanTargetVehicleId.equals(deviceAddress)) {
                    Log.d(TAG, "找到目標車輛: " + deviceAddress);

                    // 重置掃描重試計數器
                    scanRetryCount = 0;

                    // 停止掃描並清除目標
                    stopLEScan(true);

                    // 開始GATT連接
                    handler.postDelayed(() -> {
                        Log.d(TAG, "開始GATT連接到已掃描的車輛: " + deviceAddress);
                        directConnectToVehicle(deviceAddress);
                    }, 500);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "處理掃描結果時權限錯誤", e);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "LE掃描失敗，錯誤碼: " + errorCode + " (重試 " + scanRetryCount + "/" + MAX_SCAN_RETRY_COUNT + ")");

            isScanning = false;

            // 如果掃描失敗，等待一段時間後重新掃描
            if (scanTargetVehicleId != null) {
                if (scanRetryCount < MAX_SCAN_RETRY_COUNT) {
                    scanRetryCount++;
                    Log.d(TAG, "掃描失敗，將在 " + (SCAN_RETRY_DELAY_MS / 1000) + " 秒後重新掃描: " + scanTargetVehicleId);
                    handler.postDelayed(() -> {
                        if (scanTargetVehicleId != null) { // 確認還有目標車輛
                            Log.d(TAG, "重新開始掃描車輛（掃描失敗恢復）: " + scanTargetVehicleId);
                            startLEScanForVehicle(scanTargetVehicleId);
                        }
                    }, SCAN_RETRY_DELAY_MS);
                } else {
                    Log.w(TAG, "達到最大掃描重試次數，進入自動重連模式: " + scanTargetVehicleId);
                    String targetVehicleId = scanTargetVehicleId;
                    scanTargetVehicleId = null; // 清除掃描目標
                    updateConnectionStatus(CONNECTION_STATUS_ERROR);
                    if (targetVehicleId.equals(currentVehicleId)) {
                        startAutoRetry();
                    }
                }
            }
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BLEService created");

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        carDao = new CarDao(this);

        // 初始化LE掃描器
        if (bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        // Initialize BouncyCastle
        KeyUtils.setupBouncyCastle();

        // Initialize notification manager
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Start foreground service
        startForegroundService();

        // 檢查並清理現有的失效連線
        handler.post(() -> checkAndCleanupConnections());

        // Restore previously saved vehicle and start automatic connection
        restoreCurrentVehicle();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "BLEService destroyed");

        // Stop LE scan
        stopLEScan();

        // Stop automatic retry and periodic updates
        stopAutoRetry();


        // Disconnect all vehicles
        disconnectAllVehicles();
        
        // 清理所有分片狀態
        fragmentStates.clear();

        // Stop foreground service
        stopForeground(true);
    }

    public void setServiceCallback(BLEServiceCallback callback) {
        this.serviceCallback = callback;
    }

    /**
     * Connect to vehicle and start Sessions - Mimic Tesla's StartSessions process
     * 使用LE掃描來節省電力，只在找到車輛時才連接
     */
    public boolean connectToVehicle(String vehicleId) {
        Log.d(TAG, "嘗試連線到車輛: " + vehicleId);

        if (bluetoothAdapter == null) {
            Log.e(TAG, "藍芽適配器不可用");
            notifyError(vehicleId, getString(R.string.bluetooth_not_available));
            return false;
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "LE掃描器不可用");
            notifyError(vehicleId, getString(R.string.ble_scanner_not_available));
            return false;
        }

        try {
            // 檢查現有連線狀態
            BluetoothGatt existingGatt = vehicleConnections.get(vehicleId);
            if (existingGatt != null) {
                Log.d(TAG, "發現現有連線，檢查狀態: " + vehicleId);

                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(vehicleId);
                int connectionState = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
                Log.d(TAG, "現有連線狀態: " + connectionState + " (2=已連線)");

                if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "設備已連線，檢查服務狀態: " + vehicleId);

                    if (writeCharacteristics.containsKey(vehicleId) && readCharacteristics.containsKey(vehicleId)) {
                        Log.d(TAG, "連線和特性都存在，直接開始 Session: " + vehicleId);

                        if (vehicleId.equals(currentVehicleId)) {
                            resetRetryCount();
                            stopAutoRetry();
                        }

                        updateConnectionStatus(CONNECTION_STATUS_CONNECTED);
                        startSessions(vehicleId);
                        return true;
                    } else {
                        Log.d(TAG, "連線存在但特性未設定，重新探索服務: " + vehicleId);
                        boolean discoveryStarted = existingGatt.discoverServices();
                        if (discoveryStarted) {
                            updateConnectionStatus(CONNECTION_STATUS_CONNECTING);
                            return true;
                        } else {
                            Log.w(TAG, "無法開始服務探索，斷開現有連線重新連接");
                            disconnectVehicle(vehicleId);
                        }
                    }
                } else {
                    Log.d(TAG, "現有連線已斷開，清理並重新連接: " + vehicleId);
                    disconnectVehicle(vehicleId);
                }
            }

            // 使用LE掃描來尋找車輛
            Log.d(TAG, "開始LE掃描尋找車輛: " + vehicleId);
            updateConnectionStatus(CONNECTION_STATUS_SCANNING);
            return startLEScanForVehicle(vehicleId);

        } catch (SecurityException e) {
            Log.e(TAG, "連線車輛時權限錯誤", e);
            notifyError(vehicleId, getString(R.string.permission_error));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "連線車輛時發生錯誤", e);
            notifyError(vehicleId, getString(R.string.connection_failed, e.getMessage()));
            return false;
        }
    }

    /**
     * 開始LE掃描尋找特定車輛
     */
    private boolean startLEScanForVehicle(String vehicleId) {
        if (bluetoothLeScanner == null || isScanning) {
            Log.w(TAG, "掃描器不可用或已在掃描中");
            return false;
        }

        try {
            Log.d(TAG, "開始LE掃描尋找車輛: " + vehicleId);

            // 如果是新的車輛目標，重置掃描重試計數器
            if (!vehicleId.equals(scanTargetVehicleId)) {
                scanRetryCount = 0;
                Log.d(TAG, "設定新的掃描目標，重置掃描重試計數器");
            }

            scanTargetVehicleId = vehicleId;
            isScanning = true;

            // 設置掃描設定 - 使用LOW_POWER模式節省電池
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0)
                    .setLegacy(true)
                    .build();

            // 設置掃描過濾器 - 只掃描Tesla beacon
            List<ScanFilter> filters = new ArrayList<>();
            ScanFilter.Builder filterBuilder = new ScanFilter.Builder();
            filterBuilder.setServiceUuid(TESLA_BEACON_UUID);
            filters.add(filterBuilder.build());

            // 開始掃描
            bluetoothLeScanner.startScan(filters, settings, scanCallback);

            // 設置掃描超時
            scanTimeoutRunnable = () -> {
                Log.w(TAG, "LE掃描超時，未找到車輛: " + vehicleId + " (重試 " + scanRetryCount + "/" + MAX_SCAN_RETRY_COUNT + ")");
                stopLEScan(false); // 保留目標車輛 ID


                    Log.d(TAG, "掃描超時，將在 " + (SCAN_RETRY_DELAY_MS / 1000) + " 秒後重新掃描: " + vehicleId);
                    handler.postDelayed(() -> {
                        if (vehicleId.equals(scanTargetVehicleId)) { // 確認還是同一個目標車輛
                            Log.d(TAG, "重新開始掃描車輛: " + vehicleId);
                            startLEScanForVehicle(vehicleId);
                        }
                    }, SCAN_RETRY_DELAY_MS);

            };
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);

            Log.d(TAG, "LE掃描已開始，掃描超時: " + SCAN_TIMEOUT_MS + "ms");
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "開始掃描時權限錯誤", e);
            isScanning = false;
            scanTargetVehicleId = null;
            return false;
        } catch (Exception e) {
            Log.e(TAG, "開始掃描時發生錯誤", e);
            isScanning = false;
            scanTargetVehicleId = null;
            return false;
        }
    }

    /**
     * 停止LE掃描
     */
    private void stopLEScan() {
        stopLEScan(true);
    }

    /**
     * 停止LE掃描
     *
     * @param clearTarget 是否清除掃描目標
     */
    private void stopLEScan(boolean clearTarget) {
        if (bluetoothLeScanner != null && isScanning) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                Log.d(TAG, "LE掃描已停止");
            } catch (SecurityException e) {
                Log.e(TAG, "停止掃描時權限錯誤", e);
            } catch (Exception e) {
                Log.e(TAG, "停止掃描時發生錯誤", e);
            }
        }

        isScanning = false;
        if (clearTarget) {
            scanTargetVehicleId = null;
        }

        // 取消掃描超時
        if (scanTimeoutRunnable != null) {
            handler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    /**
     * 直接連接到車輛（不通過掃描）
     */
    private boolean directConnectToVehicle(String vehicleId) {
        Log.d(TAG, "直接建立GATT連線: " + vehicleId);

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(vehicleId);
            if (device == null) {
                Log.e(TAG, "找不到設備: " + vehicleId);
                notifyError(vehicleId, getString(R.string.device_not_found));
                return false;
            }

            updateConnectionStatus(CONNECTION_STATUS_CONNECTING);
            BluetoothGatt gatt = device.connectGatt(this, false, createGattCallback(vehicleId));
            if (gatt != null) {
                vehicleConnections.put(vehicleId, gatt);
                return true;
            } else {
                Log.e(TAG, "GATT 連線建立失敗: " + vehicleId);
                notifyError(vehicleId, getString(R.string.gatt_connection_creation_failed));
                return false;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "直接連線時權限錯誤", e);
            notifyError(vehicleId, getString(R.string.permission_error_connection));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "直接連線時發生錯誤", e);
            notifyError(vehicleId, getString(R.string.direct_connection_failed, e.getMessage()));
            return false;
        }
    }

    /**
     * Disconnect vehicle connection
     */
    public void disconnectVehicle(String vehicleId) {
        Log.d(TAG, "Disconnecting vehicle connection: " + vehicleId);

        // 如果正在掃描這個車輛，停止掃描並清除目標
        if (isScanning && vehicleId.equals(scanTargetVehicleId)) {
            stopLEScan(true);
        }

        BluetoothGatt gatt = vehicleConnections.get(vehicleId);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Error disconnecting connection", e);
            }
        }

        // Clean up resources
        vehicleConnections.remove(vehicleId);
        vehicleSessions.remove(vehicleId);
        writeCharacteristics.remove(vehicleId);
        readCharacteristics.remove(vehicleId);
        
        // 清理分片接收狀態
        clearFragmentState(vehicleId);

        // If disconnected is currently selected vehicle, update status
        if (vehicleId.equals(currentVehicleId)) {
            updateConnectionStatus(CONNECTION_STATUS_DISCONNECTED);
        }

        if (serviceCallback != null) {
            serviceCallback.onVehicleDisconnected(vehicleId);
        }
    }

    /**
     * Disconnect all vehicle connections
     */
    public void disconnectAllVehicles() {
        for (String vehicleId : vehicleConnections.keySet()) {
            disconnectVehicle(vehicleId);
        }
    }

    /**
     * Start Sessions for specific vehicle - Mimic Tesla's StartSessions
     */
    private void startSessions(String vehicleId) {
        Log.d(TAG, "Starting Sessions for vehicle: " + vehicleId);
        updateConnectionStatus(CONNECTION_STATUS_SESSION_ESTABLISHING);

        // Initialize Session mapping
        Map<UniversalMessage.Domain, SessionManager.Session> sessions = new HashMap<>();
        new SecureRandom().nextBytes(connectionId);
        // 嘗試從資料庫中恢復之前的Session
        Car car = null;
        try {
            CarDao carDao = new CarDao(this);
            car = carDao.getCarByBleAddress(vehicleId);

            if (car != null && car.getVcsecEpoch() != null && car.getVcsecCounter() > 0 && car.getVcsecSessionKey() != null) {
                Log.d(TAG, "嘗試從資料庫恢復之前的Session: " + vehicleId);

                // 現在只建立VCSEC domain的Session，嘗試恢復
                PrivateKey privateKey = KeyUtils.getPrivateKey(getApplicationContext());
                vehiclePrivateKeys.put(vehicleId, privateKey);

                // 創建Session並從資料庫中載入資訊
                SessionManager.Session vcsecSession = new SessionManager.Session(
                        UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY,
                        car.getVin(), // 我們使用VIN或BLE地址作為標識
                        privateKey
                );

                // 載入之前保存的Session資訊
                SessionManager.loadSessionFromDatabase(vcsecSession, getApplicationContext(), vehicleId);

                // 如果Session載入成功，直接使用它（檢查SharedSecret是否也載入成功）
                if (vcsecSession.getEpoch() != null && vcsecSession.getCounter() > 0 && vcsecSession.getSharedSecret() != null) {
                    vcsecSession.setAuthenticated(true);
                    sessions.put(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY, vcsecSession);
                    vehicleSessions.put(vehicleId, sessions);

                    Log.d(TAG, "成功從資料庫恢復Session: " + vehicleId + ", SharedSecret長度: " + vcsecSession.getSharedSecret().length);

                    // 通知Session已建立
                    if (serviceCallback != null) {
                        serviceCallback.onSessionEstablished(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
                    }

                    // 檢查是否所有必要的Session已建立
                    checkIfReady(vehicleId);

                    // 如果成功恢復Session，直接返回
                    return;
                } else {
                    Log.d(TAG, "資料庫中的Session資訊不完整，將重新建立Session (Epoch: " +
                            (vcsecSession.getEpoch() != null) + ", Counter: " + vcsecSession.getCounter() +
                            ", SharedSecret: " + (vcsecSession.getSharedSecret() != null) + ")");
                }
            } else {
                Log.d(TAG, "資料庫中沒有完整的Session資訊，將建立新Session");
            }
        } catch (Exception e) {
            Log.e(TAG, "從資料庫恢復Session失敗: " + e.getMessage(), e);
            // 失敗後會繼續嘗試建立新的Session
        }

        // Generate vehicle-specific private key
        try {
            vehiclePrivateKeys.put(vehicleId, KeyUtils.getPrivateKey(getApplicationContext()));

            // 只建立 VCSEC domain (移除 INFOTAINMENT)
            UniversalMessage.Domain[] supportedDomains = {
                    UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY
            };

            for (UniversalMessage.Domain domain : supportedDomains) {
                // Start handshake
                startHandshakeForDomain(vehicleId, domain, KeyUtils.getPrivateKey(getApplicationContext()));
            }

            vehicleSessions.put(vehicleId, sessions);

        } catch (Exception e) {
            Log.e(TAG, "Vehicle private key generation failed: " + vehicleId, e);
            notifyError(vehicleId, "Session start failed: " + e.getMessage());
            updateConnectionStatus(CONNECTION_STATUS_ERROR);
        }
    }

    /**
     * Start handshake for specific domain
     */
    private void startHandshakeForDomain(String vehicleId, UniversalMessage.Domain domain, PrivateKey privateKey) {
        Log.d(TAG, "Starting handshake for domain: " + vehicleId + ", domain: " + domain);

        try {
            // Use SessionManager to generate handshake request
            byte[] handshakeRequest = SessionManager.buildSessionInfoRequest(domain, privateKey, connectionId);

            // Send handshake request
            sendMessage(vehicleId, handshakeRequest);

            Log.d(TAG, "Handshake request sent: " + vehicleId + ", domain: " + domain);

        } catch (Exception e) {
            Log.e(TAG, "Handshake start failed: " + vehicleId + ", domain: " + domain, e);
            notifySessionFailed(vehicleId, domain, "Handshake start failed: " + e.getMessage());
        }
    }

    /**
     * Send message to vehicle
     */
    private boolean sendMessage(String vehicleId, byte[] data) {

        Log.d(TAG, "發送: " + Hex.toHexString(data));

        byte[] msg = new byte[data.length + 2];
        System.arraycopy(data, 0, msg, 2, data.length);

        msg[0] = (byte)((data.length >> 8) & 0xFF);
        msg[1] = (byte)(data.length & 0xFF);

        BluetoothGattCharacteristic writeChar = writeCharacteristics.get(vehicleId);
        if (writeChar == null) {
            Log.e(TAG, "Write characteristic not found: " + vehicleId);
            return false;
        }

        BluetoothGatt gatt = vehicleConnections.get(vehicleId);
        if (gatt == null) {
            Log.e(TAG, "GATT connection not found: " + vehicleId);
            return false;
        }

        try {
            Log.d(TAG, "TX: " + Hex.toHexString(msg));
            writeChar.setValue(msg);
            boolean result = gatt.writeCharacteristic(writeChar);

            // 如果發送成功，並且是VCSEC的Session，更新計數器
            if (result) {
                Map<UniversalMessage.Domain, SessionManager.Session> sessions = vehicleSessions.get(vehicleId);
                if (sessions != null) {
                    SessionManager.Session session = sessions.get(UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
                    if (session != null) {
                        // 保存更新後的計數器到資料庫
                        SessionManager.saveCounterToDatabase(session, getApplicationContext(), vehicleId);
                    }
                }
            }

            return result;
        } catch (SecurityException e) {
            Log.e(TAG, "Error sending message", e);
            return false;
        }
    }

    /**
     * Handle vehicle response with fragment support
     */
    private void handleVehicleResponse(String vehicleId, byte[] data) {
        Log.d(TAG, "收到車輛數據: " + vehicleId + ", 數據長度: " + data.length + ", 數據: " + bytesToHex(data));

        if (data == null || data.length == 0) {
            Log.w(TAG, "收到空數據，忽略: " + vehicleId);
            return;
        }

        try {
            // 清理過期的分片狀態
            cleanupExpiredFragments();

            // 獲取或創建分片接收狀態
            FragmentReceiveState state = fragmentStates.computeIfAbsent(vehicleId, k -> new FragmentReceiveState());

            // 檢查是否超時
            if (state.isExpired(FRAGMENT_TIMEOUT_MS)) {
                Log.w(TAG, "分片接收超時，重置狀態: " + vehicleId);
                state.reset();
            }

            // 處理第一個分片（包含長度資訊）
            if (state.isFirstFragment) {
                if (data.length < 2) {
                    Log.e(TAG, "第一個分片長度不足，無法讀取訊息長度: " + vehicleId);
                    state.reset();
                    return;
                }

                // 解析訊息長度（前2個字節，大端序）
                state.expectedLength = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                
                Log.d(TAG, "解析到訊息長度: " + state.expectedLength + " bytes, 車輛: " + vehicleId);

                // 驗證訊息長度的合理性
                if (state.expectedLength <= 0 || state.expectedLength > MAX_MESSAGE_SIZE) {
                    Log.e(TAG, "無效的訊息長度: " + state.expectedLength + ", 車輛: " + vehicleId);
                    state.reset();
                    return;
                }

                // 初始化緩衝區
                state.buffer = new byte[state.expectedLength];
                state.isFirstFragment = false;

                // 複製第一個分片的實際訊息內容（跳過前2個長度字節）
                int actualDataLength = data.length - 2;
                if (actualDataLength > 0) {
                    int copyLength = Math.min(actualDataLength, state.expectedLength);
                    System.arraycopy(data, 2, state.buffer, 0, copyLength);
                    state.receivedLength = copyLength;
                    
                    Log.d(TAG, "第一個分片已處理: " + copyLength + " bytes, 進度: " + 
                          state.receivedLength + "/" + state.expectedLength + ", 車輛: " + vehicleId);
                }
            } else {
                // 處理後續分片（純訊息內容）
                if (state.buffer == null) {
                    Log.e(TAG, "緩衝區未初始化，重置狀態: " + vehicleId);
                    state.reset();
                    return;
                }

                // 計算還需要接收的長度
                int remainingLength = state.expectedLength - state.receivedLength;
                int copyLength = Math.min(data.length, remainingLength);

                if (copyLength > 0) {
                    System.arraycopy(data, 0, state.buffer, state.receivedLength, copyLength);
                    state.receivedLength += copyLength;
                    
                    Log.d(TAG, "後續分片已處理: " + copyLength + " bytes, 進度: " + 
                          state.receivedLength + "/" + state.expectedLength + ", 車輛: " + vehicleId);
                }
            }

            // 檢查是否已接收完整訊息
            if (state.receivedLength >= state.expectedLength) {
                Log.d(TAG, "完整訊息已接收，開始處理: " + vehicleId + ", 總長度: " + state.expectedLength);
                
                // 創建完整訊息的副本
                byte[] completeMessage = new byte[state.expectedLength];
                System.arraycopy(state.buffer, 0, completeMessage, 0, state.expectedLength);
                
                // 清理狀態
                fragmentStates.remove(vehicleId);
                
                // 處理完整訊息
                try {
                    processCompleteMessage(vehicleId, completeMessage);
                } catch (Exception e) {
                    Log.e(TAG, "處理完整訊息時發生錯誤: " + vehicleId, e);
                    notifyError(vehicleId, getString(R.string.data_processing_failed, e.getMessage()));
                }
                
            } else {
                Log.d(TAG, "等待更多分片: " + vehicleId + ", 進度: " + 
                      state.receivedLength + "/" + state.expectedLength);
            }

        } catch (Exception e) {
            Log.e(TAG, "處理車輛回應時發生錯誤: " + vehicleId, e);
            
            // 發生錯誤時清理狀態
            fragmentStates.remove(vehicleId);
            
            notifyError(vehicleId, getString(R.string.data_processing_failed, e.getMessage()));
        }
    }

    /**
     * 清理過期的分片接收狀態
     */
    private void cleanupExpiredFragments() {
        long currentTime = System.currentTimeMillis();
        
        fragmentStates.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired(FRAGMENT_TIMEOUT_MS);
            if (expired) {
                Log.w(TAG, "清理過期的分片狀態: " + entry.getKey());
            }
            return expired;
        });
    }

    /**
     * 手動清理指定車輛的分片狀態
     */
    private void clearFragmentState(String vehicleId) {
        FragmentReceiveState state = fragmentStates.remove(vehicleId);
        if (state != null) {
            Log.d(TAG, "清理車輛分片狀態: " + vehicleId);
        }
    }

    /**
     * 處理完整的消息
     */
    private void processCompleteMessage(String vehicleId, byte[] completeData) {
        Log.d(TAG, "處理完整消息: " + vehicleId + ", 數據長度: " + completeData.length);

        try {

            handleHandshakeResponse(vehicleId, completeData, vin);
            handleCommandAndStatusResponse(vehicleId, completeData);


        } catch (Exception e) {
            Log.d(TAG, "不是握手回應，嘗試作為命令/狀態回應處理: " + e.getMessage());

            // Try to parse as command/status response
            try {
                handleCommandAndStatusResponse(vehicleId, completeData);
            } catch (Exception e2) {
                Log.e(TAG, "處理命令/狀態回應時發生錯誤", e2);
                notifyError(vehicleId, getString(R.string.response_processing_failed_detail, e2.getMessage()));
            }
        }
    }

    /**
     * Handle handshake response - 直接解析完整的回應數據
     */
    private void handleHandshakeResponse(String vehicleId, byte[] data, String vin) throws Exception {
        PrivateKey privateKey = vehiclePrivateKeys.get(vehicleId);
        if (privateKey == null) {
            throw new Exception("Vehicle private key not found");
        }

        Log.d(TAG, "處理握手回應: " + vehicleId + ", 數據長度: " + data.length);
        Log.d(TAG, "握手回應數據: " + bytesToHex(data));

        // 直接解析 RoutableMessage，不需要處理長度前綴
        try {
            // 嘗試直接解析為 RoutableMessage
            UniversalMessage.RoutableMessage routableMessage = UniversalMessage.RoutableMessage.parseFrom(data);

            // 根據 to_destination 判斷 domain
            UniversalMessage.Domain responseDomain = null;
            if (routableMessage.hasFromDestination() && routableMessage.getFromDestination().hasDomain()) {
                responseDomain = routableMessage.getFromDestination().getDomain();
            } else {
                // 如果沒有明確的 domain，嘗試根據內容判斷或使用預設值
                Log.w(TAG, "回應中沒有明確的 domain，將嘗試兩個 domain");
            }

            SessionManager.Session session;
            if (responseDomain != null) {
                Log.d(TAG, "From Domain: " + responseDomain.name());
                if(!(responseDomain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY || responseDomain== UniversalMessage.Domain.DOMAIN_INFOTAINMENT)){
                    return;
                }
                try {
                    session = SessionManager.handleSessionInfoResponse(data, privateKey, responseDomain, vin);
                    if(session== null) {
                        Log.d(TAG,"No session found");
                        return;
                    }
                    Log.d(TAG, "成功建立 session for domain: " + responseDomain);

                    session.setVin(carDao.getCarByBleAddress(vehicleId).getVin());

                    SessionManager.saveSessionToDatabase(session, getApplicationContext(), vehicleId);
                    Log.d(TAG, "Session資訊已保存到資料庫: " + vehicleId + ", domain: " + responseDomain.name());
                } catch (Exception e) {
                    return;
                }
            } else {
                Log.w(TAG, "略過其他 Domain");
                return;
            }

            // 保存 session
            Map<UniversalMessage.Domain, SessionManager.Session> sessions = vehicleSessions.computeIfAbsent(vehicleId, k -> new HashMap<>());
            sessions.put(responseDomain, session);

            Log.d(TAG, "Session established successfully: " + vehicleId + ", domain: " + responseDomain);

            if (serviceCallback != null) {
                serviceCallback.onSessionEstablished(vehicleId, responseDomain);
            }

            // Check if all necessary Sessions are established
            checkIfReady(vehicleId);

        } catch (Exception e) {
            Log.e(TAG, "解析握手回應時發生錯誤: " + e.getMessage(), e);

        }
        return;
    }

    /**
     * 將字節數組轉換為十六進制字符串（用於調試）
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X ", b));
        }
        return result.toString().trim();
    }

    /**
     * 將字節數組的指定範圍轉換為十六進制字符串（用於調試）
     */
    private String bytesToHex(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length <= 0 || offset + length > bytes.length) {
            return "";
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = offset; i < offset + length; i++) {
            result.append(String.format("%02X ", bytes[i]));
        }
        return result.toString().trim();
    }

    /**
     * Check if vehicle is ready
     */
    private void checkIfReady(String vehicleId) {
        // Only VCSEC Session is needed to be considered ready
        if (isSessionEstablished(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY)) {
            Log.d(TAG, "Vehicle ready: " + vehicleId);
            updateConnectionStatus(CONNECTION_STATUS_READY);

            getVehicleStatus(vehicleId);
        }
    }

    /**
     * Handle command and status response
     */
    private void handleCommandAndStatusResponse(String vehicleId, byte[] data) throws Exception {
        Log.d(TAG, "Handling command/status response: " + vehicleId);

        SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
        if (session == null) {
            throw new Exception("VCSEC Session not found");
        }

        // First try to parse as vehicle status response
        try {
            SessionManager.VehicleStatus vehicleStatus = SessionManager.ResponseParser.parseVehicleStatusResponse(session, data);

            Log.d(TAG, "Received vehicle status update: " + vehicleId +
                    ", Lock state: " + vehicleStatus.getLockStateDescription() +
                    ", Locked: " + vehicleStatus.isLocked() +
                    ", Frunk: " + (vehicleStatus.isFrunkClosed() ? "Closed" : "Open") +
                    ", Trunk: " + (vehicleStatus.isTrunkClosed() ? "Closed" : "Open"));

            // Always notify MainActivity of status updates
            if (serviceCallback != null) {
                serviceCallback.onVehicleStatusReceived(vehicleId, vehicleStatus);
            }
            return;

        } catch (Exception e) {
            Log.d(TAG, "Not vehicle status response, trying command status: " + e.getMessage());
        }

        // Try to parse as command status response
        try {
            boolean commandSuccess = SessionManager.ResponseParser.parseCommandStatusResponse(session, data);

            Log.d(TAG, "Received command status response: " + vehicleId + ", success: " + commandSuccess);

            // Notify command response
            if (serviceCallback != null) {
                serviceCallback.onCommandResponse(vehicleId, data);
            }

            // After a successful command, automatically request updated vehicle status
            if (commandSuccess) {
                Log.d(TAG, "Command successful, requesting updated vehicle status");
            }

            return;

        } catch (Exception e) {
            Log.d(TAG, "Not command status response: " + e.getMessage());
        }

        // If we reach here, it's an unknown response type
        Log.w(TAG, "Unknown response type received from vehicle: " + vehicleId);

        // Still notify as general command response
        if (serviceCallback != null) {
            serviceCallback.onCommandResponse(vehicleId, data);
        }
    }

    /**
     * Send vehicle command
     */
    public boolean sendVehicleCommand(String vehicleId, UniversalMessage.Domain domain, byte[] commandData) {
        SessionManager.Session session = getSession(vehicleId, domain);
        if (session == null || !session.isAuthenticated()) {
            Log.e(TAG, "Invalid or non-existent Session: " + vehicleId + ", domain: " + domain);
            return false;
        }

        try {
            boolean sendResult = sendMessage(vehicleId, commandData);

            // 如果命令發送成功，更新資料庫中的counter
            if (sendResult) {
                // 使用Session Manager的方法來保存counter到資料庫
                SessionManager.saveCounterToDatabase(session, getApplicationContext(), vehicleId);
                Log.d(TAG, "發送命令成功並更新資料庫中的counter: " + session.getCounter());
            }

            return sendResult;

        } catch (Exception e) {
            Log.e(TAG, "Vehicle command send failed", e);
            notifyError(vehicleId, "Command send failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Send VCSEC lock vehicle command
     */
    public boolean lockVehicle(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] lockCommand = SessionManager.VCSECCommands.createLockCommand(session);
            boolean success = sendMessage(vehicleId, lockCommand);

            if (success) {
                Log.d(TAG, "Lock command sent successfully: " + vehicleId);
            } else {
                Log.e(TAG, "Lock command send failed: " + vehicleId);
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "VCSEC lock vehicle command send failed", e);
            notifyError(vehicleId, "Lock vehicle failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Send VCSEC unlock vehicle command
     */
    public boolean unlockVehicle(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] unlockCommand = SessionManager.VCSECCommands.createUnlockCommand(session);
            boolean success = sendMessage(vehicleId, unlockCommand);

            if (success) {
                Log.d(TAG, "Unlock command sent successfully: " + vehicleId);
            } else {
                Log.e(TAG, "Unlock command send failed: " + vehicleId);
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "VCSEC unlock vehicle command send failed", e);
            notifyError(vehicleId, "Unlock vehicle failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Open Front Trunk (Frunk)
     */
    public boolean openFrunk(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] openFrunkCommand = SessionManager.VCSECCommands.createOpenFrontTrunkCommand(session);
            boolean success = sendMessage(vehicleId, openFrunkCommand);

            if (success) {
                Log.d(TAG, "Open frunk command sent successfully: " + vehicleId);
            } else {
                Log.e(TAG, "Open frunk command send failed: " + vehicleId);
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error sending open frunk command", e);
            notifyError(vehicleId, "Open frunk failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Open Trunk (Trunk)
     */
    public boolean openTrunk(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] openTrunkCommand = SessionManager.VCSECCommands.createOpenRearTrunkCommand(session);
            return sendMessage(vehicleId, openTrunkCommand);

        } catch (Exception e) {
            Log.e(TAG, "Error sending open Trunk command", e);
            notifyError(vehicleId, "Open Trunk failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Flash Lights
     */
    public boolean flashLights(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] flashLightsCommand = SessionManager.VCSECCommands.createFlashLightsCommand(session);
            return sendMessage(vehicleId, flashLightsCommand);

        } catch (Exception e) {
            Log.e(TAG, "Error sending flash lights command", e);
            notifyError(vehicleId, "Flash lights failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Wake vehicle
     */
    public boolean wakeVehicle(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] wakeCommand = SessionManager.VCSECCommands.createWakeVehicleCommand(session);
            return sendMessage(vehicleId, wakeCommand);

        } catch (Exception e) {
            Log.e(TAG, "Error sending wake vehicle command", e);
            notifyError(vehicleId, "Wake vehicle failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get vehicle status
     */
    public boolean getVehicleStatus(String vehicleId) {
        try {
            SessionManager.Session session = getSession(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
            if (session == null) {
                Log.e(TAG, "VCSEC Session not found: " + vehicleId);
                return false;
            }

            byte[] getStatusCommand = SessionManager.VCSECCommands.createGetVehicleStatusCommand(session);
            return sendMessage(vehicleId, getStatusCommand);

        } catch (Exception e) {
            Log.e(TAG, "Error sending get vehicle status command", e);
            notifyError(vehicleId, "Get vehicle status failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get Session
     */
    private synchronized SessionManager.Session getSession(String vehicleId, UniversalMessage.Domain domain) {
        Map<UniversalMessage.Domain, SessionManager.Session> sessions = vehicleSessions.get(vehicleId);
        return sessions != null ? sessions.get(domain) : null;
    }

    /**
     * Create GATT callback
     */
    private BluetoothGattCallback createGattCallback(String vehicleId) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                super.onConnectionStateChange(gatt, status, newState);

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "GATT 連線成功，準備探索服務: " + vehicleId);
                    updateConnectionStatus(CONNECTION_STATUS_CONNECTING); // 保持連線中狀態，等待服務探索完成

                    try {
                        // Discover services
                        boolean discoveryStarted = gatt.discoverServices();
                        if (!discoveryStarted) {
                            Log.e(TAG, "無法開始服務探索: " + vehicleId);
                            notifyError(vehicleId, "服務探索開始失敗");

                            // 如果是當前選擇的車輛，開始重試
                            if (vehicleId.equals(currentVehicleId)) {
                                startAutoRetry();
                            }
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "服務探索權限錯誤", e);
                        notifyError(vehicleId, "權限錯誤");
                        updateConnectionStatus(CONNECTION_STATUS_ERROR);

                        // 如果是當前選擇的車輛，開始重試
                        if (vehicleId.equals(currentVehicleId)) {
                            startAutoRetry();
                        }
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "車輛已斷線: " + vehicleId + ", status: " + status);

                    // 檢查是否為當前選擇的車輛斷線
                    boolean wasCurrentVehicle = vehicleId.equals(currentVehicleId);

                    disconnectVehicle(vehicleId);

                    // 如果是當前選擇的車輛斷線，開始自動重試
                    if (wasCurrentVehicle) {
                        Log.d(TAG, "當前選擇的車輛斷線，開始自動重試: " + vehicleId);
                        startAutoRetry();
                    }
                } else {
                    // 連線失敗的其他情況
                    Log.e(TAG, "GATT 連線失敗: " + vehicleId + ", status: " + status + ", newState: " + newState);
                    notifyError(vehicleId, "GATT 連線失敗，狀態: " + status);

                    // 如果是當前選擇的車輛連線失敗，開始重試
                    if (vehicleId.equals(currentVehicleId)) {
                        updateConnectionStatus(CONNECTION_STATUS_ERROR);
                        startAutoRetry();
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "服務探索成功: " + vehicleId);

                    // 尋找 Tesla VCSEC 服務
                    BluetoothGattService teslaService = gatt.getService(UUID.fromString(TESLA_SERVICE_UUID));
                    if (teslaService != null) {
                        Log.d(TAG, "找到 Tesla VCSEC 服務: " + vehicleId);

                        // 只有在成功找到服務後才算連線成功，重置重試計數器
                        if (vehicleId.equals(currentVehicleId)) {
                            resetRetryCount();
                            stopAutoRetry();
                        }

                        updateConnectionStatus(CONNECTION_STATUS_CONNECTED);
                        setupCharacteristics(vehicleId, gatt, teslaService);

                    } else {
                        Log.e(TAG, "找不到 Tesla VCSEC 服務: " + vehicleId);
                        notifyError(vehicleId, "找不到 Tesla VCSEC 服務");

                        // 如果是當前選擇的車輛，開始重試
                        if (vehicleId.equals(currentVehicleId)) {
                            updateConnectionStatus(CONNECTION_STATUS_ERROR);
                            startAutoRetry();
                        }
                    }
                } else {
                    Log.e(TAG, "服務探索失敗: " + vehicleId + ", 狀態: " + status);
                    notifyError(vehicleId, "服務探索失敗，狀態: " + status);

                    // 如果是當前選擇的車輛，開始重試
                    if (vehicleId.equals(currentVehicleId)) {
                        updateConnectionStatus(CONNECTION_STATUS_ERROR);
                        startAutoRetry();
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);

                // Handle vehicle response
                byte[] data = characteristic.getValue();
                if (data != null) {
                    handleVehicleResponse(vehicleId, data);
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                super.onMtuChanged(gatt, mtu, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU 設定成功: " + vehicleId + ", MTU: " + mtu);
                } else {
                    Log.w(TAG, "MTU 設定失敗: " + vehicleId + ", status: " + status + ", MTU: " + mtu);
                }

                // MTU 設定完成後繼續設定特性
                // （在 setupCharacteristics 中已經處理了特性設定）
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write successful, starting Sessions: " + vehicleId);

                    if (serviceCallback != null) {
                        serviceCallback.onVehicleConnected(vehicleId);
                    }

                    // Start Sessions
                    startSessions(vehicleId);
                } else {
                    Log.e(TAG, "Descriptor write failed: " + vehicleId + ", status: " + status);
                    notifyError(vehicleId, "Descriptor write failed");

                    // If it's currently selected vehicle, start retry
                    if (vehicleId.equals(currentVehicleId)) {
                        startAutoRetry();
                    }
                }
            }
        };
    }

    /**
     * Set characteristics and enable notifications
     */
    private void setupCharacteristics(String vehicleId, BluetoothGatt gatt, BluetoothGattService service) {
        try {
            // 首先請求更大的 MTU
            Log.d(TAG, "請求 MTU 設定為 517: " + vehicleId);
            boolean mtuRequested = gatt.requestMtu(517);
            if (!mtuRequested) {
                Log.w(TAG, "MTU 請求失敗，使用預設值: " + vehicleId);
            }

            BluetoothGattCharacteristic writeChar = service.getCharacteristic(UUID.fromString(TESLA_WRITE_CHARACTERISTIC_UUID));
            BluetoothGattCharacteristic readChar = service.getCharacteristic(UUID.fromString(TESLA_READ_CHARACTERISTIC_UUID));

            if (writeChar != null && readChar != null) {
                writeCharacteristics.put(vehicleId, writeChar);
                readCharacteristics.put(vehicleId, readChar);

                // Enable indications
                gatt.setCharacteristicNotification(readChar, true);

                // Write Client Characteristic Configuration descriptor
                BluetoothGattDescriptor descriptor = readChar.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID));
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            } else {
                Log.e(TAG, "Missing necessary characteristics: " + vehicleId);
                notifyError(vehicleId, "Missing necessary characteristics");

                // If it's currently selected vehicle, start retry
                if (vehicleId.equals(currentVehicleId)) {
                    startAutoRetry();
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Error setting characteristics: " + vehicleId, e);
            notifyError(vehicleId, "Permission error");

            // If it's currently selected vehicle, start retry
            if (vehicleId.equals(currentVehicleId)) {
                startAutoRetry();
            }
        }
    }

    /**
     * Get vehicle list
     */
    public List<Car> getPairedVehicles() {
        return carDao.getAllCars();
    }

    /**
     * Check if vehicle is connected
     */
    public boolean isVehicleConnected(String vehicleId) {
        return vehicleConnections.containsKey(vehicleId);
    }

    /**
     * Check if Session is established
     */
    public boolean isSessionEstablished(String vehicleId, UniversalMessage.Domain domain) {
        Map<UniversalMessage.Domain, SessionManager.Session> sessions = vehicleSessions.get(vehicleId);
        if (sessions != null) {
            SessionManager.Session session = sessions.get(domain);
            return session != null && session.isAuthenticated();
        }
        return false;
    }

    /**
     * Set currently selected vehicle and establish connection
     */
    public void selectVehicle(String vehicleId) {
        Log.d(TAG, "選擇車輛: " + vehicleId);

        // Stop any existing retries and periodic updates
        stopAutoRetry();

        // 先檢查並清理失效連線
        checkAndCleanupConnections();

        // If already connected to the same vehicle, reset retry counter but don't reconnect
        if (vehicleId.equals(currentVehicleId) && isVehicleConnected(vehicleId)) {
            Log.d(TAG, "已連線到此車輛: " + vehicleId);
            resetRetryCount();

            // 檢查是否需要重新建立 session
            if (!isReadyForCommands()) {
                Log.d(TAG, "車輛已連線但未準備好，重新開始 session");
                startSessions(vehicleId);
            }
            return;
        }

        // Disconnect currently connected vehicle
        if (currentVehicleId != null) {
            Log.d(TAG, "斷開當前選擇的車輛: " + currentVehicleId);
            disconnectVehicle(currentVehicleId);
        }

        // Set new target vehicle
        currentVehicleId = vehicleId;
        vin = carDao.getCarByBleAddress(vehicleId).getVin();

        resetRetryCount();
        updateConnectionStatus(CONNECTION_STATUS_CONNECTING);

        // Save currently selected vehicle
        saveCurrentVehicle(vehicleId);

        // Connect to new vehicle
        if (!connectToVehicle(vehicleId)) {
            Log.e(TAG, "初始車輛連線失敗，開始自動重試: " + vehicleId);
            startAutoRetry();
        }
    }

    /**
     * Get currently selected vehicle ID
     */
    public String getCurrentVehicleId() {
        return currentVehicleId;
    }

    /**
     * Get currently connected status
     */
    public String getConnectionStatus() {
        return connectionStatus;
    }

    /**
     * Update connection status and notify
     */
    private synchronized void updateConnectionStatus(String status) {
        this.connectionStatus = status;
        Log.d(TAG, "Connection status changed: " + status + " (Vehicle: " + currentVehicleId + ")");

        // Get currently selected vehicle name
        String vehicleName = null;
        if (currentVehicleId != null) {
            try {
                Car currentCar = carDao.getCarByBleAddress(currentVehicleId);
                if (currentCar != null) {
                    vehicleName = currentCar.getName();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting vehicle name", e);
            }
        }

        // Update Ongoing Activity
        String displayStatus;
        switch (status) {
            case CONNECTION_STATUS_CONNECTING:
                displayStatus = "Connecting";
                break;
            case CONNECTION_STATUS_CONNECTED:
                displayStatus = "Connected";
                break;
            case CONNECTION_STATUS_SESSION_ESTABLISHING:
                displayStatus = "Establishing session";
                break;
            case CONNECTION_STATUS_READY:
                displayStatus = "Ready";
                break;
            case CONNECTION_STATUS_ERROR:
                displayStatus = "Error";
                break;
            default:
                displayStatus = "Disconnected";
                break;
        }

        if (serviceCallback != null) {
            serviceCallback.onConnectionStatusChanged(currentVehicleId, status);
        }
    }

    /**
     * Lock vehicle - Called by MainActivity
     */
    public void lockVehicle() {
        if (currentVehicleId == null) {
            notifyOperationResult("LOCK", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isReadyForCommands()) {
            notifyOperationResult("LOCK", false, getString(R.string.vehicle_ready_commands));
            return;
        }

        Log.d(TAG, "Executing lock vehicle: " + currentVehicleId);
        boolean commandSent = lockVehicle(currentVehicleId);

        if (commandSent) {
            notifyOperationResult("LOCK", true, getString(R.string.lock_command_sent_msg));
        } else {
            notifyOperationResult("LOCK", false, getString(R.string.lock_command_send_failed));
        }
    }

    /**
     * Unlock vehicle - Called by MainActivity
     */
    public void unlockVehicle() {
        if (currentVehicleId == null) {
            notifyOperationResult("UNLOCK", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isReadyForCommands()) {
            notifyOperationResult("UNLOCK", false, getString(R.string.vehicle_ready_commands));
            return;
        }

        Log.d(TAG, "Executing unlock vehicle: " + currentVehicleId);
        boolean commandSent = unlockVehicle(currentVehicleId);

        if (commandSent) {
            notifyOperationResult("UNLOCK", true, getString(R.string.unlock_command_sent_msg));
        } else {
            notifyOperationResult("UNLOCK", false, getString(R.string.unlock_command_send_failed));
        }
    }

    /**
     * Open front trunk - Called by MainActivity
     */
    public void openFrunk() {
        if (currentVehicleId == null) {
            notifyOperationResult("OPEN_FRUNK", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isReadyForCommands()) {
            notifyOperationResult("OPEN_FRUNK", false, getString(R.string.vehicle_ready_commands));
            return;
        }

        Log.d(TAG, "Executing open frunk: " + currentVehicleId);
        boolean commandSent = openFrunk(currentVehicleId);

        if (commandSent) {
            notifyOperationResult("OPEN_FRUNK", true, getString(R.string.open_frunk_command_sent_msg));
        } else {
            notifyOperationResult("OPEN_FRUNK", false, getString(R.string.open_frunk_command_send_failed));
        }
    }

    /**
     * Open rear trunk - Called by MainActivity
     */
    public void openTrunk() {
        if (currentVehicleId == null) {
            notifyOperationResult("OPEN_TRUNK", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isReadyForCommands()) {
            notifyOperationResult("OPEN_TRUNK", false, getString(R.string.vehicle_ready_commands));
            return;
        }

        Log.d(TAG, "Executing open trunk: " + currentVehicleId);
        boolean commandSent = openTrunk(currentVehicleId);

        if (commandSent) {
            notifyOperationResult("OPEN_TRUNK", true, getString(R.string.open_trunk_command_sent_msg));

        } else {
            notifyOperationResult("OPEN_TRUNK", false, getString(R.string.open_trunk_command_send_failed));
        }
    }

    /**
     * Flash Lights - Called by MainActivity
     */
    public void flashLights() {
        if (currentVehicleId == null) {
            notifyOperationResult("FLASH_LIGHTS", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isReadyForCommands()) {
            notifyOperationResult("FLASH_LIGHTS", false, getString(R.string.vehicle_ready_commands));
            return;
        }

        Log.d(TAG, "Executing flash lights: " + currentVehicleId);
        boolean success = flashLights(currentVehicleId);
        notifyOperationResult("FLASH_LIGHTS", success, success ? getString(R.string.flash_lights_command_sent_msg) : getString(R.string.flash_lights_command_send_failed));
    }

    /**
     * Wake vehicle - Called by MainActivity
     */
    public void wakeVehicle() {
        if (currentVehicleId == null) {
            notifyOperationResult("WAKE_VEHICLE", false, getString(R.string.no_vehicle_selected));
            return;
        }

        if (!isVehicleConnected(currentVehicleId)) {
            notifyOperationResult("WAKE_VEHICLE", false, getString(R.string.vehicle_not_connected));
            return;
        }

        Log.d(TAG, "Executing wake vehicle: " + currentVehicleId);
        boolean success = wakeVehicle(currentVehicleId);
        notifyOperationResult("WAKE_VEHICLE", success, success ? getString(R.string.wake_vehicle_command_sent) : getString(R.string.wake_vehicle_command_send_failed));
    }

    /**
     * Get vehicle status - Called by MainActivity
     */
    public void requestVehicleStatus() {
        if (currentVehicleId == null) {
            return;
        }

        if (!isReadyForCommands()) {
            return;
        }

        getVehicleStatus(currentVehicleId);
    }

    /**
     * Check if vehicle is ready to receive commands
     */
    public boolean isReadyForCommands() {
        return isReadyForCommands(currentVehicleId);
    }

    /**
     * Check if specific vehicle is ready to receive commands
     */
    public boolean isReadyForCommands(String vehicleId) {
        if (vehicleId == null) return false;
        if (!isVehicleConnected(vehicleId)) return false;

        // Check if VCSEC Session is established
        return isSessionEstablished(vehicleId, UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY);
    }

    /**
     * Notify operation result
     */
    private void notifyOperationResult(String operation, boolean success, String message) {
        Log.d(TAG, "Operation result - " + operation + ": " + (success ? "Success" : "Failed") + " - " + message);

        if (serviceCallback != null) {
            serviceCallback.onVehicleOperationResult(operation, success, message);
        }
    }

    // Notification methods
    private void notifyError(String vehicleId, String error) {
        Log.e(TAG, "Error: " + vehicleId + " - " + error);

        // If it's currently selected vehicle, update status
        if (vehicleId.equals(currentVehicleId)) {
            updateConnectionStatus(CONNECTION_STATUS_ERROR);
        }

        if (serviceCallback != null) {
            serviceCallback.onError(vehicleId, error);
        }
    }

    private void notifySessionFailed(String vehicleId, UniversalMessage.Domain domain, String error) {
        Log.e(TAG, "Session failed: " + vehicleId + ", domain: " + domain + " - " + error);

        // If it's currently selected vehicle's session failed, update status
        if (vehicleId.equals(currentVehicleId)) {
            updateConnectionStatus(CONNECTION_STATUS_ERROR);
        }

        if (serviceCallback != null) {
            serviceCallback.onSessionFailed(vehicleId, domain, error);
        }
    }

    /**
     * Start foreground service
     */
    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, li.power.app.wearos.teslakeywearble.MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tesla Key")
                .setContentText("Active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);

        // Create Ongoing Activity
        createOngoingActivity(pendingIntent);

        startForeground(NOTIFICATION_ID, notification.build());
    }

    /**
     * Create Ongoing Activity (Wear OS specific)
     */
    private void createOngoingActivity(PendingIntent pendingIntent) {
        try {
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Tesla Key")
                    .setContentText("Active")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE);

            Log.d(TAG, "Ongoing Activity started");

        } catch (Exception e) {
            Log.w(TAG, "Failed to create Ongoing Activity", e);
        }
    }

    /**
     * Create notification channel
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Tesla BLE service");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_SECRET);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Start automatic retry connection
     */
    private void startAutoRetry() {
        if (!autoRetryEnabled || currentVehicleId == null) {
            Log.d(TAG, "Automatic retry disabled or no target vehicle");
            return;
        }

        // Cancel any existing retry tasks
        stopAutoRetry();

        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "Reached maximum retry count, stopping retry");
            updateConnectionStatus(CONNECTION_STATUS_ERROR);
            return;
        }

        // Calculate retry delay time (Exponential Backoff Strategy)
        long retryDelay = Math.min(
                RETRY_DELAY_MS + (retryCount * RETRY_INCREASE_MS),
                MAX_RETRY_DELAY_MS
        );

        Log.d(TAG, "Scheduling automatic retry connection (Attempt " + (retryCount + 1) + "), delay " + retryDelay + "ms");

        retryConnectionRunnable = () -> {
            Log.d(TAG, "Executing automatic retry connection to: " + currentVehicleId);
            retryCount++;
            connectToVehicle(currentVehicleId);
        };

        handler.postDelayed(retryConnectionRunnable, retryDelay);
    }

    /**
     * Stop automatic retry
     */
    private void stopAutoRetry() {
        if (retryConnectionRunnable != null) {
            handler.removeCallbacks(retryConnectionRunnable);
            retryConnectionRunnable = null;
            Log.d(TAG, "Automatic retry stopped");
        }
    }

    /**
     * Reset retry counter
     */
    private void resetRetryCount() {
        retryCount = 0;
        Log.d(TAG, "Retry counter reset");
    }

    /**
     * Enable/disable automatic retry
     */
    public void setAutoRetryEnabled(boolean enabled) {
        this.autoRetryEnabled = enabled;
        Log.d(TAG, "Automatic retry " + (enabled ? "enabled" : "disabled"));

        if (!enabled) {
            stopAutoRetry();
        }
    }

    /**
     * Check if in automatic retry mode
     */
    public boolean isAutoRetryEnabled() {
        return autoRetryEnabled;
    }

    /**
     * Get current retry count
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Save currently selected vehicle to SharedPreferences
     */
    private void saveCurrentVehicle(String vehicleId) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("BLEService", MODE_PRIVATE);
            prefs.edit().putString("currentVehicleId", vehicleId).apply();
            Log.d(TAG, "Current vehicle saved: " + vehicleId);
        } catch (Exception e) {
            Log.w(TAG, "Failed to save current vehicle", e);
        }
    }

    /**
     * Restore currently selected vehicle from SharedPreferences
     */
    private void restoreCurrentVehicle() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("BLEService", MODE_PRIVATE);
            String savedVehicleId = prefs.getString("currentVehicleId", null);

            if (savedVehicleId != null) {
                Log.d(TAG, "Restoring current vehicle: " + savedVehicleId);

                // Delay start automatic connection, give service time to initialize
                handler.postDelayed(() -> {
                    currentVehicleId = savedVehicleId;
                    Log.d(TAG, "Starting automatic connection to restored vehicle: " + savedVehicleId);

                    if (!connectToVehicle(savedVehicleId)) {
                        startAutoRetry();
                    }
                }, 2000); // Delay 2 seconds
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore current vehicle", e);
        }
    }

    /**
     * Clear saved vehicle
     */
    public void clearSavedVehicle() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("BLEService", MODE_PRIVATE);
            prefs.edit().remove("currentVehicleId").apply();
            Log.d(TAG, "Saved vehicle cleared");
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear saved vehicle", e);
        }
    }

    /**
     * Manually reconnect currently selected vehicle
     */
    public void reconnectCurrentVehicle() {
        if (currentVehicleId == null) {
            Log.w(TAG, "No currently selected vehicle to reconnect");
            return;
        }

        Log.d(TAG, "Manually reconnecting vehicle: " + currentVehicleId);

        // Disconnect existing connection
        if (isVehicleConnected(currentVehicleId)) {
            disconnectVehicle(currentVehicleId);
        }

        // Reset retry counter and start connection
        resetRetryCount();
        updateConnectionStatus(CONNECTION_STATUS_CONNECTING);

        if (!connectToVehicle(currentVehicleId)) {
            startAutoRetry();
        }
    }

    /**
     * Manually reset automatic retry (reset retry counter)
     */
    public void restartAutoRetry() {
        Log.d(TAG, "Manually reset automatic retry");
        resetRetryCount();

        if (currentVehicleId != null && !isVehicleConnected(currentVehicleId)) {
            startAutoRetry();
        }
    }

    /**
     * 檢查所有車輛的連線狀態並清理失效連線
     */
    private void checkAndCleanupConnections() {
        Log.d(TAG, "檢查並清理失效連線");

        if (bluetoothManager == null || bluetoothAdapter == null) {
            return;
        }

        try {
            // 檢查所有已存儲的連線
            for (Map.Entry<String, BluetoothGatt> entry : new HashMap<>(vehicleConnections).entrySet()) {
                String vehicleId = entry.getKey();
                BluetoothGatt gatt = entry.getValue();

                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(vehicleId);
                    int connectionState = bluetoothManager.getConnectionState(device, BluetoothProfile.GATT);

                    Log.d(TAG, "檢查車輛連線狀態: " + vehicleId + ", 狀態: " + connectionState);

                    if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                        Log.d(TAG, "發現失效連線，清理: " + vehicleId);
                        disconnectVehicle(vehicleId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "檢查車輛連線狀態時發生錯誤: " + vehicleId, e);
                    // 發生錯誤時也清理該連線
                    disconnectVehicle(vehicleId);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "檢查連線狀態時權限錯誤", e);
        }
    }

    /**
     * 手動檢查並修復連線狀態
     */
    public void checkConnectionHealth() {
        Log.d(TAG, "手動檢查連線健康狀態");

        handler.post(() -> {
            checkAndCleanupConnections();

            // 如果當前有選擇的車輛但未連線，嘗試重新連線
            if (currentVehicleId != null && !isVehicleConnected(currentVehicleId)) {
                Log.d(TAG, "當前車輛未連線，嘗試重新連線: " + currentVehicleId);
                connectToVehicle(currentVehicleId);
            }
        });
    }

}

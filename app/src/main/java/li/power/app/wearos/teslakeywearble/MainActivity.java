package li.power.app.wearos.teslakeywearble;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewpager2.widget.ViewPager2;

import li.power.app.wearos.teslakeywearble.adapters.CarPagerAdapter;
import li.power.app.wearos.teslakeywearble.database.CarDao;
import li.power.app.wearos.teslakeywearble.models.Car;
import li.power.app.wearos.teslakeywearble.services.BLEService;
import li.power.app.wearos.teslakeywearble.services.SessionManager;
import li.power.app.wearos.teslakeywearble.utils.KeyUtils;
import li.power.app.wearos.teslakeywearble.R;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import com.tesla.generated.universalmessage.UniversalMessage;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import android.os.Handler;

public class MainActivity extends Activity implements CarPagerAdapter.OnCarActionListener {

    private static final String TAG = "MainActivity";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "tesla_nak";
    private static final String UNLOCK_REQUIRED = "unlock_required";
    private static final String RSA_MODE = "RSA/ECB/PKCS1Padding";

    public static final int REQUEST_PERMISSIONS = 0;
    public static final int REQUEST_PAIR_CAR = 1;

    private KeyStore keyStore;
    private SharedPreferences sharedPreferences;

    // UI components
    private ViewPager2 viewPager;
    private LinearLayout pageIndicator;
    private CarPagerAdapter adapter;
    private List<Car> cars;

    // Track last status to reduce redundant UI refreshes
    private String lastConnectionStatusText = "";
    private Boolean lastConnected = null;
    private Boolean lastLocked = null;
    
    // BLE Service
    private BLEService bleService;
    private boolean serviceBound = false;
    
    // Database
    private CarDao carDao;
    
    // Vibrator for haptic feedback
    private Vibrator vibrator;
    
    // Current vehicle tracking
    private int currentVehicleIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences(KEY_ALIAS, Context.MODE_PRIVATE);
        KeyUtils.setupBouncyCastle();
        
        // 初始化資料庫
        carDao = new CarDao(this);


        setupPermissions();
        
        // 初始化震動器
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initViews();
        setupKeystore();
        loadCarsFromDatabase();
        setupViewPager();
        
        // 綁定BLE服務
        bindBLEService();
    }
    
    @Override
    protected void onDestroy() {
        Log.d(TAG, "MainActivity onDestroy");
        
        // 清除Service回調
        if (bleService != null) {
            try {
                bleService.setServiceCallback(null);
            } catch (Exception e) {
                Log.w(TAG, "清除Service回調時發生錯誤", e);
            }
        }
        
        // 解綁Service
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception e) {
                Log.w(TAG, "解綁Service時發生錯誤", e);
            } finally {
                serviceBound = false;
                bleService = null;
            }
        }
        
        // 清理其他資源
        if (adapter != null) {
            adapter = null;
        }
        
        super.onDestroy();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "MainActivity onPause");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity onResume");
        
        // 使用恢復連線方法
        if (serviceBound && bleService != null) {
            // 延遲一點執行，確保Activity已完全恢復
            new Handler().post(this::restoreVehicleConnection);
        }
    }
    
    private String getStatusText(String status) {
        switch (status) {
            case BLEService.CONNECTION_STATUS_DISCONNECTED:
                return "未連接";
            case BLEService.CONNECTION_STATUS_CONNECTING:
                return "連接中...";
            case BLEService.CONNECTION_STATUS_CONNECTED:
                return "已連接";
            case BLEService.CONNECTION_STATUS_SESSION_ESTABLISHING:
                return "建立Session...";
            case BLEService.CONNECTION_STATUS_READY:
                return "已就緒";
            case BLEService.CONNECTION_STATUS_ERROR:
                return "連接錯誤";
            default:
                return status;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "MainActivity onStop");
        
        // 在Activity停止時，安全地處理Service連接
        if (serviceBound && bleService != null) {
            try {
                // 清除回調，避免回調到已經停止的Activity
                bleService.setServiceCallback(null);
            } catch (Exception e) {
                Log.w(TAG, "清除Service回調時發生錯誤", e);
            }
        }
    }
    
    @Override
    protected void onRestart() {
        super.onRestart();
        Log.d(TAG, "MainActivity onRestart");
        
        // Activity重新啟動時，使用統一的恢復連線方法
        if (serviceBound && bleService != null) {
            // 重新設置Service回調
            bleService.setServiceCallback(bleServiceCallback);
            
            // 延遲一點執行恢復連線，確保Activity已完全恢復
            new Handler().post(this::restoreVehicleConnection);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allPermissionsGranted = true;
            List<String> deniedPermissions = new ArrayList<>();
            
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    allPermissionsGranted = false;
                    deniedPermissions.add(permissions[i]);
                    Log.d(TAG, "權限未允許: " + permissions[i]);
                } else {
                    Log.d(TAG, "權限已允許: " + permissions[i]);
                }
            }
            
            if (allPermissionsGranted) {
                Log.d(TAG, "所有請求的權限都已授予");
                onAllPermissionsGranted();
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "有 " + deniedPermissions.size() + " 個權限被拒絕");
                
                // 檢查哪些是關鍵權限
                boolean hasCriticalPermissionDenied = false;
                for (String deniedPermission : deniedPermissions) {
                    if (deniedPermission.equals(Manifest.permission.BLUETOOTH_CONNECT) ||
                        deniedPermission.equals(Manifest.permission.BLUETOOTH_SCAN) ||
                        deniedPermission.equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        hasCriticalPermissionDenied = true;
                        break;
                    }
                }
                
                if (hasCriticalPermissionDenied) {
                    showPermissionDeniedDialog();
                } else {
                    // 非關鍵權限被拒絕，可以繼續使用，但功能可能受限
                    Toast.makeText(this, getString(R.string.some_functions_limited), Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    
    /**
     * 顯示權限被拒絕的對話框
     */
    private void showPermissionDeniedDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.permissions_denied_title));
        builder.setMessage(getString(R.string.permissions_denied_message));
        
        builder.setPositiveButton(getString(R.string.retry_permissions), (dialog, which) -> {
            // 重新請求權限
            setupPermissions();
        });
        
        builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
            Toast.makeText(this, getString(R.string.app_functions_limited), Toast.LENGTH_LONG).show();
        });
        
        builder.setCancelable(false);
        builder.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_PAIR_CAR && resultCode == RESULT_OK) {
            Log.d(TAG, "配對成功返回，重新載入車輛清單");
            
            // 重新載入車輛清單
            reloadCarsFromDatabase();
            
            Toast.makeText(this, getString(R.string.vehicle_pairing_successful), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        // 定義需要的權限
        List<String> requiredPermissions = new ArrayList<>();
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN);
        requiredPermissions.add(Manifest.permission.VIBRATE);
        requiredPermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);


        // Android 13+ 需要的通知權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        
        // 檢查哪些權限尚未授予
        for (String permission : requiredPermissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
                Log.d(TAG, "需要請求權限: " + permission);
            } else {
                Log.d(TAG, "權限已授予: " + permission);
            }
        }
        
        // 如果有需要請求的權限，則請求
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "請求 " + permissionsToRequest.size() + " 個權限");
            requestPermissions(permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            Log.d(TAG, "所有權限已授予，無需請求");
            onAllPermissionsGranted();
        }
    }
    
    /**
     * 所有權限授予後的回調
     */
    private void onAllPermissionsGranted() {
        Log.d(TAG, "所有必要權限已授予");
        // 可以在這裡執行需要權限的初始化操作
    }

    private void initViews() {
        viewPager = findViewById(R.id.viewpager_cars);
        pageIndicator = findViewById(R.id.page_indicator);
    }
    
    /**
     * 從資料庫載入車輛
     */
    private void loadCarsFromDatabase() {
        cars = new ArrayList<>();
        
        try {
            List<Car> dbCars = carDao.getAllCars();

            // 直接使用Car對象
            for (Car dbCar : dbCars) {
                cars.add(dbCar);
            }
            
            Log.d(TAG, "載入了 " + cars.size() + " 輛車從資料庫");
            
        } catch (Exception e) {
            Log.e(TAG, "載入車輛資料失敗", e);
            Toast.makeText(this, getString(R.string.loading_vehicles_failed), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 重新載入車輛清單並更新UI
     */
    private void reloadCarsFromDatabase() {
        Log.d(TAG, "重新載入車輛清單");
        
        // 保存目前選中的車輛索引
        int oldCurrentIndex = currentVehicleIndex;
        
        // 重新載入車輛資料
        loadCarsFromDatabase();
        
        // 更新adapter
        if (adapter != null) {
            adapter.updateCars(cars);
            adapter.notifyDataSetChanged();
        }
        
        // 更新頁面指示器
        if (cars.size() > 0) {
            // 如果原來有選中車輛，嘗試保持選中
            if (oldCurrentIndex < cars.size()) {
                currentVehicleIndex = oldCurrentIndex;
            } else {
                currentVehicleIndex = 0;
            }
            
            // 設置ViewPager到正確的位置
            viewPager.setCurrentItem(currentVehicleIndex, false);
            updatePageIndicator(currentVehicleIndex);
            
            // 重新選擇車輛並連接
            if (serviceBound) {
                Car selectedCar = cars.get(currentVehicleIndex);
                bleService.selectVehicle(selectedCar.getBleAddress());
            }
        } else {
            // 沒有車輛時清空頁面指示器
            updatePageIndicator(0);
        }
        
        Log.d(TAG, "車輛清單重新載入完成，總共 " + cars.size() + " 輛車");
    }

    private void setupViewPager() {
        adapter = new CarPagerAdapter(this, cars, this);
        viewPager.setAdapter(adapter);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                
                // 避免在恢復過程中觸發車輛切換
                if (position == currentVehicleIndex) {
                    Log.d(TAG, "頁面位置未變更，跳過車輛切換: " + position);
                    return;
                }
                
                currentVehicleIndex = position;
                updatePageIndicator(position);
                
                // 只有當頁面是車輛頁面時才切換車輛連接（不是添加車輛頁面）
                if (serviceBound && cars.size() > 0 && position < cars.size()) {
                    Car selectedCar = cars.get(position);
                    Log.d(TAG, "用戶切換到車輛: " + selectedCar.getName() + " (" + selectedCar.getBleAddress() + ")");
                    bleService.selectVehicle(selectedCar.getBleAddress());
                } else if (position >= cars.size()) {
                    // 這是添加車輛頁面，不需要連接車輛
                    Log.d(TAG, "切換到添加車輛頁面");
                }
            }
        });

        updatePageIndicator(0);
    }

    private void updatePageIndicator(int currentPage) {
        pageIndicator.removeAllViews();

        int totalPages = adapter.getItemCount();
        for (int i = 0; i < totalPages; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    i == currentPage ? 24 : 18,
                    i == currentPage ? 24 : 18
            );
            params.setMargins(8, 0, 8, 0);
            dot.setLayoutParams(params);

            if (i == currentPage) {
                dot.setBackgroundResource(R.drawable.page_indicator_selected);
            } else {
                dot.setBackgroundResource(R.drawable.page_indicator_unselected);
            }

            pageIndicator.addView(dot);
        }
    }
    
    /**
     * 綁定BLE服務
     */
    private void bindBLEService() {
        Intent intent = new Intent(this, BLEService.class);
        try {
            // 首先啟動服務（這將使其變成前台服務）
            startForegroundService(intent);
            
            // 然後綁定服務
            boolean success = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "綁定BLE服務結果: " + success);
        } catch (Exception e) {
            Log.e(TAG, "綁定BLE服務時發生錯誤", e);
        }
    }
    
    /**
     * BLE服務連接
     */
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                Log.d(TAG, "BLE服務已連接");
                BLEService.BLEServiceBinder binder = (BLEService.BLEServiceBinder) service;
                bleService = binder.getService();
                serviceBound = true;
                
                // 設置回調
                bleService.setServiceCallback(bleServiceCallback);
                
                // 立即恢復連線狀態
                restoreVehicleConnection();
                
                // 如果有車輛且沒有當前選中的車輛，自動選擇第一輛
                if (!cars.isEmpty() && bleService.getCurrentVehicleId() == null) {
                    Car firstCar = cars.get(0);
                    Log.d(TAG, "自動選擇第一輛車: " + firstCar.getName());
                    bleService.selectVehicle(firstCar.getBleAddress());
                }
            } catch (Exception e) {
                Log.e(TAG, "處理Service連接時發生錯誤", e);
                serviceBound = false;
                bleService = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "BLE服務已斷開");
            serviceBound = false;
            bleService = null;
        }
        
        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "BLE服務綁定死亡");
            serviceBound = false;
            bleService = null;
        }
        
        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "BLE服務返回空綁定");
            serviceBound = false;
            bleService = null;
        }
    };
    
    /**
     * BLE服務回調
     */
    private BLEService.BLEServiceCallback bleServiceCallback = new BLEService.BLEServiceCallback() {
        @Override
        public void onVehicleConnected(String vehicleId) {
            Log.d(TAG, "車輛已連接: " + vehicleId);
            updateCurrentCarStatus(true, true);
        }

        @Override
        public void onVehicleDisconnected(String vehicleId) {
            Log.d(TAG, "車輛已斷開: " + vehicleId);
            updateCurrentCarStatus(false, true);
        }

        @Override
        public void onSessionEstablished(String vehicleId, UniversalMessage.Domain domain) {
            Log.d(TAG, "Session已建立: " + vehicleId + ", 域: " + domain);
            if (domain == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                updateConnectionStatus("已就緒");
                updateCurrentCarStatus(true, true);
            }
        }

        @Override
        public void onSessionFailed(String vehicleId, UniversalMessage.Domain domain, String error) {
            Log.e(TAG, "Session失敗: " + vehicleId + ", 域: " + domain + ", 錯誤: " + error);
            updateConnectionStatus("連接失敗");
            Toast.makeText(MainActivity.this, "連接失敗: " + error, Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCommandResponse(String vehicleId, byte[] response) {
            Log.d(TAG, "收到命令回應: " + vehicleId);
        }

        @Override
        public void onVehicleStatusReceived(String vehicleId, SessionManager.VehicleStatus status) {
            Log.d(TAG, "收到車輛狀態: " + vehicleId + ", 鎖定: " + status.isLocked());
            updateCurrentCarStatus(true, status.isLocked());
            
            // 更新前後備箱狀態
            if (currentVehicleIndex < cars.size()) {
                Car currentCar = cars.get(currentVehicleIndex);
                if (currentCar.getBleAddress().equals(vehicleId)) {
                    // 更新備箱狀態 (注意：isFrunkClosed() 返回 true 表示關閉，false 表示開啟)
                    currentCar.setFrunkOpen(!status.isFrunkClosed());
                    currentCar.setTrunkOpen(!status.isTrunkClosed());
                    
                    // 更新UI
                    runOnUiThread(() -> {
                        try {
                            if (adapter != null) {
                                adapter.notifyItemChanged(currentVehicleIndex);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "更新車輛狀態UI時發生錯誤", e);
                        }
                    });
                    
                    Log.d(TAG, "已更新車輛狀態: " + currentCar.getName() + 
                            " - 鎖定: " + status.isLocked() + 
                            ", 前備箱: " + (currentCar.isFrunkOpen() ? "開啟" : "關閉") + 
                            ", 後備箱: " + (currentCar.isTrunkOpen() ? "開啟" : "關閉"));
                }
            }
        }

        @Override
        public void onError(String vehicleId, String error) {
            Log.e(TAG, "車輛錯誤: " + vehicleId + ", 錯誤: " + error);
            
            runOnUiThread(() -> {
                try {
                    Toast.makeText(MainActivity.this, getString(R.string.connection_failed, error), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "顯示錯誤Toast時發生例外", e);
                }
            });
        }

        @Override
        public void onConnectionStatusChanged(String vehicleId, String status) {
            Log.d(TAG, "連線狀態變更: " + vehicleId + " -> " + status);
            
            String statusText;
            switch (status) {
                case BLEService.CONNECTION_STATUS_DISCONNECTED:
                    statusText = "未連接";
                    updateCurrentCarStatus(false, true);
                    break;
                case BLEService.CONNECTION_STATUS_CONNECTING:
                    statusText = "連接中...";
                    break;
                case BLEService.CONNECTION_STATUS_CONNECTED:
                    statusText = "已連接";
                    break;
                case BLEService.CONNECTION_STATUS_SESSION_ESTABLISHING:
                    statusText = "建立Session...";
                    break;
                case BLEService.CONNECTION_STATUS_READY:
                    statusText = "已就緒";
                    updateCurrentCarStatus(true, true);
                    break;
                case BLEService.CONNECTION_STATUS_ERROR:
                    statusText = "連接錯誤";
                    updateCurrentCarStatus(false, true);
                    break;
                default:
                    statusText = status;
                    break;
            }
            
            updateConnectionStatus(statusText);
        }

        @Override
        public void onVehicleOperationResult(String operation, boolean success, String message) {
            String operationName = getOperationDisplayName(operation);
            String resultText = success ? getString(R.string.operation_success) : getString(R.string.operation_failure);
            
            Log.d(TAG, "車輛操作結果: " + operationName + " -> " + resultText + ": " + message);
            vibrateSuccess();
        }

    };

    /**
     * 獲取操作顯示名稱
     */
    private String getOperationDisplayName(String operation) {
        switch (operation) {
            case "LOCK": return "鎖車";
            case "UNLOCK": return "開鎖";
            case "OPEN_FRUNK": return "開啟前備箱";
            case "OPEN_TRUNK": return "開啟後備箱";
            case "FLASH_LIGHTS": return "閃燈";
            case "WAKE_VEHICLE": return "喚醒車輛";
            case "GET_STATUS": return "獲取狀態";
            default: return operation;
        }
    }

    private void setupKeystore() {
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateEccPrivateKey();
            }

        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException |
                 InvalidAlgorithmParameterException | NoSuchProviderException | NoSuchPaddingException |
                 IllegalBlockSizeException | BadPaddingException | InvalidKeyException e) {
            Log.e(TAG, "金鑰庫初始化失敗", e);
            Toast.makeText(this, getString(R.string.keystore_init_failed_toast), Toast.LENGTH_SHORT).show();
        }
    }

    private void generateEccPrivateKey() throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException, NoSuchPaddingException, IllegalBlockSizeException, KeyStoreException, BadPaddingException, InvalidKeyException {
        ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec("secp256r1");
        ECDomainParameters domainParams = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH(), curve.getSeed());
        ECKeyGenerationParameters keyParams = new ECKeyGenerationParameters(domainParams, new SecureRandom());
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(keyParams);
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();
        generateRsaKey();
        sharedPreferences.edit().putString(KEY_ALIAS, Hex.encodeHexString(encryptRSA(((ECPrivateKeyParameters) keyPair.getPrivate()).getD().toByteArray()))).apply();
    }

    private byte[] encryptRSA(byte[] plainText) throws KeyStoreException, NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        PublicKey publicKey = keyStore.getCertificate(KEY_ALIAS).getPublicKey();

        Cipher cipher = Cipher.getInstance(RSA_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        return cipher.doFinal(plainText);
    }

    private void generateRsaKey() throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException {
        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build();

        KeyPairGenerator keyPairGenerator = KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER);
        keyPairGenerator.initialize(keyGenParameterSpec);
        keyPairGenerator.generateKeyPair();
    }

    // CarPagerAdapter.OnCarActionListener 實現
    @Override
    public void onFrontTrunkClick(Car car) {
        Log.d(TAG, "前備箱短按: " + car.getName());

        Toast.makeText(this, getString(R.string.frunk_long_press_required), Toast.LENGTH_SHORT).show();
    }

    // 新增長按方法
    public void onFrontTrunkLongPress(Car car) {
        Log.d(TAG, "前備箱長按: " + car.getName());
        if (serviceBound) {
            bleService.openFrunk();
        } else {
            Toast.makeText(this, getString(R.string.service_not_connected_toast), Toast.LENGTH_SHORT).show();
        }
    }

    // 新增長按取消方法
    public void onFrontTrunkLongPressCancelled(Car car) {
        Log.d(TAG, "前備箱長按取消: " + car.getName());
    }

    // 新增長按進行中方法
    public void onFrontTrunkLongPressStarted(Car car) {
        Log.d(TAG, "前備箱長按開始: " + car.getName());
    }

    @Override
    public void onLockUnlockClick(Car car) {
        Log.d(TAG, "鎖定/解鎖操作: " + car.getName() + ", 目前狀態: " + (car.isLocked() ? "已鎖定" : "已解鎖"));
        
        if (serviceBound) {
            if (car.isLocked()) {
                bleService.unlockVehicle();
            } else {
                bleService.lockVehicle();
            }
        }
    }

    @Override
    public void onRearTrunkClick(Car car) {
        Log.d(TAG, "後備箱操作: " + car.getName());
        if (serviceBound) {
            bleService.openTrunk();
        }
    }

    @Override
    public void onVehicleNameClick(Car car) {
        Log.d(TAG, "編輯車輛名稱: " + car.getName());
        showEditVehicleNameDialog(car);
    }
    
    @Override
    public void onVehicleNameLongPress(Car car) {
        Log.d(TAG, "刪除車輛: " + car.getName());
        showDeleteVehicleDialog(car);
    }
    
    private void showEditVehicleNameDialog(Car car) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.edit_vehicle_name));
        
        // 創建輸入框
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(car.getName());
        input.setSelectAllOnFocus(true);
        builder.setView(input);
        
        builder.setPositiveButton(getString(R.string.ok), (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty() && !newName.equals(car.getName())) {
                updateVehicleName(car, newName);
            }
        });
        
        builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.cancel());
        
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
        
        // 自動選中文字並顯示鍵盤
        input.requestFocus();
        input.selectAll();
    }
    
    private void showDeleteVehicleDialog(Car car) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.delete_vehicle));
        builder.setMessage(getString(R.string.delete_vehicle_confirmation, car.getName()));
        
        builder.setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
            deleteVehicle(car);
        });
        
        builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.cancel());
        
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    private void updateVehicleName(Car car, String newName) {
        try {
            // 更新資料庫中的車輛名稱
            car.setName(newName);
            carDao.updateCar(car);
            
            // 重新載入車輛列表
            reloadCarsFromDatabase();
            
            vibrateSuccess(); // 更新成功震動反饋
            Toast.makeText(this, getString(R.string.vehicle_name_updated), Toast.LENGTH_SHORT).show();
            Log.d(TAG, "車輛名稱已更新: " + car.getBleAddress() + " -> " + newName);
            
        } catch (Exception e) {
            Log.e(TAG, "更新車輛名稱失敗", e);
            Toast.makeText(this, getString(R.string.vehicle_name_update_failed), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void deleteVehicle(Car car) {
        try {
            // 如果當前連接的是要刪除的車輛，先斷開連接
            if (serviceBound && bleService.getCurrentVehicleId() != null && 
                bleService.getCurrentVehicleId().equals(car.getBleAddress())) {
                bleService.disconnectAllVehicles();
            }
            
            // 從資料庫刪除車輛
            carDao.deleteCar(car.getId());
            
            // 重新載入車輛列表
            reloadCarsFromDatabase();
            
            // 如果刪除後沒有車輛了，回到第一頁（新增車輛頁面）
            if (cars.isEmpty()) {
                viewPager.setCurrentItem(0);
            } else {
                // 如果當前頁面索引超出範圍，調整到最後一頁
                int currentItem = viewPager.getCurrentItem();
                if (currentItem >= cars.size()) {
                    viewPager.setCurrentItem(cars.size() - 1);
                }
            }

            Toast.makeText(this, getString(R.string.vehicle_deleted), Toast.LENGTH_SHORT).show();
            Log.d(TAG, "車輛已刪除: " + car.getName() + " (" + car.getBleAddress() + ")");
            
        } catch (Exception e) {
            Log.e(TAG, "刪除車輛失敗", e);
            Toast.makeText(this, getString(R.string.vehicle_delete_failed), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 震動反饋 - 操作成功時的觸覺反饋
     */
    private void vibrateSuccess() {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0 及以上版本使用 VibrationEffect
                    VibrationEffect effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE);
                    vibrator.vibrate(effect);
                } else {
                    // 舊版本直接震動
                    vibrator.vibrate(100);
                }
                Log.d(TAG, "震動反饋已觸發");
            } catch (Exception e) {
                Log.w(TAG, "震動反饋失敗", e);
            }
        }
    }

    private void vibrateFailed() {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {

                    // Android 8.0 及以上版本使用 VibrationEffect - 雙震動表示失敗
                    long[] pattern = {0, 50, 50, 50}; // 震動模式：暫停0ms, 震動50ms, 暫停50ms, 震動50ms
                    VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
                    vibrator.vibrate(effect);

                Log.d(TAG, "失敗震動反饋已觸發");
            } catch (Exception e) {
                Log.w(TAG, "震動反饋失敗", e);
            }
        }
    }

    /**
     * 更新目前車輛的連接狀態
     */
    private void updateCurrentCarStatus(boolean connected, boolean locked) {
        // 檢查Activity是否還活著
        if (isFinishing() || isDestroyed()) {
            Log.d(TAG, "Activity已銷毀，跳過車輛狀態更新");
            return;
        }

        if (currentVehicleIndex < cars.size()) {
            // Skip if status didn't change
            if (lastConnected != null && lastLocked != null &&
                    lastConnected == connected && lastLocked == locked) {
                return;
            }

            lastConnected = connected;
            lastLocked = locked;

            Car currentCar = cars.get(currentVehicleIndex);
            currentCar.setConnected(connected);
            currentCar.setLocked(locked);
            // 注意：這裡不重置備箱狀態，讓備箱狀態由車輛狀態回調單獨處理

            runOnUiThread(() -> {
                try {
                    if (adapter != null) {
                        adapter.notifyItemChanged(currentVehicleIndex);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "更新車輛狀態UI時發生錯誤", e);
                }
            });
        }
    }

    /**
     * 更新連接狀態顯示
     */
    private void updateConnectionStatus(String statusText) {
        // 檢查Activity是否還活著
        if (isFinishing() || isDestroyed()) {
            Log.d(TAG, "Activity已銷毀，跳過狀態更新");
            return;
        }

        // Skip if no change
        if (statusText.equals(lastConnectionStatusText)) {
            return;
        }

        lastConnectionStatusText = statusText;

        runOnUiThread(() -> {
            try {
                if (adapter != null) {
                    adapter.updateConnectionStatus(statusText);
                    // 只更新當前車輛項目
                    if (currentVehicleIndex < cars.size()) {
                        adapter.notifyItemChanged(currentVehicleIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "更新連接狀態UI時發生錯誤", e);
            }
        });
    }

    /**
     * 恢復與車輛的連線狀態
     */
    private void restoreVehicleConnection() {
        if (!serviceBound || bleService == null) {
            Log.d(TAG, "服務未綁定，無法恢復連線");
            return;
        }
        
        // 重新設置回調
        bleService.setServiceCallback(bleServiceCallback);
        
        // 當前選中的車輛
        String currentVehicleId = bleService.getCurrentVehicleId();
        if (currentVehicleId == null) {
            Log.d(TAG, "沒有選中的車輛，無法恢復連線");
            updateConnectionStatus("未選擇車輛");
            return;
        }
        
        // 找到對應的車輛在列表中的位置並更新ViewPager
        int vehicleIndex = -1;
        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i).getBleAddress().equals(currentVehicleId)) {
                vehicleIndex = i;
                break;
            }
        }
        
        if (vehicleIndex >= 0) {
            // 更新ViewPager位置（不觸發頁面變更回調）
            currentVehicleIndex = vehicleIndex;
            if (viewPager.getCurrentItem() != vehicleIndex) {
                viewPager.setCurrentItem(vehicleIndex, false);
                updatePageIndicator(vehicleIndex);
            }
            Log.d(TAG, "恢復車輛選擇位置: " + vehicleIndex + " (" + cars.get(vehicleIndex).getName() + ")");
        }
        
        // 檢查當前的連線狀態
        String connectionStatus = bleService.getConnectionStatus();
        Log.d(TAG, "當前連線狀態: " + connectionStatus);
        
        // 檢查車輛是否連接
        boolean isConnected = bleService.isVehicleConnected(currentVehicleId);
        Log.d(TAG, "車輛連接狀態: " + isConnected);
        
        // 根據連線狀態決定是否需要重新連線或請求狀態更新
        if (connectionStatus.equals(BLEService.CONNECTION_STATUS_ERROR) || 
            connectionStatus.equals(BLEService.CONNECTION_STATUS_DISCONNECTED)) {
            
            Log.d(TAG, "連線狀態為斷開或錯誤，嘗試重新連線");
            bleService.reconnectCurrentVehicle();
            updateConnectionStatus(getStatusText(connectionStatus));
            updateCurrentCarStatus(false, true);
            
        } else if (connectionStatus.equals(BLEService.CONNECTION_STATUS_READY)) {
            
            Log.d(TAG, "連線狀態為就緒，請求車輛狀態更新");
            
            // 立即更新UI狀態
            updateConnectionStatus(getStatusText(connectionStatus));
            updateCurrentCarStatus(true, true);  // 連接狀態為true，鎖定狀態將通過狀態請求更新
            
            // 在主線程中延遲請求，確保UI已更新
            new Handler().postDelayed(() -> {
                if (serviceBound && bleService != null) {
                    bleService.requestVehicleStatus();
                }
            }, 300);
            
        } else if (connectionStatus.equals(BLEService.CONNECTION_STATUS_CONNECTED) ||
                   connectionStatus.equals(BLEService.CONNECTION_STATUS_SESSION_ESTABLISHING) ||
                   connectionStatus.equals(BLEService.CONNECTION_STATUS_CONNECTING)) {
            
            Log.d(TAG, "連線狀態為進行中: " + connectionStatus);
            // 其他連線狀態，更新UI但不做額外操作
            updateConnectionStatus(getStatusText(connectionStatus));
            updateCurrentCarStatus(isConnected, true);
            
        } else {
            // 未知狀態，只更新UI
            updateConnectionStatus(getStatusText(connectionStatus));
            updateCurrentCarStatus(isConnected, true);
        }
    }

}
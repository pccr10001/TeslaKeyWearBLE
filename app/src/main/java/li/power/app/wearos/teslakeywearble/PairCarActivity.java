package li.power.app.wearos.teslakeywearble;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.RequiresPermission;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import li.power.app.wearos.teslakeywearble.adapters.CarScanAdapter;
import li.power.app.wearos.teslakeywearble.dialogs.PairingDialog;
import li.power.app.wearos.teslakeywearble.models.Car;
import li.power.app.wearos.teslakeywearble.services.VCSECPairingService;
import li.power.app.wearos.teslakeywearble.utils.VINUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PairCarActivity extends Activity implements 
        PairingDialog.PairingDialogListener, 
        VCSECPairingService.PairingCallback {
    
    private static final String TAG = "PairCarActivity";
    private static final ParcelUuid TESLA_SERVICE_UUID = ParcelUuid.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e");
    private static final ParcelUuid TESLA_BEACON_UUID = ParcelUuid.fromString("00001122-0000-1000-8000-00805F9B34FB");
    private static final int SCAN_PERIOD = 10000; // 10秒掃描時間
    
    private RecyclerView recyclerView;
    private CarScanAdapter adapter;
    private ProgressBar progressBar;
    
    private List<Car> bluetoothCars = new ArrayList<>();
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private Handler handler;
    private boolean isScanning = false;
    
    // 配對相關
    private PairingDialog pairingDialog;
    private VCSECPairingService vcsecService;
    private Car selectedCar;

    private String vin;
    private String model;
    
    // WakeLock 防止螢幕變暗
    private PowerManager.WakeLock wakeLock;

    // 掃描回調
    private ScanCallback scanCallback = new ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);


            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();

            String deviceName = device.getName();

            if (deviceName == null || deviceName.isEmpty()) {
                return;
            }

            int idx = -1;
            for (int i = 0; i < bluetoothCars.size(); i++) {
                if (bluetoothCars.get(i).getBleAddress().equals(device.getAddress())) {
                    bluetoothCars.get(i).setSignalStrength(rssi);
                    idx=i;
                }
            }

            if(idx > -1){
                return;
            }

            ParcelUuid uuid = parseIncompleteServiceUuids(result.getScanRecord().getBytes());
            if(uuid == null){
                return;
            }

            if(!uuid.equals(TESLA_BEACON_UUID)){
                return;
            }

            Log.d(TAG, "發現Tesla設備: " + deviceName + " 地址: " + device.getAddress() + " RSSI: " + rssi);
                
               Car newCar = new Car(deviceName, device.getAddress(), rssi);
                bluetoothCars.add(newCar);
                progressBar.setVisibility(View.GONE);
                runOnUiThread(() -> adapter.notifyDataSetChanged());

        }
        
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
            Log.e(TAG, "BLE掃描失敗，錯誤碼: " + errorCode);
            runOnUiThread(() -> {
                Toast.makeText(PairCarActivity.this, getString(R.string.bluetooth_scan_failed), Toast.LENGTH_SHORT).show();
                stopBluetoothSearch();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pair_car);
        
        handler = new Handler();
        
        initViews();
        initBluetooth();
        setupRecyclerView();
        initVCSECService();
        
        // 初始化WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TeslaKeyPairing:ActivityWakeLock"
        );
        
        // 先顯示 VIN 輸入對話框，再開始搜尋
        showVINInputDialog();
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        
        if (bluetoothAdapter == null) {
            Toast.makeText(this, getString(R.string.device_does_not_support_bluetooth), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, getString(R.string.please_enable_bluetooth), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, getString(R.string.device_does_not_support_ble), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
    }
    
    private void initViews() {
        recyclerView = findViewById(R.id.recycler_cars);
        progressBar = findViewById(R.id.progress_search);
        
    }
    
    private void initVCSECService() {
        vcsecService = new VCSECPairingService(this, this);
    }
    
    private void setupRecyclerView() {
        adapter = new CarScanAdapter(bluetoothCars, this::onCarSelected);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }
    
    private void startBluetoothSearch() {
        if (bluetoothLeScanner == null || isScanning) {
            return;
        }
        
        Log.d(TAG, "開始BLE掃描...");

        // 清空之前的結果
        bluetoothCars.clear();
        adapter.notifyDataSetChanged();

        // 顯示進度條
        progressBar.setVisibility(View.VISIBLE);


        // 設置掃描設定
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setLegacy(true)
                .build();

        // 開始掃描
        try {
            bluetoothLeScanner.startScan(null, settings, scanCallback);
            isScanning = true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "缺少藍牙權限", e);
            Toast.makeText(this, getString(R.string.missing_bluetooth_permission), Toast.LENGTH_SHORT).show();
            stopBluetoothSearch();
        }
    }
    
    private void stopBluetoothSearch() {
        if (bluetoothLeScanner != null && isScanning) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
                Log.d(TAG, "停止BLE掃描");
            } catch (SecurityException e) {
                Log.e(TAG, "停止掃描時權限錯誤", e);
            }
        }
        
        isScanning = false;
        progressBar.setVisibility(View.GONE);

        handler.removeCallbacks(this::stopBluetoothSearch);
    }

    private void onCarSelected(Car car) {
        Log.d(TAG, "選擇車輛: " + car.getName() + " 地址: " + car.getBleAddress());

        selectedCar = car;
        
        // 停止掃描
        stopBluetoothSearch();
car.setVin(vin);
      car.setModel(model);
        // 顯示配對Dialog
        showPairingDialog(car);
    }
    
    private void showPairingDialog(Car car) {
        // 獲取WakeLock防止螢幕變暗
        acquireWakeLock();
        
        pairingDialog = new PairingDialog(this, car.getModel(), car.getBleAddress(), this);
        pairingDialog.show();
        
        // 開始VCSEC配對
        startVCSECPairing(car);
    }
    
    private void startVCSECPairing(Car car) {
        if (vcsecService != null && selectedCar != null) {
            vcsecService.startPairing(car.getBleAddress(), car.getVin(), car.getModel());
        }
    }
    
    // PairingDialog.PairingDialogListener 實現
    @Override
    public void onPairingCancelled() {
        Log.d(TAG, getString(R.string.pairing_cancelled));
        
        // 釋放WakeLock
        releaseWakeLock();
        
        if (vcsecService != null) {
            vcsecService.stopPairing();
        }
        restartCarSelection();
    }
    
    // VCSECPairingService.PairingCallback 實現
    @Override
    public void onConnectionStateChanged(boolean connected) {
        Log.d(TAG, "連接狀態變更: " + connected);
    }
    
    @Override
    public void onPairingProgress(String status) {
        Log.d(TAG, "配對進度: " + status);
        
        if (pairingDialog != null) {
            pairingDialog.updateStatus(status);
        }
    }
    
    @Override  
    public void onPairingSuccess() {
        Log.d(TAG, "VCSEC配對成功");
        
        if (pairingDialog != null) {
            pairingDialog.showSuccess();
        }
    }


    private void showVINInputDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("請輸入您的 Tesla 車輛 VIN 碼（17位）");

        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("例如：5YJSA1E62NF016329");
        builder.setView(input);

        builder.setPositiveButton("確認", (dialog, which) -> {
            String vinCode = input.getText().toString().trim().toUpperCase();
            validateVINAndStartSearch(vinCode);
        });


        builder.setNegativeButton("取消", (dialog, which) -> {
            Log.d(TAG, "使用者取消 VIN 輸入");
            dialog.dismiss();
            finish();
        });

        android.app.AlertDialog dialog = builder.create();
        dialog.show();

        input.requestFocus();
    }
    
    /**
     * 驗證 VIN 並開始搜尋
     * @param vinCode 輸入的 VIN 碼
     */
    private void validateVINAndStartSearch(String vinCode) {
        if (vinCode.isEmpty()) {
            showVINErrorAndRetry("VIN 碼不能為空");
            return;
        }
        
        // 使用 VINUtils 驗證 VIN
        VINUtils.VINValidationResult result = VINUtils.validateVIN(vinCode);
        
        if (!result.isValid()) {
            // VIN 驗證失敗，顯示錯誤信息並重新輸入
            showVINErrorAndRetry(result.getErrorMessage());
            return;
        }
        
        // VIN 驗證成功
        Log.d(TAG, "VIN 驗證成功: " + vinCode);
        Log.d(TAG, "車型: " + result.getVehicleType());
        Log.d(TAG, "年份: " + result.getModelYear());
        Log.d(TAG, "製造商: " + result.getManufacturer());
        Log.d(TAG, "製造地點: " + result.getBuildLocation());
        
        // 保存 VIN 信息以供後續使用
        saveVINInfo(vinCode, result);
        
        Toast.makeText(this, "VIN 驗證成功：" + result.getVehicleType() + 
                      " (" + result.getModelYear() + ")，開始搜尋車輛...", Toast.LENGTH_LONG).show();
        
        // 開始藍芽搜尋
        startBluetoothSearch();
    }
    
    /**
     * 保存 VIN 信息
     * @param vinCode VIN 碼
     * @param result 驗證結果
     */
    private void saveVINInfo(String vinCode, VINUtils.VINValidationResult result) {

        vin = vinCode;
        model = result.getVehicleType();
 
        Log.d(TAG, "已保存 VIN 信息，準備搜尋 " + result.getVehicleType());
    }
    
    /**
     * 顯示 VIN 錯誤信息並重新輸入
     * @param errorMessage 錯誤信息
     */
    private void showVINErrorAndRetry(String errorMessage) {
        Toast.makeText(this, "VIN 驗證失敗：" + errorMessage, Toast.LENGTH_LONG).show();
        
        // 延遲一下再顯示輸入對話框，讓用戶看到錯誤信息
        handler.postDelayed(() -> showVINInputDialog(), 1000);
    }
    
    @Override
    public void onPairingFailed(String error) {
        Log.e(TAG, "VCSEC配對失敗: " + error);
        
        if (pairingDialog != null) {
            pairingDialog.showError(error);
        }
    }
    
    @Override
    public void onPairingCompleted() {
        Log.d(TAG, getString(R.string.pairing_completed_returning));
        
        // 釋放WakeLock
        releaseWakeLock();
        
        // 關閉配對Dialog
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
            pairingDialog = null;
        }
        
        // 設置結果並結束Activity，返回MainActivity
        setResult(RESULT_OK);
        finish();
    }
    
    /**
     * 重新開始車輛選擇流程
     */
    private void restartCarSelection() {
        // 清除選中的車輛
        selectedCar = null;
        
        // 關閉配對Dialog
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
            pairingDialog = null;
        }
        
        // 清空車輛列表
        bluetoothCars.clear();
        adapter.notifyDataSetChanged();
        
        // 延遲一下再開始掃描，讓用戶看到列表清空
        handler.postDelayed(() -> {
            startBluetoothSearch();
        }, 500);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBluetoothSearch();
        
        // 釋放WakeLock
        releaseWakeLock();
        
        if (vcsecService != null) {
            vcsecService.stopPairing();
        }
        
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
        }
    }

    @Override
    public void onBackPressed() {
        // 如果正在配對，先停止配對
        if (isPairing()) {
            stopCurrentPairing();
            return;
        }
        
        // 如果正在掃描，停止掃描
        if (isScanning) {
            stopBluetoothSearch();
        }
        
        super.onBackPressed();
    }
    
    private boolean isPairing() {
        return vcsecService != null && vcsecService.isPairing();
    }
    
    private void stopCurrentPairing() {
        Log.d(TAG, getString(R.string.user_interrupted_pairing));
        
        // 釋放WakeLock
        releaseWakeLock();
        
        // 停止VCSEC配對服務
        if (vcsecService != null) {
            vcsecService.stopPairing();
        }
        
        // 關閉配對Dialog
        if (pairingDialog != null && pairingDialog.isShowing()) {
            pairingDialog.dismiss();
            pairingDialog = null;
        }

        // 返回車輛選擇
        restartCarSelection();
    }

    /**
     * 獲取WakeLock防止螢幕變暗
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L); // 最多持有10分鐘
                Log.d(TAG, getString(R.string.wakelock_acquired));
            }
        } catch (Exception e) {
            Log.w(TAG, getString(R.string.wakelock_acquire_failed), e);
        }
    }
    
    /**
     * 釋放WakeLock允許螢幕變暗
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, getString(R.string.wakelock_released));
            }
        } catch (Exception e) {
            Log.w(TAG, getString(R.string.wakelock_release_failed), e);
        }
    }

    private ParcelUuid parseIncompleteServiceUuids(byte[] scanRecord) {
        if (scanRecord == null) return null;

        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index] & 0xFF;
            if (length == 0) break;

            int type = scanRecord[index + 1] & 0xFF;

            // 0x02 = Incomplete List of 16-bit Service UUIDs
            // 0x03 = Complete List of 16-bit Service UUIDs
            if (type == 0x02 || type == 0x03) {
                for (int i = 2; i < length + 1; i += 2) {
                    if (index + i + 1 < scanRecord.length) {
                        // Little Endian 格式
                        int uuid16 = ((scanRecord[index + i + 1] & 0xFF) << 8) |
                                (scanRecord[index + i] & 0xFF);
                        return ParcelUuid.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", uuid16));
                    }
                }
            }

            // 0x06 = Incomplete List of 128-bit Service UUIDs
            // 0x07 = Complete List of 128-bit Service UUIDs
            else if (type == 0x06 || type == 0x07) {
                for (int i = 2; i < length + 1; i += 16) {
                    if (index + i + 15 < scanRecord.length) {
                        byte[] uuid128 = new byte[16];
                        System.arraycopy(scanRecord, index + i, uuid128, 0, 16);
                        return ParcelUuid.fromString(bytesToUuid(uuid128));
                    }
                }
            }

            index += length + 1;
        }
        return null;
    }

    private String bytesToUuid(byte[] bytes) {
        // 將 16 bytes 轉換為 UUID 字串格式
        StringBuilder sb = new StringBuilder();
        for (int i = 15; i >= 0; i--) { // Little Endian
            sb.append(String.format("%02X", bytes[i] & 0xFF));
            if (i == 12 || i == 10 || i == 8 || i == 6) {
                sb.append("-");
            }
        }
        return sb.toString();
    }
} 
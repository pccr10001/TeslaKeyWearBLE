package li.power.app.wearos.teslakeywearble.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.tesla.generated.keys.Keys;
import com.tesla.generated.vcsec.Vcsec;
import com.tesla.generated.universalmessage.UniversalMessage;
import li.power.app.wearos.teslakeywearble.utils.KeyUtils;
import org.bouncycastle.jcajce.provider.digest.SHA1;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.util.encoders.Hex;

import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.util.UUID;
import java.util.Random;

import li.power.app.wearos.teslakeywearble.database.CarDao;
import li.power.app.wearos.teslakeywearble.models.Car;
import li.power.app.wearos.teslakeywearble.R;

public class VCSECPairingService {

    private static final String TAG = "VCSECPairingService";

    // Tesla VCSEC UUID
    private static final String TESLA_SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e";
    private static final String TESLA_WRITE_CHARACTERISTIC_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e";
    private static final String TESLA_READ_CHARACTERISTIC_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e";

    // Client Characteristic Configuration Descriptor UUID
    private static final String CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb";

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;
    private Handler handler;

    private PairingCallback pairingCallback;
    private boolean isConnected = false;
    private boolean isPairing = false;

    private SharedPreferences sharedPreferences;
    private CarDao carDao;

    // WakeLock 防止螢幕變暗
    private PowerManager.WakeLock wakeLock;

    // 當前配對的車輛資訊
    private String currentCarBleAddress;
    private String currentCarBleName;
    private String currentCarVin;

    public interface PairingCallback {
        void onConnectionStateChanged(boolean connected);

        void onPairingProgress(String status);

        void onPairingSuccess();

        void onPairingFailed(String error);

        // 新增：配對完成並需要返回MainActivity
        void onPairingCompleted();
    }

    public VCSECPairingService(Context context, PairingCallback callback) {
        this.context = context;
        this.pairingCallback = callback;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.handler = new Handler(Looper.getMainLooper());
        this.sharedPreferences = context.getSharedPreferences("CarInfo", Context.MODE_PRIVATE);
        this.carDao = new CarDao(context);

        // 初始化BouncyCastle
        KeyUtils.setupBouncyCastle();

        // 初始化WakeLock
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TeslaKeyPairing:WakeLock"
        );
    }

    public void startPairing(String deviceAddress, String vin, String model) {
        if (isPairing) {
            Log.w(TAG, context.getString(R.string.pairing_already_in_progress));
            return;
        }

        if (bluetoothAdapter == null) {
            notifyPairingFailed(context.getString(R.string.bluetooth_not_available));
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        if (device == null) {
            notifyPairingFailed(context.getString(R.string.device_not_found));
            return;
        }

        // 保存當前配對的車輛資訊
        currentCarBleAddress = deviceAddress;
        currentCarVin = vin;
        currentCarBleName = model;

        isPairing = true;
        notifyPairingProgress(context.getString(R.string.connecting_to_vehicle));

        // 獲取WakeLock防止螢幕變暗
        acquireWakeLock();

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "連接GATT時權限錯誤", e);
            notifyPairingFailed(context.getString(R.string.permission_error));
        }
    }

    public void stopPairing() {
        Log.d(TAG, "停止配對過程");
        isPairing = false;

        // 清除所有pending的handler回調
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // 斷開並關閉GATT連接
        if (bluetoothGatt != null) {
            try {
                // 先禁用notification
                if (readCharacteristic != null) {
                    bluetoothGatt.setCharacteristicNotification(readCharacteristic, false);

                    // 重置descriptor
                    BluetoothGattDescriptor descriptor = readCharacteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID));
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                        bluetoothGatt.writeDescriptor(descriptor);
                    }
                }

                // 斷開連接
                bluetoothGatt.disconnect();

                // 延遲關閉GATT，確保斷開完成
                handler.postDelayed(() -> {
                    if (bluetoothGatt != null) {
                        bluetoothGatt.close();
                        bluetoothGatt = null;
                    }
                }, 500);

            } catch (SecurityException e) {
                Log.e(TAG, "斷開GATT連接時權限錯誤", e);
                // 即使有權限錯誤，也要關閉連接
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }

        // 重置狀態
        isConnected = false;
        writeCharacteristic = null;
        readCharacteristic = null;

        // 釋放WakeLock
        releaseWakeLock();

        Log.d(TAG, "配對過程已停止");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT連接成功");
                isConnected = true;
                handler.post(() -> {
                    if (pairingCallback != null) {
                        pairingCallback.onConnectionStateChanged(true);
                    }
                });

                notifyPairingProgress(context.getString(R.string.service_discovery_success));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    Log.e(TAG, "發現服務時權限錯誤", e);
                    notifyPairingFailed(context.getString(R.string.permission_error));
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT連接斷開");
                isConnected = false;
                handler.post(() -> {
                    if (pairingCallback != null) {
                        pairingCallback.onConnectionStateChanged(false);
                    }
                });

                if (isPairing) {
                    notifyPairingFailed(context.getString(R.string.connection_lost));
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服務發現成功");

                BluetoothGattService teslaService = gatt.getService(UUID.fromString(TESLA_SERVICE_UUID));
                if (teslaService != null) {
                    Log.d(TAG, "找到Tesla VCSEC服務");

                    writeCharacteristic = teslaService.getCharacteristic(UUID.fromString(TESLA_WRITE_CHARACTERISTIC_UUID));
                    readCharacteristic = teslaService.getCharacteristic(UUID.fromString(TESLA_READ_CHARACTERISTIC_UUID));

                    if (writeCharacteristic != null && readCharacteristic != null) {
                        // 設置notification來接收indicate characteristic的數據
                        try {
                            boolean success = gatt.setCharacteristicNotification(readCharacteristic, true);
                            if (success) {
                                Log.d(TAG, "成功設置notification");

                                // 寫入descriptor來確保notification啟動
                                BluetoothGattDescriptor descriptor = readCharacteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID));
                                if (descriptor != null) {
                                    // 對於indication characteristic，使用ENABLE_INDICATION_VALUE
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                    boolean descriptorWriteSuccess = gatt.writeDescriptor(descriptor);
                                    if (descriptorWriteSuccess) {
                                        Log.d(TAG, "開始寫入descriptor");
                                        notifyPairingProgress(context.getString(R.string.setting_vehicle_notification));
                                    } else {
                                        notifyPairingFailed(context.getString(R.string.descriptor_write_failed));
                                    }
                                } else {
                                    Log.w(TAG, "找不到Client Characteristic Configuration descriptor，直接開始握手");
                                    notifyPairingProgress(context.getString(R.string.starting_vcsec_handshake));
                                    startVCSECHandshake();
                                }
                            } else {
                                notifyPairingFailed(context.getString(R.string.notification_setting_failed));
                            }
                        } catch (SecurityException e) {
                            Log.e(TAG, "設置notification時權限錯誤", e);
                            notifyPairingFailed(context.getString(R.string.permission_error));
                        }
                    } else {
                        notifyPairingFailed(context.getString(R.string.necessary_characteristic_not_found));
                    }
                } else {
                    notifyPairingFailed(context.getString(R.string.tesla_vcsec_service_not_found));
                }
            } else {
                notifyPairingFailed(context.getString(R.string.service_discovery_failed));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor寫入成功");
                if (descriptor.getUuid().toString().equals(CLIENT_CHARACTERISTIC_CONFIG_UUID)) {
                    Log.d(TAG, "Indication已啟動，開始VCSEC握手");
                    notifyPairingProgress(context.getString(R.string.starting_vcsec_handshake));
                    startVCSECHandshake();
                }
            } else {
                Log.e(TAG, "Descriptor寫入失敗，狀態: " + status);
                notifyPairingFailed(context.getString(R.string.indication_start_failed));
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "特性寫入成功");
                notifyPairingProgress(context.getString(R.string.waiting_vehicle_response));
                // 不需要手動讀取，車輛會通過indication發送回應
            } else {
                Log.e(TAG, "特性寫入失敗，狀態: " + status);
                notifyPairingFailed(context.getString(R.string.write_failed));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);

            if (characteristic.getUuid().toString().equals(TESLA_READ_CHARACTERISTIC_UUID)) {
                Log.d(TAG, "收到車輛indication");
                byte[] data = characteristic.getValue();
                if (data != null) {
                    processVCSECResponse(data);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "特性讀取成功");
                // 處理讀取的數據
                byte[] data = characteristic.getValue();
                if (data != null) {
                    processVCSECResponse(data);
                }
            } else {
                Log.e(TAG, "特性讀取失敗，狀態: " + status);
                notifyPairingFailed(context.getString(R.string.read_failed));
            }
        }
    };

    private void startVCSECHandshake() {
        try {
            notifyPairingProgress(context.getString(R.string.generating_public_key));

            // 獲取私鑰
            PrivateKey privateKey = KeyUtils.getPrivateKey(context);
            ECPrivateKey ecPrivateKey = (ECPrivateKey) privateKey;

            // 從私鑰生成公鑰
            ECPublicKey ecPublicKey = KeyUtils.getPublicKeyFromPrivate(ecPrivateKey);

            // 將公鑰轉換為X9.62 Uncompressed Point格式
            byte[] publicKeyBytes = ecPublicKey.getQ().getEncoded(false);

            Log.d(TAG, "公鑰長度: " + publicKeyBytes.length);
            Log.d(TAG, "公鑰第一個字節: 0x" + String.format("%02X", publicKeyBytes[0]));

            notifyPairingProgress(context.getString(R.string.creating_pairing_request));

            // 直接構建白名單消息（按照C++的方式）
            buildWhitelistMessage(publicKeyBytes);

        } catch (Exception e) {
            Log.e(TAG, "VCSEC握手失敗", e);
            notifyPairingFailed(context.getString(R.string.key_processing_error) + ": " + e.getMessage());
        }
    }

    /**
     * 構建白名單消息 - 完全按照client.cpp的buildWhiteListMessage方法
     */
    private void buildWhitelistMessage(byte[] publicKeyBytes) {
        try {
            Log.d(TAG, "構建白名單消息（按照官方實現）");

            // 1. 創建PermissionChange - 按照官方addKeyPayload函數
            Vcsec.PermissionChange.Builder permissionChangeBuilder = Vcsec.PermissionChange.newBuilder();
            
            // 設置公鑰 - PublicKeyRaw: publicKey.Bytes()
            Vcsec.PublicKey.Builder publicKeyBuilder = Vcsec.PublicKey.newBuilder();
            publicKeyBuilder.setPublicKeyRaw(ByteString.copyFrom(publicKeyBytes));
            permissionChangeBuilder.setKey(publicKeyBuilder.build());
            
            // KeyRole: role
            permissionChangeBuilder.setKeyRole(Keys.Role.ROLE_CHARGING_MANAGER);
            
            Vcsec.PermissionChange permissionChange = permissionChangeBuilder.build();
            Log.d(TAG, "PermissionChange構建完成，角色: " + Keys.Role.ROLE_CHARGING_MANAGER);

            // 2. 創建WhitelistOperation - 按照官方結構
            Vcsec.WhitelistOperation.Builder whitelistOpBuilder = Vcsec.WhitelistOperation.newBuilder();
            
            // SubMessage: AddKeyToWhitelistAndAddPermissions
            whitelistOpBuilder.setAddKeyToWhitelistAndAddPermissions(permissionChange);
            
            // MetadataForKey: KeyFormFactor
            Vcsec.KeyMetadata.Builder metadataBuilder = Vcsec.KeyMetadata.newBuilder();
            metadataBuilder.setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_CLOUD_KEY);
            whitelistOpBuilder.setMetadataForKey(metadataBuilder.build());
            
            Vcsec.WhitelistOperation whitelistOperation = whitelistOpBuilder.build();
            Log.d(TAG, "WhitelistOperation構建完成");

            // 3. 創建UnsignedMessage - SubMessage: WhitelistOperation
            Vcsec.UnsignedMessage.Builder unsignedMsgBuilder = Vcsec.UnsignedMessage.newBuilder();
            unsignedMsgBuilder.setWhitelistOperation(whitelistOperation);
            Vcsec.UnsignedMessage unsignedMessage = unsignedMsgBuilder.build();

            // 4. 序列化UnsignedMessage (encodedPayload)
            byte[] encodedPayload = unsignedMessage.toByteArray();
            Log.d(TAG, "UnsignedMessage序列化完成，長度: " + encodedPayload.length);

            // 5. 創建ToVCSECMessage envelope - 按照官方實現
            Vcsec.ToVCSECMessage.Builder envelopeBuilder = Vcsec.ToVCSECMessage.newBuilder();
            
            Vcsec.SignedMessage.Builder signedMsgBuilder = Vcsec.SignedMessage.newBuilder();
            signedMsgBuilder.setProtobufMessageAsBytes(ByteString.copyFrom(encodedPayload));
            signedMsgBuilder.setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY);
            
            envelopeBuilder.setSignedMessage(signedMsgBuilder.build());
            Vcsec.ToVCSECMessage envelope = envelopeBuilder.build();

            // 6. 序列化envelope (encodedEnvelope)
            byte[] encodedEnvelope = envelope.toByteArray();
            Log.d(TAG, "ToVCSECMessage序列化完成，長度: " + encodedEnvelope.length);

            notifyPairingProgress(context.getString(R.string.sending_pairing_request_to_vehicle));

            // 8. 發送到車輛 - v.conn.Send(ctx, encodedEnvelope)
            sendMessageToVehicle(encodedEnvelope);

        } catch (Exception e) {
            Log.e(TAG, "構建白名單消息失敗", e);
            notifyPairingFailed(context.getString(R.string.key_processing_error) + ": " + e.getMessage());
        }
    }

    /**
     * 發送消息到車輛
     */
    private void sendMessageToVehicle(byte[] message) {
        try {
            writeCharacteristic.setValue(message);
            boolean success = bluetoothGatt.writeCharacteristic(writeCharacteristic);
            if (!success) {
                notifyPairingFailed(context.getString(R.string.write_characteristic_failed));
            }
        } catch (SecurityException e) {
            Log.e(TAG, "寫入特性時權限錯誤", e);
            notifyPairingFailed(context.getString(R.string.permission_error));
        }
    }


    private void processVCSECResponse(byte[] data) {
        try {
            Log.d(TAG, "收到VCSEC回應，長度: " + data.length);
            Log.d(TAG, "回應數據: " + bytesToHex(data));

            // 參考client.cpp的parseUniversalMessageBLE方法
            // BLE消息前2字節是長度，需要移除
            if (data.length < 2) {
                return;
            }

            // 提取消息長度（前2字節，大端序）
            int messageLength = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
            Log.d(TAG, "消息長度: " + messageLength);

            if (data.length < 2 + messageLength) {
                return;
            }

            // 提取實際消息內容（移除前2字節的長度前綴）
            byte[] messageData = new byte[messageLength];
            System.arraycopy(data, 2, messageData, 0, messageLength);

            // 配對回應應該直接是FromVCSECMessage，而不是UniversalMessage
            // 按照C++代碼的parseFromVCSECMessage方法
            handleDirectVCSECResponse(messageData);

        } catch (Exception e) {
            Log.e(TAG, "處理VCSEC回應失敗", e);
            notifyPairingFailed(context.getString(R.string.response_processing_failed) + ": " + e.getMessage());
        }
    }

    /**
     * 直接處理FromVCSECMessage回應 - 按照client.cpp的parseFromVCSECMessage方法
     */
    private void handleDirectVCSECResponse(byte[] messageData) {
        try {
            Log.d(TAG, "直接解析FromVCSECMessage");

            // 直接解析FromVCSECMessage
            Vcsec.FromVCSECMessage fromVcsec = Vcsec.FromVCSECMessage.parseFrom(messageData);
            Log.d(TAG, "FromVCSECMessage: " + fromVcsec.toString());

            // 處理命令狀態
            handleCommandStatus(fromVcsec.getCommandStatus());
        } catch (Exception e) {
            Log.e(TAG, "直接解析FromVCSECMessage失敗", e);
            notifyPairingFailed(context.getString(R.string.response_processing_failed) + ": " + e.getMessage());
        }
    }

    private void handleCommandStatus(Vcsec.CommandStatus commandStatus) {
        Log.d(TAG, "收到Command Status: " + commandStatus.toString());

        Vcsec.OperationStatus_E operationStatus = commandStatus.getOperationStatus();
        
        // 按照官方isWhitelistOperationComplete函數的邏輯
        if (commandStatus.hasWhitelistOperationStatus()) {
            Vcsec.WhitelistOperation_status whitelistOpStatus = commandStatus.getWhitelistOperationStatus();
            Vcsec.WhitelistOperation_information_E info = whitelistOpStatus.getWhitelistOperationInformation();

            Log.d(TAG, "WhitelistOperationInformation: " + info);

            if (info == Vcsec.WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) {
                // 配對成功
                String keyId = "";
                if (whitelistOpStatus.hasSignerOfOperation()) {
                    keyId = Hex.toHexString(whitelistOpStatus.getSignerOfOperation().getPublicKeySHA1().toByteArray());
                    Log.d(TAG, "配對成功，KeyID: " + keyId);
                }

                savePairedCarToDatabase(keyId);
                Log.d(TAG, "配對成功完成");
                notifyPairingProgress(context.getString(R.string.keycard_confirmation_success));
                notifyPairingSuccess();

            } else {
                // 配對失敗，顯示具體錯誤信息
                String errorMsg = "白名單操作失敗: " + info.toString();
                Log.e(TAG, errorMsg);
                notifyPairingFailed(context.getString(R.string.command_execution_failed, info.toString()));
            }
        } else if (operationStatus == Vcsec.OperationStatus_E.OPERATIONSTATUS_WAIT) {
            Log.d(TAG, "車輛等待鑰匙卡確認");
            notifyPairingProgress(context.getString(R.string.please_click_keycard_on_vehicle));
            // 繼續等待下一個回應
            
        } else if (operationStatus == Vcsec.OperationStatus_E.OPERATIONSTATUS_ERROR) {
            String error = "操作失敗: " + operationStatus.toString();
            Log.e(TAG, error);
            notifyPairingFailed(context.getString(R.string.command_execution_failed, operationStatus.toString()));
            
        } else {
            Log.w(TAG, "未知的操作狀態: " + operationStatus);
            notifyPairingProgress(context.getString(R.string.vehicle_response_processing));
        }
    }

    /**
     * 保存配對成功的車輛資訊到數據庫
     */
    private void savePairedCarToDatabase(String keyId) {
        try {
            // 檢查是否已經存在相同的車輛
            Car existingCar = carDao.getCarByBleAddress(currentCarBleAddress);

            if (existingCar != null) {
                // 更新現有車輛的KeyID和最後連接時間
                existingCar.setKeyId(keyId);
                existingCar.setLastConnectedTime(System.currentTimeMillis());

                int updateResult = carDao.updateCar(existingCar);
                if (updateResult > 0) {
                    Log.d(TAG, "成功更新車輛資訊: " + existingCar.getName());
                } else {
                    Log.w(TAG, "更新車輛資訊失敗");
                }
            } else {
                // 創建新的車輛記錄
                Car newCar = new Car();
                newCar.setBleAddress(currentCarBleAddress);
                newCar.setBleName(currentCarBleName);
                newCar.setName(currentCarBleName); // 初始使用藍牙名稱作為車輛名稱
                newCar.setKeyId(keyId);
                newCar.setVin(currentCarVin);

                long carId = carDao.addCar(newCar);
                if (carId > 0) {
                    Log.d(TAG, "成功保存新車輛: " + newCar.getName() + ", ID: " + carId);
                } else {
                    Log.w(TAG, "保存新車輛失敗");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "保存車輛資訊到數據庫時出錯", e);
        }
    }

    private void startReadingResponse() {
        // 使用indication時不需要手動讀取，車輛會自動發送回應
        // 只需要等待onCharacteristicChanged回調
        Log.d(TAG, "等待車輛indication回應...");
    }

    private void notifyPairingProgress(String status) {
        handler.post(() -> {
            if (pairingCallback != null) {
                pairingCallback.onPairingProgress(status);
            }
        });
    }

    private void disconnect() {
        bluetoothGatt.disconnect();

        // 延遲關閉GATT，確保斷開完成
        handler.postDelayed(() -> {
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }, 500);

    }

    private void notifyPairingSuccess() {
        isPairing = false;
        disconnect();

        // 釋放WakeLock
        releaseWakeLock();

        handler.post(() -> {
            if (pairingCallback != null) {

                pairingCallback.onPairingSuccess();

                // 延遲2秒後通知配對完成，讓用戶看到成功信息
                handler.postDelayed(() -> {
                    if (pairingCallback != null) {
                        // 斷開連接

                        pairingCallback.onPairingCompleted();
                    }
                }, 2000);
            }
        });
    }

    private void notifyPairingFailed(String error) {
        isPairing = false;
        disconnect();
        // 釋放WakeLock
        releaseWakeLock();

        handler.post(() -> {
            if (pairingCallback != null) {
                pairingCallback.onPairingFailed(error);
            }
        });
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isPairing() {
        return isPairing;
    }

    /**
     * 獲取WakeLock防止螢幕變暗
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10 * 60 * 1000L); // 最多持有10分鐘
                Log.d(TAG, context.getString(R.string.wakelock_acquired));
            }
        } catch (Exception e) {
            Log.w(TAG, context.getString(R.string.wakelock_acquire_failed), e);
        }
    }

    /**
     * 釋放WakeLock允許螢幕變暗
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, context.getString(R.string.wakelock_released));
            }
        } catch (Exception e) {
            Log.w(TAG, context.getString(R.string.wakelock_release_failed), e);
        }
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
} 
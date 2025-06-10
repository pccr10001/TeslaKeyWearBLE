package li.power.app.wearos.teslakeywearble.services;

import android.util.Log;

import com.google.protobuf.ByteString;
import com.tesla.generated.signatures.Signatures;
import com.tesla.generated.universalmessage.UniversalMessage;
import com.tesla.generated.vcsec.Vcsec;

import li.power.app.wearos.teslakeywearble.utils.KeyUtils;
import li.power.app.wearos.teslakeywearble.database.CarDao;
import li.power.app.wearos.teslakeywearble.models.Car;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jcajce.provider.digest.SHA256;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Session管理器 - 基於Tesla官方vehicle-command的正確實作
 * 
 * 注意：此類用於配對完成後的會話管理
 * 配對過程本身使用VCSECPairingService中的直接ToVCSECMessage格式
 * 
 * 主要功能：
 * 1. 處理與車輛的Session建立（handshake）
 * 2. 管理AES-GCM加密/解密
 * 3. 處理反重放攻擊保護（counter）
 * 4. 建構正確的VCSEC命令
 */
public class SessionManager {
    
    private static final String TAG = "SessionManager";
    
    // 加密常數
    private static final int AES_GCM_KEY_SIZE = 32; // 256 bits
    private static final int AES_GCM_IV_SIZE = 12;  // 96 bits  
    private static final int AES_GCM_TAG_SIZE = 16; // 128 bits
    private static final int CHALLENGE_SIZE = 16;
    
    /**
     * Session資訊類
     */
    public static class Session {
        private final UniversalMessage.Domain domain;
        private String vin;
        private final PrivateKey clientPrivateKey;
        
        // Session狀態
        private PublicKey vehiclePublicKey;
        private byte[] sharedSecret;
        private byte[] epoch;
        private long clockTime;
        private final AtomicInteger counter = new AtomicInteger(1);
        private boolean authenticated = false;
        
        public Session(UniversalMessage.Domain domain, String vin, PrivateKey clientPrivateKey) {
            this.domain = domain;
            this.vin = vin;
            this.clientPrivateKey = clientPrivateKey;
        }
        
        // Getters
        public UniversalMessage.Domain getDomain() { return domain; }
        public String getVin() { return vin; }
        public PrivateKey getClientPrivateKey() { return clientPrivateKey; }
        public byte[] getSharedSecret() { return sharedSecret; }
        public byte[] getEpoch() { return epoch; }
        public long getClockTime() { return clockTime; }
        public int getCounter() { return counter.get(); }
        public int getAndIncrementCounter() { 
            int current = counter.getAndIncrement();
            Log.d(TAG, "Counter incremented for " + domain + ": " + current + " -> " + counter.get());
            return counter.get();
        }
        public boolean isAuthenticated() { return authenticated; }
        
        // Setters
        public void setVehiclePublicKey(PublicKey vehiclePublicKey) { this.vehiclePublicKey = vehiclePublicKey; }
        public void setSharedSecret(byte[] sharedSecret) { this.sharedSecret = sharedSecret; }
        public void setEpoch(byte[] epoch) { this.epoch = epoch; }
        public void setClockTime(long clockTime) { this.clockTime = clockTime; }
        public void setCounter(int counter) { 
            this.counter.set(counter);
            Log.d(TAG, "Counter set for " + domain + ": " + counter);
        }
        public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }

        public void setVin(String vin) {this.vin = vin;}

        @Override
        public String toString() {
            return "Session{" +
                    "domain=" + domain.toString() +
                    ", vin='" + vin + '\'' +
                    ", epoch=" + Hex.toHexString(epoch) +
                    ", clockTime=" + clockTime +
                    ", counter=" + counter +
                    ", authenticated=" + authenticated +
                    '}';
        }
    }
    
    /**
     * 建立Session Info Request - 正確的握手請求
     * 參考client.cpp的buildSessionInfoRequestMessage方法
     */
    public static byte[] buildSessionInfoRequest(UniversalMessage.Domain domain, PrivateKey clientPrivateKey, byte[] connectionId) throws Exception {
        Log.d(TAG, "建立Session Info Request for domain: " + domain);
        
        // 獲取客戶端公鑰 - 按照C++實現，直接使用公鑰字節
        PublicKey clientPublicKey = KeyUtils.getPublicKeyFromPrivate((ECPrivateKey) clientPrivateKey);
        byte[] publicKeyBytes = KeyUtils.publicKeyToBytes(clientPublicKey);
        Log.d(TAG, "Pubkey: "+Hex.toHexString(publicKeyBytes));
        // 建立SessionInfoRequest - 按照C++實現，不包含challenge
        UniversalMessage.SessionInfoRequest sessionInfoRequest = UniversalMessage.SessionInfoRequest.newBuilder()
                .setPublicKey(ByteString.copyFrom(publicKeyBytes))
                .build();
        
        // 創建to_destination
        UniversalMessage.Destination.Builder toDestBuilder = UniversalMessage.Destination.newBuilder();
        toDestBuilder.setDomain(domain);

        UniversalMessage.Destination.Builder fromDestBuilder = UniversalMessage.Destination.newBuilder();

        fromDestBuilder.setRoutingAddress(ByteString.copyFrom(connectionId));
        
        // 建立RoutableMessage - 按照C++版本的結構
        UniversalMessage.RoutableMessage.Builder messageBuilder = UniversalMessage.RoutableMessage.newBuilder()
                .setToDestination(toDestBuilder.build())
                .setFromDestination(fromDestBuilder.build())
                .setSessionInfoRequest(sessionInfoRequest);
        byte[] uuidBytes = new byte[16];
        UUID uuid = UUID.randomUUID();

        System.arraycopy(ByteBuffer.allocate(8).putLong(uuid.getMostSignificantBits()).array(), 0 ,uuidBytes,0,8);
        System.arraycopy(ByteBuffer.allocate(8).putLong(uuid.getLeastSignificantBits()).array(), 0 ,uuidBytes,8,8);

        messageBuilder.setUuid(ByteString.copyFrom(uuidBytes));
        
        // 序列化消息
        byte[] messageBytes = messageBuilder.build().toByteArray();

        // 添加長度前綴 - 按照C++的prependLength方法
        return messageBytes;
    }

    /**
     * 處理Session Info Response - 解析車輛的握手回應
     * 參考client.cpp的parseUniversalMessageBLE和updateSession方法
     */
    public static Session handleSessionInfoResponse(byte[] responseData, PrivateKey clientPrivateKey, 
                                                    UniversalMessage.Domain domain, String vin) throws Exception {
        Log.d(TAG, "處理Session Info Response for domain: " + domain);

        // 解析RoutableMessage
        UniversalMessage.RoutableMessage routableMessage = UniversalMessage.RoutableMessage.parseFrom(responseData);
        
        // 檢查是否包含SessionInfo
        if (!routableMessage.hasSessionInfo() || !routableMessage.hasSignatureData()) {
            return null;
        }

        // 解析SessionInfo - 按照C++的parsePayloadSessionInfo方法
        byte[] sessionInfoBytes = routableMessage.getSessionInfo().toByteArray();
        Signatures.SessionInfo sessionInfo = Signatures.SessionInfo.parseFrom(sessionInfoBytes);
        
        // 建立Session - 按照C++的updateSession方法
        Session session = new Session(domain, vin, clientPrivateKey);
        Log.d(TAG,"Client Priv:"+Hex.toHexString(((ECPrivateKey)clientPrivateKey).getD().toByteArray()) );
        
        // 設置車輛公鑰 - 按照C++的loadTeslaKey方法
        byte[] vehiclePublicKeyBytes = sessionInfo.getPublicKey().toByteArray();
        PublicKey vehiclePublicKey = KeyUtils.bytesToPublicKey(vehiclePublicKeyBytes);
        session.setVehiclePublicKey(vehiclePublicKey);
        Log.d(TAG,"Vehicle Pub:"+Hex.toHexString(vehiclePublicKeyBytes));

        // 計算共享密鑰 - 按照C++實現，使用ECDH + SHA1
        byte[] sharedSecret = computeSharedSecretWithSHA1(clientPrivateKey, vehiclePublicKey);
        session.setSharedSecret(sharedSecret);



        // 設置epoch和時間 - 按照C++的updateSession
        session.setEpoch(sessionInfo.getEpoch().toByteArray());

        session.setClockTime((new Date().getTime()/1000) - sessionInfo.getClockTime());

        // 設置counter - 按照C++實現
        session.setCounter(sessionInfo.getCounter());
        
        // 設置為已認證
        session.setAuthenticated(true);

        Log.d(TAG, "Session建立成功 - Domain: " + domain + 
                  ", Counter: " + sessionInfo.getCounter() + 
                  ", ClockTime: " + sessionInfo.getClockTime() +
                  ", Epoch: " + Hex.toHexString(sessionInfo.getEpoch().toByteArray()));
        
        return session;
    }

    public static byte[] Sha256Hmac(byte[] secretKey, byte[] message)
            throws NoSuchAlgorithmException, InvalidKeyException {

        HMac hmac = new HMac(new SHA256Digest());
        hmac.init(new KeyParameter(secretKey));
        hmac.update(message, 0, message.length);
        byte[] mac = new byte[hmac.getMacSize()];
        hmac.doFinal(mac, 0);
        return mac;

    }

    /**
     * 計算ECDH共享密鑰並使用SHA1哈希 - 按照C++的Peer::loadTeslaKey實現
     */
    private static byte[] computeSharedSecretWithSHA1(PrivateKey clientPrivateKey, PublicKey vehiclePublicKey) throws Exception {
        // 使用ECDH計算共享密鑰
        byte[] rawSharedSecret = KeyUtils.doECDH(clientPrivateKey, vehiclePublicKey);
        Log.d(TAG, "Raw Shared Secret: " + Hex.toHexString(rawSharedSecret));
        // 按照C++實現，對共享密鑰進行SHA1哈希，並只取前16字節
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(rawSharedSecret);

        byte[] finalSecret = new byte[16];
        System.arraycopy(sha1Hash, 0, finalSecret, 0, 16);
        Log.d(TAG, "Final Shared Secret: " + Hex.toHexString(finalSecret));
        Log.d(TAG, "共享密鑰計算完成，長度: " + finalSecret.length);
        return finalSecret;
    }
    
    /**
     * 建立VCSEC命令訊息
     */
    public static byte[] buildVCSECCommand(Session session, Vcsec.UnsignedMessage unsignedMessage) throws Exception {


        if (session.getDomain() != UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
            throw new Exception("Session domain不是DOMAIN_VEHICLE_SECURITY");
        }
        
        if (!session.isAuthenticated()) {
            throw new Exception("Session未認證");
        }
        
        // 序列化UnsignedMessage
        byte[] unsignedBytes = unsignedMessage.toByteArray();
        
        // 生成隨機IV
        byte[] iv = new byte[AES_GCM_IV_SIZE];
        SecureRandom.getInstanceStrong().nextBytes(iv);

        Log.d(TAG, "IV: " + Hex.toHexString(iv));
        
        // 準備計數器
        int counter = session.getAndIncrementCounter();

        Log.d(TAG, "Counter: " + counter);

        long expiredAt = (new Date().getTime()/1000) + 10 - session.clockTime;

        Log.d(TAG, "expiredAt: " + expiredAt);

        // 建立SignatureData
        Signatures.AES_GCM_Personalized_Signature_Data signatureData = 
                Signatures.AES_GCM_Personalized_Signature_Data.newBuilder()
                        .setEpoch(ByteString.copyFrom(session.getEpoch()))
                        .setNonce(ByteString.copyFrom(iv))
                        .setCounter(counter)
                        .setExpiresAt((int)expiredAt) // 60秒後過期
                        .build();

        // AES-GCM加密
        byte[] encryptedPayload = encryptAESGCM(session.getSharedSecret(), iv, unsignedBytes, 
                buildAAD(session,expiredAt));
        
        // 提取Tag
        byte[] ciphertext = new byte[encryptedPayload.length - AES_GCM_TAG_SIZE];
        byte[] tag = new byte[AES_GCM_TAG_SIZE];
        System.arraycopy(encryptedPayload, 0, ciphertext, 0, ciphertext.length);
        System.arraycopy(encryptedPayload, ciphertext.length, tag, 0, tag.length);

        Log.d(TAG, "Ciphertext: " + Hex.toHexString(ciphertext));
        Log.d(TAG, "Tag: " + Hex.toHexString(tag));
        // 更新SignatureData的tag
        signatureData = signatureData.toBuilder()
                .setTag(ByteString.copyFrom(tag))
                .build();
        
        // 建立完整的SignatureData結構
        Signatures.SignatureData fullSignatureData = Signatures.SignatureData.newBuilder()
                .setSignerIdentity(Signatures.KeyIdentity.newBuilder()
                        .setPublicKey(ByteString.copyFrom(KeyUtils.publicKeyToBytes(
                                KeyUtils.getPublicKeyFromPrivate((ECPrivateKey) session.getClientPrivateKey()))))
                        .build())
                .setAESGCMPersonalizedData(signatureData)
                .build();
        
        // 建立RoutableMessage
        UniversalMessage.RoutableMessage routableMessage = UniversalMessage.RoutableMessage.newBuilder()
                .setToDestination(UniversalMessage.Destination.newBuilder()
                        .setDomain(session.getDomain())
                        .build())
                .setProtobufMessageAsBytes(ByteString.copyFrom(ciphertext))
                .setSignatureData(fullSignatureData)
                .build();
        
        return routableMessage.toByteArray();
    }
    
    /**
     * 解密車輛回應
     */
    public static byte[] decryptVehicleResponse(Session session, byte[] encryptedResponse) throws Exception {

        // 解析RoutableMessage
        UniversalMessage.RoutableMessage routableMessage = UniversalMessage.RoutableMessage.parseFrom(encryptedResponse);
        
        // 檢查是否有簽名資料
        if (!routableMessage.hasSignatureData()) {
            // 如果沒有簽名資料，可能是明文回應
            if (routableMessage.hasProtobufMessageAsBytes()) {
                return routableMessage.getProtobufMessageAsBytes().toByteArray();
            }
            throw new Exception("回應中沒有簽名資料或明文資料");
        }
        
        Signatures.SignatureData signatureData = routableMessage.getSignatureData();
        
        // 檢查是否是AES-GCM回應
        if (signatureData.hasAESGCMResponseData()) {
            Signatures.AES_GCM_Response_Signature_Data responseData = signatureData.getAESGCMResponseData();
            
            byte[] iv = responseData.getNonce().toByteArray();
            byte[] tag = responseData.getTag().toByteArray();
            byte[] ciphertext = routableMessage.getProtobufMessageAsBytes().toByteArray();
            
            // 重建完整的加密資料 (ciphertext + tag)
            byte[] encryptedData = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, encryptedData, 0, ciphertext.length);
            System.arraycopy(tag, 0, encryptedData, ciphertext.length, tag.length);

            // 解密
            return decryptAESGCM(session.getSharedSecret(), iv, encryptedData, 
                    buildAAD(session,0 ));
        }
        
        throw new Exception("不支援的回應加密類型");
    }
    
    /**
     * AES-GCM加密
     */
    private static byte[] encryptAESGCM(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(AES_GCM_TAG_SIZE * 8, iv);
        
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        
        return cipher.doFinal(plaintext);
    }
    
    /**
     * AES-GCM解密
     */
    private static byte[] decryptAESGCM(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(AES_GCM_TAG_SIZE * 8, iv);
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        
        return cipher.doFinal(ciphertext);
    }
    
    /**
     * 建立AAD (Additional Authenticated Data)
     */
    private static byte[] buildAAD(Session session, long expiresAt) {
        ByteBuffer buffer = ByteBuffer.allocate(56);
        putAAD(buffer,Signatures.Tag.TAG_SIGNATURE_TYPE.getNumber(), Signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.getNumber());
        putAAD(buffer,Signatures.Tag.TAG_DOMAIN.getNumber(), session.domain.getNumber());
        putAAD(buffer, Signatures.Tag.TAG_PERSONALIZATION.getNumber(), session.vin.getBytes());
        putAAD(buffer, Signatures.Tag.TAG_EPOCH.getNumber(), session.epoch);
        putAAD(buffer, Signatures.Tag.TAG_EXPIRES_AT.getNumber(), ByteBuffer.allocate(4).putInt((int)expiresAt).array());
        putAAD(buffer, Signatures.Tag.TAG_COUNTER.getNumber(), ByteBuffer.allocate(4).putInt(session.getCounter()).array());
        buffer.put((byte)Signatures.Tag.TAG_END.getNumber());
        Log.d(TAG, "Metadata: " + Hex.toHexString(buffer.array()));
        Digest sha256 = new SHA256Digest();
        sha256.update(buffer.array(),0, 56);
        byte[] s = new byte[32];
        sha256.doFinal(s,0);
        Log.d(TAG, "MetadataSHA: " + Hex.toHexString(s));
        return s;
    }

    private static void putAAD(ByteBuffer buffer, int tag, byte[] value){
        buffer.put((byte)tag);
        buffer.put((byte)value.length);
        buffer.put(value);
    }

    private static void putAAD(ByteBuffer buffer, int tag, int value){
        buffer.put((byte)tag);
        buffer.put((byte)0x01);
        byte[] b = new byte[1];
        b[0] = (byte)value;
        buffer.put(b);
    }
    /**
     * VCSEC命令建構器
     */
    public static class VCSECCommands {
        
        /**
         * 鎖車
         */
        public static byte[] createLockCommand(Session session) throws Exception {
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 開鎖
         */
        public static byte[] createUnlockCommand(Session session) throws Exception {
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_UNLOCK)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 開啟後備箱
         */
        public static byte[] createOpenRearTrunkCommand(Session session) throws Exception {
            Vcsec.ClosureMoveRequest closureRequest = Vcsec.ClosureMoveRequest.newBuilder()
                    .setRearTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN)
                    .build();
            
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setClosureMoveRequest(closureRequest)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 開啟前備箱
         */
        public static byte[] createOpenFrontTrunkCommand(Session session) throws Exception {
            Vcsec.ClosureMoveRequest closureRequest = Vcsec.ClosureMoveRequest.newBuilder()
                    .setFrontTrunk(Vcsec.ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN)
                    .build();
            
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setClosureMoveRequest(closureRequest)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 閃燈（遠端駕駛模式）
         */
        public static byte[] createFlashLightsCommand(Session session) throws Exception {
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_REMOTE_DRIVE)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 獲取車輛狀態
         */
        public static byte[] createGetVehicleStatusCommand(Session session) throws Exception {
            Vcsec.InformationRequest informationRequest = Vcsec.InformationRequest.newBuilder()
                    .setInformationRequestType(Vcsec.InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS)
                    .build();
            
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setInformationRequest(informationRequest)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 喚醒車輛
         */
        public static byte[] createWakeVehicleCommand(Session session) throws Exception {
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_WAKE_VEHICLE)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
        
        /**
         * 自動鎖車
         */
        public static byte[] createAutoSecureVehicleCommand(Session session) throws Exception {
            Vcsec.UnsignedMessage unsignedMessage = Vcsec.UnsignedMessage.newBuilder()
                    .setRKEAction(Vcsec.RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE)
                    .build();
            
            return buildVCSECCommand(session, unsignedMessage);
        }
    }
    
    /**
     * 回應解析器
     */
    public static class ResponseParser {
        
        /**
         * 解析車輛狀態回應
         */
        public static VehicleStatus parseVehicleStatusResponse(Session session, byte[] responseData) throws Exception {

            Vcsec.FromVCSECMessage fromVCSEC = null;
            try{
                UniversalMessage.RoutableMessage universalMessage = UniversalMessage.RoutableMessage.parseFrom(responseData);

                if(universalMessage.hasProtobufMessageAsBytes()){
                    fromVCSEC = Vcsec.FromVCSECMessage.parseFrom(universalMessage.getProtobufMessageAsBytes());
                }
            } catch (Exception e) {
                try {
                    fromVCSEC = Vcsec.FromVCSECMessage.parseFrom(responseData);
                } catch (Exception ex) {
                    Log.d(TAG,"Not a vehicle status");
                }
            }
            
            if (fromVCSEC== null || !fromVCSEC.hasVehicleStatus()) {
                return null;
            }
            return new VehicleStatus(fromVCSEC.getVehicleStatus());
        }
        
        /**
         * 解析命令狀態回應
         */
        public static boolean parseCommandStatusResponse(Session session, byte[] responseData) throws Exception {

            Vcsec.FromVCSECMessage fromVCSEC = Vcsec.FromVCSECMessage.parseFrom(responseData);
            
            if (!fromVCSEC.hasCommandStatus()) {
                return true; // 沒有錯誤狀態表示成功
            }
            
            Vcsec.CommandStatus commandStatus = fromVCSEC.getCommandStatus();
            return commandStatus.getOperationStatus() == Vcsec.OperationStatus_E.OPERATIONSTATUS_OK;
        }
    }
    
    /**
     * 車輛狀態資訊
     */
    public static class VehicleStatus {
        private final Vcsec.VehicleStatus vcsecStatus;
        
        public VehicleStatus(Vcsec.VehicleStatus vcsecStatus) {
            this.vcsecStatus = vcsecStatus;
        }
        
        public boolean isLocked() {
            return vcsecStatus.getVehicleLockState() == Vcsec.VehicleLockState_E.VEHICLELOCKSTATE_LOCKED;
        }
        
        public String getLockStateDescription() {
            switch (vcsecStatus.getVehicleLockState()) {
                case VEHICLELOCKSTATE_UNLOCKED:
                    return "已解鎖";
                case VEHICLELOCKSTATE_LOCKED:
                    return "已上鎖";
                case VEHICLELOCKSTATE_INTERNAL_LOCKED:
                    return "內部鎖定";
                case VEHICLELOCKSTATE_SELECTIVE_UNLOCKED:
                    return "選擇性解鎖";
                default:
                    return "未知狀態";
            }
        }
        
        public boolean isFrunkClosed() {
            if (!vcsecStatus.hasClosureStatuses()) {
                return true;
            }
            return vcsecStatus.getClosureStatuses().getFrontTrunk() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED;
        }
        
        public boolean isTrunkClosed() {
            if (!vcsecStatus.hasClosureStatuses()) {
                return true;
            }
            return vcsecStatus.getClosureStatuses().getRearTrunk() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED;
        }
        
        public boolean areAllDoorsClosed() {
            if (!vcsecStatus.hasClosureStatuses()) {
                return true;
            }
            Vcsec.ClosureStatuses closures = vcsecStatus.getClosureStatuses();
            return closures.getFrontDriverDoor() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED &&
                   closures.getFrontPassengerDoor() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED &&
                   closures.getRearDriverDoor() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED &&
                   closures.getRearPassengerDoor() == Vcsec.ClosureState_E.CLOSURESTATE_CLOSED;
        }
        
        public Vcsec.VehicleStatus getVCSECStatus() {
            return vcsecStatus;
        }
    }
    
    /**
     * 從資料庫載入session狀態
     */
    public static void loadSessionFromDatabase(Session session, android.content.Context context, String vehicleId) {
        try {
            CarDao carDao = new CarDao(context);
            Car car = carDao.getCarByBleAddress(vehicleId);

            if (car != null) {

                if (session.getDomain() == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                    // 載入VCSEC session資料
                    if (car.getVcsecCounter() > 0) {
                        session.setCounter(car.getVcsecCounter());
                        Log.d(TAG, "從資料庫載入VCSEC counter: " + car.getVcsecCounter());
                    }
                    if (car.getVcsecEpoch() != null && !car.getVcsecEpoch().isEmpty()) {
                        session.setEpoch(org.bouncycastle.util.encoders.Hex.decode(car.getVcsecEpoch()));
                        Log.d(TAG, "從資料庫載入VCSEC epoch");
                    }
                    if (car.getVcsecClockTime() > 0) {
                        session.setClockTime(car.getVcsecClockTime());
                        Log.d(TAG, "從資料庫載入VCSEC clock time: " + car.getVcsecClockTime());
                    }
                    
                    // 載入SharedSecret並進行檢查
                    String vcsecSessionKey = car.getVcsecSessionKey();
                    if (vcsecSessionKey != null && !vcsecSessionKey.isEmpty()) {
                        try {
                            session.sharedSecret = Hex.decode(vcsecSessionKey);
                            Log.d(TAG, "從資料庫載入VCSEC SharedSecret，長度: " + session.sharedSecret.length);
                        } catch (Exception e) {
                            Log.e(TAG, "VCSEC SharedSecret解碼失敗: " + e.getMessage());
                            session.sharedSecret = null;
                        }
                    } else {
                        Log.w(TAG, "VCSEC SessionKey為空或null");
                        session.sharedSecret = null;
                    }
                } else if (session.getDomain() == UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
                    // 載入Infotainment session資料
                    if (car.getInfotainmentCounter() > 0) {
                        session.setCounter(car.getInfotainmentCounter());
                        Log.d(TAG, "從資料庫載入Infotainment counter: " + car.getInfotainmentCounter());
                    }
                    if (car.getInfotainmentEpoch() != null && !car.getInfotainmentEpoch().isEmpty()) {
                        session.setEpoch(org.bouncycastle.util.encoders.Hex.decode(car.getInfotainmentEpoch()));
                        Log.d(TAG, "從資料庫載入Infotainment epoch");
                    }
                    if (car.getInfotainmentClockTime() > 0) {
                        session.setClockTime(car.getInfotainmentClockTime());
                        Log.d(TAG, "從資料庫載入Infotainment clock time: " + car.getInfotainmentClockTime());
                    }

                    // 載入SharedSecret並進行檢查
                    String infotainmentSessionKey = car.getInfotainmentSessionKey();
                    if (infotainmentSessionKey != null && !infotainmentSessionKey.isEmpty()) {
                        try {
                            session.sharedSecret = Hex.decode(infotainmentSessionKey);
                            Log.d(TAG, "從資料庫載入Infotainment SharedSecret，長度: " + session.sharedSecret.length);
                        } catch (Exception e) {
                            Log.e(TAG, "Infotainment SharedSecret解碼失敗: " + e.getMessage());
                            session.sharedSecret = null;
                        }
                    } else {
                        Log.w(TAG, "Infotainment SessionKey為空或null");
                        session.sharedSecret = null;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "從資料庫載入session時出錯: " + e.getMessage());
        }
    }
    
    /**
     * 保存session狀態到資料庫
     */
    public static void saveSessionToDatabase(Session session, android.content.Context context, String vehicleId) {
        try {
            CarDao carDao = new CarDao(context);
            String epochHex = session.getEpoch() != null ? org.bouncycastle.util.encoders.Hex.toHexString(session.getEpoch()) : null;
            
            if (session.getDomain() == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                Log.d(TAG, "VCSEC key: " + Hex.toHexString(session.sharedSecret));
                carDao.updateVCSECSession(vehicleId, session.getCounter(), epochHex, session.getClockTime(), session.sharedSecret);
                Log.d(TAG, "保存VCSEC session到資料庫: counter=" + session.getCounter());
            } else if (session.getDomain() == UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
                carDao.updateInfotainmentSession(vehicleId, session.getCounter(), epochHex, session.getClockTime(), session.sharedSecret);
                Log.d(TAG, "保存Infotainment session到資料庫: counter=" + session.getCounter());
            }
        } catch (Exception e) {
            Log.e(TAG, "保存session到資料庫時出錯: " + e.getMessage());
        }
    }
    
    /**
     * 只保存counter到資料庫（用於命令發送後增量更新）
     */
    public static void saveCounterToDatabase(Session session, android.content.Context context, String vehicleId) {
        try {
            CarDao carDao = new CarDao(context);
            
            if (session.getDomain() == UniversalMessage.Domain.DOMAIN_VEHICLE_SECURITY) {
                carDao.updateVCSECCounter(vehicleId, session.getCounter());
                Log.d(TAG, "保存VCSEC counter到資料庫: " + session.getCounter());
            } else if (session.getDomain() == UniversalMessage.Domain.DOMAIN_INFOTAINMENT) {
                carDao.updateInfotainmentCounter(vehicleId, session.getCounter());
                Log.d(TAG, "保存Infotainment counter到資料庫: " + session.getCounter());
            }
        } catch (Exception e) {
            Log.e(TAG, "保存counter到資料庫時出錯: " + e.getMessage());
        }
    }
} 
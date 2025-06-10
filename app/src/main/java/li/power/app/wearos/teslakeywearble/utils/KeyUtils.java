package li.power.app.wearos.teslakeywearble.utils;

import android.content.Context;
import android.content.SharedPreferences;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import javax.crypto.*;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;

import static android.content.Context.MODE_PRIVATE;

public class KeyUtils {

    private static final String RSA_MODE = "RSA/ECB/PKCS1Padding";
    private static final String CURVE = "secp256r1";
    public static final String KEY_ALIAS = "tesla_nak";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";

    public static PrivateKey getPrivateKey(Context context) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidKeyException, InvalidKeySpecException, NoSuchProviderException, IOException, CertificateException {
        SharedPreferences sharedPreferences = context.getSharedPreferences(KEY_ALIAS, MODE_PRIVATE);
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        
        // 獲取RSA私鑰用於解密
        PrivateKey rsaPrivateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
        if (rsaPrivateKey == null) {
            throw new KeyStoreException("找不到RSA私鑰");
        }
        
        // 獲取加密的ECC私鑰數據
        String encryptedKeyHex = sharedPreferences.getString(KEY_ALIAS, "");
        if (encryptedKeyHex.isEmpty()) {
            throw new KeyStoreException("找不到加密的ECC私鑰");
        }
        
        // 解密ECC私鑰
        byte[] encryptedKeyData = Hex.decode(encryptedKeyHex);
        byte[] eccPrivateKeyData = decryptRSA(rsaPrivateKey, encryptedKeyData);

        byte[] pktest = Hex.decode("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB");

        // 載入ECC私鑰
        return loadPrivateKey(pktest);
    }

    public static byte[] doECDH(PrivateKey privKey, PublicKey pubKey) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH", "BC");
        ka.init(privKey);
        ka.doPhase(pubKey, true);
        return ka.generateSecret();
    }

    public static byte[] decryptRSA(PrivateKey privateKey, byte[] ciphertext) throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance(RSA_MODE);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(ciphertext);
    }

    public static PrivateKey loadPrivateKey(byte[] data) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {
        setupBouncyCastle();
        
        ECParameterSpec params = ECNamedCurveTable.getParameterSpec(CURVE);
        ECPrivateKeySpec prvkey = new ECPrivateKeySpec(new BigInteger(1, data), params);
        KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        return kf.generatePrivate(prvkey);
    }

    public static PublicKey loadPublicKey(byte[] data) throws Exception {
        setupBouncyCastle();
        
        ECParameterSpec params = ECNamedCurveTable.getParameterSpec(CURVE);
        ECPublicKeySpec pubKey = new ECPublicKeySpec(
                params.getCurve().decodePoint(data), params);
        KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        return kf.generatePublic(pubKey);
    }

    public static ECPublicKey getPublicKeyFromPrivate(ECPrivateKey privateKey) throws Exception {
        setupBouncyCastle();
        
        KeyFactory keyFactory = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);

        BigInteger d = privateKey.getD();
        org.bouncycastle.jce.spec.ECParameterSpec ecSpec = privateKey.getParameters();
        ECPoint Q = ecSpec.getG().multiply(d);

        org.bouncycastle.jce.spec.ECPublicKeySpec pubSpec = new
                org.bouncycastle.jce.spec.ECPublicKeySpec(Q, ecSpec);
        return (ECPublicKey) keyFactory.generatePublic(pubSpec);
    }

    public static void setupBouncyCastle() {
        final Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider == null) {
            // 添加BouncyCastle provider
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
            return;
        }
        if (provider.getClass().equals(BouncyCastleProvider.class)) {
            return;
        }
        // 移除舊的provider並添加新的
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /**
     * 將PublicKey轉換為byte數組
     */
    public static byte[] publicKeyToBytes(PublicKey publicKey) throws Exception {
        if (!(publicKey instanceof ECPublicKey)) {
            throw new Exception("需要EC公鑰");
        }
        
        ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
        org.bouncycastle.math.ec.ECPoint point = ecPublicKey.getQ();
        
        // 使用未壓縮格式（0x04前綴）
        return point.getEncoded(false);
    }

    public static PublicKey bytesToPublicKey(byte[] keyBytes) throws Exception {
        return loadPublicKey(keyBytes);
    }

}

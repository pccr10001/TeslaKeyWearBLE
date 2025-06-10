package li.power.app.wearos.teslakeywearble.models;

public class Car {
    private long id;
    private String bleAddress;
    private String bleName;
    private String name;
    private String keyId;
    private String vin;
    private long createdTime;
    private long lastConnectedTime;
    
    // Session相關欄位
    private int vcsecCounter = 1;
    private String vcsecEpoch;
    private long vcsecClockTime;
    private String vcsecSessionKey;
    private int infotainmentCounter = 1;
    private String infotainmentEpoch;
    private long infotainmentClockTime;
    private String infotainmentSessionKey;
    
    private boolean isConnected = false;
    private boolean isLocked = true;
    private int signalStrength = 0;
    private String model = "Tesla";
    
    // 添加前後備箱狀態
    private boolean isFrunkOpen = false;
    private boolean isTrunkOpen = false;


    public Car() {
        this.createdTime = System.currentTimeMillis();
        this.lastConnectedTime = System.currentTimeMillis();
    }

    public Car(String bleAddress, String bleName, String name, String keyId,String vin) {
        this();
        this.bleAddress = bleAddress;
        this.bleName = bleName;
        this.name = name;
        this.keyId = keyId;
        this.vin = vin;
    }
    
    public Car(String name, String bluetoothAddress, int signalStrength) {
        this();
        this.name = name;
        this.bleAddress = bluetoothAddress;
        this.signalStrength = signalStrength;
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getBleAddress() {
        return bleAddress;
    }

    public void setBleAddress(String bleAddress) {
        this.bleAddress = bleAddress;
    }

    public String getBleName() {
        return bleName;
    }

    public void setBleName(String bleName) {
        this.bleName = bleName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastConnectedTime() {
        return lastConnectedTime;
    }

    public void setLastConnectedTime(long lastConnectedTime) {
        this.lastConnectedTime = lastConnectedTime;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }

    public boolean isLocked() {
        return isLocked;
    }

    public void setLocked(boolean locked) {
        isLocked = locked;
    }

    public int getSignalStrength() {
        return signalStrength;
    }

    public void setSignalStrength(int signalStrength) {
        this.signalStrength = signalStrength;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    // Session相關欄位的getters和setters
    public int getVcsecCounter() {
        return vcsecCounter;
    }

    public void setVcsecCounter(int vcsecCounter) {
        this.vcsecCounter = vcsecCounter;
    }

    public String getVcsecEpoch() {
        return vcsecEpoch;
    }

    public void setVcsecEpoch(String vcsecEpoch) {
        this.vcsecEpoch = vcsecEpoch;
    }

    public long getVcsecClockTime() {
        return vcsecClockTime;
    }

    public void setVcsecClockTime(long vcsecClockTime) {
        this.vcsecClockTime = vcsecClockTime;
    }

    public int getInfotainmentCounter() {
        return infotainmentCounter;
    }

    public void setInfotainmentCounter(int infotainmentCounter) {
        this.infotainmentCounter = infotainmentCounter;
    }

    public String getInfotainmentEpoch() {
        return infotainmentEpoch;
    }

    public void setInfotainmentEpoch(String infotainmentEpoch) {
        this.infotainmentEpoch = infotainmentEpoch;
    }

    public long getInfotainmentClockTime() {
        return infotainmentClockTime;
    }

    public void setInfotainmentClockTime(long infotainmentClockTime) {
        this.infotainmentClockTime = infotainmentClockTime;
    }

    public String getVcsecSessionKey() {
        return vcsecSessionKey;
    }

    public void setVcsecSessionKey(String vcsecSessionKey) {
        this.vcsecSessionKey = vcsecSessionKey;
    }

    public String getInfotainmentSessionKey() {
        return infotainmentSessionKey;
    }

    public void setInfotainmentSessionKey(String infotainmentSessionKey) {
        this.infotainmentSessionKey = infotainmentSessionKey;
    }

    // 前後備箱狀態的 getter 和 setter 方法
    public boolean isFrunkOpen() {
        return isFrunkOpen;
    }

    public void setFrunkOpen(boolean frunkOpen) {
        isFrunkOpen = frunkOpen;
    }

    public boolean isTrunkOpen() {
        return isTrunkOpen;
    }

    public void setTrunkOpen(boolean trunkOpen) {
        isTrunkOpen = trunkOpen;
    }

    @Override
    public String toString() {
        return "Car{" +
                "id=" + id +
                ", bleAddress='" + bleAddress + '\'' +
                ", bleName='" + bleName + '\'' +
                ", name='" + name + '\'' +
                ", keyId='" + keyId + '\'' +
                ", vin='" + vin + '\'' +
                ", createdTime=" + createdTime +
                ", lastConnectedTime=" + lastConnectedTime +
                ", isConnected=" + isConnected +
                ", isLocked=" + isLocked +
                ", signalStrength=" + signalStrength +
                ", model='" + model + '\'' +
                '}';
    }
} 
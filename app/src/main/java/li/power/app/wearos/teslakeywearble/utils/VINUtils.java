package li.power.app.wearos.teslakeywearble.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Tesla VIN 驗證工具類別
 * 基於 teslatap_vin.js 的邏輯實現
 */
public class VINUtils {
    
    /**
     * VIN 驗證結果類別
     */
    public static class VINValidationResult {
        private boolean isValid;
        private String errorMessage;
        private String modelYear;
        private String vehicleType;
        private String manufacturer;
        private String buildLocation;
        
        public VINValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }
        
        // Getters and Setters
        public boolean isValid() { return isValid; }
        public void setValid(boolean valid) { isValid = valid; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public String getModelYear() { return modelYear; }
        public void setModelYear(String modelYear) { this.modelYear = modelYear; }
        public String getVehicleType() { return vehicleType; }
        public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
        public String getManufacturer() { return manufacturer; }
        public void setManufacturer(String manufacturer) { this.manufacturer = manufacturer; }
        public String getBuildLocation() { return buildLocation; }
        public void setBuildLocation(String buildLocation) { this.buildLocation = buildLocation; }
    }
    
    // VIN 字符到數字的映射（用於校驗位計算）
    private static final Map<Character, Integer> VIN_CHAR_MAP = new HashMap<>();
    static {
        VIN_CHAR_MAP.put('A', 1); VIN_CHAR_MAP.put('B', 2); VIN_CHAR_MAP.put('C', 3);
        VIN_CHAR_MAP.put('D', 4); VIN_CHAR_MAP.put('E', 5); VIN_CHAR_MAP.put('F', 6);
        VIN_CHAR_MAP.put('G', 7); VIN_CHAR_MAP.put('H', 8); VIN_CHAR_MAP.put('J', 1);
        VIN_CHAR_MAP.put('K', 2); VIN_CHAR_MAP.put('L', 3); VIN_CHAR_MAP.put('M', 4);
        VIN_CHAR_MAP.put('N', 5); VIN_CHAR_MAP.put('P', 7); VIN_CHAR_MAP.put('R', 9);
        VIN_CHAR_MAP.put('S', 2); VIN_CHAR_MAP.put('T', 3); VIN_CHAR_MAP.put('U', 4);
        VIN_CHAR_MAP.put('V', 5); VIN_CHAR_MAP.put('W', 6); VIN_CHAR_MAP.put('X', 7);
        VIN_CHAR_MAP.put('Y', 8); VIN_CHAR_MAP.put('Z', 9);
        // 數字保持原值
        for (int i = 0; i <= 9; i++) {
            VIN_CHAR_MAP.put((char)('0' + i), i);
        }
    }
    
    // 校驗位權重
    private static final int[] CHECK_WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 9, 8, 7, 6, 5, 4, 3, 2};
    
    // 年份字符映射
    private static final Map<Character, String> YEAR_MAP = new HashMap<>();
    static {
        YEAR_MAP.put('6', "2006"); YEAR_MAP.put('7', "2007"); YEAR_MAP.put('8', "2008"); YEAR_MAP.put('9', "2009");
        YEAR_MAP.put('A', "2010"); YEAR_MAP.put('B', "2011"); YEAR_MAP.put('C', "2012"); YEAR_MAP.put('D', "2013");
        YEAR_MAP.put('E', "2014"); YEAR_MAP.put('F', "2015"); YEAR_MAP.put('G', "2016"); YEAR_MAP.put('H', "2017");
        YEAR_MAP.put('J', "2018"); YEAR_MAP.put('K', "2019"); YEAR_MAP.put('L', "2020"); YEAR_MAP.put('M', "2021");
        YEAR_MAP.put('N', "2022"); YEAR_MAP.put('P', "2023"); YEAR_MAP.put('R', "2024"); YEAR_MAP.put('S', "2025");
        YEAR_MAP.put('T', "2026"); YEAR_MAP.put('V', "2027"); YEAR_MAP.put('W', "2028"); YEAR_MAP.put('X', "2029");
        YEAR_MAP.put('Y', "2030");
    }
    
    // 車型映射
    private static final Map<Character, String> VEHICLE_TYPE_MAP = new HashMap<>();
    static {
        VEHICLE_TYPE_MAP.put('C', "Cybertruck");
        VEHICLE_TYPE_MAP.put('R', "Roadster");
        VEHICLE_TYPE_MAP.put('S', "Model S");
        VEHICLE_TYPE_MAP.put('T', "Tesla Semi");
        VEHICLE_TYPE_MAP.put('X', "Model X");
        VEHICLE_TYPE_MAP.put('Y', "Model Y");
        VEHICLE_TYPE_MAP.put('3', "Model 3");
    }
    
    // 製造商映射
    private static final Map<String, String> MANUFACTURER_MAP = new HashMap<>();
    static {
        MANUFACTURER_MAP.put("5YJ", "Tesla, Inc.");
        MANUFACTURER_MAP.put("LRW", "Tesla, China");
        MANUFACTURER_MAP.put("7G2", "Tesla, Inc., Truck");
        MANUFACTURER_MAP.put("7SA", "Tesla, MPV, for Model X/Y");
        MANUFACTURER_MAP.put("SFZ", "Tesla Motors (Roadsters fully assembled in UK)");
        MANUFACTURER_MAP.put("XP7", "Tesla, Berlin");
    }
    
    // 製造地點映射
    private static final Map<Character, String> BUILD_LOCATION_MAP = new HashMap<>();
    static {
        BUILD_LOCATION_MAP.put('1', "Menlo Park, CA, USA");
        BUILD_LOCATION_MAP.put('3', "Hethel, UK");
        BUILD_LOCATION_MAP.put('A', "Austin, Texas, USA");
        BUILD_LOCATION_MAP.put('B', "Berlin, Germany");
        BUILD_LOCATION_MAP.put('C', "Shanghai, China");
        BUILD_LOCATION_MAP.put('F', "Fremont, CA, USA");
        BUILD_LOCATION_MAP.put('G', "Berlin, Germany");
        BUILD_LOCATION_MAP.put('N', "Reno, NV, USA");
        BUILD_LOCATION_MAP.put('P', "Palo Alto, CA, USA");
        BUILD_LOCATION_MAP.put('R', "Research");
    }
    
    /**
     * 驗證 Tesla VIN 碼
     * @param vin VIN 碼字符串
     * @return VINValidationResult 驗證結果
     */
    public static VINValidationResult validateVIN(String vin) {
        if (vin == null || vin.trim().isEmpty()) {
            return new VINValidationResult(false, "VIN 碼不能為空");
        }
        
        vin = vin.trim().toUpperCase();
        
        // 檢查長度
        if (vin.length() != 17) {
            return new VINValidationResult(false, "VIN 碼必須為 17 個字符，目前只有 " + vin.length() + " 個字符");
        }
        
        // 檢查無效字符
        if (vin.matches(".*[IOQ].*")) {
            return new VINValidationResult(false, "VIN 碼不能包含字母 I、O 或 Q");
        }
        
        // 檢查製造商（前3位）
        String manufacturer = vin.substring(0, 3);
        if (!MANUFACTURER_MAP.containsKey(manufacturer)) {
            return new VINValidationResult(false, "製造商代碼不是 Tesla（前3個字符）");
        }
        
        // 檢查車型（第4位）
        char vehicleTypeChar = vin.charAt(3);
        if (!VEHICLE_TYPE_MAP.containsKey(vehicleTypeChar)) {
            return new VINValidationResult(false, "未知的車型代碼（第4位）");
        }
        
        // 檢查年份（第10位）
        char yearChar = vin.charAt(9);
        if (!YEAR_MAP.containsKey(yearChar)) {
            return new VINValidationResult(false, "未知的年份代碼（第10位）");
        }
        
        // 檢查製造地點（第11位）
        char locationChar = vin.charAt(10);
        if (!BUILD_LOCATION_MAP.containsKey(locationChar)) {
            return new VINValidationResult(false, "未知的製造地點代碼（第11位）");
        }
        
        // 驗證校驗位
        char calculatedCheckDigit = calculateCheckDigit(vin);
        char actualCheckDigit = vin.charAt(8);
        
        if (calculatedCheckDigit != actualCheckDigit) {
            return new VINValidationResult(false, 
                "校驗位不正確，應該是 " + calculatedCheckDigit + "，但實際是 " + actualCheckDigit);
        }
        
        // 創建成功結果
        VINValidationResult result = new VINValidationResult(true, null);
        result.setManufacturer(MANUFACTURER_MAP.get(manufacturer));
        result.setVehicleType(VEHICLE_TYPE_MAP.get(vehicleTypeChar));
        result.setModelYear(YEAR_MAP.get(yearChar));
        result.setBuildLocation(BUILD_LOCATION_MAP.get(locationChar));
        
        return result;
    }
    
    /**
     * 計算 VIN 校驗位
     * @param vin VIN 碼
     * @return 校驗位字符
     */
    private static char calculateCheckDigit(String vin) {
        if (vin.length() != 17) {
            return '?';
        }
        
        // 去除第9位（校驗位）
        String vinWithoutCheck = vin.substring(0, 8) + vin.substring(9);
        
        int sum = 0;
        for (int i = 0; i < vinWithoutCheck.length(); i++) {
            char c = vinWithoutCheck.charAt(i);
            Integer value = VIN_CHAR_MAP.get(c);
            if (value == null) {
                return '?'; // 無效字符
            }
            sum += value * CHECK_WEIGHTS[i];
        }
        
        int remainder = sum % 11;
        if (remainder == 10) {
            return 'X';
        } else {
            return (char)('0' + remainder);
        }
    }
    
    /**
     * 檢查是否為有效的 Tesla VIN（快速檢查）
     * @param vin VIN 碼
     * @return true 如果是有效的 Tesla VIN
     */
    public static boolean isValidTeslaVIN(String vin) {
        return validateVIN(vin).isValid();
    }
    
    /**
     * 格式化 VIN 顯示（添加分隔符以便閱讀）
     * @param vin VIN 碼
     * @return 格式化的 VIN 字符串
     */
    public static String formatVIN(String vin) {
        if (vin == null || vin.length() != 17) {
            return vin;
        }
        // 格式：5YJ-SA1-E62-N-F-016329
        return vin.substring(0, 3) + "-" + 
               vin.substring(3, 6) + "-" + 
               vin.substring(6, 9) + "-" + 
               vin.substring(9, 10) + "-" + 
               vin.substring(10, 11) + "-" + 
               vin.substring(11);
    }
} 
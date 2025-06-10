package li.power.app.wearos.teslakeywearble.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import li.power.app.wearos.teslakeywearble.models.Car;
import org.apache.commons.codec.binary.Hex;

import java.util.ArrayList;
import java.util.List;

public class CarDao {
    
    private static final String TAG = "CarDao";
    private CarDatabaseHelper dbHelper;
    
    public CarDao(Context context) {
        this.dbHelper = CarDatabaseHelper.getInstance(context);
    }
    
    /**
     * 添加新車輛
     */
    public long addCar(Car car) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        long carId = -1;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_BLE_ADDRESS, car.getBleAddress());
            values.put(CarDatabaseHelper.COLUMN_BLE_NAME, car.getBleName());
            values.put(CarDatabaseHelper.COLUMN_NAME, car.getName());
            values.put(CarDatabaseHelper.COLUMN_KEY_ID, car.getKeyId());
            values.put(CarDatabaseHelper.COLUMN_VIN, car.getVin());
            values.put(CarDatabaseHelper.COLUMN_CREATED_TIME, car.getCreatedTime());
            values.put(CarDatabaseHelper.COLUMN_LAST_CONNECTED_TIME, car.getLastConnectedTime());


            carId = db.insert(CarDatabaseHelper.TABLE_CARS, null, values);
            
            if (carId != -1) {
                car.setId(carId);
                Log.d(TAG, "成功添加車輛: " + car.getName() + ", ID: " + carId);
            } else {
                Log.e(TAG, "添加車輛失敗");
            }
        } catch (Exception e) {
            Log.e(TAG, "添加車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return carId;
    }
    
    /**
     * 根據ID獲取車輛
     */
    public Car getCarById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Car car = null;
        
        try {
            String selection = CarDatabaseHelper.COLUMN_ID + " = ?";
            String[] selectionArgs = {String.valueOf(id)};
            
            Cursor cursor = db.query(CarDatabaseHelper.TABLE_CARS, null, selection, selectionArgs, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                car = cursorToCar(cursor);
            }
            
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "根據ID獲取車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return car;
    }
    
    /**
     * 根據藍牙地址獲取車輛
     */
    public Car getCarByBleAddress(String bleAddress) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Car car = null;
        
        try {
            String selection = CarDatabaseHelper.COLUMN_BLE_ADDRESS + " = ?";
            String[] selectionArgs = {bleAddress};
            
            Cursor cursor = db.query(CarDatabaseHelper.TABLE_CARS, null, selection, selectionArgs, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                car = cursorToCar(cursor);
            }
            
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "根據藍牙地址獲取車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return car;
    }
    
    /**
     * 根據KeyID獲取車輛
     */
    public Car getCarByKeyId(String keyId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Car car = null;
        
        try {
            String selection = CarDatabaseHelper.COLUMN_KEY_ID + " = ?";
            String[] selectionArgs = {keyId};
            
            Cursor cursor = db.query(CarDatabaseHelper.TABLE_CARS, null, selection, selectionArgs, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                car = cursorToCar(cursor);
            }
            
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "根據KeyID獲取車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return car;
    }
    
    /**
     * 獲取所有車輛
     */
    public List<Car> getAllCars() {
        List<Car> cars = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        
        try {
            String orderBy = CarDatabaseHelper.COLUMN_LAST_CONNECTED_TIME + " DESC";
            Cursor cursor = db.query(CarDatabaseHelper.TABLE_CARS, null, null, null, null, null, orderBy);
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    Car car = cursorToCar(cursor);
                    cars.add(car);
                } while (cursor.moveToNext());
            }
            
            if (cursor != null) {
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "獲取所有車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return cars;
    }
    
    /**
     * 更新車輛資訊
     */
    public int updateCar(Car car) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_BLE_ADDRESS, car.getBleAddress());
            values.put(CarDatabaseHelper.COLUMN_BLE_NAME, car.getBleName());
            values.put(CarDatabaseHelper.COLUMN_NAME, car.getName());
            values.put(CarDatabaseHelper.COLUMN_KEY_ID, car.getKeyId());
            values.put(CarDatabaseHelper.COLUMN_VIN, car.getVin());
            values.put(CarDatabaseHelper.COLUMN_LAST_CONNECTED_TIME, car.getLastConnectedTime());
            
            String whereClause = CarDatabaseHelper.COLUMN_ID + " = ?";
            String[] whereArgs = {String.valueOf(car.getId())};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
            
            Log.d(TAG, "更新車輛，影響行數: " + rowsAffected);
        } catch (Exception e) {
            Log.e(TAG, "更新車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 更新車輛最後連接時間
     */
    public int updateLastConnectedTime(long carId, long lastConnectedTime) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_LAST_CONNECTED_TIME, lastConnectedTime);
            
            String whereClause = CarDatabaseHelper.COLUMN_ID + " = ?";
            String[] whereArgs = {String.valueOf(carId)};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
        } catch (Exception e) {
            Log.e(TAG, "更新最後連接時間時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 刪除車輛
     */
    public int deleteCar(long carId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            String whereClause = CarDatabaseHelper.COLUMN_ID + " = ?";
            String[] whereArgs = {String.valueOf(carId)};
            
            rowsAffected = db.delete(CarDatabaseHelper.TABLE_CARS, whereClause, whereArgs);
            
            Log.d(TAG, "刪除車輛，影響行數: " + rowsAffected);
        } catch (Exception e) {
            Log.e(TAG, "刪除車輛時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 檢查車輛是否已存在（根據藍牙地址）
     */
    public boolean isCarExists(String bleAddress) {
        return getCarByBleAddress(bleAddress) != null;
    }
    
    /**
     * 檢查KeyID是否已存在
     */
    public boolean isKeyIdExists(String keyId) {
        return getCarByKeyId(keyId) != null;
    }
    
    /**
     * 從Cursor創建Car對象
     */
    private Car cursorToCar(Cursor cursor) {
        Car car = new Car();
        
        car.setId(cursor.getLong(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_ID)));
        car.setBleAddress(cursor.getString(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_BLE_ADDRESS)));
        car.setBleName(cursor.getString(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_BLE_NAME)));
        car.setName(cursor.getString(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_NAME)));
        car.setKeyId(cursor.getString(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_KEY_ID)));
        car.setVin(cursor.getString(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_VIN)));
        car.setCreatedTime(cursor.getLong(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_CREATED_TIME)));
        car.setLastConnectedTime(cursor.getLong(cursor.getColumnIndexOrThrow(CarDatabaseHelper.COLUMN_LAST_CONNECTED_TIME)));
        
        // Session相關欄位 - 處理可能為null的情況
        try {
            int vcsecCounterIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_VCSEC_COUNTER);
            if (vcsecCounterIndex >= 0 && !cursor.isNull(vcsecCounterIndex)) {
                car.setVcsecCounter(cursor.getInt(vcsecCounterIndex));
            }
            
            int vcsecEpochIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_VCSEC_EPOCH);
            if (vcsecEpochIndex >= 0 && !cursor.isNull(vcsecEpochIndex)) {
                car.setVcsecEpoch(cursor.getString(vcsecEpochIndex));
            }
            
            int vcsecClockTimeIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_VCSEC_CLOCK_TIME);
            if (vcsecClockTimeIndex >= 0 && !cursor.isNull(vcsecClockTimeIndex)) {
                car.setVcsecClockTime(cursor.getLong(vcsecClockTimeIndex));
            }
            
            // 添加讀取VCSEC Session Key
            int vcsecSessionKeyIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_VCSEC_SESSION_KEY);
            if (vcsecSessionKeyIndex >= 0 && !cursor.isNull(vcsecSessionKeyIndex)) {
                car.setVcsecSessionKey(cursor.getString(vcsecSessionKeyIndex));
            }
            
            int infotainmentCounterIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_INFOTAINMENT_COUNTER);
            if (infotainmentCounterIndex >= 0 && !cursor.isNull(infotainmentCounterIndex)) {
                car.setInfotainmentCounter(cursor.getInt(infotainmentCounterIndex));
            }
            
            int infotainmentEpochIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_INFOTAINMENT_EPOCH);
            if (infotainmentEpochIndex >= 0 && !cursor.isNull(infotainmentEpochIndex)) {
                car.setInfotainmentEpoch(cursor.getString(infotainmentEpochIndex));
            }
            
            int infotainmentClockTimeIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_INFOTAINMENT_CLOCK_TIME);
            if (infotainmentClockTimeIndex >= 0 && !cursor.isNull(infotainmentClockTimeIndex)) {
                car.setInfotainmentClockTime(cursor.getLong(infotainmentClockTimeIndex));
            }
            
            // 添加讀取Infotainment Session Key
            int infotainmentSessionKeyIndex = cursor.getColumnIndex(CarDatabaseHelper.COLUMN_INFOTAINMENT_SESSION_KEY);
            if (infotainmentSessionKeyIndex >= 0 && !cursor.isNull(infotainmentSessionKeyIndex)) {
                car.setInfotainmentSessionKey(cursor.getString(infotainmentSessionKeyIndex));
            }
        } catch (Exception e) {
            Log.w(TAG, "讀取session欄位時出錯（可能是舊版資料庫）: " + e.getMessage());
        }
        
        return car;
    }
    
    /**
     * 更新VCSEC Session資訊
     */
    public int updateVCSECSession(String bleAddress, int counter, String epoch, long clockTime, byte[] sharedSecret) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_VCSEC_COUNTER, counter);
            values.put(CarDatabaseHelper.COLUMN_VCSEC_EPOCH, epoch);
            values.put(CarDatabaseHelper.COLUMN_VCSEC_CLOCK_TIME, clockTime);
            values.put(CarDatabaseHelper.COLUMN_VCSEC_SESSION_KEY, Hex.encodeHexString(sharedSecret));
            
            String whereClause = CarDatabaseHelper.COLUMN_BLE_ADDRESS + " = ?";
            String[] whereArgs = {bleAddress};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
            
            Log.d(TAG, "VCSEC Session更新完成: " + bleAddress + ", counter: " + counter + ", 影響行數: " + rowsAffected);
        } catch (Exception e) {
            Log.e(TAG, "更新VCSEC Session時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 更新Infotainment Session資訊
     */
    public int updateInfotainmentSession(String bleAddress, int counter, String epoch, long clockTime, byte[] sharedSecret) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_INFOTAINMENT_COUNTER, counter);
            values.put(CarDatabaseHelper.COLUMN_INFOTAINMENT_EPOCH, epoch);
            values.put(CarDatabaseHelper.COLUMN_INFOTAINMENT_CLOCK_TIME, clockTime);
            values.put(CarDatabaseHelper.COLUMN_INFOTAINMENT_SESSION_KEY, Hex.encodeHexString(sharedSecret));
            
            String whereClause = CarDatabaseHelper.COLUMN_BLE_ADDRESS + " = ?";
            String[] whereArgs = {bleAddress};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
            
            Log.d(TAG, "Infotainment Session更新完成: " + bleAddress + ", counter: " + counter + ", 影響行數: " + rowsAffected);
        } catch (Exception e) {
            Log.e(TAG, "更新Infotainment Session時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 只更新counter值（用於命令發送後增量更新）
     */
    public int updateVCSECCounter(String bleAddress, int counter) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_VCSEC_COUNTER, counter);
            
            String whereClause = CarDatabaseHelper.COLUMN_BLE_ADDRESS + " = ?";
            String[] whereArgs = {bleAddress};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
            
            Log.d(TAG, "VCSEC Counter更新完成: " + bleAddress + ", counter: " + counter);
        } catch (Exception e) {
            Log.e(TAG, "更新VCSEC Counter時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
    
    /**
     * 只更新Infotainment counter值
     */
    public int updateInfotainmentCounter(String bleAddress, int counter) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        int rowsAffected = 0;
        
        try {
            ContentValues values = new ContentValues();
            values.put(CarDatabaseHelper.COLUMN_INFOTAINMENT_COUNTER, counter);
            
            String whereClause = CarDatabaseHelper.COLUMN_BLE_ADDRESS + " = ?";
            String[] whereArgs = {bleAddress};
            
            rowsAffected = db.update(CarDatabaseHelper.TABLE_CARS, values, whereClause, whereArgs);
            
            Log.d(TAG, "Infotainment Counter更新完成: " + bleAddress + ", counter: " + counter);
        } catch (Exception e) {
            Log.e(TAG, "更新Infotainment Counter時出錯", e);
        } finally {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
        
        return rowsAffected;
    }
} 
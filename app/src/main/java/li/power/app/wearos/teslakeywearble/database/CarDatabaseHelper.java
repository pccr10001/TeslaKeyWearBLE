package li.power.app.wearos.teslakeywearble.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class CarDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "CarDatabaseHelper";

    // 數據庫配置
    private static final String DATABASE_NAME = "tesla_cars.db";
    private static final int DATABASE_VERSION = 2;  // 增加版本號以支持session字段

    // 表名
    public static final String TABLE_CARS = "cars";

    // 列名
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_BLE_ADDRESS = "ble_address";
    public static final String COLUMN_BLE_NAME = "ble_name";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_KEY_ID = "key_id";
    public static final String COLUMN_VIN = "vin";
    public static final String COLUMN_VCSEC_SESSION_KEY = "vcsec_session_key";
    public static final String COLUMN_CREATED_TIME = "created_time";
    public static final String COLUMN_LAST_CONNECTED_TIME = "last_connected_time";

    // Session相關欄位
    public static final String COLUMN_VCSEC_COUNTER = "vcsec_counter";
    public static final String COLUMN_VCSEC_EPOCH = "vcsec_epoch";
    public static final String COLUMN_VCSEC_CLOCK_TIME = "vcsec_clock_time";
    public static final String COLUMN_INFOTAINMENT_COUNTER = "infotainment_counter";
    public static final String COLUMN_INFOTAINMENT_EPOCH = "infotainment_epoch";
    public static final String COLUMN_INFOTAINMENT_CLOCK_TIME = "infotainment_clock_time";
    public static final String COLUMN_INFOTAINMENT_SESSION_KEY = "infotainment_session_key";

    // 創建表的SQL語句
    private static final String CREATE_TABLE_CARS =
            "CREATE TABLE " + TABLE_CARS + " (" +
                    COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COLUMN_BLE_ADDRESS + " TEXT NOT NULL UNIQUE, " +
                    COLUMN_BLE_NAME + " TEXT, " +
                    COLUMN_NAME + " TEXT, " +
                    COLUMN_KEY_ID + " TEXT UNIQUE, " +
                    COLUMN_VIN + " TEXT, " +
                    COLUMN_VCSEC_SESSION_KEY + " TEXT, " +
                    COLUMN_CREATED_TIME + " INTEGER NOT NULL, " +
                    COLUMN_LAST_CONNECTED_TIME + " INTEGER NOT NULL, " +
                    // Session相關欄位
                    COLUMN_VCSEC_COUNTER + " INTEGER DEFAULT 1, " +
                    COLUMN_VCSEC_EPOCH + " TEXT, " +
                    COLUMN_VCSEC_CLOCK_TIME + " INTEGER, " +
                    COLUMN_INFOTAINMENT_COUNTER + " INTEGER DEFAULT 1, " +
                    COLUMN_INFOTAINMENT_EPOCH + " TEXT, " +
                    COLUMN_INFOTAINMENT_CLOCK_TIME + " INTEGER, " +
                    COLUMN_INFOTAINMENT_SESSION_KEY + " TEXT " +
                    ");";

    // 創建索引
    private static final String CREATE_INDEX_BLE_ADDRESS =
            "CREATE INDEX idx_ble_address ON " + TABLE_CARS + "(" + COLUMN_BLE_ADDRESS + ");";

    private static final String CREATE_INDEX_KEY_ID =
            "CREATE INDEX idx_key_id ON " + TABLE_CARS + "(" + COLUMN_KEY_ID + ");";

    private static CarDatabaseHelper instance;

    private CarDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    // 單例模式
    public static synchronized CarDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new CarDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "創建數據庫表");

        try {
            // 創建cars表
            db.execSQL(CREATE_TABLE_CARS);
            Log.d(TAG, "成功創建cars表");

            // 創建索引
            db.execSQL(CREATE_INDEX_BLE_ADDRESS);
            db.execSQL(CREATE_INDEX_KEY_ID);
            Log.d(TAG, "成功創建索引");

        } catch (Exception e) {
            Log.e(TAG, "創建數據庫時出錯", e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // 啟用外鍵約束
        if (!db.isReadOnly()) {
            db.execSQL("PRAGMA foreign_keys=ON;");
        }
    }
} 
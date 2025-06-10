package li.power.app.wearos.teslakeywearble.adapters;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import li.power.app.wearos.teslakeywearble.PairCarActivity;
import li.power.app.wearos.teslakeywearble.R;
import li.power.app.wearos.teslakeywearble.models.Car;
import java.util.List;

public class CarPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int TYPE_CAR = 0;
    private static final int TYPE_ADD_CAR = 1;
    private static final long LONG_PRESS_DURATION = 2000;
    
    private List<Car> cars;
    private Context context;
    private OnCarActionListener listener;
    private String currentConnectionStatus = "未連接"; // 添加當前連接狀態
    
    public interface OnCarActionListener {
        void onFrontTrunkClick(Car car);
        void onLockUnlockClick(Car car);
        void onRearTrunkClick(Car car);
        void onVehicleNameClick(Car car);
        void onVehicleNameLongPress(Car car);
    }
    
    public CarPagerAdapter(Context context, List<Car> cars, OnCarActionListener listener) {
        this.context = context;
        this.cars = cars;
        this.listener = listener;

        // Enable stable IDs to avoid unnecessary view recreation which may cause
        // flickering when notifyItemChanged is called frequently
        setHasStableIds(true);
    }
    
    /**
     * 更新車輛清單
     */
    public void updateCars(List<Car> newCars) {
        this.cars = newCars;
    }
    
    /**
     * 更新連接狀態
     */
    public void updateConnectionStatus(String status) {
        this.currentConnectionStatus = status;
    }
    
    @Override
    public int getItemViewType(int position) {
        return position < cars.size() ? TYPE_CAR : TYPE_ADD_CAR;
    }
    
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_CAR) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_car_control, parent, false);
            return new CarViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_add_car, parent, false);
            return new AddCarViewHolder(view);
        }
    }
    
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CarViewHolder) {
            ((CarViewHolder) holder).bind(cars.get(position));
        } else if (holder instanceof AddCarViewHolder) {
            ((AddCarViewHolder) holder).bind();
        }
    }
    
    @Override
    public int getItemCount() {
        return cars.size() + 1; // +1 for add car page
    }

    @Override
    public long getItemId(int position) {
        if (position < cars.size()) {
            return cars.get(position).getId();
        } else {
            // Use a negative ID for the "add car" page
            return -1;
        }
    }
    
    class CarViewHolder extends RecyclerView.ViewHolder {
        private TextView tvCarName;
        private LinearLayout btnFrontTrunk;
        private LinearLayout btnLockUnlock;
        private LinearLayout btnRearTrunk;
        private ImageView ivLockStatus;
        private ImageView ivFrontTrunkStatus;
        private ImageView ivRearTrunkStatus;
        private TextView tvConnectionStatus;
        
        // 長按相關變數
        private Handler longPressHandler = new Handler();
        private Runnable longPressRunnable;
        private boolean isLongPressing = false;
        private boolean longPressExecuted = false;
        
        // 車輛名稱長按相關變數
        private Handler nameeLongPressHandler = new Handler();
        private Runnable nameLongPressRunnable;
        private boolean isNameLongPressing = false;
        private boolean nameLongPressExecuted = false;
        
        public CarViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCarName = itemView.findViewById(R.id.tv_car_name);
            btnFrontTrunk = itemView.findViewById(R.id.btn_front_trunk);
            btnLockUnlock = itemView.findViewById(R.id.btn_lock_unlock);
            btnRearTrunk = itemView.findViewById(R.id.btn_rear_trunk);
            ivLockStatus = itemView.findViewById(R.id.iv_lock_status);
            ivFrontTrunkStatus = itemView.findViewById(R.id.iv_front_trunk_status);
            ivRearTrunkStatus = itemView.findViewById(R.id.iv_rear_trunk_status);
            tvConnectionStatus = itemView.findViewById(R.id.tv_connection_status);
        }
        
        public void bind(Car car) {
            tvCarName.setText(car.getName());
            
            // 設置車輛名稱的觸控事件
            tvCarName.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 開始長按檢測
                        isNameLongPressing = true;
                        nameLongPressExecuted = false;
                        
                        // 設置長按回調（2秒）
                        nameLongPressRunnable = () -> {
                            if (isNameLongPressing && !nameLongPressExecuted) {
                                nameLongPressExecuted = true;
                                // 執行長按操作（刪除車輛）
                                if (listener != null) {
                                    listener.onVehicleNameLongPress(car);
                                }
                            }
                        };
                        
                        nameeLongPressHandler.postDelayed(nameLongPressRunnable, 2000); // 2秒長按
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 結束長按檢測
                        if (isNameLongPressing) {
                            nameeLongPressHandler.removeCallbacks(nameLongPressRunnable);
                            
                            if (!nameLongPressExecuted) {
                                // 短按，執行編輯名稱
                                if (listener != null) {
                                    listener.onVehicleNameClick(car);
                                }
                            }
                            
                            isNameLongPressing = false;
                        }
                        return true;
                }
                return false;
            });
            
            // 更新鎖定狀態
            if (car.isLocked()) {
                ivLockStatus.setImageResource(R.drawable.locked);
            } else {
                ivLockStatus.setImageResource(R.drawable.unlocked);
            }
            
            // 更新前備箱狀態
            if (car.isFrunkOpen()) {
                ivFrontTrunkStatus.setImageResource(R.drawable.fopen);
            } else {
                ivFrontTrunkStatus.setImageResource(R.drawable.fclose);
            }
            
            // 更新後備箱狀態
            if (car.isTrunkOpen()) {
                ivRearTrunkStatus.setImageResource(R.drawable.bopen);
            } else {
                ivRearTrunkStatus.setImageResource(R.drawable.bclose);
            }
            
            // 更新連接狀態
            String displayStatus;
            int statusColor;
            
            if (car.isConnected()) {
                // 車輛已連接，顯示詳細狀態
                displayStatus = currentConnectionStatus;
                
                // 根據詳細狀態設置顏色
                switch (currentConnectionStatus) {
                    case "已就緒":
                        statusColor = 0xFF4CAF50; // 綠色
                        break;
                    case "連接中...":
                    case "建立Session...":
                        statusColor = 0xFFFF9800; // 橙色
                        break;
                    case "已連接":
                        statusColor = 0xFF2196F3; // 藍色
                        break;
                    case "連接錯誤":
                        statusColor = 0xFFF44336; // 紅色
                        break;
                    default:
                        statusColor = 0xFF4CAF50; // 預設綠色
                        break;
                }
            } else {
                // 車輛未連接
                displayStatus = "未連接";
                statusColor = 0xFFF44336; // 紅色
            }
            
            tvConnectionStatus.setText(displayStatus);
            tvConnectionStatus.setTextColor(statusColor);
            
            // 設置前備箱按鈕的長按處理
            btnFrontTrunk.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // 開始長按
                        isLongPressing = true;
                        longPressExecuted = false;
                        
                        // 立即顯示長按開始提示
                        int pos = getBindingAdapterPosition();
                        Car currentCar = (pos != RecyclerView.NO_POSITION) ? cars.get(pos) : car;
                        if (listener instanceof li.power.app.wearos.teslakeywearble.MainActivity) {
                            ((li.power.app.wearos.teslakeywearble.MainActivity) listener).onFrontTrunkLongPressStarted(currentCar);
                        }
                        
                        // 設置長按完成的回調
                        longPressRunnable = () -> {
                            if (isLongPressing && !longPressExecuted) {
                                longPressExecuted = true;
                                // 執行長按操作
                                int pos = getBindingAdapterPosition();
                                Car currentCar = (pos != RecyclerView.NO_POSITION) ? cars.get(pos) : car;
                                if (listener instanceof li.power.app.wearos.teslakeywearble.MainActivity) {
                                    ((li.power.app.wearos.teslakeywearble.MainActivity) listener).onFrontTrunkLongPress(currentCar);
                                }
                            }
                        };
                        
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 結束長按
                        if (isLongPressing) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                            
                            if (!longPressExecuted) {
                                // 長按未完成，顯示短按提示
                                if (listener != null) {
                                    int pos = getBindingAdapterPosition();
                                    Car currentCar = (pos != RecyclerView.NO_POSITION) ? cars.get(pos) : car;
                                    listener.onFrontTrunkClick(currentCar);
                                }
                            }
                            
                            isLongPressing = false;
                        }
                        return true;
                }
                return false;
            });
            
            btnLockUnlock.setOnClickListener(v -> {
                if (listener != null) {
                    int pos = getBindingAdapterPosition();
                    Car currentCar = (pos != RecyclerView.NO_POSITION) ? cars.get(pos) : car;
                    listener.onLockUnlockClick(currentCar);
                }
            });
            
            btnRearTrunk.setOnClickListener(v -> {
                if (listener != null) {
                    int pos = getBindingAdapterPosition();
                    Car currentCar = (pos != RecyclerView.NO_POSITION) ? cars.get(pos) : car;
                    listener.onRearTrunkClick(currentCar);
                }
            });
        }
    }
    
    class AddCarViewHolder extends RecyclerView.ViewHolder {
        private ImageView ivAddCar;
        
        public AddCarViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAddCar = itemView.findViewById(R.id.iv_add_car);
        }
        
        public void bind() {
            ivAddCar.setOnClickListener(v -> {
                Intent intent = new Intent(context, PairCarActivity.class);
                if (context instanceof Activity) {
                    ((Activity) context).startActivityForResult(intent, 1); // REQUEST_PAIR_CAR = 1
                } else {
                    context.startActivity(intent);
                }
            });
        }
    }
} 
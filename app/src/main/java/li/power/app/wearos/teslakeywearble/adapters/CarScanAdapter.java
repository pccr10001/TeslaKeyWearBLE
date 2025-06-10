package li.power.app.wearos.teslakeywearble.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import li.power.app.wearos.teslakeywearble.R;
import li.power.app.wearos.teslakeywearble.models.Car;
import java.util.List;

public class CarScanAdapter extends RecyclerView.Adapter<CarScanAdapter.ViewHolder> {
    
    private List<Car> cars;
    private OnCarClickListener listener;
    
    public interface OnCarClickListener {
        void onCarClick(Car car);
    }
    
    public CarScanAdapter(List<Car> cars, OnCarClickListener listener) {
        this.cars = cars;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bluetooth_car, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Car car = cars.get(position);
        holder.bind(car);
    }
    
    @Override
    public int getItemCount() {
        return cars.size();
    }
    
    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tvCarName;
        private TextView tvBluetoothAddress;
        private TextView tvSignalStrength;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCarName = itemView.findViewById(R.id.tv_car_name);
            tvBluetoothAddress = itemView.findViewById(R.id.tv_bluetooth_address);
            tvSignalStrength = itemView.findViewById(R.id.tv_signal_strength);
            
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onCarClick(cars.get(position));
                    }
                }
            });
        }
        
        public void bind(Car car) {
            tvCarName.setText(car.getName());
            tvBluetoothAddress.setText(car.getBleAddress());
            tvSignalStrength.setText(car.getSignalStrength() + "dBm");
        }
    }
} 
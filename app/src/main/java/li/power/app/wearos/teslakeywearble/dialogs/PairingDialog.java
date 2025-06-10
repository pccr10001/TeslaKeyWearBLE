package li.power.app.wearos.teslakeywearble.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import li.power.app.wearos.teslakeywearble.R;

public class PairingDialog extends Dialog {
    
    private TextView tvPairingStatus;
    private TextView tvCarName;
    private TextView tvInstructionText;
    private ProgressBar progressBar;
    private ImageView ivKeyCard;
    private Button btnCancel;
    
    private String carName;
    private String carAddress;
    private PairingDialogListener listener;
    
    public interface PairingDialogListener {
        void onPairingCancelled();
        void onPairingSuccess();
        void onPairingFailed(String error);
    }
    
    public PairingDialog(Context context, String carName, String carAddress, PairingDialogListener listener) {
        super(context);
        this.carName = carName;
        this.carAddress = carAddress;
        this.listener = listener;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_pairing);
        
        initViews();
        setupDialog();
    }
    
    private void initViews() {
        tvCarName = findViewById(R.id.tv_car_name);
        tvPairingStatus = findViewById(R.id.tv_pairing_status);
        tvInstructionText = findViewById(R.id.tv_instruction_text);
        progressBar = findViewById(R.id.progress_pairing);
        ivKeyCard = findViewById(R.id.iv_key_card);
        btnCancel = findViewById(R.id.btn_cancel);
        
        tvCarName.setText(carName);
        tvPairingStatus.setText("正在連接到車輛...");
        
        // 初始隱藏指示和鑰匙卡圖標
        tvInstructionText.setVisibility(View.GONE);
        ivKeyCard.setVisibility(View.GONE);
        
        btnCancel.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPairingCancelled();
            }
            dismiss();
        });
    }
    
    private void setupDialog() {
        setCancelable(true);  // 允許用戶點擊外部取消
        setCanceledOnTouchOutside(true);  // 允許點擊外部取消
        
        // 設置取消監聽器
        setOnCancelListener(dialog -> {
            if (listener != null) {
                listener.onPairingCancelled();
            }
        });
    }
    
    @Override
    public void onBackPressed() {
        // 用戶按返回鍵時也觸發取消
        if (listener != null) {
            listener.onPairingCancelled();
        }
        super.onBackPressed();
    }
    
    public void updateStatus(String status) {
        if (tvPairingStatus != null) {
            tvPairingStatus.setText(status);
        }
        
        // 根據不同狀態顯示不同提示
        if (status.contains("請在車輛讀卡器上點擊鑰匙卡")) {
            showKeyCardInstruction("請在車輛讀卡器上\n點擊鑰匙卡確認配對");
        } else if (status.contains("發送配對請求") || status.contains("等待車輛回應")) {
            showKeyCardInstruction("配對請求已發送\n等待車輛回應...");
        } else if (status.contains("鑰匙卡確認成功")) {
            hideKeyCardInstruction();
        }
    }
    
    private void showKeyCardInstruction(String instructionText) {
        if (tvInstructionText != null && ivKeyCard != null) {
            tvInstructionText.setText(instructionText);
            tvInstructionText.setVisibility(View.VISIBLE);
            ivKeyCard.setVisibility(View.VISIBLE);
        }
    }
    
    private void hideKeyCardInstruction() {
        if (tvInstructionText != null && ivKeyCard != null) {
            tvInstructionText.setVisibility(View.GONE);
            ivKeyCard.setVisibility(View.GONE);
        }
    }
    
    public void showProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
    }
    
    public void hideProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }
    
    public void showSuccess() {
        updateStatus("配對成功！");
        hideProgress();
        hideKeyCardInstruction();
        
        if (btnCancel != null) {
            btnCancel.setText("完成");
            btnCancel.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPairingSuccess();
                }
                dismiss();
            });
        }
    }
    
    public void showError(String error) {
        updateStatus("配對失敗：" + error);
        hideProgress();
        hideKeyCardInstruction();
        
        if (btnCancel != null) {
            btnCancel.setText("重試");
            btnCancel.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPairingFailed(error);
                }
                dismiss();
            });
        }
    }
} 
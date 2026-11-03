package cn.componentvault.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.common.InputImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 扫一扫只解码物料编号，不启动 OCR；商城负责提供商品参数。 */
public final class ScanActivity extends ComponentActivity {
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private Camera camera;
    private TextView status;
    private final BarcodeScanner scanner=BarcodeScanning.getClient();
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing=new AtomicBoolean();
    private volatile boolean finished,destroyed;
    private boolean closed,torch;
    private final ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{
        if(granted)startCamera();else failure("需要相机权限。也可返回首页手动输入编号查询商城。");
    });
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff19362f);root.setPadding(20,40,20,30);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){var bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());root.setPadding(20,bars.top+16,20,bars.bottom+16);}return insets;
        });
        TextView title=new TextView(this);title.setText("扫一扫 · 立创物料二维码");title.setTextSize(23);title.setTextColor(-1);root.addView(title);
        preview=new PreviewView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        preview.setOnTouchListener((v,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_UP && camera!=null){var point=preview.getMeteringPointFactory().createPoint(event.getX(),event.getY());camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(point).build());v.performClick();}return true;
        });
        status=new TextView(this);status.setText("对准一张标签的二维码，点击画面对焦。\n读到完整物料编号后自动查询商城，参数就绪直接进入录入页。\n本入口不使用文字识别；拍照识别请从首页单独进入。");status.setTextColor(-1);status.setTextSize(14);status.setGravity(Gravity.CENTER);status.setPadding(10,24,10,24);root.addView(status);
        LinearLayout actions=new LinearLayout(this);
        Button back=new Button(this);back.setText("返回");back.setOnClickListener(v->finish());actions.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        Button light=new Button(this);light.setText("补光");light.setOnClickListener(v->{if(camera!=null && camera.getCameraInfo().hasFlashUnit()){torch=!torch;camera.getCameraControl().enableTorch(torch);}});actions.addView(light,new LinearLayout.LayoutParams(0,-2,1));
        Button zoom=new Button(this);zoom.setText("变焦");zoom.setOnClickListener(v->{if(camera==null)return;var z=camera.getCameraInfo().getZoomState().getValue();if(z!=null)camera.getCameraControl().setZoomRatio(z.getZoomRatio()>1.5f?1f:Math.min(2f,z.getMaxZoomRatio()));});actions.addView(zoom,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else permission.launch(Manifest.permission.CAMERA);
    }
    @android.annotation.SuppressLint("UnsafeOptInUsageError")
    private void startCamera(){
        var future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            if(isFinishing()||isDestroyed())return;
            try{
                provider=future.get();Preview live=new Preview.Builder().build();live.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new android.util.Size(1920,1080)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor,frame->{
                    if(finished||destroyed||!processing.compareAndSet(false,true)){frame.close();return;}
                    var media=frame.getImage();if(media==null){frame.close();processing.set(false);return;}
                    try{
                        scanner.process(InputImage.fromMediaImage(media,frame.getImageInfo().getRotationDegrees())).addOnSuccessListener(codes->{
                            if(finished||destroyed||isFinishing())return;
                            List<String> raw=new ArrayList<>();for(var code:codes)if(code.getRawValue()!=null)raw.add(code.getRawValue());
                            String selected=QrPayload.uniqueProductPayload(raw);
                            if(selected!=null){finished=true;setResult(RESULT_OK,new Intent().putExtra("qr",selected));finish();}
                            else if(!raw.isEmpty())status.setText("未取得唯一的完整立创编号。请一次只扫描一张二维码；二维码需包含 C 开头的商品编号。其他标签可从首页拍照或手动录入。");
                        }).addOnFailureListener(e->{if(!destroyed)status.setText("二维码暂未读到，请调整距离、对焦后重试。");}).addOnCompleteListener(task->{frame.close();processing.set(false);closeIfDestroyed();});
                    }catch(Exception e){frame.close();processing.set(false);}
                });
                provider.unbindAll();camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,live,analysis);
            }catch(Exception e){failure("相机启动失败："+e.getLocalizedMessage());}
        },ContextCompat.getMainExecutor(this));
    }
    private void failure(String text){if(!isFinishing())new AlertDialog.Builder(this).setTitle("扫码不可用").setMessage(text).setPositiveButton("返回",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
    private void closeIfDestroyed(){if(destroyed&&!processing.get()&&!closed){closed=true;scanner.close();}}
    @Override protected void onDestroy(){destroyed=true;finished=true;if(provider!=null)provider.unbindAll();executor.shutdown();closeIfDestroyed();super.onDestroy();}
}

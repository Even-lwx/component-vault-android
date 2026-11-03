package cn.componentvault.app;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 原图和温和增强图分别识别，保留分歧；二维码编号可用时交给商城查询而不追加 OCR 猜测。 */
public final class PhotoRecognizer {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    public static final class Result {
        public final List<String> texts=new ArrayList<>(),codes=new ArrayList<>();
        public String processing="原图识别";
    }
    public interface Callback { void success(Result result); void failure(Exception error); }
    private PhotoRecognizer() {}

    public static void recognize(Context context,Uri uri,Callback callback) {
        Context app=context.getApplicationContext();
        Tasks.call(WORKER,()->load(app,uri)).addOnSuccessListener(bitmap->run(bitmap,callback)).addOnFailureListener(callback::failure);
    }

    private static Bitmap load(Context context,Uri uri) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) { if(in==null)throw new IOException("无法读取照片"); BitmapFactory.decodeStream(in,null,bounds); }
        if(bounds.outWidth<=0 || bounds.outHeight<=0)throw new IOException("图片格式不支持或文件已损坏");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2800)options.inSampleSize*=2;
        Bitmap bitmap;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) { bitmap=BitmapFactory.decodeStream(in,null,options); }
        if(bitmap==null)throw new IOException("无法解码照片");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in!=null)orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        }catch(IOException ignored){}
        Matrix matrix=new Matrix();
        switch(orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.postRotate(90);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.postRotate(180);break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.postRotate(270);break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
        }
        if(!matrix.isIdentity()) { Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();bitmap=rotated; }
        return bitmap;
    }

    private static void run(Bitmap original,Callback callback) {
        TextRecognizer chinese=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        TextRecognizer latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        BarcodeScanner barcode=BarcodeScanning.getClient(); Result result=new Result();
        InputImage image=InputImage.fromBitmap(original,0);
        var ch=chinese.process(image);var en=latin.process(image);var qr=barcode.process(image);
        Tasks.whenAllComplete(ch,en,qr).addOnCompleteListener(done->{
            if(en.isSuccessful())result.texts.add(en.getResult().getText());
            if(ch.isSuccessful())result.texts.add(ch.getResult().getText());
            if(qr.isSuccessful())for(var code:qr.getResult())if(code.getRawValue()!=null)result.codes.add(code.getRawValue());
            boolean knownQr=result.codes.stream().anyMatch(code->LcscProductParser.validCode(QrPayload.parse(code,Collections.emptyList()).code));
            if(knownQr) {
                result.processing="已读取二维码编号，优先使用商城参数校验";
                close(original,null,chinese,latin,barcode);callback.success(result);return;
            }
            Rect region=new Rect();
            for(var task:Arrays.asList(ch,en))if(task.isSuccessful())for(var block:task.getResult().getTextBlocks()) {
                Rect box=block.getBoundingBox();if(box!=null)region.union(box);
            }
            Rect crop=region;
            Tasks.call(WORKER,()->enhance(original,crop)).addOnSuccessListener(enhanced->{
                InputImage second=InputImage.fromBitmap(enhanced,0);
                var ch2=chinese.process(second);var en2=latin.process(second);var qr2=barcode.process(second);
                Tasks.whenAllComplete(ch2,en2,qr2).addOnCompleteListener(end->{
                    if(en2.isSuccessful())result.texts.add(en2.getResult().getText());
                    if(ch2.isSuccessful())result.texts.add(ch2.getResult().getText());
                    if(qr2.isSuccessful())for(var code:qr2.getResult())if(code.getRawValue()!=null && !result.codes.contains(code.getRawValue()))result.codes.add(code.getRawValue());
                    result.processing="原图 + 文字区域裁剪/放大 + 温和灰度对比增强；所有分歧均保留";
                    boolean successful=ch.isSuccessful()||en.isSuccessful()||qr.isSuccessful()||ch2.isSuccessful()||en2.isSuccessful()||qr2.isSuccessful();
                    close(original,enhanced,chinese,latin,barcode);
                    if(successful)callback.success(result);else callback.failure(new IOException("识别器暂不可用，请重试或手动填写"));
                });
            }).addOnFailureListener(error->{
                result.processing="增强处理不可用，保留原图结果";close(original,null,chinese,latin,barcode);callback.success(result);
            });
        });
    }

    private static Bitmap enhance(Bitmap original,Rect detected) {
        Rect bounds=new Rect(0,0,original.getWidth(),original.getHeight());
        if(!detected.isEmpty()) {
            int margin=Math.max(32,Math.min(original.getWidth(),original.getHeight())/30);
            Rect expanded=new Rect(detected.left-margin,detected.top-margin,detected.right+margin,detected.bottom+margin);
            if(expanded.intersect(bounds) && expanded.width()>=100 && expanded.height()>=60)bounds=expanded;
        }
        float scale=Math.min(2f,2600f/Math.max(bounds.width(),bounds.height()));
        int width=Math.max(1,Math.round(bounds.width()*scale)),height=Math.max(1,Math.round(bounds.height()*scale));
        Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(out);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        ColorMatrix gray=new ColorMatrix();gray.setSaturation(0);
        float contrast=1.12f,bias=128*(1-contrast);
        ColorMatrix adjust=new ColorMatrix(new float[]{contrast,0,0,0,bias,0,contrast,0,0,bias,0,0,contrast,0,bias,0,0,0,1,0});
        gray.postConcat(adjust);paint.setColorFilter(new ColorMatrixColorFilter(gray));
        canvas.drawBitmap(original,bounds,new Rect(0,0,width,height),paint);return out;
    }
    private static void close(Bitmap a,Bitmap b,TextRecognizer ch,TextRecognizer en,BarcodeScanner qr) {
        ch.close();en.close();qr.close();if(b!=null && b!=a)b.recycle();a.recycle();
    }
}

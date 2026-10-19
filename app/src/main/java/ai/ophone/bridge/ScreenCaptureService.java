package ai.ophone.bridge;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-consented, bounded, video-only screen recording foreground service. */
public final class ScreenCaptureService extends Service {
    private static final String CHANNEL="ophoneai_screen_record";static final String ACTION_STOP="ai.ophone.bridge.STOP_SCREEN_RECORD";
    private final Handler handler=new Handler(Looper.getMainLooper());private final AtomicBoolean finishing=new AtomicBoolean();
    private MediaProjection projection;private VirtualDisplay display;private MediaRecorder recorder;private String recordingId;private long startedAt;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        startForeground(3902,notification());
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_STOP.equals(intent.getAction())){if(recordingId==null)recordingId=intent.getStringExtra("recording_id");finish(false,"user_cancelled: "+intent.getStringExtra("cancel_reason"));return START_NOT_STICKY;}
        if(recorder!=null)return START_NOT_STICKY;recordingId=intent.getStringExtra("recording_id");
        Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("result_data",Intent.class):(Intent)intent.getParcelableExtra("result_data");
        int result=intent.getIntExtra("result_code",Activity.RESULT_CANCELED);
        if(recordingId==null||data==null||result!=Activity.RESULT_OK){fail("invalid_media_projection_consent");return START_NOT_STICKY;}
        try{prepareCapture(result,data);}catch(Exception error){fail(error.getClass().getSimpleName()+": "+error.getMessage());}
        return START_NOT_STICKY;
    }
    private void prepareCapture(int result,Intent data)throws Exception{
        JSONObject receipt=ScreenRecordingStore.internal(this,recordingId);MediaProjectionManager manager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);projection=manager.getMediaProjection(result,data);if(projection==null)throw new IllegalStateException("media_projection_unavailable");
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){if(!finishing.get())handler.post(()->finish(false,"media_projection_stopped"));}},handler);
        long delay=Math.max(0,receipt.optLong("start_at_epoch_ms",System.currentTimeMillis())-System.currentTimeMillis());
        if(delay>0){ScreenRecordingStore.markScheduled(this,recordingId);handler.postDelayed(()->{try{startCapture();}catch(Exception error){fail(error.getClass().getSimpleName()+": "+error.getMessage());}},delay);}else startCapture();
    }
    private void startCapture()throws Exception{
        JSONObject receipt=ScreenRecordingStore.internal(this,recordingId);File output=new File(receipt.getString("output_path"));File parent=output.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IllegalStateException("recording_directory_unavailable");
        DisplayMetrics metrics=new DisplayMetrics();((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);int rawW=metrics.widthPixels,rawH=metrics.heightPixels;double scale=Math.min(1d,1920d/Math.max(rawW,rawH));int width=Math.max(2,((int)Math.round(rawW*scale))&~1),height=Math.max(2,((int)Math.round(rawH*scale))&~1);
        recorder=new MediaRecorder();recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setOutputFile(output.getAbsolutePath());recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);recorder.setVideoEncodingBitRate(6000000);recorder.setVideoFrameRate(30);recorder.setVideoSize(width,height);recorder.prepare();
        display=projection.createVirtualDisplay("OPhoneAI-recording",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,handler);
        recorder.start();startedAt=System.currentTimeMillis();ScreenRecordingStore.markRecording(this,recordingId,width,height);
        handler.postDelayed(()->finish(true,""),receipt.getLong("duration_ms"));
    }
    private void finish(boolean scheduled,String reason){
        if(!finishing.compareAndSet(false,true))return;handler.removeCallbacksAndMessages(null);boolean stopped=false;
        try{if(recorder!=null){recorder.stop();stopped=true;}}catch(Exception ignored){}
        try{if(display!=null)display.release();}catch(Exception ignored){}try{if(recorder!=null)recorder.release();}catch(Exception ignored){}try{if(projection!=null)projection.stop();}catch(Exception ignored){}
        long elapsed=startedAt==0?0:System.currentTimeMillis()-startedAt;
        try{if(scheduled&&stopped)ScreenRecordingStore.markCompleted(this,recordingId,elapsed);else if(reason.startsWith("user_cancelled:"))ScreenRecordingStore.markCancelled(this,recordingId,reason.substring("user_cancelled:".length()).trim());else ScreenRecordingStore.markFailed(this,recordingId,reason.isEmpty()?"recording_stop_failed":reason);}catch(Exception error){ScreenRecordingStore.markFailed(this,recordingId,error.getClass().getSimpleName()+": "+error.getMessage());}
        stopForeground(true);stopSelf();
    }
    private void fail(String reason){if(recordingId!=null)ScreenRecordingStore.markFailed(this,recordingId,reason);stopForeground(true);stopSelf();}
    private Notification notification(){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CHANNEL,getString(R.string.screen_record_channel),NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,PlatformActivity.class);PendingIntent content=PendingIntent.getActivity(this,3902,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(getString(R.string.screen_record_notification_title)).setContentText(getString(R.string.screen_record_notification_text)).setOngoing(true).setContentIntent(content).build();
    }
    @Override public void onDestroy(){if((recorder!=null||projection!=null)&&!finishing.get())finish(false,"screen_capture_service_destroyed");super.onDestroy();}
}

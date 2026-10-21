package ai.ophone.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** Minimal transparent host for Android's mandatory MediaProjection confirmation. */
public final class ScreenCaptureActivity extends Activity {
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(base); UiKit.applyTheme(this); }
    private static final int REQUEST_CAPTURE=3901;
    private String recordingId;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);recordingId=getIntent().getStringExtra("recording_id");
        if(recordingId==null){finish();return;}
        if(state==null){
            MediaProjectionManager manager=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if(manager==null){ScreenRecordingStore.markFailed(this,recordingId,"media_projection_unavailable");finish();return;}
            startActivityForResult(manager.createScreenCaptureIntent(),REQUEST_CAPTURE);
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=REQUEST_CAPTURE)return;
        try{if(!"awaiting_user_consent".equals(ScreenRecordingStore.status(this,recordingId).optString("state"))){finish();return;}}catch(Exception ignored){}
        if(result!=RESULT_OK||data==null){ScreenRecordingStore.markFailed(this,recordingId,"screen_capture_permission_denied");finish();return;}
        Intent service=new Intent(this,ScreenCaptureService.class).putExtra("recording_id",recordingId)
                .putExtra("result_code",result).putExtra("result_data",data);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
        catch(Exception error){ScreenRecordingStore.markFailed(this,recordingId,"screen_capture_service_failed: "+error.getMessage());}
        finish();overridePendingTransition(0,0);
    }
}

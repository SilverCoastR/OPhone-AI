package ai.ophone.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Sent by Android only to this app after it was updated: removes the kept installer and confirms the new version. */
public final class UpdateInstalledReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        PendingResult result=goAsync();Context c=context.getApplicationContext();
        new Thread(()->{try{UpdateManager.packageReplaced(c);ScheduleStore.arm(c);}finally{result.finish();}},"OPhoneAI-updated").start();
    }
}

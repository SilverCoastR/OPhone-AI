package ai.ophone.workbench;

import java.io.File;

/** A privileged watchdog stays in the job group: an app UID cannot directly kill root descendants. */
public final class RootJobScript {
    private RootJobScript(){}
    public static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    public static String script(String command,File policy,String generation,File lease,File identity,File exit,File reason,File cwd,long deadlineSeconds,int requiredUid){
        String permitted="[ -f "+quote(lease.getAbsolutePath())+" ] && /system/bin/grep -q "+quote("\"enabled\":true")+" "+quote(policy.getAbsolutePath())+" && /system/bin/grep -q "+quote("\"generation\":\""+generation+"\"")+" "+quote(policy.getAbsolutePath());
        String groupKill="/system/bin/kill -KILL -- -\"$group\"";
        String inner="umask 022; group=$$; printf '%s %s\\n' \"$group\" \"$(/system/bin/id -u)\" > "+quote(identity.getAbsolutePath())+"; "
            +"( while :; do if ! ( "+permitted+" ); then printf '%s' revoked > "+quote(reason.getAbsolutePath())+"; "+groupKill+"; exit; fi; "
            +"if [ \"$(/system/bin/date +%s)\" -ge "+deadlineSeconds+" ]; then printf '%s' timeout > "+quote(reason.getAbsolutePath())+"; "+groupKill+"; exit; fi; /system/bin/sleep 0.2; done ) </dev/null & "
            +"cd "+quote(cwd.getAbsolutePath())+" || exit 125; /system/bin/sh -c "+quote(command)+"; code=$?; printf '%s' \"$code\" > "+quote(exit.getAbsolutePath())+"; "+groupKill+"; exit 125";
        return "[ \"$(/system/bin/id -u)\" = "+requiredUid+" ] || { echo root_uid_verification_failed >&2; exit 126; }; "
            +"( "+permitted+" ) || { echo root_policy_revoked >&2; exit 126; }; exec /system/bin/setsid -w /system/bin/sh -c "+quote(inner);
    }
}

package ru.smotrim.tv;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONObject;

/** Local diagnostics only: no stack traces, titles or URLs are collected or sent. */
final class PlaybackHealth {
    static void rendererGone(Context context,boolean crashed) {
        context.getSharedPreferences("playback-health",0).edit().putLong("rendererTime",System.currentTimeMillis())
            .putString("rendererReason",crashed?"Сбой интерфейса WebView":"Android остановил интерфейс WebView. Возможна нехватка памяти").apply();
    }
    static JSONObject snapshot(Context context) throws Exception {
        JSONObject result=new JSONObject();
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);
            result.put("totalMb",info.totalMem/TorrentBufferPolicy.MIB).put("availableMb",info.availMem/TorrentBufferPolicy.MIB).put("lowMemory",info.lowMemory);}
        Runtime runtime=Runtime.getRuntime();
        result.put("heapMb",(runtime.totalMemory()-runtime.freeMemory())/TorrentBufferPolicy.MIB)
            .put("heapLimitMb",runtime.maxMemory()/TorrentBufferPolicy.MIB).put("playerTargetMb",PlaybackLoadControl.TARGET_BYTES/TorrentBufferPolicy.MIB);
        SharedPreferences prefs=context.getSharedPreferences("playback-health",0);
        long lastTime=prefs.getLong("rendererTime",0);String reason=prefs.getString("rendererReason","");
        if(manager!=null&&Build.VERSION.SDK_INT>=30)try{
            for(ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(context.getPackageName(),0,12)){
                if(context.getPackageName().equals(exit.getProcessName())&&exit.getTimestamp()>lastTime){lastTime=exit.getTimestamp();reason=exitReason(exit.getReason());}
            }
        }catch(SecurityException|UnsupportedOperationException ignored){}
        return result.put("exitTime",lastTime).put("exitReason",reason);
    }
    static String exitReason(int reason) {
        return switch(reason){
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "Android завершил приложение из-за нехватки оперативной памяти";
            case ApplicationExitInfo.REASON_CRASH -> "Ошибка приложения";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "Сбой нативного компонента";
            case ApplicationExitInfo.REASON_ANR -> "Приложение перестало отвечать";
            case ApplicationExitInfo.REASON_USER_REQUESTED,ApplicationExitInfo.REASON_USER_STOPPED -> "Остановка пользователем или системой управления приложениями";
            case ApplicationExitInfo.REASON_EXIT_SELF -> "Приложение завершило работу";
            default -> "Android завершил процесс; причина не уточнена (код "+reason+")";
        };
    }
}

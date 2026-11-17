package com.peerlink.app.bot;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;

/** Test-only child process. It never loads a game or opens a network connection. */
public class EngineExitProbeService extends Service {
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) {
            if (message.what == 3) {
                System.loadLibrary("peerlinkpatchtests");
                nativeCrash();
                return;
            }
            if (message.what == 2) {
                Process.killProcess(Process.myPid());
                return;
            }
            try {
                Message reply = Message.obtain(null, 1);
                reply.arg1 = Process.myPid();
                message.replyTo.send(reply);
            } catch (RemoteException error) { throw new RuntimeException(error); }
        }
    });
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    private static native void nativeCrash();
}

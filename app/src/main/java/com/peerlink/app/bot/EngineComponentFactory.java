package com.peerlink.app.bot;
import android.app.Activity;
import android.content.Intent;
import androidx.core.app.CoreComponentFactory;

/** Android creates and attaches the Activity; this selects its installed-game class loader. */
public final class EngineComponentFactory extends CoreComponentFactory {
    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (!name.equals("com.peerlink.engine.PhoneGameHost")) return super.instantiateActivity(loader, name, intent);
        try { return (Activity)GameAndroidRuntime.gameLoader().loadClass(name).getConstructor().newInstance(); }
        catch (ReflectiveOperationException error) {
            InstantiationException failed = new InstantiationException("Bot Android host could not be created");
            failed.initCause(error); throw failed;
        }
    }
}

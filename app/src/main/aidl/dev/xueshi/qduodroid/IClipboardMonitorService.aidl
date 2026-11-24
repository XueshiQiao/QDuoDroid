package dev.xueshi.qduodroid;

import dev.xueshi.qduodroid.IClipboardCallback;

interface IClipboardMonitorService {
    void destroy();
    void startWatching(IClipboardCallback callback, boolean enableFreezeWakeup);
    void stopWatching();
    String getCurrentClipText();
    String getDiagnostics();
    String consumePendingClipText();
    String applyKeepAliveExemptions(String packageName);
    String grantAccessibilityWritePermissions(String packageName);
    void triggerTestWake();
}

package dev.xueshi.qduodroid;

interface IClipboardCallback {
    void onClipChanged(String text, long timestamp);
    oneway void onStatusChanged(String status);
    oneway void onDebugLog(String message);
}

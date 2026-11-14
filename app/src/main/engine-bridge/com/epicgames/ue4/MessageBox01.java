package com.epicgames.ue4;

/** Headless error-reporting port. Never supplies a successful dialog/button response. */
public final class MessageBox01 {
    private String caption = "", text = "";
    public MessageBox01() {}
    public void setCaption(String value) { caption = value; }
    public void setText(String value) { text = value; }
    public void addButton(String value) { /* No screen or synthetic button response. */ }
    public void clear() { caption = ""; text = ""; }
    public int show() {
        String message = caption + ": " + text;
        android.util.Log.e("PeerlinkStartupError", message);
        throw new IllegalStateException("Bot engine dialog: " + message);
    }
}

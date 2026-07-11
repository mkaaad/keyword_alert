#!/system/bin/sh

ui_print "=== Keyword Alert ==="
ui_print "  version: v2.0"

APK_PATH="$MODPATH/system/priv-app/KeywordAlert/KeywordAlert.apk"
if [ -f "$APK_PATH" ]; then
    SIZE=$(wc -c < "$APK_PATH" 2>/dev/null || echo 0)
    if [ "$SIZE" -lt 1000000 ]; then
        ui_print "  WARNING: APK too small (${SIZE} bytes)"
    else
        ui_print "  APK ready ($((SIZE/1048576)) MB)"
    fi
else
    ui_print "  ERROR: APK not found!"
    ui_print "  MODPATH=$MODPATH"
    ls -la "$MODPATH/system/priv-app/KeywordAlert/" 2>/dev/null || ls -la "$MODPATH/"
    abort
fi

ui_print "  Done."

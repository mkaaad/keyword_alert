#!/system/bin/sh
# Inject REMOTE_SUBMIX into audio policy at boot (best-effort).

MODDIR=${0%/*}
LOG=/cache/keyword_alert.log

log() {
    echo "[KeywordAlert] $1" >> "$LOG" 2>/dev/null
}

AUDIO_POLICY=""
for c in \
    /vendor/etc/audio_policy_configuration.xml \
    /vendor/etc/audio/audio_policy_configuration.xml \
    /odm/etc/audio_policy_configuration.xml \
    /system/etc/audio_policy_configuration.xml
do
    if [ -f "$c" ]; then
        AUDIO_POLICY="$c"
        break
    fi
done

if [ -z "$AUDIO_POLICY" ]; then
    log "audio_policy_configuration.xml not found"
    exit 0
fi

if grep -q 'r_submix\|REMOTE_SUBMIX\|Remote Submix' "$AUDIO_POLICY" 2>/dev/null; then
    log "r_submix already present in $AUDIO_POLICY"
    exit 0
fi

log "Injecting r_submix into $AUDIO_POLICY"

R_SUBMIX_FOUND=false
for hal in \
    /vendor/lib/hw/audio.r_submix.default.so \
    /vendor/lib64/hw/audio.r_submix.default.so \
    /system/lib/hw/audio.r_submix.default.so \
    /system/lib64/hw/audio.r_submix.default.so
do
    if [ -f "$hal" ]; then
        R_SUBMIX_FOUND=true
        break
    fi
done

MODULE_BLOCK='    <module name="r_submix" halVersion="2.0">
        <attachedDevices><item>Remote Submix Out</item></attachedDevices>
        <mixPort name="r_submix output" role="source" flags="AUDIO_OUTPUT_FLAG_DIRECT">
            <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
        </mixPort>
        <devicePort tagName="Remote Submix Out" type="AUDIO_DEVICE_OUT_REMOTE_SUBMIX" role="sink">
            <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>
        </devicePort>
        <devicePort tagName="Remote Submix In" type="AUDIO_DEVICE_IN_REMOTE_SUBMIX" role="source">
            <profile name="" format="AUDIO_FORMAT_PCM_16_BIT" samplingRates="16000" channelMasks="AUDIO_CHANNEL_IN_MONO"/>
        </devicePort>
        <route type="mix" sink="Remote Submix In" sources="r_submix output"/>
    </module>'

TMP="${AUDIO_POLICY}.keyword_alert.tmp"
if [ "$R_SUBMIX_FOUND" = true ]; then
    # Insert module block just before </modules>
    if command -v awk >/dev/null 2>&1; then
        awk -v block="$MODULE_BLOCK" '{if(/<\/modules>/){print block} print}' "$AUDIO_POLICY" > "$TMP" 2>/dev/null \
            && cat "$TMP" > "$AUDIO_POLICY" 2>/dev/null \
            && chmod 644 "$AUDIO_POLICY" 2>/dev/null \
            && log "Injected module into $AUDIO_POLICY" \
            || log "Failed to patch $AUDIO_POLICY"
        rm -f "$TMP" 2>/dev/null
    else
        log "awk not available, skip inject"
    fi
else
    log "audio.r_submix HAL not found; device may lack REMOTE_SUBMIX support"
fi

# Ensure audio is not forced silent
resetprop ro.audio.silent 0 2>/dev/null

exit 0

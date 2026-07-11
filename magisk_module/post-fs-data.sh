#!/system/bin/sh
# Inject REMOTE_SUBMIX at boot

MODDIR=${0%/*}

AUDIO_POLICY=""
for c in /vendor/etc/audio_policy_configuration.xml /vendor/etc/audio/audio_policy_configuration.xml /odm/etc/audio_policy_configuration.xml; do
    [ -f "$c" ] && AUDIO_POLICY="$c" && break
done

[ -z "$AUDIO_POLICY" ] && return

grep -q 'r_submix' "$AUDIO_POLICY" && return

echo "[KeywordAlert] Injecting r_submix into $AUDIO_POLICY" >> /cache/keyword_alert.log

R_SUBMIX_FOUND=false
for hal in /vendor/lib/hw/audio.r_submix.default.so /vendor/lib64/hw/audio.r_submix.default.so /system/lib/hw/audio.r_submix.default.so /system/lib64/hw/audio.r_submix.default.so; do
    [ -f "$hal" ] && R_SUBMIX_FOUND=true && break
done

if [ "$R_SUBMIX_FOUND" = false ]; then
    mkdir -p /vendor/etc/audio 2>/dev/null
    cat > /vendor/etc/audio/audio_policy_configuration_r_submix.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<audioPolicyConfiguration version="1.0">
    <globalConfiguration speaker_drc_enabled="false"/>
    <modules>
        <module name="r_submix" halVersion="2.0">
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
        </module>
    </modules>
</audioPolicyConfiguration>
EOF
    chmod 644 /vendor/etc/audio/audio_policy_configuration_r_submix.xml
else
    TMP="$AUDIO_POLICY.tmp"
    awk -v block='    <module name="r_submix" halVersion="2.0">
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
    </module>' '{if(/<\/modules>/){print block}print}' "$AUDIO_POLICY" > "$TMP"
    mv "$TMP" "$AUDIO_POLICY"
    chmod 644 "$AUDIO_POLICY"
fi

resetprop ro.audio.silent 0 2>/dev/null

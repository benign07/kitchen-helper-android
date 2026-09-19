package kr.co.kitchenhelper.inspector;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Generates a short two-pitch ding-dong twice on the alarm audio stream. */
public final class DingDongPlayer {
    private static final int SAMPLE_RATE = 44_100;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack track;

    public void play() {
        stop();
        short[] samples = createSamples();
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(samples, 0, samples.length);
            track.setVolume(1f);
            track.play();
            handler.postDelayed(this::stop, samples.length * 1000L / SAMPLE_RATE + 250L);
        } catch (RuntimeException error) {
            stop();
        }
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        AudioTrack old = track;
        track = null;
        if (old == null) return;
        try {
            old.stop();
        } catch (IllegalStateException ignored) {
            // It may already have naturally completed.
        }
        old.release();
    }

    private static short[] createSamples() {
        double[] frequencies = {1046.5, 0, 784.0, 0, 1046.5, 0, 784.0};
        double[] durations = {0.26, 0.07, 0.42, 0.20, 0.26, 0.07, 0.42};
        int total = 0;
        for (double duration : durations) total += (int) (duration * SAMPLE_RATE);
        short[] output = new short[total];
        int offset = 0;
        for (int note = 0; note < frequencies.length; note++) {
            int count = (int) (durations[note] * SAMPLE_RATE);
            double frequency = frequencies[note];
            for (int i = 0; i < count; i++) {
                if (frequency == 0) {
                    output[offset + i] = 0;
                    continue;
                }
                double time = i / (double) SAMPLE_RATE;
                double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.012));
                double decay = Math.exp(-3.2 * i / count);
                double wave = Math.sin(2 * Math.PI * frequency * time)
                        + 0.22 * Math.sin(2 * Math.PI * frequency * 2 * time);
                output[offset + i] = (short) (Short.MAX_VALUE * 0.72 * attack * decay * wave);
            }
            offset += count;
        }
        return output;
    }
}

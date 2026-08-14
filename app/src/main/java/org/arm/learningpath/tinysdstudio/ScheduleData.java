package org.arm.learningpath.tinysdstudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class ScheduleData {
    final int numSteps;
    final float initNoiseSigma;
    final long[] timesteps;
    final float[] sigmaCurrent;
    final float[] sigmaPrevious;
    final float[] alphaCurrentSqrt;
    final float[] alphaPreviousSqrt;
    final float[] phi1;
    final float[] r0;
    final float[] secondOrderWeight;

    private ScheduleData(JSONObject root) throws Exception {
        numSteps = root.getInt("num_steps");
        initNoiseSigma = (float) root.getDouble("init_noise_sigma");
        timesteps = readLongArray(root.getJSONArray("timesteps"));
        sigmaCurrent = readFloatArray(root.getJSONArray("sigma_cur"));
        sigmaPrevious = readFloatArray(root.getJSONArray("sigma_prev"));
        alphaCurrentSqrt = readFloatArray(root.getJSONArray("alpha_cur_sqrt"));
        alphaPreviousSqrt = readFloatArray(root.getJSONArray("alpha_prev_sqrt"));
        phi1 = readFloatArray(root.getJSONArray("phi1"));
        r0 = readFloatArray(root.getJSONArray("r0"));
        secondOrderWeight = readFloatArray(root.getJSONArray("second_order_w"));
        validateLengths();
    }

    static ScheduleData load(File file) throws Exception {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return new ScheduleData(new JSONObject(json));
    }

    private void validateLengths() {
        if (timesteps.length != numSteps
                || sigmaCurrent.length != numSteps
                || sigmaPrevious.length != numSteps
                || alphaCurrentSqrt.length != numSteps
                || alphaPreviousSqrt.length != numSteps
                || phi1.length != numSteps
                || r0.length != numSteps
                || secondOrderWeight.length != numSteps) {
            throw new IllegalArgumentException("Schedule arrays do not match num_steps");
        }
    }

    private static long[] readLongArray(JSONArray array) throws Exception {
        long[] values = new long[array.length()];
        for (int index = 0; index < array.length(); index++) {
            values[index] = array.getLong(index);
        }
        return values;
    }

    private static float[] readFloatArray(JSONArray array) throws Exception {
        float[] values = new float[array.length()];
        for (int index = 0; index < array.length(); index++) {
            values[index] = (float) array.getDouble(index);
        }
        return values;
    }
}

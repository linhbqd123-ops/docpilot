package io.docpilot.mcp.personalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HashingEmbeddingProvider implements EmbeddingProvider {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final int dimensions;

    HashingEmbeddingProvider(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String name() {
        return "hashing";
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            vectors.add(embedSingle(input));
        }
        return vectors;
    }

    private float[] embedSingle(String input) {
        float[] vector = new float[dimensions];
        String normalised = DocumentTextSupport.normaliseWhitespace(input).toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN_PATTERN.matcher(normalised);
        int tokenCount = 0;
        while (matcher.find()) {
            String token = matcher.group();
            tokenCount++;
            accumulate(vector, token.hashCode(), 1.0f);
            accumulate(vector, Integer.rotateLeft(token.hashCode(), 11) ^ token.length(), 0.5f);
        }

        if (tokenCount == 0 && !normalised.isBlank()) {
            accumulate(vector, normalised.hashCode(), 1.0f);
        }

        normalise(vector);
        return vector;
    }

    private void accumulate(float[] vector, int hash, float weight) {
        int index = Math.floorMod(hash, dimensions);
        float sign = ((hash >>> 1) & 1) == 0 ? 1.0f : -1.0f;
        vector[index] += weight * sign;
    }

    private void normalise(float[] vector) {
        double magnitude = 0.0d;
        for (float value : vector) {
            magnitude += value * value;
        }

        if (magnitude == 0.0d) {
            vector[0] = 1.0f;
            return;
        }

        float scale = (float) (1.0d / Math.sqrt(magnitude));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
    }
}
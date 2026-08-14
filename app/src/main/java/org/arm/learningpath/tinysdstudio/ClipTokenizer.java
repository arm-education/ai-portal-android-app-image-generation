package org.arm.learningpath.tinysdstudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClipTokenizer {
    private static final int BOS_TOKEN_ID = 49406;
    private static final int EOS_TOKEN_ID = 49407;
    private static final int MAX_TOKEN_COUNT = 77;
    private static final String BOS_TOKEN = "<|startoftext|>";
    private static final String EOS_TOKEN = "<|endoftext|>";
    private static final String END_OF_WORD = "</w>";
    private static final char PAIR_SEPARATOR = '\u0000';
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|"
                    + "[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+"
    );

    private final Map<String, Integer> vocabulary;
    private final Map<String, Integer> mergeRanks;
    private final Map<Integer, String> byteEncoder;
    private final Map<String, List<String>> bpeCache = new HashMap<>();

    ClipTokenizer(File tokenizerFile) throws Exception {
        String json = new String(
                Files.readAllBytes(tokenizerFile.toPath()),
                StandardCharsets.UTF_8
        );
        JSONObject model = new JSONObject(json).getJSONObject("model");
        vocabulary = readVocabulary(model.getJSONObject("vocab"));
        mergeRanks = readMergeRanks(model.getJSONArray("merges"));
        byteEncoder = createByteEncoder();
    }

    long[] encode(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").toLowerCase(Locale.ROOT);

        List<Long> tokenIds = new ArrayList<>(MAX_TOKEN_COUNT);
        tokenIds.add((long) BOS_TOKEN_ID);

        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find() && tokenIds.size() < MAX_TOKEN_COUNT - 1) {
            String token = matcher.group();
            if (BOS_TOKEN.equals(token)) {
                tokenIds.add((long) BOS_TOKEN_ID);
                continue;
            }
            if (EOS_TOKEN.equals(token)) {
                tokenIds.add((long) EOS_TOKEN_ID);
                continue;
            }

            String byteEncoded = encodeBytes(token);
            for (String piece : applyBpe(byteEncoded)) {
                if (tokenIds.size() >= MAX_TOKEN_COUNT - 1) {
                    break;
                }
                tokenIds.add((long) vocabulary.getOrDefault(piece, EOS_TOKEN_ID));
            }
        }

        tokenIds.add((long) EOS_TOKEN_ID);
        while (tokenIds.size() < MAX_TOKEN_COUNT) {
            tokenIds.add((long) EOS_TOKEN_ID);
        }

        long[] result = new long[MAX_TOKEN_COUNT];
        for (int index = 0; index < result.length; index++) {
            result[index] = tokenIds.get(index);
        }
        return result;
    }

    private String encodeBytes(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            encoded.append(byteEncoder.get(value & 0xff));
        }
        return encoded.toString();
    }

    private List<String> applyBpe(String token) {
        List<String> cached = bpeCache.get(token);
        if (cached != null) {
            return cached;
        }

        List<String> symbols = new ArrayList<>();
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            symbols.add(new String(Character.toChars(codePoint)));
            offset += Character.charCount(codePoint);
        }
        int lastIndex = symbols.size() - 1;
        symbols.set(lastIndex, symbols.get(lastIndex) + END_OF_WORD);

        while (symbols.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            String bestFirst = null;
            String bestSecond = null;

            for (int index = 0; index < symbols.size() - 1; index++) {
                String first = symbols.get(index);
                String second = symbols.get(index + 1);
                Integer rank = mergeRanks.get(pairKey(first, second));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestFirst = first;
                    bestSecond = second;
                }
            }

            if (bestFirst == null) {
                break;
            }

            List<String> merged = new ArrayList<>(symbols.size());
            int index = 0;
            while (index < symbols.size()) {
                if (index < symbols.size() - 1
                        && symbols.get(index).equals(bestFirst)
                        && symbols.get(index + 1).equals(bestSecond)) {
                    merged.add(bestFirst + bestSecond);
                    index += 2;
                } else {
                    merged.add(symbols.get(index));
                    index++;
                }
            }
            symbols = merged;
        }

        bpeCache.put(token, symbols);
        return symbols;
    }

    private static Map<String, Integer> readVocabulary(JSONObject json) throws Exception {
        Map<String, Integer> vocabulary = new HashMap<>(65536);
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String token = keys.next();
            vocabulary.put(token, json.getInt(token));
        }
        return vocabulary;
    }

    private static Map<String, Integer> readMergeRanks(JSONArray json) throws Exception {
        Map<String, Integer> ranks = new HashMap<>(65536);
        for (int rank = 0; rank < json.length(); rank++) {
            JSONArray pair = json.getJSONArray(rank);
            ranks.put(pairKey(pair.getString(0), pair.getString(1)), rank);
        }
        return ranks;
    }

    private static Map<Integer, String> createByteEncoder() {
        List<Integer> bytes = new ArrayList<>();
        for (int value = 33; value <= 126; value++) {
            bytes.add(value);
        }
        for (int value = 161; value <= 172; value++) {
            bytes.add(value);
        }
        for (int value = 174; value <= 255; value++) {
            bytes.add(value);
        }

        List<Integer> codePoints = new ArrayList<>(bytes);
        int extraCodePoint = 0;
        for (int value = 0; value < 256; value++) {
            if (!bytes.contains(value)) {
                bytes.add(value);
                codePoints.add(256 + extraCodePoint);
                extraCodePoint++;
            }
        }

        Map<Integer, String> encoder = new HashMap<>(256);
        for (int index = 0; index < bytes.size(); index++) {
            encoder.put(bytes.get(index), new String(Character.toChars(codePoints.get(index))));
        }
        return encoder;
    }

    private static String pairKey(String first, String second) {
        return first + PAIR_SEPARATOR + second;
    }
}

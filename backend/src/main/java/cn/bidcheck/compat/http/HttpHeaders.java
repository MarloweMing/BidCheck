package cn.bidcheck.compat.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class HttpHeaders {
    private final Map<String, List<String>> values;

    HttpHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        values = Collections.unmodifiableMap(copy);
    }

    public Optional<String> firstValue(String name) {
        List<String> found = values.get(name);
        return found == null || found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public List<String> allValues(String name) {
        List<String> found = values.get(name);
        return found == null ? Collections.emptyList() : found;
    }

    public Map<String, List<String>> map() { return values; }
}

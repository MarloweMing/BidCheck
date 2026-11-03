package cn.bidcheck.compat;

import cn.bidcheck.ocr.OcrBatch;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OcrBatchCompatibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void compactConstructorsStillValidateAndNormalizeDeserializedOcrValues() throws Exception {
        OcrBatch.Item item = json.readValue("{\"id\":\"page:1\",\"result\":null,\"errorCode\":null,\"retryable\":true}", OcrBatch.Item.class);
        assertEquals("", item.errorCode());
        assertEquals(item, json.readValue(json.writeValueAsBytes(item), OcrBatch.Item.class));

        OcrBatch.Input input = new OcrBatch.Input("page:1", new byte[]{1, 2}, "image/png", 32, 64, "SCAN", "TEXT");
        OcrBatch.Input restored = json.readValue(json.writeValueAsBytes(input), OcrBatch.Input.class);
        assertEquals(input.id(), restored.id());
        assertArrayEquals(input.image(), restored.image());
        assertEquals(2048L, restored.pixels());
        assertThrows(JsonMappingException.class, () -> json.readValue("{\"id\":\"page:1\",\"image\":\"AQI=\",\"mimeType\":\"image/png\",\"width\":0,\"height\":64,\"pageType\":\"SCAN\",\"schema\":\"TEXT\"}", OcrBatch.Input.class));
    }

    @Test void compactConstructorKeepsItsDefensiveCollectionCopy() throws Exception {
        List<String> ids = new ArrayList<>(Arrays.asList("page:1"));
        OcrBatch.Event event = new OcrBatch.Event("batch", "", "DONE", ids, "model", "policy", "hash", 2048L, 4, 10L, "");
        ids.add("page:2");
        assertEquals(Arrays.asList("page:1"), event.inputIds());
        assertThrows(UnsupportedOperationException.class, () -> event.inputIds().add("page:3"));
        assertEquals(event, json.readValue(json.writeValueAsBytes(event), OcrBatch.Event.class));
    }

}

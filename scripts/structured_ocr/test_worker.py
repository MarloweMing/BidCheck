import base64
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from worker import WorkerError, LocalOcrWorker, decode_image, detect_grids, normalize_boxes, _assemble_grid, validate_regions, recognize_regions, MAX_BYTES, MAX_BASE64, MAX_LINE


def image_bytes(width=80, height=60, format="PNG"):
    stream = io.BytesIO()
    Image.new("RGB", (width, height), "white").save(stream, format=format)
    return stream.getvalue()


class LocalWorkerTest(unittest.TestCase):
    def test_image_roundtrip_dimensions_and_bytes(self):
        raw = image_bytes()
        actual, image = decode_image(base64.b64encode(raw).decode())
        self.assertEqual(actual, raw)
        self.assertEqual(image.shape[:2], (60, 80))

    def test_bad_base64_and_other_formats_rejected(self):
        for value in ("no%%", "data:image/png;base64,AAAA", None,
                      base64.b64encode(image_bytes(format="GIF")).decode()):
            with self.assertRaises(WorkerError):
                decode_image(value)

    def test_pixel_budget_checked_before_decode(self):
        raw = image_bytes(4000, 4000)
        with self.assertRaisesRegex(WorkerError, "IMAGE_PIXELS_LIMIT"):
            decode_image(base64.b64encode(raw).decode())

    def test_real_closed_grid_has_empty_cells_and_no_guessed_headers(self):
        image = np.full((500, 600, 3), 255, np.uint8)
        for x in (50, 200, 400, 550):
            cv2.line(image, (x, 100), (x, 400), (0, 0, 0), 2)
        for y in (100, 200, 300, 400):
            cv2.line(image, (50, y), (550, y), (0, 0, 0), 2)
        grids, warnings = detect_grids(image)
        self.assertEqual(len(grids), 1, warnings)
        grid = grids[0]
        self.assertEqual((grid["rows"], grid["columns"]), (3, 3))
        self.assertEqual(len(grid["cells"]), 9)
        self.assertTrue(all(not c["header"] for c in grid["cells"]))
        self.assertTrue(all("text" not in c and "blockIds" not in c for c in grid["cells"]))

    def test_pale_rules_on_colored_header_remain_physical_boundaries(self):
        image = np.full((500, 600, 3), 255, np.uint8)
        image[100:170, 50:550] = (218, 244, 245)
        for x in (50, 200, 400, 550):
            cv2.line(image, (x, 100), (x, 400), (213, 213, 213), 1)
        for y in (100, 170, 400):
            cv2.line(image, (50, y), (550, y), (213, 213, 213), 1)
        cv2.putText(image, "HEADER", (60, 140), cv2.FONT_HERSHEY_SIMPLEX, .7, (0, 0, 0), 1)
        grids, warnings = detect_grids(image)
        self.assertEqual(len(grids), 1, warnings)
        self.assertEqual((grids[0]["rows"], grids[0]["columns"]), (2, 3))
        self.assertEqual(len(grids[0]["cells"]), 6)

    def test_grid_only_never_initializes_or_calls_ocr(self):
        worker = LocalOcrWorker()
        with patch.object(worker, "_engine", side_effect=AssertionError("must not run OCR")):
            result = worker.process({"id": "grid", "gridOnly": True,
                "imageBase64": base64.b64encode(image_bytes()).decode()})
        self.assertEqual(result["textBoxes"], [])
        self.assertEqual(result["engine"]["name"], "OpenCV")
        self.assertNotIn("OCR_NO_TEXT", result["warnings"])

    def test_regions_round_inward_and_reject_overlap_or_bad_coordinates(self):
        regions = validate_regions([{"id": "cell_1", "box": [1.2, 2.3, 20.9, 30.8]}], 100, 100)
        self.assertEqual(regions[0]["actualBox"], [2, 3, 20, 30])
        for entries in ([{"id": "a", "box": [0, 0, 101, 10]}],
                        [{"id": "a", "box": [0, 0, float("nan"), 10]}],
                        [{"id": "a", "box": [1.1, 1.1, 2.9, 2.9]}],
                        [{"id": "a", "box": [0, 0, 10, 10]}, {"id": "b", "box": [5, 5, 15, 15]}],
                        [{"id": "a", "box": [0, 0, 10, 10]}] * 65):
            with self.assertRaises(WorkerError):
                validate_regions(entries, 100, 100)

    def test_crop_ocr_is_measured_then_mapped_back_without_text_splitting(self):
        calls = []
        def engine(crop):
            h, w = crop.shape[:2]
            calls.append((w, h))
            return SimpleNamespace(boxes=[[[0, 0], [w, 0], [w, h], [0, h]]],
                                   txts=["原样 12.50 元"], scores=[.8])
        regions = validate_regions([{"id": "one", "box": [1.2, 2.1, 31.9, 12.9]},
                                    {"id": "two", "box": [40, 20, 70, 50]}], 100, 100)
        boxes, metadata, warnings = recognize_regions(engine, np.zeros((100, 100, 3), np.uint8), regions)
        self.assertEqual(calls, [(116, 36), (120, 120)])
        self.assertEqual(boxes[0]["bbox"], {"x": 2., "y": 3., "width": 29., "height": 9.})
        self.assertEqual(boxes[0]["text"], "原样 12.50 元")
        self.assertEqual(boxes[0]["sourceRegion"]["box"], [2, 3, 31, 12])
        self.assertEqual(metadata[0]["scaleX"], 4)
        self.assertEqual(warnings, [])

    def test_cell_resize_budget_is_bounded_without_running_a_model(self):
        dimensions = []
        def empty(crop):
            h, w = crop.shape[:2];dimensions.append((w, h))
            return SimpleNamespace(boxes=None, txts=None, scores=None)
        regions = validate_regions([{"id": "large", "box": [0, 0, 2500, 2200]}], 2500, 2200)
        boxes, _, warnings = recognize_regions(empty, np.zeros((2200, 2500, 3), np.uint8), regions)
        self.assertLessEqual(max(dimensions[0]), 2400)
        self.assertLessEqual(dimensions[0][0] * dimensions[0][1], 4_000_000)
        self.assertEqual(boxes, [])
        self.assertIn("CELL_OCR_NO_TEXT", warnings)

    def test_six_mebibyte_image_with_64_region_metadata_fits_protocol_budget(self):
        png = image_bytes()
        raw = png + b"\0" * (MAX_BYTES - len(png))
        regions = [{"id": f"region_{i}_" + "x" * 80,
                    "box": [(i % 8) * 5, (i // 8) * 5, (i % 8) * 5 + 4, (i // 8) * 5 + 4]}
                   for i in range(64)]
        request = {"id": "full-budget", "imageBase64": base64.b64encode(raw).decode(), "cellRegions": regions}
        wire = json.dumps(request).encode() + b"\n"
        self.assertGreater(len(wire), MAX_BASE64 + 4096)
        self.assertLessEqual(len(wire), MAX_LINE)
        worker = LocalOcrWorker()
        empty = lambda crop: SimpleNamespace(boxes=None, txts=None, scores=None)
        with patch.object(worker, "_engine", return_value=empty):
            result = worker.process(request)
        self.assertEqual(result["status"], "OK")
        self.assertEqual(len(result["sourceRegions"]), 64)

    def test_merged_cell_only_when_shared_line_missing_and_outer_closed(self):
        h = np.zeros((220, 320), np.uint8)
        v = h.copy()
        for y in (10, 110, 210):
            cv2.line(h, (10, y), (310, y), 255, 1)
        for x in (10, 310):
            cv2.line(v, (x, 10), (x, 210), 255, 1)
        cv2.line(v, (160, 110), (160, 210), 255, 1)
        grid = _assemble_grid(h, v, [10, 160, 310], [10, 110, 210])
        self.assertIsNotNone(grid)
        self.assertEqual(len(grid["cells"]), 3)
        self.assertEqual(grid["cells"][0]["columnSpan"], 2)
        v[120:150, 160] = 0
        self.assertIsNone(_assemble_grid(h, v, [10, 160, 310], [10, 110, 210]))

    def test_open_outer_border_and_borderless_text_never_form_table(self):
        image = np.full((300, 500, 3), 255, np.uint8)
        for y in (80, 150, 230):
            cv2.putText(image, "A    123    B", (40, y), cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 0), 1)
        self.assertEqual(detect_grids(image)[0], [])
        h = np.zeros((220, 320), np.uint8)
        v = h.copy()
        for y in (10, 110):
            cv2.line(h, (10, y), (310, y), 255, 1)
        for x in (10, 160, 310):
            cv2.line(v, (x, 10), (x, 210), 255, 1)
        self.assertIsNone(_assemble_grid(h, v, [10, 160, 310], [10, 110, 210]))

    def test_boxes_keep_original_text_coordinates_and_confidence(self):
        out = SimpleNamespace(boxes=[[[1, 2], [20, 2], [20, 12], [1, 12]]],
                              txts=["甲公司 1.20万元"], scores=[0.91])
        boxes = normalize_boxes(out, 100, 50)
        self.assertEqual(boxes[0]["text"], out.txts[0])
        self.assertEqual(boxes[0]["confidence"], .91)
        self.assertEqual(boxes[0]["bbox"], {"x": 1., "y": 2., "width": 19., "height": 10.})

    def test_boxes_never_silently_clip_or_accept_nonfinite(self):
        for points, score in (([[1, 2], [101, 2], [101, 12], [1, 12]], .8),
                              ([[1, 2], [20, 2], [20, 12], [1, 12]], float("nan"))):
            with self.assertRaises(WorkerError):
                normalize_boxes(SimpleNamespace(boxes=[points], txts=["text"], scores=[score]), 100, 50)

    def test_daemon_invalid_request_recovers_and_stdout_is_json_only(self):
        request = b'not json\n{"id":"safe","imageBase64":"bad%%%"}\n'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("worker.py"))],
                                input=request, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0)
        lines = [json.loads(x) for x in result.stdout.decode().splitlines()]
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[0]["errorCode"], "INVALID_JSON")
        self.assertEqual(lines[1]["id"], "safe")
        self.assertEqual(lines[1]["errorCode"], "INVALID_IMAGE")


if __name__ == "__main__":
    unittest.main()

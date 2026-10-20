"""Local, bounded OCR worker. JSON-lines in/out; stdout never contains model logs.

Coordinates are original decoded-image pixels. Grid cells come from observed
closed raster lines, not generated text. Java remains the authority for mapping
text boxes to cells and validating source text and all layout relationships.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import contextlib
import hashlib
import importlib.metadata
import io
import json
import math
import os
import re
import sys
import threading
import time
from pathlib import Path

os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("MKL_NUM_THREADS", "2")

SCHEMA = "local-ocr-grid-1"
POLICY = "rapidocr-raster-grid-2"
MAX_BYTES = 6 * 1024 * 1024
MAX_PIXELS = 12_000_000
MAX_BASE64 = 4 * ((MAX_BYTES + 2) // 3)
MAX_LINE = MAX_BASE64 + 32768
MAX_BOXES = 1500
MAX_TABLES = 40
MAX_SLOTS = 3000


class WorkerError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def decode_image(value):
    import cv2
    import numpy as np
    from PIL import Image

    if not isinstance(value, str) or not value or len(value) > MAX_BASE64:
        raise WorkerError("INVALID_IMAGE")
    try:
        raw = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise WorkerError("INVALID_IMAGE") from None
    if not raw or len(raw) > MAX_BYTES:
        raise WorkerError("IMAGE_BYTES_LIMIT")
    try:
        with Image.open(io.BytesIO(raw)) as probe:
            width, height = probe.size
            if probe.format not in ("PNG", "JPEG"):
                raise WorkerError("IMAGE_FORMAT_UNSUPPORTED")
            if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                raise WorkerError("IMAGE_PIXELS_LIMIT")
            # imdecode and OCR use stored raster orientation, never implicit EXIF.
            if getattr(probe, "n_frames", 1) != 1:
                raise WorkerError("IMAGE_MULTIFRAME_UNSUPPORTED")
            probe.verify()
        image = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION)
        if image is None or image.shape[:2] != (height, width):
            raise WorkerError("INVALID_IMAGE")
    except WorkerError:
        raise
    except Exception:
        raise WorkerError("INVALID_IMAGE") from None
    return raw, image


def _clusters(values):
    """Collapse only adjacent pixels of one line, retaining its observed center."""
    groups = []
    for item in values:
        item = int(item)
        if not groups or item > groups[-1][-1] + 2:
            groups.append([item])
        else:
            groups[-1].append(item)
    return [int(round(sum(g) / len(g))) for g in groups]


def _edge(mask, horizontal, axis, start, end):
    import numpy as np
    # Exclude intersections: a pair of vertical lines is not a horizontal edge.
    start, end = start + 3, end - 3
    if end <= start:
        return None
    if horizontal:
        sample = mask[max(0, axis - 2):axis + 3, start:end]
        covered = np.any(sample > 0, axis=0)
    else:
        sample = mask[start:end, max(0, axis - 2):axis + 3]
        covered = np.any(sample > 0, axis=1)
    coverage = float(covered.mean()) if covered.size else 0.0
    if coverage >= 0.80:
        return True
    if coverage <= 0.08:
        return False
    return None  # An interrupted/uncertain edge must not invent a merge.


def _assemble_grid(hmask, vmask, xs, ys):
    rows, columns = len(ys) - 1, len(xs) - 1
    if rows < 1 or columns < 2 or rows * columns > MAX_SLOTS:
        return None
    horizontal = [[_edge(hmask, True, y, xs[c], xs[c + 1])
                   for c in range(columns)] for y in ys]
    vertical = [[_edge(vmask, False, x, ys[r], ys[r + 1])
                 for x in xs] for r in range(rows)]
    if any(v is None for line in horizontal + vertical for v in line):
        return None
    if not all(horizontal[0] + horizontal[-1]) or not all(
            row[0] and row[-1] for row in vertical):
        return None
    parent = list(range(rows * columns))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def join(a, b):
        parent[find(a)] = find(b)

    for r in range(rows):
        for c in range(columns):
            if c + 1 < columns and not vertical[r][c + 1]:
                join(r * columns + c, r * columns + c + 1)
            if r + 1 < rows and not horizontal[r + 1][c]:
                join(r * columns + c, (r + 1) * columns + c)
    groups = {}
    for r in range(rows):
        for c in range(columns):
            groups.setdefault(find(r * columns + c), []).append((r, c))
    cells = []
    for positions in groups.values():
        r0, r1 = min(r for r, _ in positions), max(r for r, _ in positions)
        c0, c1 = min(c for _, c in positions), max(c for _, c in positions)
        if len(positions) != (r1 - r0 + 1) * (c1 - c0 + 1):
            return None  # L-shaped holes are not a rectangular merged cell.
        # Every merged cell must have all four observed closed borders.
        if not (all(horizontal[r0][c0:c1 + 1]) and
                all(horizontal[r1 + 1][c0:c1 + 1]) and
                all(vertical[r][c0] and vertical[r][c1 + 1]
                    for r in range(r0, r1 + 1))):
            return None
        cells.append({"row": r0, "column": c0, "rowSpan": r1 - r0 + 1,
                      "columnSpan": c1 - c0 + 1,
                      "box": [xs[c0], ys[r0], xs[c1 + 1], ys[r1 + 1]],
                      "header": False})
    cells.sort(key=lambda item: (item["row"], item["column"]))
    return {"frameId": "f1", "coordinateSpace": "PIXEL_XYXY",
            "rows": rows, "columns": columns, "cells": cells}


def detect_grids(image):
    """Only ruled, closed tables; borderless and ambiguous tables stay unbound."""
    import cv2
    import numpy as np
    height, width = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    # Directional black-hat isolates thin dark rules from colored fills. Local
    # mean thresholding incorrectly erases pale rules beside dense header text.
    hb = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                         cv2.getStructuringElement(cv2.MORPH_RECT, (1, 7)))
    vb = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT,
                         cv2.getStructuringElement(cv2.MORPH_RECT, (7, 1)))
    hmask = cv2.morphologyEx(cv2.threshold(hb, 5, 255, cv2.THRESH_BINARY)[1], cv2.MORPH_OPEN,
                           cv2.getStructuringElement(cv2.MORPH_RECT, (24, 1)))
    vmask = cv2.morphologyEx(cv2.threshold(vb, 5, 255, cv2.THRESH_BINARY)[1], cv2.MORPH_OPEN,
                           cv2.getStructuringElement(cv2.MORPH_RECT, (1, 24)))
    connected = cv2.dilate(cv2.bitwise_or(hmask, vmask), np.ones((3, 3), np.uint8))
    count, _, stats, _ = cv2.connectedComponentsWithStats(connected, 8)
    grids, warnings = [], []
    regions = sorted(stats[1:], key=lambda s: (int(s[1]), int(s[0])))
    for x, y, w, h, _ in regions:
        x, y, w, h = map(int, (x, y, w, h))
        if w < max(60, width // 20) or h < 30:
            continue
        # A potential table needs at least two observed columns and one row.
        xs = [x + p for p in _clusters(np.flatnonzero(
            np.count_nonzero(vmask[y:y + h, x:x + w], axis=0) >= max(20, h * .12)))]
        ys = [y + p for p in _clusters(np.flatnonzero(
            np.count_nonzero(hmask[y:y + h, x:x + w], axis=1) >= max(24, w * .10)))]
        if len(xs) < 3 or len(ys) < 2:
            continue
        grid = _assemble_grid(hmask, vmask, xs, ys)
        if grid is None:
            warnings.append("GRID_GEOMETRY_AMBIGUOUS")
            continue
        if len(grids) >= MAX_TABLES or sum(len(g["cells"]) for g in grids) + len(grid["cells"]) > MAX_SLOTS:
            warnings.append("GRID_BUDGET_EXCEEDED")
            break
        grids.append(grid)
    return grids, sorted(set(warnings))


def normalize_boxes(output, width, height):
    import numpy as np
    boxes = getattr(output, "boxes", None)
    texts = getattr(output, "txts", None)
    scores = getattr(output, "scores", None)
    if boxes is None and texts is None:
        return []
    if boxes is None or texts is None or scores is None or not (len(boxes) == len(texts) == len(scores)):
        raise WorkerError("OCR_OUTPUT_INVALID")
    if len(boxes) > MAX_BOXES:
        raise WorkerError("OCR_BOXES_LIMIT")
    result = []
    for polygon, text, score in zip(boxes, texts, scores):
        points = np.asarray(polygon, dtype=float)
        confidence = float(score)
        if points.shape != (4, 2) or not np.isfinite(points).all() or not math.isfinite(confidence):
            raise WorkerError("OCR_OUTPUT_INVALID")
        if not isinstance(text, str) or not text.strip() or len(text) > 8000 or not 0 <= confidence <= 1:
            raise WorkerError("OCR_OUTPUT_INVALID")
        x0, y0 = points.min(axis=0)
        x1, y1 = points.max(axis=0)
        if x0 < 0 or y0 < 0 or x1 > width or y1 > height or x1 <= x0 or y1 <= y0:
            raise WorkerError("OCR_COORDINATES_INVALID")
        result.append({"id": f"b{len(result) + 1}", "frameId": "f1", "text": text,
                       "polygon": points.tolist(),
                       "bbox": {"x": float(x0), "y": float(y0),
                                "width": float(x1 - x0), "height": float(y1 - y0)},
                       "confidence": confidence})
    return result


def validate_regions(value, width, height):
    """Inward rounding cannot include pixels across an observed cell boundary."""
    if not isinstance(value, list) or not 1 <= len(value) <= 64:
        raise WorkerError("CELL_REGIONS_LIMIT")
    regions, ids = [], set()
    for item in value:
        if not isinstance(item, dict) or set(item) != {"id", "box"}:
            raise WorkerError("CELL_REGION_INVALID")
        identity, box = item["id"], item["box"]
        if not isinstance(identity, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,96}", identity) or identity in ids:
            raise WorkerError("CELL_REGION_INVALID")
        if not isinstance(box, list) or len(box) != 4 or any(
                not isinstance(n, (int, float)) or isinstance(n, bool) or not math.isfinite(n) for n in box):
            raise WorkerError("CELL_REGION_INVALID")
        x0, y0, x1, y1 = box
        if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
            raise WorkerError("CELL_REGION_OUT_OF_BOUNDS")
        inward = [math.ceil(x0), math.ceil(y0), math.floor(x1), math.floor(y1)]
        if inward[2] - inward[0] < 2 or inward[3] - inward[1] < 2:
            raise WorkerError("CELL_REGION_EMPTY")
        for other in regions:
            prior = other["box"]
            if min(x1, prior[2]) > max(x0, prior[0]) and min(y1, prior[3]) > max(y0, prior[1]):
                raise WorkerError("CELL_REGIONS_OVERLAP")
        ids.add(identity)
        regions.append({"id": identity, "box": list(box), "actualBox": inward})
    return regions


def recognize_regions(engine, image, regions):
    import cv2
    boxes, metadata, warnings = [], [], []
    for region in regions:
        x0, y0, x1, y1 = region["actualBox"]
        width, height = x1 - x0, y1 - y0
        scale = min(4.0, 2400.0 / max(width, height), math.sqrt(4_000_000.0 / (width * height)))
        out_width, out_height = max(1, math.floor(width * scale)), max(1, math.floor(height * scale))
        crop = image[y0:y1, x0:x1]
        if (out_width, out_height) != (width, height):
            crop = cv2.resize(crop, (out_width, out_height), interpolation=cv2.INTER_CUBIC)
        sx, sy = out_width / width, out_height / height
        provenance = {"id": region["id"], "box": region["actualBox"], "scale": scale,
                      "scaleX": sx, "scaleY": sy, "inputWidth": out_width, "inputHeight": out_height}
        metadata.append(provenance)
        local = normalize_boxes(engine(crop), out_width, out_height)
        if not local:
            warnings.append("CELL_OCR_NO_TEXT")
        for box in local:
            # This is an exact crop/resize transform of measured detector points.
            polygon = [[x0 + p[0] / sx, y0 + p[1] / sy] for p in box["polygon"]]
            left, right = min(p[0] for p in polygon), max(p[0] for p in polygon)
            top, bottom = min(p[1] for p in polygon), max(p[1] for p in polygon)
            boxes.append({"id": f"b{len(boxes) + 1}", "frameId": "f1", "text": box["text"],
                          "polygon": polygon,
                          "bbox": {"x": left, "y": top, "width": right - left, "height": bottom - top},
                          "confidence": box["confidence"], "sourceRegion": provenance})
            if len(boxes) > MAX_BOXES:
                raise WorkerError("OCR_BOXES_LIMIT")
    return boxes, metadata, warnings


class LocalOcrWorker:
    def __init__(self, model_dir=None):
        self.engine = None
        self.model_dir = model_dir

    def _engine(self):
        if self.engine is None:
            import cv2
            from rapidocr import RapidOCR
            cv2.setNumThreads(2)
            params = {"EngineConfig.onnxruntime.intra_op_num_threads": 2,
                      "EngineConfig.onnxruntime.inter_op_num_threads": 1,
                      "EngineConfig.onnxruntime.use_cuda": False,
                      "Global.log_level": "error", "Global.max_side_len": 2400,
                      "Global.text_score": 0.5, "Det.limit_side_len": 1600,
                      "Det.limit_type": "max"}
            if self.model_dir:
                params["Global.model_root_dir"] = str(Path(self.model_dir).resolve())
            self.engine = RapidOCR(params=params)
        return self.engine

    def process(self, request):
        started = time.monotonic()
        if not isinstance(request, dict) or set(request) - {"id", "imageBase64", "gridOnly", "cellRegions"}:
            raise WorkerError("INVALID_REQUEST")
        request_id = request.get("id")
        if not isinstance(request_id, str) or not request_id or len(request_id) > 128:
            raise WorkerError("INVALID_REQUEST_ID")
        grid_only = request.get("gridOnly", False)
        if not isinstance(grid_only, bool):
            raise WorkerError("INVALID_REQUEST")
        raw, image = decode_image(request.get("imageBase64"))
        height, width = image.shape[:2]
        regions = None
        if "cellRegions" in request:
            if grid_only:
                raise WorkerError("INVALID_REQUEST")
            regions = validate_regions(request["cellRegions"], width, height)
        source_regions, ocr_warnings = [], []
        if grid_only:
            import cv2
            cv2.setNumThreads(2)
            boxes = []
            engine = {"name": "OpenCV", "version": cv2.__version__,
                      "executionProvider": "CPU", "threads": 2}
        else:
            # Some libraries print download messages: keep protocol stdout clean.
            with contextlib.redirect_stdout(sys.stderr):
                if regions is not None:
                    boxes, source_regions, ocr_warnings = recognize_regions(self._engine(), image, regions)
                else:
                    output = self._engine()(image)
                    boxes = normalize_boxes(output, width, height)
            engine = {"name": "RapidOCR", "version": importlib.metadata.version("rapidocr"),
                      "executionProvider": "CPU", "threads": 2}
        grids, warnings = detect_grids(image)
        warnings.extend(ocr_warnings)
        if not boxes and not grid_only:
            warnings.append("OCR_NO_TEXT")
        if not grids:
            warnings.append("NO_CLOSED_GRID")
        return {"id": request_id, "schemaVersion": SCHEMA, "policy": POLICY,
                "status": "OK", "coordinateSystem": "PIXEL_TOP_LEFT",
                "width": width, "height": height,
                "imageHash": hashlib.sha256(raw).hexdigest(),
                "engine": engine, "gridOnly": grid_only, "sourceRegions": source_regions,
                "textBoxes": boxes, "readingOrder": [b["id"] for b in boxes],
                "gridCandidates": grids, "warnings": sorted(set(warnings)),
                "elapsedMs": round((time.monotonic() - started) * 1000)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir")
    parser.add_argument("--timeout-seconds", type=int, default=120)
    args = parser.parse_args()
    if not 10 <= args.timeout_seconds <= 300:
        parser.error("timeout must be between 10 and 300 seconds")
    worker = LocalOcrWorker(args.model_dir)
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    sys.stderr.reconfigure(encoding="utf-8")
    while True:
        line = sys.stdin.buffer.readline(MAX_LINE + 1)
        if not line:
            break
        if len(line) > MAX_LINE:
            # Never continue with the tail of an over-budget request as new JSON.
            print(json.dumps({"status": "ERROR", "errorCode": "REQUEST_LIMIT"}), flush=True)
            return 2
        request_id = None
        timer = threading.Timer(args.timeout_seconds, lambda: os._exit(124))
        timer.daemon = True
        try:
            request = json.loads(line)
            request_id = request.get("id") if isinstance(request, dict) else None
            if not isinstance(request_id, str) or len(request_id) > 128:
                request_id = None
            timer.start()
            result = worker.process(request)
        except WorkerError as exc:
            result = {"id": request_id, "status": "ERROR", "errorCode": exc.code}
        except (json.JSONDecodeError, UnicodeDecodeError):
            result = {"id": request_id, "status": "ERROR", "errorCode": "INVALID_JSON"}
        except Exception:
            # No exception repr, source text, URL, filesystem path or secrets.
            result = {"id": request_id, "status": "ERROR", "errorCode": "OCR_ENGINE_ERROR"}
        finally:
            timer.cancel()
        print(json.dumps(result, ensure_ascii=False, allow_nan=False, separators=(",", ":")), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

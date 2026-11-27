"""Aqlli kamera: piyodalar (yosh guruhlari bo'yicha) va transport (turlari bo'yicha) sanash.

Ishga tushirish:
    python -m kamera.run --config sozlama.yaml
    python -m kamera.run --config sozlama.yaml --source video.mp4
"""
from __future__ import annotations

import argparse
import time
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import cv2

from .config import Config, load_config
from .counter import LineCounter
from .report import build_reports, save_reports

PERSON = "person"


def _to_px(pt, w, h):
    x, y = pt
    if 0 <= x <= 1 and 0 <= y <= 1:  # nisbiy koordinatalar
        return (x * w, y * h)
    return (float(x), float(y))


def _parse_source(src: str):
    return int(src) if str(src).isdigit() else src


class Pipeline:
    def __init__(self, cfg: Config):
        from ultralytics import YOLO

        self.cfg = cfg
        self.model = YOLO(cfg.model)
        names = self.model.names
        self.name_to_id = {v: k for k, v in names.items()}
        self.class_ids = [self.name_to_id[PERSON]] + [
            self.name_to_id[c] for c in cfg.vehicle_classes if c in self.name_to_id]
        self.names = names
        self.age = None
        if cfg.age_enabled:
            try:
                from .age import AgeClassifier
                self.age = AgeClassifier(cfg.age_groups, cfg.age_model, cfg.age_pretrained,
                                         device=cfg.device, min_crop_h=cfg.age_min_crop_h,
                                         samples_per_track=cfg.age_samples_per_track)
            except Exception as e:  # og'irliklar yuklanmasa: yoshsiz davom etadi
                print(f"Ogohlantirish: yosh modeli yuklanmadi ({e}). Piyodalar yoshsiz sanaladi.")
        self.lines: list[tuple[LineCounter, str]] = []
        self.track_classes: dict[int, Counter] = defaultdict(Counter)
        self.last_seen: dict[int, int] = {}
        self.pending: list[dict] = []  # yoshi hali aniqlanmagan piyoda hodisalari
        self.events: list[dict] = []
        self.totals: Counter = Counter()

    # --- yordamchi -----------------------------------------------------
    def _setup_lines(self, w: int, h: int):
        self.lines = [(LineCounter(l.name, _to_px(l.p1, w, h), _to_px(l.p2, w, h)), l.applies_to)
                      for l in self.cfg.lines]
        if not self.lines:  # chiziq berilmasa: kadr o'rtasidan gorizontal chiziq
            self.lines = [(LineCounter("orta", (0, h / 2), (w, h / 2)), "all")]

    def _kind(self, tid: int) -> tuple[str, str]:
        cls = self.track_classes[tid].most_common(1)[0][0]
        if cls == PERSON:
            return "piyoda", ""
        return "transport", self.cfg.vehicle_classes.get(cls, cls)

    def _finalize(self, frame_idx: int, fps: float, force: bool = False):
        keep = []
        for ev in self.pending:
            tid = ev["trek_id"]
            done = force or (frame_idx - self.last_seen.get(tid, frame_idx) > 2 * fps)
            if self.age and not done:
                done = not self.age.wants_sample(tid, 10**6)  # namunalar to'ldi
            if done:
                if self.age:
                    ev["toifa"], ev["ishonch"] = self.age.result(tid)
                else:
                    ev["toifa"], ev["ishonch"] = "noaniq", 0.0
                self.events.append(ev)
                self.totals[f"piyoda:{ev['toifa']}"] += 1
            else:
                keep.append(ev)
        self.pending = keep

    def _draw(self, frame, boxes):
        for lc, _ in self.lines:
            p1 = tuple(int(v) for v in lc.p1)
            p2 = tuple(int(v) for v in lc.p2)
            cv2.line(frame, p1, p2, (0, 255, 255), 2)
            cv2.putText(frame, lc.name, p1, cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
        for tid, (x1, y1, x2, y2), cls in boxes:
            color = (60, 200, 60) if cls == PERSON else (230, 140, 30)
            label = f"{tid}"
            if cls == PERSON and self.age:
                g, p = self.age.result(tid)
                label += f" {g}"
            else:
                label += f" {self.cfg.vehicle_classes.get(cls, cls)}"
            cv2.rectangle(frame, (int(x1), int(y1)), (int(x2), int(y2)), color, 2)
            cv2.putText(frame, label, (int(x1), int(y1) - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)
        y = 24
        cv2.rectangle(frame, (0, 0), (300, 22 + 20 * max(1, len(self.totals))), (0, 0, 0), -1)
        for k, v in sorted(self.totals.items()):
            cv2.putText(frame, f"{k}: {v}", (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 1)
            y += 20
        return frame

    # --- asosiy sikl ---------------------------------------------------
    def run(self) -> list[Path]:
        cfg = self.cfg
        src = _parse_source(cfg.source)
        live = isinstance(src, int) or str(src).startswith(("rtsp://", "http://", "https://"))
        cap = cv2.VideoCapture(src)
        fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
        w, h = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        cap.release()
        self._setup_lines(w, h)

        t0 = datetime.fromisoformat(cfg.start_time) if cfg.start_time else datetime.now().replace(microsecond=0)
        out_dir = Path(cfg.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        stem = f"{cfg.site_name}_{t0:%Y%m%d_%H%M}"
        writer = None
        if cfg.save_video:
            writer = cv2.VideoWriter(str(out_dir / f"{stem}_korinish.mp4"),
                                     cv2.VideoWriter_fourcc(*"mp4v"), fps / cfg.frame_stride, (w, h))

        results = self.model.track(source=src, stream=True, persist=True, tracker=cfg.tracker,
                                   conf=cfg.conf, imgsz=cfg.imgsz, classes=self.class_ids,
                                   device=cfg.device, vid_stride=cfg.frame_stride, verbose=False)
        last_save = time.time()
        frame_idx = 0
        started = time.time()
        for frame_idx, r in enumerate(results):
            frame = r.orig_img
            now = datetime.now() if live else t0 + timedelta(seconds=frame_idx * cfg.frame_stride / fps)
            boxes = []
            if r.boxes is not None and r.boxes.id is not None:
                ids = r.boxes.id.int().tolist()
                xyxy = r.boxes.xyxy.tolist()
                clss = r.boxes.cls.int().tolist()
                for tid, b, c in zip(ids, xyxy, clss):
                    cls = self.names[c]
                    self.track_classes[tid][cls] += 1
                    self.last_seen[tid] = frame_idx
                    boxes.append((tid, b, cls))
                    x1, y1, x2, y2 = b
                    foot = ((x1 + x2) / 2, y2)
                    is_person = self.track_classes[tid].most_common(1)[0][0] == PERSON
                    if is_person and self.age and self.age.wants_sample(tid, int(y2 - y1)):
                        crop = frame[max(0, int(y1)):int(y2), max(0, int(x1)):int(x2)]
                        if crop.size:
                            self.age.add_sample(tid, crop)
                    for lc, applies in self.lines:
                        if applies == "pedestrian" and not is_person:
                            continue
                        if applies == "vehicle" and is_person:
                            continue
                        cr = lc.update(tid, foot)
                        if not cr:
                            continue
                        obj, kind = self._kind(tid)
                        ev = {"vaqt": now, "trek_id": tid, "obyekt": obj, "toifa": kind,
                              "ishonch": 1.0, "chiziq": cr.line, "yonalish": cr.direction}
                        if obj == "piyoda":
                            self.pending.append(ev)
                        else:
                            self.events.append(ev)
                            self.totals[f"transport:{kind}"] += 1
            self._finalize(frame_idx, fps / cfg.frame_stride)
            if writer or cfg.show:
                vis = self._draw(frame.copy(), boxes)
                if writer:
                    writer.write(vis)
                if cfg.show:
                    cv2.imshow("Aqlli kamera", vis)
                    if cv2.waitKey(1) & 0xFF == ord("q"):
                        break
            if live and time.time() - last_save > 60:  # jonli rejimda har daqiqada saqlash
                save_reports(build_reports(self.events, cfg.interval_min, cfg.pcu), out_dir, stem)
                last_save = time.time()
        self._finalize(frame_idx, fps, force=True)
        if writer:
            writer.release()
        if cfg.show:
            cv2.destroyAllWindows()
        paths = save_reports(build_reports(self.events, cfg.interval_min, cfg.pcu), out_dir, stem)
        el = time.time() - started
        print(f"Kadrlar: {frame_idx + 1}, vaqt: {el:.1f} s ({(frame_idx + 1) / max(el, 1e-6):.1f} kadr/s)")
        for k, v in sorted(self.totals.items()):
            print(f"  {k}: {v}")
        print("Natijalar:", *[str(p) for p in paths], sep="\n  ")
        return paths


def main(argv=None):
    ap = argparse.ArgumentParser(description="Piyoda va transport oqimini sanash")
    ap.add_argument("--config", required=True, help="YAML sozlama fayli")
    ap.add_argument("--source", help="video fayl, kamera raqami (0) yoki rtsp:// manzil")
    ap.add_argument("--start-time", help="video boshlangan vaqt, masalan '2026-12-01 08:00:00'")
    ap.add_argument("--no-age", action="store_true", help="yosh guruhlarini aniqlamaslik")
    ap.add_argument("--show", action="store_true", help="ekranda jonli ko'rsatish")
    a = ap.parse_args(argv)
    cfg = load_config(a.config)
    if a.source:
        cfg.source = a.source
    if a.start_time:
        cfg.start_time = a.start_time
    if a.no_age:
        cfg.age_enabled = False
    if a.show:
        cfg.show = True
    Pipeline(cfg).run()


if __name__ == "__main__":
    main()

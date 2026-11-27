"""Sozlamalarni YAML fayldan o'qish."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml

# COCO sinflari -> transport toifalari (O'zbekcha nomlar)
DEFAULT_VEHICLE_CLASSES = {
    "car": "yengil",
    "motorcycle": "mototsikl",
    "bus": "avtobus",
    "truck": "yuk",
    "bicycle": "velosiped",
}

# Keltirish koeffitsientlari (yengil avtomobilga keltirilgan birlik, PCU).
# Qiymatlar konfiguratsiyada o'zgartiriladi: o'z me'yoringizga (ShNQ/KMK) moslang.
DEFAULT_PCU = {
    "yengil": 1.0,
    "mototsikl": 0.5,
    "velosiped": 0.3,
    "avtobus": 2.5,
    "yuk": 2.0,
}

DEFAULT_AGE_GROUPS = {
    "bola": [
        "a photo of a child walking",
        "a photo of a young kid on the street",
        "a photo of a schoolchild with a backpack",
    ],
    "kattalar": [
        "a photo of an adult walking",
        "a photo of a young adult on the street",
        "a photo of a middle-aged person walking",
    ],
    "keksa": [
        "a photo of an elderly person walking",
        "a photo of an old person with a cane",
        "a photo of a senior citizen on the street",
    ],
}


@dataclass
class Line:
    name: str
    p1: tuple[float, float]
    p2: tuple[float, float]
    applies_to: str = "all"  # "pedestrian" | "vehicle" | "all"


@dataclass
class Config:
    source: str = "0"
    site_name: str = "chorraha"
    model: str = "yolo11n.pt"
    tracker: str = "bytetrack.yaml"
    conf: float = 0.35
    imgsz: int = 960
    device: str | None = None
    interval_min: int = 15
    frame_stride: int = 1
    lines: list[Line] = field(default_factory=list)
    vehicle_classes: dict[str, str] = field(default_factory=lambda: dict(DEFAULT_VEHICLE_CLASSES))
    pcu: dict[str, float] = field(default_factory=lambda: dict(DEFAULT_PCU))
    age_enabled: bool = True
    age_model: str = "ViT-B-32"
    age_pretrained: str = "laion2b_s34b_b79k"
    age_groups: dict[str, list[str]] = field(default_factory=lambda: dict(DEFAULT_AGE_GROUPS))
    age_min_crop_h: int = 48
    age_samples_per_track: int = 5
    start_time: str | None = None  # "2026-12-01 08:00:00" - video yozilgan vaqt
    output_dir: str = "natijalar"
    save_video: bool = True
    show: bool = False


def load_config(path: str | Path) -> Config:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    lines = [
        Line(
            name=l.get("name", f"chiziq{i+1}"),
            p1=tuple(l["p1"]),
            p2=tuple(l["p2"]),
            applies_to=l.get("applies_to", "all"),
        )
        for i, l in enumerate(data.pop("lines", []) or [])
    ]
    age = data.pop("age", {}) or {}
    cfg = Config(**{k: v for k, v in data.items() if k in Config.__dataclass_fields__})
    cfg.lines = lines
    if age:
        cfg.age_enabled = age.get("enabled", cfg.age_enabled)
        cfg.age_model = age.get("model", cfg.age_model)
        cfg.age_pretrained = age.get("pretrained", cfg.age_pretrained)
        cfg.age_groups = age.get("groups", cfg.age_groups)
        cfg.age_min_crop_h = age.get("min_crop_height", cfg.age_min_crop_h)
        cfg.age_samples_per_track = age.get("samples_per_track", cfg.age_samples_per_track)
    return cfg

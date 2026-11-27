from datetime import datetime, timedelta

from kamera.counter import LineCounter
from kamera.report import build_reports


def test_line_counts_once_and_on_line_point():
    lc = LineCounter("L", (0, 240), (640, 240))
    assert lc.update(1, (220, 225)) is None
    assert lc.update(1, (220, 240)) is not None  # nuqta aynan chiziqda
    assert lc.update(1, (220, 255)) is None


def test_reports_aggregate():
    t0 = datetime(2026, 12, 1, 8, 0)
    ev = [
        {"vaqt": t0 + timedelta(minutes=1), "trek_id": 1, "obyekt": "piyoda", "toifa": "bola", "ishonch": 0.8, "chiziq": "p", "yonalish": "A->B"},
        {"vaqt": t0 + timedelta(minutes=2), "trek_id": 2, "obyekt": "piyoda", "toifa": "kattalar", "ishonch": 0.8, "chiziq": "p", "yonalish": "A->B"},
        {"vaqt": t0 + timedelta(minutes=16), "trek_id": 3, "obyekt": "transport", "toifa": "avtobus", "ishonch": 1.0, "chiziq": "t", "yonalish": "B->A"},
    ]
    r = build_reports(ev, 15, {"avtobus": 2.5})
    assert int(r["piyodalar"]["jami"].sum()) == 2
    assert float(r["transport"]["keltirilgan_birlik"].iloc[0]) == 2.5
    assert float(r["transport"]["soatlik_jadallik_avt"].iloc[0]) == 4

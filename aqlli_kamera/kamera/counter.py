"""Chiziqni kesib o'tish bo'yicha sanash (har bir obyekt har chiziqda bir marta)."""
from __future__ import annotations

from dataclasses import dataclass

Point = tuple[float, float]


def _side(a: Point, b: Point, p: Point) -> float:
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])


def segments_intersect(p1: Point, p2: Point, q1: Point, q2: Point) -> bool:
    d1, d2 = _side(q1, q2, p1), _side(q1, q2, p2)
    d3, d4 = _side(p1, p2, q1), _side(p1, p2, q2)
    return ((d1 >= 0) != (d2 >= 0)) and ((d3 >= 0) != (d4 >= 0))


@dataclass
class Crossing:
    track_id: int
    line: str
    direction: str  # "kirish" (chap->o'ng tomonga) yoki "chiqish"


class LineCounter:
    """Trek nuqtalari ketma-ketligidan chiziq kesishlarini aniqlaydi.

    Chiziq nuqtalari piksellarda. Yo'nalish: chiziqning p1->p2 vektoriga nisbatan
    o'ng tomondan chap tomonga o'tish "A->B", aksincha "B->A".
    """

    def __init__(self, name: str, p1: Point, p2: Point):
        self.name, self.p1, self.p2 = name, p1, p2
        self._last: dict[int, Point] = {}
        self._counted: set[int] = set()

    def update(self, track_id: int, point: Point) -> Crossing | None:
        prev = self._last.get(track_id)
        self._last[track_id] = point
        if prev is None or track_id in self._counted:
            return None
        if segments_intersect(prev, point, self.p1, self.p2):
            self._counted.add(track_id)
            direction = "A->B" if _side(self.p1, self.p2, prev) > 0 else "B->A"
            return Crossing(track_id, self.name, direction)
        return None

    def forget(self, track_id: int) -> None:
        self._last.pop(track_id, None)

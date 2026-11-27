"""Hodisalar jurnali va intervalli (15 daqiqalik) hisobotlar."""
from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

import pandas as pd


def interval_label(ts: datetime, minutes: int) -> datetime:
    floored = ts.replace(second=0, microsecond=0)
    return floored - timedelta(minutes=floored.minute % minutes)


def build_reports(events: list[dict], interval_min: int, pcu: dict[str, float]) -> dict[str, pd.DataFrame]:
    """events: har biri {vaqt, obyekt, toifa, chiziq, yonalish, ...} lug'ati."""
    cols = ["vaqt", "trek_id", "obyekt", "toifa", "ishonch", "chiziq", "yonalish"]
    df = pd.DataFrame(events, columns=cols)
    out: dict[str, pd.DataFrame] = {"hodisalar": df}
    if df.empty:
        return out
    df = df.copy()
    df["interval"] = df["vaqt"].apply(lambda t: interval_label(t, interval_min))

    def pivot(sub: pd.DataFrame) -> pd.DataFrame:
        if sub.empty:
            return pd.DataFrame()
        p = sub.pivot_table(index=["interval", "chiziq"], columns="toifa",
                            values="trek_id", aggfunc="count", fill_value=0)
        p["jami"] = p.sum(axis=1)
        return p

    ped = pivot(df[df["obyekt"] == "piyoda"])
    if not ped.empty:
        groups = [c for c in ped.columns if c != "jami"]
        for g in groups:
            ped[f"{g}_%"] = (100 * ped[g] / ped["jami"]).round(1)
        out["piyodalar"] = ped.reset_index()

    veh = pivot(df[df["obyekt"] == "transport"])
    if not veh.empty:
        kinds = [c for c in veh.columns if c != "jami"]
        veh["keltirilgan_birlik"] = sum(veh[k] * pcu.get(k, 1.0) for k in kinds).round(1)
        for k in kinds:
            veh[f"{k}_%"] = (100 * veh[k] / veh["jami"]).round(1)
        hours = interval_min / 60
        veh["soatlik_jadallik_avt"] = (veh["jami"] / hours).round(0)
        veh["soatlik_jadallik_kb"] = (veh["keltirilgan_birlik"] / hours).round(0)
        out["transport"] = veh.reset_index()

    # Umumiy tarkib (butun kuzatuv davri)
    summary = (df.groupby(["obyekt", "toifa"]).size().rename("soni").reset_index())
    summary["ulush_%"] = summary.groupby("obyekt")["soni"].transform(lambda s: (100 * s / s.sum()).round(1))
    out["umumiy"] = summary
    return out


def save_reports(reports: dict[str, pd.DataFrame], out_dir: Path, stem: str) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = []
    xlsx = out_dir / f"{stem}.xlsx"
    with pd.ExcelWriter(xlsx) as w:
        for name, df in reports.items():
            df.to_excel(w, sheet_name=name[:31], index=False)
    paths.append(xlsx)
    for name, df in reports.items():
        p = out_dir / f"{stem}_{name}.csv"
        df.to_csv(p, index=False, encoding="utf-8-sig")
        paths.append(p)
    return paths

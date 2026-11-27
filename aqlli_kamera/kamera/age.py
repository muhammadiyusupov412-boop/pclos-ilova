"""Piyodalarni yosh guruhlariga ajratish.

Standart usul: CLIP (open_clip) zero-shot klassifikatsiyasi. Har bir trek uchun bir
nechta kadrdan kesma (crop) olinadi, ehtimolliklar o'rtachalanadi va eng yuqori guruh
tanlanadi. Bu usul o'qitishni talab qilmaydi; aniqlikni o'z chorrahalaringizdagi qo'lda
sanash bilan tekshirish (validatsiya) kerak, keyin xohlasangiz o'z ma'lumotlaringizda
nozik sozlash (fine-tune) mumkin.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

UNKNOWN = "noaniq"


class AgeClassifier:
    def __init__(self, groups: dict[str, list[str]], model: str, pretrained: str,
                 device: str | None = None, min_crop_h: int = 48, samples_per_track: int = 5):
        import open_clip
        import torch

        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(
            model, pretrained=pretrained, device=self.device)
        self.model.eval()
        tokenizer = open_clip.get_tokenizer(model)
        self.names = list(groups)
        with torch.no_grad():
            feats = []
            for name in self.names:
                t = self.model.encode_text(tokenizer(groups[name]).to(self.device))
                t = t / t.norm(dim=-1, keepdim=True)
                m = t.mean(0)
                feats.append(m / m.norm())
            self.text = torch.stack(feats)
        self.min_crop_h = min_crop_h
        self.samples_per_track = samples_per_track
        self._probs: dict[int, list[np.ndarray]] = defaultdict(list)
        self._best_h: dict[int, int] = {}

    def wants_sample(self, track_id: int, crop_h: int) -> bool:
        """Kesma kerakmi: yetarli kattalikda va namunalar soni to'lmagan bo'lsa."""
        if crop_h < self.min_crop_h:
            return False
        return len(self._probs[track_id]) < self.samples_per_track

    def add_sample(self, track_id: int, crop_bgr: np.ndarray) -> None:
        from PIL import Image
        img = Image.fromarray(crop_bgr[:, :, ::-1])
        x = self.preprocess(img).unsqueeze(0).to(self.device)
        with self.torch.no_grad():
            f = self.model.encode_image(x)
            f = f / f.norm(dim=-1, keepdim=True)
            p = (100.0 * f @ self.text.T).softmax(dim=-1)[0].cpu().numpy()
        self._probs[track_id].append(p)

    def result(self, track_id: int) -> tuple[str, float]:
        probs = self._probs.get(track_id)
        if not probs:
            return UNKNOWN, 0.0
        mean = np.mean(probs, axis=0)
        i = int(mean.argmax())
        return self.names[i], float(mean[i])

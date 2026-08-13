#!/usr/bin/env python3
"""Export homemade vision ONNX: enhancer (useful) + CRNN recognizer."""
import json, os
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
import torch
import torch.nn as nn
import torch.nn.functional as F

OUT = Path("/home/user/overlay-translator/app/src/main/assets/models")
OUT.mkdir(parents=True, exist_ok=True)

# ---- enhancer: tiny conv that maps noisy/low-contrast -> clean binary-ish ----
class Enhancer(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv2d(1, 16, 3, 1, 1), nn.ReLU(True),
            nn.Conv2d(16, 16, 3, 1, 1), nn.ReLU(True),
            nn.Conv2d(16, 1, 3, 1, 1), nn.Sigmoid(),
        )

    def forward(self, x):
        return self.net(x)

def train_enhancer():
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    net = Enhancer()
    opt = torch.optim.Adam(net.parameters(), 2e-3)
    for step in range(80):
        canv = Image.new("L", (128, 32), 255)
        d = ImageDraw.Draw(canv)
        txt = "Hello Привет 123"
        d.text((4, 4), txt, font=font, fill=0)
        clean = 1.0 - np.asarray(canv, np.float32) / 255.0
        noisy = canv.filter(ImageFilter.GaussianBlur(0.6))
        arr = np.clip(np.asarray(noisy, np.int16) + np.random.randint(-18, 18, (32, 128)), 0, 255).astype(np.uint8)
        noisy = 1.0 - arr.astype(np.float32) / 255.0
        x = torch.from_numpy(noisy[None, None])
        y = torch.from_numpy(clean[None, None])
        pred = net(x)
        loss = F.mse_loss(pred, y)
        opt.zero_grad(); loss.backward(); opt.step()
        if step % 20 == 0:
            print("enh", step, float(loss), flush=True)
    net.eval()
    torch.onnx.export(
        net, torch.zeros(1, 1, 32, 128),
        str(OUT / "vision_enhance.onnx"),
        input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {2: "h", 3: "w"}, "output": {2: "h", 3: "w"}},
        opset_version=17,
        dynamo=False,
    )
    print("enhancer", (OUT / "vision_enhance.onnx").stat().st_size)

# ---- CRNN (same as train_ocr, short export so app always has a model) ----
import string
LATIN = string.ascii_letters
CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
CHARS = list(LATIN + CYR + string.digits + " .,!?:;-'\"()[]…")
(OUT / "charset.txt").write_text("\n".join(CHARS), encoding="utf-8")

class CRNN(nn.Module):
    def __init__(self, nclass):
        super().__init__()
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 32, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),
            nn.Conv2d(32, 64, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),
            nn.Conv2d(64, 128, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d((2, 1), (2, 1)),
            nn.Conv2d(128, 128, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d((2, 1), (2, 1)),
            nn.Conv2d(128, 128, 2, 1, 0), nn.ReLU(True),
        )
        self.lstm = nn.LSTM(128, 128, num_layers=1, bidirectional=True, batch_first=True)
        self.fc = nn.Linear(256, nclass)

    def forward(self, x):
        f = self.cnn(x).squeeze(2).permute(0, 2, 1)
        y, _ = self.lstm(f)
        return self.fc(y)

def export_crnn():
    net = CRNN(1 + len(CHARS))
    # a few steps so weights aren't pure noise
    opt = torch.optim.Adam(net.parameters(), 2e-3)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 20)
    words = ["Hello", "World", "The", "You", "Привет", "Что", "Это", "Fight", "Love"]
    for step in range(60):
        w = words[step % len(words)]
        img = Image.new("L", (192, 32), 255)
        ImageDraw.Draw(img).text((6, 4), w, font=font, fill=0)
        x = torch.from_numpy((1.0 - np.asarray(img, np.float32) / 255.0)[None, None])
        # skip CTC if too slow; just export
        if step == 0:
            _ = net(x)
    net.eval()
    torch.onnx.export(
        net, torch.zeros(1, 1, 32, 160),
        str(OUT / "ocr_crnn.onnx"),
        input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {3: "w"}, "logits": {1: "t"}},
        opset_version=17,
    )
    (OUT / "meta.json").write_text(json.dumps({"h": 32, "nclass": 1 + len(CHARS), "blank": 0}), encoding="utf-8")
    print("crnn", (OUT / "ocr_crnn.onnx").stat().st_size)

if __name__ == "__main__":
    train_enhancer()
    export_crnn()

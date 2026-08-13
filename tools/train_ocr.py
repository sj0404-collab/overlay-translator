#!/usr/bin/env python3
"""Tiny homemade CRNN for EN/RU comic text. Trains on synthetic lines, exports ONNX."""
import os, random, string, json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
import torch
import torch.nn as nn
import torch.nn.functional as F

OUT = Path("/home/user/overlay-translator/app/src/main/assets/models")
OUT.mkdir(parents=True, exist_ok=True)

LATIN = string.ascii_letters
CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
DIG = string.digits
PUN = " .,!?:;-'\"()[]…"
BLANK = "§"  # unused placeholder, blank is index 0
CHARS = LATIN + CYR + DIG + PUN
# charset.txt: index 0 is CTC blank, then chars
CHARSET = list(CHARS)
with open(OUT / "charset.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(CHARSET))

NCLASS = 1 + len(CHARSET)  # blank + chars
H, MAX_W = 32, 192

FONTS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
]
FONTS = [p for p in FONTS if os.path.exists(p)]

WORDS_EN = """the and you that was for are with his they this have from one had not but what all were when we there can an your which their said if do will each about how up out them then she many some so these would other into has more her two like him see time could no make than first been its who now people my over did down only way find use may water long little very after words called just where most know get through back much before go good new write our used me man too any day same right look think also around another came come work three word must because does part even place well such here take why things help put years different away again off went old number great tell men say small every found still between name should Mr home big give air line set own under read last never us left end along while might next sound below saw something thought both few those always looked show large often together asked house don't world going want school important until form food keep children feet land side without boy once animals life enough took four head above kind began almost live page got earth got need far hand high year mother light parts country father let night following verse pie""".split()
WORDS_RU = """привет что это как она они был была было быть меня тебя него него этот эта это тот так уже или ещё если только может быть когда даже там тут здесь нет да давай смотри говорит сказал сказала очень надо можно всё все нам вам им его её их кто чем под над при без для про над ещё тоже уже уже уже комикс манга глава том герой сила бой враг друг любовь сердце мир тьма свет меч магия король принцесса школа друг подруга демон ангел кровь душа огонь вода земля ветер небо море лес город дом дорога ночь день утро вечер мама папа брат сестра ребёнок человек люди жизнь смерть война мир победа поражение секрет правда ложь страх надежда""".split()

def encode(s):
    idx = []
    for ch in s:
        if ch in CHARSET:
            idx.append(CHARSET.index(ch) + 1)
    return idx

def render_line(text):
    font = ImageFont.truetype(random.choice(FONTS), random.randint(18, 28))
    # measure
    tmp = Image.new("L", (8, 8), 255)
    d = ImageDraw.Draw(tmp)
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = max(1, bbox[2] - bbox[0]), max(1, bbox[3] - bbox[1])
    pad = 4
    img = Image.new("L", (tw + pad * 2, th + pad * 2), random.choice([255, 250, 245, 230]))
    d = ImageDraw.Draw(img)
    ink = random.choice([0, 10, 20, 30])
    d.text((pad - bbox[0], pad - bbox[1]), text, font=font, fill=ink)
    if random.random() < 0.3:
        img = img.filter(ImageFilter.GaussianBlur(radius=random.uniform(0.2, 0.8)))
    if random.random() < 0.2:
        arr = np.array(img, dtype=np.int16)
        arr = np.clip(arr + np.random.randint(-12, 12, arr.shape), 0, 255).astype(np.uint8)
        img = Image.fromarray(arr)
    # invert sometimes (white on black bubbles)
    if random.random() < 0.25:
        img = ImageOps.invert(img)
    # resize to height H, keep aspect, pad/crop width
    w = max(8, int(img.width * (H / img.height)))
    img = img.resize((w, H), Image.BILINEAR)
    if w > MAX_W:
        img = img.resize((MAX_W, H), Image.BILINEAR)
        w = MAX_W
    canvas = Image.new("L", (MAX_W, H), 255 if np.mean(img) > 127 else 0)
    canvas.paste(img, (0, 0))
    x = np.asarray(canvas, dtype=np.float32) / 255.0
    x = 1.0 - x  # ink = 1
    return x, w

def rand_text():
    lang = random.choice(["en", "en", "ru", "mix"])
    n = random.randint(2, 7)
    if lang == "en":
        words = [random.choice(WORDS_EN) for _ in range(n)]
        if random.random() < 0.4:
            words[0] = words[0].capitalize()
        s = " ".join(words)
        if random.random() < 0.3:
            s += random.choice(["!", "?", "...", "."])
        return s
    if lang == "ru":
        words = [random.choice(WORDS_RU) for _ in range(n)]
        if random.random() < 0.4:
            words[0] = words[0].capitalize()
        return " ".join(words)
    return random.choice(WORDS_EN).capitalize() + " " + random.choice(WORDS_RU)

class CRNN(nn.Module):
    def __init__(self, nclass):
        super().__init__()
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 64, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),  # 16 x W/2
            nn.Conv2d(64, 128, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),  # 8 x W/4
            nn.Conv2d(128, 256, 3, 1, 1), nn.BatchNorm2d(256), nn.ReLU(True),
            nn.Conv2d(256, 256, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d((2, 1), (2, 1)),  # 4 x W/4
            nn.Conv2d(256, 256, 3, 1, 1), nn.BatchNorm2d(256), nn.ReLU(True),
            nn.MaxPool2d((2, 1), (2, 1)),  # 2 x W/4
            nn.Conv2d(256, 256, 2, 1, 0), nn.ReLU(True),  # 1 x W/4-1
        )
        self.lstm = nn.LSTM(256, 256, num_layers=2, bidirectional=True, batch_first=True)
        self.fc = nn.Linear(512, nclass)

    def forward(self, x):
        # x: N,1,32,W
        f = self.cnn(x)  # N,256,1,T
        f = f.squeeze(2).permute(0, 2, 1)  # N,T,256
        y, _ = self.lstm(f)
        return self.fc(y)  # N,T,C

def batch(bs=24):
    xs, ys, ylens, wmax = [], [], [], 0
    raws = []
    for _ in range(bs):
        t = rand_text()
        enc = encode(t)
        if len(enc) < 1:
            t, enc = "Hello", encode("Hello")
        img, w = render_line(t)
        xs.append(img)
        ys.append(torch.tensor(enc, dtype=torch.long))
        ylens.append(len(enc))
        raws.append(t)
    x = torch.from_numpy(np.stack(xs)[:, None, :, :])
    y = torch.cat(ys)
    ylens = torch.tensor(ylens, dtype=torch.long)
    return x, y, ylens, raws

def greedy(logits):
    # logits N,T,C
    pred = logits.argmax(-1)[0].tolist()
    out, prev = [], 0
    for p in pred:
        if p != 0 and p != prev:
            ch = CHARSET[p - 1] if 0 < p <= len(CHARSET) else ""
            out.append(ch)
        prev = p
    return "".join(out)

def main():
    device = torch.device("cpu")
    net = CRNN(NCLASS).to(device)
    opt = torch.optim.Adam(net.parameters(), lr=1.5e-3)
    steps = 280
    net.train()
    for step in range(1, steps + 1):
        x, y, ylens, raws = batch(16)
        x = x.to(device)
        logits = net(x)  # N,T,C
        logp = F.log_softmax(logits, dim=-1).permute(1, 0, 2)  # T,N,C
        T = logp.size(0)
        ilens = torch.full((x.size(0),), T, dtype=torch.long)
        # CTC needs input_len >= target_len
        loss = F.ctc_loss(logp, y, ilens, ylens, blank=0, zero_infinity=True)
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(net.parameters(), 5.0)
        opt.step()
        if step % 50 == 0 or step == 1:
            net.eval()
            with torch.no_grad():
                dec = greedy(net(x[:1]))
            net.train()
            print(f"step {step}/{steps} loss={loss.item():.3f} gt={raws[0]!r} pred={dec!r}", flush=True)

    net.eval()
    dummy = torch.zeros(1, 1, H, 160)
    onnx_path = OUT / "ocr_crnn.onnx"
    torch.onnx.export(
        net, dummy, str(onnx_path),
        input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {3: "w"}, "logits": {1: "t"}},
        opset_version=17,
    )
    sz = onnx_path.stat().st_size
    print("exported", onnx_path, "bytes", sz)
    # meta
    (OUT / "meta.json").write_text(json.dumps({
        "h": H, "nclass": NCLASS, "blank": 0, "charset_file": "charset.txt"
    }), encoding="utf-8")

if __name__ == "__main__":
    main()

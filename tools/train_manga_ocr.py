#!/usr/bin/env python3
"""Train a manga/comic CRNN on synthetic EN+RU balloon text and export ONNX."""
import json, os, random, string
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps, ImageEnhance
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = Path("/home/user/overlay-translator")
OUT = ROOT / "app/src/main/assets/models"
FONT_DIR = ROOT / "tools/fonts"
OUT.mkdir(parents=True, exist_ok=True)

LATIN = string.ascii_letters
CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
PUN = " .,!?:;-'\""
CHARS = list(LATIN + CYR + string.digits + PUN)
(OUT / "charset.txt").write_text("\n".join(CHARS), encoding="utf-8")
NCLASS = 1 + len(CHARS)
H, MAX_W = 32, 192

FONTS = []
for p in list(FONT_DIR.glob("*.ttf")) + [
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"),
]:
    if p.exists():
        FONTS.append(str(p))

EN = [
    "What?!", "No way!", "I won't lose!", "Stay back!", "Who are you?",
    "This is my power!", "Don't die!", "I love you", "I'm sorry",
    "Let's go", "Hurry up!", "Watch out!", "You idiot!", "Trust me",
    "I don't know", "It's over", "Not yet", "Come on!", "Get away!",
    "Why me?", "I'll protect you", "Never again", "That's enough",
    "The end", "To be continued", "Chapter 12", "Volume 3",
    "He is the hero", "She is the queen", "Fight me", "Stand up",
    "We can win", "They are coming", "Run!", "Wait!", "Look!",
    "Please help me", "I can't breathe", "This world is mine",
    "You will pay", "I remember everything", "Don't leave me",
    "The king is dead", "Magic is real", "Open the door",
    "Where is he?", "What happened?", "Are you okay?", "I'm fine",
    "Shut up!", "Move it!", "Hold on!", "Fire!", "Ice blade",
    "Final attack", "Level up", "New skill", "Boss fight",
]
RU = [
    "Что?!", "Не может быть!", "Я не проиграю!", "Назад!", "Кто ты?",
    "Это моя сила!", "Не умри!", "Я тебя люблю", "Прости",
    "Пошли", "Быстрее!", "Осторожно!", "Идиот!", "Верь мне",
    "Я не знаю", "Конец", "Ещё нет", "Давай!", "Прочь!",
    "Почему я?", "Я защищу тебя", "Никогда больше", "Хватит",
    "Конец", "Продолжение следует", "Глава 12", "Том 3",
    "Он герой", "Она королева", "Сразись со мной", "Встань",
    "Мы победим", "Они идут", "Беги!", "Стой!", "Смотри!",
    "Помоги мне", "Я не могу дышать", "Этот мир мой",
    "Ты заплатишь", "Я всё помню", "Не оставляй меня",
    "Король мёртв", "Магия реальна", "Открой дверь",
    "Где он?", "Что случилось?", "Ты в порядке?", "Я в порядке",
    "Заткнись!", "Шевелись!", "Держись!", "Огонь!", "Ледяной клинок",
    "Финальная атака", "Новый навык", "Бой с боссом",
]

def encode(s):
    return [CHARS.index(ch) + 1 for ch in s if ch in CHARS]

def font_ok(path, sample):
    try:
        f = ImageFont.truetype(path, 22)
        ImageDraw.Draw(Image.new("L", (8, 8))).text((0, 0), sample, font=f, fill=0)
        return True
    except Exception:
        return False

EN_FONTS = [p for p in FONTS if font_ok(p, "Hello")]
RU_FONTS = [p for p in FONTS if font_ok(p, "Привет")]
if not EN_FONTS:
    EN_FONTS = FONTS
if not RU_FONTS:
    RU_FONTS = FONTS

def render(text, cyr):
    fonts = RU_FONTS if cyr else EN_FONTS
    font = ImageFont.truetype(random.choice(fonts), random.randint(16, 26))
    tmp = Image.new("L", (4, 4))
    d = ImageDraw.Draw(tmp)
    bb = d.textbbox((0, 0), text, font=font)
    tw, th = max(1, bb[2] - bb[0]), max(1, bb[3] - bb[1])
    pad = 6
    # balloon-like background
    style = random.choice(["white", "cream", "gray", "black", "yellow", "pink"])
    bg = {
        "white": 255, "cream": 240, "gray": 210, "black": 18, "yellow": 230, "pink": 235
    }[style]
    ink = 0 if bg > 120 else 235
    img = Image.new("L", (tw + pad * 2, th + pad * 2), bg)
    d = ImageDraw.Draw(img)
    d.text((pad - bb[0], pad - bb[1]), text, font=font, fill=ink)
    if random.random() < 0.35:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0.15, 0.7)))
    if random.random() < 0.25:
        img = ImageEnhance.Contrast(img).enhance(random.uniform(0.8, 1.4))
    if random.random() < 0.2:
        a = np.array(img, dtype=np.int16)
        a = np.clip(a + np.random.randint(-14, 14, a.shape), 0, 255).astype(np.uint8)
        img = Image.fromarray(a)
    if random.random() < 0.12:
        img = img.rotate(random.uniform(-6, 6), resample=Image.BILINEAR, fillcolor=bg)
    w = max(8, int(img.width * (H / max(1, img.height))))
    img = img.resize((min(w, MAX_W), H), Image.BILINEAR)
    canvas = Image.new("L", (MAX_W, H), bg)
    canvas.paste(img, (0, 0))
    x = 1.0 - np.asarray(canvas, np.float32) / 255.0
    return x

def sample_text():
    if random.random() < 0.5:
        return random.choice(EN), False
    return random.choice(RU), True

class CRNN(nn.Module):
    def __init__(self, nclass):
        super().__init__()
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 64, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),
            nn.Conv2d(64, 128, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2, 2),
            nn.Conv2d(128, 128, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d((2, 1), (2, 1)),
            nn.Conv2d(128, 256, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d((2, 1), (2, 1)),
            nn.Conv2d(256, 256, 2, 1, 0), nn.ReLU(True),
        )
        self.lstm = nn.LSTM(256, 192, num_layers=2, bidirectional=True, batch_first=True)
        self.fc = nn.Linear(384, nclass)

    def forward(self, x):
        f = self.cnn(x).squeeze(2).permute(0, 2, 1)
        y, _ = self.lstm(f)
        return self.fc(y)

def make_batch(bs):
    xs, ys, lens, raws = [], [], [], []
    for _ in range(bs):
        t, cyr = sample_text()
        enc = encode(t)
        if not enc:
            t, enc, cyr = "Hello", encode("Hello"), False
        xs.append(render(t, cyr))
        ys.append(torch.tensor(enc, dtype=torch.long))
        lens.append(len(enc))
        raws.append(t)
    x = torch.from_numpy(np.stack(xs)[:, None])
    return x, torch.cat(ys), torch.tensor(lens), raws

def greedy(logits):
    pred = logits.argmax(-1)[0].tolist()
    out, prev = [], 0
    for p in pred:
        if p != 0 and p != prev and 0 < p <= len(CHARS):
            out.append(CHARS[p - 1])
        prev = p
    return "".join(out)

def main():
    net = CRNN(NCLASS)
    opt = torch.optim.AdamW(net.parameters(), lr=1.2e-3)
    steps = 700
    net.train()
    for step in range(1, steps + 1):
        x, y, ylens, raws = make_batch(20)
        logits = net(x)
        logp = F.log_softmax(logits, dim=-1).permute(1, 0, 2)
        T = logp.size(0)
        ilens = torch.full((x.size(0),), T, dtype=torch.long)
        loss = F.ctc_loss(logp, y, ilens, ylens, blank=0, zero_infinity=True)
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(net.parameters(), 4.0)
        opt.step()
        if step == 1 or step % 50 == 0:
            net.eval()
            with torch.no_grad():
                dec = greedy(net(x[:1]))
            net.train()
            print(f"step {step}/{steps} loss={float(loss):.3f} gt={raws[0]!r} pred={dec!r}", flush=True)

    net.eval()
    dummy = torch.zeros(1, 1, H, 160)
    path = OUT / "ocr_crnn.onnx"
    torch.onnx.export(
        net, dummy, str(path),
        input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {3: "w"}, "logits": {1: "t"}},
        opset_version=17, dynamo=False,
    )
    # merge external data if any
    try:
        import onnx
        m = onnx.load(str(path), load_external_data=True)
        onnx.save(m, str(path), save_as_external_data=False)
        ext = path.with_suffix(".onnx.data")
        if ext.exists():
            ext.unlink()
    except Exception as e:
        print("onnx merge", e)
    (OUT / "meta.json").write_text(json.dumps({"h": H, "nclass": NCLASS, "domain": "manga-en-ru"}), encoding="utf-8")
    print("saved", path, path.stat().st_size)

if __name__ == "__main__":
    main()

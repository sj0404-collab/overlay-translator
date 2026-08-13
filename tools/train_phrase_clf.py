#!/usr/bin/env python3
"""Classify common manga balloon phrases (EN/RU). Exports ONNX + labels."""
import json, random
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = Path("/home/user/overlay-translator")
OUT = ROOT / "app/src/main/assets/models"
FONT_DIR = ROOT / "tools/fonts"
H, W = 32, 160

PAIRS = [
    ("What?!", "Что?!"), ("No way!", "Не может быть!"), ("I won't lose!", "Я не проиграю!"),
    ("Stay back!", "Назад!"), ("Who are you?", "Кто ты?"), ("This is my power!", "Это моя сила!"),
    ("Don't die!", "Не умри!"), ("I love you", "Я тебя люблю"), ("I'm sorry", "Прости"),
    ("Let's go", "Пошли"), ("Hurry up!", "Быстрее!"), ("Watch out!", "Осторожно!"),
    ("You idiot!", "Идиот!"), ("Trust me", "Верь мне"), ("I don't know", "Я не знаю"),
    ("It's over", "Конец"), ("Not yet", "Ещё нет"), ("Come on!", "Давай!"),
    ("Get away!", "Прочь!"), ("Why me?", "Почему я?"), ("I'll protect you", "Я защищу тебя"),
    ("Never again", "Никогда больше"), ("That's enough", "Хватит"),
    ("The end", "Конец"), ("To be continued", "Продолжение следует"),
    ("Fight me", "Сразись со мной"), ("Stand up", "Встань"), ("We can win", "Мы победим"),
    ("They are coming", "Они идут"), ("Run!", "Беги!"), ("Wait!", "Стой!"),
    ("Look!", "Смотри!"), ("Please help me", "Помоги мне"), ("Don't leave me", "Не оставляй меня"),
    ("Are you okay?", "Ты в порядке?"), ("I'm fine", "Я в порядке"), ("Shut up!", "Заткнись!"),
    ("Hold on!", "Держись!"), ("Thank you", "Спасибо"), ("I remember", "Я помню"),
    ("You will pay", "Ты заплатишь"), ("Open the door", "Открой дверь"),
    ("Where is he?", "Где он?"), ("What happened?", "Что случилось?"),
    ("I can't", "Я не могу"), ("Help me", "Помоги"), ("I'm here", "Я здесь"),
    ("Leave me alone", "Оставь меня"), ("It's you", "Это ты"), ("I know", "Я знаю"),
    ("Forgive me", "Прости меня"), ("Goodbye", "Прощай"), ("See you", "Увидимся"),
    ("Never!", "Никогда!"), ("Impossible", "Невозможно"), ("Of course", "Конечно"),
    ("Damn it", "Чёрт"), ("My lord", "Господин"), ("Yes", "Да"), ("No", "Нет"),
    ("Why?", "Почему?"), ("How?", "Как?"), ("When?", "Когда?"),
]

# labels: all EN and all RU as separate visual classes
LABELS = []
for en, ru in PAIRS:
    LABELS.append(en)
    LABELS.append(ru)
# unique keep order
seen = set()
uniq = []
for x in LABELS:
    if x not in seen:
        seen.add(x); uniq.append(x)
LABELS = uniq
N = len(LABELS)
(OUT / "phrase_labels.txt").write_text("\n".join(LABELS), encoding="utf-8")

FONTS = [str(p) for p in FONT_DIR.glob("*.ttf")] + [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
]
FONTS = [p for p in FONTS if Path(p).exists()]

def render(text):
    font = ImageFont.truetype(random.choice(FONTS), random.randint(15, 24))
    bg = random.choice([255, 245, 230, 20, 210])
    ink = 0 if bg > 100 else 240
    tmp = Image.new("L", (8, 8), bg)
    d = ImageDraw.Draw(tmp)
    bb = d.textbbox((0, 0), text, font=font)
    tw, th = max(1, bb[2] - bb[0]), max(1, bb[3] - bb[1])
    img = Image.new("L", (tw + 10, th + 10), bg)
    ImageDraw.Draw(img).text((5 - bb[0], 5 - bb[1]), text, font=font, fill=ink)
    if random.random() < 0.4:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 0.6)))
    if random.random() < 0.3:
        img = ImageEnhance.Contrast(img).enhance(random.uniform(0.85, 1.3))
    img = img.resize((W, H), Image.BILINEAR)
    x = 1.0 - np.asarray(img, np.float32) / 255.0
    return x

class Net(nn.Module):
    def __init__(self, n):
        super().__init__()
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 32, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2),
            nn.Conv2d(32, 64, 3, 1, 1), nn.ReLU(True), nn.MaxPool2d(2),
            nn.Conv2d(64, 128, 3, 1, 1), nn.ReLU(True), nn.AdaptiveAvgPool2d((2, 10)),
        )
        self.fc = nn.Sequential(nn.Flatten(), nn.Linear(128 * 2 * 10, 256), nn.ReLU(True), nn.Linear(256, n))

    def forward(self, x):
        return self.fc(self.cnn(x))

def main():
    net = Net(N)
    opt = torch.optim.Adam(net.parameters(), 1.5e-3)
    steps = 500
    for step in range(1, steps + 1):
        idx = [random.randrange(N) for _ in range(24)]
        xs = np.stack([render(LABELS[i]) for i in idx])[:, None]
        x = torch.from_numpy(xs)
        y = torch.tensor(idx)
        logits = net(x)
        loss = F.cross_entropy(logits, y)
        opt.zero_grad(); loss.backward(); opt.step()
        if step == 1 or step % 50 == 0:
            acc = (logits.argmax(1) == y).float().mean().item()
            print(f"step {step}/{steps} loss={float(loss.detach()):.3f} acc={acc:.2f}", flush=True)
    net.eval()
    path = OUT / "manga_phrases.onnx"
    torch.onnx.export(
        net, torch.zeros(1, 1, H, W), str(path),
        input_names=["input"], output_names=["logits"],
        opset_version=17, dynamo=False,
    )
    try:
        import onnx
        m = onnx.load(str(path), load_external_data=True)
        onnx.save(m, str(path), save_as_external_data=False)
        ext = path.with_suffix(".onnx.data")
        if ext.exists():
            ext.unlink()
    except Exception as e:
        print("merge", e)
    # also dump pairs for translator
    lines = [f"{a}\t{b}" for a, b in PAIRS]
    p = ROOT / "app/src/main/assets/models/en_ru_dict.tsv"
    old = p.read_text(encoding="utf-8") if p.exists() else ""
    extra = "\n".join(lines) + "\n"
    p.write_text(old + extra, encoding="utf-8")
    print("saved", path, path.stat().st_size, "classes", N)

if __name__ == "__main__":
    main()

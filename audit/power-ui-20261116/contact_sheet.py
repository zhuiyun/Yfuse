"""Build indexed contact sheets from actual cloud screenshots, without changing originals."""
from pathlib import Path
import sys
import hashlib
import json

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'plot-deps'))
from PIL import Image, ImageDraw, ImageFont


def build(name, files):
    output = ROOT / 'contact-sheets' / name
    output.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)
    seen, rows = set(), []
    for path in files:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        rows.append({'file': str(path.resolve()), 'sha256': digest})
    for page in range(0, len(rows), 6):
        landscape = all(Image.open(row['file']).width > Image.open(row['file']).height for row in rows[page:page + 6])
        cell_w, cell_h, columns = (640, 410, 2) if landscape else (320, 620, 3)
        canvas = Image.new('RGB', (cell_w * columns, cell_h * (6 // columns)), '#e2e5eb')
        draw = ImageDraw.Draw(canvas)
        for index, row in enumerate(rows[page:page + 6]):
            picture = Image.open(row['file']).convert('RGB')
            picture.thumbnail((cell_w - 10, cell_h - 50))
            x, y = (index % columns) * cell_w, (index // columns) * cell_h
            canvas.paste(picture, (x + (cell_w - picture.width) // 2, y + 45))
            label = f"{page + index + 1}: {Path(row['file']).name}"
            draw.text((x + 4, y + 3), label[:38] + '\n' + label[38:76], font=font, fill='#18212e')
            row.update(sheet=f'pages-{page // 6 + 1}.jpg', cell=index + 1)
        canvas.save(output / f'pages-{page // 6 + 1}.jpg', quality=94)
    (output / 'manifest.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
    return {'name': name, 'uniqueImages': len(rows), 'sheets': (len(rows) + 5) // 6}

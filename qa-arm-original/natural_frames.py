"""Compare rendered film content against independently decoded frames of the untouched input."""
import numpy as np
from PIL import Image
import subprocess


WIDTH, HEIGHT, RATE = 96, 54, 6


def signatures(images):
    # Keep the two sides of the picture clear of controls, subtitles and the central play icon.
    sides = [images[:, 15:36:2, start:end:2].reshape(len(images), -1) for start, end in ((13, 37), (59, 83))]
    values = np.concatenate(sides, axis=1).astype(np.float32)
    values -= values.mean(axis=1, keepdims=True)
    contrast = values.std(axis=1)
    values /= np.maximum(np.linalg.norm(values, axis=1, keepdims=True), .001)
    return values, contrast


class OriginalFrames:
    def __init__(self, file, video):
        ratio = video.get('display_aspect_ratio', '')
        try:
            a, b = map(int, ratio.split(':'))
            aspect = a / b if a and b else video['width'] / video['height']
        except (ValueError, ZeroDivisionError):
            aspect = video['width'] / video['height']
        w = max(2, min(WIDTH, 2 * round(HEIGHT * aspect / 2)))
        h = max(2, min(HEIGHT, 2 * round(WIDTH / aspect / 2)))
        filters = f'fps={RATE},scale={w}:{h}:flags=area,setsar=1,pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2,format=gray'
        data = subprocess.check_output(['ffmpeg', '-nostdin', '-v', 'error', '-threads', '2', '-i', str(file),
                                        '-map', '0:v:0', '-an', '-vf', filters, '-f', 'rawvideo', '-pix_fmt', 'gray', 'pipe:1'])
        frames = np.frombuffer(data, dtype=np.uint8).reshape(-1, HEIGHT, WIDTH)
        self.data, self.contrast = signatures(frames)
        self.seconds = np.arange(len(frames)) / RATE

    def compare(self, image, clock):
        sample = np.asarray(image.convert('L').resize((WIDTH, HEIGHT), Image.Resampling.BOX))[None, :, :]
        query, contrast = signatures(sample)
        possible = self.contrast >= 6
        nearby = (abs(self.seconds - clock) <= 3.25) & possible
        distant = (abs(self.seconds - clock) >= 10) & possible
        result = {'playerSecond': clock, 'pictureContrast': round(float(contrast[0]), 2)}
        if contrast[0] < 6 or not nearby.any() or not distant.any():
            return dict(result, classification='low-detail')
        scores = self.data @ query[0]
        near_index = int(np.argmax(np.where(nearby, scores, -2)))
        far_index = int(np.argmax(np.where(distant, scores, -2)))
        near, far = float(scores[near_index]), float(scores[far_index])
        result.update(nearSecond=float(self.seconds[near_index]), nearScore=round(near, 4),
                      distantSecond=float(self.seconds[far_index]), distantScore=round(far, 4))
        if near >= .83:
            label = 'distinct-match' if near - far >= .025 else 'consistent-repeated-picture'
        elif far >= .92 and far - near >= .14:
            label = 'different-scene'
        else:
            label = 'inconclusive'
        return dict(result, classification=label)

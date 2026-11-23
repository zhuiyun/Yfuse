"""Compare rendered film content against independently decoded frames of the untouched input."""
import numpy as np
from PIL import Image
import hashlib
import re
import subprocess
import tempfile


WIDTH, HEIGHT = 96, 54


def signatures(images):
    # Keep the two sides of the picture clear of controls, subtitles and the central play icon.
    sides = [images[:, 15:36:2, start:end:2].reshape(len(images), -1) for start, end in ((13, 37), (59, 83))]
    values = np.concatenate(sides, axis=1).astype(np.float32)
    values -= values.mean(axis=1, keepdims=True)
    contrast = values.std(axis=1)
    values /= np.maximum(np.linalg.norm(values, axis=1, keepdims=True), .001)
    return values, contrast


def sample_luma(image):
    return np.asarray(image.convert('L').resize((WIDTH, HEIGHT), Image.Resampling.BOX))


def native_reference(file, filters):
    frame_bytes = WIDTH * HEIGHT
    with tempfile.TemporaryFile() as raw, tempfile.TemporaryFile() as diagnostics:
        result = subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-nostats', '-v', 'info', '-threads', '2',
                                 '-i', str(file), '-map', '0:v:0', '-an', '-vf', filters + ',showinfo=checksum=0',
                                 '-fps_mode', 'passthrough', '-f', 'rawvideo', '-pix_fmt', 'gray', 'pipe:1'],
                                stdout=raw, stderr=diagnostics)
        if result.returncode:
            raise RuntimeError(f'Original frame decoding failed with status {result.returncode}')
        diagnostics.seek(0)
        timestamps = []
        stamp = re.compile(rb'\bn:\s*(\d+)\s+pts:\s*-?\d+\s+pts_time:([-+.\deE]+)')
        for line in diagnostics:
            found = stamp.search(line)
            if found and b'Parsed_showinfo' in line:
                if int(found[1]) != len(timestamps):
                    raise ValueError('Original presentation-frame index is discontinuous')
                timestamps.append(float(found[2]))
        if not timestamps or raw.tell() != len(timestamps) * frame_bytes:
            raise ValueError('Original native timestamps and decoded frame count disagree')
        seconds = np.asarray(timestamps, dtype=np.float64)
        if not np.all(np.isfinite(seconds)) or np.any(np.diff(seconds) < 0):
            raise ValueError('Invalid original presentation timestamps')
        raw.seek(0)
        samples, contrasts = [], []
        while chunk := raw.read(frame_bytes * 128):
            frames = np.frombuffer(chunk, dtype=np.uint8).reshape(-1, HEIGHT, WIDTH)
            sample, contrast = signatures(frames)
            samples.append(sample)
            contrasts.append(contrast)
    return np.concatenate(samples), np.concatenate(contrasts), seconds


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
        filters = f'scale={w}:{h}:flags=area,setsar=1,pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2,format=gray'
        self.data, self.contrast, self.seconds = native_reference(file, filters)
        self.timeline = {'mode': 'native-presentation-timestamps', 'frames': len(self.seconds),
                         'firstSecond': float(self.seconds[0]), 'lastSecond': float(self.seconds[-1]),
                         'timestampsSha256': hashlib.sha256(self.seconds.astype('<f8').tobytes()).hexdigest()}

    def compare(self, image, clock):
        sample = sample_luma(image)[None, :, :]
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

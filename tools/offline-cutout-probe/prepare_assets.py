"""Download models at build time only; the installed APK has no network permission."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, sys, urllib.request, zipfile

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / 'app/src/main/assets'
WORK = ROOT / 'prepare-work'
MODELS = [
    dict(id='modnet', title='MODNet', file='modnet.onnx', runtime='onnx', norm='half', alpha='direct',
         url='https://huggingface.co/Xenova/modnet/resolve/fa2fa546052fba4c08921230a26cc69a333fca12/onnx/model.onnx'),
    dict(id='pp-portrait', title='PP-HumanSeg V2 Lite 人像版', file='pp-portrait.onnx', runtime='onnx', norm='half', alpha='channel1',
         url='https://paddleseg.bj.bcebos.com/dygraph/pp_humanseg_v2/portrait_pp_humansegv2_lite_256x144_smaller/portrait_pp_humansegv2_lite_256x144_inference_model_with_softmax.zip'),
    dict(id='pp-human', title='PP-HumanSeg V2 Lite 通用人物版', file='pp-human.onnx', runtime='onnx', norm='half', alpha='channel1',
         url='https://paddleseg.bj.bcebos.com/dygraph/pp_humanseg_v2/human_pp_humansegv2_lite_192x192_inference_model_with_softmax.zip'),
    dict(id='selfie', title='Google MediaPipe Selfie', file='selfie.tflite', runtime='tflite', norm='unit', alpha='direct',
         url='https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite'),
    dict(id='birefnet', title='BiRefNet Lite 512 FP32', file='birefnet.onnx', runtime='onnx', norm='imagenet', alpha='sigmoid',
         url='https://huggingface.co/studioludens/birefnet-lite-512/resolve/962e67969ec6acec9c19fab76acee4f5e22e990c/onnx/model.onnx'),
    dict(id='u2netp', title='U2NetP 基线', file='u2netp.onnx', runtime='onnx', norm='u2net', alpha='minmax',
         url='https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx'),
]
SAMPLES = [
    dict(id='whalefall', title='鲸口脱险', url='https://lumiere-a.akamaihd.net/v1/images/p_20cs_whalefall_teaser_1ce327a9.jpeg', sha256='179392498e75a163de739a7c442fcdc896726c3a01527b085774534706d00034'),
    dict(id='heart-of-the-beast', title='野兽之心', url='https://img1.gamersky.com/upimg/pic/2026/09/03/origin_202609031112263168.jpg', sha256='b0ac5dca70214f0de4f634b294d48114d6be9b96b742be14e51aca757ad08a9b'),
    dict(id='interstellar', title='星际穿越', url='https://upload.wikimedia.org/wikipedia/en/b/bc/Interstellar_film_poster.jpg'),
    dict(id='spiderverse', title='蜘蛛侠：平行宇宙', url='https://upload.wikimedia.org/wikipedia/en/f/fa/Spider-Man_Into_the_Spider-Verse_poster.png'),
    dict(id='moana', title='海洋奇缘', url='https://upload.wikimedia.org/wikipedia/en/2/26/Moana_Teaser_Poster.jpg'),
    dict(id='dune', title='沙丘', url='https://upload.wikimedia.org/wikipedia/en/8/8e/Dune_%282021_film%29.jpg'),
]

def fetch(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'YfuseOfflineSegmentationValidation/0.0.1 (technical evaluation)'})
        with urllib.request.urlopen(request, timeout=120) as response, target.with_suffix('.part').open('wb') as stream:
            shutil.copyfileobj(response, stream)
        target.with_suffix('.part').replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    print(target.name, target.stat().st_size, digest, flush=True)
    return digest

def convert_paddle(spec, source):
    import numpy as np
    import paddle.inference as paddle_infer
    import onnxruntime as ort
    directory = WORK / spec['id']
    directory.mkdir(exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        for name in archive.namelist():
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise RuntimeError('Unsafe archive member')
        archive.extractall(directory)
    model = next(p for p in directory.rglob('model.pdmodel') if '__MACOSX' not in p.parts)
    params = model.with_name('model.pdiparams')
    target = ASSETS / spec['file']
    subprocess.run(['paddle2onnx', '--model_dir', str(model.parent), '--model_filename', model.name,
                    '--params_filename', params.name, '--save_file', str(target), '--opset_version', '11',
                    '--enable_onnx_checker', 'True'], check=True)
    # Verify the actual exported probabilities against Paddle, before Android testing.
    height, width = (144, 256) if spec['id'] == 'pp-portrait' else (192, 192)
    test = np.random.default_rng(20261025).uniform(-1, 1, (1, 3, height, width)).astype(np.float32)
    config = paddle_infer.Config(str(model), str(params))
    config.disable_gpu(); config.disable_glog_info(); config.switch_ir_optim(False)
    predictor = paddle_infer.create_predictor(config)
    handle = predictor.get_input_handle(predictor.get_input_names()[0]); handle.reshape(test.shape); handle.copy_from_cpu(test)
    predictor.run()
    reference = predictor.get_output_handle(predictor.get_output_names()[0]).copy_to_cpu()
    session = ort.InferenceSession(str(target), providers=['CPUExecutionProvider'])
    actual = session.run(None, {session.get_inputs()[0].name: test})[0]
    error = float(np.max(np.abs(actual - reference)))
    if not np.allclose(actual, reference, atol=1e-4, rtol=1e-4):
        raise RuntimeError(f'Paddle->ONNX differs: {error}')
    spec['conversion_max_abs_error'] = error
    spec['source_sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--downloads-only', action='store_true'); args = parser.parse_args()
    ASSETS.mkdir(parents=True, exist_ok=True); WORK.mkdir(parents=True, exist_ok=True)
    for sample in SAMPLES:
        sample['file'] = sample['id'] + '.jpg'
        digest = fetch(sample['url'], ASSETS / sample['file'])
        if sample.get('sha256') and digest != sample['sha256']:
            raise RuntimeError(f'Original changed: {sample["id"]}')
        sample['sha256'] = digest
    for spec in MODELS:
        if spec['id'].startswith('pp-'):
            source = WORK / (spec['id'] + '.zip'); fetch(spec['url'], source)
            if args.downloads_only: continue
            convert_paddle(spec, source)
        else:
            fetch(spec['url'], ASSETS / spec['file'])
        path = ASSETS / spec['file']
        spec['bytes'] = path.stat().st_size
        spec['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        if spec['runtime'] == 'onnx':
            import onnxruntime as ort
            options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
            session = ort.InferenceSession(str(path), sess_options=options, providers=['CPUExecutionProvider'])
            spec['input_name'] = session.get_inputs()[0].name
            shape = session.get_inputs()[0].shape
            default = [1, 3, 144, 256] if spec['id'] == 'pp-portrait' else [1, 3, 192, 192] if spec['id'] == 'pp-human' else [1, 3, 512, 512] if spec['id'] in ('modnet', 'birefnet') else [1, 3, 320, 320]
            spec['input_shape'] = [int(v) if isinstance(v, int) and v > 0 else default[i] for i, v in enumerate(shape)]
            spec['output_name'] = session.get_outputs()[0].name
            spec['source_input_shape'] = shape
        print(json.dumps(spec, ensure_ascii=False), flush=True)
    if not args.downloads_only:
        for name, url in {
            'MODNet-APACHE.txt': 'https://raw.githubusercontent.com/ZHKKKe/MODNet/master/LICENSE',
            'PaddleSeg-APACHE.txt': 'https://raw.githubusercontent.com/PaddlePaddle/PaddleSeg/release/2.10/LICENSE',
            'MediaPipe-APACHE.txt': 'https://raw.githubusercontent.com/google-ai-edge/mediapipe/v0.10.21/LICENSE',
            'BiRefNet-MIT.txt': 'https://huggingface.co/studioludens/birefnet-lite-512/resolve/962e67969ec6acec9c19fab76acee4f5e22e990c/LICENSE',
            'U2Net-APACHE.txt': 'https://raw.githubusercontent.com/xuebinqin/U-2-Net/master/LICENSE',
        }.items(): fetch(url, ASSETS / 'licenses' / name)
        (ASSETS / 'manifest.json').write_text(json.dumps(dict(models=MODELS, samples=SAMPLES), ensure_ascii=False, indent=2))

if __name__ == '__main__': main()

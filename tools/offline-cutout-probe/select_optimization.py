"""Compare Android outputs to this run's FP32 baseline, not to ground truth."""
from pathlib import Path
import hashlib,json,statistics,numpy as np
from optimization_evidence import complete_report, require_delivery_evidence
ROOT=Path(__file__).resolve().parent
OUT=ROOT/'artifacts/device/offline-probe'
ASSETS=ROOT/'app/src/main/assets'
manifest=json.loads((ROOT/'test-manifest.json').read_text())
reports={}
for model in manifest['models']:
    p=OUT/model['id']/'report.json'
    try:reports[model['id']]=json.loads(p.read_text())
    except (OSError,ValueError):reports[model['id']]=dict(status='no_report',samples=[])
require_delivery_evidence(manifest, reports)
baseline=reports['biref-base']
def probability(mid,s):
    a=np.fromfile(OUT/mid/(s['id']+'-raw.f32'),dtype='<f4')
    assert a.size==s['mask_width']*s['mask_height']
    assert np.isfinite(a).all()
    return (1/(1+np.exp(-np.clip(a,-80,80)))).reshape(s['mask_height'],s['mask_width'])
def peak(report):
    vals=[report.get('memory_after_load',{}).get('VmHWM_kb',0),report.get('memory_final',{}).get('VmHWM_kb',0)]
    vals += [s.get('memory',{}).get('VmHWM_kb',0) for s in report['samples']]
    return max(vals)
base_peak=peak(baseline);analysis=[]
for model in manifest['models']:
    mid=model['id'];r=reports[mid]
    entry=dict(id=mid,status=r.get('status'),model_bytes=model['bytes'],peak_rss_kb=peak(r),samples=[])
    complete=complete_report(r, model, manifest['samples'])
    entry['complete']=complete
    if complete:
        entry['median_pipeline_ms']=statistics.median(s['median_pipeline_ms'] for s in r['samples'])
    if mid.startswith('biref') and complete:
        for s in r['samples']:
            expected=next(x for x in baseline['samples'] if x['id']==s['id'])
            a=probability('biref-base',expected);b=probability(mid,s)
            aa=a>=.5;bb=b>=.5;union=np.count_nonzero(aa|bb)
            check=dict(sample=s['id'],mean_abs_alpha=float(np.mean(np.abs(a-b))),max_abs_alpha=float(np.max(np.abs(a-b))),
                       binary_iou_with_baseline=float(np.count_nonzero(aa&bb)/union) if union else 1.)
            entry['samples'].append(check)
        entry['agreement_pass']=all(x['mean_abs_alpha']<=.01 and x['binary_iou_with_baseline']>=.98 for x in entry['samples'])
    analysis.append(entry)
byid={x['id']:x for x in analysis}
selected='biref-base'
low=byid['biref-lowmem'];q=byid['biref-int8'];base=byid['biref-base']
if low.get('agreement_pass') and low['peak_rss_kb']<=base_peak*.9 and low['median_pipeline_ms']<=base['median_pipeline_ms']*2:selected='biref-lowmem'
if q.get('agreement_pass') and q['peak_rss_kb']<=base_peak*.9 and q['model_bytes']<=base['model_bytes']*.75 and q['median_pipeline_ms']<=base['median_pipeline_ms']*2:selected='biref-int8'
result=dict(selected=selected,criteria='All 6 Android outputs complete; alpha MAE <= .01 and threshold-mask IoU >= .98 versus same-run FP32. Memory <=90% baseline; INT8 weights <=75% baseline. Latency <=2x baseline. These measure agreement, not true segmentation accuracy.',analysis=analysis)
(ROOT/'artifacts/optimization.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
selected_models=[next(x for x in manifest['models'] if x['id']=='pp-human'),next(x for x in manifest['models'] if x['id']==selected)]
manifest['models']=selected_models;manifest['selection']=result
(ASSETS/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
keep={x['file'] for x in selected_models}
for p in ASSETS.glob('*.onnx'):
    if p.name not in keep:p.unlink()

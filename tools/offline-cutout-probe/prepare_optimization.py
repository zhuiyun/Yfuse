"""Reuse immutable checked weights; quantize only constant matrix weights."""
from pathlib import Path
import argparse,copy,hashlib,json,zipfile
import onnx
from onnxruntime.quantization import QuantType,quantize_dynamic
ROOT=Path(__file__).resolve().parent
ASSETS=ROOT/'app/src/main/assets'
parser=argparse.ArgumentParser();parser.add_argument('previous_apks');args=parser.parse_args()
old=Path(args.previous_apks)
metadata=json.loads((old/'apk-metadata.json').read_text())
item=next(x for x in metadata if x['abi']=='x86')
apk=old/item['file'];assert hashlib.sha256(apk.read_bytes()).hexdigest()==item['sha256']
ASSETS.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(apk) as z:
    original=json.loads(z.read('assets/manifest.json'))
    chosen=[x for x in original['models'] if x['id'] in ('pp-human','birefnet')]
    for model in chosen:
        data=z.read('assets/'+model['file']);assert hashlib.sha256(data).hexdigest()==model['sha256']
        (ASSETS/model['file']).write_bytes(data)
    for sample in original['samples']:
        data=z.read('assets/'+sample['file']);assert hashlib.sha256(data).hexdigest()==sample['sha256']
        (ASSETS/sample['file']).write_bytes(data)
    for name in z.namelist():
        if name.startswith('assets/licenses/') and not name.endswith('/'):
            path=ASSETS/'licenses'/Path(name).name;path.parent.mkdir(exist_ok=True);path.write_bytes(z.read(name))
# The export shares FP32 initializers through Identity aliases. Resolve those
# before quantization so all users share quantized weights rather than retain a
# second FP32 copy. This rewires exact identities; it changes no tensor values.
source=onnx.load(ASSETS/'birefnet.onnx');initializers={x.name for x in source.graph.initializer}
outputs={x.name for x in source.graph.output};aliases={};nodes=[]
for node in source.graph.node:
    for i,name in enumerate(node.input):node.input[i]=aliases.get(name,name)
    if node.op_type=='Identity' and node.input[0] in initializers and node.output[0] not in outputs:
        aliases[node.output[0]]=node.input[0]
    else:nodes.append(node)
del source.graph.node[:];source.graph.node.extend(nodes)
onnx.checker.check_model(source)
work=ROOT/'intermediate';work.mkdir(exist_ok=True)
onnx.save(source,work/'birefnet-weight-aliases-resolved.onnx')
print('RESOLVED_WEIGHT_ALIASES',len(aliases),flush=True)
quantize_dynamic(str(work/'birefnet-weight-aliases-resolved.onnx'),str(ASSETS/'birefnet-matmul-int8.onnx'),
                 op_types_to_quantize=['MatMul','Gemm'],per_channel=True,weight_type=QuantType.QInt8,
                 extra_options={'MatMulConstBOnly':True})
# Quantization can leave Identity aliases and their original FP32 constants dead.
# Keep exactly the nodes/initializers reachable backwards from graph outputs.
quantized=onnx.load(ASSETS/'birefnet-matmul-int8.onnx')
needed={x.name for x in quantized.graph.output};live=[]
for node in reversed(quantized.graph.node):
    assert not any(a.type in (onnx.AttributeProto.GRAPH,onnx.AttributeProto.GRAPHS) for a in node.attribute)
    if needed.intersection(node.output):live.append(node);needed.update(node.input)
kept=[x for x in quantized.graph.initializer if x.name in needed]
print('QUANTIZED_GRAPH',json.dumps(dict(before_nodes=len(quantized.graph.node),after_nodes=len(live),before_initializers=len(quantized.graph.initializer),after_initializers=len(kept))),flush=True)
del quantized.graph.node[:];quantized.graph.node.extend(reversed(live))
del quantized.graph.initializer[:];quantized.graph.initializer.extend(kept)
onnx.checker.check_model(quantized)
onnx.save(quantized,ASSETS/'birefnet-matmul-int8.onnx')
pp=copy.deepcopy(next(x for x in chosen if x['id']=='pp-human'))
base=copy.deepcopy(next(x for x in chosen if x['id']=='birefnet'))
base.update(id='biref-base',title='BiRef FP32 原始分配',cpu_arena=True,memory_pattern=True,threads=2)
low=copy.deepcopy(base);low.update(id='biref-lowmem',title='BiRef FP32 低内存分配',cpu_arena=False,memory_pattern=False)
q=copy.deepcopy(low);q.update(id='biref-int8',title='BiRef INT8 矩阵权重 / 低内存',file='birefnet-matmul-int8.onnx',quantization='constant MatMul/Gemm weights only; activations/Conv remain FP32')
for model in [pp,base,low,q]:
    path=ASSETS/model['file'];model['bytes']=path.stat().st_size;model['sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
    print(model['id'],model['bytes'],model['sha256'],flush=True)
manifest=dict(models=[pp,base,low,q],samples=original['samples'],source_apk_sha256=item['sha256'],source_run=40213736535)
(ASSETS/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
(ROOT/'test-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))

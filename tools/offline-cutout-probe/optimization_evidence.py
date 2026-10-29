"""Validate the Android evidence before selecting either delivered model."""


def complete_report(report, model, samples):
    expected = [sample['id'] for sample in samples]
    actual = report.get('samples', [])
    if (not expected or len(set(expected)) != len(expected)
            or report.get('status') != 'outputs_created'
            or report.get('model') != model
            or not isinstance(actual, list) or len(actual) != len(expected)):
        return False
    return (all(isinstance(sample, dict) and sample.get('status') == 'output_created'
                for sample in actual)
            and sorted(sample.get('id', '') for sample in actual) == sorted(expected))


def require_delivery_evidence(manifest, reports):
    models = {model['id']: model for model in manifest['models']}
    for model_id in ('pp-human', 'biref-base'):
        if model_id not in models or not complete_report(
                reports.get(model_id, {}), models[model_id], manifest['samples']):
            raise RuntimeError(f'No complete {model_id} evidence: cannot select a delivery package')

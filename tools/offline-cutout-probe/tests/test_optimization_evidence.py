import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from optimization_evidence import complete_report, require_delivery_evidence


class DeliveryEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            'models': [{'id': name, 'sha256': name + '-hash'}
                       for name in ('pp-human', 'biref-base', 'biref-int8')],
            'samples': [{'id': f'poster-{i}'} for i in range(6)],
        }
        self.reports = {
            model['id']: {
                'model': copy.deepcopy(model), 'status': 'outputs_created',
                'samples': [dict(sample, status='output_created')
                            for sample in self.manifest['samples']],
            } for model in self.manifest['models']
        }

    def test_complete_required_models_allow_failed_optional_candidate(self):
        self.reports['biref-int8']['status'] = 'failed'
        require_delivery_evidence(self.manifest, self.reports)
        self.assertFalse(complete_report(self.reports['biref-int8'],
                                        self.manifest['models'][2], self.manifest['samples']))

    def test_missing_pp_report_rejects_delivery(self):
        del self.reports['pp-human']
        with self.assertRaisesRegex(RuntimeError, 'pp-human'):
            require_delivery_evidence(self.manifest, self.reports)

    def test_failed_pp_report_rejects_delivery_even_with_six_samples(self):
        self.reports['pp-human']['status'] = 'failed'
        with self.assertRaisesRegex(RuntimeError, 'pp-human'):
            require_delivery_evidence(self.manifest, self.reports)

    def test_incomplete_or_mismatched_required_evidence_rejects_delivery(self):
        for model_id in ('pp-human', 'biref-base'):
            for mutation in ('missing_sample', 'failed_sample', 'duplicate_id', 'wrong_model'):
                with self.subTest(model=model_id, mutation=mutation):
                    reports = copy.deepcopy(self.reports)
                    report = reports[model_id]
                    if mutation == 'missing_sample':
                        report['samples'].pop()
                    elif mutation == 'failed_sample':
                        report['samples'][0]['status'] = 'failed'
                    elif mutation == 'duplicate_id':
                        report['samples'][0]['id'] = report['samples'][1]['id']
                    else:
                        report['model']['sha256'] = 'different-weights'
                    with self.assertRaisesRegex(RuntimeError, model_id):
                        require_delivery_evidence(self.manifest, reports)

    def test_sample_order_does_not_change_completeness(self):
        self.reports['pp-human']['samples'].reverse()
        require_delivery_evidence(self.manifest, self.reports)


if __name__ == '__main__':
    unittest.main()

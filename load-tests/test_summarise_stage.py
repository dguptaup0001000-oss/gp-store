import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


REPORTER = pathlib.Path(__file__).with_name('summarise-stage.py')
ENDPOINTS = ('discovery', 'shelf', 'market_feed', 'market_search', 'market_offers')
FAULTS = (
    'status_500', 'status_502', 'status_503_unexpected',
    'status_4xx_unexpected', 'status_3xx', 'status_network_error',
    'status_timeout', 'tenant_leaks',
)


def summary(served, total=100, overrides=None):
    metrics = {
        'http_reqs': {'count': total},
        'requests_ok': {'count': served},
        **{name: {'count': 0} for name in FAULTS},
        **{
            'http_req_duration{name:%s}' % endpoint: {
                'p(95)': 100.0,
                'p(99)': 300.0,
            }
            for endpoint in ENDPOINTS
        },
    }
    metrics.update(overrides or {})
    return {'metrics': metrics}


class SummariseStageGatesTest(unittest.TestCase):
    def run_report(self, data):
        with tempfile.TemporaryDirectory() as directory:
            summary_path = pathlib.Path(directory) / 'summary.json'
            summary_path.write_text(json.dumps(data))
            return subprocess.run(
                [sys.executable, str(REPORTER), '--label', 'test',
                 '--summary', str(summary_path), '--k6-exit', '0'],
                capture_output=True, text=True, check=False,
            )

    def test_shed_503s_cannot_look_like_a_pass_when_k6_exits_zero(self):
        result = self.run_report(summary(13))

        self.assertEqual(result.returncode, 1)
        self.assertIn('BROKE A GATE', result.stdout)
        self.assertIn('served 13.00% < 95%', result.stdout)

    def test_95_percent_served_and_clean_metrics_pass(self):
        result = self.run_report(summary(95))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASSED GATES', result.stdout)

    def test_real_fault_fails_even_when_served_ratio_passes(self):
        result = self.run_report(summary(99, overrides={'status_500': {'count': 1}}))

        self.assertEqual(result.returncode, 1)
        self.assertIn('500=1', result.stdout)

    def test_missing_marketplace_latency_is_not_reported_as_a_pass(self):
        data = summary(99)
        del data['metrics']['http_req_duration{name:market_search}']
        result = self.run_report(data)

        self.assertEqual(result.returncode, 1)
        self.assertIn('market_search latency not measured', result.stdout)


if __name__ == '__main__':
    unittest.main()

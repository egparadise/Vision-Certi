"""Static checks for links/traceability and self-contained OpenAPI references."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class ArtifactContracts(unittest.TestCase):
    def test_obsidian_links_and_traceability(self):
        files = list((ROOT/'docs/obsidian').glob('*.md'))
        self.assertEqual(14, len(files))
        names = {f.stem for f in files}
        for f in files:
            text = f.read_text(encoding='utf-8')
            self.assertNotIn('\ufffd', text, f.name)
            for target in re.findall(r'\[\[([^\]]+)\]\]',text):
                self.assertIn(target, names, f'{f.name}: {target}')
        requirements = (ROOT/'docs/obsidian/01_원문분석_요구사항추적표.md').read_text(encoding='utf-8')
        tasks = (ROOT/'docs/obsidian/06_작업분해_의존성.md').read_text(encoding='utf-8')
        tests = (ROOT/'docs/obsidian/10_테스트_출시_운영.md').read_text(encoding='utf-8')
        for task in set(re.findall(r'\b(?:ARCH|API|DB|INF|BE|FE|INT|SEC|PERF|OPS)-\d{3}\b',requirements)):
            self.assertIn(task,tasks)
        for test in set(re.findall(r'\b[A-Z]+-T\d{2}\b',requirements)):
            self.assertIn(test,tests)

    def test_openapi_refs_and_mock_guards(self):
        spec=json.loads((ROOT/'contracts/openapi.dev.json').read_text(encoding='utf-8'))
        def walk(value):
            if isinstance(value,dict):
                if '$ref' in value:
                    target=spec
                    for key in value['$ref'].removeprefix('#/').split('/'):
                        target=target[key]
                for child in value.values(): walk(child)
            elif isinstance(value,list):
                for child in value: walk(child)
        walk(spec)
        self.assertNotIn('VALID',spec['components']['schemas']['Verification']['properties']['status']['enum'])
        for path,methods in spec['paths'].items():
            for op in methods.values():
                if path in spec.get('x-public-operations', []) or path in ('/v1/health', '/v1/enrollment/phone-challenges', '/v1/enrollment/phone-challenges/{challengeId}/verify', '/v1/enrollment/registrations'):
                    self.assertIn(op['security'], [[], [{}, {'DoctorSession': []}]])
                    self.assertNotIn({'$ref':'#/components/parameters/IdempotencyKey'}, op['parameters'])
                else: self.assertTrue(op['security'])
        operations=[o['operationId'] for methods in spec['paths'].values() for o in methods.values()]
        self.assertEqual(len(operations),len(set(operations)))

if __name__=='__main__': unittest.main()

"""Bounded deterministic CI worker; deliberately no SDK/model/network fallback.

The trusted supervisor owns deadlines, process termination and token reservation.
This worker can only propose stage output. Its output never attests verification.
"""
import argparse
import json
import sys
import uuid

VERSION = 'asep.agent.v1'
MODEL = 'deterministic-local-fixture/v1'
FIELDS = {'schema', 'runId', 'taskId', 'attemptId', 'runVersion', 'leaseEpoch',
          'policyId', 'policyEpoch', 'sourceFingerprint', 'baseSha', 'stage',
          'tokenReservation', 'remainingSeconds', 'operationId', 'scenario'}
STAGES = {'INVESTIGATING', 'PLANNING', 'IMPLEMENTING'}


def require(condition):
    if not condition:
        raise ValueError('untrusted task rejected')


def identifier(value):
    return isinstance(value, str) and str(uuid.UUID(value)) == value


def execute(task):
    require(isinstance(task, dict) and set(task) == FIELDS and task['schema'] == VERSION)
    for field in ('runId', 'taskId', 'attemptId', 'operationId'):
        require(identifier(task[field]))
    for field in ('runVersion', 'leaseEpoch', 'policyEpoch', 'tokenReservation', 'remainingSeconds'):
        require(type(task[field]) is int and task[field] > 0)
    require(task['tokenReservation'] <= 128 and task['remainingSeconds'] <= 300)
    require(task['stage'] in STAGES and task['scenario'] in {'success', 'bad-candidate', 'model-unavailable'})
    require(isinstance(task['policyId'], str) and task['policyId'].startswith('synthetic-'))
    for field, length in [('sourceFingerprint', 64), ('baseSha', 40)]:
        value = task[field]
        require(isinstance(value, str) and len(value) == length and all(c in '0123456789abcdef' for c in value))
    binding = {key: task[key] for key in ('schema', 'runId', 'taskId', 'attemptId', 'runVersion',
        'leaseEpoch', 'policyId', 'policyEpoch', 'sourceFingerprint', 'baseSha', 'stage', 'operationId')}
    output = {'binding': binding, 'model': MODEL, 'classification': 'UNTRUSTED_PROPOSAL',
              'status': 'MODEL_UNAVAILABLE' if task['scenario'] == 'model-unavailable' else 'PROPOSED',
              'tokensReserved': task['tokenReservation'], 'coverage': [], 'proposal': None}
    if output['status'] != 'PROPOSED':
        return output
    if task['stage'] == 'INVESTIGATING':
        output['coverage'] = ['synthetic-ticket', 'pinned-base-tree']
        output['proposal'] = {'finding': 'bounded text probe missing', 'missingContext': []}
    elif task['stage'] == 'PLANNING':
        output['proposal'] = {'allowedPaths': ['vn-t14-probe-' + task['operationId'] + '.txt'],
                              'acceptance': 'exact fixed probe; unchanged base; protected integrity/content tests'}
    else:
        body = 'VN-T14 isolated delivery probe\nOperation: ' + task['operationId'] + '\n'
        if task['scenario'] == 'bad-candidate':
            body = 'SYNTHETIC INCORRECT CONTENT\n'
        # Artifact bytes are a proposal, not a claimed digest or success receipt.
        output['proposal'] = {'path': 'vn-t14-probe-' + task['operationId'] + '.txt', 'content': body}
    return output


def strict_json(body):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result
    return json.loads(body, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synthetic', action='store_true')
    args = parser.parse_args()
    require(args.synthetic)  # This entry point cannot activate a real provider.
    body = sys.stdin.buffer.read(8193)
    require(len(body) <= 8192)
    result = execute(strict_json(body))
    sys.stdout.write(json.dumps(result, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, TypeError, KeyError, AttributeError):
        print('Synthetic worker rejected task; no authority or external action issued', file=sys.stderr)
        raise SystemExit(2)

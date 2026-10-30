"""Heuristic credential scan of tracked production files and all reachable Git snapshots.

Prints locations/categories only, never the matched values. Not a proof that no
secret exists; provider-side access and unreachable objects require a separate audit.
"""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def inspect(text, is_java=False):
    findings = []
    patterns = {
        'provider-token': r'(?<![A-Za-z0-9])(?:AKIA[A-Z0-9]{16}|ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9_-]{32,})',
        'private-key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
        'embedded-url-credential': r'(?:https?|mysql|postgres(?:ql)?)://[^\s/@:]+:[^\s/@]+@',
    }
    for label, pattern in patterns.items():
        for match in re.finditer(pattern, text):
            findings.append((label, text[:match.start()].count('\n') + 1))
    for number, line in enumerate(text.splitlines(), 1):
        if is_java:
            # Look for hardcoded quoted literals, not setters or ConfigLoader calls.
            match = re.search(r'\b[A-Za-z0-9_]*(?:password|secret|api_?key|token)\s*=\s*"([^"\n]*)"', line, re.I)
        else:
            match = re.match(r'\s*(?:[A-Za-z0-9_.-]*(?:password|secret|api[_.-]?key|token))\s*=\s*(.*?)\s*$', line, re.I)
        if match:
            value = match.group(1).strip('"\'')
            if value and not any(marker in value.upper() for marker in ('CHANGE_ME', 'PLACEHOLDER', '${', '<', 'PENDING_', 'EXAMPLE', 'YOUR_')):
                findings.append(('non-placeholder-secret-assignment', number))
    return findings


def main():
    commits = git('rev-list', '--all').decode().splitlines()
    seen = set()
    findings = []
    def relevant(name):
        return not name.startswith(('src/test/', '.github/modernize/')) and Path(name).suffix.lower() in ('.java', '.properties', '.xml', '.json', '.yaml', '.yml', '.sql', '.md')
    def scan(revision, name, content):
        identity = (name, content)
        if identity in seen:
            return
        seen.add(identity)
        for category, line in inspect(content.decode('utf-8', errors='replace'), name.endswith('.java')):
            findings.append((revision[:10], name, line, category))
    for name in filter(None, git('ls-files', '--cached', '--others', '--exclude-standard', '-z').decode().split('\0')):
        if relevant(name):
            try:
                scan('WORKTREE', name, (ROOT / name).read_bytes())
            except OSError:
                pass
    # Read reachable historical blobs in one process, avoiding thousands of
    # separate git-show processes on Windows and OneDrive workspaces.
    objects = []
    for line in git('rev-list', '--objects', '--all').decode().splitlines():
        fields = line.split(' ', 1)
        if len(fields) == 2 and relevant(fields[1]):
            objects.append(fields)
    result = subprocess.run(['git', 'cat-file', '--batch'], cwd=ROOT,
                            input=('\n'.join(sha for sha, _ in objects) + '\n').encode(),
                            capture_output=True, check=True).stdout
    offset = 0
    for sha, name in objects:
        end = result.index(b'\n', offset)
        header = result[offset:end].split()
        size = int(header[2])
        content = result[end + 1:end + 1 + size]
        offset = end + 1 + size + 1
        if header[1] == b'blob':
            scan(sha, name, content)
    remote = subprocess.run(['git', 'config', '--get', 'remote.origin.url'], cwd=ROOT, capture_output=True, text=True).stdout
    print(f'Scanned {len(commits)} reachable commits and {len(seen)} distinct production file snapshots.')
    print(f'Heuristic findings: {len(findings)}. Values are withheld.')
    for revision, name, line, category in findings:
        print(f'{revision} {name}:{line} [{category}]')
    print('Remote URL contains embedded password:', bool(re.search(r'https?://[^/@:]+:[^/@]+@', remote)))
    print('Scope excludes test fixtures, ignored/local secret files, unreachable Git objects, and external provider logs. Historical IDs identify blobs.')


if __name__ == '__main__':
    main()

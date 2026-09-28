import importlib.util
from pathlib import Path
import subprocess
import sys
import pytest

SCRIPT = Path(__file__).with_name('configure.py')
spec = importlib.util.spec_from_file_location('configure', SCRIPT)
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], text=True, capture_output=True)


def test_generation_no_overwrite_no_secret_output(tmp_path):
    path = tmp_path / '.env'
    result = run('--file', path)
    assert result.returncode == 0, result.stderr
    values = config.load(path)
    original = path.read_bytes()
    assert values['POSTGRES_PASSWORD'] not in result.stdout
    assert values['APP_DB_PASSWORD'] not in result.stdout
    assert values['APP_DB_PASSWORD'] != values['POSTGRES_PASSWORD']
    assert run('--file', path).returncode != 0
    assert path.read_bytes() == original
    assert run('--file', path, '--check').returncode == 0


def test_production_requires_real_domain_and_matching_url(tmp_path):
    path = tmp_path / '.env'
    assert run('--file', path, '--mode', 'production').returncode != 0
    assert not path.exists()
    assert run('--file', path, '--mode', 'production', '--domain', 'api.example.com', '--email', 'x@test.com').returncode != 0
    result = run('--file', path, '--mode', 'production', '--domain', 'api.wenfou-test.cn', '--email', 'x@wenfou-test.cn')
    assert result.returncode == 0
    assert config.load(path)['WENFOU_ENVIRONMENT'] == 'production'
    path.write_text(path.read_text().replace('https://api.wenfou-test.cn', 'http://api.wenfou-test.cn'))
    assert run('--file', path, '--check').returncode != 0


def test_unsafe_env_file_rejected(tmp_path):
    path = tmp_path / '.env'
    assert run('--file', path).returncode == 0
    path.chmod(0o644)
    with pytest.raises(ValueError):
        config.load(path)
    path.chmod(0o600)
    path.write_text(path.read_text() + 'POSTGRES_PASSWORD=$(echo unsafe)\n')
    with pytest.raises(ValueError):
        config.load(path)

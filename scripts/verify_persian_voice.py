"""Pinned native Persian synthesis smoke test; requires sherpa-onnx==1.13.8."""
import hashlib
import math
from pathlib import Path
import sys
import tarfile
import urllib.request

PACK = 'vits-piper-fa_IR-ganji-medium'
SHA256 = '6eae2acccd1b4460159fa5acdad4bb5d2df6d64d8da3e4029dbdff4db14e7a7a'
workspace = Path(sys.argv[1] if len(sys.argv) > 1 else '/tmp/prime-voice-check')
workspace.mkdir(parents=True, exist_ok=True)
archive = workspace / 'persian.tar.bz2'
if not archive.exists():
    urllib.request.urlretrieve(f'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{PACK}.tar.bz2', archive)
with archive.open('rb') as source:
    assert hashlib.file_digest(source, 'sha256').hexdigest() == SHA256, 'Voice checksum mismatch'
with tarfile.open(archive) as tar:
    tar.extractall(workspace, filter='data')

import sherpa_onnx
model = workspace / PACK
config = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    vits=sherpa_onnx.OfflineTtsVitsModelConfig(
        model=str(model / 'fa_IR-ganji-medium.onnx'),
        tokens=str(model / 'tokens.txt'), data_dir=str(model / 'espeak-ng-data')),
    num_threads=2))
tts = sherpa_onnx.OfflineTts(config)
phrase = 'سلام، من پرایم هستم. صدای فارسی آماده است. چطور می‌توانم کمکت کنم؟'
audio = tts.generate(phrase, sid=0, speed=1.0)
samples = audio.samples
assert audio.sample_rate == 22050
assert len(samples) > 22050, 'No usable Persian audio was generated'
rms = math.sqrt(sum(float(sample) ** 2 for sample in samples) / len(samples))
assert rms > 0.001 and all(math.isfinite(float(sample)) for sample in samples)
print(f'Persian synthesis OK: {len(samples) / audio.sample_rate:.2f}s, {audio.sample_rate}Hz, RMS {rms:.4f}')

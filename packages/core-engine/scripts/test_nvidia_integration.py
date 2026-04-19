import traceback
import json
import os
import sys

# Ensure package root is on sys.path when running from scripts/
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from openai import OpenAI
from config import settings


def masked(k):
    if not k:
        return "<missing>"
    if len(k) <= 8:
        return k
    return k[:4] + "..." + k[-4:]

print("NVIDIA base_url:", settings.nvidia_base_url)
print("Has key:", bool(settings.nvidia_api_key))
print("Masked key:", masked(settings.nvidia_api_key))

url = settings.nvidia_base_url
key = settings.nvidia_api_key

print('\n== Attempt 1: OpenAI client with Authorization: Bearer')
try:
    client = OpenAI(base_url=url, api_key=key)
    resp = client.chat.completions.create(
        model="z-ai/glm4.7",
        messages=[{"role": "user", "content": "hi"}],
        max_tokens=1,
    )
    print('Response type:', type(resp))
    try:
        # Try to show a compact representation
        print('Response keys:', getattr(resp, '__dict__', str(resp))[:1000])
    except Exception:
        print(repr(resp)[:1000])
except Exception as e:
    print('Exception during attempt 1:', str(e))
    traceback.print_exc()

print('\n== Attempt 2: OpenAI client with x-api-key header (no Authorization)')
try:
    client2 = OpenAI(base_url=url, api_key="", default_headers={"x-api-key": key})
    resp2 = client2.chat.completions.create(
        model="z-ai/glm4.7",
        messages=[{"role": "user", "content": "hi"}],
        max_tokens=1,
    )
    print('Response2 type:', type(resp2))
    try:
        print('Response2 keys:', getattr(resp2, '__dict__', str(resp2))[:1000])
    except Exception:
        print(repr(resp2)[:1000])
except Exception as e:
    print('Exception during attempt 2:', str(e))
    traceback.print_exc()

print('\n== Attempt 3: Streamed call (show chunks)')
try:
    client3 = OpenAI(base_url=url, api_key=key)
    stream = client3.chat.completions.create(
        model="z-ai/glm4.7",
        messages=[{"role": "user", "content": "hi"}],
        stream=True,
        max_tokens=32,
    )
    for i, chunk in enumerate(stream):
        print(f'CHUNK #{i}:', repr(chunk)[:1000])
        if i >= 5:
            break
except Exception as e:
    print('Exception during attempt 3:', str(e))
    traceback.print_exc()

print('\nDone')

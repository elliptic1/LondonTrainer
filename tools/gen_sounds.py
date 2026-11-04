#!/usr/bin/env python3
"""Synthesizes the app's sound effects from scratch, so they're original and license-free.

Writes 16-bit mono WAVs to app/src/main/res/raw/. Usage: gen_sounds.py   (needs numpy)
"""
import os, wave
import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/res/raw")
rng = np.random.default_rng(1851)  # the first modern chess tournament: London


def ring(freq, ms, decay, gain=1.0):
    t = np.arange(int(RATE * ms / 1000)) / RATE
    return gain * np.sin(2 * np.pi * freq * t) * np.exp(-t * decay)


def tick(ms, decay, gain):
    """The contact noise that makes a tone read as an object hitting wood."""
    t = np.arange(int(RATE * ms / 1000)) / RATE
    return gain * rng.standard_normal(len(t)) * np.exp(-t * decay)


def mix(*parts):
    """Each part is (start ms, samples)."""
    length = max(int(RATE * at / 1000) + len(x) for at, x in parts)
    out = np.zeros(length)
    for at, x in parts:
        i = int(RATE * at / 1000)
        out[i:i + len(x)] += x
    return out


def knock(body, ms=90):
    return mix((0, ring(body, ms, 95)), (0, ring(body * 2.76, ms, 170, 0.35)), (0, ring(body * 5.4, ms, 320, 0.12)), (0, tick(10, 700, 0.5)))


def bell(freq, ms=420):
    return mix((0, ring(freq, ms, 8)), (0, ring(freq * 2.0, ms, 16, 0.28)), (0, ring(freq * 3.0, ms, 30, 0.1)))


SOUNDS = {
    "fx_move": (knock(620), 0.75),
    "fx_capture": (mix((0, knock(540)), (38, knock(760))), 0.9),
    "fx_castle": (mix((0, knock(600)), (85, knock(520))), 0.8),
    "fx_wrong": (mix((0, ring(196, 150, 26)), (95, ring(165, 190, 22))), 0.55),
    "fx_right": (mix((0, ring(1175, 180, 34)), (0, ring(2350, 180, 60, 0.2))), 0.16),
    "fx_done": (mix((0, bell(523)), (110, bell(659)), (220, bell(784)), (340, bell(1047, 600))), 0.5),
}

os.makedirs(OUT, exist_ok=True)
for name, (samples, level) in SOUNDS.items():
    fade = np.minimum(1, (len(samples) - np.arange(len(samples))) / (RATE * 0.01))  # no click at the tail
    pcm = (samples * fade / np.abs(samples).max() * level * 32767).astype(np.int16)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    print(f"{name}: {len(pcm) / RATE * 1000:.0f} ms, peak {np.abs(pcm).max() / 32767:.2f}")

"""An original 15-second upbeat electronic track for the Grabbly promo video.

128 BPM, 8 bars (exactly 15 s), C major, chords C - G - Am - F twice.
Bar 1: intro (pad, filtered arpeggio, rising noise). Bars 2-7: full groove (kick, clap, hats,
offbeat bass, plucked arpeggio, pad; a lead melody from bar 5). Bar 8: final chord rings out.
Everything is synthesised here, so there is no copyright to clear.

    pip install numpy scipy
    python music.py music.wav
"""
import sys
import wave

import numpy as np
from scipy.signal import butter, sosfilt

SR = 44_100
BPM = 128
BEAT = 60 / BPM
BAR = 4 * BEAT
BARS = 8
N = int(round(BARS * BAR * SR))  # 15.0 s
rng = np.random.default_rng(7)


def hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def lowpass(x, cutoff, order=2):
    return sosfilt(butter(order, cutoff, "low", fs=SR, output="sos"), x)


def highpass(x, cutoff, order=2):
    return sosfilt(butter(order, cutoff, "high", fs=SR, output="sos"), x)


def bandpass(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], "band", fs=SR, output="sos"), x)


def place(track, sound, start_s, gain=1.0):
    i = int(round(start_s * SR))
    if i >= len(track):
        return
    end = min(len(track), i + len(sound))
    track[i:end] += gain * sound[: end - i]


def saw(freq, length, detune_cents=0.0, phase=0.0):
    t = np.arange(length) / SR
    f = freq * 2 ** (detune_cents / 1200)
    return 2 * ((t * f + phase) % 1.0) - 1


# ---------------------------------------------------------------- drums
def kick():
    n = int(0.42 * SR)
    t = np.arange(n) / SR
    freq = 46 + 110 * np.exp(-t / 0.035)
    phase = 2 * np.pi * np.cumsum(freq) / SR
    body = np.sin(phase) * np.exp(-t / 0.22)
    click = highpass(rng.standard_normal(n) * np.exp(-t / 0.004), 1500) * 0.35
    return np.tanh(1.6 * (body + click))


def clap():
    n = int(0.3 * SR)
    t = np.arange(n) / SR
    env = np.zeros(n)
    for k, delay in enumerate((0.0, 0.011, 0.022)):  # a few quick slaps, like hands
        d = int(delay * SR)
        env[d:] += np.exp(-(t[: n - d]) / (0.012 if k < 2 else 0.11))
    return bandpass(rng.standard_normal(n), 900, 5200) * env * 0.9


def hat(open_=False):
    n = int((0.22 if open_ else 0.06) * SR)
    t = np.arange(n) / SR
    return highpass(rng.standard_normal(n), 7000, 4) * np.exp(-t / (0.07 if open_ else 0.014))


def crash():
    n = int(1.8 * SR)
    t = np.arange(n) / SR
    return highpass(rng.standard_normal(n), 4000, 2) * np.exp(-t / 0.6)


def riser(length_s):
    n = int(length_s * SR)
    t = np.arange(n) / SR
    noise = rng.standard_normal(n)
    # sweep a band of noise upwards by filtering slices with a rising centre
    out = np.zeros(n)
    slices = 24
    for k in range(slices):
        a, b = k * n // slices, (k + 1) * n // slices
        centre = 400 * (12000 / 400) ** (k / (slices - 1))
        out[a:b] = bandpass(noise[a:b], centre * 0.7, min(centre * 1.4, SR / 2 - 100))
    return out * (t / t[-1]) ** 2


# ---------------------------------------------------------------- instruments
CHORDS = [  # pad voicing, bass root, arpeggio tones
    ([60, 64, 67], 36, [72, 76, 79, 84]),  # C
    ([59, 62, 67], 31, [71, 74, 79, 83]),  # G
    ([57, 60, 64], 33, [69, 72, 76, 81]),  # Am
    ([57, 60, 65], 29, [69, 72, 77, 81]),  # F
] * 2


def pad_chord(notes, length, detunes=(-9, 0, 8)):
    t = np.arange(length) / SR
    voices = []
    for note in notes:
        for cents in detunes:
            voices.append(saw(hz(note), length, cents, phase=rng.random()))
    x = np.sum(voices, axis=0) / len(voices)
    attack = np.minimum(1, t / 0.25)
    return lowpass(x, 2200, 2) * attack


def bass_note(midi, length):
    t = np.arange(length) / SR
    f = hz(midi)
    x = 0.75 * np.sin(2 * np.pi * f * t) + 0.35 * saw(f * 2, length)
    x = lowpass(x, 700, 2)
    env = np.minimum(1, t / 0.005) * np.exp(-t / 0.16)
    return np.tanh(1.4 * x * env)


def pluck(midi, length, decay=0.16, bright=5200):
    t = np.arange(length) / SR
    f = hz(midi)
    x = saw(f, length, 0, rng.random()) * 0.6 + np.sin(2 * np.pi * f * t) * 0.4
    return lowpass(x, bright, 2) * np.exp(-t / decay) * np.minimum(1, t / 0.002)


def bell(midi, length):
    t = np.arange(length) / SR
    f = hz(midi)
    x = np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-t / 0.15) \
        + 0.12 * np.sin(2 * np.pi * 3.01 * f * t) * np.exp(-t / 0.08)
    return x * np.exp(-t / 0.55) * np.minimum(1, t / 0.003)


def main(out_path: str) -> None:
    drums = np.zeros(N)
    kicks_track = np.zeros(N)
    hats_l, hats_r = np.zeros(N), np.zeros(N)
    arp_r = np.zeros(N)
    bass = np.zeros(N)
    pad_l, pad_r = np.zeros(N), np.zeros(N)
    arp = np.zeros(N)
    lead = np.zeros(N)
    fx = np.zeros(N)

    kicks = []
    for bar in range(BARS):
        b0 = bar * BAR
        pad_notes, root, arp_notes = CHORDS[bar]

        # Pad: every bar, the last one rings out
        length = int((BAR * (2.2 if bar == BARS - 1 else 1.02)) * SR)
        chord = pad_chord(pad_notes, length)
        widen = pad_chord(pad_notes, length, detunes=(-14, 5, 12))  # detuned differently: stereo width
        if bar == BARS - 1:
            chord *= np.exp(-np.arange(length) / SR / 0.9)
            widen *= np.exp(-np.arange(length) / SR / 0.9)
        place(pad_l, chord, b0)
        place(pad_r, widen, b0)

        if bar == BARS - 1:
            # Final hit: kick, crash, bass and a bright chord on beat 1, then let it ring
            place(kicks_track, kick(), b0, 1.0)
            kicks.append(b0)
            place(fx, crash(), b0, 0.9)
            place(bass, bass_note(root, int(1.6 * SR)) * np.exp(-np.arange(int(1.6 * SR)) / SR / 0.5), b0, 1.0)
            for k, note in enumerate(arp_notes):
                place(arp if k % 2 == 0 else arp_r, pluck(note, int(1.5 * SR), decay=0.6), b0 + k * 0.03, 0.8)
            place(lead, bell(84, int(1.6 * SR)), b0, 1.0)
            continue

        # Arpeggio: 16ths (softer in the intro)
        pattern = [0, 2, 1, 3, 2, 1, 3, 2, 0, 2, 1, 3, 2, 3, 1, 2]
        for step in range(16):
            note = arp_notes[pattern[step]]
            accent = 1.0 if step % 4 == 0 else 0.72
            side = arp if step % 2 == 0 else arp_r  # ping-pong between left and right
            place(side, pluck(note, int(0.35 * SR), bright=2600 if bar == 0 else 5200), b0 + step * BEAT / 4,
                  accent * (0.55 if bar == 0 else 1.0))

        if bar == 0:
            place(fx, riser(BAR), b0, 0.5)
            continue

        # Groove: kick on every beat, clap on 2 and 4, hats on the offbeats, bass on the offbeats
        for beat in range(4):
            t = b0 + beat * BEAT
            place(kicks_track, kick(), t, 1.0)
            kicks.append(t)
            if beat in (1, 3):
                place(drums, clap(), t, 0.7)
            place(hats_r, hat(open_=(beat == 3 and bar % 2 == 1)), t + BEAT / 2, 0.3)
            place(hats_l, hat(), t + BEAT / 4, 0.12)
            place(hats_l, hat(), t + 3 * BEAT / 4, 0.12)
            place(bass, bass_note(root, int(BEAT / 2 * SR)), t + BEAT / 2, 1.0)
        if bar in (1, 4):
            place(fx, crash(), b0, 0.45)
        if bar == 3:
            place(fx, riser(BAR), b0, 0.35)

        # Lead melody from bar 5: a short hook per bar
        if bar >= 4:
            hooks = {4: [(0, 76), (1.5, 79), (2, 84), (3, 83)], 5: [(0, 79), (1.5, 74), (2, 79), (3, 81)],
                     6: [(0, 81), (1.5, 76), (2, 84), (3, 79)]}
            for beat_pos, note in hooks[bar]:
                place(lead, bell(note, int(0.9 * SR)), b0 + beat_pos * BEAT, 0.9)

    # Sidechain: duck the pad, bass and arpeggio a little after each kick (the "pumping" feel)
    duck = np.ones(N)
    t_all = np.arange(N) / SR
    for k in kicks:
        i = int(k * SR)
        span = slice(i, min(N, i + int(0.3 * SR)))
        duck[span] = np.minimum(duck[span], 1 - 0.55 * np.exp(-(t_all[span] - k) / 0.09))

    # Arpeggio echo, alternating sides (dotted eighth)
    delay = int(0.75 * BEAT * SR)
    echo_l, echo_r = np.zeros(N), np.zeros(N)
    echo_l[delay:] = arp_r[:-delay] * 0.35  # each side's echo comes back on the other side
    echo_r[delay:] = arp[:-delay] * 0.35

    # Levels: phone and laptop speakers barely play deep bass, so the melody parts carry the track
    left = (0.62 * kicks_track + 0.8 * drums + 0.75 * hats_l + 0.4 * hats_r + 0.36 * bass * duck
            + 0.34 * pad_l * duck + 0.46 * arp * duck + 0.16 * arp_r * duck + 0.3 * echo_l + 0.34 * lead + 0.16 * fx)
    right = (0.62 * kicks_track + 0.8 * drums + 0.4 * hats_l + 0.75 * hats_r + 0.36 * bass * duck
             + 0.34 * pad_r * duck + 0.16 * arp * duck + 0.46 * arp_r * duck + 0.3 * echo_r + 0.34 * lead + 0.16 * fx)
    mix = np.stack([left, right], axis=1)

    # Gentle mastering: tame the lowest rumble, soft-clip, normalise, fade the very ends
    mix = highpass(mix.T, 28, 2).T
    mix = np.tanh(mix / np.max(np.abs(mix)) * 1.25)
    mix *= 0.89 / np.max(np.abs(mix))
    fade_in = np.minimum(1, t_all / 0.03)
    fade_out = np.minimum(1, (t_all[-1] - t_all) / 0.6)
    mix *= (fade_in * fade_out)[:, None]

    pcm = (mix * 32767).astype("<i2")
    with wave.open(out_path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    rms = 20 * np.log10(np.sqrt(np.mean(mix ** 2)))
    print(f"wrote {out_path}: {N / SR:.2f} s, peak {20 * np.log10(np.max(np.abs(mix))):.1f} dBFS, rms {rms:.1f} dBFS")


if __name__ == "__main__":
    main(sys.argv[1])

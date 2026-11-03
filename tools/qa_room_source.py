"""Drive the opt-in Fold5 room test with a separate quiet Windows speaker. Only synthetic speech is played."""
import argparse
import array
import ctypes as ct
from ctypes import wintypes as wt
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import wave


class WaveCaps(ct.Structure):
    _fields_ = [("manufacturer", ct.c_ushort), ("product", ct.c_ushort), ("version", wt.DWORD),
                ("name", ct.c_wchar * 32), ("formats", wt.DWORD), ("channels", ct.c_ushort),
                ("reserved", ct.c_ushort), ("support", wt.DWORD)]


class WaveFormat(ct.Structure):
    _pack_ = 2
    _fields_ = [("codec", ct.c_ushort), ("channels", ct.c_ushort), ("rate", wt.DWORD), ("byte_rate", wt.DWORD),
                ("alignment", ct.c_ushort), ("bits", ct.c_ushort), ("extra", ct.c_ushort)]


class WaveHeader(ct.Structure):
    _fields_ = [("data", ct.c_void_p), ("length", wt.DWORD), ("recorded", wt.DWORD), ("user", ct.c_size_t),
                ("flags", wt.DWORD), ("loops", wt.DWORD), ("next", ct.c_void_p), ("reserved", ct.c_size_t)]


def adb(*args):
    return subprocess.check_output(["adb", "-d", *args], encoding="utf-8", errors="replace").strip()


def checked(result):
    if result:
        raise RuntimeError(f"Windows audio API returned {result}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--speaker", default="Realtek", help="substring of a physical Windows speaker endpoint")
    args = parser.parse_args()
    if os.name != "nt" or adb("shell", "getprop", "ro.product.model") != "V2436A":
        raise RuntimeError("Requires Windows and one authorized Fold5")
    media = adb("shell", "dumpsys", "audio").split("- STREAM_MUSIC:", 1)[1].split("- STREAM_", 1)[0]
    if not re.search(r"\b2 \(speaker\): 1[, ]", media):
        raise RuntimeError("Fold5 speaker is not at its lowest nonzero volume")
    win = ct.windll.winmm
    endpoints = []
    for number in range(win.waveOutGetNumDevs()):
        caps = WaveCaps()
        checked(win.waveOutGetDevCapsW(number, ct.byref(caps), ct.sizeof(caps)))
        if args.speaker.casefold() in caps.name.casefold():
            endpoints.append(number)
    if len(endpoints) != 1:
        raise RuntimeError("Select one physical speaker using --speaker")
    fixture = Path(__file__).resolve().parents[1] / "app/src/main/assets/phrasebook/station-zh.wav"
    with wave.open(str(fixture), "rb") as source:
        rate = source.getframerate()
        if (source.getnchannels(), source.getsampwidth()) != (1, 2):
            raise ValueError("Expected mono PCM16")
        original = array.array("h", source.readframes(source.getnframes()))
    first = next(i for i, value in enumerate(original) if abs(value) >= 650)
    # Keep the independent speaker quiet as well. No global Windows volume setting is changed.
    data = ct.create_string_buffer(array.array("h", (round(v / 150) for v in original[first:])).tobytes())
    fmt = WaveFormat(1, 1, rate, rate * 2, 2, 16, 0)
    handle = wt.HANDLE()
    checked(win.waveOutOpen(ct.byref(handle), endpoints[0], ct.byref(fmt), 0, 0, 0))
    header = WaveHeader(ct.addressof(data), len(data) - 1)
    prepared = False
    try:
        checked(win.waveOutPrepareHeader(handle, ct.byref(header), ct.sizeof(header)))
        prepared = True
        trial = uuid.uuid4().hex[:8]
        started = time.monotonic()
        device_clock = float(adb("shell", "cat", "/proc/uptime").split()[0])
        offset = (started + time.monotonic()) / 2 - device_clock
        command = ["adb", "-d", "shell", "am", "instrument", "--user", "0", "-w", "-r", "-e", "roomAudio", "true",
                   "-e", "externalTrial", trial, "-e", "class", "dev.polybridge.app.RoomAudioDeviceTest",
                   "dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner"]
        with subprocess.Popen(command, stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace") as process:
            while time.monotonic() - started < 25 and process.poll() is None:
                logs = adb("logcat", "-d", "-s", "PolyBridgeRoomQA:I", "*:S")
                stamp = re.search(trial + r" playAt=(\d+)", logs)
                if stamp:
                    due = int(stamp[1]) / 1000 + offset
                    time.sleep(max(0, due - time.monotonic()))
                    checked(win.waveOutWrite(handle, ct.byref(header), ct.sizeof(header)))
                    print(f"Independent speaker submitted the fixture, scheduler offset {1000*(time.monotonic()-due):.0f} ms")
                    break
                time.sleep(.08)
            result, _ = process.communicate(timeout=20)
        for line in result.splitlines():
            if "roomAudio=" in line or "stack=" in line or "OK (" in line:
                print(line)
        if "OK (" not in result:
            raise SystemExit(1)
    finally:
        win.waveOutReset(handle)
        if prepared:
            win.waveOutUnprepareHeader(handle, ct.byref(header), ct.sizeof(header))
        win.waveOutClose(handle)


if __name__ == "__main__":
    main()

"""Voice assistant module powered by gTTS (Google Text-to-Speech)."""

from __future__ import annotations

import ctypes
import hashlib
import io
import os
import re
import tempfile
from typing import Optional

try:
    from gtts import gTTS

    GTTS_AVAILABLE = True
except ImportError:
    GTTS_AVAILABLE = False


_AUDIO_CACHE: dict[str, bytes] = {}

VOICE_PROFILES = {
    "hinglish": {"lang": "en", "tld": "co.in", "label": "Hinglish (Indian Accent) 🇮🇳"},
    "hi": {"lang": "hi", "tld": "com", "label": "Hindi (हिन्दी) 🇮🇳"},
    "hindi": {"lang": "hi", "tld": "com", "label": "Hindi (हिन्दी) 🇮🇳"},
    "en-in": {"lang": "en", "tld": "co.in", "label": "English (India) 🇮🇳"},
    "en": {"lang": "en", "tld": "com", "label": "English (US)"},
}

HINGLISH_PATTERN = re.compile(
    r"\b(hai|kya|ka|ki|ke|ko|mein|se|aur|bhi|nahi|nahin|hoga|hogi|hote|raha|rahe|karo|karna|karenge|batao|chahiye|aapka|apka|mera|meri|hum|tum|baje|kal|aaj|shukriya|namaste|dhanyavad)\b",
    re.IGNORECASE,
)


def detect_voice_profile(text: str, requested: str = "auto") -> tuple[str, str]:
    """Resolve (lang, tld) based on user preference or smart language detection.
    
    Supports:
    - Pure Devanagari Hindi (lang='hi')
    - Romanized Hinglish (lang='en', tld='co.in' or lang='hi')
    - Indian English (lang='en', tld='co.in')
    - Standard English (lang='en', tld='com')
    """
    key = (requested or "auto").lower().strip()
    if key in VOICE_PROFILES:
        prof = VOICE_PROFILES[key]
        return prof["lang"], prof["tld"]

    # Devanagari unicode range: \u0900 to \u097F
    if re.search(r"[\u0900-\u097F]", text):
        return "hi", "com"

    # If text has common Hinglish / Roman Hindi vocabulary
    if HINGLISH_PATTERN.search(text):
        return "en", "co.in"

    # Default to Indian English tld='co.in' for natural Indian/Hinglish phonetics
    if key in ("in", "india"):
        return "en", "co.in"

    return "en", "co.in" if key == "auto" else "com"


def clean_text_for_speech(text: str, max_chars: int = 1000) -> str:
    """Clean markdown and special symbols so the text sounds natural when spoken."""
    if not text:
        return ""

    # Remove code blocks
    text = re.sub(r"```[\s\S]*?```", " Code snippet omitted. ", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)

    # Markdown links [label](url) -> label
    text = re.sub(r"\[([^\]]+)\]\([^\)]+\)", r"\1", text)

    # Remove header markers and bullet markers
    text = re.sub(r"^[#*>\-\+\d\.]+\s+", "", text, flags=re.MULTILINE)

    # Remove bold, italic, strikethrough markdown
    text = re.sub(r"[*_~]{1,3}", "", text)

    # Replace newlines with spaces or periods
    text = re.sub(r"\n+", ". ", text)

    # Collapse multiple spaces or periods
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\.+", ".", text)

    if len(text) > max_chars:
        # Trim at last sentence end or space
        clipped = text[:max_chars]
        last_period = clipped.rfind(".")
        if last_period > max_chars // 2:
            text = clipped[: last_period + 1]
        else:
            text = clipped + "..."

    return text


def generate_speech_bytes(
    text: str,
    lang: str = "auto",
    tld: str | None = None,
) -> Optional[bytes]:
    """Generate MP3 audio bytes using gTTS with smart Hinglish and Hindi support."""
    if not GTTS_AVAILABLE:
        return None

    cleaned = clean_text_for_speech(text)
    if not cleaned:
        return None

    detected_lang, detected_tld = detect_voice_profile(cleaned, lang)
    final_lang = detected_lang
    final_tld = tld or detected_tld

    cache_key = hashlib.sha256(f"{final_lang}:{final_tld}:{cleaned}".encode("utf-8")).hexdigest()
    if cache_key in _AUDIO_CACHE:
        return _AUDIO_CACHE[cache_key]

    try:
        tts = gTTS(text=cleaned, lang=final_lang, tld=final_tld)
        buffer = io.BytesIO()
        tts.write_to_fp(buffer)
        audio_bytes = buffer.getvalue()
        # Keep cache bounded to 50 items
        if len(_AUDIO_CACHE) > 50:
            _AUDIO_CACHE.pop(next(iter(_AUDIO_CACHE)))
        _AUDIO_CACHE[cache_key] = audio_bytes
        return audio_bytes
    except Exception as exc:
        print(f"[voice] gTTS generation error ({final_lang}, {final_tld}): {exc}")
        try:
            tts = gTTS(text=cleaned, lang="en")
            buffer = io.BytesIO()
            tts.write_to_fp(buffer)
            return buffer.getvalue()
        except Exception:
            return None


def play_speech_bytes(audio_bytes: bytes, wait: bool = True) -> bool:
    """Play MP3 audio on Windows using winmm MCI API."""
    if not audio_bytes:
        return False

    temp_path = None
    alias = f"gtts_{os.getpid()}_{hash(audio_bytes) & 0xFFFFFFFF}"
    try:
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(audio_bytes)
            temp_path = f.name

        winmm = ctypes.windll.winmm
        open_cmd = f'open "{temp_path}" type mpegvideo alias {alias}'
        if winmm.mciSendStringW(open_cmd, None, 0, 0) != 0:
            return False

        play_cmd = f"play {alias} wait" if wait else f"play {alias}"
        winmm.mciSendStringW(play_cmd, None, 0, 0)
        winmm.mciSendStringW(f"close {alias}", None, 0, 0)
        return True
    except Exception as exc:
        print(f"[voice] Playback error: {exc}")
        return False
    finally:
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass


def speak(text: str, wait: bool = True) -> bool:
    """Convenience helper: synthesize and play speech."""
    audio = generate_speech_bytes(text)
    if not audio:
        return False
    return play_speech_bytes(audio, wait=wait)


if __name__ == "__main__":
    print(f"gTTS available: {GTTS_AVAILABLE}")
    if GTTS_AVAILABLE:
        print("Testing speech generation...")
        data = generate_speech_bytes("Hello! Google Text to Speech voice assistant is now active.")
        print(f"Generated {len(data or b'')} bytes of audio.")

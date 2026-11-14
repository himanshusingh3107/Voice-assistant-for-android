import unittest
from unittest.mock import patch, MagicMock
import io

from voice import clean_text_for_speech, generate_speech_bytes, GTTS_AVAILABLE
from campus_agent import app_status


class VoiceAssistantTests(unittest.TestCase):
    def test_clean_text_strips_markdown_and_links(self):
        raw = "Here is a [link](https://example.com) and **bold** text with `code`.\n\n* Bullet 1\n* Bullet 2"
        cleaned = clean_text_for_speech(raw)
        self.assertNotIn("[link]", cleaned)
        self.assertIn("link", cleaned)
        self.assertNotIn("**bold**", cleaned)
        self.assertIn("bold", cleaned)
        self.assertNotIn("`code`", cleaned)
        self.assertIn("code", cleaned)
        self.assertNotIn("* Bullet", cleaned)

    def test_clean_text_removes_code_blocks(self):
        raw = "Before code. ```python\nprint('hello')\n``` After code."
        cleaned = clean_text_for_speech(raw)
        self.assertIn("Before code.", cleaned)
        self.assertIn("After code.", cleaned)
        self.assertNotIn("print('hello')", cleaned)

    def test_app_status_reports_voice(self):
        status = app_status()
        self.assertIn("voice", status)
        self.assertIn("voice_available", status)
        self.assertEqual(GTTS_AVAILABLE, status["voice_available"])

    @patch("voice.gTTS")
    def test_generate_speech_bytes(self, mock_gtts_cls):
        mock_instance = MagicMock()
        def fake_write(fp):
            fp.write(b"FAKE_MP3_DATA")
        mock_instance.write_to_fp.side_effect = fake_write
        mock_gtts_cls.return_value = mock_instance

        data = generate_speech_bytes("Test speech generation")
        self.assertIsNotNone(data)
        self.assertEqual(b"FAKE_MP3_DATA", data)

    def test_detect_voice_profile_hinglish_and_devanagari(self):
        from voice import detect_voice_profile
        # Explicit Hinglish profile
        lang, tld = detect_voice_profile("Hello", requested="hinglish")
        self.assertEqual("en", lang)
        self.assertEqual("co.in", tld)

        # Devanagari Hindi text
        lang_hi, _ = detect_voice_profile("नमस्ते, आप कैसे हैं?", requested="auto")
        self.assertEqual("hi", lang_hi)

        # Romanized Hinglish text
        lang_hinglish, tld_hinglish = detect_voice_profile("Aapka timetable Monday ko hai.", requested="auto")
        self.assertEqual("en", lang_hinglish)
        self.assertEqual("co.in", tld_hinglish)


if __name__ == "__main__":
    unittest.main()

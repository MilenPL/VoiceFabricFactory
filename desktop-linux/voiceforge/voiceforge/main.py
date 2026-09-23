"""VoiceForge — GTK4/Libadwaita interface.

Pages
-----
Compose   voice selector + upload/record, text box, mood/loudness/speed/pitch
          panel, generation, result player with waveform + Save / Export.
Voices    manage samples: upload, record, preview, rename, delete.
Queue     batch generation — queue several texts (or import a .txt), generate.
Saved     library of generated MP3s with date, search, sort, download,
          delete and regenerate-with-same-settings.

The UI language (English ⇄ Polish) can be switched live from the Compose
page; every registered widget text is re-applied without a restart.
"""
from __future__ import annotations

import array
import queue
import subprocess
import sys
import threading
import uuid
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import engine, recording, storage  # noqa: E402
from .engine import LANGUAGES, MOODS  # noqa: E402

STAGING = storage.DATA_DIR / "staging"

SORT_KEYS = ["date_desc", "date_asc", "name"]

# Result waveform: ~240 peak bars decoded by ffmpeg at 4 kHz mono.
WAVE_BARS = 240
WAVE_RATE = 4000


# =========================================================================== #
# i18n — one dict, a tr() helper and a per-widget registry (see MainWindow.T)
# =========================================================================== #
STRINGS: dict[str, dict[str, str]] = {
    # ---- sidebar pages -------------------------------------------------- #
    "page.compose": {"en": "Compose", "pl": "Tworzenie"},
    "page.voices": {"en": "Voices", "pl": "Głosy"},
    "page.queue": {"en": "Batch queue", "pl": "Kolejka"},
    "page.saved": {"en": "Saved", "pl": "Zapisane"},

    # ---- compose page --------------------------------------------------- #
    "frame.voice": {"en": "Voice", "pl": "Głos"},
    "btn.upload_sample": {"en": "Upload sample…", "pl": "Wgraj próbkę…"},
    "btn.record_sample": {"en": "Record sample…", "pl": "Nagraj próbkę…"},
    "label.language": {"en": "Language", "pl": "Język"},
    "label.ui_language": {"en": "Język / Language", "pl": "Język / Language"},
    "frame.text": {"en": "Text", "pl": "Tekst"},
    "frame.mood": {"en": "Mood & delivery", "pl": "Nastrój i sposób mówienia"},
    "label.mood": {"en": "Mood", "pl": "Nastrój"},
    "label.loudness": {"en": "Loudness", "pl": "Głośność"},
    "label.speed": {"en": "Speed", "pl": "Tempo"},
    "label.pitch": {"en": "Pitch", "pl": "Wysokość"},
    "btn.normalize": {"en": "Auto normalize loudness (EBU R128)",
                      "pl": "Auto normalizuj głośność (EBU R128)"},
    "btn.generate": {"en": "Generate speech", "pl": "Generuj mowę"},
    "frame.result": {"en": "Result", "pl": "Wynik"},
    "result.empty.title": {"en": "Nothing generated yet",
                           "pl": "Nic jeszcze nie wygenerowano"},
    "result.empty.desc": {"en": "Generated MP3 files will appear here.",
                          "pl": "Wygenerowane pliki MP3 pojawią się tutaj."},
    "btn.save_library": {"en": "Save to library", "pl": "Zapisz w bibliotece"},
    "btn.export": {"en": "Export / download…", "pl": "Eksportuj / pobierz…"},
    "hint.save": {"en": "Save stores the file in the app's storage "
                        "(see the Saved tab).",
                  "pl": "Zapisuje plik w pamięci aplikacji "
                        "(zob. zakładkę Zapisane)."},

    # ---- status lines --------------------------------------------------- #
    "status.queued": {"en": "Queued…", "pl": "W kolejce…"},
    "status.done": {"en": "Done ✓", "pl": "Gotowe ✓"},
    "status.batch_finished": {"en": "Batch finished ✓",
                              "pl": "Partia zakończona ✓"},
    "status.pending_n": {"en": "{n} task(s) pending…",
                         "pl": "Zadań w toku: {n}…"},

    # ---- toasts --------------------------------------------------------- #
    "toast.need_voice": {"en": "Upload or record a voice sample first "
                                "(Voices tab).",
                          "pl": "Najpierw wgraj lub nagraj próbkę głosu "
                                "(zakładka Głosy)."},
    "toast.enter_text": {"en": "Enter some text to speak.",
                         "pl": "Wpisz tekst do wypowiedzenia."},
    "toast.type_first": {"en": "Type something first.",
                         "pl": "Najpierw wpisz coś."},
    "toast.voice_added": {"en": "Voice “{name}” added.",
                          "pl": "Głos „{name}” dodany."},
    "toast.voice_renamed": {"en": "Voice renamed.",
                            "pl": "Nazwa głosu zmieniona."},
    "toast.voice_deleted": {"en": "Voice deleted.", "pl": "Głos usunięty."},
    "toast.file_deleted": {"en": "File deleted.", "pl": "Plik usunięty."},
    "toast.nothing_pending": {"en": "Nothing pending in the queue.",
                              "pl": "W kolejce nie ma nic do zrobienia."},
    "toast.item_running": {"en": "This item is generating right now.",
                           "pl": "Ta pozycja jest właśnie generowana."},
    "toast.saved_to": {"en": "Saved to library: {name}",
                       "pl": "Zapisano w bibliotece: {name}"},
    "toast.exported_to": {"en": "Exported to {path}",
                          "pl": "Wyeksportowano do {path}"},
    "toast.downloaded_to": {"en": "Downloaded to {path}",
                            "pl": "Pobrano do {path}"},
    "toast.added_n": {"en": "Added {n} entries", "pl": "Dodano wpisów: {n}"},
    "toast.regen_no_text": {"en": "This entry has no text to regenerate.",
                            "pl": "Ten wpis nie ma tekstu do wygenerowania."},
    "toast.regen_no_voice": {"en": "The voice used for this file is no "
                                    "longer available.",
                             "pl": "Głos użyty do tego pliku nie jest już "
                                   "dostępny."},

    # ---- alerts and dialogs --------------------------------------------- #
    "alert.close": {"en": "Close", "pl": "Zamknij"},
    "alert.ok": {"en": "OK", "pl": "OK"},
    "alert.gen_failed": {"en": "Generation failed",
                         "pl": "Generowanie nie powiodło się"},
    "alert.save_failed": {"en": "Could not save",
                          "pl": "Nie udało się zapisać"},
    "alert.export_failed": {"en": "Export failed",
                            "pl": "Eksport nie powiódł się"},
    "alert.download_failed": {"en": "Download failed",
                              "pl": "Pobieranie nie powiodło się"},
    "alert.upload_failed": {"en": "Upload failed",
                            "pl": "Import nie powiódł się"},
    "alert.import_failed": {"en": "Could not import sample",
                            "pl": "Nie udało się zaimportować próbki"},
    "alert.record_failed": {"en": "Could not store recording",
                            "pl": "Nie udało się zapisać nagrania"},
    "alert.unsupported_loc": {"en": "Unsupported file location.",
                              "pl": "Nieobsługiwane położenie pliku."},
    "alert.txt_failed": {"en": "Could not read the file",
                         "pl": "Nie udało się odczytać pliku"},
    "dialog.choose_sample": {"en": "Choose a voice sample "
                                   "(MP3, WAV, M4A…)",
                             "pl": "Wybierz próbkę głosu (MP3, WAV, M4A…)"},
    "filter.audio": {"en": "Audio files", "pl": "Pliki audio"},
    "filter.mp3": {"en": "MP3 audio", "pl": "Audio MP3"},
    "filter.txt": {"en": "Text files", "pl": "Pliki tekstowe"},
    "dialog.name_voice": {"en": "Name this voice", "pl": "Nadaj nazwę głosowi"},
    "dialog.rename_voice": {"en": "Rename voice", "pl": "Zmień nazwę głosu"},
    "dialog.give_name": {"en": "Give it a recognizable name.",
                         "pl": "Podaj rozpoznawalną nazwę."},
    "dialog.cancel": {"en": "Cancel", "pl": "Anuluj"},
    "dialog.save": {"en": "Save", "pl": "Zapisz"},
    "dialog.delete": {"en": "Delete", "pl": "Usuń"},
    "dialog.delete_voice_q": {"en": "Delete “{name}”?",
                              "pl": "Usunąć „{name}”?"},
    "dialog.delete_voice_body": {
        "en": "The sample will be removed from the app's storage. "
              "Files generated with it stay in Saved.",
        "pl": "Próbka zostanie usunięta z pamięci aplikacji. Pliki "
              "wygenerowane z jej użyciem pozostają w Zapisanych."},
    "dialog.delete_saved_q": {"en": "Delete “{name}”?",
                              "pl": "Usunąć „{name}”?"},
    "dialog.delete_saved_body": {
        "en": "The MP3 will be removed from the app's storage.",
        "pl": "Plik MP3 zostanie usunięty z pamięci aplikacji."},
    "dialog.download_title": {"en": "Download / save a copy",
                              "pl": "Pobierz / zapisz kopię"},
    "dialog.import_txt": {"en": "Import lines from a .txt file",
                          "pl": "Importuj wiersze z pliku .txt"},
    "dialog.record_title": {"en": "Record a voice sample",
                            "pl": "Nagraj próbkę głosu"},

    # ---- record dialog --------------------------------------------------- #
    "record.hint": {"en": "Record 2–5 minutes of clear, continuous speech "
                          "in a quiet room (reading a book works well).",
                    "pl": "Nagraj 2–5 minut wyraźnej, ciągłej mowy w cichym "
                          "pokoju (dobrze działa czytanie książki na głos)."},
    "record.mic": {"en": "Microphone", "pl": "Mikrofon"},
    "record.start": {"en": "●  Start recording",
                     "pl": "●  Rozpocznij nagrywanie"},
    "record.stop": {"en": "■  Stop recording",
                    "pl": "■  Zatrzymaj nagrywanie"},
    "record.start_again": {"en": "●  Start recording again",
                           "pl": "●  Nagraj ponownie"},
    "record.save_first": {"en": "Stop the recording first",
                          "pl": "Najpierw zatrzymaj nagranie"},
    "record.timer": {"en": "0:00 / 10:00", "pl": "0:00 / 10:00"},
    "record.recorded": {"en": "{d} / 10:00 (recorded)",
                        "pl": "{d} / 10:00 (nagrano)"},
    "record.default_name": {"en": "My voice", "pl": "Mój głos"},

    # ---- voices page ----------------------------------------------------- #
    "btn.upload_voice": {"en": "Upload new voice (sample)…",
                         "pl": "Wgraj nowy głos (próbkę)…"},
    "btn.record_voice": {"en": "Record new voice…",
                         "pl": "Nagraj nowy głos…"},
    "voices.empty.title": {"en": "No voices yet", "pl": "Brak głosów"},
    "voices.empty.desc": {"en": "Upload an MP3 sample (2–5 minutes of clear "
                                "speech) or record one with your microphone.",
                          "pl": "Wgraj próbkę MP3 (2–5 minut wyraźnej mowy) "
                                "albo nagraj ją mikrofonem."},
    "tip.preview": {"en": "Preview", "pl": "Podgląd"},
    "tip.rename": {"en": "Rename", "pl": "Zmień nazwę"},
    "tip.delete": {"en": "Delete", "pl": "Usuń"},
    "sub.added": {"en": "added {d}", "pl": "dodano {d}"},

    # ---- queue page ------------------------------------------------------ #
    "queue.info": {"en": "Add one text per entry, then generate them all one "
                         "after another. Every finished file is saved to the "
                         "Saved tab automatically (the current voice, mood "
                         "and delivery settings are used).",
                   "pl": "Dodaj osobny tekst dla każdego wpisu, a potem "
                         "wygeneruj je wszystkie po sobie. Każdy gotowy plik "
                         "trafia automatycznie do zakładki Zapisane "
                         "(używany jest bieżący głos, nastrój i ustawienia "
                         "mówienia)."},
    "frame.new_entry": {"en": "New entry", "pl": "Nowy wpis"},
    "btn.add_queue": {"en": "Add to queue", "pl": "Dodaj do kolejki"},
    "btn.import_txt": {"en": "Import .txt…", "pl": "Importuj .txt…"},
    "btn.generate_all": {"en": "Generate all", "pl": "Generuj wszystko"},
    "btn.clear_finished": {"en": "Clear finished",
                           "pl": "Wyczyść ukończone"},
    "queue.empty.title": {"en": "Queue is empty", "pl": "Kolejka jest pusta"},
    "queue.empty.desc": {"en": "Add texts above to generate them in one go.",
                         "pl": "Dodaj teksty powyżej, aby wygenerować je "
                               "za jednym razem."},
    "tip.play_result": {"en": "Play result", "pl": "Odtwórz wynik"},
    "tip.remove_queue": {"en": "Remove from queue",
                         "pl": "Usuń z kolejki"},
    "queue.pending": {"en": "⏳ waiting", "pl": "⏳ oczekuje"},
    "queue.running": {"en": "⚙ generating…", "pl": "⚙ generowanie…"},
    "queue.saved": {"en": "✓ saved", "pl": "✓ zapisano"},
    "queue.saved_at": {"en": "✓ saved {d}", "pl": "✓ zapisano {d}"},
    "queue.error": {"en": "✗ {e}", "pl": "✗ {e}"},

    # ---- saved page ------------------------------------------------------ #
    "search.saved": {"en": "Search saved files…",
                     "pl": "Szukaj zapisanych plików…"},
    "sort.newest": {"en": "Newest first", "pl": "Najpierw najnowsze"},
    "sort.oldest": {"en": "Oldest first", "pl": "Najpierw najstarsze"},
    "sort.name": {"en": "Name A–Z", "pl": "Nazwa A–Z"},
    "saved.empty.title": {"en": "No saved files yet",
                          "pl": "Brak zapisanych plików"},
    "saved.empty.desc": {"en": "Generate speech on the Compose page and press "
                               "“Save to library”.",
                         "pl": "Wygeneruj mowę na stronie Tworzenie i "
                               "naciśnij „Zapisz w bibliotece”."},
    "tip.play": {"en": "Play", "pl": "Odtwórz"},
    "tip.download": {"en": "Download / save as…",
                     "pl": "Pobierz / zapisz jako…"},
    "tip.regenerate": {"en": "Regenerate with same settings",
                       "pl": "Wygeneruj ponownie z tymi samymi ustawieniami"},
    "sub.saved": {"en": "saved {d}", "pl": "zapisano {d}"},
    "sub.voice": {"en": "voice: {v}", "pl": "głos: {v}"},
}

# Widget property → setter used by the live-translation registry.
_I18N_SETTERS = {
    "label": "set_label",
    "text": "set_text",
    "title": "set_title",
    "description": "set_description",
    "subtitle": "set_subtitle",
    "tooltip_text": "set_tooltip_text",
    "placeholder_text": "set_placeholder_text",
}

_lang = "en"


def get_lang() -> str:
    return _lang


def set_lang(lang: str) -> None:
    """Select the active UI language ('en' or 'pl')."""
    global _lang
    _lang = lang if lang in ("en", "pl") else "en"


def tr(key: str, **kw) -> str:
    """Translate `key` (falls back to English, then to the key itself)."""
    entry = STRINGS.get(key, {})
    text = entry.get(_lang) or entry.get("en") or key
    return text.format(**kw) if kw else text


def sort_labels() -> list[str]:
    return [tr("sort.newest"), tr("sort.oldest"), tr("sort.name")]


def _fmt_dur(sec: float) -> str:
    return storage.format_duration(sec)


def _fmt_date(ts: float) -> str:
    return storage.format_date(ts)


def _peaks_from_pcm(data: bytes, buckets: int = WAVE_BARS) -> list[float]:
    """Max-abs peak (0.0–1.0) for each of `buckets` slices of s16le mono."""
    n = len(data) // 2
    if n <= 0:
        return []
    samples = array.array("h")
    samples.frombytes(data[: n * 2])
    if sys.byteorder != "little":
        samples.byteswap()
    peaks: list[float] = []
    for i in range(buckets):
        start = i * n // buckets
        end = max(start + 1, (i + 1) * n // buckets)
        chunk = samples[start:min(end, n)]
        peaks.append(max((abs(s) for s in chunk), default=0) / 32768.0)
    return peaks


def _stop_media(media: Gtk.MediaStream | None) -> None:
    """Pause and rewind a stream (GTK4's MediaStream has no stop())."""
    if media is None:
        return
    media.pause()
    try:
        media.seek(0)
    except Exception:  # noqa: BLE001 — seeking may be unsupported mid-load
        pass


# =========================================================================== #
# Application
# =========================================================================== #
class VoiceForgeApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id="com.voiceforge.VoiceForge",
                         flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.window: "MainWindow | None" = None
        self.connect("activate", self._on_activate)

    def _on_activate(self, _app) -> None:
        storage.ensure_dirs()
        STAGING.mkdir(parents=True, exist_ok=True)
        if self.window is None:
            self.window = MainWindow(application=self)
        self.window.present()


# =========================================================================== #
# Worker (model inference runs off the UI thread)
# =========================================================================== #
class Worker:
    def __init__(self) -> None:
        self._q: queue.Queue = queue.Queue()
        self._pending = 0
        self.on_busy = None      # callback(pending:int) — called on any change
        thread = threading.Thread(target=self._loop, daemon=True,
                                  name="voiceforge-worker")
        thread.start()

    @property
    def pending(self) -> int:
        return self._pending

    def submit(self, task: dict) -> None:
        self._pending += 1
        self._notify()
        self._q.put(task)

    def _notify(self) -> None:
        if self.on_busy:
            GLib.idle_add(self.on_busy, self._pending)

    def _loop(self) -> None:
        while True:
            task = self._q.get()
            raw_status = task.get("status")
            status = (lambda m: GLib.idle_add(raw_status, m)) \
                if raw_status else None
            try:
                if "func" in task:
                    # Generic task (e.g. a whole batch) run off-thread.
                    task["func"](status)
                    out = None
                else:
                    out = engine.ENGINE.synthesize(
                        text=task["text"],
                        speaker_wav=task["speaker_wav"],
                        out_mp3=task["out_mp3"],
                        language=task.get("language", "pl"),
                        pitch=task.get("pitch", 0.0),
                        speed=task.get("speed", 1.0),
                        gain=task.get("gain", 0.0),
                        normalize=task.get("normalize", False),
                        status=status,
                    )
                GLib.idle_add(task.get("ok"), out)
            except Exception as exc:  # noqa: BLE001
                GLib.idle_add(task.get("err"), str(exc))
            finally:
                self._pending -= 1
                self._notify()


# =========================================================================== #
# Main window
# =========================================================================== #
class MainWindow(Adw.ApplicationWindow):
    def __init__(self, **kwargs) -> None:
        super().__init__(default_width=1120, default_height=800,
                         title="VoiceForge", **kwargs)
        storage.ensure_dirs()
        STAGING.mkdir(parents=True, exist_ok=True)

        # Persisted configuration: UI language + loudness normalization.
        self.config = storage.load_config()
        storage.save_config(self.config)        # create the file on first run
        set_lang(self.config.get("lang", "en"))

        # Live-translation registry: (widget, string key, property name).
        self._i18n_regs: list[tuple[Gtk.Widget, str, str]] = []
        # Last status texts: (key, kwargs, raw message) for re-translation.
        self._status_state: tuple[str, dict, str] = ("", {}, "")
        self._batch_status_state: tuple[str, dict, str] = ("", {}, "")

        self.worker = Worker()
        self.worker.on_busy = self._on_busy_changed

        self.voices: list[dict] = []
        self.current_voice: dict | None = None
        self.last_result: dict | None = None
        self.batch_rows: list[dict] = []
        self._recorder: recording.Recorder | None = None
        self._rec_timer_id = 0
        self._preview_media: Gtk.MediaFile | None = None
        self._result_media: Gtk.MediaFile | None = None

        # Waveform + playhead state for the result player.
        self.wave_peaks: list[float] = []
        self.wave_duration = 0.0
        self.wave_gen = 0
        self._playhead_id = 0
        self._playhead_pos = 0.0

        # ------------------------------------------------------------ layout
        self.toast_overlay = Adw.ToastOverlay()
        self.set_content(self.toast_overlay)
        self._fs = False

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.toast_overlay.set_child(outer)

        # Title bar with the ordinary window buttons (minimize / maximize /
        # close come from the HeaderBar's window controls) + fullscreen.
        header = Adw.HeaderBar()
        header.set_title_widget(
            Adw.WindowTitle(title="VoiceForge", subtitle=""))
        btn_fs = Gtk.Button(
            icon_name="view-fullscreen-symbolic",
            tooltip_text="Fullscreen / Pełny ekran")
        btn_fs.connect("clicked", self._toggle_fullscreen)
        header.pack_end(btn_fs)
        outer.append(header)

        root = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        outer.append(root)

        self.stack = Adw.ViewStack()
        sidebar = Adw.ViewSwitcherSidebar(stack=self.stack)
        sidebar.set_size_request(190, -1)
        root.append(sidebar)
        root.append(Gtk.Separator(orientation=Gtk.Orientation.VERTICAL))
        scroller = Gtk.ScrolledWindow(hexpand=True, vexpand=True,
                                      hscrollbar_policy=Gtk.PolicyType.NEVER)
        scroller.set_child(self.stack)
        root.append(scroller)

        self.page_compose = self._build_compose()
        self.page_voices = self._build_voices()
        self.page_queue = self._build_queue()
        self.page_saved = self._build_saved()

        self.T(self.stack.add_titled(self.page_compose, "compose",
                                     tr("page.compose")),
               "page.compose", "title")
        self.T(self.stack.add_titled(self.page_voices, "voices",
                                     tr("page.voices")),
               "page.voices", "title")
        self.T(self.stack.add_titled(self.page_queue, "queue",
                                     tr("page.queue")),
               "page.queue", "title")
        self.T(self.stack.add_titled(self.page_saved, "saved",
                                     tr("page.saved")),
               "page.saved", "title")

        self.refresh_voices()
        self.refresh_saved()

        self.connect("close-request", self._on_close)

    # ------------------------------------------------------------------ util
    def _toggle_fullscreen(self, _btn) -> None:
        self._fs = not self._fs
        if self._fs:
            self.fullscreen()
        else:
            self.unfullscreen()

    def toast(self, message: str) -> None:
        self.toast_overlay.add_toast(Adw.Toast.new(message))

    def alert(self, heading: str, body: str, *, error: bool = False) -> None:
        dlg = Adw.AlertDialog.new(heading, body)
        dlg.add_response("ok", tr("alert.close") if error else tr("alert.ok"))
        dlg.set_default_response("ok")
        dlg.set_close_response("ok")
        dlg.present(self)

    # ------------------------------------------------------------- i18n util
    def T(self, widget, key: str, prop: str = "label"):
        """Register a widget text for live translation and set it now."""
        self._i18n_regs.append((widget, key, prop))
        getattr(widget, _I18N_SETTERS[prop])(tr(key))
        return widget

    def _on_ui_lang_changed(self, dd, *_a) -> None:
        lang = "pl" if dd.get_selected() == 1 else "en"
        if lang == get_lang():
            return
        set_lang(lang)
        storage.set_config("lang", lang)
        self._apply_i18n()

    def _apply_i18n(self) -> None:
        """Re-apply every registered text — no restart needed."""
        for widget, key, prop in self._i18n_regs:
            getattr(widget, _I18N_SETTERS[prop])(tr(key))
        # The sort options live in a model, so rebuild it (keeping selection).
        sel = self.dd_sort.get_selected()
        self.sort_model = Gtk.StringList.new(sort_labels())
        self.dd_sort.set_model(self.sort_model)
        if 0 <= sel < len(SORT_KEYS):
            self.dd_sort.set_selected(sel)
        # Dynamic rows (tooltips/subtitles) are rebuilt with the new strings.
        self.refresh_voices()
        self.refresh_queue()
        self.refresh_saved()
        # Status lines keep showing their last message, now translated.
        self._restore_status()
        self._restore_batch_status()

    # --------------------------------------------------------- status labels
    def _set_status(self, key: str = "", **kw) -> None:
        """Show a translatable status text (empty key clears the label)."""
        self._status_state = (key, kw, "")
        self.lbl_status.set_text(tr(key, **kw) if key else "")

    def _set_status_msg(self, message: str) -> None:
        """Show a raw (engine-produced, non translatable) status text."""
        self._status_state = ("", {}, message)
        self.lbl_status.set_text(message)

    def _restore_status(self) -> None:
        key, kw, raw = self._status_state
        self.lbl_status.set_text(tr(key, **kw) if key else raw)

    def _set_batch_status(self, key: str = "", **kw) -> None:
        self._batch_status_state = (key, kw, "")
        self.lbl_batch_status.set_text(tr(key, **kw) if key else "")

    def _set_batch_status_msg(self, message: str) -> None:
        self._batch_status_state = ("", {}, message)
        self.lbl_batch_status.set_text(message)

    def _restore_batch_status(self) -> None:
        key, kw, raw = self._batch_status_state
        self.lbl_batch_status.set_text(tr(key, **kw) if key else raw)

    def _on_close(self, *_a) -> bool:
        if self._recorder is not None:
            self._recorder.cancel()
        return False

    def _on_busy_changed(self, pending: int) -> None:
        busy = pending > 0
        self.btn_generate.set_sensitive(not busy)
        self.btn_generate_batch.set_sensitive(not busy)
        self.spin.set_visible(busy)
        if busy:
            self.spin.start()
            self._set_batch_status("status.pending_n", n=pending)
        else:
            self.spin.stop()
            self._set_status()
            self._set_batch_status()

    def _header(self, title_widget: Gtk.Widget | None = None) -> Adw.HeaderBar:
        hb = Adw.HeaderBar()
        if title_widget is not None:
            hb.set_title_widget(title_widget)
        return hb

    # ==================================================================== #
    # COMPOSE PAGE
    # ==================================================================== #
    def _build_compose(self) -> Gtk.Widget:
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14,
                       margin_top=18, margin_bottom=18, margin_start=22,
                       margin_end=22)

        # ---- voice + language row --------------------------------------
        frame_v = Gtk.Frame()
        self.T(frame_v, "frame.voice")
        vb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8,
                     margin_top=10, margin_bottom=12, margin_start=12,
                     margin_end=12)
        row1 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.voice_model = Gtk.StringList()
        self.dd_voice = Gtk.DropDown(model=self.voice_model)
        self.dd_voice.set_hexpand(True)
        row1.append(self.dd_voice)

        btn_up = Gtk.Button()
        self.T(btn_up, "btn.upload_sample")
        btn_up.connect("clicked", self._on_upload_voice)
        row1.append(btn_up)

        btn_rec = Gtk.Button()
        self.T(btn_rec, "btn.record_sample")
        btn_rec.connect("clicked", self._on_record_voice)
        row1.append(btn_rec)
        vb.append(row1)

        row_lang = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        row_lang.append(self.T(Gtk.Label(xalign=0), "label.language"))
        self.lang_model = Gtk.StringList.new([name for name, _ in LANGUAGES])
        self.dd_lang = Gtk.DropDown(model=self.lang_model)
        self.dd_lang.set_hexpand(True)
        row_lang.append(self.dd_lang)
        vb.append(row_lang)

        # ---- GUI language switcher (English ⇄ Polish, live) -------------
        row_ui = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        row_ui.append(self.T(Gtk.Label(xalign=0), "label.ui_language"))
        self.ui_lang_model = Gtk.StringList.new(["English", "Polski"])
        self.dd_ui_lang = Gtk.DropDown(model=self.ui_lang_model)
        self.dd_ui_lang.set_hexpand(True)
        self.dd_ui_lang.set_selected(1 if get_lang() == "pl" else 0)
        self.dd_ui_lang.connect("notify::selected", self._on_ui_lang_changed)
        row_ui.append(self.dd_ui_lang)
        vb.append(row_ui)
        frame_v.set_child(vb)
        page.append(frame_v)

        # ---- text box ---------------------------------------------------
        frame_t = Gtk.Frame()
        self.T(frame_t, "frame.text")
        self.tv_text = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR,
                                    top_margin=10, bottom_margin=10,
                                    left_margin=10, right_margin=10)
        self.tv_text.set_size_request(-1, 150)
        tscroll = Gtk.ScrolledWindow(vexpand=True, min_content_height=150)
        tscroll.set_child(self.tv_text)
        frame_t.set_child(tscroll)
        page.append(frame_t)

        # ---- mood / delivery panel -------------------------------------
        frame_d = Gtk.Frame()
        self.T(frame_d, "frame.mood")
        grid = Gtk.Grid(column_spacing=14, row_spacing=8, margin_top=10,
                        margin_bottom=12, margin_start=12, margin_end=12)
        grid.attach(self.T(Gtk.Label(xalign=0), "label.mood"), 0, 0, 1, 1)
        self.mood_model = Gtk.StringList.new(list(MOODS.keys()))
        self.dd_mood = Gtk.DropDown(model=self.mood_model)
        self.dd_mood.connect("notify::selected", self._on_mood_changed)
        grid.attach(self.dd_mood, 1, 0, 2, 1)

        self.sc_loud = self._make_scale(-12.0, 12.0, 0.5, 0.0, " dB")
        self.sc_speed = self._make_scale(0.5, 2.0, 0.05, 1.0, "×")
        self.sc_pitch = self._make_scale(-6.0, 6.0, 0.5, 0.0, " st")
        grid.attach(self.T(Gtk.Label(xalign=0), "label.loudness"), 0, 1, 1, 1)
        grid.attach(self.sc_loud, 1, 1, 2, 1)
        grid.attach(self.T(Gtk.Label(xalign=0), "label.speed"), 0, 2, 1, 1)
        grid.attach(self.sc_speed, 1, 2, 2, 1)
        grid.attach(self.T(Gtk.Label(xalign=0), "label.pitch"), 0, 3, 1, 1)
        grid.attach(self.sc_pitch, 1, 3, 2, 1)

        # ---- auto loudness normalization (persisted in config.json) -----
        self.chk_normalize = Gtk.CheckButton()
        self.T(self.chk_normalize, "btn.normalize")
        self.chk_normalize.set_active(bool(self.config.get("normalize")))
        self.chk_normalize.connect("toggled", self._on_normalize_toggled)
        grid.attach(self.chk_normalize, 0, 4, 3, 1)
        frame_d.set_child(grid)
        page.append(frame_d)

        # ---- generate ---------------------------------------------------
        gen_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.btn_generate = Gtk.Button(hexpand=True)
        self.T(self.btn_generate, "btn.generate")
        self.btn_generate.add_css_class("suggested-action")
        self.btn_generate.add_css_class("pill")
        self.btn_generate.connect("clicked", self._on_generate)
        gen_row.append(self.btn_generate)
        self.spin = Gtk.Spinner()
        gen_row.append(self.spin)
        page.append(gen_row)

        self.lbl_status = Gtk.Label(xalign=0)
        self.lbl_status.add_css_class("dim-label")
        page.append(self.lbl_status)

        # ---- result player (controls + waveform) ------------------------
        frame_r = Gtk.Frame()
        self.T(frame_r, "frame.result")
        self.result_stack = Gtk.Stack()
        placeholder = Adw.StatusPage(
            icon_name="audio-x-generic-symbolic",
        )
        self.T(placeholder, "result.empty.title", "title")
        self.T(placeholder, "result.empty.desc", "description")
        self.result_stack.add_named(placeholder, "empty")

        player_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL,
                             spacing=10, margin_top=8, margin_bottom=12,
                             margin_start=10, margin_end=10)
        self.result_controls = Gtk.MediaControls()
        player_box.append(self.result_controls)

        # Bar-style waveform of the current result + playback playhead.
        self.wave_area = Gtk.DrawingArea(height_request=64)
        self.wave_area.set_draw_func(self._draw_waveform)
        player_box.append(self.wave_area)

        btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL,
                          spacing=8)
        self.btn_save = Gtk.Button()
        self.T(self.btn_save, "btn.save_library")
        self.btn_save.add_css_class("suggested-action")
        self.btn_save.connect("clicked", self._on_save_result)
        btn_row.append(self.btn_save)
        self.btn_export = Gtk.Button()
        self.T(self.btn_export, "btn.export")
        self.btn_export.connect("clicked", self._on_export_result)
        btn_row.append(self.btn_export)
        lbl_hint = Gtk.Label(xalign=0, hexpand=True)
        self.T(lbl_hint, "hint.save")
        lbl_hint.add_css_class("dim-label")
        lbl_hint.add_css_class("small")
        btn_row.append(lbl_hint)
        player_box.append(btn_row)
        self.result_stack.add_named(player_box, "player")
        frame_r.set_child(self.result_stack)
        page.append(frame_r)

        return page

    def _make_scale(self, lo: float, hi: float, step: float, val: float,
                    unit: str) -> Gtk.Scale:
        sc = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, lo, hi, step)
        sc.set_value(val)
        sc.set_draw_value(True)
        sc.set_value_pos(Gtk.PositionType.RIGHT)
        sc.set_digits(2)
        sc.set_hexpand(True)
        sc.set_format_value_func(lambda _sc, v: f"{v:g}{unit}")
        return sc

    def _on_mood_changed(self, dd, *_a) -> None:
        idx = dd.get_selected()
        name = dd.get_string(idx)
        preset = MOODS.get(name)
        if preset:
            self.sc_pitch.set_value(preset["pitch"])
            self.sc_speed.set_value(preset["speed"])
            self.sc_loud.set_value(preset["gain"])

    def _on_normalize_toggled(self, btn) -> None:
        # Persist "normalize" so the choice survives a restart.
        self.config = storage.set_config("normalize", btn.get_active())

    # ---------------------------------------------------------- generation
    def _selected_voice(self) -> dict | None:
        idx = self.dd_voice.get_selected()
        if idx >= 0 and idx < len(self.voices):
            return self.voices[idx]
        return None

    def _selected_language(self) -> str:
        idx = self.dd_lang.get_selected()
        if 0 <= idx < len(LANGUAGES):
            return LANGUAGES[idx][1]
        return "pl"

    def _current_text(self) -> str:
        buf = self.tv_text.get_buffer()
        start, end = buf.get_bounds()
        return buf.get_text(start, end, True).strip()

    def _current_settings(self) -> dict:
        """Snapshot of every UI setting used to (re)generate speech."""
        voice = self._selected_voice()
        return {
            "mood": self.mood_model.get_string(self.dd_mood.get_selected()),
            "pitch": self.sc_pitch.get_value(),
            "speed": self.sc_speed.get_value(),
            "gain": self.sc_loud.get_value(),
            "normalize": bool(self.chk_normalize.get_active()),
            "language": self._selected_language(),
            "voice_id": voice["id"] if voice else "",
            "voice_name": voice["name"] if voice else "",
        }

    def _on_generate(self, _btn) -> None:
        voice = self._selected_voice()
        if voice is None:
            self.toast(tr("toast.need_voice"))
            return
        text = self._current_text()
        if not text:
            self.toast(tr("toast.enter_text"))
            return
        out = STAGING / f"result_{uuid.uuid4().hex[:8]}.mp3"
        mood = self.mood_model.get_string(self.dd_mood.get_selected())
        task = {
            "text": text,
            "speaker_wav": str(storage.voice_path(voice)),
            "out_mp3": str(out),
            "language": self._selected_language(),
            "pitch": self.sc_pitch.get_value(),
            "speed": self.sc_speed.get_value(),
            "gain": self.sc_loud.get_value(),
            "normalize": bool(self.chk_normalize.get_active()),
            "status": lambda m: self._set_status_msg(m),
            "ok": lambda p: self._on_generated(Path(p), text, mood, voice),
            "err": lambda e: self._on_gen_error(e),
        }
        self.worker.submit(task)
        self._set_status("status.queued")

    def _on_gen_error(self, message: str) -> None:
        self._set_status()
        self.alert(tr("alert.gen_failed"), message, error=True)

    def _on_generated(self, path: Path, text: str, mood: str,
                      voice: dict | None) -> None:
        self._set_status("status.done")
        self.last_result = {
            "path": path, "text": text, "mood": mood,
            "voice_name": voice["name"] if voice else "",
        }
        self.play_in_result(path)

    # ------------------------------------------------------- result player
    def play_in_result(self, path: Path) -> None:
        """Load `path` into the result player and redraw its waveform."""
        path = storage.playback_copy(Path(path))
        self._stop_playhead()
        _stop_media(self._result_media)
        media = Gtk.MediaFile.new_for_filename(str(path))
        media.set_loop(False)
        self._result_media = media
        media.connect("notify::playing", self._on_result_playing)
        self.result_controls.set_media_stream(media)
        self.result_stack.set_visible_child_name("player")
        self._start_waveform(Path(path))
        media.play()

    def _on_result_playing(self, media, *_a) -> None:
        # Playhead redraws run only while playback is active (~80 ms ticks).
        if media.get_playing():
            if self._playhead_id == 0:
                self._playhead_id = GLib.timeout_add(80, self._playhead_tick)
        else:
            self._stop_playhead()
            self.wave_area.queue_draw()

    def _stop_playhead(self) -> None:
        if self._playhead_id:
            GLib.source_remove(self._playhead_id)
            self._playhead_id = 0

    def _playhead_tick(self) -> bool:
        media = self._result_media
        if media is None:
            self._playhead_id = 0
            return False
        # get_timestamp()/get_duration() report microseconds; calibrated
        # against ffprobe duration (a 2.0 s file reports 2_000_000 µs).
        ts = media.get_timestamp()
        dur = media.get_duration()
        if dur > 0:
            self.wave_duration = dur / 1_000_000.0
        if ts >= 0:
            self._playhead_pos = ts / 1_000_000.0
        self.wave_area.queue_draw()
        if not media.get_playing():
            self._playhead_id = 0
            return False
        return True

    # ----------------------------------------------------------- waveform
    def _start_waveform(self, path: Path) -> None:
        """Decode the file off-thread and fill the peak bars when ready."""
        self.wave_gen += 1
        self.wave_peaks = []
        self.wave_duration = 0.0
        self._playhead_pos = 0.0
        self.wave_area.queue_draw()
        threading.Thread(target=self._wave_worker, args=(self.wave_gen, path),
                         daemon=True, name="voiceforge-wave").start()

    def _wave_worker(self, token: int, path: Path) -> None:
        peaks: list[float] = []
        duration = 0.0
        try:
            duration = storage.probe_duration(path)
            proc = subprocess.run(
                ["ffmpeg", "-v", "error", "-i", str(path),
                 "-f", "s16le", "-ac", "1", "-ar", str(WAVE_RATE), "-"],
                capture_output=True, timeout=300)
            peaks = _peaks_from_pcm(proc.stdout, WAVE_BARS)
            if duration <= 0:
                duration = len(proc.stdout) / (2 * WAVE_RATE)
        except Exception:  # noqa: BLE001 — a missing file simply shows no bars
            peaks, duration = [], 0.0
        GLib.idle_add(self._wave_ready, token, peaks, duration)

    def _wave_ready(self, token: int, peaks: list[float],
                    duration: float) -> bool:
        if token != self.wave_gen:
            return False                      # a newer file was loaded
        self.wave_peaks = peaks
        if duration > 0:
            self.wave_duration = duration
        self.wave_area.queue_draw()
        return False

    def _draw_waveform(self, _area, cr, width: int, height: int) -> None:
        peaks = self.wave_peaks
        if peaks and width > 0:
            # Blue bars read fine on both light and dark themes.
            cr.set_source_rgba(0.35, 0.55, 0.9, 0.9)
            n = len(peaks)
            slot = width / n
            bar_w = max(1.0, slot - 1.0)
            mid = height / 2.0
            for i, peak in enumerate(peaks):
                bar_h = max(2.0, peak * (height - 4.0))
                x = i * slot + (slot - bar_w) / 2
                cr.rectangle(x, mid - bar_h / 2, bar_w, bar_h)
            cr.fill()
        # Playhead (only once a position is known).
        if self.wave_duration > 0 and self._playhead_pos > 0 and width > 0:
            frac = min(1.0, self._playhead_pos / self.wave_duration)
            cr.set_source_rgba(0.85, 0.3, 0.3, 0.9)
            cr.rectangle(width * frac - 1.0, 0, 2.0, height)
            cr.fill()

    # ------------------------------------------------------- save / export
    def _on_save_result(self, _btn) -> None:
        if not self.last_result:
            return
        try:
            entry = storage.add_saved(
                self.last_result["path"],
                name=self._suggest_name(),
                text=self.last_result["text"],
                mood=self.last_result["mood"],
                voice_name=self.last_result["voice_name"],
                settings=self._current_settings(),
            )
            self.toast(tr("toast.saved_to", name=entry["name"]))
            self.refresh_saved()
        except Exception as exc:  # noqa: BLE001
            self.alert(tr("alert.save_failed"), str(exc), error=True)

    def _suggest_name(self) -> str:
        text = self.last_result["text"] if self.last_result else "speech"
        words = " ".join(text.split()[:6])
        if len(words) > 40:
            words = words[:40].rstrip() + "…"
        return words or "Generated speech"

    def _on_export_result(self, _btn) -> None:
        if not self.last_result:
            return
        dlg = Gtk.FileDialog(initial_name=Path(self.last_result["path"]).name)
        filt = Gtk.FileFilter()
        filt.add_pattern("*.mp3")
        filt.set_name(tr("filter.mp3"))
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(filt)
        dlg.set_filters(filters)

        def on_save(d, res) -> None:
            try:
                gfile = d.save_finish(res)
            except GLib.Error:
                return
            if gfile is None:
                return
            dest = Path(gfile.get_path() or "")
            if not dest:
                return
            try:
                import shutil
                shutil.copy2(self.last_result["path"], dest)
                self.toast(tr("toast.exported_to", path=dest))
            except Exception as exc:  # noqa: BLE001
                self.alert(tr("alert.export_failed"), str(exc), error=True)

        dlg.save(self, None, on_save)

    # ==================================================================== #
    # VOICES PAGE
    # ==================================================================== #
    def _build_voices(self) -> Gtk.Widget:
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                       margin_top=18, margin_bottom=18, margin_start=22,
                       margin_end=22)
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        b1 = Gtk.Button(css_classes=["suggested-action"])
        self.T(b1, "btn.upload_voice")
        b1.connect("clicked", self._on_upload_voice)
        toolbar.append(b1)
        b2 = Gtk.Button()
        self.T(b2, "btn.record_voice")
        b2.connect("clicked", self._on_record_voice)
        toolbar.append(b2)
        page.append(toolbar)

        self.preview_controls = Gtk.MediaControls()
        self.preview_controls.set_visible(False)
        page.append(self.preview_controls)

        self.voice_stack = Gtk.Stack()
        self.voice_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.voice_list.add_css_class("boxed-list")
        scroll = Gtk.ScrolledWindow(vexpand=True)
        scroll.set_child(self.voice_list)
        self.voice_stack.add_named(scroll, "list")
        empty = Adw.StatusPage(icon_name="microphone-symbolic")
        self.T(empty, "voices.empty.title", "title")
        self.T(empty, "voices.empty.desc", "description")
        self.voice_stack.add_named(empty, "empty")
        page.append(self.voice_stack)
        self.voice_list.set_vexpand(True)
        self.voice_stack.set_vexpand(True)
        return page

    def refresh_voices(self) -> None:
        self.voices = storage.list_voices()
        # remember selection
        prev_id = self.current_voice["id"] if self.current_voice else None

        model = Gtk.StringList()
        for v in self.voices:
            model.append(v["name"])
        self.voice_model = model
        self.dd_voice.set_model(model)

        sel = 0
        for i, v in enumerate(self.voices):
            if prev_id and v["id"] == prev_id:
                sel = i
        if self.voices:
            self.dd_voice.set_selected(sel)
            self.current_voice = self.voices[min(sel, len(self.voices) - 1)]
        else:
            self.current_voice = None

        # list rows
        while (row := self.voice_list.get_row_at_index(0)) is not None:
            self.voice_list.remove(row)

        for v in self.voices:
            sub = (f"{_fmt_dur(v['duration'])} · "
                   f"{tr('sub.added', d=_fmt_date(v['created']))} · "
                   f"{v.get('source', '')}")
            row = Adw.ActionRow(title=v["name"], subtitle=sub)
            btn_play = Gtk.Button(icon_name="media-playback-start-symbolic",
                                  tooltip_text=tr("tip.preview"),
                                  valign=Gtk.Align.CENTER)
            btn_play.connect("clicked", self._on_preview_voice, v)
            btn_ren = Gtk.Button(icon_name="edit-symbolic",
                                 tooltip_text=tr("tip.rename"),
                                 valign=Gtk.Align.CENTER)
            btn_ren.connect("clicked", self._on_rename_voice, v)
            btn_del = Gtk.Button(icon_name="user-trash-symbolic",
                                 tooltip_text=tr("tip.delete"),
                                 valign=Gtk.Align.CENTER)
            btn_del.connect("clicked", self._on_delete_voice, v)
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
            for b in (btn_play, btn_ren, btn_del):
                box.append(b)
            row.add_suffix(box)
            self.voice_list.append(row)

        self.voice_stack.set_visible_child_name(
            "list" if self.voices else "empty")

    def _on_preview_voice(self, _btn, v: dict) -> None:
        play = storage.playback_copy(storage.voice_path(v))
        if self._preview_media is not None:
            cur = self._preview_media.get_file()
            if cur is not None and cur.get_path() == str(play) \
                    and self._preview_media.get_playing():
                self._preview_media.pause()
                return
            _stop_media(self._preview_media)
        media = Gtk.MediaFile.new_for_filename(str(play))
        media.set_loop(False)
        self._preview_media = media
        self.preview_controls.set_media_stream(media)
        self.preview_controls.set_visible(True)
        media.play()

    def _name_dialog(self, heading: str, initial: str,
                     callback) -> None:
        dlg = Adw.AlertDialog.new(heading, tr("dialog.give_name"))
        entry = Gtk.Entry(text=initial, activates_default=True)
        entry.set_width_chars(30)
        dlg.set_extra_child(entry)
        dlg.add_response("cancel", tr("dialog.cancel"))
        dlg.add_response("ok", tr("dialog.save"))
        dlg.set_response_appearance("ok", Adw.ResponseAppearance.SUGGESTED)
        dlg.set_default_response("ok")
        dlg.set_close_response("cancel")

        def on_response(d, resp) -> None:
            if resp == "ok":
                callback(entry.get_text().strip())

        dlg.connect("response", on_response)
        dlg.present(self)

    def _on_upload_voice(self, _btn) -> None:
        dlg = Gtk.FileDialog(title=tr("dialog.choose_sample"))
        filt = Gtk.FileFilter()
        filt.set_name(tr("filter.audio"))
        for pat in ("*.mp3", "*.wav", "*.m4a", "*.ogg", "*.flac", "*.opus"):
            filt.add_pattern(pat)
        filt.add_mime_type("audio/*")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(filt)
        dlg.set_filters(filters)

        def on_open(d, res) -> None:
            try:
                gfile = d.open_finish(res)
            except GLib.Error:
                return
            if gfile is None:
                return
            path = gfile.get_path()
            if not path:
                self.alert(tr("alert.upload_failed"),
                           tr("alert.unsupported_loc"), error=True)
                return
            stem = Path(path).stem

            def save_as(name: str) -> None:
                if not name:
                    name = "Untitled voice"
                try:
                    storage.import_voice(path, name)
                except Exception as exc:  # noqa: BLE001
                    self.alert(tr("alert.import_failed"), str(exc),
                               error=True)
                    return
                self.refresh_voices()
                self.toast(tr("toast.voice_added", name=name))

            self._name_dialog(tr("dialog.name_voice"), stem, save_as)

        dlg.open(self, None, on_open)

    def _on_rename_voice(self, _btn, v: dict) -> None:
        def rename(new_name: str) -> None:
            if not new_name:
                return
            storage.rename_voice(v["id"], new_name)
            self.refresh_voices()
            self.toast(tr("toast.voice_renamed"))

        self._name_dialog(tr("dialog.rename_voice"), v["name"], rename)

    def _on_delete_voice(self, _btn, v: dict) -> None:
        dlg = Adw.AlertDialog.new(
            tr("dialog.delete_voice_q", name=v["name"]),
            tr("dialog.delete_voice_body"))
        dlg.add_response("cancel", tr("dialog.cancel"))
        dlg.add_response("del", tr("dialog.delete"))
        dlg.set_response_appearance("del", Adw.ResponseAppearance.DESTRUCTIVE)
        dlg.set_default_response("cancel")
        dlg.set_close_response("cancel")

        def on_response(d, resp) -> None:
            if resp == "del":
                storage.delete_voice(v["id"])
                if self.current_voice and self.current_voice["id"] == v["id"]:
                    self.current_voice = None
                self.refresh_voices()
                self.toast(tr("toast.voice_deleted"))

        dlg.connect("response", on_response)
        dlg.present(self)

    # ------------------------------------------------------- record dialog
    def _on_record_voice(self, _btn) -> None:
        win = Adw.Window(transient_for=self, modal=True,
                         title=tr("dialog.record_title"), default_width=440)
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        win.set_content(outer)
        hb = Adw.HeaderBar()
        hb.set_title_widget(Adw.WindowTitle(title=tr("dialog.record_title"),
                                            subtitle=""))
        outer.append(hb)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14,
                      margin_top=10, margin_bottom=18, margin_start=18,
                      margin_end=18)
        outer.append(box)

        hint = Gtk.Label(wrap=True, xalign=0)
        hint.set_text(tr("record.hint"))
        hint.add_css_class("dim-label")
        box.append(hint)

        src_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        src_row.append(Gtk.Label(label=tr("record.mic"), xalign=0))
        sources = recording.list_sources()
        src_model = Gtk.StringList.new([lbl for _, lbl in sources])
        dd_src = Gtk.DropDown(model=src_model)
        dd_src.set_hexpand(True)
        src_row.append(dd_src)
        box.append(src_row)

        self.lbl_timer = Gtk.Label(label=tr("record.timer"))
        self.lbl_timer.add_css_class("title-1")
        box.append(self.lbl_timer)

        btn_toggle = Gtk.Button(label=tr("record.start"))
        btn_toggle.add_css_class("suggested-action")
        box.append(btn_toggle)

        state = {"rec": None, "wav": None}

        def tick() -> bool:
            rec = state["rec"]
            if rec is None or not rec.running:
                return False
            self.lbl_timer.set_text(
                f"{_fmt_dur(rec.elapsed)} / 10:00")
            if rec.elapsed >= recording.MAX_RECORD_SECONDS:
                stop_rec()
                return False
            return True

        def start_rec() -> None:
            idx = dd_src.get_selected()
            src = sources[idx][0] if 0 <= idx < len(sources) else "default"
            wav = STAGING / f"rec_{uuid.uuid4().hex[:8]}.wav"
            state["rec"] = recording.Recorder(src, wav)
            state["wav"] = wav
            state["rec"].start()
            btn_toggle.set_label(tr("record.stop"))
            btn_toggle.remove_css_class("suggested-action")
            btn_toggle.add_css_class("destructive-action")
            self._rec_timer_id = GLib.timeout_add(250, tick)

        def stop_rec() -> None:
            rec = state["rec"]
            if rec is None:
                return
            dur = rec.stop()
            btn_toggle.set_label(tr("record.start_again"))
            btn_toggle.remove_css_class("destructive-action")
            btn_toggle.add_css_class("suggested-action")
            self.lbl_timer.set_text(tr("record.recorded", d=_fmt_dur(dur)))
            btn_save_rec.set_sensitive(dur >= storage.MIN_SAMPLE_SECONDS)

        def on_toggle(_b) -> None:
            if state["rec"] is not None and state["rec"].running:
                stop_rec()
            else:
                start_rec()

        btn_toggle.connect("clicked", on_toggle)

        btn_save_rec = Gtk.Button(label=tr("record.save_first"),
                                  sensitive=False,
                                  css_classes=["suggested-action"])
        box.append(btn_save_rec)

        def on_save_rec(_b) -> None:
            wav = state["wav"]
            win.close()

            def save_as(name: str) -> None:
                if not name:
                    name = "Recorded voice"
                try:
                    storage.add_recorded_voice(wav, name)
                except Exception as exc:  # noqa: BLE001
                    Path(wav).unlink(missing_ok=True)
                    self.alert(tr("alert.record_failed"), str(exc),
                               error=True)
                    return
                self.refresh_voices()
                self.toast(tr("toast.voice_added", name=name))

            self._name_dialog(tr("dialog.name_voice"),
                              tr("record.default_name"), save_as)

        btn_save_rec.connect("clicked", on_save_rec)

        def on_close(w, *_a) -> bool:
            rec = state["rec"]
            if rec is not None:
                if rec.running:
                    rec.cancel()
                else:
                    Path(state["wav"]).unlink(missing_ok=True)
            self.refresh_voices()
            return False

        win.connect("close-request", on_close)
        win.present()

    # ==================================================================== #
    # BATCH QUEUE PAGE
    # ==================================================================== #
    def _build_queue(self) -> Gtk.Widget:
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                       margin_top=18, margin_bottom=18, margin_start=22,
                       margin_end=22)
        info = Gtk.Label(xalign=0, wrap=True)
        self.T(info, "queue.info")
        info.add_css_class("dim-label")
        page.append(info)

        frame = Gtk.Frame()
        self.T(frame, "frame.new_entry")
        self.tv_queue = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR,
                                     top_margin=8, bottom_margin=8,
                                     left_margin=8, right_margin=8)
        self.tv_queue.set_size_request(-1, 80)
        qs = Gtk.ScrolledWindow(min_content_height=80)
        qs.set_child(self.tv_queue)
        frame.set_child(qs)
        page.append(frame)

        btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        add = Gtk.Button()
        self.T(add, "btn.add_queue")
        add.connect("clicked", self._on_queue_add)
        btn_row.append(add)
        imp = Gtk.Button()
        self.T(imp, "btn.import_txt")
        imp.connect("clicked", self._on_queue_import_txt)
        btn_row.append(imp)
        self.btn_generate_batch = Gtk.Button(css_classes=["suggested-action"])
        self.T(self.btn_generate_batch, "btn.generate_all")
        self.btn_generate_batch.connect("clicked", self._on_queue_run)
        btn_row.append(self.btn_generate_batch)
        clear = Gtk.Button()
        self.T(clear, "btn.clear_finished")
        clear.connect("clicked", lambda _b: self._on_queue_clear())
        btn_row.append(clear)
        page.append(btn_row)

        self.lbl_batch_status = Gtk.Label(xalign=0)
        self.lbl_batch_status.add_css_class("dim-label")
        page.append(self.lbl_batch_status)

        self.queue_stack = Gtk.Stack()
        self.queue_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.queue_list.add_css_class("boxed-list")
        qs2 = Gtk.ScrolledWindow(vexpand=True)
        qs2.set_child(self.queue_list)
        self.queue_stack.add_named(qs2, "list")
        empty = Adw.StatusPage(icon_name="view-list-symbolic")
        self.T(empty, "queue.empty.title", "title")
        self.T(empty, "queue.empty.desc", "description")
        self.queue_stack.add_named(empty, "empty")
        page.append(self.queue_stack)
        self.queue_stack.set_vexpand(True)
        return page

    def _on_queue_add(self, _btn) -> None:
        buf = self.tv_queue.get_buffer()
        start, end = buf.get_bounds()
        text = buf.get_text(start, end, True).strip()
        if not text:
            self.toast(tr("toast.type_first"))
            return
        self.batch_rows.append({"id": uuid.uuid4().hex[:8], "text": text,
                                "status": "pending", "path": None})
        buf.set_text("")
        self.refresh_queue()

    def _on_queue_import_txt(self, _btn) -> None:
        """Import every non-empty line of a .txt file as a queue row."""
        dlg = Gtk.FileDialog(title=tr("dialog.import_txt"))
        filt = Gtk.FileFilter()
        filt.set_name(tr("filter.txt"))
        filt.add_pattern("*.txt")
        filt.add_mime_type("text/plain")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(filt)
        dlg.set_filters(filters)

        def on_open(d, res) -> None:
            try:
                gfile = d.open_finish(res)
            except GLib.Error:
                return
            if gfile is None:
                return
            path = gfile.get_path()
            if not path:
                self.alert(tr("alert.txt_failed"),
                           tr("alert.unsupported_loc"), error=True)
                return
            try:
                content = Path(path).read_text(encoding="utf-8")
            except Exception as exc:  # noqa: BLE001
                self.alert(tr("alert.txt_failed"), str(exc), error=True)
                return
            count = self._queue_add_lines(content)
            self.refresh_queue()
            self.toast(tr("toast.added_n", n=count))

        dlg.open(self, None, on_open)

    def _queue_add_lines(self, content: str) -> int:
        """Append every non-empty line as its own queue row (text view
        untouched). Returns how many rows were added."""
        count = 0
        for line in content.splitlines():
            line = line.strip()
            if not line:
                continue
            self.batch_rows.append({"id": uuid.uuid4().hex[:8],
                                    "text": line, "status": "pending",
                                    "path": None})
            count += 1
        return count

    def _on_queue_clear(self) -> None:
        self.batch_rows = [r for r in self.batch_rows
                           if r["status"] not in ("done", "error")]
        self.refresh_queue()

    def refresh_queue(self) -> None:
        while (row := self.queue_list.get_row_at_index(0)) is not None:
            self.queue_list.remove(row)
        for item in self.batch_rows:
            status_text = {
                "pending": tr("queue.pending"),
                "running": tr("queue.running"),
                "done": tr("queue.saved_at", d=_fmt_date(item["saved_at"]))
                        if item.get("saved_at") else tr("queue.saved"),
                "error": tr("queue.error",
                            e=item.get("error", "failed")[:80]),
            }.get(item["status"], item["status"])
            row = Adw.ActionRow(title=item["text"][:90] +
                                ("…" if len(item["text"]) > 90 else ""),
                                subtitle=status_text)
            if item["status"] == "done" and item.get("path"):
                btn_play = Gtk.Button(
                    icon_name="media-playback-start-symbolic",
                    tooltip_text=tr("tip.play_result"),
                    valign=Gtk.Align.CENTER)
                btn_play.connect("clicked",
                                 lambda _b, it=item: self._play_queue(it))
                row.add_suffix(btn_play)
            btn_x = Gtk.Button(icon_name="user-trash-symbolic",
                               tooltip_text=tr("tip.remove_queue"),
                               valign=Gtk.Align.CENTER)
            btn_x.connect("clicked", self._on_queue_remove, item)
            row.add_suffix(btn_x)
            self.queue_list.append(row)
        self.queue_stack.set_visible_child_name(
            "list" if self.batch_rows else "empty")

    def _on_queue_remove(self, _btn, item: dict) -> None:
        if item["status"] == "running":
            self.toast(tr("toast.item_running"))
            return
        self.batch_rows = [r for r in self.batch_rows if r is not item]
        self.refresh_queue()

    def _play_queue(self, item: dict) -> None:
        # Batch results are already in the library; load one into the player.
        for entry in storage.list_saved():
            if entry["id"] == Path(item["path"]).stem or \
                    entry["file"] == item["path"]:
                path = storage.saved_path(entry)
                self.last_result = {
                    "path": path, "text": item["text"],
                    "mood": entry.get("mood", ""),
                    "voice_name": entry.get("voice_name", ""),
                }
                break
        else:
            path = storage.SAVED_DIR / item["path"]
        self.stack.set_visible_child_name("compose")
        self.play_in_result(path)

    def _on_queue_run(self, _btn) -> None:
        voice = self._selected_voice()
        if voice is None:
            self.toast(tr("toast.need_voice"))
            return
        pending = [r for r in self.batch_rows if r["status"] == "pending"]
        if not pending:
            self.toast(tr("toast.nothing_pending"))
            return
        settings = self._current_settings()
        language = settings["language"]
        pitch = settings["pitch"]
        speed = settings["speed"]
        gain = settings["gain"]
        normalize = settings["normalize"]
        mood = settings["mood"]

        from .engine import ENGINE

        def status(msg: str) -> None:
            self._set_batch_status_msg(msg)
            self._set_status_msg(msg)

        def ok(_out) -> None:
            self.refresh_queue()
            self.refresh_saved()
            self._set_batch_status("status.batch_finished")

        def err(message: str) -> None:
            self._set_batch_status()
            self.alert(tr("alert.gen_failed"), message, error=True)

        # Runs inside the worker thread; walks the pending items in order.
        def run_all(status_cb) -> None:
            total = len(pending)
            for n, item in enumerate(pending, 1):
                item["status"] = "running"
                GLib.idle_add(self.refresh_queue)
                out = STAGING / f"batch_{item['id']}.mp3"
                try:
                    ENGINE.synthesize(
                        text=item["text"],
                        speaker_wav=str(storage.voice_path(voice)),
                        out_mp3=str(out),
                        language=language, pitch=pitch, speed=speed,
                        gain=gain, normalize=normalize,
                        status=(lambda m, n=n:
                                status_cb(f"Batch {n}/{total} — {m}"))
                        if status_cb else None,
                    )
                    entry = storage.add_saved(
                        out, name=" ".join(item["text"].split()[:6])[:40],
                        text=item["text"], mood=mood,
                        voice_name=voice["name"],
                        settings=dict(settings, voice_id=voice["id"],
                                      voice_name=voice["name"], mood=mood))
                    item.update(status="done", path=entry["file"],
                                saved_at=entry["created"],
                                saved_name=entry["name"])
                except Exception as exc:  # noqa: BLE001
                    item.update(status="error", error=str(exc))
                GLib.idle_add(self.refresh_queue)

        self.worker.submit({"func": run_all, "status": status,
                            "ok": ok, "err": err})
        self.refresh_queue()

    # ==================================================================== #
    # SAVED PAGE
    # ==================================================================== #
    def _build_saved(self) -> Gtk.Widget:
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                       margin_top=18, margin_bottom=18, margin_start=22,
                       margin_end=22)
        toolbar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.entry_search = Gtk.SearchEntry()
        self.T(self.entry_search, "search.saved", "placeholder_text")
        self.entry_search.set_hexpand(True)
        self.entry_search.connect("search-changed",
                                  lambda *_: self.refresh_saved())
        toolbar.append(self.entry_search)
        self.sort_model = Gtk.StringList.new(sort_labels())
        self.dd_sort = Gtk.DropDown(model=self.sort_model)
        self.dd_sort.connect("notify::selected", lambda *_: self.refresh_saved())
        toolbar.append(self.dd_sort)
        page.append(toolbar)

        self.saved_stack = Gtk.Stack()
        self.saved_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.saved_list.add_css_class("boxed-list")
        sc = Gtk.ScrolledWindow(vexpand=True)
        sc.set_child(self.saved_list)
        self.saved_stack.add_named(sc, "list")
        empty = Adw.StatusPage(icon_name="document-save-symbolic")
        self.T(empty, "saved.empty.title", "title")
        self.T(empty, "saved.empty.desc", "description")
        self.saved_stack.add_named(empty, "empty")
        page.append(self.saved_stack)
        self.saved_stack.set_vexpand(True)
        return page

    def refresh_saved(self, *_a) -> None:
        query = self.entry_search.get_text() if hasattr(self, "entry_search") \
            else ""
        sort_idx = self.dd_sort.get_selected() if hasattr(self, "dd_sort") else 0
        key = SORT_KEYS[sort_idx] if 0 <= sort_idx < len(SORT_KEYS) else "date_desc"
        items = storage.list_saved(sort=key, query=query)

        while (row := self.saved_list.get_row_at_index(0)) is not None:
            self.saved_list.remove(row)

        for it in items:
            sub = (f"{tr('sub.saved', d=_fmt_date(it['created']))} · "
                   f"{_fmt_dur(it['duration'])}")
            if it.get("voice_name"):
                sub += " · " + tr("sub.voice", v=it["voice_name"])
            if it.get("mood"):
                sub += f" · {it['mood']}"
            row = Adw.ActionRow(title=it["name"], subtitle=sub,
                                subtitle_lines=2)
            if it.get("text"):
                row.add_prefix(Gtk.Label(label=it["text"][:60] + ("…"
                               if len(it["text"]) > 60 else ""),
                               xalign=0, margin_start=4))
            box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
            btn_play = Gtk.Button(icon_name="media-playback-start-symbolic",
                                  tooltip_text=tr("tip.play"),
                                  valign=Gtk.Align.CENTER)
            btn_play.connect("clicked", self._on_play_saved, it)
            btn_regen = Gtk.Button(icon_name="view-refresh-symbolic",
                                   tooltip_text=tr("tip.regenerate"),
                                   valign=Gtk.Align.CENTER)
            btn_regen.connect("clicked", self._on_regen_saved, it)
            btn_dl = Gtk.Button(icon_name="download-symbolic",
                                tooltip_text=tr("tip.download"),
                                valign=Gtk.Align.CENTER)
            btn_dl.connect("clicked", self._on_download_saved, it)
            btn_del = Gtk.Button(icon_name="user-trash-symbolic",
                                 tooltip_text=tr("tip.delete"),
                                 valign=Gtk.Align.CENTER)
            btn_del.connect("clicked", self._on_delete_saved, it)
            for b in (btn_play, btn_regen, btn_dl, btn_del):
                box.append(b)
            row.add_suffix(box)
            self.saved_list.append(row)

        self.saved_stack.set_visible_child_name(
            "list" if items else "empty")

    def _on_play_saved(self, _btn, it: dict) -> None:
        path = storage.saved_path(it)
        self.last_result = {"path": path, "text": it.get("text", ""),
                            "mood": it.get("mood", ""),
                            "voice_name": it.get("voice_name", "")}
        self.stack.set_visible_child_name("compose")
        self.play_in_result(path)

    def _resolve_voice(self, settings: dict) -> dict | None:
        """Find the voice a saved entry was generated with (id, then name)."""
        voices = storage.list_voices()
        vid = settings.get("voice_id")
        if vid:
            for v in voices:
                if v["id"] == vid:
                    return v
        vname = settings.get("voice_name")
        if vname:
            for v in voices:
                if v["name"] == vname:
                    return v
        return None

    def _on_regen_saved(self, _btn, it: dict) -> None:
        """Regenerate a saved entry with its stored text and settings."""
        text = it.get("text", "").strip()
        if not text:
            self.toast(tr("toast.regen_no_text"))
            return
        settings = it.get("settings") or self._current_settings()
        voice = self._resolve_voice(settings)
        if voice is None:
            self.toast(tr("toast.regen_no_voice"))
            return
        mood = settings.get("mood") or it.get("mood", "")
        out = STAGING / f"result_{uuid.uuid4().hex[:8]}.mp3"
        task = {
            "text": text,
            "speaker_wav": str(storage.voice_path(voice)),
            "out_mp3": str(out),
            "language": settings.get("language") or self._selected_language(),
            "pitch": float(settings.get("pitch", 0.0)),
            "speed": float(settings.get("speed", 1.0)),
            "gain": float(settings.get("gain", 0.0)),
            "normalize": bool(settings.get("normalize", False)),
            "status": lambda m: self._set_status_msg(m),
            "ok": lambda p: self._on_generated(Path(p), text, mood, voice),
            "err": lambda e: self._on_gen_error(e),
        }
        self.stack.set_visible_child_name("compose")
        self.worker.submit(task)
        self._set_status("status.queued")

    def _on_download_saved(self, _btn, it: dict) -> None:
        dlg = Gtk.FileDialog(title=tr("dialog.download_title"),
                             initial_name=f"{it['name']}.mp3")
        filt = Gtk.FileFilter()
        filt.add_pattern("*.mp3")
        filt.set_name(tr("filter.mp3"))
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(filt)
        dlg.set_filters(filters)

        def on_save(d, res) -> None:
            try:
                gfile = d.save_finish(res)
            except GLib.Error:
                return
            if gfile is None:
                return
            dest = gfile.get_path()
            if not dest:
                return
            if not dest.lower().endswith(".mp3"):
                dest += ".mp3"
            try:
                storage.export_saved(it["id"], Path(dest))
                self.toast(tr("toast.downloaded_to", path=dest))
            except Exception as exc:  # noqa: BLE001
                self.alert(tr("alert.download_failed"), str(exc), error=True)

        dlg.save(self, None, on_save)

    def _on_delete_saved(self, _btn, it: dict) -> None:
        dlg = Adw.AlertDialog.new(
            tr("dialog.delete_saved_q", name=it["name"]),
            tr("dialog.delete_saved_body"))
        dlg.add_response("cancel", tr("dialog.cancel"))
        dlg.add_response("del", tr("dialog.delete"))
        dlg.set_response_appearance("del", Adw.ResponseAppearance.DESTRUCTIVE)
        dlg.set_default_response("cancel")
        dlg.set_close_response("cancel")

        def on_response(d, resp) -> None:
            if resp == "del":
                storage.delete_saved(it["id"])
                self.refresh_saved()
                self.toast(tr("toast.file_deleted"))

        dlg.connect("response", on_response)
        dlg.present(self)


def main() -> None:
    app = VoiceForgeApp()
    app.run(None)


if __name__ == "__main__":
    main()

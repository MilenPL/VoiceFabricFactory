package com.voiceforge.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tiny i18n table — English + Polish. Screens call Strings.t("key");
 * changing [Strings.lang] flips a snapshot state and recomposes everything.
 *
 * Keys: extend this table with every string the UI shows (same coverage as the
 * Linux app: tabs, frames, buttons, hints, empty states, dialogs, toasts,
 * status messages, sort options, tooltips…).
 */
object Strings {
    var lang: String by mutableStateOf("en")

    private val TABLE: Map<String, Array<String>> = mapOf(
        // key            en                                  pl
        "tab_compose" to arrayOf("Compose", "Tworzenie"),
        "tab_voices" to arrayOf("Voices", "Głosy"),
        "tab_queue" to arrayOf("Batch queue", "Kolejka"),
        "tab_saved" to arrayOf("Saved", "Zapisane"),
        "language" to arrayOf("Language", "Język"),
        "voice" to arrayOf("Voice", "Głos"),
        "text" to arrayOf("Text", "Tekst"),
        "mood_delivery" to arrayOf("Mood & delivery", "Nastrój i sposób mówienia"),
        "mood" to arrayOf("Mood", "Nastrój"),
        "loudness" to arrayOf("Loudness", "Głośność"),
        "speed" to arrayOf("Speed", "Tempo"),
        "pitch" to arrayOf("Pitch", "Wysokość"),
        "normalize" to arrayOf("Auto normalize loudness (EBU R128)",
            "Auto normalizuj głośność (EBU R128)"),
        "generate" to arrayOf("Generate speech", "Generuj mowę"),
        "result" to arrayOf("Result", "Wynik"),
        "nothing_generated" to arrayOf("Nothing generated yet", "Jeszcze nic nie wygenerowano"),
        "nothing_generated_desc" to arrayOf(
            "Generated MP3 files will appear here.",
            "Wygenerowane pliki MP3 pojawią się tutaj."),
        "save_library" to arrayOf("Save to library", "Zapisz w bibliotece"),
        "export" to arrayOf("Export / download…", "Eksportuj / pobierz…"),
        "upload_sample" to arrayOf("Upload sample…", "Wgraj próbkę…"),
        "record_sample" to arrayOf("Record sample…", "Nagraj próbkę…"),
        "upload_voice" to arrayOf("Upload new voice (sample)…", "Wgraj nowy głos (próbkę)…"),
        "record_voice" to arrayOf("Record new voice…", "Nagraj nowy głos…"),
        "search_saved" to arrayOf("Search saved files…", "Szukaj zapisanych plików…"),
        "sort_newest" to arrayOf("Newest first", "Najpierw najnowsze"),
        "sort_oldest" to arrayOf("Oldest first", "Najpierw najstarsze"),
        "sort_name" to arrayOf("Name A–Z", "Nazwa A–Z"),
        "add_queue" to arrayOf("Add to queue", "Dodaj do kolejki"),
        "generate_all" to arrayOf("Generate all", "Generuj wszystko"),
        "clear_finished" to arrayOf("Clear finished", "Wyczyść ukończone"),
        "import_txt" to arrayOf("Import .txt…", "Importuj .txt…"),
        "new_entry" to arrayOf("New entry", "Nowy wpis"),
        "queue_empty" to arrayOf("Queue is empty", "Kolejka jest pusta"),
        "queue_empty_desc" to arrayOf(
            "Add texts above to generate them in one go.",
            "Dodaj teksty powyżej, aby wygenerować je naraz."),
        "no_voices" to arrayOf("No voices yet", "Brak głosów"),
        "no_voices_desc" to arrayOf(
            "Upload an MP3 sample (2–5 minutes of clear speech) or record one with your microphone.",
            "Wgraj próbkę MP3 (2–5 minut wyraźnej mowy) lub nagraj ją mikrofonem."),
        "no_saved" to arrayOf("No saved files yet", "Brak zapisanych plików"),
        "no_saved_desc" to arrayOf(
            "Generate speech on the Compose page and press “Save to library”.",
            "Wygeneruj mowę na stronie Tworzenie i naciśnij „Zapisz w bibliotece”."),
        "preview" to arrayOf("Preview", "Podgląd"),
        "rename" to arrayOf("Rename", "Zmień nazwę"),
        "delete" to arrayOf("Delete", "Usuń"),
        "download" to arrayOf("Download / save as…", "Pobierz / zapisz jako…"),
        "play" to arrayOf("Play", "Odtwórz"),
        "regenerate" to arrayOf("Regenerate with same settings",
            "Wygeneruj ponownie z tymi samymi ustawieniami"),
        "done" to arrayOf("Done ✓", "Gotowe ✓"),
        "queued" to arrayOf("Queued…", "W kolejce…"),
        "batch_finished" to arrayOf("Batch finished ✓", "Partia zakończona ✓"),
        "saved_toast" to arrayOf("Saved to library", "Zapisano w bibliotece"),
        "enter_text" to arrayOf("Enter some text to speak.", "Wpisz tekst do wypowiedzenia."),
        "need_voice" to arrayOf(
            "Upload or record a voice sample first (Voices tab).",
            "Najpierw wgraj lub nagraj próbkę głosu (zakładka Głosy)."),
        "model_loading" to arrayOf("Preparing the model…", "Przygotowywanie modelu…"),
        "synthesizing" to arrayOf("Synthesizing speech…", "Generowanie mowy…"),
        "applying_effects" to arrayOf("Applying mood and loudness…",
            "Stosowanie nastroju i głośności…"),
        "encoding" to arrayOf("Encoding MP3…", "Kodowanie MP3…"),
        "analyzing_voice" to arrayOf("Analyzing voice sample…", "Analizowanie próbki głosu…"),
        "download_model" to arrayOf("Download AI model", "Pobierz model AI"),
        "cancel" to arrayOf("Cancel", "Anuluj"),
        "close" to arrayOf("Close", "Zamknij"),
        "ok" to arrayOf("OK", "OK"),
        "save" to arrayOf("Save", "Zapisz"),
        "record" to arrayOf("Record", "Nagraj"),
        "stop" to arrayOf("Stop", "Stop"),
        "failed" to arrayOf("Generation failed", "Generacja nie powiodła się"),

        // ---- UI language switcher (Compose tab) ------------------------
        "ui_language" to arrayOf("UI language", "Język interfejsu"),

        // ---- theme switcher (Compose tab) ------------------------------
        "theme" to arrayOf("Theme", "Motyw"),
        "theme_system" to arrayOf("System", "Systemowy"),
        "theme_light" to arrayOf("Light", "Jasny"),
        "theme_dark" to arrayOf("Dark", "Ciemny"),

        // ---- voice entry: upload + naming ------------------------------
        "name_voice" to arrayOf("Name this voice", "Nadaj nazwę głosu"),
        "name_voice_desc" to arrayOf("Give it a recognizable name.",
            "Nadaj mu rozpoznawalną nazwę."),
        "untitled_voice" to arrayOf("Untitled voice", "Głos bez nazwy"),
        "default_voice_name" to arrayOf("My voice", "Mój głos"),
        "voice_added" to arrayOf("Voice “{name}” added.", "Dodano głos „{name}”."),
        "too_short_title" to arrayOf("Sample too short", "Próbka za krótka"),
        "sample_too_short" to arrayOf(
            "The sample is only {sec} s long — XTTS needs at least 6 seconds of clean speech.",
            "Próbka ma tylko {sec} s — XTTS wymaga co najmniej 6 sekund czystej mowy."),
        "upload_failed" to arrayOf("Upload failed", "Nie udało się wgrać pliku"),
        "import_failed" to arrayOf("Could not import the sample",
            "Nie udało się zaimportować próbki"),

        // ---- record dialog ---------------------------------------------
        "record_title" to arrayOf("Record a voice sample", "Nagraj próbkę głosu"),
        "record_hint" to arrayOf(
            "Record 2–5 minutes of clear, continuous speech in a quiet room (reading a book works well).",
            "Nagraj 2–5 minut wyraźnej, ciągłej mowy w cichym pokoju (dobrze sprawdza się czytanie książki)."),
        "mic_rationale" to arrayOf(
            "Microphone access is required to record a voice sample.",
            "Potrzebny jest dostęp do mikrofonu, aby nagrać próbkę głosu."),
        "mic_grant" to arrayOf("Allow microphone", "Zezwól na mikrofon"),
        "mic_denied" to arrayOf("Microphone permission denied.",
            "Odmówiono dostępu do mikrofonu."),
        "recording" to arrayOf("Recording…", "Nagrywanie…"),
        "recorded" to arrayOf("(recorded)", "(nagrano)"),
        "record_min" to arrayOf("Record at least 6 s before saving.",
            "Przed zapisem nagraj co najmniej 6 s."),
        "record_failed" to arrayOf("Could not access the microphone.",
            "Nie udało się uzyskać dostępu do mikrofonu."),

        // ---- result player ---------------------------------------------
        "pause" to arrayOf("Pause", "Wstrzymaj"),
        "save_hint" to arrayOf("Saved files appear in the Saved tab.",
            "Zapisane pliki znajdziesz w zakładce Zapisane."),
        "saved_as" to arrayOf("Saved to library: {name}",
            "Zapisano w bibliotece: {name}"),
        "save_failed" to arrayOf("Could not save to the library",
            "Nie udało się zapisać w bibliotece"),

        // ---- export / download -----------------------------------------
        "exported_to" to arrayOf("Exported: {name}", "Wyeksportowano: {name}"),
        "export_failed" to arrayOf("Export failed", "Eksport nie powiódł się"),
        "storage_rationale" to arrayOf(
            "Storage permission is needed to write into Downloads.",
            "Potrzebne jest pozwolenie na zapis, aby zapisać w Pobranych."),

        // ---- voices list -------------------------------------------------
        "added_on" to arrayOf("added {date}", "dodano {date}"),
        "src_upload" to arrayOf("upload", "wgrany"),
        "src_record" to arrayOf("record", "nagrany"),
        "rename_voice" to arrayOf("Rename voice", "Zmień nazwę głosu"),
        "voice_renamed" to arrayOf("Voice renamed.", "Zmieniono nazwę głosu."),
        "voice_deleted" to arrayOf("Voice deleted.", "Głos usunięty."),
        "delete_voice_title" to arrayOf("Delete “{name}”?", "Usunąć „{name}”?"),
        "delete_voice_msg" to arrayOf(
            "The sample will be removed from the app's storage. Files generated with this voice stay in Saved.",
            "Próbka zostanie usunięta z pamięci aplikacji. Pliki wygenerowane tym głosem pozostają w Zapisanych."),
        "preview_playing" to arrayOf("Playing: {name}", "Odtwarzanie: {name}"),
        "preview_paused" to arrayOf("Paused: {name}", "Wstrzymano: {name}"),
        "preview_idle" to arrayOf("Not playing", "Brak odtwarzania"),

        // ---- batch queue --------------------------------------------------
        "queue_info" to arrayOf(
            "Add one text per entry, then generate them all one after another. Every finished file is saved to the Saved tab automatically (the current voice, mood and delivery settings are used).",
            "Dodaj po jednym tekście na wpis, a potem wygeneruj je wszystkie po kolei. Każdy ukończony plik trafia automatycznie do zakładki Zapisane (używane są bieżący głos, nastrój i ustawienia mowy)."),
        "type_first" to arrayOf("Type something first.", "Najpierw wpisz jakiś tekst."),
        "nothing_pending" to arrayOf("Nothing pending in the queue.",
            "Brak oczekujących wpisów w kolejce."),
        "item_running" to arrayOf("This item is generating right now.",
            "Ten element jest właśnie generowany."),
        "remove_entry" to arrayOf("Remove from queue", "Usuń z kolejki"),
        "play_result" to arrayOf("Play result", "Odtwórz wynik"),
        "status_waiting" to arrayOf("⏳ waiting", "⏳ oczekuje"),
        "status_running" to arrayOf("⚙ generating…", "⚙ generowanie…"),
        "status_saved" to arrayOf("✓ saved {date}", "✓ zapisano {date}"),
        "status_saved_now" to arrayOf("✓ saved", "✓ zapisano"),
        "status_error" to arrayOf("✗ {error}", "✗ {error}"),
        "batch_of" to arrayOf("Batch {n}/{total}", "Partia {n}/{total}"),
        "queue_added_1" to arrayOf("Added 1 entry", "Dodano 1 wpis"),
        "queue_added_few" to arrayOf("Added {n} entries", "Dodano {n} wpisy"),
        "queue_added_many" to arrayOf("Added {n} entries", "Dodano {n} wpisów"),
        "read_failed" to arrayOf("Could not read that file.",
            "Nie udało się odczytać tego pliku."),

        // ---- saved library ------------------------------------------------
        "saved_on" to arrayOf("saved {date}", "zapisano {date}"),
        "voice_lower" to arrayOf("voice", "głos"),
        "delete_file_title" to arrayOf("Delete “{name}”?", "Usunąć „{name}”?"),
        "delete_file_msg" to arrayOf("The MP3 will be removed from the app's storage.",
            "Plik MP3 zostanie usunięty z pamięci aplikacji."),
        "file_deleted" to arrayOf("File deleted.", "Plik usunięty."),
        "voice_missing" to arrayOf(
            "The voice sample for this file no longer exists.",
            "Próbka głosu dla tego pliku już nie istnieje."),
        "no_settings" to arrayOf(
            "This file has no stored generation settings.",
            "Ten plik nie ma zapisanych ustawień generacji."),
        "nothing_found" to arrayOf("Nothing found.", "Brak wyników."),
    )

    fun t(key: String, args: Map<String, String> = emptyMap()): String {
        val row = TABLE[key] ?: return key
        var s = if (lang == "pl" && row.size > 1) row[1] else row[0]
        if (args.isNotEmpty()) {
            for ((k, v) in args) s = s.replace("{$k}", v)
        }
        return s
    }
}

package com.vayunmathur.keyboard.util

/**
 * The catalogue of layouts the user can enable in settings.
 *
 * Each non-Latin layout carries its script's *whole* alphabet across the three rows (plus
 * long-press alternates for the few letters that traditionally live on a punctuation key,
 * like Russian `ё` or Ukrainian `ґ`) — a layout you cannot write the language with would be
 * worse than not offering it. Scripts that need a composition engine rather than a layout
 * (Korean jamo, Japanese kana-kanji, Chinese pinyin) are deliberately absent.
 *
 * Layout data follows the standard national layout for each language, so muscle memory from
 * a physical keyboard carries over.
 */
object KeyboardLayouts {

    /** Used when nothing is stored yet, and as the fallback for an unknown persisted id. */
    val DEFAULT: KeyboardLayout by lazy { ALL.first() }

    fun byId(id: String): KeyboardLayout? = byId[id]

    /**
     * Every layout, in picker order: Latin first (most users), then Cyrillic, then the
     * remaining scripts.
     */
    val ALL: List<KeyboardLayout> by lazy {
        listOf(
            // --- Latin ---
            KeyboardLayout(
                id = "en_qwerty",
                name = "English",
                description = "English · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = Layouts.LATIN_ALTERNATES,
                englishDictionary = true,
            ),
            KeyboardLayout(
                id = "en_dvorak",
                name = "English (Dvorak)",
                description = "English · Dvorak",
                rows = listOf("',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz"),
                alternates = Layouts.LATIN_ALTERNATES,
                englishDictionary = true,
            ),
            KeyboardLayout(
                id = "en_colemak",
                name = "English (Colemak)",
                description = "English · Colemak",
                rows = listOf("qwfpgjluy;", "arstdhneio", "zxcvbkm,./"),
                alternates = Layouts.LATIN_ALTERNATES,
                englishDictionary = true,
            ),
            KeyboardLayout(
                id = "en_workman",
                name = "English (Workman)",
                description = "English · Workman",
                rows = listOf("qdrwbjfup;", "ashtgyneoi", "zxmcvkl,./"),
                alternates = Layouts.LATIN_ALTERNATES,
                englishDictionary = true,
            ),
            KeyboardLayout(
                id = "de_qwertz",
                name = "Deutsch",
                description = "German · QWERTZ",
                rows = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
                alternates = latin('s' to "ß", 'e' to "éèêë", 'a' to "äàáâã", 'o' to "öòóôø", 'u' to "üùúû"),
            ),
            KeyboardLayout(
                id = "fr_azerty",
                name = "Français",
                description = "French · AZERTY",
                rows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
                alternates = latin(
                    'e' to "éèêë", 'a' to "àâáäã", 'u' to "ùûúü", 'i' to "îïíì",
                    'o' to "ôœòóö", 'c' to "çć", 'y' to "ÿ",
                ),
            ),
            KeyboardLayout(
                id = "es_qwerty",
                name = "Español",
                description = "Spanish · QWERTY",
                rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
                alternates = latin(
                    'a' to "áàâä", 'e' to "éèêë", 'i' to "íìîï", 'o' to "óòôö", 'u' to "úüùû",
                ),
            ),
            KeyboardLayout(
                id = "pt_qwerty",
                name = "Português",
                description = "Portuguese · QWERTY",
                rows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
                alternates = latin(
                    'a' to "ãáàâä", 'e' to "éêèë", 'i' to "íìîï", 'o' to "óõôòö", 'u' to "úùûü",
                ),
            ),
            KeyboardLayout(
                id = "it_qwerty",
                name = "Italiano",
                description = "Italian · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "àáâä", 'e' to "èéêë", 'i' to "ìíîï", 'o' to "òóôö", 'u' to "ùúûü",
                ),
            ),
            KeyboardLayout(
                id = "sv_qwerty",
                name = "Svenska",
                description = "Swedish / Finnish · QWERTY",
                rows = listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm"),
                alternates = latin('a' to "åäàá", 'o' to "öø", 'e' to "é", 's' to "š", 'z' to "ž"),
            ),
            KeyboardLayout(
                id = "da_qwerty",
                name = "Dansk / Norsk",
                description = "Danish / Norwegian · QWERTY",
                rows = listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm"),
                alternates = latin('a' to "æåàá", 'o' to "øöô", 'e' to "éë"),
            ),
            KeyboardLayout(
                id = "pl_qwerty",
                name = "Polski",
                description = "Polish · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "ąàáâä", 'c' to "ćç", 'e' to "ęéèê", 'l' to "ł", 'n' to "ńñ",
                    'o' to "óòôö", 's' to "ś", 'z' to "żź",
                ),
            ),
            KeyboardLayout(
                id = "cs_qwertz",
                name = "Čeština",
                description = "Czech · QWERTZ",
                rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
                alternates = latin(
                    'a' to "á", 'c' to "č", 'd' to "ď", 'e' to "éě", 'i' to "í", 'n' to "ň",
                    'o' to "ó", 'r' to "ř", 's' to "š", 't' to "ť", 'u' to "úů", 'y' to "ý", 'z' to "ž",
                ),
            ),
            KeyboardLayout(
                id = "sk_qwertz",
                name = "Slovenčina",
                description = "Slovak · QWERTZ",
                rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
                alternates = latin(
                    'a' to "áä", 'c' to "č", 'd' to "ď", 'e' to "é", 'i' to "í", 'l' to "ľĺ",
                    'n' to "ň", 'o' to "óô", 'r' to "ŕ", 's' to "š", 't' to "ť", 'u' to "ú",
                    'y' to "ý", 'z' to "ž",
                ),
            ),
            KeyboardLayout(
                id = "hu_qwertz",
                name = "Magyar",
                description = "Hungarian · QWERTZ",
                rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
                alternates = latin('a' to "á", 'e' to "é", 'i' to "í", 'o' to "óöő", 'u' to "úüű"),
            ),
            KeyboardLayout(
                id = "ro_qwerty",
                name = "Română",
                description = "Romanian · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('a' to "ăâà", 'i' to "î", 's' to "ș", 't' to "ț"),
            ),
            KeyboardLayout(
                id = "hr_qwertz",
                name = "Hrvatski / Srpski",
                description = "Croatian / Serbian (Latin) · QWERTZ",
                rows = listOf("qwertzuiopšđ", "asdfghjklčćž", "yxcvbnm"),
                alternates = latin('c' to "čć", 's' to "š", 'z' to "ž", 'd' to "đ"),
            ),
            KeyboardLayout(
                id = "tr_q",
                name = "Türkçe (Q)",
                description = "Turkish · Q-klavye",
                rows = listOf("qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç"),
                // Shift has to be spelled out: Turkish upper-cases i to İ and ı to I, which
                // Char.uppercaseChar() (locale-independent) gets wrong for the dotted pair.
                shiftedRows = listOf("QWERTYUIOPĞÜ", "ASDFGHJKLŞİ", "ZXCVBNMÖÇ"),
                alternates = latin('a' to "â", 'i' to "î", 'u' to "û", 'o' to "ö", 'g' to "ğ"),
            ),
            KeyboardLayout(
                id = "tr_f",
                name = "Türkçe (F)",
                description = "Turkish · F-klavye",
                rows = listOf("fgğıodrnhpqw", "uieaütkmlyşx", "jövcçzsb"),
                shiftedRows = listOf("FGĞIODRNHPQW", "UİEAÜTKMLYŞX", "JÖVCÇZSB"),
                alternates = latin('a' to "â", 'i' to "î", 'u' to "û"),
            ),
            KeyboardLayout(
                id = "vi_qwerty",
                name = "Tiếng Việt",
                description = "Vietnamese · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "ăâáàảãạ", 'd' to "đ", 'e' to "êéèẻẽẹ", 'i' to "íìỉĩị",
                    'o' to "ôơóòỏõọ", 'u' to "ưúùủũụ", 'y' to "ýỳỷỹỵ",
                ),
            ),

            // --- Cyrillic ---
            KeyboardLayout(
                id = "ru",
                name = "Русский",
                description = "Russian · ЙЦУКЕН",
                rows = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё", 'ь' to "ъ", 'и' to "й"),
            ),
            KeyboardLayout(
                id = "uk",
                name = "Українська",
                description = "Ukrainian · ЙЦУКЕН",
                rows = listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю"),
                alternates = mapOf('г' to "ґ", 'і' to "ї", 'е' to "є", 'ь' to "'"),
            ),
            KeyboardLayout(
                id = "be",
                name = "Беларуская",
                description = "Belarusian · ЙЦУКЕН",
                rows = listOf("йцукенгшўзх'", "фывапролджэ", "ячсмітьбю"),
                alternates = mapOf('е' to "ё", 'у' to "ў", 'і' to "'"),
            ),
            KeyboardLayout(
                id = "bg",
                name = "Български",
                description = "Bulgarian · phonetic",
                rows = listOf("явертъуиопч", "асдфгхйклшщ", "зьцжбнмю"),
            ),
            KeyboardLayout(
                id = "sr",
                name = "Српски",
                description = "Serbian (Cyrillic) · ЈЦУКЕН",
                rows = listOf("љњертзуиопшђ", "асдфгхјклчћж", "џцвбнм"),
            ),
            KeyboardLayout(
                id = "mk",
                name = "Македонски",
                description = "Macedonian · ЈЦУКЕН",
                rows = listOf("љњертѕуиопшѓ", "асдфгхјклчќж", "зџцвбнм"),
            ),

            // --- Other scripts ---
            KeyboardLayout(
                id = "el",
                name = "Ελληνικά",
                description = "Greek",
                rows = listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ"),
                alternates = mapOf(
                    'α' to "ά", 'ε' to "έ", 'η' to "ή", 'ι' to "ίϊΐ", 'ο' to "ό",
                    'υ' to "ύϋΰ", 'ω' to "ώ", 'σ' to "ς",
                ),
            ),
            KeyboardLayout(
                id = "he",
                name = "עברית",
                description = "Hebrew",
                rows = listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
            ),
            KeyboardLayout(
                id = "ar",
                name = "العربية",
                description = "Arabic",
                rows = listOf("ضصثقفغعهخحجد", "شسيبلاتنمكط", "ئءؤرىةوزظذ"),
                alternates = mapOf('ا' to "أإآ", 'ه' to "ة", 'و' to "ؤ", 'ي' to "ى", 'ء' to "ئؤأإ"),
            ),
            KeyboardLayout(
                id = "fa",
                name = "فارسی",
                description = "Persian",
                rows = listOf("ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "ظطزرذدپوژ"),
                alternates = mapOf('ا' to "آأإ", 'ه' to "ة", 'و' to "ؤ", 'ی' to "ئي", 'ک' to "ك"),
            ),
            KeyboardLayout(
                id = "hi_inscript",
                name = "हिन्दी",
                description = "Hindi / Marathi · InScript",
                rows = listOf("ौैाीूबहगदजड़", "ोे्िुपरकतचट", "ॉंमनवलस,.य"),
                shiftedRows = listOf("औऐआईऊभङघधझढञ", "ओएअइउफऱखथछठ", "ऑँणऩऴळशष।\u095F"),
            ),
            KeyboardLayout(
                id = "th",
                name = "ไทย",
                description = "Thai · Kedmanee",
                rows = listOf("ๆไำพะัีรนยบล", "ฟหกดเ้่าสวง", "ผปแอิืทมใฝ"),
                shiftedRows = listOf("๐\"ฎฑธํ๊ณฯญฐ,", "ฤฆฏโฌ็๋ษศซ.", "()ฉฮฺ์?ฒฬฦ"),
            ),
            KeyboardLayout(
                id = "ka",
                name = "ქართული",
                description = "Georgian",
                rows = listOf("ქწერტყუიოპ", "ასდფგჰჯკლ", "ზხცვბნმ"),
                // Mkhedruli has no case, so shift is the standard second layer that carries
                // the seven letters which do not fit on the base rows.
                shiftedRows = listOf("ქჭეღთყუიოპ", "აშდფგჰჟკლ", "ძხჩვბნმ"),
            ),
        )
    }

    private val byId: Map<String, KeyboardLayout> by lazy { ALL.associateBy { it.id } }

    /** Latin alternates plus this language's own, which take precedence. */
    private fun latin(vararg extra: Pair<Char, String>): Map<Char, String> =
        Layouts.LATIN_ALTERNATES + extra.toMap()
}

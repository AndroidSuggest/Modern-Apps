package com.vayunmathur.keyboard.util

/**
 * The catalogue of layouts the user can enable in settings.
 *
 * The rule every entry keeps is that you can write the language with it: each layout carries
 * its script's whole alphabet across its rows, its shift layer and its long-press alternates.
 * Where a letter would not fit that is checked against Unicode rather than assumed.
 *
 * Where a standard national layout exists and is known, the rows reproduce it, so muscle memory
 * from a physical keyboard carries over. Where it is not — the scripts marked "alphabetical" —
 * the rows are the script's alphabet in order, split across a base and a shift layer: learnable,
 * complete, and honest about what it is. Those are the first ones to replace as speakers of
 * those languages report what the real fingering should be.
 *
 * Korean, Chinese, Japanese and Ethiopic are typed through the composition engines in
 * [com.vayunmathur.keyboard.ime.Composer]: for those the rows are only half the story, since
 * what a key produces depends on the keys around it.
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
                id = "az_qwerty",
                name = "Azərbaycan",
                description = "Azerbaijani · QWERTY",
                rows = listOf("qüertyuiopöğ", "asdfghjklıə", "zxcvbnmçş"),
                // Like Turkish, the dotted and dotless i do not upper-case the way the
                // locale-independent Char.uppercaseChar() thinks they do.
                shiftedRows = listOf("QÜERTYUİOPÖĞ", "ASDFGHJKLIƏ", "ZXCVBNMÇŞ"),
                alternates = mapOf('a' to "â", 'i' to "ı", 'u' to "ü", 'o' to "ö", 'e' to "ə"),
            ),
            KeyboardLayout(
                id = "et_qwerty",
                name = "Eesti",
                description = "Estonian · QWERTY",
                rows = listOf("qwertyuiopüõ", "asdfghjklöä", "zxcvbnm"),
                alternates = latin('s' to "š", 'z' to "ž", 'o' to "õö", 'a' to "ä"),
            ),
            KeyboardLayout(
                id = "lt_qwerty",
                name = "Lietuvių",
                description = "Lithuanian · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "ą", 'c' to "č", 'e' to "ęė", 'i' to "įy", 's' to "š",
                    'u' to "ųū", 'z' to "ž",
                ),
            ),
            KeyboardLayout(
                id = "lv_qwerty",
                name = "Latviešu",
                description = "Latvian · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "ā", 'c' to "č", 'e' to "ē", 'g' to "ģ", 'i' to "ī", 'k' to "ķ",
                    'l' to "ļ", 'n' to "ņ", 's' to "š", 'u' to "ū", 'z' to "ž",
                ),
            ),
            KeyboardLayout(
                id = "is_qwerty",
                name = "Íslenska",
                description = "Icelandic · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "áæ", 'd' to "ð", 'e' to "é", 'i' to "í", 'o' to "óö",
                    't' to "þ", 'u' to "ú", 'y' to "ý",
                ),
            ),
            KeyboardLayout(
                id = "ca_qwerty",
                name = "Català",
                description = "Catalan · QWERTY",
                rows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
                alternates = latin(
                    'a' to "àá", 'e' to "èé", 'i' to "íï", 'o' to "òó", 'u' to "úü", 'c' to "ç",
                ),
            ),
            KeyboardLayout(
                id = "sq_qwerty",
                name = "Shqip",
                description = "Albanian · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('e' to "ë", 'c' to "ç"),
            ),
            KeyboardLayout(
                id = "mt_qwerty",
                name = "Malti",
                description = "Maltese · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('c' to "ċ", 'g' to "ġ", 'h' to "ħ", 'z' to "ż", 'a' to "à"),
            ),
            KeyboardLayout(
                id = "cy_qwerty",
                name = "Cymraeg",
                description = "Welsh · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "âáàä", 'e' to "êéèë", 'i' to "îíìï", 'o' to "ôóòö",
                    'u' to "ûúùü", 'w' to "ŵẃẁẅ", 'y' to "ŷýỳÿ",
                ),
            ),
            KeyboardLayout(
                id = "ga_qwerty",
                name = "Gaeilge",
                description = "Irish · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "ú"),
            ),
            KeyboardLayout(
                id = "eo_qwerty",
                name = "Esperanto",
                description = "Esperanto · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'c' to "ĉ", 'g' to "ĝ", 'h' to "ĥ", 'j' to "ĵ", 's' to "ŝ", 'u' to "ŭ",
                ),
            ),
            KeyboardLayout(
                id = "id_qwerty",
                name = "Indonesia / Melayu",
                description = "Indonesian / Malay · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = Layouts.LATIN_ALTERNATES,
            ),
            KeyboardLayout(
                id = "fil_qwerty",
                name = "Filipino",
                description = "Filipino · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('n' to "ñ", 'a' to "áàä", 'e' to "éè", 'o' to "óò"),
            ),
            KeyboardLayout(
                id = "sw_qwerty",
                name = "Kiswahili",
                description = "Swahili · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = Layouts.LATIN_ALTERNATES,
            ),
            KeyboardLayout(
                id = "af_qwerty",
                name = "Afrikaans",
                description = "Afrikaans · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('e' to "êëé", 'o' to "ôö", 'i' to "îï", 'u' to "ûü", 'a' to "áà"),
            ),
            KeyboardLayout(
                id = "ha_qwerty",
                name = "Hausa",
                description = "Hausa · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('b' to "ɓ", 'd' to "ɗ", 'k' to "ƙ", 'y' to "ƴ"),
            ),
            KeyboardLayout(
                id = "yo_qwerty",
                name = "Yorùbá",
                description = "Yoruba · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "àá", 'e' to "ẹèé", 'i' to "ìí", 'o' to "ọòó", 'u' to "ùú",
                    's' to "ṣ", 'n' to "ń",
                ),
            ),
            KeyboardLayout(
                id = "ig_qwerty",
                name = "Igbo",
                description = "Igbo · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('i' to "ị", 'o' to "ọ", 'u' to "ụ", 'n' to "ṅ", 'a' to "á"),
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
            // InScript, the Indian government standard: one arrangement of *sound* positions
            // shared by every Indic script, so a Bengali typist and a Gujarati typist use the
            // same fingering. The rows below are Devanagari's mapped into each script's
            // Unicode block (the blocks are deliberately parallel), with the positions whose
            // letter that script does not have removed rather than left as unassigned
            // codepoints. Shift is the second layer, as in Devanagari: no Indic script has
            // case.
            KeyboardLayout(
                id = "bn_inscript",
                name = "বাংলা",
                description = "Bengali · InScript",
                rows = listOf("ৌৈাীূবহগদজড়", "োে্িুপরকতচট", "ংমনলস,.য"),
                shiftedRows = listOf("ঔঐআঈঊভঙঘধঝঢঞ", "ওএঅইউফরখথছঠ", "ঁণনলশষ.য়"),
            ),
            KeyboardLayout(
                id = "pa_inscript",
                name = "ਪੰਜਾਬੀ",
                description = "Punjabi (Gurmukhi) · InScript",
                rows = listOf("ੌੈਾੀੂਬਹਗਦਜਡ਼", "ੋੇ੍ਿੁਪਰਕਤਚਟ", "ਂਮਨਵਲਸ,.ਯ"),
                shiftedRows = listOf("ਔਐਆਈਊਭਙਘਧਝਢਞ", "ਓਏਅਇਉਫਰਖਥਛਠ", "ਁਣਨਵਲ਼ਸ਼,.ਯ"),
            ),
            KeyboardLayout(
                id = "gu_inscript",
                name = "ગુજરાતી",
                description = "Gujarati · InScript",
                rows = listOf("ૌૈાીૂબહગદજડ઼", "ોે્િુપરકતચટ", "ૉંમનવલસ,.ય"),
                shiftedRows = listOf("ઔઐઆઈઊભઙઘધઝઢઞ", "ઓએઅઇઉફરખથછઠ", "ઑઁણનવળશષ.ય"),
            ),
            KeyboardLayout(
                id = "or_inscript",
                name = "ଓଡ଼ିଆ",
                description = "Odia · InScript",
                rows = listOf("ୌୈାୀୂବହଗଦଜଡ଼", "ୋେ୍ିୁପରକତଚଟ", "ଂମନଵଲସ,.ଯ"),
                shiftedRows = listOf("ଔଐଆଈଊଭଙଘଧଝଢଞ", "ଓଏଅଇଉଫରଖଥଛଠ", "ଁଣନଵଳଶଷ.ୟ"),
            ),

            KeyboardLayout(
                id = "kk",
                name = "Қазақша",
                description = "Kazakh · ЙЦУКЕН",
                // Kazakh's nine extra letters live on the digit row of the physical layout,
                // so this is a four-row layout and gives up the keyboard's own digit row.
                rows = listOf("әіңғүұқөһ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё", 'и' to "й"),
            ),

            KeyboardLayout(
                id = "th",
                name = "ไทย",
                description = "Thai · Kedmanee",
                rows = listOf("ๆไำพะัีรนยบลฃ", "ฟหกดเ้่าสวง", "ผปแอิืทมใฝ"),
                shiftedRows = listOf("๐\"ฎฑธํ๊ณฯญฐ,ฅ", "ฤฆฏโฌ็๋ษศซ.", "()ฉฮฺ์?ฒฬฦ"),
            ),

            // --- Composed scripts (see com.vayunmathur.keyboard.ime.Composer) ---
            KeyboardLayout(
                id = "ko_dubeolsik",
                name = "한국어",
                description = "Korean · 두벌식 (Dubeolsik)",
                // The standard 2-set layout: consonants left, vowels right. Shift is the
                // tense consonants ㅃㅉㄸㄲㅆ and the two extra vowels ㅒㅖ.
                rows = listOf("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ"),
                shiftedRows = listOf("ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ", "ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", "ㅋㅌㅊㅍㅠㅜㅡ"),
                composer = ComposerKind.HANGUL,
            ),
            KeyboardLayout(
                id = "zh_pinyin",
                name = "中文 (拼音)",
                description = "Chinese · Pinyin, simplified",
                rows = Layouts.LETTER_ROWS,
                composer = ComposerKind.PINYIN_SIMPLIFIED,
                comma = "，",
                period = "。",
            ),
            KeyboardLayout(
                id = "zh_pinyin_tc",
                name = "中文 (拼音・繁)",
                description = "Chinese · Pinyin, traditional",
                rows = Layouts.LETTER_ROWS,
                composer = ComposerKind.PINYIN_TRADITIONAL,
                comma = "，",
                period = "。",
            ),
            KeyboardLayout(
                id = "zh_bopomofo",
                name = "中文 (注音)",
                description = "Chinese · Bopomofo 大千, traditional",
                // The 大千 layout is four rows: it takes the digit row, so the keyboard
                // gives up its own. Tone marks end a syllable and take the top candidate.
                rows = listOf(
                    "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ",
                    "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
                    "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",
                    "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",
                ),
                composer = ComposerKind.BOPOMOFO,
                comma = "，",
                period = "。",
            ),
            KeyboardLayout(
                id = "ja_romaji",
                name = "日本語 (ローマ字)",
                description = "Japanese · romaji to kana; shift types katakana",
                rows = Layouts.LETTER_ROWS,
                composer = ComposerKind.ROMAJI,
                comma = "、",
                period = "。",
            ),
            KeyboardLayout(
                id = "ja_kana",
                name = "日本語 (かな)",
                description = "Japanese · JIS kana",
                // JIS kana, four rows; shift is the small kana, as on a JIS keyboard.
                rows = listOf(
                    "ぬふあうえおやゆよわほへ",
                    "たていすかんなにらせ゛゜",
                    "ちとしはきくまのりれけむ",
                    "つさそひこみもねるめろ",
                ),
                shiftedRows = listOf(
                    "ぬふぁぅぇぉゃゅょをーへ",
                    "たてぃすかんなにらせ゛゜",
                    "ちとしはきくまのりれけむ",
                    "っさそひこみもね、。・",
                ),
                composer = ComposerKind.KANA,
                comma = "、",
                period = "。",
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

            KeyboardLayout(
                id = "ta_inscript",
                name = "தமிழ்",
                description = "Tamil · InScript",
                rows = listOf("ௌைாீூஹஜ", "ோே்ிுபரகதசட", "ஂமநவலஸ,.ய"),
                shiftedRows = listOf("ஔஐஆஈஊஙஜ", "ஓஏஅஇஉபறகதசட", "ஂணனழளஶஷ.ய"),
                alternates = mapOf('ே' to "ெஎ", 'ோ' to "ொஒ", 'ஂ' to "ஃ", 'ந' to "ஞ", 'ௌ' to "ௗ"),
            ),
            KeyboardLayout(
                id = "te_inscript",
                name = "తెలుగు",
                description = "Telugu · InScript",
                rows = listOf("ౌైాీూబహగదజడ఼", "ోే్ిుపరకతచట", "ంమనవలస,.య"),
                shiftedRows = listOf("ఔఐఆఈఊభఙఘధఝఢఞ", "ఓఏఅఇఉఫఱఖథఛఠ", "ఁణనఴళశష.య"),
                alternates = mapOf('ే' to "ెఎ", 'ో' to "ొఒ", 'ం' to "ఃఀ", 'ర' to "ఋృౠ", 'ల' to "ఌౢ", 'చ' to "ౘ", 'జ' to "ౙ", 'ా' to "ఽ"),
            ),
            KeyboardLayout(
                id = "kn_inscript",
                name = "ಕನ್ನಡ",
                description = "Kannada · InScript",
                rows = listOf("ೌೈಾೀೂಬಹಗದಜಡ಼", "ೋೇ್ಿುಪರಕತಚಟ", "ಂಮನವಲಸ,.ಯ"),
                shiftedRows = listOf("ಔಐಆಈಊಭಙಘಧಝಢಞ", "ಓಏಅಇಉಫಱಖಥಛಠ", "ಁಣನವಳಶಷ.ಯ"),
                alternates = mapOf('ೇ' to "ೆಎ", 'ೋ' to "ೊಒ", 'ಂ' to "ಃ", 'ರ' to "ಋೃೠ", 'ಲ' to "ಌೞ", 'ಾ' to "ಽ"),
            ),
            KeyboardLayout(
                id = "ml_inscript",
                name = "മലയാളം",
                description = "Malayalam · InScript",
                rows = listOf("ൌൈാീൂബഹഗദജഡ഼", "ോേ്ിുപരകതചട", "ംമനവലസ,.യ"),
                shiftedRows = listOf("ഔഐആഈഊഭങഘധഝഢഞ", "ഓഏഅഇഉഫറഖഥഛഠ", "ഁണഩഴളശഷ.യ"),
                alternates = mapOf('േ' to "െഎ", 'ോ' to "ൊഒ", 'ം' to "ഃ", 'ര' to "ഋൃർ", 'ല' to "ഌൽൾ", 'ന' to "ൻൺ", 'ക' to "ൿ", 'ാ' to "ഽ", 'ൌ' to "ൗ"),
            ),
            KeyboardLayout(
                id = "hy",
                name = "Հայերեն",
                description = "Armenian · alphabetical",
                rows = listOf("աբգդեզէըթժ", "իլխծկհձղճմ", "յնշոչպջռսվ", "տրցւփքօֆև"),
            ),
            KeyboardLayout(
                id = "lo",
                name = "ລາວ",
                description = "Lao · alphabetical",
                rows = listOf("ກຂຄງຈສຊຍດ", "ຕຖທນບປຜຝພ", "ຟມຢຣລວຫອຮ"),
                shiftedRows = listOf("ະັາຳິີຶືຸ", "ູົຼຽເແໂໃໄ", "່້໊໋ໍ໌ໆໜໝ"),
            ),
            KeyboardLayout(
                id = "km",
                name = "ភាសាខ្មែរ",
                description = "Khmer · alphabetical",
                rows = listOf("កខគឃងចឆជឈញដ", "ឋឌឍណតថទធនបផ", "ពភមយរលវឝឞសហ", "ឡអឣឤឥឦឧឨឩ"),
                shiftedRows = listOf("ឪឫឬឭឮឯឰឱឲឳ឴", "឵ាិីឹឺុូួើឿ", "ៀេែៃោៅំះៈ៉៊", "់៌៍៎៏័៑្៓"),
            ),
            KeyboardLayout(
                id = "si",
                name = "සිංහල",
                description = "Sinhala · alphabetical",
                rows = listOf("අආඇඈඉඊඋඌඍඎ", "ඏඐඑඒඓඔඕඖකඛ", "ගඝඞඟචඡජඣඤඥ", "ඦටඨඩඪණඬතථ"),
                shiftedRows = listOf("දධනඳපඵබභමඹ", "යරලවශෂසහළෆ", "්ාැෑිීුූෘෙ", "ේෛොෝෞෟෲෳං"),
            ),
            KeyboardLayout(
                id = "my",
                name = "မြန်မာ",
                description = "Burmese · alphabetical",
                rows = listOf("ကခဂဃငစဆဇ", "ဈဉညဋဌဍဎဏ", "တထဒဓနပဖဗ", "ဘမယရလဝသဟ"),
                shiftedRows = listOf("ဠအဢဣဤဥဦဧ", "ဨဩဪါာိီု", "ူေဲဳဴဵံ့", "း္်ျြွှ။"),
            ),
            KeyboardLayout(
                id = "bo",
                name = "བོད་ཡིག",
                description = "Tibetan · alphabetical",
                rows = listOf("ཀཁགགྷངཅཆཇ", "ཉཊཋཌཌྷཎཏཐ", "དདྷནཔཕབབྷམ", "ཙཚཛཛྷཝཞཟའ"),
                shiftedRows = listOf("ཡརལཤཥསཧཨ", "ཀྵཪཫཬཱཱིིུ", "ཱུྲྀཷླྀཹེཻོ", "ཽཾཿ྄ཱྀྀྂྃ"),
            ),
            KeyboardLayout(
                id = "dv",
                name = "ދިވެހި",
                description = "Dhivehi (Thaana) · alphabetical",
                rows = listOf("ހށނރބޅކ", "އވމފދތލ", "ގޏސޑޒޓޔ", "ޕޖޗޘ"),
                shiftedRows = listOf("ޙޚޛޜޝޞޟ", "ޠޡޢޣޤޥަ", "ާިީުޫެޭ", "ޮޯްޱ"),
            ),
            KeyboardLayout(
                id = "chr",
                name = "ᏣᎳᎩ",
                description = "Cherokee · syllabary",
                rows = listOf("ᎠᎡᎢᎣᎤᎥᎦᎧᎨᎩᎪ", "ᎫᎬᎭᎮᎯᎰᎱᎲᎳᎴᎵ", "ᎶᎷᎸᎹᎺᎻᎼᎽᎾᎿᏀ", "ᏁᏂᏃᏄᏅᏆᏇᏈᏉᏊ"),
                shiftedRows = listOf("ᏋᏌᏍᏎᏏᏐᏑᏒᏓᏔᏕ", "ᏖᏗᏘᏙᏚᏛᏜᏝᏞᏟᏠ", "ᏡᏢᏣᏤᏥᏦᏧᏨᏩᏪᏫ", "ᏬᏭᏮᏯᏰᏱᏲᏳᏴᏵ"),
            ),
            KeyboardLayout(
                id = "am",
                name = "አማርኛ / ትግርኛ",
                description = "Amharic / Tigrinya · consonant then vowel",
                rows = listOf("ሀለሐመሠረሰሸቀቐበ", "ቨተቸኀነኘከኸዐዘዠ", "የደዸጀገጘጠጨጰጸፀ", "ፈፐአኡኢኣኤእኦ"),
                composer = ComposerKind.ETHIOPIC,
            ),
            KeyboardLayout(
                id = "ur",
                name = "اردو",
                description = "Urdu · Pakistan",
                rows = listOf("طصھدٹپتبجح", "مورنلہاکی", "قفےسشغع"),
                alternates = mapOf('ت' to "ث", 'ج' to "چ", 'ح' to "خ", 'د' to "ڈذ", 'ر' to "ڑزژ", 'ص' to "ض", 'ط' to "ظ", 'ک' to "گ", 'ن' to "ں", 'ا' to "آأء", 'و' to "ؤ", 'ی' to "ئ", 'ہ' to "ھۃ"),
            ),
            KeyboardLayout(
                id = "ps",
                name = "پښتو",
                description = "Pashto · Afghanistan",
                rows = listOf("ضصثقفغعهخحجچ", "شسیبلاتنمکگ", "ظطزرذدپوژ"),
                alternates = mapOf('ت' to "ټ", 'د' to "ډ", 'ر' to "ړ", 'ز' to "ږ", 'س' to "ښ", 'ک' to "ګ", 'ن' to "ڼ", 'ی' to "ۍېئ", 'ا' to "آأ", 'ه' to "ة", 'چ' to "څ", 'ج' to "ځ", 'و' to "ؤ"),
            ),
            KeyboardLayout(
                id = "tg",
                name = "Тоҷикӣ",
                description = "Tajik · ЙЦУКЕН",
                rows = listOf("ғқӣӯҳҷ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),
            KeyboardLayout(
                id = "ky",
                name = "Кыргызча",
                description = "Kyrgyz · ЙЦУКЕН",
                rows = listOf("ңөү", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),
            KeyboardLayout(
                id = "tt",
                name = "Татарча",
                description = "Tatar · ЙЦУКЕН",
                rows = listOf("әөүҗңһ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),
            KeyboardLayout(
                id = "ba",
                name = "Башҡортса",
                description = "Bashkir · ЙЦУКЕН",
                rows = listOf("әөүғҙҫңһҡ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),
            KeyboardLayout(
                id = "mn",
                name = "Монгол",
                description = "Mongolian · ЙЦУКЕН",
                rows = listOf("өү", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),
            KeyboardLayout(
                id = "cv",
                name = "Чӑвашла",
                description = "Chuvash · ЙЦУКЕН",
                rows = listOf("ӑӗҫӳ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"),
                alternates = mapOf('е' to "ё"),
            ),

            // --- Devanagari and Bengali variants: the same InScript arrangement, listed
            // separately because a Nepali speaker looks for नेपाली, not हिन्दी. ---
            KeyboardLayout(
                id = "ne_inscript",
                name = "नेपाली",
                description = "Nepali · InScript",
                rows = listOf("ौैाीूबहगदजड़", "ोे्िुपरकतचट", "ॉंमनवलस,.य"),
                shiftedRows = listOf("औऐआईऊभङघधझढञ", "ओएअइउफऱखथछठ", "ऑँणऩऴळशष।\u095F"),
            ),
            KeyboardLayout(
                id = "mr_inscript",
                name = "मराठी",
                description = "Marathi · InScript",
                rows = listOf("ौैाीूबहगदजड़", "ोे्िुपरकतचट", "ॉंमनवलस,.य"),
                shiftedRows = listOf("औऐआईऊभङघधझढञ", "ओएअइउफऱखथछठ", "ऑँणऩऴळशष।\u095F"),
            ),
            KeyboardLayout(
                id = "sa_inscript",
                name = "संस्कृतम्",
                description = "Sanskrit · InScript",
                rows = listOf("ौैाीूबहगदजड़", "ोे्िुपरकतचट", "ॉंमनवलस,.य"),
                shiftedRows = listOf("औऐआईऊभङघधझढञ", "ओएअइउफऱखथछठ", "ऑँणऩऴळशष।\u095F"),
            ),
            KeyboardLayout(
                id = "as_inscript",
                name = "অসমীয়া",
                description = "Assamese · InScript",
                rows = listOf("ৌৈাীূবহগদজড়", "োে্িুপরকতচট", "ংমনলস,.য"),
                shiftedRows = listOf("ঔঐআঈঊভঙঘধঝঢঞ", "ওএঅইউফরখথছঠ", "ঁণনলশষ.য়"),
                // Assamese uses ৰ and ৱ where Bengali uses র and ব.
                alternates = mapOf('র' to "ৰ", 'ব' to "ৱ"),
            ),

            // --- More Latin languages. Plain QWERTY plus the letters that language adds,
            // which is what the phone keyboards for these languages actually are. ---
            KeyboardLayout(
                id = "sl_qwertz",
                name = "Slovenščina",
                description = "Slovenian · QWERTZ",
                rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
                alternates = latin('c' to "č", 's' to "š", 'z' to "ž"),
            ),
            KeyboardLayout(
                id = "eu_qwerty",
                name = "Euskara",
                description = "Basque · QWERTY",
                rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
                alternates = latin('u' to "ü", 'a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó"),
            ),
            KeyboardLayout(
                id = "gl_qwerty",
                name = "Galego",
                description = "Galician · QWERTY",
                rows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
                alternates = latin('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "úü"),
            ),
            KeyboardLayout(
                id = "lb_qwertz",
                name = "Lëtzebuergesch",
                description = "Luxembourgish · QWERTZ",
                rows = listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"),
                alternates = latin('e' to "ëé", 'a' to "äà", 'o' to "ö", 'u' to "ü"),
            ),
            KeyboardLayout(
                id = "gd_qwerty",
                name = "Gàidhlig",
                description = "Scottish Gaelic · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('a' to "à", 'e' to "è", 'i' to "ì", 'o' to "ò", 'u' to "ù"),
            ),
            KeyboardLayout(
                id = "br_qwerty",
                name = "Brezhoneg",
                description = "Breton · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('n' to "ñ", 'e' to "êé", 'u' to "ùü", 'a' to "â"),
            ),
            KeyboardLayout(
                id = "so_qwerty",
                name = "Soomaali",
                description = "Somali · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = Layouts.LATIN_ALTERNATES,
            ),
            KeyboardLayout(
                id = "zu_qwerty",
                name = "isiZulu / isiXhosa",
                description = "Zulu / Xhosa · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = Layouts.LATIN_ALTERNATES,
            ),
            KeyboardLayout(
                id = "uz_qwerty",
                name = "Oʻzbekcha",
                description = "Uzbek · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('o' to "\u02BB", 'g' to "\u02BB", 'c' to "ç", 's' to "ş"),
            ),
            KeyboardLayout(
                id = "tk_qwerty",
                name = "Türkmençe",
                description = "Turkmen · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin(
                    'a' to "ä", 'z' to "ž", 'n' to "ň", 'o' to "ö", 's' to "ş",
                    'u' to "ü", 'y' to "ý", 'c' to "ç",
                ),
            ),
            KeyboardLayout(
                id = "ku_qwerty",
                name = "Kurdî",
                description = "Kurdish (Kurmanji) · QWERTY",
                rows = Layouts.LETTER_ROWS,
                alternates = latin('c' to "ç", 'e' to "ê", 'i' to "î", 's' to "ş", 'u' to "û"),
            ),
        )
    }

    private val byId: Map<String, KeyboardLayout> by lazy { ALL.associateBy { it.id } }

    /** Latin alternates plus this language's own, which take precedence. */
    private fun latin(vararg extra: Pair<Char, String>): Map<Char, String> =
        Layouts.LATIN_ALTERNATES + extra.toMap()
}

package com.vayunmathur.flashcards.util

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.flashcards.data.flashcardsDbConfigs
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = flashcardsDbConfigs(this)

    override val extraFiles: List<File>
        get() = emptyList()
}

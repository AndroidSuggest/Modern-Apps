package com.vayunmathur.todo.platform

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.todo.data.todoDbConfigs
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = todoDbConfigs(this)

    override val extraFiles: List<File>
        get() = emptyList()
}

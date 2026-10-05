package com.vayunmathur.communicate.data.whatsapp

/**
 * Binary token tables (split for file length).
 */
internal object BinaryTokenTables {

    val doubleByteTokens: Array<Array<String>> =
        BinaryTokenTablesA.doubleByteTokensA + BinaryTokenTablesB.doubleByteTokensB
}

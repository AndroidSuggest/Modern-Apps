package com.vayunmathur.euicc.data

import kotlinx.serialization.Serializable

/**
 * Displayable subset of SGP.22 EUICCInfo1, deserialized from the JSON the native
 * core emits (see euicc/src/main/rust/src/es10.rs).
 */
@Serializable
data class EuiccInfo(
    /** SGP.22 spec version, e.g. "2.2.0". */
    val svn: String = "",
    /** GSMA CI public-key identifiers accepted for verification. */
    val ciPkIdListForVerification: List<String> = emptyList(),
    /** GSMA CI public-key identifiers the eUICC can sign under. */
    val ciPkIdListForSigning: List<String> = emptyList(),
)

package com.vayunmathur.euicc.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One installed eSIM profile, deserialized from the native GetProfilesInfo JSON. */
@Serializable
data class Profile(
    /** Raw ICCID octets as hex; the key passed back into profile operations. */
    val iccid: String = "",
    /** Human-readable ICCID digits. */
    val iccidDisplay: String = "",
    val isdpAid: String = "",
    /** "enabled", "disabled", or "unknown". */
    val state: String = "unknown",
    /** "operational", "test", "provisioning", or "unknown". */
    @SerialName("class") val profileClass: String = "unknown",
    val nickname: String = "",
    val serviceProvider: String = "",
    val name: String = "",
) {
    val isEnabled: Boolean get() = state == "enabled"

    /** Best label to show for this profile. */
    val displayName: String
        get() = nickname.ifBlank { name.ifBlank { serviceProvider.ifBlank { iccidDisplay } } }
}

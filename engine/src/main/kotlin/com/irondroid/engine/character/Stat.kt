package com.irondroid.engine.character

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The five stats of an Ironsworn character. */
@Serializable
enum class Stat {
    @SerialName("edge") EDGE,
    @SerialName("heart") HEART,
    @SerialName("iron") IRON,
    @SerialName("shadow") SHADOW,
    @SerialName("wits") WITS,
}

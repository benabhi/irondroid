package com.irondroid.engine.meters

/** The three condition meters of a character (RF-MOT-08). */
enum class MeterType {
    HEALTH,
    SPIRIT,
    SUPPLY,
}

/** One condition meter, from [MIN] to [MAX]. Immutable: every change returns a new value. */
data class Meter(val type: MeterType, val value: Int = MAX) {
    init {
        require(value in MIN..MAX) { "$type must be $MIN..$MAX, was $value" }
    }

    /** Adds [amount], stopping at [MAX]. */
    fun gain(amount: Int): Meter {
        require(amount >= 0) { "Gain cannot be negative, was $amount" }
        return copy(value = minOf(value + amount, MAX))
    }

    /**
     * Removes [amount], stopping at [MIN]. The part that does not fit below zero is reported in
     * [MeterLoss.overflow], for the suffer moves to resolve.
     */
    fun lose(amount: Int): MeterLoss {
        require(amount >= 0) { "Loss cannot be negative, was $amount" }
        val target = value - amount
        return MeterLoss(copy(value = maxOf(target, MIN)), amount, overflow = maxOf(MIN - target, 0))
    }

    companion object {
        const val MIN = 0
        const val MAX = 5
    }
}

/** Result of losing from a meter. */
data class MeterLoss(val meter: Meter, val amount: Int, val overflow: Int) {
    /** Supply lost and is at 0: the player must make the Out of Supply move. */
    val triggersOutOfSupply: Boolean
        get() = meter.type == MeterType.SUPPLY && amount > 0 && meter.value == Meter.MIN
}

/** Health, spirit and supply together, adjusted by type. */
data class Meters(
    val health: Meter = Meter(MeterType.HEALTH),
    val spirit: Meter = Meter(MeterType.SPIRIT),
    val supply: Meter = Meter(MeterType.SUPPLY),
) {
    init {
        require(health.type == MeterType.HEALTH && spirit.type == MeterType.SPIRIT && supply.type == MeterType.SUPPLY) {
            "Meters are in the wrong slots: $health, $spirit, $supply"
        }
    }

    operator fun get(type: MeterType): Meter = when (type) {
        MeterType.HEALTH -> health
        MeterType.SPIRIT -> spirit
        MeterType.SUPPLY -> supply
    }

    /** Same meters with [meter] replacing the one of its type. */
    fun with(meter: Meter): Meters = when (meter.type) {
        MeterType.HEALTH -> copy(health = meter)
        MeterType.SPIRIT -> copy(spirit = meter)
        MeterType.SUPPLY -> copy(supply = meter)
    }

    fun gain(type: MeterType, amount: Int): Meters = with(get(type).gain(amount))

    /** Loses [amount] from [type]; the [MeterLoss] tells whether a suffer move or Out of Supply follows. */
    fun lose(type: MeterType, amount: Int): Pair<Meters, MeterLoss> {
        val loss = get(type).lose(amount)
        return with(loss.meter) to loss
    }
}

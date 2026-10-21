package com.irondroid.data

import com.irondroid.data.entity.CharacterEntity
import com.irondroid.data.entity.PendingBonusEntity
import com.irondroid.data.entity.ProgressTrackEntity
import com.irondroid.engine.character.Character
import com.irondroid.engine.character.PendingBonus
import com.irondroid.engine.character.Stat
import com.irondroid.engine.debility.Debilities
import com.irondroid.engine.meters.Meter
import com.irondroid.engine.meters.MeterType
import com.irondroid.engine.meters.Meters
import com.irondroid.engine.momentum.Momentum
import com.irondroid.engine.progress.ProgressTrack

/** Converts between the engine's character sheet and its database rows. */
internal object CharacterMapper {
    /** Row for [sheet]; [id] is 0 for a character not saved yet. */
    fun toEntity(sheet: Character, campaignId: Long, name: String, id: Long = 0): CharacterEntity = CharacterEntity(
        id = id,
        campaignId = campaignId,
        name = name,
        edge = sheet.stats.getValue(Stat.EDGE),
        heart = sheet.stats.getValue(Stat.HEART),
        iron = sheet.stats.getValue(Stat.IRON),
        shadow = sheet.stats.getValue(Stat.SHADOW),
        wits = sheet.stats.getValue(Stat.WITS),
        health = sheet.meters.health.value,
        spirit = sheet.meters.spirit.value,
        supply = sheet.meters.supply.value,
        momentum = sheet.momentum.value,
        debilities = sheet.debilities.marked,
        experience = sheet.experience,
        isEnded = sheet.isEnded,
    )

    fun tracksToEntities(sheet: Character, characterId: Long): List<ProgressTrackEntity> =
        sheet.tracks.map { (trackId, track) ->
            ProgressTrackEntity(characterId, trackId, track.name, track.type, track.rank, track.ticks)
        }

    fun bonusesToEntities(sheet: Character, characterId: Long): List<PendingBonusEntity> =
        sheet.pendingBonuses.mapIndexed { position, bonus ->
            PendingBonusEntity(characterId, position, bonus.amount, bonus.move)
        }

    /** The engine sheet stored in [entity] and its tracks and bonuses (bonuses already in position order). */
    fun toSheet(
        entity: CharacterEntity,
        tracks: List<ProgressTrackEntity>,
        bonuses: List<PendingBonusEntity>,
    ): Character = Character(
        stats = mapOf(
            Stat.EDGE to entity.edge,
            Stat.HEART to entity.heart,
            Stat.IRON to entity.iron,
            Stat.SHADOW to entity.shadow,
            Stat.WITS to entity.wits,
        ),
        meters = Meters(
            Meter(MeterType.HEALTH, entity.health),
            Meter(MeterType.SPIRIT, entity.spirit),
            Meter(MeterType.SUPPLY, entity.supply),
        ),
        momentum = Momentum(entity.momentum, entity.debilities.size),
        debilities = Debilities(entity.debilities),
        tracks = tracks.associate { it.trackId to ProgressTrack(it.name, it.type, it.rank, it.ticks) },
        experience = entity.experience,
        pendingBonuses = bonuses.map { PendingBonus(it.amount, it.move) },
        isEnded = entity.isEnded,
    )
}
